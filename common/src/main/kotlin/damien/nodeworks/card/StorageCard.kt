package damien.nodeworks.card

import net.minecraft.core.component.DataComponents
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.CustomData

/**
 * Storage Card — registers an adjacent container as passive network storage.
 * Items in storage-card inventories are discoverable by the entire network
 * and available for crafting via Recipe Cards.
 */
class StorageCard(properties: Properties) : NodeCard(properties) {
    override val cardType: String = "storage"

    companion object {
        fun getPriority(stack: ItemStack): Int {
            val customData = stack.get(DataComponents.CUSTOM_DATA) ?: return 0
            return customData.copyTag().getInt("priority").orElse(0)
        }

        fun setPriority(stack: ItemStack, priority: Int) {
            val clamped = priority.coerceIn(0, 99)
            val tag = CompoundTag()
            tag.putInt("priority", clamped)
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag))
        }
    }
}
