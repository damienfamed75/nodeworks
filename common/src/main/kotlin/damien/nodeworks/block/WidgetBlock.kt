package damien.nodeworks.block

import com.mojang.serialization.MapCodec
import damien.nodeworks.block.entity.WidgetBlockEntity
import damien.nodeworks.block.entity.WidgetType
import damien.nodeworks.item.NetworkWrenchItem
import damien.nodeworks.platform.PlatformServices
import damien.nodeworks.screen.WidgetMenu
import damien.nodeworks.screen.WidgetOpenData
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.BlockGetter
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
import net.minecraft.world.level.block.state.properties.EnumProperty
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape

/**
 * Widget, a placed, GUI-configured control surface (button / switch / radio /
 * slider). It does nothing on its own, scripts read its value or hook a
 * callback via `network:get(name)`. DIRECTIONAL like the Breaker / Placer /
 * User: the front face is the widget, the other five network-connect.
 *
 * Plain right-click operates the widget forward. Shift + right-click on the
 * centred control band operates it in reverse; shift + right-click on the
 * name / value rows (above or below the band) opens the config GUI. See
 * [WidgetBlockEntity] for the per-type state model.
 */
class WidgetBlock(properties: Properties) : BaseEntityBlock(properties) {

    companion object {
        val CODEC: MapCodec<WidgetBlock> = simpleCodec(::WidgetBlock)
        val FACING = BlockStateProperties.FACING

        /** Blockstate enum that drives per-type model selection in the
         *  chunk renderer. Kept in sync with [WidgetBlockEntity.widgetType]
         *  by [WidgetBlockEntity.setType]. */
        val WIDGET_TYPE: EnumProperty<WidgetType> =
            EnumProperty.create("widget_type", WidgetType::class.java)

        /** Full 16×16×16 hitbox: the widget reads as a solid block to hit-tests
         *  and collisions regardless of facing, so the player has the whole
         *  face to click on for the control. */
        private val SHAPE_FULL: VoxelShape = Shapes.block()

        /** Slider track + handle geometry, shared with [damien.nodeworks.render.WidgetRenderer]
         *  so the click hit-test and the rendered handle stay in lockstep. Track
         *  is centred on the face, the handle's edges line up with the track's
         *  edges at min / max value. Edit these if you author a different
         *  track size in `widget_slider.json`. */
        const val SLIDER_TRACK_WIDTH_PX = 11
        const val SLIDER_HANDLE_WIDTH_PX = 2
        /** Block-relative inset of the track's left edge from the block's
         *  left edge. = (16 - track_width) / 2 / 16. */
        const val SLIDER_TRACK_MIN = (16f - SLIDER_TRACK_WIDTH_PX) / 32f
        /** Block-relative width of the slidable track. */
        const val SLIDER_TRACK_SPAN = SLIDER_TRACK_WIDTH_PX / 16f
        /** Block-relative travel of the handle's centre, edge-to-edge of the
         *  track. = (track_width - handle_width) / 16. */
        const val SLIDER_HANDLE_TRAVEL = (SLIDER_TRACK_WIDTH_PX - SLIDER_HANDLE_WIDTH_PX) / 16f

        /** Vertical band of the face occupied by the control (the name label
         *  sits above it, the value readout below). A shift-click inside this
         *  band operates the widget in reverse; a shift-click on the name /
         *  value rows opens the config GUI. Symmetric about the face centre
         *  (0.5), matching [damien.nodeworks.render.WidgetRenderer]'s centred
         *  control. */
        private const val CONTROL_BAND_MIN = 0.30f
        private const val CONTROL_BAND_MAX = 0.70f

        /** Project a point on the block's front face onto the slider's track,
         *  returning a 0..1 fraction along the face's local +X axis (the axis
         *  [damien.nodeworks.render.WidgetRenderer] positions the handle on).
         *  The fraction is remapped to the track's actual width ([SLIDER_TRACK_SPAN])
         *  so a click on the track's left edge gives 0 and a click on its right
         *  edge gives 1, with clicks past the track clamped. Shared by the
         *  click handler and the client-side drag controller. */
        fun sliderFractionFor(facing: Direction, pos: BlockPos, loc: net.minecraft.world.phys.Vec3): Float {
            val lx = (loc.x - pos.x).toFloat()
            val lz = (loc.z - pos.z).toFloat()
            val rawFrac = when (facing) {
                Direction.SOUTH -> lx
                Direction.NORTH -> 1f - lx
                Direction.EAST -> 1f - lz
                Direction.WEST -> lz
                else -> lx // UP / DOWN: nominal, the track has no world-horizontal anchor
            }
            return ((rawFrac - SLIDER_TRACK_MIN) / SLIDER_TRACK_SPAN).coerceIn(0f, 1f)
        }
    }

