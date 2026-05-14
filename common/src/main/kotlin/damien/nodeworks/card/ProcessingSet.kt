package damien.nodeworks.card

import damien.nodeworks.platform.PlatformServices
import damien.nodeworks.screen.ProcessingSetOpenData
import damien.nodeworks.screen.ProcessingSetScreenHandler
import damien.nodeworks.script.RecipeId
import damien.nodeworks.script.RecipeIngredient
import net.minecraft.ChatFormatting
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.NbtOps
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.resources.RegistryOps
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
 *
 * As of the component-identity refactor, inputs/outputs are stored as full
 * ItemStacks (with their DataComponentPatch) so recipes like "potion of
 * weakness + redstone → potion of strength" become expressible. The legacy
 * itemId+count storage is still read from old save files via the v1 reader
 * in [getInputs] / [getOutputs] for backward compatibility.
 */
class ProcessingSet(properties: Properties) : Item(properties) {

    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResult {
        tryResetConfig(level, player, hand)?.let { return it }
        if (level.isClientSide) return InteractionResult.SUCCESS

        val stack = player.getItemInHand(hand)
        val serverPlayer = player as ServerPlayer
        val registries = level.registryAccess()

        PlatformServices.menu.openExtendedMenu(
            serverPlayer,
            Component.translatable("container.nodeworks.processing_set"),
            ProcessingSetOpenData(
                name = getCardName(stack),
                inputs = getInputs(stack, registries),
                inputSlots = getInputPositions(stack),
                outputs = getOutputs(stack, registries),
                outputSlots = getOutputPositions(stack),
                timeout = getTimeout(stack),
                fuzzy = isFuzzy(stack),
                serial = isSerial(stack),
            ),
            ProcessingSetOpenData.STREAM_CODEC,
            { syncId, inv, p -> ProcessingSetScreenHandler.createHandheld(syncId, inv, hand, stack) }
        )

        return InteractionResult.CONSUME
    }

    override fun appendHoverText(stack: ItemStack, context: TooltipContext, display: TooltipDisplay, tooltip: Consumer<Component>, flag: TooltipFlag) {
        super.appendHoverText(stack, context, display, tooltip, flag)
        // Per-ingredient name lines were redundant once the [getTooltipImage]
        // icon strip arrived. Timeout / fuzzy stay as text since they're
        // metadata, not items.
        val timeout = getTimeout(stack)
        if (timeout > 0) {
            tooltip.accept(Component.literal("  Timeout: ${timeout}t (${timeout / 20.0}s)")
                .withStyle(ChatFormatting.DARK_GRAY))
        }
        if (isFuzzy(stack)) {
            tooltip.accept(Component.literal("  Fuzzy: any component variant matches")
                .withStyle(ChatFormatting.DARK_GRAY))
        }
    }

    /** Provides the recipe-icon strip for the tooltip. Surfaces inputs and
     *  outputs with their configured counts, preserving component identity
     *  (so a Strength Potion ingredient renders as the strength variant).
     *  Returns absent when the set hasn't been configured. */
    override fun getTooltipImage(stack: ItemStack): java.util.Optional<net.minecraft.world.inventory.tooltip.TooltipComponent> {
        // Tooltip rendering runs client-side; grab the client level's
        // synced registries so component-bearing entries (potions, dyed
        // armor) parse with their full identity instead of degrading to
        // bare items. Falls back to an empty provider if the player isn't
        // in a world (main menu, etc.).
        val registries = net.minecraft.client.Minecraft.getInstance().level?.registryAccess()
            ?: net.minecraft.core.HolderLookup.Provider.create(java.util.stream.Stream.empty())
        val rawInputs = getInputs(stack, registries)
        val inputSlots = getInputPositions(stack)
        // Place each input at its configured grid slot so the tooltip
        // mirrors what the player set up in the GUI; sparse layouts
        // (a single ingredient in slot 4) read positionally instead of
        // bunching at the top-left.
        val inputs = MutableList(damien.nodeworks.screen.tooltip.RecipeIconTooltip.INPUT_SLOTS) { ItemStack.EMPTY }
        for ((i, ingr) in rawInputs.withIndex()) {
            val slotIdx = inputSlots.getOrNull(i) ?: i
            if (slotIdx in inputs.indices) {
                inputs[slotIdx] = ingr.stack.copyWithCount(ingr.count)
            }
        }
        val outputs = getOutputs(stack, registries).map { it.stack.copyWithCount(it.count) }
        if (inputs.all { it.isEmpty } && outputs.isEmpty()) return java.util.Optional.empty()
        return java.util.Optional.of(damien.nodeworks.screen.tooltip.RecipeIconTooltip(inputs, outputs))
    }

