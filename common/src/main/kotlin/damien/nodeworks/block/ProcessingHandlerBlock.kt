package damien.nodeworks.block

import com.mojang.serialization.MapCodec
import damien.nodeworks.block.entity.ProcessingHandlerBlockEntity
import damien.nodeworks.network.NodeConnectionHelper
import damien.nodeworks.platform.PlatformServices
import damien.nodeworks.screen.ProcessingHandlerMenu
import damien.nodeworks.screen.ProcessingHandlerOpenData
import damien.nodeworks.screen.ProcessingHandlerServerLogic
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.VoxelShape

/**
 * Block-driven equivalent of `network:handle(...)`. The back face joins the
 * parent network so the network's CPU can find this Handler and bind it to a
 * Processing Set; the front face anchors a "micro-network" of pipes / nodes /
 * storage cards that route inputs into machines and pull outputs back out.
 *
 * Phase 1: placement and the directional split. GUI, item routing, and the
 * actual handler binding land in later phases.
 */
class ProcessingHandlerBlock(properties: Properties) : BaseEntityBlock(properties), Wrenchable {

    companion object {
        val CODEC: MapCodec<ProcessingHandlerBlock> = simpleCodec(::ProcessingHandlerBlock)
        val FACING = BlockStateProperties.FACING

        // Hitbox matches the block model: 12×12 cross-section, full 16 along
        // the FACING axis. One shape per axis - NORTH/SOUTH share, EAST/WEST
        // share, UP/DOWN share, since the cross-section is symmetric.
        private val SHAPE_Z: VoxelShape = Block.box(2.0, 2.0, 0.0, 14.0, 14.0, 16.0)
        private val SHAPE_X: VoxelShape = Block.box(0.0, 2.0, 2.0, 16.0, 14.0, 14.0)
        private val SHAPE_Y: VoxelShape = Block.box(2.0, 0.0, 2.0, 14.0, 16.0, 14.0)
    }

    init {
        registerDefaultState(stateDefinition.any().setValue(FACING, net.minecraft.core.Direction.NORTH))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(FACING)
    }

    override fun codec(): MapCodec<out BaseEntityBlock> = CODEC
    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.MODEL

    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape =
        when (state.getValue(FACING).axis) {
            net.minecraft.core.Direction.Axis.X -> SHAPE_X
            net.minecraft.core.Direction.Axis.Y -> SHAPE_Y
            else -> SHAPE_Z
        }

    /** Front face points away from the player so the player intuits "I aim
     *  the front at the machine cluster I want to feed". Same convention as
     *  Placer / Breaker. */
    override fun getStateForPlacement(context: BlockPlaceContext): BlockState? =
        defaultBlockState().setValue(FACING, context.nearestLookingDirection.opposite)

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity =
        ProcessingHandlerBlockEntity(pos, state)

    /** Re-propagate from the new position so the back face joins the parent
     *  network and the front face starts (or merges into) a micro-network. */
    override fun setPlacedBy(level: Level, pos: BlockPos, state: BlockState, placer: LivingEntity?, stack: ItemStack) {
        super.setPlacedBy(level, pos, state, placer, stack)
        if (level !is ServerLevel) return
        NodeConnectionHelper.propagateNetworkId(level, pos)
        // Trigger a separate walk from a front-face neighbor so the micro
        // side gets its own UUID assignment. propagate's per-tick dedup
        // covers the back side already.
        val front = pos.relative(state.getValue(FACING))
        NodeConnectionHelper.propagateNetworkId(level, front)
    }

    override fun playerWillDestroy(level: Level, pos: BlockPos, state: BlockState, player: Player): BlockState {
        val entity = level.getBlockEntity(pos) as? ProcessingHandlerBlockEntity
        entity?.blockDestroyed = true
        return super.playerWillDestroy(level, pos, state, player)
    }

    override fun useWithoutItem(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hitResult: BlockHitResult,
    ): InteractionResult {
        if (player.mainHandItem.item is damien.nodeworks.item.NetworkWrenchItem ||
            player.mainHandItem.item is damien.nodeworks.item.DiagnosticToolItem
        ) return InteractionResult.PASS
        if (level.isClientSide) return InteractionResult.SUCCESS

        val entity = level.getBlockEntity(pos) as? ProcessingHandlerBlockEntity ?: return InteractionResult.PASS
        val serverLevel = level as? ServerLevel ?: return InteractionResult.PASS
        val serverPlayer = player as ServerPlayer
        val openData = ProcessingHandlerServerLogic.buildOpenData(serverLevel, entity)
        PlatformServices.menu.openExtendedMenu(
            serverPlayer,
            Component.translatable("container.nodeworks.processing_handler"),
            openData,
            ProcessingHandlerOpenData.STREAM_CODEC,
            { syncId, inv, _ -> ProcessingHandlerMenu.createServer(syncId, inv, entity) },
        )
        return InteractionResult.SUCCESS
    }
}
