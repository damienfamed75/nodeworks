package damien.nodeworks.block

import com.mojang.serialization.MapCodec
import damien.nodeworks.block.entity.PipeBlockEntity
import damien.nodeworks.network.Connectable
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.util.RandomSource
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.LevelReader
import net.minecraft.world.level.ScheduledTickAccess
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BooleanProperty
import net.minecraft.world.level.block.state.properties.EnumProperty
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape

/**
 * Pipe block. The connectivity backbone of the network, auto-connects to any
 * face-adjacent [Connectable] BE (other Pipes, Nodes, Controllers, Terminals,
 * antennas, chests, ...). Renders as a 4×4 cross-section tube with a 6×6×6
 * junction core when the connection pattern isn't a single straight line.
 *
 * BlockState carries 6 per-direction BooleanProperties (one for each face's
 * connection state) plus a derived [pipe_axis] enum that the multipart model
 * uses to pick between three straight-tube models and the junction-core
 * variant. Recomputed via [updateShape] whenever a neighbour changes, the
 * vanilla pattern fences and chains use.
 */
class PipeBlock(properties: Properties) : BaseEntityBlock(properties) {

    init {
        registerDefaultState(
            stateDefinition.any()
                .setValue(PIPE_DOWN, false)
                .setValue(PIPE_UP, false)
                .setValue(PIPE_NORTH, false)
                .setValue(PIPE_SOUTH, false)
                .setValue(PIPE_WEST, false)
                .setValue(PIPE_EAST, false)
                .setValue(PIPE_AXIS, PipeAxis.NONE)
        )
    }

    enum class PipeAxis(private val displayName: String) : net.minecraft.util.StringRepresentable {
        NONE("none"),
        X("x"),
        Y("y"),
        Z("z"),
        JUNCTION("junction");

        override fun getSerializedName(): String = displayName
    }

    companion object {
        val CODEC: MapCodec<PipeBlock> = simpleCodec(::PipeBlock)

        val PIPE_DOWN: BooleanProperty = BooleanProperty.create("pipe_down")
        val PIPE_UP: BooleanProperty = BooleanProperty.create("pipe_up")
        val PIPE_NORTH: BooleanProperty = BooleanProperty.create("pipe_north")
        val PIPE_SOUTH: BooleanProperty = BooleanProperty.create("pipe_south")
        val PIPE_WEST: BooleanProperty = BooleanProperty.create("pipe_west")
        val PIPE_EAST: BooleanProperty = BooleanProperty.create("pipe_east")

        val PIPE_AXIS: EnumProperty<PipeAxis> = EnumProperty.create("pipe_axis", PipeAxis::class.java)

        /** Indexed by [Direction.ordinal] (DOWN, UP, NORTH, SOUTH, WEST, EAST). */
        private val PIPE_PROPS: Array<BooleanProperty> = arrayOf(
            PIPE_DOWN, PIPE_UP, PIPE_NORTH, PIPE_SOUTH, PIPE_WEST, PIPE_EAST,
        )

        fun propFor(side: Direction): BooleanProperty = PIPE_PROPS[side.ordinal]

        /** Whether the pipe at [pos] should connect to its neighbour in
         *  [side]. True when the neighbour is any [Connectable] BE that
         *  accepts a connection on the touching face and neither side has
         *  wrench-blocked it. The face-acceptance check is what hides
         *  pipe stubs on Processing Handler's inert side faces. */
        fun computePipeFlag(level: BlockGetter, pos: BlockPos, side: Direction): Boolean {
            val neighborPos = pos.relative(side)
            val neighborBe = level.getBlockEntity(neighborPos) as? Connectable ?: return false
            if (!neighborBe.adjacencyFaceAllowed(side.opposite, null)) return false
            // Wrench force-block on either side hides the stub. Both sides are
            // queried independently because each BE owns its own per-face flag,
            // toggling one without consulting the other would leave a half-stub.
            val selfBe = level.getBlockEntity(pos) as? Connectable
            if (selfBe?.forcedPipeBlocked(side) == true) return false
            if (neighborBe.forcedPipeBlocked(side.opposite)) return false
            return true
        }

        /** Recompute the 6 directional booleans + axis from current neighbours. */
        fun rebuildState(level: BlockGetter, pos: BlockPos, base: BlockState): BlockState {
            var state = base
            for (dir in Direction.entries) {
                state = state.setValue(propFor(dir), computePipeFlag(level, pos, dir))
            }
            return state.setValue(PIPE_AXIS, computeAxis(state))
        }

        private fun computeAxis(state: BlockState): PipeAxis {
            val n = state.getValue(PIPE_NORTH)
            val s = state.getValue(PIPE_SOUTH)
            val e = state.getValue(PIPE_EAST)
            val w = state.getValue(PIPE_WEST)
            val u = state.getValue(PIPE_UP)
            val d = state.getValue(PIPE_DOWN)
            val count = listOf(n, s, e, w, u, d).count { it }
            if (count == 0) return PipeAxis.NONE
            if (count == 2) {
                if (n && s && !e && !w && !u && !d) return PipeAxis.Z
                if (e && w && !n && !s && !u && !d) return PipeAxis.X
                if (u && d && !n && !s && !e && !w) return PipeAxis.Y
            }
            return PipeAxis.JUNCTION
        }

        // VoxelShape primitives used by [getShape]. 4×4 stubs from the centre
        // out to each face boundary, plus a 6×6×6 core at the centre.
        private val CORE: VoxelShape = Block.box(5.0, 5.0, 5.0, 11.0, 11.0, 11.0)
        private val STUB_DOWN: VoxelShape = Block.box(6.0, 0.0, 6.0, 10.0, 5.0, 10.0)
        private val STUB_UP: VoxelShape = Block.box(6.0, 11.0, 6.0, 10.0, 16.0, 10.0)
        private val STUB_NORTH: VoxelShape = Block.box(6.0, 6.0, 0.0, 10.0, 10.0, 5.0)
        private val STUB_SOUTH: VoxelShape = Block.box(6.0, 6.0, 11.0, 10.0, 10.0, 16.0)
        private val STUB_WEST: VoxelShape = Block.box(0.0, 6.0, 6.0, 5.0, 10.0, 10.0)
        private val STUB_EAST: VoxelShape = Block.box(11.0, 6.0, 6.0, 16.0, 10.0, 10.0)

        private val STRAIGHT_X: VoxelShape = Block.box(0.0, 6.0, 6.0, 16.0, 10.0, 10.0)
        private val STRAIGHT_Y: VoxelShape = Block.box(6.0, 0.0, 6.0, 10.0, 16.0, 10.0)
        private val STRAIGHT_Z: VoxelShape = Block.box(6.0, 6.0, 0.0, 10.0, 10.0, 16.0)

        private val STUB_BY_DIR: Array<VoxelShape> = arrayOf(
            STUB_DOWN, STUB_UP, STUB_NORTH, STUB_SOUTH, STUB_WEST, STUB_EAST,
        )

        fun shapeFor(state: BlockState): VoxelShape = when (state.getValue(PIPE_AXIS)) {
            PipeAxis.X -> STRAIGHT_X
            PipeAxis.Y -> STRAIGHT_Y
            PipeAxis.Z -> STRAIGHT_Z
            PipeAxis.NONE -> CORE
            PipeAxis.JUNCTION -> {
                var combined: VoxelShape = CORE
                for (dir in Direction.entries) {
                    if (state.getValue(propFor(dir))) {
                        combined = Shapes.or(combined, STUB_BY_DIR[dir.ordinal])
                    }
                }
                combined
            }
        }
    }

