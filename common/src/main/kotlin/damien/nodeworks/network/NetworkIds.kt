package damien.nodeworks.network

import net.minecraft.core.BlockPos
import net.minecraft.world.level.Level
import java.util.UUID

/**
 * Network ids come in two shapes. A Network Controller or a micro anchor carries a
 * random UUID saved with it. A Node that anchors a controller-less network lends an id
 * derived from its dimension and position instead: nothing is saved, a cloned Node
 * cannot carry another network's id along, and a Node re-placed where one stood lends
 * the same id again.
 *
 * The two shapes never collide. A random UUID has the variant bits `10` at the top of
 * its low half, a derived id has `00` there, so any code can tell a Node anchor id
 * from a controller's without a lookup or a network walk.
 */
object NetworkIds {

    private const val VARIANT_MASK = 0x3FFF_FFFF_FFFF_FFFFL

    fun nodeAnchor(level: Level, pos: BlockPos): UUID {
        val dimension = level.dimension().toString().hashCode().toLong()
        return UUID(pos.asLong(), (dimension shl 16) and VARIANT_MASK)
    }

    /** True for an id lent by a Node, false for a controller's, a micro anchor's or null. */
    fun isNodeAnchor(id: UUID?): Boolean =
        id != null && (id.leastSignificantBits ushr 62) == 0L

    /** Controllers a player broke this session. A walk that finds no controller keeps the
     *  id its blocks carry when the controller may just be unloaded, these ids are known
     *  to be gone for good, so they are never kept. */
    private val retiredControllerIds: MutableSet<UUID> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    fun retireController(id: UUID) {
        retiredControllerIds.add(id)
    }

    fun isRetiredController(id: UUID): Boolean = id in retiredControllerIds

    fun clearRetired() {
        retiredControllerIds.clear()
    }
}
