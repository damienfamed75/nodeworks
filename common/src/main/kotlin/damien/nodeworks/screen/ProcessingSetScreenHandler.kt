package damien.nodeworks.screen

import damien.nodeworks.card.ProcessingSet
import damien.nodeworks.registry.ModScreenHandlers
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
 *
 * The recipe lives in two backing containers, 27 inputs as a 3 wide by 9 tall grid and 9
 * outputs as a column. The screen shows a 3x3 window of the inputs and 3 of the outputs
 * and scrolls them on the client alone, the way the creative inventory scrolls its items:
 *
 *  - The window cells are vanilla slots at fixed positions over a [WindowView], which on
 *    the client reads the backing grid at the screen's scroll offset and on the server is
 *    always empty. The view ignores writes, so vanilla's sync of the server's empty window
 *    cannot touch the client's backing grid.
 *  - The backing grids are exposed through hidden slots placed off screen, so vanilla's
 *    per-slot sync carries every cell to every viewer and two players editing one card
 *    stay in step, each with their own window.
 *  - A click on a window cell never reaches [clicked]. The screen maps it to a backing
 *    index and sends it as a packet, the server applies [applyGhostClick], and the client
 *    applies the same rule to its own copy at once.
 *
 * ContainerData layout: 0..26 input counts, 27..35 output counts, 36 timeout.
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

    /** Tag each input cell accepts any member of while [fuzzy] is on, null for exact. Set
     *  from a JEI transfer, cleared whenever the cell's item changes. By backing index. */
    val inputTags: Array<String?> = arrayOfNulls(INPUT_SLOTS)

    /** Output cells the machine only sometimes fills. Toggled by right-clicking the cell
     *  with an empty cursor, cleared when the cell's item changes. By backing index. */
    val outputOptional: BooleanArray = BooleanArray(OUTPUT_SLOTS)

    /** Set true the first time a user action mutates configurable state.
     *  [removed] skips the save (and the >1-stack split-copy side effect)
     *  when this stays false, so opening a stack of unprogrammed Processing
     *  Sets and closing the GUI without clicks doesn't write an empty
     *  handler to a split-off copy. */
    private var dirty: Boolean = false

    /** The windows the screen shows, see [WindowView]. The screen owns the offset. */
    val inputWindow = WindowView(inputGrid, INPUT_COLS, saveMode is SaveMode.ClientDummy)
    val outputWindow = WindowView(outputGrid, 1, saveMode is SaveMode.ClientDummy)

    /** The row both windows start at. Client only, nothing reads it on the server. */
    var scrollRow: Int
        get() = inputWindow.offsetRow
        set(value) {
            val row = value.coerceIn(0, MAX_SCROLL)
            inputWindow.offsetRow = row
            outputWindow.offsetRow = row
        }

    /** The backing input cell the last shift-click from the inventory filled, or -1. The
     *  screen reads and clears it to scroll that cell into view. */
    var lastQuickMoveTarget: Int = -1

    val inputCounts: IntArray get() = IntArray(INPUT_SLOTS) { data.get(it) }
    val outputCounts: IntArray get() = IntArray(OUTPUT_SLOTS) { data.get(INPUT_SLOTS + it) }
    val timeout: Int get() = data.get(DATA_TIMEOUT)

    companion object {
        const val INPUT_COLS = 3
        const val INPUT_ROWS = 9
        const val OUTPUT_ROWS = 9
        const val VISIBLE_ROWS = 3
        /** Backing sizes. Payload indices and the tag and optional arrays use these. */
        const val INPUT_SLOTS = INPUT_COLS * INPUT_ROWS
        const val OUTPUT_SLOTS = OUTPUT_ROWS
        /** Linear backing space the slot packets address: inputs then outputs. */
        const val TOTAL_GHOST_SLOTS = INPUT_SLOTS + OUTPUT_SLOTS
        /** Window cells, menu slot ids 0 until this: inputs first, then outputs. */
        const val VISIBLE_INPUT_SLOTS = INPUT_COLS * VISIBLE_ROWS
        const val VISIBLE_OUTPUT_SLOTS = VISIBLE_ROWS
        const val WINDOW_SLOTS = VISIBLE_INPUT_SLOTS + VISIBLE_OUTPUT_SLOTS
        /** Menu slot id of the first player inventory slot. Hidden backing slots sit between. */
        const val PLAYER_SLOT_START = WINDOW_SLOTS + TOTAL_GHOST_SLOTS
        /** The grid and the column are the same height and scroll together under one bar. */
        const val MAX_SCROLL = INPUT_ROWS - VISIBLE_ROWS
        const val DATA_TIMEOUT = TOTAL_GHOST_SLOTS
        const val DATA_COUNT = DATA_TIMEOUT + 1
        /** Server-side cap on per-set tick timeout. Mirrors [ProcessingSetScreen.TIMEOUT_MAX]
         *  so a tampered client packet can't push the value past the design ceiling. */
        const val TIMEOUT_MAX = 999
        /** Per-cell count ceiling. ContainerData travels as shorts, so anything above this
         *  would wrap on the wire, and the click path already stops here. */
        const val MAX_COUNT = Short.MAX_VALUE.toInt()

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
                // Stash the full component-bearing stack with count=1 (ghost cell
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
                    // Default all cell counts to 1, empty cells also get 1 so edits
                    // don't start from 0 and force the user to re-enter everything.
                    for (i in 0 until INPUT_SLOTS) values[i] = 1
                    for (i in 0 until OUTPUT_SLOTS) values[INPUT_SLOTS + i] = 1
                    for ((i, ingr) in inputs.withIndex()) {
                        val slot = inputSlots.getOrElse(i) { i }
                        if (slot in 0 until INPUT_SLOTS) values[slot] = ingr.count.coerceIn(1, MAX_COUNT)
                    }
                    for ((i, ingr) in outputs.withIndex()) {
                        val slot = outputSlots.getOrElse(i) { i }
                        if (slot in 0 until OUTPUT_SLOTS) values[INPUT_SLOTS + slot] = ingr.count.coerceIn(1, MAX_COUNT)
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
                for ((i, ingr) in inputs.withIndex()) {
                    val slot = inputSlots.getOrElse(i) { i }
                    if (slot in 0 until INPUT_SLOTS) it.inputTags[slot] = ingr.tag
                }
                for ((i, ingr) in outputs.withIndex()) {
                    val slot = outputSlots.getOrElse(i) { i }
                    if (slot in 0 until OUTPUT_SLOTS) it.outputOptional[slot] = ingr.optional
                }
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
                if (slot in 0 until INPUT_SLOTS) data.set(slot, ingr.count.coerceIn(1, MAX_COUNT))
            }
            for ((i, ingr) in openData.outputs.withIndex()) {
                val slot = openData.outputSlots.getOrElse(i) { i }
                if (slot in 0 until OUTPUT_SLOTS) data.set(INPUT_SLOTS + slot, ingr.count.coerceIn(1, MAX_COUNT))
            }
            data.set(DATA_TIMEOUT, openData.timeout)

            return ProcessingSetScreenHandler(syncId, playerInventory, inputGrid, outputGrid, data, SaveMode.ClientDummy).also {
                it.cardName = openData.name
                it.serial = openData.serial
                it.fuzzy = openData.fuzzy
                for ((i, ingr) in openData.inputs.withIndex()) {
                    val slot = openData.inputSlots.getOrElse(i) { i }
                    if (slot in 0 until INPUT_SLOTS) it.inputTags[slot] = ingr.tag
                }
                for ((i, ingr) in openData.outputs.withIndex()) {
                    val slot = openData.outputSlots.getOrElse(i) { i }
                    if (slot in 0 until OUTPUT_SLOTS) it.outputOptional[slot] = ingr.optional
                }
            }
        }
    }

    /**
     * The cells on screen: [VISIBLE_ROWS] rows of [cols] over [backing], read at
     * [offsetRow]. Only a live view, the client's, reads anything, the server's is always
     * empty since the server never needs the window. Writes are ignored: vanilla syncs the
     * server's empty window into the client's view on open, and that must not reach the
     * backing grid, which the hidden slots keep in step on their own.
     */
    class WindowView(private val backing: Container, private val cols: Int, private val live: Boolean) : Container {
        var offsetRow: Int = 0
        private val size = cols * VISIBLE_ROWS

        fun backingIndex(visible: Int): Int = offsetRow * cols + visible

        override fun getContainerSize(): Int = size
        override fun isEmpty(): Boolean = !live || (0 until size).all { getItem(it).isEmpty }
        override fun getItem(slot: Int): ItemStack =
            if (!live || slot !in 0 until size) ItemStack.EMPTY else backing.getItem(backingIndex(slot))
        override fun removeItem(slot: Int, amount: Int): ItemStack = ItemStack.EMPTY
        override fun removeItemNoUpdate(slot: Int): ItemStack = ItemStack.EMPTY
        override fun setItem(slot: Int, stack: ItemStack) {}
        override fun setChanged() {}
        override fun stillValid(player: Player): Boolean = true
        override fun clearContent() {}
    }

    init {
        // The window: 9 input cells, 3x3, horizontally centered under the 180-wide frame
        // (input block spans x=36..90, output column at x=128, gap 90..128 hosts the arrow).
        for (row in 0 until VISIBLE_ROWS) {
            for (col in 0 until INPUT_COLS) {
                addSlot(GhostSlot(inputWindow, row * INPUT_COLS + col, 36 + col * 18, 13 + row * 18))
            }
        }
        for (i in 0 until VISIBLE_OUTPUT_SLOTS) {
            addSlot(GhostSlot(outputWindow, i, 128, 13 + i * 18))
        }

        // The backing grids, carried to every viewer by vanilla's slot sync. Off screen,
        // inactive, never drawn, never clickable.
        for (i in 0 until INPUT_SLOTS) addSlot(HiddenSlot(inputGrid, i))
        for (i in 0 until OUTPUT_SLOTS) addSlot(HiddenSlot(outputGrid, i))

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

    /** A window cell. Vanilla draws and highlights it, the screen handles every click. */
    private class GhostSlot(container: Container, index: Int, x: Int, y: Int) : Slot(container, index, x, y) {
        override fun mayPlace(stack: ItemStack): Boolean = false
        override fun mayPickup(player: Player): Boolean = false
        override fun getMaxStackSize(): Int = 1
    }

    /** A backing cell on the wire only. */
    private class HiddenSlot(container: Container, index: Int) : Slot(container, index, -999, -999) {
        override fun isActive(): Boolean = false
        override fun mayPlace(stack: ItemStack): Boolean = false
        override fun mayPickup(player: Player): Boolean = false
    }

    /** Window and hidden slots never take a vanilla click, a modified client included. The
     *  screen sends a click packet for a window cell instead, see [applyGhostClick]. */
    override fun clicked(slotId: Int, button: Int, clickType: net.minecraft.world.inventory.ContainerInput, player: Player) {
        if (slotId in 0 until PLAYER_SLOT_START) return
        super.clicked(slotId, button, clickType, player)
    }

    /**
     * The editor's one placement rule, run on the server for a click packet and on the
     * client before sending it so the cell changes under the cursor. [index] is a backing
     * index on its side. An empty hand clears the cell and resets its count. With the
     * cursor full, a left click places the carried stack with its count so "4 ingots"
     * comes from clicking with a stack of 4, a right click places one or adds one to the
     * same item so a precise count can be dialled in. A right click on a filled output
     * with an empty hand toggles optional while another guaranteed output remains.
     * Components are kept so component-aware recipes work end to end.
     */
    fun applyGhostClick(index: Int, isOutput: Boolean, rightClick: Boolean) {
        val grid = if (isOutput) outputGrid else inputGrid
        val size = if (isOutput) OUTPUT_SLOTS else INPUT_SLOTS
        if (index !in 0 until size) return
        val dataIdx = if (isOutput) INPUT_SLOTS + index else index
        val carried = carried
        if (carried.isEmpty) {
            if (isOutput && rightClick && !grid.getItem(index).isEmpty) {
                if (outputOptional[index] || hasOtherGuaranteed(index)) outputOptional[index] = !outputOptional[index]
            } else {
                grid.setItem(index, ItemStack.EMPTY)
                if (isOutput) outputOptional[index] = false else inputTags[index] = null
                data.set(dataIdx, 1)
            }
        } else {
            val existing = grid.getItem(index)
            val sameItem = !existing.isEmpty && ItemStack.isSameItemSameComponents(existing, carried)
            val newCount = if (rightClick) {
                if (sameItem) (data.get(dataIdx) + 1).coerceAtMost(MAX_COUNT) else 1
            } else {
                carried.count.coerceAtLeast(1)
            }
            grid.setItem(index, carried.copyWithCount(1))
            if (isOutput) {
                if (!sameItem) outputOptional[index] = false
            } else {
                inputTags[index] = null
            }
            data.set(dataIdx, newCount)
        }
        dirty = true
    }

    override fun quickMoveStack(player: Player, slotIndex: Int): ItemStack {
        if (slotIndex >= PLAYER_SLOT_START) {
            val slot = slots.getOrNull(slotIndex) ?: return ItemStack.EMPTY
            if (!slot.hasItem()) return ItemStack.EMPTY
            val stack = slot.item
            for (i in 0 until INPUT_SLOTS) {
                if (inputGrid.getItem(i).isEmpty) {
                    // Carry the full stack (with components) into the ghost cell,
                    // not just the item id, so shift-clicked potions and dyed
                    // armor land as their specific variant.
                    inputGrid.setItem(i, stack.copyWithCount(1))
                    lastQuickMoveTarget = i
                    dirty = true
                    break
                }
            }
        }
        return ItemStack.EMPTY
    }

    fun setInputCount(slotIndex: Int, count: Int) {
        if (slotIndex in 0 until INPUT_SLOTS) {
            data.set(slotIndex, count.coerceIn(1, MAX_COUNT))
            dirty = true
        }
    }

    fun setOutputCount(slotIndex: Int, count: Int) {
        if (slotIndex in 0 until OUTPUT_SLOTS) {
            data.set(INPUT_SLOTS + slotIndex, count.coerceIn(1, MAX_COUNT))
            dirty = true
        }
    }

    fun setTimeout(timeout: Int) {
        data.set(DATA_TIMEOUT, timeout.coerceIn(0, TIMEOUT_MAX))
        dirty = true
    }

    /** Set a backing cell to [stack] (or clear it if empty), [slotIndex] in the linear
     *  space of inputs then outputs. Used by the JEI ghost-ingredient drag and the
     *  recipe-transfer button. Preserves the stack's components so dragging "Potion of
     *  Strength" into a cell lands the actual strength variant, not a bare uncraftable
     *  potion. */
    fun setSlotFromStack(slotIndex: Int, stack: ItemStack) {
        val placed = if (stack.isEmpty) ItemStack.EMPTY else stack.copyWithCount(1)
        when {
            slotIndex < 0 -> return
            slotIndex < INPUT_SLOTS -> {
                inputGrid.setItem(slotIndex, placed)
                inputTags[slotIndex] = null
            }
            slotIndex < TOTAL_GHOST_SLOTS -> {
                outputGrid.setItem(slotIndex - INPUT_SLOTS, placed)
                outputOptional[slotIndex - INPUT_SLOTS] = false
            }
            else -> return
        }
        dirty = true
    }

    /** Empties every cell and resets every count, one packet from the clear button rather
     *  than one per cell. */
    fun clearAll() {
        for (i in 0 until INPUT_SLOTS) {
            inputGrid.setItem(i, ItemStack.EMPTY)
            data.set(i, 1)
        }
        for (i in 0 until OUTPUT_SLOTS) {
            outputGrid.setItem(i, ItemStack.EMPTY)
            data.set(INPUT_SLOTS + i, 1)
        }
        inputTags.fill(null)
        outputOptional.fill(false)
        dirty = true
    }

    /** Set the tag input [slotIndex] accepts, or clear it with null. Only kept when the
     *  cell's item is a member, so a stale or forged tag can never widen a recipe. */
    fun setInputTag(slotIndex: Int, tagId: String?) {
        if (slotIndex !in 0 until INPUT_SLOTS) return
        val item = inputGrid.getItem(slotIndex)
        val valid = tagId != null && !item.isEmpty && damien.nodeworks.card.ItemTagLookup.isMember(item.item, tagId)
        inputTags[slotIndex] = if (valid) tagId else null
        dirty = true
    }

    /** Mark output [slotIndex] optional or not. Only a filled cell can be optional. */
    fun setOutputOptional(slotIndex: Int, optional: Boolean) {
        if (slotIndex !in 0 until OUTPUT_SLOTS) return
        outputOptional[slotIndex] = optional && !outputGrid.getItem(slotIndex).isEmpty && hasOtherGuaranteed(slotIndex)
        dirty = true
    }

    /** A set must keep one guaranteed output, or nothing could ever count on it. */
    private fun hasOtherGuaranteed(except: Int): Boolean =
        (0 until OUTPUT_SLOTS).any { it != except && !outputGrid.getItem(it).isEmpty && !outputOptional[it] }

    /** True when an input or output cell at or past [fromRow] holds an item. */
    fun hasContentAtOrBelow(fromRow: Int): Boolean =
        ((fromRow * INPUT_COLS).coerceAtLeast(0) until INPUT_SLOTS).any { !inputGrid.getItem(it).isEmpty } ||
            (fromRow.coerceAtLeast(0) until OUTPUT_SLOTS).any { !outputGrid.getItem(it).isEmpty }

    /** True when an input or output cell before [row] holds an item. */
    fun hasContentAbove(row: Int): Boolean =
        (0 until (row * INPUT_COLS).coerceIn(0, INPUT_SLOTS)).any { !inputGrid.getItem(it).isEmpty } ||
            (0 until row.coerceIn(0, OUTPUT_SLOTS)).any { !outputGrid.getItem(it).isEmpty }

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
            // ghost cell's runtime count into the recipe definition.
            inputs.add(damien.nodeworks.script.RecipeIngredient(stack.copyWithCount(1), count, inputTags[i]))
            inputSlots.add(i)
        }

        val outputs = mutableListOf<damien.nodeworks.script.RecipeIngredient>()
        val outputSlots = mutableListOf<Int>()
        for (i in 0 until OUTPUT_SLOTS) {
            val stack = outputGrid.getItem(i)
            if (stack.isEmpty) continue
            val count = data.get(INPUT_SLOTS + i).coerceAtLeast(1)
            outputs.add(damien.nodeworks.script.RecipeIngredient(stack.copyWithCount(1), count, optional = outputOptional[i]))
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
