package damien.nodeworks.block

import com.mojang.serialization.MapCodec
import damien.nodeworks.block.entity.DisplayBlockEntity
import damien.nodeworks.item.DiagnosticToolItem
import damien.nodeworks.item.NetworkWrenchItem
import damien.nodeworks.platform.PlatformServices
import damien.nodeworks.screen.DisplayMenu
import damien.nodeworks.screen.DisplayOpenData
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.VoxelShape

/**
 * Display block. A script-driven, retained-mode in-world UI surface that
 * projects a holographic volume up to 2 blocks deep in front of its facing.
 * `DIRECTIONAL` like the Breaker/Placer/User so it can face any of the 6
 * directions (sit on a wall, face up for a tabletop hologram).
 *
 * Collision ([getShape]) is just the block, so the hologram never blocks
 * movement or placement. Button input is NOT driven off the block's shape, a
 * `getInteractionShape` extending into air doesn't work (MC only tests a
 * block's shape when the look ray crosses that block's own cell). Instead the
 * client right-click handlers raycast against button AABBs directly, see
 * [damien.nodeworks.render.DisplayButtonHitTest]. The block's own use handlers
 * only cover the sneak → settings-GUI gesture.
 */
class DisplayBlock(properties: Properties) : BaseEntityBlock(properties) {

    companion object {
        val CODEC: MapCodec<DisplayBlock> = simpleCodec(::DisplayBlock)
        val FACING = BlockStateProperties.FACING

        // Block hitbox: 14×14 cross-section inset 1px, full 16 along FACING,
        // one shape per axis. Matches Breaker / Placer / User.
        private val SHAPE_Z: VoxelShape = Block.box(1.0, 1.0, 0.0, 15.0, 15.0, 16.0)
        private val SHAPE_X: VoxelShape = Block.box(0.0, 1.0, 1.0, 16.0, 15.0, 15.0)
        private val SHAPE_Y: VoxelShape = Block.box(1.0, 0.0, 1.0, 15.0, 16.0, 15.0)
    }

    init {
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(FACING)
    }

    override fun codec(): MapCodec<out BaseEntityBlock> = CODEC
    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.MODEL

    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape =
        when (state.getValue(FACING).axis) {
            Direction.Axis.X -> SHAPE_X
            Direction.Axis.Y -> SHAPE_Y
            else -> SHAPE_Z
        }

    override fun getStateForPlacement(context: BlockPlaceContext): BlockState =
        defaultBlockState().setValue(FACING, context.nearestLookingDirection.opposite)

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity =
        DisplayBlockEntity(pos, state)

    override fun useWithoutItem(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hitResult: BlockHitResult,
    ): InteractionResult {
        // Wrench + Diagnostic tool keep their own use handlers.
        val handItem = player.mainHandItem.item
        if (handItem is NetworkWrenchItem || handItem is DiagnosticToolItem) return InteractionResult.PASS
        // Sneak opens the settings GUI. A plain right-click is handled by the
        // client-side button raycast, not here.
        if (player.isSecondaryUseActive) return openSettings(level, pos, player)
        return InteractionResult.PASS
    }

    override fun useItemOn(
        stack: ItemStack,
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hand: InteractionHand,
        hitResult: BlockHitResult,
    ): InteractionResult {
        // Button presses are caught client-side before this runs. Fall through
        // so everything else behaves normally: sneak → GUI (via useWithoutItem),
        // held-item placement, wrench / diagnostic-tool handlers.
        return InteractionResult.TRY_WITH_EMPTY_HAND
    }

    private fun openSettings(level: Level, pos: BlockPos, player: Player): InteractionResult {
        if (level.isClientSide) return InteractionResult.SUCCESS
        val be = level.getBlockEntity(pos) as? DisplayBlockEntity ?: return InteractionResult.PASS
        val serverPlayer = player as? ServerPlayer ?: return InteractionResult.PASS
        PlatformServices.menu.openExtendedMenu(
            serverPlayer,
            Component.translatable("container.nodeworks.display"),
            DisplayOpenData(pos, be.clusterName()),
            DisplayOpenData.STREAM_CODEC,
            { syncId, inv, _ -> DisplayMenu.createServer(syncId, inv, be) },
        )
        return InteractionResult.SUCCESS
    }

    override fun playerWillDestroy(level: Level, pos: BlockPos, state: BlockState, player: Player): BlockState {
        val be = level.getBlockEntity(pos) as? DisplayBlockEntity
        be?.blockDestroyed = true
        return super.playerWillDestroy(level, pos, state, player)
    }
}
