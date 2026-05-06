package damien.nodeworks.block.entity

import damien.nodeworks.compat.getBlockPosList
import damien.nodeworks.compat.putBlockPosList
import damien.nodeworks.network.NodeConnectionHelper
import damien.nodeworks.registry.ModBlockEntities
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput

/**
 * Focus Node block entity. Inherits all the regular Node behaviour and
 * adds persisted focused-laser-link connections. Uses the inherited
 * [connections] set so the existing Connectable machinery just works.
 * Regular Nodes don't write that set to NBT to keep their footprint
 * minimal, this BE does.
 */
class FocusNodeBlockEntity(
    pos: BlockPos,
    state: BlockState,
) : NodeBlockEntity(ModBlockEntities.FOCUS_NODE, pos, state) {

    override fun saveAdditional(output: ValueOutput) {
        super.saveAdditional(output)
        if (connections.isNotEmpty()) {
            output.putBlockPosList("connections", connections.toList())
        }
    }

    override fun loadAdditional(input: ValueInput) {
        super.loadAdditional(input)
        // Parent loadAdditional already cleared `connections` to drop legacy
        // laser links from regular Nodes. Repopulate from the persisted list.
        connections.addAll(input.getBlockPosList("connections"))
    }

    override fun setLevel(newLevel: Level) {
        super.setLevel(newLevel)
        if (newLevel is ServerLevel) {
            NodeConnectionHelper.trackNode(newLevel, worldPosition)
        }
    }

    override fun setRemoved() {
        val lvl = level
        if (lvl is ServerLevel) {
            NodeConnectionHelper.untrackNode(lvl, worldPosition)
        }
        super.setRemoved()
    }
}