    init {
        registerDefaultState(
            stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(WIDGET_TYPE, WidgetType.BUTTON)
        )
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(FACING, WIDGET_TYPE)
    }

    override fun codec(): MapCodec<out BaseEntityBlock> = CODEC
    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.MODEL

    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape =
        SHAPE_FULL

    override fun getStateForPlacement(context: BlockPlaceContext): BlockState? =
        defaultBlockState().setValue(FACING, context.nearestLookingDirection.opposite)

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity =
        WidgetBlockEntity(pos, state)

    override fun <T : BlockEntity> getTicker(
        level: Level,
        state: BlockState,
        blockEntityType: BlockEntityType<T>,
    ): BlockEntityTicker<T>? {
        // Server-side only: the ticker just releases a held BUTTON when its
        // hold expires, a couple of comparisons per widget per tick.
        if (level.isClientSide) return null
        return BlockEntityTicker { lvl, _, _, be ->
            if (be is WidgetBlockEntity && lvl is ServerLevel) be.serverTick(lvl)
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
        if (level.isClientSide) {
            // A plain right-click on a slider starts client-side drag tracking,
            // which streams the value to the server while the use key is held.
            if (!player.isShiftKeyDown) {
                val be = level.getBlockEntity(pos) as? WidgetBlockEntity
                if (be?.widgetType == WidgetType.SLIDER) {
                    damien.nodeworks.render.WidgetDragController.beginDrag(pos, state.getValue(FACING))
                }
            }
            return InteractionResult.SUCCESS
        }

        val entity = level.getBlockEntity(pos) as? WidgetBlockEntity ?: return InteractionResult.PASS

        // Plain right-click operates the widget forward. Shift-click splits by
        // where on the face it landed: a click inside the centred control band
        // operates the widget in reverse, a click on the name / value rows
        // (above or below the band) opens the config GUI. The BUTTON's cap is
        // narrow, so its band is checked on both axes — the empty space left
        // and right of the cap also opens the GUI.
        if (player.isShiftKeyDown) {
            val v = faceV(state, pos, hitResult)
            val inBand = if (entity.widgetType == WidgetType.BUTTON) {
                val u = faceU(state, pos, hitResult)
                v in CONTROL_BAND_MIN..CONTROL_BAND_MAX && u in CONTROL_BAND_MIN..CONTROL_BAND_MAX
            } else {
                v in CONTROL_BAND_MIN..CONTROL_BAND_MAX
            }
            if (inBand) {
                operate(level, pos, state, entity, hitResult, reverse = true)
            } else {
                openConfig(pos, player as ServerPlayer, entity)
            }
            return InteractionResult.SUCCESS
        }

        operate(level, pos, state, entity, hitResult, reverse = false)
        return InteractionResult.SUCCESS
    }

    private fun openConfig(pos: BlockPos, player: ServerPlayer, entity: WidgetBlockEntity) {
        PlatformServices.menu.openExtendedMenu(
            player,
            Component.translatable("container.nodeworks.widget"),
            WidgetOpenData(
                pos = pos,
                widgetName = entity.widgetName,
                widgetType = entity.widgetType.ordinal,
                channelId = entity.channel.id,
                sliderMin = entity.sliderMin,
                sliderMax = entity.sliderMax,
                sliderStep = entity.sliderStep,
                options = entity.radioOptions.joinToString("\n"),
                buttonHoldTicks = entity.buttonHoldTicks,
            ),
            WidgetOpenData.STREAM_CODEC,
            { syncId, inv, _ -> WidgetMenu.createServer(syncId, inv, entity) },
        )
    }

    /** Apply a right-click to the widget, dispatched on its configured type.
     *  [reverse] runs the action backward, radio cycles back, slider steps
     *  down. Button / switch ignore it (a switch toggle is its own reverse). */
    private fun operate(
        level: Level,
        pos: BlockPos,
        state: BlockState,
        entity: WidgetBlockEntity,
        hitResult: BlockHitResult,
        reverse: Boolean,
    ) {
        when (entity.widgetType) {
            WidgetType.BUTTON -> {
                // A latched hold absorbs further clicks, no click sound.
                if (entity.press()) {
                    playSound(level, pos, SoundEvents.STONE_BUTTON_CLICK_ON, 1.0f)
                }
            }
            WidgetType.SWITCH -> {
                entity.toggle()
                playSound(level, pos, SoundEvents.LEVER_CLICK, if (entity.value >= 0.5f) 0.6f else 0.5f)
            }
            WidgetType.RADIO -> {
                if (reverse) entity.cycleBack() else entity.cycle()
                playSound(level, pos, SoundEvents.STONE_BUTTON_CLICK_ON, 1.2f)
            }
            WidgetType.SLIDER -> {
                // Forward: jump to the clicked position. Reverse: nudge one step down.
                val changed = if (reverse) {
                    entity.setSliderValue(entity.value - entity.sliderStep)
                } else {
                    val frac = sliderFraction(state, pos, hitResult)
                    entity.setSliderValue(entity.sliderMin + frac * (entity.sliderMax - entity.sliderMin))
                }
                if (changed) playSound(level, pos, SoundEvents.STONE_BUTTON_CLICK_ON, sliderClickPitch(entity))
            }
        }
    }

    private fun sliderFraction(state: BlockState, pos: BlockPos, hitResult: BlockHitResult): Float =
        sliderFractionFor(state.getValue(FACING), pos, hitResult.location)

    /** Vertical position of the hit on the face, 0 (bottom) .. 1 (top). For
     *  UP / DOWN facings there's no true vertical, the north-south axis stands
     *  in so the GUI / reverse split still works. */
    private fun faceV(state: BlockState, pos: BlockPos, hitResult: BlockHitResult): Float {
        val loc = hitResult.location
        val v = when (state.getValue(FACING).axis) {
            Direction.Axis.Y -> (loc.z - pos.z).toFloat()
            else -> (loc.y - pos.y).toFloat()
        }
        return v.coerceIn(0f, 1f)
    }

    /** Horizontal position of the hit on the face, 0 (left) .. 1 (right) in
     *  face-local coords. Used by the BUTTON's shift-click split so the empty
     *  space left and right of the cap opens the GUI. */
    private fun faceU(state: BlockState, pos: BlockPos, hitResult: BlockHitResult): Float {
        val loc = hitResult.location
        val u = when (state.getValue(FACING).axis) {
            Direction.Axis.X -> (loc.z - pos.z).toFloat()
            else -> (loc.x - pos.x).toFloat()
        }
        return u.coerceIn(0f, 1f)
    }

    private fun playSound(level: Level, pos: BlockPos, sound: net.minecraft.sounds.SoundEvent, pitch: Float) {
        level.playSound(null, pos, sound, SoundSource.BLOCKS, 0.4f, pitch)
    }

    /** Slider step-click pitch, deeper at the low end of the range and higher
     *  at the top so the player hears the value travelling. Shared by the
     *  click-to-position path here and the drag stream in `Nodeworks.kt`. */
    private fun sliderClickPitch(entity: WidgetBlockEntity): Float =
        0.6f + entity.sliderValueFraction() * 1.0f

    override fun playerWillDestroy(level: Level, pos: BlockPos, state: BlockState, player: Player): BlockState {
        val entity = level.getBlockEntity(pos) as? WidgetBlockEntity
        entity?.blockDestroyed = true
        return super.playerWillDestroy(level, pos, state, player)
    }
}
