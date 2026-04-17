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
import net.minecraft.world.level.block.state.properties.BlockStateProperties
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
        val FACING = BlockStateProperties.HORIZONTAL_FACING
        /** True when the stack's base is reachable from a Network Controller. Only meaningful for
         *  [Part.RECEIVER]; broadcast parts ignore it. Drives the horn on/off multipart model. */
        val CONNECTED: net.minecraft.world.level.block.state.properties.BooleanProperty =
            net.minecraft.world.level.block.state.properties.BooleanProperty.create("connected")

        /** 7×16×7 footprint, centered. */
        private val SHAPE: VoxelShape = Shapes.box(4.5 / 16.0, 0.0, 4.5 / 16.0, 11.5 / 16.0, 1.0, 11.5 / 16.0)

        /** How far up to scan when looking for the stack's base (broadcast antenna block). */
        private const val MAX_STACK_HEIGHT = 5

        /** Check if a block is any antenna base type (broadcast or receiver). */
        fun isAntennaBase(block: net.minecraft.world.level.block.Block): Boolean =
            block is BroadcastAntennaBlock || block is ReceiverAntennaBlock

        /** Find a broadcast or receiver antenna base at or below [pos], or null. */
        fun findBase(level: BlockGetter, pos: BlockPos): BlockPos? {
            var cursor = pos
            for (i in 0 until MAX_STACK_HEIGHT) {
                val state = level.getBlockState(cursor)
                if (isAntennaBase(state.block)) return cursor
                if (state.block !is AntennaSegmentBlock) return null
                cursor = cursor.below()
            }
            return null
        }
    }

    init {
        registerDefaultState(
            stateDefinition.any()
                .setValue(PART, Part.MIDDLE)
                .setValue(FACING, Direction.NORTH)
                .setValue(CONNECTED, false)
        )
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(PART, FACING, CONNECTED)
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

    override fun affectNeighborsAfterRemoval(state: BlockState, level: net.minecraft.server.level.ServerLevel, pos: BlockPos, movedByPiston: Boolean) {
        cascadeRemove(level, pos)
        super.affectNeighborsAfterRemoval(state, level, pos, movedByPiston)
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
        if (isAntennaBase(level.getBlockState(below).block)) {
            level.destroyBlock(below, true)
        }
    }

    override fun getCloneItemStack(
        level: net.minecraft.world.level.LevelReader,
        pos: BlockPos,
        state: BlockState,
        includeData: Boolean
    ): net.minecraft.world.item.ItemStack {
        val part = state.getValue(PART)
        val block = if (part == Part.RECEIVER) ModBlocks.RECEIVER_ANTENNA else ModBlocks.BROADCAST_ANTENNA
        return net.minecraft.world.item.ItemStack(block)
    }

    enum class Part : StringRepresentable {
        MIDDLE, TOP, RECEIVER;
        override fun getSerializedName(): String = name.lowercase()
    }
}