    override fun codec(): MapCodec<out BaseEntityBlock> = CODEC

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(PIPE_DOWN, PIPE_UP, PIPE_NORTH, PIPE_SOUTH, PIPE_WEST, PIPE_EAST, PIPE_AXIS)
    }

    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.MODEL

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity =
        PipeBlockEntity(pos, state)

    /** Mark the BE as player-destroyed so [damien.nodeworks.network.NodeConnectionHelper.removeAllConnections]
     *  re-propagates from the surviving neighbours. Without this, breaking the
     *  cable that bridges two networks-in-conflict leaves every BE on both
     *  sides with `networkId == null` until a chunk reload re-runs revalidation. */
    override fun playerWillDestroy(
        level: net.minecraft.world.level.Level,
        pos: BlockPos,
        state: BlockState,
        player: net.minecraft.world.entity.player.Player,
    ): BlockState {
        val entity = level.getBlockEntity(pos) as? PipeBlockEntity
        entity?.blockDestroyed = true
        return super.playerWillDestroy(level, pos, state, player)
    }

    override fun getStateForPlacement(ctx: BlockPlaceContext): BlockState =
        rebuildState(ctx.level, ctx.clickedPos, defaultBlockState())

    override fun updateShape(
        state: BlockState,
        level: LevelReader,
        ticks: ScheduledTickAccess,
        pos: BlockPos,
        dir: Direction,
        neighborPos: BlockPos,
        neighborState: BlockState,
        random: RandomSource,
    ): BlockState = rebuildState(level, pos, state)

    override fun getShape(
        state: BlockState,
        level: BlockGetter,
        pos: BlockPos,
        ctx: CollisionContext,
    ): VoxelShape = shapeFor(state)

    override fun getCollisionShape(
        state: BlockState,
        level: BlockGetter,
        pos: BlockPos,
        ctx: CollisionContext,
    ): VoxelShape = shapeFor(state)
}
