package damien.nodeworks.screen

import damien.nodeworks.card.NodeCard
import damien.nodeworks.card.StorageCard
import damien.nodeworks.item.CardProgrammerItem
import damien.nodeworks.registry.ModScreenHandlers
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.world.InteractionHand
import net.minecraft.world.SimpleContainer
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.inventory.ContainerData
import net.minecraft.world.inventory.SimpleContainerData
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack

class CardProgrammerMenu(
    syncId: Int,
    private val playerInventory: Inventory,
    private val hand: InteractionHand?
) : AbstractContainerMenu(ModScreenHandlers.CARD_PROGRAMMER, syncId) {

    private val templateContainer = SimpleContainer(1)

    /** Synced toggle / counter state. Slots:
     *    [0] = counter (legacy stepper value, retained while a future widget
     *          decides what to do with the slot below the new toggle)
     *    [1] = copyName  (1 = on, 0 = off)
     *    [2] = copyChannel (1 = on, 0 = off) when on, [applyTemplate] writes
     *          the template's [CardChannel] onto each programmed card so a row
     *          of cards starts on the same channel without manual dyeing. */
    val counterData: ContainerData = SimpleContainerData(3)

    init {
        // Load template + counter from programmer item (server side only)
        if (hand != null) {
            val programmerStack = playerInventory.player.getItemInHand(hand)
            val template = CardProgrammerItem.getTemplate(programmerStack)
            if (!template.isEmpty) templateContainer.setItem(0, template.copy())
            counterData.set(0, CardProgrammerItem.getCounter(programmerStack))
            counterData.set(1, if (CardProgrammerItem.getCopyName(programmerStack)) 1 else 0)
            counterData.set(2, if (CardProgrammerItem.getCopyChannel(programmerStack)) 1 else 0)
        } else {
            // Defaults for the client dummy menu (when there's no item to read).
            counterData.set(1, 1)
            counterData.set(2, 1)
        }

        // Slot positions match the black 18x18 squares in card_programmer_bg.png (176x100, natural size).

        // Slot 0: Template, left black square at source (52, 18) + programmer Y offset of 2
        addSlot(TemplateSlot(templateContainer, 0, 53, 21))

        // Slot 1: Input, right black square at source (106, 18) + programmer Y offset of 2
        addSlot(InputSlot(SimpleContainer(1), 0, 107, 21))

        // Player inventory (slots 2-37), standard inventory frame below programmer
        for (row in 0 until 3) {
            for (col in 0 until 9) {
                addSlot(Slot(playerInventory, col + row * 9 + 9, 9 + col * 18, 123 + row * 18))
            }
        }
        // Hotbar (slots 38-46)
        for (col in 0 until 9) {
            addSlot(Slot(playerInventory, col, 9 + col * 18, 181))
        }

        addDataSlots(counterData)
    }

    fun getCounter(): Int = counterData.get(0)
    fun setCounter(value: Int) {
        counterData.set(0, value.coerceAtLeast(0))
    }

    fun getCopyName(): Boolean = counterData.get(1) != 0
    fun setCopyName(value: Boolean) {
        counterData.set(1, if (value) 1 else 0)
    }

    fun getCopyChannel(): Boolean = counterData.get(2) != 0
    fun setCopyChannel(value: Boolean) {
        counterData.set(2, if (value) 1 else 0)
    }

    fun hasTemplate(): Boolean = !templateContainer.getItem(0).isEmpty
    fun getTemplate(): ItemStack = templateContainer.getItem(0)

    private fun isValidInput(stack: ItemStack): Boolean {
        if (!hasTemplate()) return false
        val template = getTemplate()
        return stack.item is NodeCard && stack.item.javaClass == template.item.javaClass
    }

    private fun applyTemplate(stack: ItemStack) {
        val template = getTemplate()
        if (template.isEmpty) return

        // Copy priority (StorageCard)
        if (template.item is StorageCard && stack.item is StorageCard) {
            StorageCard.setPriority(stack, StorageCard.getPriority(template))
        }

        // Copy the template's name verbatim when the toggle is on.
        if (getCopyName()) {
            val templateName = template.get(DataComponents.CUSTOM_NAME)
            if (templateName != null) {
                stack.set(DataComponents.CUSTOM_NAME, Component.literal(templateName.string))
            }
        }

        // Copy the template's channel when the toggle is on. Lets a row of
        // cards inherit the template's channel without having to dye each one
        // by hand.
        if (getCopyChannel()) {
            damien.nodeworks.card.CardChannel.set(
                stack, damien.nodeworks.card.CardChannel.get(template),
            )
        }
    }

    /** Handle counter +/- buttons, toggle flips, and direct counter set via clickMenuButton. */
    override fun clickMenuButton(player: Player, id: Int): Boolean {
        when {
            id == 0 -> setCounter(getCounter() - 1)
            id == 1 -> setCounter(getCounter() + 1)
            id == 2 -> setCopyName(!getCopyName())
            id == 3 -> setCopyChannel(!getCopyChannel())
            id in 100..10099 -> setCounter(id - 100) // direct value set (0-9999)
        }
        return true
    }

    override fun clicked(slotIndex: Int, button: Int, clickType: ContainerInput, player: Player) {
        // Intercept regular clicks on the input slot (slot 1)
        if (slotIndex == 1 && clickType == ContainerInput.PICKUP) {
            val carried = carried
            if (!carried.isEmpty && isValidInput(carried)) {
                val modified = carried.copyWithCount(1)
                applyTemplate(modified)
                if (!player.inventory.add(modified)) {
                    if (!player.level().isClientSide) {
                        player.drop(modified, false)
                    }
                }
                carried.shrink(1)
                broadcastChanges()
                return
            }
        }
        super.clicked(slotIndex, button, clickType, player)
    }

    override fun quickMoveStack(player: Player, slotIndex: Int): ItemStack {
        val slot = slots.getOrNull(slotIndex) ?: return ItemStack.EMPTY
        if (!slot.hasItem()) return ItemStack.EMPTY

        val stack = slot.item
        val original = stack.copy()

        when {
            slotIndex == 0 -> {
                // Template → player inventory
                if (!moveItemStackTo(stack, 2, slots.size, true)) return ItemStack.EMPTY
            }

            slotIndex == 1 -> {
                // Input → player inventory (shouldn't normally have items)
                if (!moveItemStackTo(stack, 2, slots.size, true)) return ItemStack.EMPTY
            }

            slotIndex >= 2 -> {
                // Player inventory → template or process
                if (stack.item is NodeCard) {
                    if (!hasTemplate()) {
                        // No template, place as template
                        if (!moveItemStackTo(stack, 0, 1, false)) return ItemStack.EMPTY
                    } else if (isValidInput(stack)) {
                        // Has template, apply settings in-place
                        applyTemplate(stack)
                        slot.setChanged()
                        return ItemStack.EMPTY
                    } else {
                        return ItemStack.EMPTY
                    }
                } else {
                    return ItemStack.EMPTY
                }
            }
        }

        if (stack.isEmpty) slot.set(ItemStack.EMPTY) else slot.setChanged()
        return original
    }

    override fun removed(player: Player) {
        super.removed(player)
        if (player.level().isClientSide) return
        saveToProgrammer(player)
    }

    private fun saveToProgrammer(player: Player) {
        if (hand == null) return
        val programmerStack = player.getItemInHand(hand)
        if (programmerStack.item !is CardProgrammerItem) return
        CardProgrammerItem.setTemplate(programmerStack, templateContainer.getItem(0))
        CardProgrammerItem.setCounter(programmerStack, getCounter())
        CardProgrammerItem.setCopyName(programmerStack, getCopyName())
        CardProgrammerItem.setCopyChannel(programmerStack, getCopyChannel())
    }

    override fun stillValid(player: Player): Boolean {
        if (hand == null) return true
        return player.getItemInHand(hand).item is CardProgrammerItem
    }

    fun getNextName(): String {
        val template = getTemplate()
        if (template.isEmpty) return ""
        val templateName = template.get(DataComponents.CUSTOM_NAME)
        return if (templateName != null) {
            "${templateName.string}_${getCounter()}"
        } else {
            ""
        }
    }

    private inner class TemplateSlot(container: SimpleContainer, index: Int, x: Int, y: Int) :
        Slot(container, index, x, y) {
        override fun mayPlace(stack: ItemStack): Boolean = stack.item is NodeCard

        override fun setChanged() {
            super.setChanged()
            // Reset counter when template changes
            if (item.isEmpty) {
                setCounter(0)
            }
            // Sync template to the held programmer item so its texture updates immediately
            syncTemplateToProgrammer()
        }
    }

    /** Writes the current template slot contents to the held programmer item's CONTAINER component.
     *  Triggers a client-side re-evaluation of the card_type model predicate so the texture swaps immediately. */
    private fun syncTemplateToProgrammer() {
        if (hand == null) return
        val player = playerInventory.player
        if (player.level().isClientSide) return
        val programmerStack = player.getItemInHand(hand)
        if (programmerStack.item !is CardProgrammerItem) return
        CardProgrammerItem.setTemplate(programmerStack, templateContainer.getItem(0))
    }

    private inner class InputSlot(container: SimpleContainer, index: Int, x: Int, y: Int) :
        Slot(container, index, x, y) {
        override fun mayPlace(stack: ItemStack): Boolean = isValidInput(stack)
    }

    companion object {
        fun clientFactory(syncId: Int, playerInventory: Inventory, data: CardProgrammerOpenData): CardProgrammerMenu {
            val hand =
                if (data.handOrdinal < InteractionHand.entries.size) InteractionHand.entries[data.handOrdinal] else null
            return CardProgrammerMenu(syncId, playerInventory, hand)
        }
    }
}
