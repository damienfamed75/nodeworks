package damien.nodeworks.compat.curios

import damien.nodeworks.item.PortableInventoryTerminalItem
import damien.nodeworks.item.PortableTerminalOpener
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.ItemStack
import top.theillusivec4.curios.api.CuriosApi

/** Lets the Ctrl+E hotkey open a Portable worn in a Curios slot. Any slot counts, not
 *  just the Nodeworks one, so packs can allow it elsewhere with a tag. */
internal object CuriosPortableSlot {

    fun register() {
        PortableTerminalOpener.extraHolders += ::findPortable
    }

    /** A holder that re-reads the Portable's Curios slot each call, so the menu always
     *  sees the live stack and closes once it is taken out. */
    private fun findPortable(player: ServerPlayer): (() -> ItemStack)? {
        val found = CuriosApi.getCuriosInventory(player)
            .flatMap { it.findFirstCurio { stack -> stack.item is PortableInventoryTerminalItem } }
            .orElse(null) ?: return null
        val slotId = found.slotContext().identifier()
        val index = found.slotContext().index()
        return holder@{
            val stacks = CuriosApi.getCuriosInventory(player)
                .flatMap { it.getStacksHandler(slotId) }
                .orElse(null)?.stacks ?: return@holder ItemStack.EMPTY
            if (index !in 0 until stacks.slots) return@holder ItemStack.EMPTY
            stacks.getStackInSlot(index).takeIf { it.item is PortableInventoryTerminalItem } ?: ItemStack.EMPTY
        }
    }
}
