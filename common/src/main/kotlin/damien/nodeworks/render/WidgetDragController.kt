package damien.nodeworks.render

import damien.nodeworks.block.WidgetBlock
import damien.nodeworks.network.DeviceSettingsPayload
import damien.nodeworks.platform.PlatformServices
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.phys.BlockHitResult
import kotlin.math.abs

/**
 * Client-side slider drag tracker. Minecraft has no "drag on a block" hook, so
 * a plain right-click on a Slider widget enters drag mode: while the use key
 * stays held and the crosshair stays on that widget, this polls the crosshair
 * each client tick and streams the slider value to the server.
 *
 * Releasing the use key ends the drag. The coarse 4-tick `useWithoutItem`
 * re-fire vanilla already does keeps single clicks working, this just makes
 * holding-and-moving smooth (and produces the per-step click sound server-side
 * via the `slider_drag` payload key).
 */
object WidgetDragController {

    private var dragPos: BlockPos? = null
    private var dragFacing: Direction? = null
    /** Last fraction sent to the server, NaN before the first send. */
    private var lastSentFrac: Float = Float.NaN

    /** Wires the per-tick poll. Called once from client setup. */
    fun init() {
        PlatformServices.clientEvents.onClientTick { tick() }
    }

    /** Begin (or restart) a drag on the slider at [pos]. Called client-side
     *  from [WidgetBlock.useWithoutItem] when a slider is right-clicked. */
    fun beginDrag(pos: BlockPos, facing: Direction) {
        dragPos = pos
        dragFacing = facing
        lastSentFrac = Float.NaN
    }

    private fun tick() {
        val pos = dragPos ?: return
        val facing = dragFacing ?: return
        val mc = Minecraft.getInstance()
        // The drag lives only as long as the use key is held.
        if (!mc.options.keyUse.isDown) {
            dragPos = null
            dragFacing = null
            return
        }
        // Update only while the crosshair is still on the dragged widget, so
        // looking away pauses rather than snapping the value.
        val hit = mc.hitResult as? BlockHitResult ?: return
        if (hit.blockPos != pos) return

        val frac = WidgetBlock.sliderFractionFor(facing, pos, hit.location)
        // Skip sends when the value hasn't meaningfully moved, keeps the
        // per-tick packet rate down to actual changes.
        if (!lastSentFrac.isNaN() && abs(frac - lastSentFrac) < 0.002f) return
        lastSentFrac = frac
        PlatformServices.clientNetworking.sendToServer(
            DeviceSettingsPayload(pos, "slider_drag", 0, frac.toString())
        )
    }
}
