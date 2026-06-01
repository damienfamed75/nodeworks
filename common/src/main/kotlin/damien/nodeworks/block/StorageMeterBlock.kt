package damien.nodeworks.block

import com.mojang.serialization.MapCodec
import damien.nodeworks.block.entity.StorageMeterBlockEntity
import damien.nodeworks.item.DiagnosticToolItem
import damien.nodeworks.item.NetworkWrenchItem
import damien.nodeworks.platform.PlatformServices
import damien.nodeworks.screen.StorageMeterMenu
import damien.nodeworks.screen.StorageMeterOpenData
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
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
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape

/**
 * Storage Meter. Tracks one configured item across network storage on a chosen
 * channel and emits a redstone signal when the count drops below the
 * threshold. When the local network has a Crafting CPU it also submits the
 * missing-quantity craft itself; with no CPU it stays purely a redstone
 * emitter, which is the cross-network pattern (paired with a Craft Requester
 * on another network).
 *
 * Right-click with an empty hand opens the settings GUI. Right-click with
 * any other item sets that item as the tracked target (no consumption).
 * Right-click with the already-configured item also opens the GUI.
 * Right-click while holding a wrench rotates the block via [Wrenchable].
 */
class StorageMeterBlock(properties: Properties) : BaseEntityBlock(properties), Wrenchable {

    companion object {
        val CODEC: MapCodec<StorageMeterBlock> = simpleCodec(::StorageMeterBlock)
        val FACING = BlockStateProperties.HORIZONTAL_FACING

        /** Occlusion shape covers only the top and bottom 2-px slabs of the
         *  Blockbench model: the only faces of the meter that actually fill
         *  the block edge. Returning a full cube here would make neighbouring
         *  blocks cull the faces touching the meter's hourglass mid-section,
         *  leaving visible gaps. Mirrors the [CraftRequesterBlock] shape. */
        private val OCCLUSION_SHAPE: VoxelShape = Shapes.or(
            Block.box(0.0, 14.0, 0.0, 16.0, 16.0, 16.0),
            Block.box(0.0, 0.0, 0.0, 16.0, 2.0, 16.0),
        )
    }

    init {
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(FACING)
    }

    override fun codec(): MapCodec<out BaseEntityBlock> = CODEC
    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.MODEL
    override fun getOcclusionShape(state: BlockState): VoxelShape = OCCLUSION_SHAPE

    override fun getStateForPlacement(context: BlockPlaceContext): BlockState =
        defaultBlockState().setValue(FACING, context.horizontalDirection.opposite)

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity =
        StorageMeterBlockEntity(pos, state)

    override fun <T : BlockEntity> getTicker(
        level: Level,
        state: BlockState,
        blockEntityType: BlockEntityType<T>,
    ): BlockEntityTicker<T>? {
        if (level.isClientSide) return null
        return BlockEntityTicker { lvl, _, _, be ->
            if (be is StorageMeterBlockEntity && lvl is net.minecraft.server.level.ServerLevel) {
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
        val handItem = player.mainHandItem.item
        if (handItem is NetworkWrenchItem || handItem is DiagnosticToolItem) return InteractionResult.PASS
        if (level.isClientSide) return InteractionResult.SUCCESS
        val entity = level.getBlockEntity(pos) as? StorageMeterBlockEntity ?: return InteractionResult.PASS
        val serverPlayer = player as ServerPlayer
        PlatformServices.menu.openExtendedMenu(
            serverPlayer,
            Component.translatable("container.nodeworks.storage_meter"),
            StorageMeterOpenData(
                pos = pos,
                target = entity.target,
                threshold = entity.threshold,
                channelId = entity.channel.toNbtInt(),
                autocraftEnabled = entity.autocraftEnabled,
            ),
            StorageMeterOpenData.STREAM_CODEC,
            { syncId, inv, _ -> StorageMeterMenu.createServer(syncId, inv, entity) },
        )
        return InteractionResult.SUCCESS
    }

    // Right-click with an item in hand fires useItemOn BEFORE useWithoutItem.
    // Quick-config path: any normal item → sets that item as the tracked
    // target without consuming the stack, no GUI opens. Empty hand /
    // wrench / diagnostic fall through to useWithoutItem for the usual GUI
    // and tool behaviours. Right-clicking with the item already configured
    // also falls through, so a quick second click on a set-up meter still
    // opens the GUI for fine-tuning threshold / channel.
    override fun useItemOn(
        stack: net.minecraft.world.item.ItemStack,
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hand: net.minecraft.world.InteractionHand,
        hitResult: BlockHitResult,
    ): InteractionResult {
        if (stack.isEmpty) return InteractionResult.TRY_WITH_EMPTY_HAND
        if (stack.item is NetworkWrenchItem || stack.item is DiagnosticToolItem) {
            return InteractionResult.TRY_WITH_EMPTY_HAND
        }
        if (level.isClientSide) return InteractionResult.SUCCESS
        val entity = level.getBlockEntity(pos) as? StorageMeterBlockEntity
            ?: return InteractionResult.TRY_WITH_EMPTY_HAND
        if (net.minecraft.world.item.ItemStack.isSameItemSameComponents(stack, entity.target)) {
            return InteractionResult.TRY_WITH_EMPTY_HAND
        }
        entity.target = stack.copyWithCount(1)
        return InteractionResult.SUCCESS
    }

    // ---- Redstone output ----

    override fun isSignalSource(state: BlockState): Boolean = true

    override fun getSignal(state: BlockState, level: net.minecraft.world.level.BlockGetter, pos: BlockPos, direction: Direction): Int {
        val entity = level.getBlockEntity(pos) as? StorageMeterBlockEntity ?: return 0
        return if (entity.isBelowThreshold) 15 else 0
    }

    override fun playerWillDestroy(level: Level, pos: BlockPos, state: BlockState, player: Player): BlockState {
        val entity = level.getBlockEntity(pos) as? StorageMeterBlockEntity
        entity?.blockDestroyed = true
        return super.playerWillDestroy(level, pos, state, player)
    }
}
