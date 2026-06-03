package damien.nodeworks.client

import damien.nodeworks.item.GrappleBeamItem
import damien.nodeworks.screen.Icons
import damien.nodeworks.script.ClientServerPolicy
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3

/**
 * Crosshair indicator for the Grapple Beam. While the player is holding
 * the tool, raycasts forward each frame and draws a small diamond
 * reticle next to the crosshair. Colors:
 *  - green: block in range
 *  - blue:  entity in range (entity grapple enabled)
 *  - gray:  nothing in range
 *
 * Registered as a HUD layer from the loader-side client setup; the
 * `GuiLayer` interface lives in NeoForge.
 */
object GrappleBeamHud {

    /** Most recent raycast target. Read by callers that want to know
     *  whether a click will hit something and what kind of anchor. */
    @Volatile
    var lastTargetType: TargetType = TargetType.NONE
        private set

    enum class TargetType { NONE, BLOCK, ENTITY }

    private const val RETICLE_OFFSET_X = 5
    private const val RETICLE_OFFSET_Y = 5
    private const val RETICLE_SIZE = 4

    private const val COLOR_BLOCK_RGB = 0x73E1A7
    private const val COLOR_ENTITY_RGB = 0x73B0E1
    private const val COLOR_NONE_RGB = 0xAAAAAA
    private const val ALPHA_NONE = 0.5f

    fun render(graphics: GuiGraphicsExtractor, delta: DeltaTracker) {
        val mc = Minecraft.getInstance()
        if (mc.options.hideGui) return
        val player = mc.player ?: return
        val holdingMain = player.mainHandItem.item is GrappleBeamItem
        val holdingOff = player.offhandItem.item is GrappleBeamItem
        if (!holdingMain && !holdingOff) {
            lastTargetType = TargetType.NONE
            return
        }

        val maxDistance = ClientServerPolicy.grappleMaxDistance.toDouble()
        val allowEntities = ClientServerPolicy.grappleEntities
        val partial = delta.getGameTimeDeltaPartialTick(true)

        val eye = player.getEyePosition(partial)
        val look = player.getViewVector(partial)
        val end = eye.add(look.x * maxDistance, look.y * maxDistance, look.z * maxDistance)

        val blockHit = player.pick(maxDistance, partial, false)
        val blockEnd: Vec3 = if (blockHit.type == HitResult.Type.BLOCK) blockHit.location else end
        val blockDistSq = eye.distanceToSqr(blockEnd)

        var hitType = if (blockHit.type == HitResult.Type.BLOCK) TargetType.BLOCK else TargetType.NONE

        if (allowEntities) {
            val scanBox = AABB(eye, blockEnd).inflate(0.5)
            val candidates = player.level().getEntities(player, scanBox) { e ->
                e is LivingEntity && e.isPickable && e !== player
            }
            var closestSq = blockDistSq
            var hitEntity = false
            for (e in candidates) {
                val box = e.boundingBox.inflate(e.pickRadius.toDouble())
                val clip = box.clip(eye, blockEnd).orElse(null) ?: continue
                val dsq = eye.distanceToSqr(clip)
                if (dsq < closestSq) {
                    closestSq = dsq
                    hitEntity = true
                }
            }
            if (hitEntity) hitType = TargetType.ENTITY
        }

        lastTargetType = hitType

        val rgb: Int
        val alpha: Float
        when (hitType) {
            TargetType.BLOCK -> {
                rgb = COLOR_BLOCK_RGB; alpha = 1f
            }

            TargetType.ENTITY -> {
                rgb = COLOR_ENTITY_RGB; alpha = 1f
            }

            TargetType.NONE -> {
                rgb = COLOR_NONE_RGB; alpha = ALPHA_NONE
            }
        }

        val window = mc.window
        val cx = window.guiScaledWidth / 2 + RETICLE_OFFSET_X
        val cy = window.guiScaledHeight / 2 + RETICLE_OFFSET_Y

        // Rotate 45 degrees around the icon center to read as a diamond.
        val half = RETICLE_SIZE / 2f
        val pose = graphics.pose()
        pose.pushMatrix()
        pose.translate(cx + half, cy + half)
        pose.rotate((Math.PI / 4.0).toFloat())
        pose.translate(-half, -half)
        Icons.GRAPPLE_RETICLE.drawTopLeftTinted(graphics, 0, 0, RETICLE_SIZE, RETICLE_SIZE, rgb, alpha)
        pose.popMatrix()
    }
}