    companion object {
        // v2 NBT keys (component-aware). Each entry in INPUTS_V2_KEY / OUTPUTS_V2_KEY
        // is a CompoundTag holding the ItemStack (codec-encoded with components),
        // an explicit Long count, and the grid slot position.
        private const val INPUTS_V2_KEY = "inputs_v2"
        private const val OUTPUTS_V2_KEY = "outputs_v2"
        private const val ENTRY_STACK_KEY = "stack"
        private const val ENTRY_COUNT_KEY = "count"
        private const val ENTRY_SLOT_KEY = "slot"

        // v1 NBT keys, still read for backward compatibility. Migrated to v2
        // shape (with empty components) on the next save.
        private const val INPUTS_KEY = "inputs"
        private const val INPUT_COUNTS_KEY = "input_counts"
        private const val INPUT_SLOTS_KEY = "input_slots"
        private const val OUTPUTS_KEY = "outputs"
        private const val OUTPUT_COUNTS_KEY = "output_counts"
        private const val OUTPUT_SLOTS_KEY = "output_slots"

        private const val NAME_KEY = "name"
        private const val TIMEOUT_KEY = "timeout"
        private const val SERIAL_KEY = "serial"
        private const val FUZZY_KEY = "fuzzy"
        const val MAX_OUTPUTS = 3
        const val INPUT_GRID_SIZE = 9

        /** The card's display name. Cosmetic only after the canonical-id retire,
         *  recipe identity is the hash, not the name. */
        fun getCardName(stack: ItemStack): String {
            val customData = stack.get(DataComponents.CUSTOM_DATA) ?: return ""
            return customData.copyTag().getStringOr(NAME_KEY, "")
        }

        /** Inputs as component-aware [RecipeIngredient]s. Reads v2 NBT when
         *  present, falls back to v1 (itemId + count, empty components). The
         *  grid-position info is mirrored by [getInputPositions] so callers
         *  that need both can pair them by index.
         *
         *  [registries] is required to parse component-bearing items whose
         *  components reference registry-backed values (potion contents,
         *  enchantments, banner patterns). Calling with a mismatched / empty
         *  provider degrades component-bearing items to bare items, which
         *  renders potions as "Uncraftable Potion" and similar. */
        fun getInputs(stack: ItemStack, registries: net.minecraft.core.HolderLookup.Provider): List<RecipeIngredient> =
            readIngredients(stack, INPUTS_V2_KEY, INPUTS_KEY, INPUT_COUNTS_KEY, registries)

        /** Grid positions (0..8) for each entry returned by [getInputs], parallel
         *  to that list. Reads v2 slots when present, falls back to v1's
         *  INPUT_SLOTS array, falls back to sequential positions for very old
         *  cards saved before slot tracking existed. */
        fun getInputPositions(stack: ItemStack): IntArray =
            readPositions(stack, INPUTS_V2_KEY, INPUTS_KEY, INPUT_SLOTS_KEY)

        /** Outputs as component-aware [RecipeIngredient]s. Same compat shape as
         *  [getInputs]. */
        fun getOutputs(stack: ItemStack, registries: net.minecraft.core.HolderLookup.Provider): List<RecipeIngredient> =
            readIngredients(stack, OUTPUTS_V2_KEY, OUTPUTS_KEY, OUTPUT_COUNTS_KEY, registries)

        /** Output-column positions (0..2) parallel to [getOutputs]. */
        fun getOutputPositions(stack: ItemStack): IntArray =
            readPositions(stack, OUTPUTS_V2_KEY, OUTPUTS_KEY, OUTPUT_SLOTS_KEY)

        /** Cheap structural check: does this Set have at least one output
         *  ingredient declared? Reads NBT lists directly without decoding
         *  ItemStacks, so it doesn't need a [registries] provider. Slot
         *  `mayPlace` and similar fast paths use this instead of the full
         *  [getOutputs] decode. */
        fun hasOutputs(stack: ItemStack): Boolean {
            val customData = stack.get(DataComponents.CUSTOM_DATA) ?: return false
            val tag = customData.copyTag()
            val v2 = tag.get(OUTPUTS_V2_KEY) as? ListTag
            if (v2 != null && v2.size > 0) return true
            val v1 = tag.getList(OUTPUTS_KEY).orElse(null) ?: return false
            for (i in 0 until v1.size) {
                if (v1.getStringOr(i, "").isNotEmpty()) return true
            }
            return false
        }

        fun getTimeout(stack: ItemStack): Int {
            val customData = stack.get(DataComponents.CUSTOM_DATA) ?: return 0
            return customData.copyTag().getIntOr(TIMEOUT_KEY, 0)
        }

        fun isSerial(stack: ItemStack): Boolean {
            val customData = stack.get(DataComponents.CUSTOM_DATA) ?: return false
            return customData.copyTag().getBooleanOr(SERIAL_KEY, false)
        }

        /** True when the recipe accepts any component variant of its declared
         *  inputs (e.g. any potion in the input slot, not specifically a
         *  Potion of Strength). Default false = exact-component matching. */
        fun isFuzzy(stack: ItemStack): Boolean {
            val customData = stack.get(DataComponents.CUSTOM_DATA) ?: return false
            return customData.copyTag().getBooleanOr(FUZZY_KEY, false)
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
         * [HandlerParamNames.build] for the rule details. Legacy
         * `(itemId, count)` overload retained for callers that haven't
         * switched to RecipeIngredient.
         */
        fun buildHandlerParamNames(inputs: List<Pair<String, Int>>): List<String> =
            HandlerParamNames.build(inputs)

        /** Component-aware build. */
        @JvmName("buildHandlerParamNamesFromIngredients")
        fun buildHandlerParamNamesFromIngredients(inputs: List<RecipeIngredient>): List<String> =
            HandlerParamNames.build(inputs.map { it.itemId to it.count })

        /** Save the processing recipe to [stack]. Encodes inputs and outputs as
         *  component-aware v2 entries, keeping the slot-position arrays parallel
         *  so the editor restores ghost slots exactly where the player left them.
         *
         *  [registryAccess] is required to encode component-bearing items
         *  (potions, enchanted books, banners). Their codecs reference
         *  registry-backed values. The screen handler / item-use path threads
         *  this through from the player's level. */
        fun setRecipe(
            stack: ItemStack,
            name: String,
            inputs: List<RecipeIngredient>,
            outputs: List<RecipeIngredient>,
            timeout: Int,
            fuzzy: Boolean = false,
            serial: Boolean = false,
            inputPositions: IntArray? = null,
            outputPositions: IntArray? = null,
            registryAccess: net.minecraft.core.HolderLookup.Provider,
        ) {
            val ops = RegistryOps.create(NbtOps.INSTANCE, registryAccess)
            val tag = CompoundTag()
            tag.putString(NAME_KEY, name)

            tag.put(INPUTS_V2_KEY, encodeIngredients(inputs, inputPositions, ops))
            tag.put(OUTPUTS_V2_KEY, encodeIngredients(outputs, outputPositions, ops))

            tag.putInt(TIMEOUT_KEY, timeout)
            tag.putBoolean(SERIAL_KEY, serial)
            tag.putBoolean(FUZZY_KEY, fuzzy)
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag))
        }

