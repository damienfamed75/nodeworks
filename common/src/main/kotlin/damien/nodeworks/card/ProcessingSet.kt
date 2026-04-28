package damien.nodeworks.card

import damien.nodeworks.platform.PlatformServices
import damien.nodeworks.screen.ProcessingSetOpenData
import damien.nodeworks.screen.ProcessingSetScreenHandler
import net.minecraft.ChatFormatting
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.StringTag
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.item.component.CustomData
import net.minecraft.world.item.component.TooltipDisplay
import net.minecraft.world.level.Level
import java.util.function.Consumer

/**
 * Processing Set, stores a processing recipe contract:
 * input items + counts, up to 3 output items + counts, and optional timeout in ticks.
 * Goes in Processing Storage blocks. Right-click while holding to open the recipe editor.
 */
class ProcessingSet(properties: Properties) : Item(properties) {

    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResult {
        if (level.isClientSide) return InteractionResult.SUCCESS

        val stack = player.getItemInHand(hand)
        val serverPlayer = player as ServerPlayer

        PlatformServices.menu.openExtendedMenu(
            serverPlayer,
            Component.translatable("container.nodeworks.processing_set"),
            ProcessingSetOpenData(
                getCardName(stack), getInputs(stack), getInputPositions(stack),
                getOutputs(stack), getOutputPositions(stack),
                getTimeout(stack), isSerial(stack)
            ),
            ProcessingSetOpenData.STREAM_CODEC,
            { syncId, inv, p -> ProcessingSetScreenHandler.createHandheld(syncId, inv, hand, stack) }
        )

        return InteractionResult.CONSUME
    }

    override fun appendHoverText(stack: ItemStack, context: TooltipContext, display: TooltipDisplay, tooltip: Consumer<Component>, flag: TooltipFlag) {
        super.appendHoverText(stack, context, display, tooltip, flag)
        val inputs = getInputs(stack)
        val outputs = getOutputs(stack)

        if (inputs.isNotEmpty()) {
            tooltip.accept(Component.translatable("tooltip.nodeworks.instruction_set.input")
                .withStyle(ChatFormatting.GRAY))
            for ((itemId, count) in inputs) {
                val identifier = Identifier.tryParse(itemId) ?: continue
                val item = BuiltInRegistries.ITEM.getValue(identifier) ?: continue
                val countStr = if (count > 1) " x$count" else ""
                tooltip.accept(Component.literal("  ").append(Component.translatable(item.descriptionId)).append(countStr)
                    .withStyle(ChatFormatting.DARK_GRAY))
            }
        }

        if (outputs.isNotEmpty()) {
            tooltip.accept(Component.translatable("tooltip.nodeworks.instruction_set.output")
                .withStyle(ChatFormatting.GRAY))
            for ((itemId, count) in outputs) {
                val identifier = Identifier.tryParse(itemId) ?: continue
                val item = BuiltInRegistries.ITEM.getValue(identifier) ?: continue
                val countStr = if (count > 1) " x$count" else ""
                tooltip.accept(Component.literal("  ").append(Component.translatable(item.descriptionId)).append(countStr)
                    .withStyle(ChatFormatting.DARK_GRAY))
            }
        }

        val timeout = getTimeout(stack)
        if (timeout > 0) {
            tooltip.accept(Component.literal("  Timeout: ${timeout}t (${timeout / 20.0}s)")
                .withStyle(ChatFormatting.DARK_GRAY))
        }
    }

