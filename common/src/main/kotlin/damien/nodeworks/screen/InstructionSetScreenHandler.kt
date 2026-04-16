package damien.nodeworks.screen

import damien.nodeworks.card.InstructionSet
import damien.nodeworks.registry.ModScreenHandlers
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.Container
import net.minecraft.world.InteractionHand
import net.minecraft.world.SimpleContainer
import net.minecraft.world.item.crafting.CraftingInput
import net.minecraft.world.item.crafting.RecipeType
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack

/**
 * Screen handler for the Instruction Set's 3x3 crafting template editor.
 * Supports two modes: handheld (card in player's hand) or node-based (card in a node slot).
 */
class InstructionSetScreenHandler(
    syncId: Int,
    private val playerInventory: Inventory,
    private val recipeGrid: SimpleContainer,
    private val saveMode: SaveMode
) : AbstractContainerMenu(ModScreenHandlers.INSTRUCTION_SET, syncId) {

    private val resultContainer = SimpleContainer(1)

    sealed class SaveMode {
        data class Handheld(val hand: InteractionHand) : SaveMode()
        data class InNode(val nodePos: BlockPos, val sideOrdinal: Int, val slotIndex: Int) : SaveMode()
        object ClientDummy : SaveMode()
    }

    companion object {
        private fun recipeToGrid(recipe: List<String>): SimpleContainer {
            val grid = SimpleContainer(9)
            for (i in 0 until 9) {
                if (recipe[i].isNotEmpty()) {
                    val id = ResourceLocation.tryParse(recipe[i]) ?: continue
                    val item = BuiltInRegistries.ITEM.get(id) ?: continue
                    grid.setItem(i, ItemStack(item, 1))
                }
            }
            return grid
        }

        fun createHandheld(syncId: Int, playerInventory: Inventory, hand: InteractionHand, stack: ItemStack): InstructionSetScreenHandler {
            val recipe = InstructionSet.getRecipe(stack)
            return InstructionSetScreenHandler(syncId, playerInventory, recipeToGrid(recipe), SaveMode.Handheld(hand))
        }

        fun createServer(syncId: Int, playerInventory: Inventory, nodePos: BlockPos, side: Direction, slotIndex: Int, stack: ItemStack): InstructionSetScreenHandler {
            val recipe = InstructionSet.getRecipe(stack)
            return InstructionSetScreenHandler(syncId, playerInventory, recipeToGrid(recipe), SaveMode.InNode(nodePos, side.ordinal, slotIndex))
        }

        fun clientFactory(syncId: Int, playerInventory: Inventory, data: InstructionSetOpenData): InstructionSetScreenHandler {
            return InstructionSetScreenHandler(syncId, playerInventory, recipeToGrid(data.recipe), SaveMode.ClientDummy)
        }
    }

    init {
        // 3x3 recipe grid — ghost slots (slots 0-8). Positions match InstructionSetScreen.
        for (row in 0..2) {
            for (col in 0..2) {
                addSlot(GhostSlot(recipeGrid, row * 3 + col, 36 + col * 18, 13 + row * 18))
            }
        }

        // Result slot (slot 9) — display only, vertically centered on the middle row.
        addSlot(ResultSlot(resultContainer, 0, 128, 31))

        // Player inventory — matches ProcessingSetScreen layout (9 cols at x=9, inv y=95, hotbar y=153).
        // INV_GRID_Y = 80+14 = 94, so slot y = 95. Hotbar y = 95 + 54 + 4 = 153.
        for (row in 0 until 3) {
            for (col in 0 until 9) {
                addSlot(net.minecraft.world.inventory.Slot(playerInventory, col + row * 9 + 9, 9 + col * 18, 95 + row * 18))
            }
        }
        for (col in 0 until 9) {
            addSlot(net.minecraft.world.inventory.Slot(playerInventory, col, 9 + col * 18, 153))
        }

        updateResult()
    }

    private class GhostSlot(container: Container, index: Int, x: Int, y: Int) : Slot(container, index, x, y) {
        override fun mayPlace(stack: ItemStack): Boolean = true
        override fun getMaxStackSize(): Int = 1
    }

    /** Display-only slot that shows the crafting result. */
    private class ResultSlot(container: Container, index: Int, x: Int, y: Int) : Slot(container, index, x, y) {
        override fun mayPlace(stack: ItemStack): Boolean = false
        override fun mayPickup(player: Player): Boolean = false
    }

    /** Looks up the crafting result from the recipe manager and updates the result slot. */
    private fun updateResult() {
        val level = playerInventory.player.level()
        if (level.isClientSide) return // Recipe lookup is server-side only

        val serverLevel = level as? net.minecraft.server.level.ServerLevel ?: return
        val recipeManager = serverLevel.getRecipeManager() ?: return

        val items = (0 until 9).map { recipeGrid.getItem(it) }
        val input = CraftingInput.of(3, 3, items)

        val result = recipeManager
            .getRecipeFor(RecipeType.CRAFTING, input, level)
            .map { it.value().assemble(input, level.registryAccess()) }
            .orElse(ItemStack.EMPTY)

        resultContainer.setItem(0, result)
    }

    override fun clickMenuButton(player: Player, id: Int): Boolean {
        // ID 0 — triggered by the clear-all button in InstructionSetScreen. Wipes every
        // ghost slot in the 3×3 recipe grid and recomputes the result.
        if (id == 0) {
            for (i in 0..8) recipeGrid.setItem(i, ItemStack.EMPTY)
            updateResult()
            broadcastChanges()
            return true
        }
        return false
    }

    override fun clicked(slotId: Int, button: Int, clickType: net.minecraft.world.inventory.ClickType, player: Player) {
        if (slotId in 0..8) {
            val carried = carried
            if (carried.isEmpty) {
                recipeGrid.setItem(slotId, ItemStack.EMPTY)
            } else {
                recipeGrid.setItem(slotId, ItemStack(carried.item, 1))
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
                    recipeGrid.setItem(i, ItemStack(stack.item, 1))
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
        saveRecipe(player)
    }

    private fun saveRecipe(player: Player) {
        val recipe = (0 until 9).map { i ->
            val stack = recipeGrid.getItem(i)
            if (stack.isEmpty) "" else BuiltInRegistries.ITEM.getKey(stack.item)?.toString() ?: ""
        }
        val resultStack = resultContainer.getItem(0)
        val output = if (resultStack.isEmpty) "" else BuiltInRegistries.ITEM.getKey(resultStack.item)?.toString() ?: ""

        when (val mode = saveMode) {
            is SaveMode.Handheld -> {
                val stack = player.getItemInHand(mode.hand)
                if (stack.item is InstructionSet) {
                    InstructionSet.setRecipe(stack, recipe, output)
                }
            }
            is SaveMode.InNode -> {
                val level = player.level()
                val nodeEntity = level.getBlockEntity(mode.nodePos) as? damien.nodeworks.block.entity.NodeBlockEntity ?: return
                val side = Direction.entries[mode.sideOrdinal]
                val globalSlot = side.ordinal * damien.nodeworks.block.entity.NodeBlockEntity.SLOTS_PER_SIDE + mode.slotIndex
                val cardStack = nodeEntity.getItem(globalSlot)
                if (cardStack.item is InstructionSet) {
                    InstructionSet.setRecipe(cardStack, recipe, output)
                    nodeEntity.setChanged()
                }
            }
            is SaveMode.ClientDummy -> {} // no-op
        }
    }

    override fun stillValid(player: Player): Boolean = true

    fun getRecipeGrid(): Container = recipeGrid

    /** Sets the 3x3 recipe grid from a list of item ID strings (used by JEI integration). */
    fun setRecipeFromIds(items: List<String>) {
        for (i in 0 until minOf(9, items.size)) {
            if (items[i].isEmpty()) {
                recipeGrid.setItem(i, ItemStack.EMPTY)
            } else {
                val id = ResourceLocation.tryParse(items[i]) ?: continue
                val item = BuiltInRegistries.ITEM.get(id) ?: continue
                recipeGrid.setItem(i, ItemStack(item, 1))
            }
        }
        updateResult()
        broadcastChanges()
    }
}
