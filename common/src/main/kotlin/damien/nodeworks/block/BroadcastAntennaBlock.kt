package damien.nodeworks.block

import com.mojang.serialization.MapCodec
import damien.nodeworks.block.entity.BroadcastAntennaBlockEntity
import damien.nodeworks.platform.PlatformServices
import damien.nodeworks.registry.ModBlocks
import damien.nodeworks.screen.BroadcastAntennaMenu
import damien.nodeworks.screen.BroadcastAntennaOpenData
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.Containers
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape

/**
 * Base of the Broadcast Antenna 5-block-tall multiblock. Carries the block entity and
 * all gameplay logic; the four [AntennaSegmentBlock]s above it are purely decorative
 * and forward interactions back down.
 */
class BroadcastAntennaBlock(properties: Properties) : BaseEntityBlock(properties) {

    companion object {
        val CODEC: MapCodec<BroadcastAntennaBlock> = simpleCodec(::BroadcastAntennaBlock)

        /** 11×16×11 footprint, centered. */
        private val SHAPE: VoxelShape = Shapes.box(2.5 / 16.0, 0.0, 2.5 / 16.0, 13.5 / 16.0, 1.0, 13.5 / 16.0)

        /** Spaces above the base that must be free, and what fills each:
         *  y+1..y+3 → segment MIDDLE, y+4 → segment TOP. */
        private const val STACK_HEIGHT = 4
    }

    override fun codec(): MapCodec<out BaseEntityBlock> = CODEC
    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.MODEL

    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape = SHAPE

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity =
        BroadcastAntennaBlockEntity(pos, state)

    override fun getStateForPlacement(context: BlockPlaceContext): BlockState? {
        val level = context.level
        val pos = context.clickedPos
        // Need 4 empty/replaceable spaces above so the segments can auto-place.
        for (i in 1..STACK_HEIGHT) {
            if (!level.getBlockState(pos.above(i)).canBeReplaced(context)) return null
        }
        return defaultBlockState()
    }

    override fun setPlacedBy(level: Level, pos: BlockPos, state: BlockState, placer: LivingEntity?, stack: ItemStack) {
        super.setPlacedBy(level, pos, state, placer, stack)
        if (level.isClientSide) return
        val segment = ModBlocks.ANTENNA_SEGMENT.defaultBlockState()
        for (i in 1..3) {
            level.setBlock(pos.above(i), segment.setValue(AntennaSegmentBlock.PART, AntennaSegmentBlock.Part.MIDDLE), Block.UPDATE_ALL)
        }
        level.setBlock(pos.above(STACK_HEIGHT), segment.setValue(AntennaSegmentBlock.PART, AntennaSegmentBlock.Part.TOP), Block.UPDATE_ALL)
    }

    override fun useWithoutItem(
        state: BlockState, level: Level, pos: BlockPos,
        player: Player, hitResult: BlockHitResult
    ): InteractionResult {
        if (level.isClientSide) return InteractionResult.SUCCESS
        val entity = level.getBlockEntity(pos) as? BroadcastAntennaBlockEntity ?: return InteractionResult.PASS
        val serverPlayer = player as ServerPlayer

        PlatformServices.menu.openExtendedMenu(
            serverPlayer,
            Component.translatable("container.nodeworks.broadcast_antenna"),
            BroadcastAntennaOpenData(pos),
            BroadcastAntennaOpenData.STREAM_CODEC,
            { syncId, inv, p -> BroadcastAntennaMenu.createServer(syncId, inv, entity, pos) }
        )
        return InteractionResult.SUCCESS
    }

    override fun onRemove(state: BlockState, level: Level, pos: BlockPos, newState: BlockState, movedByPiston: Boolean) {
        if (!state.`is`(newState.block)) {
            // Drop inventory contents (if BE is still alive).
            val entity = level.getBlockEntity(pos) as? BroadcastAntennaBlockEntity
            if (entity != null) Containers.dropContents(level, pos, entity)
            // Always cascade-remove segments above, even if BE was already gone.
            if (!level.isClientSide) {
                var cursor = pos.above()
                while (level.getBlockState(cursor).block is AntennaSegmentBlock) {
                    level.setBlock(cursor, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL)
                    cursor = cursor.above()
                }
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston)
    }
}
