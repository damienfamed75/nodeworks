package damien.nodeworks.block

import com.mojang.serialization.MapCodec
import damien.nodeworks.block.entity.InventoryTerminalBlockEntity
import damien.nodeworks.network.NodeConnectionHelper
import damien.nodeworks.platform.PlatformServices
import damien.nodeworks.screen.InventoryTerminalOpenData
import damien.nodeworks.screen.InventoryTerminalMenu
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult

class InventoryTerminalBlock(properties: Properties) : BaseEntityBlock(properties) {

    companion object {
        val CODEC: MapCodec<InventoryTerminalBlock> = simpleCodec(::InventoryTerminalBlock)

        /** Find an adjacent node block. */
        fun findAdjacentNode(level: Level, pos: BlockPos): BlockPos? {
            for (dir in Direction.entries) {
                val adjacentPos = pos.relative(dir)
                if (NodeConnectionHelper.getNodeEntity(level, adjacentPos) != null) {
                    return adjacentPos
                }
            }
            return null
        }
    }

    override fun codec(): MapCodec<out BaseEntityBlock> = CODEC
    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.MODEL

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity {
        return InventoryTerminalBlockEntity(pos, state)
    }

    override fun useWithoutItem(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hitResult: BlockHitResult
    ): InteractionResult {
        if (level.isClientSide) return InteractionResult.SUCCESS

        val nodePos = findAdjacentNode(level, pos)
        if (nodePos == null) {
            player.displayClientMessage(Component.translatable("message.nodeworks.terminal_no_network"), false)
            return InteractionResult.SUCCESS
        }

        val serverPlayer = player as ServerPlayer
        val serverLevel = level as ServerLevel

        // Check for controller
        val snapshot = damien.nodeworks.network.NetworkDiscovery.discoverNetwork(serverLevel, nodePos)
        if (!snapshot.isOnline) {
            player.displayClientMessage(Component.translatable("message.nodeworks.no_controller"), false)
            return InteractionResult.SUCCESS
        }

        PlatformServices.menu.openExtendedMenu(
            serverPlayer,
            Component.translatable("container.nodeworks.inventory_terminal"),
            InventoryTerminalOpenData(pos, nodePos),
            InventoryTerminalOpenData.STREAM_CODEC,
            { syncId, inv, p -> InventoryTerminalMenu.createServer(syncId, inv, serverLevel, nodePos) }
        )

        return InteractionResult.SUCCESS
    }
}