        // -----------------------------------------------------------------
        // Internal helpers
        // -----------------------------------------------------------------

        private fun encodeIngredients(
            ingredients: List<RecipeIngredient>,
            slotsOverride: IntArray?,
            ops: net.minecraft.resources.RegistryOps<net.minecraft.nbt.Tag>,
        ): ListTag {
            val list = ListTag()
            for ((i, ingr) in ingredients.withIndex()) {
                val entry = CompoundTag()
                // Encode the stack with count=1 baked in; the authoritative count
                // is the separate ENTRY_COUNT_KEY. Avoids the ItemStack codec
                // refusing zero-count or huge-count stacks.
                val template = ingr.stack.copyWithCount(1)
                val stackTag = ItemStack.CODEC.encodeStart(ops, template).result().orElse(null) ?: continue
                entry.put(ENTRY_STACK_KEY, stackTag)
                entry.putInt(ENTRY_COUNT_KEY, ingr.count.coerceAtLeast(1))
                entry.putInt(ENTRY_SLOT_KEY, slotsOverride?.getOrElse(i) { i } ?: i)
                list.add(entry)
            }
            return list
        }

        private fun readIngredients(
            stack: ItemStack,
            v2Key: String,
            v1IdsKey: String,
            v1CountsKey: String,
            registries: net.minecraft.core.HolderLookup.Provider,
        ): List<RecipeIngredient> {
            val customData = stack.get(DataComponents.CUSTOM_DATA) ?: return emptyList()
            val tag = customData.copyTag()

            // v2 path: list of compound entries with full stacks. RegistryOps
            // wrapping is required to deserialize component values that
            // reference registry entries (POTION_CONTENTS → Holder<Potion>,
            // enchantments → Holder<Enchantment>, banner patterns, etc).
            // Plain NbtOps drops those components and the resulting "potion"
            // item renders as "Uncraftable Potion".
            val ops = RegistryOps.create(NbtOps.INSTANCE, registries)
            val v2List = tag.get(v2Key) as? ListTag
            if (v2List != null) {
                val out = ArrayList<RecipeIngredient>(v2List.size)
                for (i in 0 until v2List.size) {
                    val entry = v2List.getCompound(i).orElse(null) ?: continue
                    val stackTag = entry.get(ENTRY_STACK_KEY) ?: continue
                    val ingrStack = ItemStack.CODEC.parse(ops, stackTag).result().orElse(null) ?: continue
                    if (ingrStack.isEmpty) continue
                    val count = entry.getInt(ENTRY_COUNT_KEY).orElse(1).coerceAtLeast(1)
                    out.add(RecipeIngredient(ingrStack, count))
                }
                return out
            }

            // v1 path: parallel ListTag<String> + IntArray.
            val ids = tag.getList(v1IdsKey).orElse(null) ?: return emptyList()
            val counts = tag.getIntArray(v1CountsKey).orElse(IntArray(0))
            val out = ArrayList<RecipeIngredient>(ids.size)
            for (i in 0 until ids.size) {
                val id = ids.getStringOr(i, "")
                if (id.isEmpty()) continue
                val identifier = Identifier.tryParse(id) ?: continue
                val item = BuiltInRegistries.ITEM.getValue(identifier) ?: continue
                val count = counts.getOrElse(i) { 1 }.coerceAtLeast(1)
                out.add(RecipeIngredient(ItemStack(item), count))
            }
            return out
        }

