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
 * Focus Node block entity. Inherits all the regular Node behaviour
 * (cards, faces, redstone, container, capabilities) and adds persisted
 * focused-laser-link connections to other Focus Nodes.
 *
 * The link set lives on the inherited [connections] field, so existing
 * `Connectable` machinery (`getConnections`, `addConnection`,
 * `removeConnection`, `hasConnection`) just works without override. Only
 * persistence needs the subclass touch — regular Nodes deliberately don't
 * write the set to NBT to keep the on-disk footprint minimal, this BE does.
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
        // Parent's loadAdditional has already cleared `connections` (regular
        // Nodes use that path to drop legacy laser links). Re-populate from
        // the persisted list so wrench-linked endpoints survive a reload.
        connections.addAll(input.getBlockPosList("connections"))
    }

    override fun setLevel(newLevel: Level) {
        super.setLevel(newLevel)
        // Register in the per-dimension chunk index so the
        // [ServerLevelSetBlockMixin] hook can find this Focus Node when a
        // block changes nearby and re-validate its laser links.
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
