package damien.nodeworks.block

import com.mojang.serialization.MapCodec
import damien.nodeworks.block.entity.ExportChestBlockEntity
import damien.nodeworks.item.NetworkWrenchItem
import damien.nodeworks.platform.PlatformServices
import damien.nodeworks.screen.ExportChestMenu
import damien.nodeworks.screen.ExportChestOpenData
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Mirror
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.Rotation
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.VoxelShape

/**
 * Export Chest. Buffer + network output device. See [ExportChestBlockEntity]
 * for runtime semantics.
 */
class ExportChestBlock(properties: Properties) : BaseEntityBlock(properties), Wrenchable {

    init {
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.SOUTH))
    }

    companion object {
        val FACING = BlockStateProperties.HORIZONTAL_FACING
        val CODEC: MapCodec<ExportChestBlock> = simpleCodec(::ExportChestBlock)

        private val CHEST_SHAPE: VoxelShape = Block.box(1.0, 0.0, 1.0, 15.0, 14.0, 15.0)
    }

    override fun codec(): MapCodec<out BaseEntityBlock> = CODEC
    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.MODEL

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(FACING)
    }

    override fun getStateForPlacement(context: BlockPlaceContext): BlockState =
        defaultBlockState().setValue(FACING, context.horizontalDirection.opposite)

    override fun rotate(state: BlockState, rotation: Rotation): BlockState =
        state.setValue(FACING, rotation.rotate(state.getValue(FACING)))

    override fun mirror(state: BlockState, mirror: Mirror): BlockState =
        state.rotate(mirror.getRotation(state.getValue(FACING)))

    override fun getShape(
        state: BlockState, level: BlockGetter, pos: BlockPos, ctx: CollisionContext,
    ): VoxelShape = CHEST_SHAPE

    override fun getCollisionShape(
        state: BlockState, level: BlockGetter, pos: BlockPos, ctx: CollisionContext,
    ): VoxelShape = CHEST_SHAPE

    override fun getOcclusionShape(state: BlockState): VoxelShape = CHEST_SHAPE

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity =
        ExportChestBlockEntity(pos, state)

    override fun <T : BlockEntity> getTicker(
        level: Level,
        state: BlockState,
        blockEntityType: BlockEntityType<T>,
    ): BlockEntityTicker<T>? = if (level.isClientSide) {
        BlockEntityTicker { _, _, _, be ->
            if (be is ExportChestBlockEntity) be.lidAnimateTick()
        }
    } else {
        BlockEntityTicker { lvl, _, _, be ->
            if (be is ExportChestBlockEntity && lvl is ServerLevel) be.serverTick(lvl)
        }
    }

    override fun triggerEvent(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        id: Int,
        type: Int,
    ): Boolean {
        super.triggerEvent(state, level, pos, id, type)
        val be = level.getBlockEntity(pos)
        return be?.triggerEvent(id, type) ?: false
    }

    override fun useWithoutItem(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hitResult: BlockHitResult,
    ): InteractionResult {
        if (player.mainHandItem.item is NetworkWrenchItem ||
            player.mainHandItem.item is damien.nodeworks.item.DiagnosticToolItem
        ) return InteractionResult.PASS
        if (level.isClientSide) return InteractionResult.SUCCESS

        val entity = level.getBlockEntity(pos) as? ExportChestBlockEntity ?: return InteractionResult.PASS
        val serverPlayer = player as ServerPlayer
        PlatformServices.menu.openExtendedMenu(
            serverPlayer,
            Component.translatable("container.nodeworks.export_chest"),
            ExportChestOpenData(
                pos = pos,
                filterRules = entity.filterRules,
                channelNbtInt = entity.channel.toNbtInt(),
                pushFaceOrdinal = entity.pushFace?.ordinal ?: -1,
                redstoneMode = entity.redstoneMode,
                tickInterval = entity.tickInterval,
            ),
            ExportChestOpenData.STREAM_CODEC,
            { syncId, inv, _ -> ExportChestMenu.createServer(syncId, inv, entity) },
        )
        return InteractionResult.SUCCESS
    }

    override fun playerWillDestroy(level: Level, pos: BlockPos, state: BlockState, player: Player): BlockState {
        val entity = level.getBlockEntity(pos) as? ExportChestBlockEntity
        entity?.blockDestroyed = true
        if (entity != null && !level.isClientSide) {
            net.minecraft.world.Containers.dropContents(level, pos, entity)
        }
        return super.playerWillDestroy(level, pos, state, player)
    }
}
