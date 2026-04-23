package damien.nodeworks.client.item

import com.mojang.serialization.MapCodec
import damien.nodeworks.item.LinkCrystalItem
import damien.nodeworks.item.PortableInventoryTerminalItem
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.item.properties.conditional.ConditionalItemModelProperty
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.item.ItemStack

/**
 * Item-model predicate for the Portable Inventory Terminal. Returns true when the
 * Portable has an installed Link Crystal with pairing data (regardless of kind) —
 * mirrors the LinkCrystal's own linked/unlinked semantic so the two items read as
 * a matched pair visually: "slot is populated with a live crystal."
 *
 * The linked model is responsible for showing the emissive accent layer; the
 * unlinked model is a single flat texture. An installed but blank (un-encoded)
 * crystal renders as unlinked — there's nothing for the accent to represent yet.
 *
 * Registered under `nodeworks:portable_inventory_terminal_linked` and referenced
 * from `assets/nodeworks/items/portable_inventory_terminal.json`.
 */
@JvmRecord
data class PortableInventoryTerminalLinkedProperty(private val unit: Unit = Unit) : ConditionalItemModelProperty {
    override fun get(
        stack: ItemStack,
        level: ClientLevel?,
        owner: LivingEntity?,
        seed: Int,
        context: ItemDisplayContext,
    ): Boolean {
        val crystal = PortableInventoryTerminalItem.getInstalledCrystal(stack)
        if (crystal.isEmpty) return false
        return LinkCrystalItem.isEncoded(crystal)
    }

    override fun type(): MapCodec<out ConditionalItemModelProperty> = MAP_CODEC

    companion object {
        val MAP_CODEC: MapCodec<PortableInventoryTerminalLinkedProperty> =
            MapCodec.unit(PortableInventoryTerminalLinkedProperty())
    }
}
