package damien.nodeworks.screen

import damien.nodeworks.card.ProcessingSet
import damien.nodeworks.registry.ModScreenHandlers
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.world.Container
import net.minecraft.world.InteractionHand
import net.minecraft.world.SimpleContainer
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.ContainerData
import net.minecraft.world.inventory.SimpleContainerData
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack

/**
 * Screen handler for the Processing Set editor.
 * Ghost slots: 9 inputs (3x3) + 3 outputs, with count/timeout data.
 *
 * ContainerData layout (15 slots):
 *   0-8:  input counts
 *   9-11: output counts
 *   12:   timeout
 */
class ProcessingSetScreenHandler(
    syncId: Int,
    private val playerInventory: Inventory,
    private val inputGrid: SimpleContainer,
    private val outputGrid: SimpleContainer,
    private val data: ContainerData,
    private val saveMode: SaveMode
) : AbstractContainerMenu(ModScreenHandlers.PROCESSING_SET, syncId) {

    sealed class SaveMode {
        data class Handheld(val hand: InteractionHand) : SaveMode()
        object ClientDummy : SaveMode()
    }

    var cardName: String = ""
    var serial: Boolean = false
    var fuzzy: Boolean = false

    /** Set true the first time a user action mutates configurable state.
     *  [removed] skips the save (and the >1-stack split-copy side effect)
     *  when this stays false, so opening a stack of unprogrammed Processing
     *  Sets and closing the GUI without clicks doesn't write an empty
     *  handler to a split-off copy. */
    private var dirty: Boolean = false

    val inputCounts: IntArray get() = IntArray(INPUT_SLOTS) { data.get(it) }
    val outputCounts: IntArray get() = IntArray(OUTPUT_SLOTS) { data.get(INPUT_SLOTS + it) }
    val timeout: Int get() = data.get(DATA_TIMEOUT)

    companion object {
        const val INPUT_SLOTS = 9
        const val OUTPUT_SLOTS = 3
        const val TOTAL_GHOST_SLOTS = INPUT_SLOTS + OUTPUT_SLOTS // 12
        const val DATA_TIMEOUT = INPUT_SLOTS + OUTPUT_SLOTS // index 12
        const val DATA_COUNT = DATA_TIMEOUT + 1 // 13
        /** Server-side cap on per-set tick timeout. Mirrors [ProcessingSetScreen.TIMEOUT_MAX]
         *  so a tampered client packet can't push the value past the design ceiling. */
        const val TIMEOUT_MAX = 999

        fun createHandheld(syncId: Int, playerInventory: Inventory, hand: InteractionHand, stack: ItemStack): ProcessingSetScreenHandler {
            val registries = playerInventory.player.level().registryAccess()
            val inputs = ProcessingSet.getInputs(stack, registries)
            val inputSlots = ProcessingSet.getInputPositions(stack)
            val outputs = ProcessingSet.getOutputs(stack, registries)
            val outputSlots = ProcessingSet.getOutputPositions(stack)
            val timeout = ProcessingSet.getTimeout(stack)

            val inputGrid = SimpleContainer(INPUT_SLOTS)
            for ((i, ingr) in inputs.withIndex()) {
                val slot = inputSlots.getOrElse(i) { i }
                if (slot !in 0 until INPUT_SLOTS) continue
                // Stash the full component-bearing stack with count=1 (ghost slot
                // capacity). The recipe's authoritative count rides in [data].
                inputGrid.setItem(slot, ingr.stack.copyWithCount(1))
            }

            val outputGrid = SimpleContainer(OUTPUT_SLOTS)
            for ((i, ingr) in outputs.withIndex()) {
                val slot = outputSlots.getOrElse(i) { i }
                if (slot !in 0 until OUTPUT_SLOTS) continue
                outputGrid.setItem(slot, ingr.stack.copyWithCount(1))
            }

            val data = object : ContainerData {
                private val values = IntArray(DATA_COUNT)
                init {
                    // Default all slot counts to 1, empty slots also get 1 so edits
                    // don't start from 0 and force the user to re-enter everything.
                    for (i in 0 until INPUT_SLOTS) values[i] = 1
                    for (i in 0 until OUTPUT_SLOTS) values[INPUT_SLOTS + i] = 1
                    for ((i, ingr) in inputs.withIndex()) {
                        val slot = inputSlots.getOrElse(i) { i }
                        if (slot in 0 until INPUT_SLOTS) values[slot] = ingr.count
                    }
                    for ((i, ingr) in outputs.withIndex()) {
                        val slot = outputSlots.getOrElse(i) { i }
                        if (slot in 0 until OUTPUT_SLOTS) values[INPUT_SLOTS + slot] = ingr.count
                    }
                    values[DATA_TIMEOUT] = timeout
                }
                override fun get(index: Int): Int = values.getOrElse(index) { 0 }
                override fun set(index: Int, value: Int) { if (index in values.indices) values[index] = value }
                override fun getCount(): Int = DATA_COUNT
            }

            return ProcessingSetScreenHandler(syncId, playerInventory, inputGrid, outputGrid, data, SaveMode.Handheld(hand)).also {
                it.cardName = ProcessingSet.getCardName(stack)
                it.serial = ProcessingSet.isSerial(stack)
                it.fuzzy = ProcessingSet.isFuzzy(stack)
            }
        }

        fun clientFactory(syncId: Int, playerInventory: Inventory, openData: ProcessingSetOpenData): ProcessingSetScreenHandler {
            val inputGrid = SimpleContainer(INPUT_SLOTS)
            for ((i, ingr) in openData.inputs.withIndex()) {
                val slot = openData.inputSlots.getOrElse(i) { i }
                if (slot !in 0 until INPUT_SLOTS) continue
                inputGrid.setItem(slot, ingr.stack.copyWithCount(1))
            }

            val outputGrid = SimpleContainer(OUTPUT_SLOTS)
            for ((i, ingr) in openData.outputs.withIndex()) {
                val slot = openData.outputSlots.getOrElse(i) { i }
                if (slot !in 0 until OUTPUT_SLOTS) continue
                outputGrid.setItem(slot, ingr.stack.copyWithCount(1))
            }

            val data = SimpleContainerData(DATA_COUNT)
            for (i in 0 until INPUT_SLOTS) data.set(i, 1)
            for (i in 0 until OUTPUT_SLOTS) data.set(INPUT_SLOTS + i, 1)
            for ((i, ingr) in openData.inputs.withIndex()) {
                val slot = openData.inputSlots.getOrElse(i) { i }
                if (slot in 0 until INPUT_SLOTS) data.set(slot, ingr.count)
            }
            for ((i, ingr) in openData.outputs.withIndex()) {
                val slot = openData.outputSlots.getOrElse(i) { i }
                if (slot in 0 until OUTPUT_SLOTS) data.set(INPUT_SLOTS + slot, ingr.count)
            }
            data.set(DATA_TIMEOUT, openData.timeout)

            return ProcessingSetScreenHandler(syncId, playerInventory, inputGrid, outputGrid, data, SaveMode.ClientDummy).also {
                it.cardName = openData.name
                it.serial = openData.serial
                it.fuzzy = openData.fuzzy
            }
        }
    }

    init {
        // 9 input ghost slots, 3×3 grid, horizontally centered under the 180-wide frame
        // (input block spans x=36..90, output column at x=128, gap 90..128 hosts the arrow).
        for (row in 0..2) {
            for (col in 0..2) {
                addSlot(GhostSlot(inputGrid, row * 3 + col, 36 + col * 18, 13 + row * 18))
            }
        }

        for (i in 0 until OUTPUT_SLOTS) {
            addSlot(GhostSlot(outputGrid, i, 128, 13 + i * 18))
        }

        // Player inventory (3 rows), starts at x=10 so the 9-slot block (width 160)
        // is centered in the 180-wide frame (10 px padding on each side). y starts
        // at 140 to leave room for the crafting grid + recessed timeout/parallel panel.
        for (row in 0 until 3) {
            for (col in 0 until 9) {
                addSlot(Slot(playerInventory, col + row * 9 + 9, 9 + col * 18, 137 + row * 18))
            }
        }
        for (col in 0 until 9) {
            addSlot(Slot(playerInventory, col, 9 + col * 18, 195))
        }

        addDataSlots(data)
    }

    private class GhostSlot(container: Container, index: Int, x: Int, y: Int) : Slot(container, index, x, y) {
        override fun mayPlace(stack: ItemStack): Boolean = true
        override fun getMaxStackSize(): Int = 1
    }

    override fun clicked(slotId: Int, button: Int, clickType: net.minecraft.world.inventory.ContainerInput, player: Player) {
        if (slotId in 0 until TOTAL_GHOST_SLOTS) {
            val carried = carried
            if (carried.isEmpty) {
                // Empty-handed click clears the ghost slot and resets its count to 1.
                when {
                    slotId < INPUT_SLOTS -> {
                        inputGrid.setItem(slotId, ItemStack.EMPTY)
                        data.set(slotId, 1)
                    }
                    else -> {
                        val outIdx = slotId - INPUT_SLOTS
                        outputGrid.setItem(outIdx, ItemStack.EMPTY)
                        data.set(INPUT_SLOTS + outIdx, 1)
                    }
                }
            } else {
                // Left-click: inherit the carried stack's count so the recipe
                // picks up "4 ingots" when the player clicked with a stack of
                // 4. Right-click: always start at 1 and increment by 1 on each
                // subsequent right-click with the same item, so the player can
                // dial in a precise quantity from a full stack.
                //
                // Branch on (clickType, button): vanilla `mouseClicked` starts
                // a QUICK_CRAFT drag whenever the cursor is non-empty (even
                // for a stationary click that releases without a drag). The
                // QUICK_CRAFT(add) phase fires with [button] = mask(stage=1,
                // type), so left-drag-add carries button=1, right-drag-add
                // carries button=5. The PICKUP path only fires for the
                // zero-slot-drag fallback. Decoding the button properly here
                // is necessary or left-drag-add gets misread as a right click
                // and the count drops to 1 on the first click.
                //
                // Components (potion contents, custom name, enchantments) are
                // preserved so component-aware recipes work end-to-end.
                val isRightClick = when (clickType) {
                    net.minecraft.world.inventory.ContainerInput.PICKUP -> button == 1
                    net.minecraft.world.inventory.ContainerInput.QUICK_CRAFT -> ((button shr 2) and 3) == 1
                    else -> false
                }
                val isInput = slotId < INPUT_SLOTS
                val outIdx = if (isInput) -1 else slotId - INPUT_SLOTS
                val dataIdx = if (isInput) slotId else INPUT_SLOTS + outIdx
                val existing = if (isInput) inputGrid.getItem(slotId) else outputGrid.getItem(outIdx)
                val sameItem = !existing.isEmpty &&
                    ItemStack.isSameItemSameComponents(existing, carried)
                val newCount = if (isRightClick) {
                    if (sameItem) (data.get(dataIdx) + 1).coerceAtMost(Short.MAX_VALUE.toInt()) else 1
                } else {
                    carried.count.coerceAtLeast(1)
                }
                if (isInput) inputGrid.setItem(slotId, carried.copyWithCount(1))
                else outputGrid.setItem(outIdx, carried.copyWithCount(1))
                data.set(dataIdx, newCount)
            }
            dirty = true
            return
        }
        super.clicked(slotId, button, clickType, player)
    }

    override fun quickMoveStack(player: Player, slotIndex: Int): ItemStack {
        if (slotIndex >= TOTAL_GHOST_SLOTS) {
            val slot = slots.getOrNull(slotIndex) ?: return ItemStack.EMPTY
            if (!slot.hasItem()) return ItemStack.EMPTY
            val stack = slot.item
            for (i in 0 until INPUT_SLOTS) {
                if (inputGrid.getItem(i).isEmpty) {
                    // Carry the full stack (with components) into the ghost slot,
                    // not just the item id, so shift-clicked potions and dyed
                    // armor land as their specific variant.
                    inputGrid.setItem(i, stack.copyWithCount(1))
                    dirty = true
                    break
                }
            }
        }
        return ItemStack.EMPTY
    }

    fun setInputCount(slotIndex: Int, count: Int) {
        if (slotIndex in 0 until INPUT_SLOTS) {
            data.set(slotIndex, maxOf(1, count))
            dirty = true
        }
    }

    fun setOutputCount(slotIndex: Int, count: Int) {
        if (slotIndex in 0 until OUTPUT_SLOTS) {
            data.set(INPUT_SLOTS + slotIndex, maxOf(1, count))
            dirty = true
        }
    }

    fun setTimeout(timeout: Int) {
        data.set(DATA_TIMEOUT, timeout.coerceIn(0, TIMEOUT_MAX))
        dirty = true
    }

    /** Set a ghost slot to [stack] (or clear it if empty). Used by the JEI
     *  ghost-ingredient drag and the recipe-transfer button. Preserves the
     *  stack's components so dragging "Potion of Strength" into a ghost slot
     *  lands the actual strength variant, not a bare uncraftable potion. */
    fun setSlotFromStack(slotIndex: Int, stack: ItemStack) {
        val placed = if (stack.isEmpty) ItemStack.EMPTY else stack.copyWithCount(1)
        when {
            slotIndex < INPUT_SLOTS -> inputGrid.setItem(slotIndex, placed)
            slotIndex < TOTAL_GHOST_SLOTS -> outputGrid.setItem(slotIndex - INPUT_SLOTS, placed)
        }
        dirty = true
    }

    /** Mark the recipe as edited from outside the slot/click path (e.g. UI controls
     *  that toggle `serial` or `cardName`). Without this, toggling serial mode and
     *  closing the GUI wouldn't persist the change. */
    fun markDirty() {
        dirty = true
    }

    override fun removed(player: Player) {
        super.removed(player)
        if (player.level().isClientSide) return
        if (!dirty) return
        saveRecipe(player)
    }

    private fun saveRecipe(player: Player) {
        val inputs = mutableListOf<damien.nodeworks.script.RecipeIngredient>()
        val inputSlots = mutableListOf<Int>()
        for (i in 0 until INPUT_SLOTS) {
            val stack = inputGrid.getItem(i)
            if (stack.isEmpty) continue
            val count = data.get(i).coerceAtLeast(1)
            // copyWithCount(1) snapshots item + components without leaking the
            // ghost slot's runtime count into the recipe definition.
            inputs.add(damien.nodeworks.script.RecipeIngredient(stack.copyWithCount(1), count))
            inputSlots.add(i)
        }

        val outputs = mutableListOf<damien.nodeworks.script.RecipeIngredient>()
        val outputSlots = mutableListOf<Int>()
        for (i in 0 until OUTPUT_SLOTS) {
            val stack = outputGrid.getItem(i)
            if (stack.isEmpty) continue
            val count = data.get(INPUT_SLOTS + i).coerceAtLeast(1)
            outputs.add(damien.nodeworks.script.RecipeIngredient(stack.copyWithCount(1), count))
            outputSlots.add(i)
        }

        val timeout = data.get(DATA_TIMEOUT).coerceAtLeast(0)

        when (val mode = saveMode) {
            is SaveMode.Handheld -> {
                val stack = player.getItemInHand(mode.hand)
                if (stack.item is ProcessingSet) {
                    // Recipe identity is now a hash of the full ingredient structure
                    // (including components and the fuzzy flag) so identical recipes
                    // authored separately collapse under one entry and component
                    // variants stay distinct. See [RecipeId].
                    val displayName = if (cardName.isNotEmpty()) cardName else {
                        outputs.firstOrNull()?.stack?.hoverName?.string ?: ""
                    }
                    val registryAccess = player.level().registryAccess()
                    // ProcessingSets stack to 64 with shared NBT, so writing the
                    // recipe into the held stack would programme every copy.
                    // Split off a single copy when the stack has more than one,
                    // apply the recipe to that copy alone, and bounce it into the
                    // player's inventory.
                    if (stack.count > 1) {
                        val configured = stack.copyWithCount(1)
                        ProcessingSet.setRecipe(
                            configured, displayName, inputs, outputs, timeout,
                            fuzzy = fuzzy,
                            serial = serial,
                            inputPositions = inputSlots.toIntArray(),
                            outputPositions = outputSlots.toIntArray(),
                            registryAccess = registryAccess,
                        )
                        stack.shrink(1)
                        if (!player.inventory.add(configured)) {
                            player.drop(configured, false)
                        }
                    } else {
                        ProcessingSet.setRecipe(
                            stack, displayName, inputs, outputs, timeout,
                            fuzzy = fuzzy,
                            serial = serial,
                            inputPositions = inputSlots.toIntArray(),
                            outputPositions = outputSlots.toIntArray(),
                            registryAccess = registryAccess,
                        )
                    }
                }
            }
            is SaveMode.ClientDummy -> {}
        }
    }

    override fun stillValid(player: Player): Boolean = true
}