    companion object {
        private const val INPUTS_KEY = "inputs"
        private const val INPUT_COUNTS_KEY = "input_counts"
        private const val INPUT_SLOTS_KEY = "input_slots"
        private const val OUTPUTS_KEY = "outputs"
        private const val OUTPUT_COUNTS_KEY = "output_counts"
        private const val OUTPUT_SLOTS_KEY = "output_slots"
        private const val NAME_KEY = "name"
        private const val TIMEOUT_KEY = "timeout"
        private const val SERIAL_KEY = "serial"
        const val MAX_OUTPUTS = 3
        const val INPUT_GRID_SIZE = 9

        /** Get the card's registered name (used as the handler key). */
        fun getCardName(stack: ItemStack): String {
            val customData = stack.get(DataComponents.CUSTOM_DATA) ?: return ""
            return customData.copyTag().getStringOr(NAME_KEY, "")
        }

        /** Get the list of (itemId, count) input pairs. Empty-ID entries are filtered. */
        fun getInputs(stack: ItemStack): List<Pair<String, Int>> {
            val customData = stack.get(DataComponents.CUSTOM_DATA) ?: return emptyList()
            val tag = customData.copyTag()
            val ids = tag.getList(INPUTS_KEY).orElse(null) ?: return emptyList()
            val counts = tag.getIntArray(INPUT_COUNTS_KEY).orElse(IntArray(0))
            return (0 until ids.size).mapNotNull { i ->
                val id = ids.getStringOr(i, "")
                if (id.isEmpty()) return@mapNotNull null
                val count = counts.getOrElse(i) { 1 }
                id to count
            }
        }

        /**
         * Grid positions (0..8) for each entry returned by [getInputs], parallel to
         * that list. Returns sequential positions for legacy cards saved before slot
         * tracking existed.
         */
        fun getInputPositions(stack: ItemStack): IntArray {
            val customData = stack.get(DataComponents.CUSTOM_DATA) ?: return IntArray(0)
            val tag = customData.copyTag()
            val ids = tag.getList(INPUTS_KEY).orElse(null) ?: return IntArray(0)
            val slots = tag.getIntArray(INPUT_SLOTS_KEY).orElse(IntArray(0))
            val result = ArrayList<Int>(ids.size)
            for (i in 0 until ids.size) {
                val id = ids.getStringOr(i, "")
                if (id.isEmpty()) continue
                result.add(slots.getOrElse(i) { i })
            }
            return result.toIntArray()
        }

        /** Get the list of (itemId, count) output pairs (up to 3). */
        fun getOutputs(stack: ItemStack): List<Pair<String, Int>> {
            val customData = stack.get(DataComponents.CUSTOM_DATA) ?: return emptyList()
            val tag = customData.copyTag()
            val idsOpt = tag.getList(OUTPUTS_KEY).orElse(null)
            if (idsOpt == null) {
                // Legacy: single output field
                val output = tag.getStringOr("output", "")
                val outputCount = tag.getIntOr("output_count", 1)
                return if (output.isNotEmpty()) listOf(output to outputCount) else emptyList()
            }
            val counts = tag.getIntArray(OUTPUT_COUNTS_KEY).orElse(IntArray(0))
            return (0 until idsOpt.size).mapNotNull { i ->
                val id = idsOpt.getStringOr(i, "")
                if (id.isEmpty()) return@mapNotNull null
                val count = counts.getOrElse(i) { 1 }
                id to count
            }
        }

        /** Output-column positions (0..2) parallel to [getOutputs]. Sequential fallback
         *  for legacy cards. */
        fun getOutputPositions(stack: ItemStack): IntArray {
            val customData = stack.get(DataComponents.CUSTOM_DATA) ?: return IntArray(0)
            val tag = customData.copyTag()
            val ids = tag.getList(OUTPUTS_KEY).orElse(null)
            if (ids == null) {
                val output = tag.getStringOr("output", "")
                return if (output.isNotEmpty()) intArrayOf(0) else IntArray(0)
            }
            val slots = tag.getIntArray(OUTPUT_SLOTS_KEY).orElse(IntArray(0))
            val result = ArrayList<Int>(ids.size)
            for (i in 0 until ids.size) {
                val id = ids.getStringOr(i, "")
                if (id.isEmpty()) continue
                result.add(slots.getOrElse(i) { i })
            }
            return result.toIntArray()
        }

        /** Get the timeout in ticks (0 = no timeout). */
        fun getTimeout(stack: ItemStack): Int {
            val customData = stack.get(DataComponents.CUSTOM_DATA) ?: return 0
            return customData.copyTag().getIntOr(TIMEOUT_KEY, 0)
        }

        /** Whether this card enforces serial (one-at-a-time) handler execution. */
        fun isSerial(stack: ItemStack): Boolean {
            val customData = stack.get(DataComponents.CUSTOM_DATA) ?: return false
            return customData.copyTag().getBooleanOr(SERIAL_KEY, false)
        }

        /**
         * Build the canonical handler ID for a Processing Set from its input and output
         * layout. Inputs must already be in row-major grid order (slot 0 → 8, empty
         * slots skipped), outputs top-to-bottom. Duplicate items in different slots are
         * preserved, they produce distinct entries.
         *
         * Format: `itemId@count|itemId@count|...>>itemId@count|...`
         *
         * The format deliberately uses `@` and `|`, characters that never appear in
         * vanilla or modded item IDs, so parsing is unambiguous.
         */
        fun canonicalId(inputs: List<Pair<String, Int>>, outputs: List<Pair<String, Int>>): String {
            val inputPart = inputs.joinToString("|") { (id, c) -> "$id@$c" }
            val outputPart = outputs.joinToString("|") { (id, c) -> "$id@$c" }
            return "$inputPart>>$outputPart"
        }

        /**
         * Convert an item id into a camelCase Lua identifier:
         * `minecraft:copper_ingot` → `copperIngot`. Delegates to
         * [HandlerParamNames] so unit-testable surfaces (LuaDiagnostics) can
         * reuse the rule without loading [ProcessingSet]'s MC dependencies.
         */
        fun itemIdToParamName(itemId: String): String =
            HandlerParamNames.itemIdToParamName(itemId)

        /**
         * Build per-slot handler parameter names in grid order. See
         * [HandlerParamNames.build] for the rule details.
         */
        fun buildHandlerParamNames(inputs: List<Pair<String, Int>>): List<String> =
            HandlerParamNames.build(inputs)

        /**
         * Save the processing recipe to the card stack. [inputPositions] maps each
         * entry in [inputs] to its grid slot (0..8). [outputPositions] does the same
         * for outputs (0..2). Pass `null` to default to sequential positions, this is
         * only appropriate for legacy callers that don't care about layout.
         */
        fun setRecipe(
            stack: ItemStack,
            name: String,
            inputs: List<Pair<String, Int>>,
            outputs: List<Pair<String, Int>>,
            timeout: Int,
            serial: Boolean = false,
            inputPositions: IntArray? = null,
            outputPositions: IntArray? = null
        ) {
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
            tag.putIntArray(INPUT_SLOTS_KEY, inputPositions ?: IntArray(inputs.size) { it })

            val outputIds = ListTag()
            val outputCounts = IntArray(outputs.size)
            for ((i, pair) in outputs.withIndex()) {
                outputIds.add(StringTag.valueOf(pair.first))
                outputCounts[i] = pair.second
            }
            tag.put(OUTPUTS_KEY, outputIds)
            tag.putIntArray(OUTPUT_COUNTS_KEY, outputCounts)
            tag.putIntArray(OUTPUT_SLOTS_KEY, outputPositions ?: IntArray(outputs.size) { it })

            tag.putInt(TIMEOUT_KEY, timeout)
            tag.putBoolean(SERIAL_KEY, serial)
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag))
        }
    }
}
