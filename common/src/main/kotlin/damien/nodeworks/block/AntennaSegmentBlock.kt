package damien.nodeworks.block

import damien.nodeworks.registry.ModBlocks
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.util.StringRepresentable
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.EnumProperty
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape

/**
 * Non-interactive upper block of the Broadcast Antenna multiblock. The [BroadcastAntennaBlock]
 * auto-places three [Part.MIDDLE] and one [Part.TOP] segments above it; breaking any part of
 * the stack cascades to remove the rest. Right-click / wrench forwards to the bottom block.
 */
class AntennaSegmentBlock(properties: Properties) : Block(properties) {

    companion object {
        val PART: EnumProperty<Part> = EnumProperty.create("part", Part::class.java)

        /** 7×16×7 footprint, centered. */
        private val SHAPE: VoxelShape = Shapes.box(4.5 / 16.0, 0.0, 4.5 / 16.0, 11.5 / 16.0, 1.0, 11.5 / 16.0)

        /** How far up to scan when looking for the stack's base (broadcast antenna block). */
        private const val MAX_STACK_HEIGHT = 5

        /** Find the [BroadcastAntennaBlock] at or below [pos], or null if not part of a stack. */
        fun findBase(level: BlockGetter, pos: BlockPos): BlockPos? {
            var cursor = pos
            for (i in 0 until MAX_STACK_HEIGHT) {
                val state = level.getBlockState(cursor)
                if (state.block is BroadcastAntennaBlock) return cursor
                if (state.block !is AntennaSegmentBlock) return null
                cursor = cursor.below()
            }
            return null
        }
    }

    init {
        registerDefaultState(stateDefinition.any().setValue(PART, Part.MIDDLE))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(PART)
    }

    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape = SHAPE

    override fun useWithoutItem(
        state: BlockState, level: Level, pos: BlockPos,
        player: Player, hitResult: BlockHitResult
    ): InteractionResult {
        // Forward right-click / wrench to the base antenna.
        val basePos = findBase(level, pos) ?: return InteractionResult.PASS
        val baseState = level.getBlockState(basePos)
        return baseState.useWithoutItem(level, player, hitResult)
    }

    override fun onRemove(state: BlockState, level: Level, pos: BlockPos, newState: BlockState, movedByPiston: Boolean) {
        if (!state.`is`(newState.block) && !level.isClientSide) {
            cascadeRemove(level, pos)
        }
        super.onRemove(state, level, pos, newState, movedByPiston)
    }

    private fun cascadeRemove(level: Level, pos: BlockPos) {
        // Remove segments ABOVE the broken position first — the broken block is already
        // AIR so the base's upward scan would stop at the gap if we don't clear these now.
        var cursor = pos.above()
        while (level.getBlockState(cursor).block is AntennaSegmentBlock) {
            level.setBlock(cursor, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL)
            cursor = cursor.above()
        }
        // Remove segments BELOW (between us and the base).
        var below = pos.below()
        while (level.getBlockState(below).block is AntennaSegmentBlock) {
            level.setBlock(below, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL)
            below = below.below()
        }
        // Destroy the base — drops antenna item + inventory via the base's onRemove.
        if (level.getBlockState(below).block is BroadcastAntennaBlock) {
            level.destroyBlock(below, true)
        }
    }

    override fun getCloneItemStack(
        level: net.minecraft.world.level.LevelReader,
        pos: BlockPos,
        state: BlockState
    ): net.minecraft.world.item.ItemStack {
        // Picking the block gives the antenna item, not the segment (which has no obtainable item form).
        return net.minecraft.world.item.ItemStack(ModBlocks.BROADCAST_ANTENNA)
    }

    enum class Part : StringRepresentable {
        MIDDLE, TOP;
        override fun getSerializedName(): String = name.lowercase()
    }
}
