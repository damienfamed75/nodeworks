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

    val inputCounts: IntArray get() = IntArray(INPUT_SLOTS) { data.get(it) }
    val outputCounts: IntArray get() = IntArray(OUTPUT_SLOTS) { data.get(INPUT_SLOTS + it) }
    val timeout: Int get() = data.get(DATA_TIMEOUT)

    companion object {
        const val INPUT_SLOTS = 9
        const val OUTPUT_SLOTS = 3
        const val TOTAL_GHOST_SLOTS = INPUT_SLOTS + OUTPUT_SLOTS // 12
        const val DATA_TIMEOUT = INPUT_SLOTS + OUTPUT_SLOTS // index 12
        const val DATA_COUNT = DATA_TIMEOUT + 1 // 13

        fun createHandheld(syncId: Int, playerInventory: Inventory, hand: InteractionHand, stack: ItemStack): ProcessingSetScreenHandler {
            val inputs = ProcessingSet.getInputs(stack)
            val inputSlots = ProcessingSet.getInputPositions(stack)
            val outputs = ProcessingSet.getOutputs(stack)
            val outputSlots = ProcessingSet.getOutputPositions(stack)
            val timeout = ProcessingSet.getTimeout(stack)

            val inputGrid = SimpleContainer(INPUT_SLOTS)
            for ((i, pair) in inputs.withIndex()) {
                val slot = inputSlots.getOrElse(i) { i }
                if (slot !in 0 until INPUT_SLOTS) continue
                val id = Identifier.tryParse(pair.first) ?: continue
                val item = BuiltInRegistries.ITEM.getValue(id) ?: continue
                inputGrid.setItem(slot, ItemStack(item, 1))
            }

            val outputGrid = SimpleContainer(OUTPUT_SLOTS)
            for ((i, pair) in outputs.withIndex()) {
                val slot = outputSlots.getOrElse(i) { i }
                if (slot !in 0 until OUTPUT_SLOTS) continue
                val id = Identifier.tryParse(pair.first) ?: continue
                val item = BuiltInRegistries.ITEM.getValue(id) ?: continue
                outputGrid.setItem(slot, ItemStack(item, 1))
            }

            val data = object : ContainerData {
                private val values = IntArray(DATA_COUNT)
                init {
                    // Default all slot counts to 1 — empty slots also get 1 so edits
                    // don't start from 0 and force the user to re-enter everything.
                    for (i in 0 until INPUT_SLOTS) values[i] = 1
                    for (i in 0 until OUTPUT_SLOTS) values[INPUT_SLOTS + i] = 1
                    for ((i, pair) in inputs.withIndex()) {
                        val slot = inputSlots.getOrElse(i) { i }
                        if (slot in 0 until INPUT_SLOTS) values[slot] = pair.second
                    }
                    for ((i, pair) in outputs.withIndex()) {
                        val slot = outputSlots.getOrElse(i) { i }
                        if (slot in 0 until OUTPUT_SLOTS) values[INPUT_SLOTS + slot] = pair.second
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
            }
        }

        fun clientFactory(syncId: Int, playerInventory: Inventory, openData: ProcessingSetOpenData): ProcessingSetScreenHandler {
            val inputGrid = SimpleContainer(INPUT_SLOTS)
            for ((i, pair) in openData.inputs.withIndex()) {
                val slot = openData.inputSlots.getOrElse(i) { i }
                if (slot !in 0 until INPUT_SLOTS) continue
                val id = Identifier.tryParse(pair.first) ?: continue
                val item = BuiltInRegistries.ITEM.getValue(id) ?: continue
                inputGrid.setItem(slot, ItemStack(item, 1))
            }

            val outputGrid = SimpleContainer(OUTPUT_SLOTS)
            for ((i, pair) in openData.outputs.withIndex()) {
                val slot = openData.outputSlots.getOrElse(i) { i }
                if (slot !in 0 until OUTPUT_SLOTS) continue
                val id = Identifier.tryParse(pair.first) ?: continue
                val item = BuiltInRegistries.ITEM.getValue(id) ?: continue
                outputGrid.setItem(slot, ItemStack(item, 1))
            }

            val data = SimpleContainerData(DATA_COUNT)
            for (i in 0 until INPUT_SLOTS) data.set(i, 1)
            for (i in 0 until OUTPUT_SLOTS) data.set(INPUT_SLOTS + i, 1)
            for ((i, pair) in openData.inputs.withIndex()) {
                val slot = openData.inputSlots.getOrElse(i) { i }
                if (slot in 0 until INPUT_SLOTS) data.set(slot, pair.second)
            }
            for ((i, pair) in openData.outputs.withIndex()) {
                val slot = openData.outputSlots.getOrElse(i) { i }
                if (slot in 0 until OUTPUT_SLOTS) data.set(INPUT_SLOTS + slot, pair.second)
            }
            data.set(DATA_TIMEOUT, openData.timeout)

            return ProcessingSetScreenHandler(syncId, playerInventory, inputGrid, outputGrid, data, SaveMode.ClientDummy).also {
                it.cardName = openData.name
                it.serial = openData.serial
            }
        }
    }

    init {
        // 9 input ghost slots — 3×3 grid, horizontally centered under the 180-wide frame
        // (input block spans x=36..90, output column at x=128, gap 90..128 hosts the arrow).
        for (row in 0..2) {
            for (col in 0..2) {
                addSlot(GhostSlot(inputGrid, row * 3 + col, 36 + col * 18, 13 + row * 18))
            }
        }

        for (i in 0 until OUTPUT_SLOTS) {
            addSlot(GhostSlot(outputGrid, i, 128, 13 + i * 18))
        }

        // Player inventory (3 rows) — starts at x=10 so the 9-slot block (width 160)
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

    override fun clicked(slotId: Int, button: Int, clickType: net.minecraft.world.inventory.ClickType, player: Player) {
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
                // Populate slot AND inherit the clicked stack's count so the recipe
                // picks up "4 ingots" when the player clicked with a stack of 4.
                val inheritedCount = carried.count.coerceAtLeast(1)
                when {
                    slotId < INPUT_SLOTS -> {
                        inputGrid.setItem(slotId, ItemStack(carried.item, 1))
                        data.set(slotId, inheritedCount)
                    }
                    else -> {
                        val outIdx = slotId - INPUT_SLOTS
                        outputGrid.setItem(outIdx, ItemStack(carried.item, 1))
                        data.set(INPUT_SLOTS + outIdx, inheritedCount)
                    }
                }
            }
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
                    inputGrid.setItem(i, ItemStack(stack.item, 1))
                    break
                }
            }
        }
        return ItemStack.EMPTY
    }

    fun setInputCount(slotIndex: Int, count: Int) {
        if (slotIndex in 0 until INPUT_SLOTS) {
            data.set(slotIndex, maxOf(1, count))
        }
    }

    fun setOutputCount(slotIndex: Int, count: Int) {
        if (slotIndex in 0 until OUTPUT_SLOTS) {
            data.set(INPUT_SLOTS + slotIndex, maxOf(1, count))
        }
    }

    fun setTimeout(timeout: Int) {
        data.set(DATA_TIMEOUT, maxOf(0, timeout))
    }

    /** Set a ghost slot by item ID string (used by JEI ghost ingredient and recipe transfer). */
    fun setSlotFromId(slotIndex: Int, itemId: String) {
        if (itemId.isEmpty()) {
            when {
                slotIndex < INPUT_SLOTS -> inputGrid.setItem(slotIndex, ItemStack.EMPTY)
                slotIndex < TOTAL_GHOST_SLOTS -> outputGrid.setItem(slotIndex - INPUT_SLOTS, ItemStack.EMPTY)
            }
            return
        }
        val id = Identifier.tryParse(itemId) ?: return
        val item = BuiltInRegistries.ITEM.getValue(id) ?: return
        when {
            slotIndex < INPUT_SLOTS -> inputGrid.setItem(slotIndex, ItemStack(item, 1))
            slotIndex < TOTAL_GHOST_SLOTS -> outputGrid.setItem(slotIndex - INPUT_SLOTS, ItemStack(item, 1))
        }
    }

    /** Set the entire input grid from a list of item IDs (used by JEI recipe transfer). */
    fun setInputsFromIds(items: List<String>) {
        for (i in 0 until minOf(INPUT_SLOTS, items.size)) {
            setSlotFromId(i, items[i])
        }
        broadcastChanges()
    }

    override fun removed(player: Player) {
        super.removed(player)
        if (player.level().isClientSide) return
        saveRecipe(player)
    }

    private fun saveRecipe(player: Player) {
        val inputs = mutableListOf<Pair<String, Int>>()
        val inputSlots = mutableListOf<Int>()
        for (i in 0 until INPUT_SLOTS) {
            val stack = inputGrid.getItem(i)
            if (stack.isEmpty) continue
            val id = BuiltInRegistries.ITEM.getKey(stack.item)?.toString() ?: continue
            val count = data.get(i).coerceAtLeast(1)
            inputs.add(id to count)
            inputSlots.add(i)
        }

        val outputs = mutableListOf<Pair<String, Int>>()
        val outputSlots = mutableListOf<Int>()
        for (i in 0 until OUTPUT_SLOTS) {
            val stack = outputGrid.getItem(i)
            if (stack.isEmpty) continue
            val id = BuiltInRegistries.ITEM.getKey(stack.item)?.toString() ?: continue
            val count = data.get(INPUT_SLOTS + i).coerceAtLeast(1)
            outputs.add(id to count)
            outputSlots.add(i)
        }

        val timeout = data.get(DATA_TIMEOUT).coerceAtLeast(0)

        when (val mode = saveMode) {
            is SaveMode.Handheld -> {
                val stack = player.getItemInHand(mode.hand)
                if (stack.item is ProcessingSet) {
                    // Name is now derived from the recipe layout — the canonical ID is
                    // the unique handler key. Custom naming is gone. See
                    // docs/design/processing-set-handler-ux.md.
                    val canonical = ProcessingSet.canonicalId(inputs, outputs)
                    ProcessingSet.setRecipe(
                        stack, canonical, inputs, outputs, timeout, serial,
                        inputPositions = inputSlots.toIntArray(),
                        outputPositions = outputSlots.toIntArray()
                    )
                }
            }
            is SaveMode.ClientDummy -> {}
        }
    }

    override fun stillValid(player: Player): Boolean = true
}
