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
 * Link Crystal — used to pair Receiver Antennas to Broadcast Antennas.
 * Blank when crafted. Encodes with frequency data when placed in a Broadcast Antenna slot.
 * Place the encoded chip in a Receiver Antenna to link it.
 */
class LinkCrystalItem(properties: Properties) : Item(properties) {

    override fun appendHoverText(stack: ItemStack, context: TooltipContext, display: TooltipDisplay, tooltip: Consumer<Component>, flag: TooltipFlag) {
        val data = getPairingData(stack)
        if (data != null) {
            tooltip.accept(Component.literal("Linked to (${data.pos.x}, ${data.pos.y}, ${data.pos.z})")
                .withStyle(ChatFormatting.GRAY))
            val dimId = data.dimension.identifier().path
            tooltip.accept(Component.literal("Dimension: $dimId")
                .withStyle(ChatFormatting.DARK_GRAY))
        } else {
            tooltip.accept(Component.literal("Blank — place in Broadcast Antenna")
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
            val freqStr = tag.getStringOr(FREQ_KEY, "")
            if (freqStr.isEmpty()) return null
            val pos = BlockPos.of(tag.getLongOr(POS_KEY, 0L))
            val dimId = Identifier.tryParse(tag.getStringOr(DIM_KEY, "")) ?: return null
            val dimension = ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, dimId)
            val freq = try { UUID.fromString(freqStr) } catch (_: Exception) { return null }
            return PairingData(pos, dimension, freq)
        }

        fun encode(stack: ItemStack, pos: BlockPos, dimension: ResourceKey<Level>, frequencyId: UUID) {
            val tag = CompoundTag()
            tag.putLong(POS_KEY, pos.asLong())
            tag.putString(DIM_KEY, dimension.identifier().toString())
            tag.putString(FREQ_KEY, frequencyId.toString())
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag))
        }

        fun isEncoded(stack: ItemStack): Boolean {
            val customData = stack.get(DataComponents.CUSTOM_DATA) ?: return false
            return customData.copyTag().contains(FREQ_KEY)
        }
    }
}
