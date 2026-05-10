package damien.nodeworks.block

import com.mojang.serialization.MapCodec
import damien.nodeworks.block.entity.NodeBlockEntity
import damien.nodeworks.item.NetworkWrenchItem
import damien.nodeworks.screen.NodeSideOpenData
import damien.nodeworks.screen.NodeSideScreenHandler
import damien.nodeworks.platform.PlatformServices
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.RandomSource
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.ContainerLevelAccess
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.LevelAccessor
import net.minecraft.world.level.LevelReader
import net.minecraft.world.level.ScheduledTickAccess
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BooleanProperty
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape

open class NodeBlock(properties: Properties) : BaseEntityBlock(properties) {

    init {
        registerDefaultState(
            stateDefinition.any()
                .setValue(PIPE_DOWN, false)
                .setValue(PIPE_UP, false)
                .setValue(PIPE_NORTH, false)
                .setValue(PIPE_SOUTH, false)
                .setValue(PIPE_WEST, false)
                .setValue(PIPE_EAST, false)
        )
    }

    companion object {
        val CODEC: MapCodec<NodeBlock> = simpleCodec(::NodeBlock)

        /** 8×8×8 outer wireframe (pixels 4..12) for hit-testing and collision.
         *  Visually the Node fills only the inner 6×6×6 core (5..11) plus the
         *  wireframe edges around it, but the collision box uses the full 4..12
         *  so the player can interact with the wireframe edges. Bigger than
         *  the 6×6×6 pipe junction so a Node placed in the middle of a pipe
         *  run reads as the "fat" piece. */
        val NODE_CORE_SHAPE: VoxelShape = Block.box(4.0, 4.0, 4.0, 12.0, 12.0, 12.0)

        // Node-side stub geometry. Same 4×4×5 shape the [PipeBlock] stubs use:
        // the visible Node core sits at pixels 5..11 (6×6×6), so the stub spans
        // from the block face (0/16) to the core boundary at 5/11. Sharing the
        // pipe stub dimensions lets the Node multipart reuse pipe_stub_* models
        // verbatim, no Node-specific stub assets needed.
        private val STUB_DOWN: VoxelShape = Block.box(6.0, 0.0, 6.0, 10.0, 5.0, 10.0)
        private val STUB_UP: VoxelShape = Block.box(6.0, 11.0, 6.0, 10.0, 16.0, 10.0)
        private val STUB_NORTH: VoxelShape = Block.box(6.0, 6.0, 0.0, 10.0, 10.0, 5.0)
        private val STUB_SOUTH: VoxelShape = Block.box(6.0, 6.0, 11.0, 10.0, 10.0, 16.0)
        private val STUB_WEST: VoxelShape = Block.box(0.0, 6.0, 6.0, 5.0, 10.0, 10.0)
        private val STUB_EAST: VoxelShape = Block.box(11.0, 6.0, 6.0, 16.0, 10.0, 10.0)
        private val STUB_BY_DIR: Array<VoxelShape> = arrayOf(
            STUB_DOWN, STUB_UP, STUB_NORTH, STUB_SOUTH, STUB_WEST, STUB_EAST,
        )

        val PIPE_DOWN: BooleanProperty = BooleanProperty.create("pipe_down")
        val PIPE_UP: BooleanProperty = BooleanProperty.create("pipe_up")
        val PIPE_NORTH: BooleanProperty = BooleanProperty.create("pipe_north")
        val PIPE_SOUTH: BooleanProperty = BooleanProperty.create("pipe_south")
        val PIPE_WEST: BooleanProperty = BooleanProperty.create("pipe_west")
        val PIPE_EAST: BooleanProperty = BooleanProperty.create("pipe_east")

        /** Indexed by [Direction.ordinal]. */
        private val PIPE_PROPS: Array<BooleanProperty> = arrayOf(
            PIPE_DOWN, PIPE_UP, PIPE_NORTH, PIPE_SOUTH, PIPE_WEST, PIPE_EAST,
        )

        fun propFor(side: Direction): BooleanProperty = PIPE_PROPS[side.ordinal]

        /** Whether the Node at [pos] should render a pipe stub on [side]. True
         *  when the neighbour is a Connectable BE that accepts a connection
         *  on the touching face (so a User's inert sides + Processing
         *  Handler's left/right/top/bottom don't sprout phantom stubs), and
         *  neither side has wrench-blocked it. Same rule as
         *  [PipeBlock.computePipeFlag], the two block types share the
         *  Connectable-as-source-of-truth contract. */
        fun computePipeFlag(level: BlockGetter, pos: BlockPos, side: Direction): Boolean {
            val neighborPos = pos.relative(side)
            val neighborBe = level.getBlockEntity(neighborPos) as? damien.nodeworks.network.Connectable
                ?: return false
            if (!neighborBe.adjacencyFaceAllowed(side.opposite, null)) return false
            val selfBe = level.getBlockEntity(pos) as? damien.nodeworks.network.Connectable
            if (selfBe?.forcedPipeBlocked(side) == true) return false
            if (neighborBe.forcedPipeBlocked(side.opposite)) return false
            return true
        }

        /** Recompute the 6 directional booleans against current neighbours. */
        fun rebuildState(level: BlockGetter, pos: BlockPos, base: BlockState): BlockState {
            var state = base
            for (dir in Direction.entries) {
                state = state.setValue(propFor(dir), computePipeFlag(level, pos, dir))
            }
            return state
        }

        /** Composed VoxelShape: [core] plus a stub for each PIPE-roled face.
         *  Cached implicitly via the blockstate identity (vanilla caches
         *  per-state shape lookups). [core] defaults to the regular Node's
         *  8×8×8 cube; subclasses (Focus Node) pass their own. */
        fun shapeFor(state: BlockState, core: VoxelShape = NODE_CORE_SHAPE): VoxelShape {
            var combined: VoxelShape = core
            for (dir in Direction.entries) {
                if (state.getValue(propFor(dir))) {
                    combined = Shapes.or(combined, STUB_BY_DIR[dir.ordinal])
                }
            }
            return combined
        }

        /** A resolved placement: which node, and which side of it. */
        data class PlacementTarget(val nodePos: BlockPos, val side: Direction)

        /** Resolve a click into a card-placement target, or null when the click
         *  doesn't target a node face (directly or through an adjacent block).
         *
         *  Two paths:
         *  1. The clicked block IS a node, side comes from [clickedFace],
         *     flipped to the opposite when [shiftHeld] (long-standing UX so
         *     hard-to-reach faces still work via shift).
         *  2. The clicked block is *adjacent* to a node in the [clickedFace]
         *     direction. Side resolves to the node's face touching the clicked
         *     block, i.e. [clickedFace.opposite].
         *
         *  Vanilla's own use-order handles the "is this block interactable"
         *  gate for us: if the player right-clicks a chest with a card,
         *  [ChestBlock.useItemOn] consumes the click and [Item.useOn] is never
         *  called, so we never get a chance to place on the adjacent node.
         *  For non-interactable blocks (stone, dirt, glass, ...) the block's
         *  useItemOn returns PASS and the click reaches us, so the card lands
         *  on the node behind it without the player needing to hold shift.
         *  Shift is still useful as the explicit override that bypasses
         *  [Block.useItemOn] entirely (for placing through interactable
         *  neighbours), and to flip onto the opposite face when targeting the
         *  node directly.
         *
         *  We track shift via the key state, not the crouch pose, so creative
         *  flight (where shift descends instead of crouching) still triggers
         *  the flip. */
        fun resolvePlacementTarget(
            level: Level,
            pos: BlockPos,
            clickedFace: Direction,
            shiftHeld: Boolean,
        ): PlacementTarget? {
            val state = level.getBlockState(pos)
            if (state.block is NodeBlock) {
                val side = if (shiftHeld) clickedFace.opposite else clickedFace
                return rejectIfPipeFace(level, pos, side)
            }
            val adjPos = pos.relative(clickedFace)
            val adjState = level.getBlockState(adjPos)
            if (adjState.block !is NodeBlock) return null
            return rejectIfPipeFace(level, adjPos, clickedFace.opposite)
        }

        /** Card placement is gated on face role: PIPE faces are consumed by
         *  the network connection and refuse cards. Returns the target on
         *  DEVICE / FREE faces, null on PIPE. */
        private fun rejectIfPipeFace(level: Level, nodePos: BlockPos, side: Direction): PlacementTarget? {
            val be = level.getBlockEntity(nodePos) as? NodeBlockEntity ?: return null
            if (be.faceRole(side) == NodeBlockEntity.FaceRole.PIPE) return null
            return PlacementTarget(nodePos, side)
        }

        /** Quick-place a card into the first empty slot on the targeted face of a
         *  Node. Shared between [useItemOn] (no-shift right click on the node),
         *  the [damien.nodeworks.card.NodeCard.useOn] override (shift right click,
         *  on either the node or any block adjacent to it). Returns:
         *
         *    * [InteractionResult.SUCCESS] when a card was placed.
         *    * [InteractionResult.TRY_WITH_EMPTY_HAND] when the targeted face is
         *      full, so the surrounding chain can fall through to the GUI.
         *    * [InteractionResult.PASS] when the stack isn't a card. */
        fun tryQuickPlaceCard(
            stack: net.minecraft.world.item.ItemStack,
            level: Level,
            target: PlacementTarget,
            player: Player,
        ): InteractionResult {
            if (stack.item !is damien.nodeworks.card.NodeCard) return InteractionResult.PASS
            val be = level.getBlockEntity(target.nodePos) as? NodeBlockEntity ?: return InteractionResult.PASS
            var emptySlot = -1
            for (i in 0 until NodeBlockEntity.SLOTS_PER_SIDE) {
                if (be.getStack(target.side, i).isEmpty) {
                    emptySlot = i
                    break
                }
            }
            if (emptySlot == -1) return InteractionResult.TRY_WITH_EMPTY_HAND
            if (level.isClientSide) return InteractionResult.SUCCESS
            be.setStack(target.side, emptySlot, stack.copyWithCount(1))
            if (!player.abilities.instabuild) stack.shrink(1)
            level.playSound(
                null, target.nodePos,
                net.minecraft.sounds.SoundEvents.ITEM_FRAME_ADD_ITEM,
                net.minecraft.sounds.SoundSource.BLOCKS,
                1.0f, 1.0f,
            )
            spawnCardPlacementPuff(level, target.nodePos, target.side, stack.item as damien.nodeworks.card.NodeCard)
            return InteractionResult.SUCCESS
        }

        /** Tinted dust puff at the centre of the face the card was placed on,
         *  colour matched to the card type so a row of cards visually says
         *  what's where as the player drops them in. Mirrors the palette
         *  [damien.nodeworks.render.NodeRenderer] uses on the node body so the
         *  puff and the resulting indicator agree. */
        private fun spawnCardPlacementPuff(
            level: Level,
            pos: BlockPos,
            face: Direction,
            card: damien.nodeworks.card.NodeCard,
        ) {
            val server = level as? net.minecraft.server.level.ServerLevel ?: return
            val color = cardTypeColor(card.cardType)
            // Node geometry is a 6x6x6 centred cube (5..11). The face centre is
            // 3px out from the block centre along the face normal, plus a 2px
            // offset so the puff blooms in front of the face rather than
            // overlapping the node body. Total: 5px = 5/16 = 0.3125.
            val cx = pos.x + 0.5 + face.stepX * 0.3125
            val cy = pos.y + 0.5 + face.stepY * 0.3125
            val cz = pos.z + 0.5 + face.stepZ * 0.3125
            server.sendParticles(
                net.minecraft.core.particles.DustParticleOptions(color, 1.0f),
                cx, cy, cz,
                6,                  // count
                0.08, 0.08, 0.08,   // per-axis spread
                0.01,               // speed
            )
        }

        private fun cardTypeColor(type: String): Int = when (type) {
            "io" -> 0x83E086        // green
            "storage" -> 0xAA83E0   // purple
            "redstone" -> 0xF53B68  // red
            "observer" -> 0xFFEB3B  // yellow
            else -> 0xFFFFFF        // unrecognised cards fall back to white
        }
    }

