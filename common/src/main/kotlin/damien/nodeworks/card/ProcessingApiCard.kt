package damien.nodeworks.card

import damien.nodeworks.platform.PlatformServices
import damien.nodeworks.screen.ProcessingApiCardOpenData
import damien.nodeworks.screen.ProcessingApiCardScreenHandler
import net.minecraft.ChatFormatting
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.StringTag
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResultHolder
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.item.component.CustomData
import net.minecraft.world.level.Level

/**
 * Processing API Card — stores a processing recipe contract:
 * input items + counts, up to 3 output items + counts, and optional timeout in ticks.
 * Goes in API Storage blocks. Right-click while holding to open the recipe editor.
 */
class ProcessingApiCard(properties: Properties) : Item(properties) {

    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResultHolder<ItemStack> {
        val stack = player.getItemInHand(hand)
        if (level.isClientSide) return InteractionResultHolder.success(stack)

        val serverPlayer = player as ServerPlayer
        val name = getCardName(stack)
        val inputs = getInputs(stack)
        val outputs = getOutputs(stack)
        val timeout = getTimeout(stack)

        PlatformServices.menu.openExtendedMenu(
            serverPlayer,
            Component.translatable("container.nodeworks.processing_api_card"),
            ProcessingApiCardOpenData(name, inputs, outputs, timeout),
            ProcessingApiCardOpenData.STREAM_CODEC,
            { syncId, inv, p -> ProcessingApiCardScreenHandler.createHandheld(syncId, inv, hand, stack) }
        )

        return InteractionResultHolder.consume(stack)
    }

    override fun appendHoverText(stack: ItemStack, context: TooltipContext, tooltip: MutableList<Component>, flag: TooltipFlag) {
        super.appendHoverText(stack, context, tooltip, flag)
        val inputs = getInputs(stack)
        val outputs = getOutputs(stack)

        if (inputs.isNotEmpty()) {
            tooltip.add(Component.translatable("tooltip.nodeworks.instruction_set.input")
                .withStyle(ChatFormatting.GRAY))
            for ((itemId, count) in inputs) {
                val identifier = ResourceLocation.tryParse(itemId) ?: continue
                val item = BuiltInRegistries.ITEM.get(identifier) ?: continue
                val countStr = if (count > 1) " x$count" else ""
                tooltip.add(Component.literal("  ").append(item.description).append(countStr)
                    .withStyle(ChatFormatting.DARK_GRAY))
            }
        }

        if (outputs.isNotEmpty()) {
            tooltip.add(Component.translatable("tooltip.nodeworks.instruction_set.output")
                .withStyle(ChatFormatting.GRAY))
            for ((itemId, count) in outputs) {
                val identifier = ResourceLocation.tryParse(itemId) ?: continue
                val item = BuiltInRegistries.ITEM.get(identifier) ?: continue
                val countStr = if (count > 1) " x$count" else ""
                tooltip.add(Component.literal("  ").append(item.description).append(countStr)
                    .withStyle(ChatFormatting.DARK_GRAY))
            }
        }

        val timeout = getTimeout(stack)
        if (timeout > 0) {
            tooltip.add(Component.literal("  Timeout: ${timeout}t (${timeout / 20.0}s)")
                .withStyle(ChatFormatting.DARK_GRAY))
        }
    }

    companion object {
        private const val INPUTS_KEY = "inputs"
        private const val INPUT_COUNTS_KEY = "input_counts"
        private const val OUTPUTS_KEY = "outputs"
        private const val OUTPUT_COUNTS_KEY = "output_counts"
        private const val NAME_KEY = "name"
        private const val TIMEOUT_KEY = "timeout"
        const val MAX_OUTPUTS = 3

        /** Get the card's registered name (used as the handler key). */
        fun getCardName(stack: ItemStack): String {
            val customData = stack.get(DataComponents.CUSTOM_DATA) ?: return ""
            return customData.copyTag().getString(NAME_KEY)
        }

        /** Get the list of (itemId, count) input pairs. */
        fun getInputs(stack: ItemStack): List<Pair<String, Int>> {
            val customData = stack.get(DataComponents.CUSTOM_DATA) ?: return emptyList()
            val tag = customData.copyTag()
            if (!tag.contains(INPUTS_KEY)) return emptyList()
            val ids = tag.getList(INPUTS_KEY, 8) // 8 = StringTag
            val counts = if (tag.contains(INPUT_COUNTS_KEY)) tag.getIntArray(INPUT_COUNTS_KEY) else IntArray(0)
            return (0 until ids.size).mapNotNull { i ->
                val id = ids.getString(i)
                if (id.isEmpty()) return@mapNotNull null
                val count = counts.getOrElse(i) { 1 }
                id to count
            }
        }

        /** Get the list of (itemId, count) output pairs (up to 3). */
        fun getOutputs(stack: ItemStack): List<Pair<String, Int>> {
            val customData = stack.get(DataComponents.CUSTOM_DATA) ?: return emptyList()
            val tag = customData.copyTag()
            if (!tag.contains(OUTPUTS_KEY)) {
                // Legacy: single output field
                val output = tag.getString("output")
                val outputCount = if (tag.contains("output_count")) tag.getInt("output_count") else 1
                return if (output.isNotEmpty()) listOf(output to outputCount) else emptyList()
            }
            val ids = tag.getList(OUTPUTS_KEY, 8)
            val counts = if (tag.contains(OUTPUT_COUNTS_KEY)) tag.getIntArray(OUTPUT_COUNTS_KEY) else IntArray(0)
            return (0 until ids.size).mapNotNull { i ->
                val id = ids.getString(i)
                if (id.isEmpty()) return@mapNotNull null
                val count = counts.getOrElse(i) { 1 }
                id to count
            }
        }

        /** Get the timeout in ticks (0 = no timeout). */
        fun getTimeout(stack: ItemStack): Int {
            val customData = stack.get(DataComponents.CUSTOM_DATA) ?: return 0
            val tag = customData.copyTag()
            return if (tag.contains(TIMEOUT_KEY)) tag.getInt(TIMEOUT_KEY) else 0
        }

        /** Save the processing recipe to the card stack. */
        fun setRecipe(stack: ItemStack, name: String, inputs: List<Pair<String, Int>>, outputs: List<Pair<String, Int>>, timeout: Int) {
            val tag = CompoundTag()
            tag.putString(NAME_KEY, name)

            val inputIds = ListTag()
            val inputCounts = IntArray(inputs.size)
            for ((i, pair) in inputs.withIndex()) {
                inputIds.add(StringTag.valueOf(pair.first))
                inputCounts[i] = pair.second
            }
            tag.put(INPUTS_KEY, inputIds)
            tag.putIntArray(INPUT_COUNTS_KEY, inputCounts)

            val outputIds = ListTag()
            val outputCounts = IntArray(outputs.size)
            for ((i, pair) in outputs.withIndex()) {
                outputIds.add(StringTag.valueOf(pair.first))
                outputCounts[i] = pair.second
            }
            tag.put(OUTPUTS_KEY, outputIds)
            tag.putIntArray(OUTPUT_COUNTS_KEY, outputCounts)

            tag.putInt(TIMEOUT_KEY, timeout)
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag))
        }
    }
}
