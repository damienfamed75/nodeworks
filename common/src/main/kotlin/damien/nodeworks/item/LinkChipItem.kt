package damien.nodeworks.item

import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponents
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.item.component.CustomData
import net.minecraft.world.level.Level
import java.util.UUID

/**
 * Link Chip — used to pair Receiver Antennas to Broadcast Antennas.
 * Blank when crafted. Encodes with frequency data when placed in a Broadcast Antenna slot.
 * Place the encoded chip in a Receiver Antenna to link it.
 */
class LinkChipItem(properties: Properties) : Item(properties) {

    override fun appendHoverText(stack: ItemStack, context: TooltipContext, tooltip: MutableList<Component>, flag: TooltipFlag) {
        val data = getPairingData(stack)
        if (data != null) {
            tooltip.add(Component.literal("Linked to (${data.pos.x}, ${data.pos.y}, ${data.pos.z})")
                .withStyle(ChatFormatting.GRAY))
            val dimId = data.dimension.location().path
            tooltip.add(Component.literal("Dimension: $dimId")
                .withStyle(ChatFormatting.DARK_GRAY))
        } else {
            tooltip.add(Component.literal("Blank — place in Broadcast Antenna")
                .withStyle(ChatFormatting.DARK_GRAY))
        }
    }

    companion object {
        private const val POS_KEY = "paired_pos"
        private const val DIM_KEY = "paired_dim"
        private const val FREQ_KEY = "frequency"

        data class PairingData(
            val pos: BlockPos,
            val dimension: ResourceKey<Level>,
            val frequencyId: UUID
        )

        fun getPairingData(stack: ItemStack): PairingData? {
            val customData = stack.get(DataComponents.CUSTOM_DATA) ?: return null
            val tag = customData.copyTag()
            if (!tag.contains(FREQ_KEY)) return null
            val pos = BlockPos.of(tag.getLong(POS_KEY))
            val dimId = ResourceLocation.tryParse(tag.getString(DIM_KEY)) ?: return null
            val dimension = ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, dimId)
            val freq = try { UUID.fromString(tag.getString(FREQ_KEY)) } catch (_: Exception) { return null }
            return PairingData(pos, dimension, freq)
        }

        fun encode(stack: ItemStack, pos: BlockPos, dimension: ResourceKey<Level>, frequencyId: UUID) {
            val tag = CompoundTag()
            tag.putLong(POS_KEY, pos.asLong())
            tag.putString(DIM_KEY, dimension.location().toString())
            tag.putString(FREQ_KEY, frequencyId.toString())
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag))
        }

        fun isEncoded(stack: ItemStack): Boolean {
            val customData = stack.get(DataComponents.CUSTOM_DATA) ?: return false
            return customData.copyTag().contains(FREQ_KEY)
        }
    }
}