    override fun codec(): MapCodec<out BaseEntityBlock> = CODEC

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(PIPE_DOWN, PIPE_UP, PIPE_NORTH, PIPE_SOUTH, PIPE_WEST, PIPE_EAST)
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
        context: CollisionContext
    ): VoxelShape = shapeFor(state)

    override fun getCollisionShape(
        state: BlockState,
        level: BlockGetter,
        pos: BlockPos,
        context: CollisionContext
    ): VoxelShape = shapeFor(state)

    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.MODEL

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity {
        return NodeBlockEntity(pos, state)
    }

    override fun useWithoutItem(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hitResult: BlockHitResult
    ): InteractionResult {
        if (player.mainHandItem.item is NetworkWrenchItem || player.mainHandItem.item is damien.nodeworks.item.DiagnosticToolItem) return InteractionResult.PASS
        if (level.isClientSide) return InteractionResult.SUCCESS

        val blockEntity = level.getBlockEntity(pos) as? NodeBlockEntity ?: return InteractionResult.PASS
        val side = hitResult.direction

        // Shift+Right Click opens the opposite side. Track the key state, not
        // the crouch pose, so creative flight (shift descends, no crouch pose)
        // still flips the GUI to the far face.
        val initialSide = if (player.isShiftKeyDown) side.opposite else side
        // Pipe-roled faces (touching another Connectable) have no card slots,
        // skip to the first non-pipe face. Defensive PASS if every face is
        // pipe-roled (a Node fully surrounded by Connectables, in which case
        // there's no useful GUI to open).
        val openSide = if (blockEntity.faceRole(initialSide) != NodeBlockEntity.FaceRole.PIPE) {
            initialSide
        } else {
            Direction.entries.firstOrNull { blockEntity.faceRole(it) != NodeBlockEntity.FaceRole.PIPE }
                ?: return InteractionResult.PASS
        }
        val serverPlayer = player as ServerPlayer
        val sideName = openSide.name.replaceFirstChar { it.uppercase() }
        PlatformServices.menu.openExtendedMenu(
            serverPlayer,
            Component.translatable("container.nodeworks.node_side", sideName),
            NodeSideOpenData(pos, openSide.ordinal),
            NodeSideOpenData.STREAM_CODEC,
            { syncId, inv, p -> NodeSideScreenHandler(syncId, inv, blockEntity, openSide, pos, ContainerLevelAccess.create(level, pos)) }
        )

        return InteractionResult.SUCCESS
    }

    /** Quick-place: right-click a Node while holding a Card drops the card into
     *  the first empty slot on the clicked face without opening the GUI. The
     *  shift+right-click variant (and the place-via-adjacent-block variant)
     *  live in [damien.nodeworks.card.NodeCard.useOn] because vanilla bypasses
     *  block.useItemOn when the player crouches with an item in hand. Both
     *  paths funnel through [resolvePlacementTarget] + [tryQuickPlaceCard]. */
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
        if (stack.item is NetworkWrenchItem ||
            stack.item is damien.nodeworks.item.DiagnosticToolItem
        ) return InteractionResult.TRY_WITH_EMPTY_HAND
        val target = resolvePlacementTarget(level, pos, hitResult.direction, player.isShiftKeyDown)
            ?: return InteractionResult.TRY_WITH_EMPTY_HAND
        val result = tryQuickPlaceCard(stack, level, target, player)
        // PASS = not a card, fall through to GUI. Other results are returned as-is.
        return if (result == InteractionResult.PASS) InteractionResult.TRY_WITH_EMPTY_HAND
        else result
    }

    // --- Redstone emission ---

    override fun isSignalSource(state: BlockState): Boolean = true

    override fun getSignal(state: BlockState, level: BlockGetter, pos: BlockPos, direction: Direction): Int {
        // `direction` is the side of the querying block, the node side is the opposite
        val entity = level.getBlockEntity(pos) as? NodeBlockEntity ?: return 0
        return entity.getRedstoneOutput(direction.opposite)
    }

    override fun getDirectSignal(state: BlockState, level: BlockGetter, pos: BlockPos, direction: Direction): Int {
        return getSignal(state, level, pos, direction)
    }

    override fun playerWillDestroy(level: Level, pos: BlockPos, state: BlockState, player: Player): BlockState {
        val entity = level.getBlockEntity(pos) as? NodeBlockEntity
        if (entity != null) entity.blockDestroyed = true
        return super.playerWillDestroy(level, pos, state, player)
    }
}