        private fun readPositions(
            stack: ItemStack,
            v2Key: String,
            v1IdsKey: String,
            v1SlotsKey: String,
        ): IntArray {
            val customData = stack.get(DataComponents.CUSTOM_DATA) ?: return IntArray(0)
            val tag = customData.copyTag()

            val v2List = tag.get(v2Key) as? ListTag
            if (v2List != null) {
                val out = IntArray(v2List.size)
                for (i in 0 until v2List.size) {
                    val entry = v2List.getCompound(i).orElse(null)
                    out[i] = entry?.getInt(ENTRY_SLOT_KEY)?.orElse(i) ?: i
                }
                return out
            }

            // v1 fallback: parallel slot array.
            val ids = tag.getList(v1IdsKey).orElse(null) ?: return IntArray(0)
            val slots = tag.getIntArray(v1SlotsKey).orElse(IntArray(0))
            val result = ArrayList<Int>(ids.size)
            for (i in 0 until ids.size) {
                val id = ids.getStringOr(i, "")
                if (id.isEmpty()) continue
                result.add(slots.getOrElse(i) { i })
            }
            return result.toIntArray()
        }

        /** Compute the recipe's hash-based identity. Identical structure (same
         *  inputs and outputs, same fuzzy flag) always yields the same id, so
         *  re-authoring the same recipe in two places merges under one entry.
         *  Uses the cross-session-stable [RecipeId.of] overload so the hash
         *  encoded into Processing Handler bindings survives world reloads.
         *  Without [registries] the per-stack components hash would mix in
         *  `Holder.Reference.hashCode()` (object identity), which drifts
         *  between sessions and causes handlers to "forget" their recipe. */
        fun computeRecipeId(stack: ItemStack, registries: net.minecraft.core.HolderLookup.Provider): String =
            RecipeId.of(getInputs(stack, registries), getOutputs(stack, registries), isFuzzy(stack), registries)
    }
}
