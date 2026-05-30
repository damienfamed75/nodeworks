package damien.nodeworks.block

import com.mojang.serialization.MapCodec
import damien.nodeworks.block.entity.PlacerBlockEntity
import damien.nodeworks.item.NetworkWrenchItem
import damien.nodeworks.platform.PlatformServices
import damien.nodeworks.screen.PlacerMenu
import damien.nodeworks.screen.PlacerOpenData
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.LivingEntity
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
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape

/**
 * Placer, pulls one item from network storage on demand and places it as a block
 * in front of the device. Pairs with Breaker for closed-loop renewable farms
 * (sapling replant, sugar cane, kelp, sweet berries). Synchronous: the placement
 * resolves in the same tick `placer:place(...)` is called, no ticker needed.
 */
class PlacerBlock(properties: Properties) : BaseEntityBlock(properties), Wrenchable {

    companion object {
        val CODEC: MapCodec<PlacerBlock> = simpleCodec(::PlacerBlock)
        val FACING = BlockStateProperties.FACING

        // Hitbox matches the User block: 14×14 cross-section inset 1 px on
        // both perpendicular axes, full 16 along the FACING axis. One shape
        // per axis - NORTH/SOUTH share, EAST/WEST share, UP/DOWN share.
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

    /** Lets levers / buttons / redstone components attach to any face despite
     *  the inset collision shape. */
    override fun getBlockSupportShape(state: BlockState, level: BlockGetter, pos: BlockPos): VoxelShape =
        Shapes.block()

    override fun getStateForPlacement(context: BlockPlaceContext): BlockState? =
        defaultBlockState().setValue(FACING, context.nearestLookingDirection.opposite)

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity =
        PlacerBlockEntity(pos, state)

    /** Capture the placer's UUID so placements fire BlockEvent.EntityPlaceEvent with
     *  that actor, letting claim mods and spawn protection resolve permissions correctly. */
    override fun setPlacedBy(level: Level, pos: BlockPos, state: BlockState, placer: LivingEntity?, stack: ItemStack) {
        super.setPlacedBy(level, pos, state, placer, stack)
        if (level.isClientSide) return
        val placerEntity = level.getBlockEntity(pos) as? PlacerBlockEntity ?: return
        if (placerEntity.ownerUuid == null && placer is Player) {
            placerEntity.ownerUuid = placer.uuid
            placerEntity.setChanged()
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

        val entity = level.getBlockEntity(pos) as? PlacerBlockEntity ?: return InteractionResult.PASS
        val serverPlayer = player as ServerPlayer
        PlatformServices.menu.openExtendedMenu(
            serverPlayer,
            Component.translatable("container.nodeworks.placer"),
            PlacerOpenData(
                pos = pos,
                deviceName = entity.deviceName,
                channelId = entity.channel.id,
                filterRule = entity.filterRule,
                redstoneMode = entity.redstoneMode,
                previewArea = entity.previewArea,
            ),
            PlacerOpenData.STREAM_CODEC,
            { syncId, inv, _ -> PlacerMenu.createServer(syncId, inv, entity) },
        )
        return InteractionResult.SUCCESS
    }

    override fun <T : net.minecraft.world.level.block.entity.BlockEntity> getTicker(
        level: Level,
        state: BlockState,
        blockEntityType: net.minecraft.world.level.block.entity.BlockEntityType<T>,
    ): net.minecraft.world.level.block.entity.BlockEntityTicker<T>? {
        if (level.isClientSide) return null
        return net.minecraft.world.level.block.entity.BlockEntityTicker { lvl, _, _, be ->
            if (be is PlacerBlockEntity && lvl is net.minecraft.server.level.ServerLevel) {
                be.serverTick(lvl)
            }
        }
    }

    override fun playerWillDestroy(level: Level, pos: BlockPos, state: BlockState, player: Player): BlockState {
        val entity = level.getBlockEntity(pos) as? PlacerBlockEntity
        entity?.blockDestroyed = true
        return super.playerWillDestroy(level, pos, state, player)
    }
}
