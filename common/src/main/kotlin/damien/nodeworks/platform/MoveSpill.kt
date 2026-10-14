package damien.nodeworks.platform

import net.minecraft.world.item.ItemStack
import org.slf4j.LoggerFactory

/**
 * Last resort for items a move took out of a storage and could neither deliver nor
 * return. A caller that knows a world position installs a spill around the move with
 * [around], so those items land as item entities there instead of being deleted.
 */
object MoveSpill {
    private val logger = LoggerFactory.getLogger("nodeworks-move-spill")
    private val current = ThreadLocal<((ItemStack) -> Unit)?>()

    fun <T> around(spill: (ItemStack) -> Unit, block: () -> T): T {
        val previous = current.get()
        current.set(spill)
        try {
            return block()
        } finally {
            current.set(previous)
        }
    }

    /** Hand [stack] to the installed spill. Without one, the loss is logged as an error
     *  so it is at least visible. */
    fun spill(stack: ItemStack, reason: String) {
        if (stack.isEmpty) return
        val sink = current.get()
        if (sink != null) {
            logger.warn("{}, dropped {} x{} in the world", reason, stack.item, stack.count)
            sink(stack)
        } else {
            logger.error("{}, {} x{} lost with no drop position", reason, stack.item, stack.count)
        }
    }
}
