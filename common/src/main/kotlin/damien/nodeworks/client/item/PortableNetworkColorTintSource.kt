package damien.nodeworks.client.item

import com.mojang.serialization.MapCodec
import damien.nodeworks.block.entity.BroadcastAntennaBlockEntity
import damien.nodeworks.item.BroadcastSourceKind
import damien.nodeworks.item.LinkCrystalItem
import damien.nodeworks.item.PortableInventoryTerminalItem
import damien.nodeworks.network.Connectable
import damien.nodeworks.network.NetworkSettingsRegistry
import net.minecraft.client.color.item.ItemTintSource
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.ItemStack
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Item tint source for the Portable Inventory Terminal's emissive layer. Returns the
 * ARGB color of the network the installed crystal is paired to.
 *
 * ## Resolution
 *
 * The fresh lookup chain (all client-side):
 *   1. Installed crystal + pairing data (kind + antenna pos + frequency).
 *   2. [BroadcastAntennaBlockEntity] at the paired position on the player's current
 *      [ClientLevel].
 *   3. Antenna's `detectSource()` points at an adjacent Network Controller.
 *   4. Controller's [Connectable.networkId].
 *   5. [NetworkSettingsRegistry.getColor].
 *
 * ## Cache
 *
 * A fresh lookup only succeeds when the player is in the paired dimension AND the
 * antenna's chunk is loaded. In every other state (cross-dim, antenna chunk unloaded,
 * antenna freshly unloaded during render) a hard fallback to neutral white would
 * make the Portable read as "disconnected" even though the menu still works fine
 * within the antenna's range. To avoid that misleading signal, each successful
 * lookup caches `frequencyId → color`, and a cross-dim/offline render falls back to
 * the cached value before giving up.
 *
 * The cache is keyed by `frequencyId` (the antenna's UUID) so colors stay tied to a
 * specific antenna even across world reloads. Entries are overwritten on the next
 * successful fresh lookup, so network-color edits propagate the instant the player
 * returns to the paired dimension.
 *
 * Called from the item model's `tints[1]` slot (see
 * `assets/nodeworks/items/portable_inventory_terminal.json`).
 */
@JvmRecord
data class PortableNetworkColorTintSource(private val unit: Unit = Unit) : ItemTintSource {
    override fun calculate(stack: ItemStack, level: ClientLevel?, entity: LivingEntity?): Int {
        if (level == null) return DEFAULT_COLOR

        val crystal = PortableInventoryTerminalItem.getInstalledCrystal(stack)
        if (crystal.isEmpty) return DEFAULT_COLOR

        val pairing = LinkCrystalItem.getPairingData(crystal) ?: return DEFAULT_COLOR
        if (pairing.kind != BroadcastSourceKind.NETWORK_CONTROLLER) return DEFAULT_COLOR

        // Try a fresh lookup first — picks up color changes immediately when the
        // player is in the right dimension with the antenna chunk loaded.
        val fresh = resolveFreshColor(pairing, level)
        if (fresh != null) {
            cache[pairing.frequencyId] = fresh
            return fresh
        }

        // Fall back to the last color we saw for this antenna. Covers cross-dim,
        // antenna chunk unloaded, and transient "frame between join and BE sync"
        // gaps — all states where the Portable is still logically linked.
        return cache[pairing.frequencyId] ?: DEFAULT_COLOR
    }

    private fun resolveFreshColor(pairing: LinkCrystalItem.Companion.PairingData, level: ClientLevel): Int? {
        if (pairing.dimension != level.dimension()) return null
        val antenna = level.getBlockEntity(pairing.pos) as? BroadcastAntennaBlockEntity ?: return null
        if (antenna.frequencyId != pairing.frequencyId) return null
        val source = antenna.detectSource() ?: return null
        if (source.first != BroadcastSourceKind.NETWORK_CONTROLLER) return null
        val controller = level.getBlockEntity(source.second) as? Connectable ?: return null
        val networkId = controller.networkId ?: return null
        // NetworkSettingsRegistry returns a packed RGB int; force alpha to 0xFF so the
        // tint is fully opaque (item-model tints are applied as ARGB to the texel).
        return 0xFF000000.toInt() or (NetworkSettingsRegistry.getColor(networkId) and 0xFFFFFF)
    }

    override fun type(): MapCodec<out ItemTintSource> = MAP_CODEC

    companion object {
        /** Neutral white — applies no visible hue shift to the emissive texture. */
        const val DEFAULT_COLOR: Int = 0xFFFFFFFF.toInt()

        /** `frequencyId → ARGB` cache. Written by the render thread on every
         *  successful fresh lookup; read by the same thread when a fresh lookup
         *  fails. `ConcurrentHashMap` guards against any future off-thread reads
         *  (item tooltip rendering, JEI previews) without requiring a rework. */
        private val cache = ConcurrentHashMap<UUID, Int>()

        val MAP_CODEC: MapCodec<PortableNetworkColorTintSource> =
            MapCodec.unit(PortableNetworkColorTintSource())
    }
}
