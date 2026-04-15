package damien.nodeworks.screen

import damien.nodeworks.card.ProcessingSet
import damien.nodeworks.registry.ModScreenHandlers
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
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
            val outputs = ProcessingSet.getOutputs(stack)
            val timeout = ProcessingSet.getTimeout(stack)

            val inputGrid = SimpleContainer(INPUT_SLOTS)
            for ((i, pair) in inputs.withIndex()) {
                if (i >= INPUT_SLOTS) break
                val id = ResourceLocation.tryParse(pair.first) ?: continue
                val item = BuiltInRegistries.ITEM.get(id) ?: continue
                inputGrid.setItem(i, ItemStack(item, 1))
            }

            val outputGrid = SimpleContainer(OUTPUT_SLOTS)
            for ((i, pair) in outputs.withIndex()) {
                if (i >= OUTPUT_SLOTS) break
                val id = ResourceLocation.tryParse(pair.first) ?: continue
                val item = BuiltInRegistries.ITEM.get(id) ?: continue
                outputGrid.setItem(i, ItemStack(item, 1))
            }

            val data = object : ContainerData {
                private val values = IntArray(DATA_COUNT)
                init {
                    for ((i, pair) in inputs.withIndex()) {
                        if (i >= INPUT_SLOTS) break
                        values[i] = pair.second
                    }
                    for ((i, pair) in outputs.withIndex()) {
                        if (i >= OUTPUT_SLOTS) break
                        values[INPUT_SLOTS + i] = pair.second
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
                if (i >= INPUT_SLOTS) break
                val id = ResourceLocation.tryParse(pair.first) ?: continue
                val item = BuiltInRegistries.ITEM.get(id) ?: continue
                inputGrid.setItem(i, ItemStack(item, 1))
            }

            val outputGrid = SimpleContainer(OUTPUT_SLOTS)
            for ((i, pair) in openData.outputs.withIndex()) {
                if (i >= OUTPUT_SLOTS) break
                val id = ResourceLocation.tryParse(pair.first) ?: continue
                val item = BuiltInRegistries.ITEM.get(id) ?: continue
                outputGrid.setItem(i, ItemStack(item, 1))
            }

            val data = SimpleContainerData(DATA_COUNT)
            for ((i, pair) in openData.inputs.withIndex()) {
                if (i >= INPUT_SLOTS) break
                data.set(i, pair.second)
            }
            for ((i, pair) in openData.outputs.withIndex()) {
                if (i >= OUTPUT_SLOTS) break
                data.set(INPUT_SLOTS + i, pair.second)
            }
            data.set(DATA_TIMEOUT, openData.timeout)

            return ProcessingSetScreenHandler(syncId, playerInventory, inputGrid, outputGrid, data, SaveMode.ClientDummy).also {
                it.cardName = openData.name
                it.serial = openData.serial
            }
        }
    }

    init {
        // 9 input ghost slots — 3x3 grid (positioned to match dark-themed screen layout)
        // Input grid starts at x=8, y=36 (after top bar 20 + 4 gap + 12 label)
        for (row in 0..2) {
            for (col in 0..2) {
                addSlot(GhostSlot(inputGrid, row * 3 + col, 8 + col * 18, 36 + row * 18))
            }
        }

        // 3 output ghost slots — vertical column at x=100, y=36
        for (i in 0 until OUTPUT_SLOTS) {
            addSlot(GhostSlot(outputGrid, i, 100, 36 + i * 18))
        }

        // Player inventory (3 rows) — starts at y=116
        for (row in 0 until 3) {
            for (col in 0 until 9) {
                addSlot(Slot(playerInventory, col + row * 9 + 9, 11 + col * 18, 116 + row * 18))
            }
        }
        // Player hotbar — y=178 (4px gap after main inv)
        for (col in 0 until 9) {
            addSlot(Slot(playerInventory, col, 11 + col * 18, 178))
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
                when {
                    slotId < INPUT_SLOTS -> inputGrid.setItem(slotId, ItemStack.EMPTY)
                    else -> outputGrid.setItem(slotId - INPUT_SLOTS, ItemStack.EMPTY)
                }
            } else {
                when {
                    slotId < INPUT_SLOTS -> inputGrid.setItem(slotId, ItemStack(carried.item, 1))
                    else -> outputGrid.setItem(slotId - INPUT_SLOTS, ItemStack(carried.item, 1))
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
        val id = ResourceLocation.tryParse(itemId) ?: return
        val item = BuiltInRegistries.ITEM.get(id) ?: return
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
        for (i in 0 until INPUT_SLOTS) {
            val stack = inputGrid.getItem(i)
            if (stack.isEmpty) continue
            val id = BuiltInRegistries.ITEM.getKey(stack.item)?.toString() ?: continue
            val count = data.get(i).coerceAtLeast(1)
            inputs.add(id to count)
        }

        val outputs = mutableListOf<Pair<String, Int>>()
        for (i in 0 until OUTPUT_SLOTS) {
            val stack = outputGrid.getItem(i)
            if (stack.isEmpty) continue
            val id = BuiltInRegistries.ITEM.getKey(stack.item)?.toString() ?: continue
            val count = data.get(INPUT_SLOTS + i).coerceAtLeast(1)
            outputs.add(id to count)
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
                    ProcessingSet.setRecipe(stack, canonical, inputs, outputs, timeout, serial)
                }
            }
            is SaveMode.ClientDummy -> {}
        }
    }

    override fun stillValid(player: Player): Boolean = true
}
