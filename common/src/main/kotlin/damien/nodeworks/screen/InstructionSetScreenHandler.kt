package damien.nodeworks.screen

import damien.nodeworks.card.InstructionSet
import damien.nodeworks.registry.ModScreenHandlers
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.Container
import net.minecraft.world.InteractionHand
import net.minecraft.world.SimpleContainer
import net.minecraft.world.item.crafting.CraftingInput
import net.minecraft.world.item.crafting.RecipeType
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.SimpleContainerData
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack

/**
 * Screen handler for the Instruction Set's 3x3 crafting template editor.
 * Supports two modes: handheld (card in player's hand) or node-based (card in a node slot).
 *
 * The grid holds real stacks at count one, components included, so a recipe that only
 * matches a specific variant can be laid out, resolved and saved as that variant.
 */
class InstructionSetScreenHandler(
    syncId: Int,
    private val playerInventory: Inventory,
    private val recipeGrid: SimpleContainer,
    private val saveMode: SaveMode,
    initialSubstitutions: Boolean = true,
) : AbstractContainerMenu(ModScreenHandlers.INSTRUCTION_SET, syncId) {

    private val resultContainer = SimpleContainer(1)

    /** The last matched recipe is tried first, so every click on a grid that still matches
     *  it, and every normalisation trial that keeps matching, skips the full recipe scan. */
    private val craftingCheck = net.minecraft.world.item.crafting.RecipeManager
        .createCheck<CraftingInput, net.minecraft.world.item.crafting.CraftingRecipe>(RecipeType.CRAFTING)

    /** Snapshot of the recipe grid at open time, captured so [removed] can skip
     *  saving when the user closes without making any changes. Without this,
     *  opening a stack of >1 unprogrammed Instruction Sets and closing the GUI
     *  splits a copy off the stack and writes an empty recipe to it (same path
     *  the >1-stack save uses), leaving the player with a "programmed-empty"
     *  card alongside the original stack. */
    private val initialRecipe: List<ItemStack> = readGrid()
    private val initialSubstitutionsValue: Boolean = initialSubstitutions

    /** Slot 0: substitutions toggle (1 = on, 0 = off). Server-synced so the
     *  screen's icon reflects the current value without bespoke payloads. */
    val substitutionsData: SimpleContainerData = SimpleContainerData(1).apply {
        set(0, if (initialSubstitutions) 1 else 0)
    }

    sealed class SaveMode {
        data class Handheld(val hand: InteractionHand) : SaveMode()
        data class InNode(val nodePos: BlockPos, val sideOrdinal: Int, val slotIndex: Int) : SaveMode()
        object ClientDummy : SaveMode()
    }

    companion object {
        /** Bound on the recipe lookups one save may spend trimming components. Each lookup
         *  scans every crafting recipe, and a grid of nine heavily componented stacks could
         *  otherwise ask for dozens. */
        private const val MAX_NORMALISE_LOOKUPS = 24

        private fun recipeToGrid(recipe: List<ItemStack>): SimpleContainer {
            val grid = SimpleContainer(9)
            for (i in 0 until 9) {
                val stack = recipe.getOrNull(i) ?: continue
                if (!stack.isEmpty) grid.setItem(i, stack.copyWithCount(1))
            }
            return grid
        }

        fun createHandheld(syncId: Int, playerInventory: Inventory, hand: InteractionHand, stack: ItemStack): InstructionSetScreenHandler {
            val recipe = InstructionSet.getRecipeStacks(stack)
            val subs = InstructionSet.getSubstitutions(stack)
            return InstructionSetScreenHandler(syncId, playerInventory, recipeToGrid(recipe), SaveMode.Handheld(hand), subs)
        }

        fun createServer(syncId: Int, playerInventory: Inventory, nodePos: BlockPos, side: Direction, slotIndex: Int, stack: ItemStack): InstructionSetScreenHandler {
            val recipe = InstructionSet.getRecipeStacks(stack)
            val subs = InstructionSet.getSubstitutions(stack)
            return InstructionSetScreenHandler(syncId, playerInventory, recipeToGrid(recipe), SaveMode.InNode(nodePos, side.ordinal, slotIndex), subs)
        }

        fun clientFactory(syncId: Int, playerInventory: Inventory, data: InstructionSetOpenData): InstructionSetScreenHandler {
            return InstructionSetScreenHandler(syncId, playerInventory, recipeToGrid(data.recipe), SaveMode.ClientDummy, data.allowSubstitutions)
        }

        /** True when both grids hold the same item and components in every slot. */
        private fun sameGrid(a: List<ItemStack>, b: List<ItemStack>): Boolean {
            if (a.size != b.size) return false
            for (i in a.indices) {
                if (a[i].isEmpty != b[i].isEmpty) return false
                if (!a[i].isEmpty && !ItemStack.isSameItemSameComponents(a[i], b[i])) return false
            }
            return true
        }
    }

    init {
        // 3x3 recipe grid, ghost slots (slots 0-8). Positions match InstructionSetScreen.
        for (row in 0..2) {
            for (col in 0..2) {
                addSlot(GhostSlot(recipeGrid, row * 3 + col, 36 + col * 18, 13 + row * 18))
            }
        }

        // Result slot (slot 9), display only, vertically centered on the middle row.
        addSlot(ResultSlot(resultContainer, 0, 128, 31))

        // Player inventory, matches ProcessingSetScreen layout (9 cols at x=9, inv y=95, hotbar y=153).
        // INV_GRID_Y = 80+14 = 94, so slot y = 95. Hotbar y = 95 + 54 + 4 = 153.
        for (row in 0 until 3) {
            for (col in 0 until 9) {
                addSlot(net.minecraft.world.inventory.Slot(playerInventory, col + row * 9 + 9, 9 + col * 18, 95 + row * 18))
            }
        }
        for (col in 0 until 9) {
            addSlot(net.minecraft.world.inventory.Slot(playerInventory, col, 9 + col * 18, 153))
        }

        addDataSlots(substitutionsData)
        updateResult()
    }

    fun getSubstitutions(): Boolean = substitutionsData.get(0) != 0

    private class GhostSlot(container: Container, index: Int, x: Int, y: Int) : Slot(container, index, x, y) {
        override fun mayPlace(stack: ItemStack): Boolean = true
        override fun getMaxStackSize(): Int = 1
    }

    /** Display-only slot that shows the crafting result. */
    private class ResultSlot(container: Container, index: Int, x: Int, y: Int) : Slot(container, index, x, y) {
        override fun mayPlace(stack: ItemStack): Boolean = false
        override fun mayPickup(player: Player): Boolean = false
    }

    /** The result the recipe manager gives for [items], empty when nothing matches. Null
     *  when there is no recipe manager to ask, which only happens on the client. */
    private fun resultFor(items: List<ItemStack>): ItemStack? {
        val level = playerInventory.player.level()
        val serverLevel = level as? net.minecraft.server.level.ServerLevel ?: return null
        val input = CraftingInput.of(3, 3, items)
        return craftingCheck
            .getRecipeFor(input, serverLevel)
            .map { it.value().assemble(input) }
            .orElse(ItemStack.EMPTY)
    }

    /** Looks up the crafting result from the recipe manager and updates the result slot. */
    private fun updateResult() {
        val result = resultFor(readGrid()) ?: return
        resultContainer.setItem(0, result)
    }

    /**
     * Drops the components the recipe does not need, so a worn pickaxe dropped into a
     * slot does not pin the card to that exact wear. A slot is first tried plain. When
     * the grid stops giving the same result, each of its component types is tried on
     * its own and only the ones whose removal breaks the recipe stay, so a tiered module
     * with a custom name keeps the tier and loses the name. With no matching recipe
     * every slot goes plain, as the card always stored it. Runs once, on save, and stops
     * trying after [MAX_NORMALISE_LOOKUPS] recipe lookups, keeping whatever is left.
     */
    private fun normaliseGrid() {
        val grid = readGrid().toMutableList()
        if (grid.none { !it.isEmpty && it.componentsPatch.size() > 0 }) return
        val expected = resultFor(grid) ?: return
        var lookups = 1
        var changed = false
        fun sameResult(trial: List<ItemStack>): Boolean? {
            if (lookups >= MAX_NORMALISE_LOOKUPS) return null
            lookups++
            val result = resultFor(trial) ?: return null
            return !result.isEmpty && result.count == expected.count && ItemStack.isSameItemSameComponents(result, expected)
        }
        for (i in 0 until 9) {
            val stack = grid[i]
            if (stack.isEmpty || stack.componentsPatch.size() == 0) continue
            val plain = ItemStack(stack.item, 1)
            if (expected.isEmpty) {
                grid[i] = plain
                changed = true
                continue
            }
            val stripped = grid.toMutableList().also { it[i] = plain }
            when (sameResult(stripped)) {
                null -> return applyGrid(grid, changed)
                true -> {
                    grid[i] = plain
                    changed = true
                    continue
                }
                false -> {}
            }
            val original = stack.componentsPatch
            var kept = original
            for (type in original.entrySet().map { it.key }) {
                val without = kept.forget { it == type }
                val trial = grid.toMutableList().also { it[i] = ItemStack(stack.item, 1).apply { applyComponents(without) } }
                when (sameResult(trial)) {
                    null -> {
                        if (kept !== original) {
                            grid[i] = ItemStack(stack.item, 1).apply { applyComponents(kept) }
                            changed = true
                        }
                        return applyGrid(grid, changed)
                    }
                    true -> kept = without
                    false -> {}
                }
            }
            if (kept !== original) {
                grid[i] = ItemStack(stack.item, 1).apply { applyComponents(kept) }
                changed = true
            }
        }
        applyGrid(grid, changed)
    }

    private fun applyGrid(grid: List<ItemStack>, changed: Boolean) {
        if (!changed) return
        for (i in 0 until 9) recipeGrid.setItem(i, grid[i])
    }

    override fun clickMenuButton(player: Player, id: Int): Boolean {
        when (id) {
            // 0 = clear grid (matches InstructionSetScreen.BTN_ID_CLEAR_GRID)
            0 -> {
                for (i in 0..8) recipeGrid.setItem(i, ItemStack.EMPTY)
                updateResult()
                broadcastChanges()
                return true
            }
            // 1 = toggle substitutions (matches InstructionSetScreen.BTN_ID_TOGGLE_SUBSTITUTIONS)
            1 -> {
                substitutionsData.set(0, if (getSubstitutions()) 0 else 1)
                return true
            }
        }
        return false
    }

    override fun clicked(slotId: Int, button: Int, clickType: net.minecraft.world.inventory.ContainerInput, player: Player) {
        if (slotId in 0..8) {
            val carried = carried
            if (carried.isEmpty) {
                recipeGrid.setItem(slotId, ItemStack.EMPTY)
            } else {
                recipeGrid.setItem(slotId, carried.copyWithCount(1))
            }
            updateResult()
            return
        }
        // Don't allow clicking the result slot
        if (slotId == 9) return
        super.clicked(slotId, button, clickType, player)
    }

    override fun quickMoveStack(player: Player, slotIndex: Int): ItemStack {
        // Shift-click from player inventory → copy to first empty ghost slot
        if (slotIndex >= 10) { // 0-8 = grid, 9 = result, 10+ = player inv
            val slot = slots.getOrNull(slotIndex) ?: return ItemStack.EMPTY
            if (!slot.hasItem()) return ItemStack.EMPTY
            val stack = slot.item
            for (i in 0..8) {
                if (recipeGrid.getItem(i).isEmpty) {
                    recipeGrid.setItem(i, stack.copyWithCount(1))
                    updateResult()
                    break
                }
            }
        }
        return ItemStack.EMPTY
    }

    override fun removed(player: Player) {
        super.removed(player)
        if (player.level().isClientSide) return
        // Skip the save (and the >1-stack split-copy side effect) when the user
        // closes without changing anything. See [initialRecipe] for the bug
        // this guards against.
        val subs = getSubstitutions()
        if (sameGrid(readGrid(), initialRecipe) && subs == initialSubstitutionsValue) {
            backfillOutputStack(player)
            return
        }
        normaliseGrid()
        updateResult()
        saveRecipe(player, readGrid(), subs)
    }

    /** Give an older card its output stack on open. Only adds the component, and only
     *  when the result is the item the card already outputs. */
    private fun backfillOutputStack(player: Player) {
        val result = resultContainer.getItem(0)
        if (result.isEmpty) return
        val card = when (val mode = saveMode) {
            is SaveMode.Handheld -> player.getItemInHand(mode.hand)
            is SaveMode.InNode -> {
                val node = player.level().getBlockEntity(mode.nodePos) as? damien.nodeworks.block.entity.NodeBlockEntity ?: return
                val side = Direction.entries[mode.sideOrdinal]
                node.getItem(side.ordinal * damien.nodeworks.block.entity.NodeBlockEntity.SLOTS_PER_SIDE + mode.slotIndex)
            }
            is SaveMode.ClientDummy -> return
        }
        if (card.item !is InstructionSet) return
        if (card.has(damien.nodeworks.registry.ModDataComponents.INSTRUCTION_SET_OUTPUT)) return
        InstructionSet.setOutputStack(card, result)
        if (card.has(damien.nodeworks.registry.ModDataComponents.INSTRUCTION_SET_OUTPUT)) {
            (saveMode as? SaveMode.InNode)?.let { mode ->
                (player.level().getBlockEntity(mode.nodePos) as? damien.nodeworks.block.entity.NodeBlockEntity)?.setChanged()
            }
        }
    }

    /** The nine grid slots as copies, so later edits to the grid never reach a snapshot. */
    private fun readGrid(): List<ItemStack> = (0 until 9).map { i ->
        val stack = recipeGrid.getItem(i)
        if (stack.isEmpty) ItemStack.EMPTY else stack.copyWithCount(1)
    }

    private fun saveRecipe(player: Player, grid: List<ItemStack>, allowSubstitutions: Boolean) {
        val recipe = InstructionSet.idsOf(grid)
        val resultStack = resultContainer.getItem(0)
        val output = if (resultStack.isEmpty) "" else BuiltInRegistries.ITEM.getKey(resultStack.item)?.toString() ?: ""
        // Recipe-resolved yield (e.g. 3 for the door recipe). Persisted so the
        // item tooltip can render the count badge without a recipe lookup,
        // which isn't possible on the client (only the server has the full
        // RecipeManager).
        val outputCount = if (resultStack.isEmpty) 1 else resultStack.count

        when (val mode = saveMode) {
            is SaveMode.Handheld -> {
                val stack = player.getItemInHand(mode.hand)
                if (stack.item is InstructionSet) {
                    // InstructionSets stack to 64 with shared NBT, so writing the
                    // recipe directly into the held stack would programme every
                    // copy in it. Split off a single copy when the stack has more
                    // than one, apply the recipe to that copy alone, and bounce
                    // it into the player's inventory.
                    if (stack.count > 1) {
                        val configured = stack.copyWithCount(1)
                        InstructionSet.setRecipe(configured, recipe, output, outputCount, allowSubstitutions, resultStack, grid)
                        stack.shrink(1)
                        if (!player.inventory.add(configured)) {
                            player.drop(configured, false)
                        }
                    } else {
                        InstructionSet.setRecipe(stack, recipe, output, outputCount, allowSubstitutions, resultStack, grid)
                    }
                }
            }
            is SaveMode.InNode -> {
                val level = player.level()
                val nodeEntity = level.getBlockEntity(mode.nodePos) as? damien.nodeworks.block.entity.NodeBlockEntity ?: return
                val side = Direction.entries[mode.sideOrdinal]
                val globalSlot = side.ordinal * damien.nodeworks.block.entity.NodeBlockEntity.SLOTS_PER_SIDE + mode.slotIndex
                val cardStack = nodeEntity.getItem(globalSlot)
                if (cardStack.item is InstructionSet) {
                    InstructionSet.setRecipe(cardStack, recipe, output, outputCount, allowSubstitutions, resultStack, grid)
                    nodeEntity.setChanged()
                }
            }
            is SaveMode.ClientDummy -> {} // no-op
        }
    }

    override fun stillValid(player: Player): Boolean = true

    fun getRecipeGrid(): Container = recipeGrid

    /** Sets the 3x3 recipe grid from recipe view stacks (used by JEI integration). One
     *  recipe lookup, the same as a click, so a flood of these packets costs no more than
     *  a flood of clicks. Components the recipe does not need are dropped on save. */
    fun setRecipeFromStacks(items: List<ItemStack>) {
        for (i in 0 until minOf(9, items.size)) {
            val stack = items[i]
            recipeGrid.setItem(i, if (stack.isEmpty) ItemStack.EMPTY else stack.copyWithCount(1))
        }
        updateResult()
        broadcastChanges()
    }
}
