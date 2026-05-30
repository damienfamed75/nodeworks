package damien.nodeworks.block

import com.mojang.serialization.MapCodec
import damien.nodeworks.block.entity.ImportChestBlockEntity
import damien.nodeworks.item.NetworkWrenchItem
import damien.nodeworks.platform.PlatformServices
import damien.nodeworks.screen.ImportChestMenu
import damien.nodeworks.screen.ImportChestOpenData
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.Level
import net.minecraft.world.level.BlockGetter
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
 * Import Chest. Buffer + network input device. See [ImportChestBlockEntity]
 * for runtime semantics.
 */
class ImportChestBlock(properties: Properties) : BaseEntityBlock(properties), Wrenchable {

    init {
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.SOUTH))
    }

    companion object {
        val FACING = BlockStateProperties.HORIZONTAL_FACING
        val CODEC: MapCodec<ImportChestBlock> = simpleCodec(::ImportChestBlock)

        // Static closed-chest shape, also used as the occlusion shape so
        // neighbours don't cull faces touching the chest. The animated lid
        // can sweep beyond this box when open, accepted to keep the outline
        // steady as the lid swings.
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
        ImportChestBlockEntity(pos, state)

    override fun <T : BlockEntity> getTicker(
        level: Level,
        state: BlockState,
        blockEntityType: BlockEntityType<T>,
    ): BlockEntityTicker<T>? = if (level.isClientSide) {
        BlockEntityTicker { _, _, _, be ->
            if (be is ImportChestBlockEntity) be.lidAnimateTick()
        }
    } else {
        BlockEntityTicker { lvl, _, _, be ->
            if (be is ImportChestBlockEntity && lvl is ServerLevel) be.serverTick(lvl)
        }
    }

    // Forward block events to the BE, vanilla default returns false and blocks
    // the lid-state broadcast. Required for the lid animation.
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

        val entity = level.getBlockEntity(pos) as? ImportChestBlockEntity ?: return InteractionResult.PASS
        val serverPlayer = player as ServerPlayer
        PlatformServices.menu.openExtendedMenu(
            serverPlayer,
            Component.translatable("container.nodeworks.import_chest"),
            ImportChestOpenData(
                pos = pos,
                channelNbtInt = entity.channel.toNbtInt(),
                redstoneMode = entity.redstoneMode,
                roundRobin = entity.roundRobin,
                tickInterval = entity.tickInterval,
            ),
            ImportChestOpenData.STREAM_CODEC,
            { syncId, inv, _ -> ImportChestMenu.createServer(syncId, inv, entity) },
        )
        return InteractionResult.SUCCESS
    }

    override fun playerWillDestroy(level: Level, pos: BlockPos, state: BlockState, player: Player): BlockState {
        // Mark the BE as destroyed BEFORE super removes it so removeAllConnections fires
        // through the player-destruction path (vs chunk-unload, which shouldn't tear down
        // network state).
        val entity = level.getBlockEntity(pos) as? ImportChestBlockEntity
        entity?.blockDestroyed = true
        // Drop chest contents on break.
        if (entity != null && !level.isClientSide) {
            net.minecraft.world.Containers.dropContents(level, pos, entity)
        }
        return super.playerWillDestroy(level, pos, state, player)
    }
}
