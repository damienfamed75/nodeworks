package damien.nodeworks.item

import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponents
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.Identifier
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.item.component.CustomData
import net.minecraft.world.item.component.TooltipDisplay
import net.minecraft.world.level.Level
import java.util.UUID
import java.util.function.Consumer

/**
 * Describes what a Broadcast Antenna is exposing when a Link Crystal is paired to it.
 * The crystal carries this alongside the antenna's position so consumers can type-check,
 * e.g. a Handheld Inventory Terminal only accepts crystals paired to a [NETWORK_CONTROLLER]
 * source, while a Receiver Antenna only accepts [PROCESSING_STORAGE] crystals.
 *
 * The kind is a snapshot taken at encoding time, if the antenna's adjacent block changes
 * after a crystal is encoded, the mismatch surfaces at consumer-resolution time rather
 * than silently switching the crystal's meaning underneath the player.
 */
enum class BroadcastSourceKind {
    PROCESSING_STORAGE,
    NETWORK_CONTROLLER,
}

/**
 * Link Crystal, used to pair remote consumers (Receiver Antennas, Handheld Inventory
 * Terminals, etc.) to a Broadcast Antenna. Blank when crafted, encodes with the antenna's
 * frequency, position, dimension, and source-kind when placed in a Broadcast Antenna slot.
 * The encoded crystal is then inserted into whatever consumer it's meant to drive.
 */
class LinkCrystalItem(properties: Properties) : Item(properties) {

    override fun appendHoverText(stack: ItemStack, context: TooltipContext, display: TooltipDisplay, tooltip: Consumer<Component>, flag: TooltipFlag) {
        val data = getPairingData(stack)
        if (data == null) {
            tooltip.accept(
                Component.literal("Blank. Place in Broadcast Antenna to pair.")
                    .withStyle(ChatFormatting.DARK_GRAY)
            )
            return
        }
        val kindLabel = when (data.kind) {
            BroadcastSourceKind.PROCESSING_STORAGE -> "Processing Storage"
            BroadcastSourceKind.NETWORK_CONTROLLER -> "Network Controller"
        }
        tooltip.accept(
            Component.literal("Paired to $kindLabel at (${data.pos.x}, ${data.pos.y}, ${data.pos.z})")
                .withStyle(ChatFormatting.GRAY)
        )
        val dimId = data.dimension.identifier().path
        tooltip.accept(
            Component.literal("Dimension: $dimId")
                .withStyle(ChatFormatting.DARK_GRAY)
        )
    }

    companion object {
        private const val POS_KEY = "paired_pos"
        private const val DIM_KEY = "paired_dim"
        private const val FREQ_KEY = "frequency"
        private const val KIND_KEY = "paired_kind"

        data class PairingData(
            val pos: BlockPos,
            val dimension: ResourceKey<Level>,
            val frequencyId: UUID,
            val kind: BroadcastSourceKind,
        )

        /**
         * Read the pairing from a crystal's data component. Returns null for blank crystals.
         *
         * Backward-compat: crystals encoded before `paired_kind` existed (i.e. pre-Handheld
         * builds) have no kind field in their NBT. Those crystals were, by definition,
         * paired to a Processing Storage broadcast (it was the only possibility), so we
         * default to [BroadcastSourceKind.PROCESSING_STORAGE] when the key is absent. That
         * keeps existing saves' crystals working without a migration step.
         */
        fun getPairingData(stack: ItemStack): PairingData? {
            val customData = stack.get(DataComponents.CUSTOM_DATA) ?: return null
            val tag = customData.copyTag()
            val freqStr = tag.getStringOr(FREQ_KEY, "")
            if (freqStr.isEmpty()) return null
            val pos = BlockPos.of(tag.getLongOr(POS_KEY, 0L))
            val dimId = Identifier.tryParse(tag.getStringOr(DIM_KEY, "")) ?: return null
            val dimension = ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, dimId)
            val freq = try { UUID.fromString(freqStr) } catch (_: Exception) { return null }
            val kindStr = tag.getStringOr(KIND_KEY, "")
            val kind = if (kindStr.isEmpty()) {
                BroadcastSourceKind.PROCESSING_STORAGE
            } else {
                // Unrecognised kind (forwards-compat, e.g. a newer mod version added a
                // variant we don't know). Treat it as an unpairable crystal rather than
                // silently coercing to a wrong kind.
                try { BroadcastSourceKind.valueOf(kindStr) } catch (_: IllegalArgumentException) { return null }
            }
            return PairingData(pos, dimension, freq, kind)
        }

        fun encode(
            stack: ItemStack,
            pos: BlockPos,
            dimension: ResourceKey<Level>,
            frequencyId: UUID,
            kind: BroadcastSourceKind,
        ) {
            val tag = CompoundTag()
            tag.putLong(POS_KEY, pos.asLong())
            tag.putString(DIM_KEY, dimension.identifier().toString())
            tag.putString(FREQ_KEY, frequencyId.toString())
            tag.putString(KIND_KEY, kind.name)
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag))
        }

        fun isEncoded(stack: ItemStack): Boolean {
            val customData = stack.get(DataComponents.CUSTOM_DATA) ?: return false
            return customData.copyTag().contains(FREQ_KEY)
        }
    }
}
