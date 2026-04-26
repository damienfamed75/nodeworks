package damien.nodeworks.block

import com.mojang.serialization.MapCodec
import damien.nodeworks.block.entity.InventoryTerminalBlockEntity
import damien.nodeworks.item.DiagnosticToolItem
import damien.nodeworks.item.NetworkWrenchItem
import damien.nodeworks.network.NetworkDiscovery
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
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.phys.BlockHitResult

class InventoryTerminalBlock(properties: Properties) : BaseEntityBlock(properties) {

    companion object {
        val CODEC: MapCodec<InventoryTerminalBlock> = simpleCodec(::InventoryTerminalBlock)
        val FACING = BlockStateProperties.HORIZONTAL_FACING
    }

    init {
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(FACING)
    }

    override fun getStateForPlacement(context: BlockPlaceContext): BlockState {
        // Front face points at the player, matching Terminal / Monitor convention.
        return defaultBlockState().setValue(FACING, context.horizontalDirection.opposite)
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
        // Skip if player is holding a wrench or diagnostic tool
        val held = player.mainHandItem.item
        if (held is NetworkWrenchItem || held is DiagnosticToolItem) return InteractionResult.PASS

        if (level.isClientSide) return InteractionResult.SUCCESS

        val serverPlayer = player as ServerPlayer
        val serverLevel = level as ServerLevel

        // Check for controller via network connections
        val snapshot = NetworkDiscovery.discoverNetwork(serverLevel, pos)
        if (!snapshot.isOnline) {
            player.sendSystemMessage(Component.translatable("message.nodeworks.no_controller"))
            return InteractionResult.SUCCESS
        }

        PlatformServices.menu.openExtendedMenu(
            serverPlayer,
            Component.translatable("container.nodeworks.inventory_terminal"),
            InventoryTerminalOpenData(pos),
            InventoryTerminalOpenData.STREAM_CODEC,
            { syncId, inv, _ ->
                // Fixed terminal uses its own position as the network entry point, the
                // terminal is a Connectable, so NetworkDiscovery walks out from there.
                val source = damien.nodeworks.screen.NodeBackedSource(
                    dimension = serverLevel.dimension(),
                    entryPoint = pos,
                )
                InventoryTerminalMenu.createServer(syncId, inv, serverLevel, source, displayPos = pos)
            }
        )

        return InteractionResult.SUCCESS
    }

    override fun playerWillDestroy(level: Level, pos: BlockPos, state: BlockState, player: Player): BlockState {
        val entity = level.getBlockEntity(pos)
        if (entity is InventoryTerminalBlockEntity) {
            entity.blockDestroyed = true
        }
        return super.playerWillDestroy(level, pos, state, player)
    }
}
