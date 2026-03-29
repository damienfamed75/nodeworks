package damien.nodeworks.item

import damien.nodeworks.block.InstructionCrafterBlock
import damien.nodeworks.block.NetworkControllerBlock
import damien.nodeworks.block.NodeBlock
import damien.nodeworks.block.TerminalBlock
import damien.nodeworks.block.CraftingCoreBlock
import damien.nodeworks.block.VariableBlock
import damien.nodeworks.block.entity.NetworkControllerBlockEntity
import damien.nodeworks.network.NetworkDiscovery
import damien.nodeworks.network.NodeConnectionHelper
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.InteractionResult
import net.minecraft.world.item.Item
import net.minecraft.world.item.context.UseOnContext
import net.minecraft.world.level.Level
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class NetworkWrenchItem(properties: Properties) : Item(properties) {

    private data class Selection(val pos: BlockPos, val dimension: ResourceKey<Level>)

    companion object {
        private val selectedNodes = ConcurrentHashMap<UUID, Selection>()

        /** Client-side selected node for highlight rendering. Only meaningful on the client JVM. */
        @JvmField
        var clientSelectedNode: BlockPos? = null

        fun clearSelection(playerUuid: UUID) {
            selectedNodes.remove(playerUuid)
        }
    }

    override fun useOn(context: UseOnContext): InteractionResult {
        val level = context.level
        val pos = context.clickedPos
        val player = context.player ?: return InteractionResult.PASS

        // Must click a connectable block (node, instruction crafter, or network controller)
        val block = level.getBlockState(pos).block
        if (block !is NodeBlock && block !is InstructionCrafterBlock && block !is NetworkControllerBlock && block !is VariableBlock && block !is TerminalBlock && block !is CraftingCoreBlock && block !is damien.nodeworks.block.InstructionStorageBlock && block !is damien.nodeworks.block.ApiStorageBlock && block !is damien.nodeworks.block.ReceiverAntennaBlock) return InteractionResult.PASS

        val isNode = block is NodeBlock

        // Client side: track selection for highlight rendering (nodes only)
        if (level.isClientSide) {
            if (player.isShiftKeyDown && isNode) {
                clientSelectedNode = pos
            }
            return InteractionResult.SUCCESS
        }

        // --- Server side below ---
        val serverLevel = level as ServerLevel

        if (player.isShiftKeyDown) {
            // Shift + right-click: select (only Nodes can be selected as connection endpoints)
            if (!isNode) return InteractionResult.PASS
            selectedNodes[player.uuid] = Selection(pos, level.dimension())
            player.displayClientMessage(
                Component.translatable("message.nodeworks.node_selected", pos.x, pos.y, pos.z), false
            )
            return InteractionResult.SUCCESS
        }

        // Right-click: connect/disconnect
        val selection = selectedNodes[player.uuid]
        if (selection == null) {
            player.displayClientMessage(
                Component.translatable("message.nodeworks.no_selection"), false
            )
            return InteractionResult.SUCCESS
        }

        // Selection from a different dimension is invalid
        if (selection.dimension != level.dimension()) {
            selectedNodes.remove(player.uuid)
            player.displayClientMessage(
                Component.translatable("message.nodeworks.selection_invalid"), false
            )
            return InteractionResult.SUCCESS
        }

        val selectedPos = selection.pos

        if (selectedPos == pos) {
            player.displayClientMessage(
                Component.translatable("message.nodeworks.same_node"), false
            )
            return InteractionResult.SUCCESS
        }

        if (NodeConnectionHelper.getConnectable(level, selectedPos) == null) {
            selectedNodes.remove(player.uuid)
            player.displayClientMessage(
                Component.translatable("message.nodeworks.selection_invalid"), false
            )
            return InteractionResult.SUCCESS
        }

        if (!NodeConnectionHelper.isWithinRange(selectedPos, pos)) {
            player.displayClientMessage(
                Component.translatable("message.nodeworks.too_far"), false
            )
            return InteractionResult.SUCCESS
        }

        if (!NodeConnectionHelper.checkLineOfSight(level, selectedPos, pos)) {
            player.displayClientMessage(
                Component.translatable("message.nodeworks.no_line_of_sight"), false
            )
            return InteractionResult.SUCCESS
        }

        // Check for duplicate controllers before connecting
        val entityA = NodeConnectionHelper.getConnectable(level, selectedPos)
        val entityB = NodeConnectionHelper.getConnectable(level, pos)
        if (entityA != null && entityB != null && !entityA.hasConnection(pos)) {
            // About to connect — check if both networks already have controllers
            val snapshotA = NetworkDiscovery.discoverNetwork(serverLevel, selectedPos)
            val snapshotB = NetworkDiscovery.discoverNetwork(serverLevel, pos)
            if (snapshotA.controller != null && snapshotB.controller != null) {
                player.displayClientMessage(
                    Component.translatable("message.nodeworks.duplicate_controller"), false
                )
                return InteractionResult.SUCCESS
            }
        }

        val connected = NodeConnectionHelper.toggleConnection(serverLevel, selectedPos, pos)

        val msgKey = if (connected) "message.nodeworks.connected" else "message.nodeworks.disconnected"
        player.displayClientMessage(Component.translatable(msgKey), false)

        return InteractionResult.SUCCESS
    }
}
