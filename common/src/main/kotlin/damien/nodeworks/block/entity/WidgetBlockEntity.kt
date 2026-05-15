package damien.nodeworks.block.entity

import com.mojang.serialization.Codec
import damien.nodeworks.block.WidgetBlock
import damien.nodeworks.network.Connectable
import damien.nodeworks.network.NodeConnectionHelper
import damien.nodeworks.registry.ModBlockEntities
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.DyeColor
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
import damien.nodeworks.compat.getBlockPosList
import damien.nodeworks.compat.getStringOrNull
import damien.nodeworks.compat.putBlockPosList
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Backing entity for the [WidgetBlock]. A placed, GUI-configured control:
 * configure it as a button / switch / radio / slider, name it, and read its
 * value (or hook a callback) from a scripting terminal via `network:get`.
 *
 * The block is DIRECTIONAL, the front face (the BlockState's `FACING`) is the
 * widget surface, the other five faces network-connect like a Breaker.
 *
 * [interactionGeneration] bumps on every player interaction. The script
 * engine polls it (the same shape as redstone / observer onChange) so a
 * BUTTON press fires its callback even though it carries no persisted value.
 */
class WidgetBlockEntity(
    pos: BlockPos,
    state: BlockState,
) : BlockEntity(ModBlockEntities.WIDGET, pos, state), Connectable {

    private val connections = LinkedHashSet<BlockPos>()
    override var blockDestroyed: Boolean = false
    override var networkId: UUID? = null

    /** Custom alias for `network:get(name)`. Empty string keeps the widget
     *  off script lookups, mirroring how an unnamed Variable stays hidden. */
    var widgetName: String = ""
        set(value) {
            field = value.take(32)
            markDirtyAndSync()
        }

    var widgetType: WidgetType = WidgetType.BUTTON
        private set

    /** Current state. BUTTON keeps it at 0, SWITCH is 0/1, RADIO is the
     *  selected option index, SLIDER is a number in `[sliderMin, sliderMax]`
     *  snapped to `sliderStep`. */
    var value: Float = 0f
        private set

    /** Bumps on every interaction (button press, switch toggle, radio cycle,
     *  slider step). The script engine watches this to fire `:on` callbacks. */
    var interactionGeneration: Int = 0
        private set

    var sliderMin: Float = 0f
        private set
    var sliderMax: Float = 10f
        private set
    var sliderStep: Float = 1f
        private set

    var radioOptions: List<String> = listOf("A", "B", "C")
        private set

    /** BUTTON only, how many ticks the button stays "held" (`value == 1`)
     *  after a press before auto-releasing. 0 = a pure momentary press
     *  (value stays 0, only the interaction edge fires). */
    var buttonHoldTicks: Int = 0
        private set

    /** Game time the current button hold expires at. Transient, a hold does
     *  not survive a reload (the next [serverTick] releases a stale one). */
    @Transient
    private var heldUntilTick: Long = 0L

    var channel: DyeColor = DyeColor.WHITE
        set(value) {
            field = value
            markDirtyAndSync()
        }

    fun setType(type: WidgetType) {
        widgetType = type
        value = 0f
        markDirtyAndSync()
    }

    fun setButtonHold(ticks: Int) {
        buttonHoldTicks = ticks.coerceIn(0, MAX_HOLD_TICKS)
        markDirtyAndSync()
    }

    fun setSliderConfig(min: Float, max: Float, step: Float) {
        sliderMin = min
        sliderMax = maxOf(max, min)
        sliderStep = step.coerceAtLeast(0.0001f)
        value = sanitize(value)
        markDirtyAndSync()
    }

    fun setRadioOptions(options: List<String>) {
        radioOptions = options.ifEmpty { listOf("A") }
        value = sanitize(value)
        markDirtyAndSync()
    }

    /** Set the value directly (used by the GUI's default picker + load).
     *  Sanitized to the type's valid range. */
    fun setValue(raw: Float) {
        value = sanitize(raw)
        markDirtyAndSync()
    }

    // --- Interactions, called from the block's right-click handler ---

    /** BUTTON press. With [buttonHoldTicks] == 0 it is a pure momentary edge
     *  (value stays 0). With a positive hold it latches `value` to 1 and a
     *  later [serverTick] releases it, so a script sees both the press (1)
     *  and release (0) edges. */
    fun press() {
        if (widgetType == WidgetType.BUTTON && buttonHoldTicks > 0) {
            value = 1f
            heldUntilTick = (level?.gameTime ?: 0L) + buttonHoldTicks
        }
        interactionGeneration++
        markDirtyAndSync()
    }

    /** Server-side per-tick hook, releases a held BUTTON once its hold ends. */
    fun serverTick(level: ServerLevel) {
        if (widgetType != WidgetType.BUTTON || value < 0.5f) return
        if (level.gameTime >= heldUntilTick) {
            value = 0f
            interactionGeneration++
            markDirtyAndSync()
        }
    }

    /** SWITCH, flip 0 <-> 1. */
    fun toggle() {
        value = if (value >= 0.5f) 0f else 1f
        interactionGeneration++
        markDirtyAndSync()
    }

    /** RADIO, advance to the next option index, wrapping. */
    fun cycle() {
        val count = radioOptions.size
        if (count > 0) value = ((value.toInt() + 1) % count).toFloat()
        interactionGeneration++
        markDirtyAndSync()
    }

    /** RADIO, step back to the previous option index, wrapping. */
    fun cycleBack() {
        val count = radioOptions.size
        if (count > 0) value = ((value.toInt() - 1 + count) % count).toFloat()
        interactionGeneration++
        markDirtyAndSync()
    }

    /** SLIDER, snap [raw] to the configured step+bounds. Returns true when the
     *  value actually moved, so the block can play a per-step click sound. */
    fun setSliderValue(raw: Float): Boolean {
        val snapped = sanitizeSlider(raw)
        if (snapped == value) return false
        value = snapped
        interactionGeneration++
        markDirtyAndSync()
        return true
    }

    private fun sanitize(raw: Float): Float = when (widgetType) {
        WidgetType.BUTTON -> 0f
        WidgetType.SWITCH -> if (raw >= 0.5f) 1f else 0f
        WidgetType.RADIO -> raw.toInt().coerceIn(0, (radioOptions.size - 1).coerceAtLeast(0)).toFloat()
        WidgetType.SLIDER -> sanitizeSlider(raw)
    }

    private fun sanitizeSlider(raw: Float): Float {
        val clamped = raw.coerceIn(sliderMin, sliderMax)
        val steps = ((clamped - sliderMin) / sliderStep).roundToInt()
        return (sliderMin + steps * sliderStep).coerceIn(sliderMin, sliderMax)
    }

    private fun markDirtyAndSync() {
        setChanged()
        level?.sendBlockUpdated(worldPosition, blockState, blockState, Block.UPDATE_ALL)
    }

    // --- Connectable ---

    override fun getConnections(): Set<BlockPos> = connections

    override fun addConnection(pos: BlockPos): Boolean {
        if (!connections.add(pos)) return false
        markDirtyAndSync()
        return true
    }

    override fun removeConnection(pos: BlockPos): Boolean {
        if (!connections.remove(pos)) return false
        markDirtyAndSync()
        return true
    }

    override fun hasConnection(pos: BlockPos): Boolean = connections.contains(pos)

    /** Every face except the front (the widget surface) network-connects,
     *  mirroring the Breaker / Placer / User "front is the action face" rule. */
    override fun activeFaces(): Set<Direction> =
        java.util.EnumSet.complementOf(
            java.util.EnumSet.of(blockState.getValue(WidgetBlock.FACING))
        )

    // --- Lifecycle ---

    override fun setLevel(level: net.minecraft.world.level.Level) {
        super.setLevel(level)
        if (level is ServerLevel) {
            NodeConnectionHelper.trackNode(level, worldPosition)
            NodeConnectionHelper.queueRevalidation(level, worldPosition)
        }
        damien.nodeworks.render.NodeConnectionRenderer.trackConnectable(level, worldPosition, true)
    }

    override fun setRemoved() {
        damien.nodeworks.render.NodeConnectionRenderer.trackConnectable(level, worldPosition, false)
        val lvl = level
        if (lvl is ServerLevel) {
            NodeConnectionHelper.removeAllConnections(lvl, this)
            NodeConnectionHelper.untrackNode(lvl, worldPosition)
        }
        super.setRemoved()
    }

    // --- Serialization ---

    override fun saveAdditional(output: ValueOutput) {
        super.saveAdditional(output)
        output.putString("widgetName", widgetName)
        output.putInt("widgetType", widgetType.ordinal)
        output.putFloat("value", value)
        output.putInt("interactionGeneration", interactionGeneration)
        output.putFloat("sliderMin", sliderMin)
        output.putFloat("sliderMax", sliderMax)
        output.putFloat("sliderStep", sliderStep)
        output.putInt("channel", channel.id)
        output.putInt("buttonHoldTicks", buttonHoldTicks)
        if (radioOptions.isNotEmpty()) {
            val list = output.list("radioOptions", Codec.STRING)
            for (opt in radioOptions) list.add(opt)
        }
        networkId?.let { output.putString("networkId", it.toString()) }
        output.putBlockPosList("connections", connections)
    }

    override fun loadAdditional(input: ValueInput) {
        super.loadAdditional(input)
        widgetName = input.getStringOr("widgetName", "")
        widgetType = WidgetType.fromOrdinal(input.getIntOr("widgetType", 0))
        interactionGeneration = input.getIntOr("interactionGeneration", 0)
        sliderMin = input.getFloatOr("sliderMin", 0f)
        sliderMax = input.getFloatOr("sliderMax", 10f)
        sliderStep = input.getFloatOr("sliderStep", 1f).coerceAtLeast(0.0001f)
        channel = runCatching { DyeColor.byId(input.getIntOr("channel", 0)) }.getOrDefault(DyeColor.WHITE)
        buttonHoldTicks = input.getIntOr("buttonHoldTicks", 0).coerceIn(0, MAX_HOLD_TICKS)
        val opts = ArrayList<String>()
        for (s in input.listOrEmpty("radioOptions", Codec.STRING)) opts.add(s)
        radioOptions = opts.ifEmpty { listOf("A", "B", "C") }
        value = sanitize(input.getFloatOr("value", 0f))
        networkId = input.getStringOrNull("networkId")?.takeIf { it.isNotEmpty() }?.let {
            try { UUID.fromString(it) } catch (_: Exception) { null }
        }
        damien.nodeworks.network.NetworkSettingsRegistry.notifyConnectableChanged(networkId)
        connections.clear()
        connections.addAll(input.getBlockPosList("connections"))
    }

    override fun getUpdateTag(registries: HolderLookup.Provider): CompoundTag = saveWithoutMetadata(registries)
    override fun getUpdatePacket(): Packet<ClientGamePacketListener> = ClientboundBlockEntityDataPacket.create(this)

    companion object {
        /** Upper bound on a button's hold duration (10 s at 20 tps). */
        const val MAX_HOLD_TICKS = 200
    }
}
