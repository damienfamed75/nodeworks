package damien.nodeworks.block

import com.mojang.serialization.MapCodec
import damien.nodeworks.block.entity.BreakerBlockEntity
import damien.nodeworks.item.NetworkWrenchItem
import damien.nodeworks.platform.PlatformServices
import damien.nodeworks.screen.BreakerMenu
import damien.nodeworks.screen.BreakerOpenData
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.phys.BlockHitResult

/**
 * Breaker, destroys the block at its facing position over time, routes drops
 * into network storage by default. Diamond-pickaxe tier (encoded via the recipe),
 * break duration uses the wooden-pickaxe formula so common blocks break in ~1s
 * but harder blocks like ancient debris are slow. See [BreakerBlockEntity] for
 * the server-side break-progress logic.
 */
class BreakerBlock(properties: Properties) : BaseEntityBlock(properties) {

    companion object {
        val CODEC: MapCodec<BreakerBlock> = simpleCodec(::BreakerBlock)
        val FACING = BlockStateProperties.FACING
    }

    init {
        registerDefaultState(stateDefinition.any().setValue(FACING, net.minecraft.core.Direction.NORTH))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(FACING)
    }

    override fun codec(): MapCodec<out BaseEntityBlock> = CODEC
    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.MODEL

    override fun getStateForPlacement(context: BlockPlaceContext): BlockState? {
        // Face the side opposite where the player is looking from, same shoulder-mount
        // shape as a piston/observer/dispenser. The "front" of the block points at
        // whatever the player was aiming at when placing.
        return defaultBlockState().setValue(FACING, context.nearestLookingDirection.opposite)
    }

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity =
        BreakerBlockEntity(pos, state)

    override fun <T : BlockEntity> getTicker(
        level: Level,
        state: BlockState,
        blockEntityType: BlockEntityType<T>,
    ): BlockEntityTicker<T>? {
        // Server-side ticker drives the multi-tick break progress. Client doesn't
        // need to tick, the destroy-progress overlay is server-pushed via
        // `level.destroyBlockProgress` and clients just render whatever stage they
        // were last told.
        if (level.isClientSide) return null
        return BlockEntityTicker { lvl, _, _, be ->
            if (be is BreakerBlockEntity && lvl is net.minecraft.server.level.ServerLevel) {
                be.serverTick(lvl)
            }
        }
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

        val entity = level.getBlockEntity(pos) as? BreakerBlockEntity ?: return InteractionResult.PASS
        val serverPlayer = player as ServerPlayer
        PlatformServices.menu.openExtendedMenu(
            serverPlayer,
            Component.translatable("container.nodeworks.breaker"),
            BreakerOpenData(pos, entity.deviceName, entity.channel.id),
            BreakerOpenData.STREAM_CODEC,
            { syncId, inv, _ -> BreakerMenu.createServer(syncId, inv, entity) },
        )
        return InteractionResult.SUCCESS
    }

    override fun playerWillDestroy(level: Level, pos: BlockPos, state: BlockState, player: Player): BlockState {
        // Mark the entity as destroyed BEFORE super removes it so the destroy-progress
        // overlay we may have pushed gets cleared in setRemoved instead of leaking.
        val entity = level.getBlockEntity(pos) as? BreakerBlockEntity
        entity?.blockDestroyed = true
        return super.playerWillDestroy(level, pos, state, player)
    }
}
