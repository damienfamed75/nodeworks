package damien.nodeworks.block

import com.mojang.serialization.MapCodec
import damien.nodeworks.block.entity.ReceiverAntennaBlockEntity
import damien.nodeworks.platform.PlatformServices
import damien.nodeworks.registry.ModBlocks
import damien.nodeworks.screen.ReceiverAntennaMenu
import damien.nodeworks.screen.ReceiverAntennaOpenData
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.Containers
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.phys.BlockHitResult

/**
 * Base of the Receiver Antenna 2-block-tall multiblock. Full block (Connectable via
 * block entity). Auto-places one [AntennaSegmentBlock] with [AntennaSegmentBlock.Part.RECEIVER]
 * above it; breaking either cascades the whole stack.
 */
class ReceiverAntennaBlock(properties: Properties) : BaseEntityBlock(properties) {

    companion object {
        val CODEC: MapCodec<ReceiverAntennaBlock> = simpleCodec(::ReceiverAntennaBlock)
        val FACING = BlockStateProperties.HORIZONTAL_FACING
    }

    init {
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(FACING)
    }

    override fun codec(): MapCodec<out BaseEntityBlock> = CODEC
    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.MODEL

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity =
        ReceiverAntennaBlockEntity(pos, state)

    override fun getStateForPlacement(context: BlockPlaceContext): BlockState? {
        val level = context.level
        val pos = context.clickedPos
        if (!level.getBlockState(pos.above()).canBeReplaced(context)) return null
        return defaultBlockState().setValue(FACING, context.horizontalDirection.opposite)
    }

    override fun setPlacedBy(level: Level, pos: BlockPos, state: BlockState, placer: LivingEntity?, stack: ItemStack) {
        super.setPlacedBy(level, pos, state, placer, stack)
        if (level.isClientSide) return
        val facing = state.getValue(FACING)
        level.setBlock(
            pos.above(),
            ModBlocks.ANTENNA_SEGMENT.defaultBlockState()
                .setValue(AntennaSegmentBlock.PART, AntennaSegmentBlock.Part.RECEIVER)
                .setValue(AntennaSegmentBlock.FACING, facing),
            Block.UPDATE_ALL
        )
    }

    override fun useWithoutItem(
        state: BlockState, level: Level, pos: BlockPos,
        player: Player, hitResult: BlockHitResult
    ): InteractionResult {
        if (player.mainHandItem.item is damien.nodeworks.item.NetworkWrenchItem || player.mainHandItem.item is damien.nodeworks.item.DiagnosticToolItem) return InteractionResult.PASS
        if (level.isClientSide) return InteractionResult.SUCCESS
        val entity = level.getBlockEntity(pos) as? ReceiverAntennaBlockEntity ?: return InteractionResult.PASS
        val serverPlayer = player as ServerPlayer

        PlatformServices.menu.openExtendedMenu(
            serverPlayer,
            Component.translatable("container.nodeworks.receiver_antenna"),
            ReceiverAntennaOpenData(pos, if (level is net.minecraft.server.level.ServerLevel) entity.getConnectionStatus(level) else 0),
            ReceiverAntennaOpenData.STREAM_CODEC,
            { syncId, inv, p -> ReceiverAntennaMenu.createServer(syncId, inv, entity, pos) }
        )
        return InteractionResult.SUCCESS
    }

    override fun onRemove(state: BlockState, level: Level, pos: BlockPos, newState: BlockState, movedByPiston: Boolean) {
        if (!state.`is`(newState.block)) {
            val entity = level.getBlockEntity(pos) as? ReceiverAntennaBlockEntity
            if (entity != null) Containers.dropContents(level, pos, entity)
            if (!level.isClientSide) {
                val above = pos.above()
                if (level.getBlockState(above).block is AntennaSegmentBlock) {
                    level.setBlock(above, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL)
                }
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston)
    }

    override fun playerWillDestroy(level: Level, pos: BlockPos, state: BlockState, player: Player): BlockState {
        val entity = level.getBlockEntity(pos) as? ReceiverAntennaBlockEntity
        entity?.blockDestroyed = true
        return super.playerWillDestroy(level, pos, state, player)
    }
}
