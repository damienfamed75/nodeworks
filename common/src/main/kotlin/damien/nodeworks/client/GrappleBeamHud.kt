package damien.nodeworks.client

import damien.nodeworks.item.GrappleBeamItem
import damien.nodeworks.script.ClientServerPolicy
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3

/**
 * Crosshair indicator for the Grapple Beam. While the player is holding the
 * tool, raycasts forward each frame up to [ClientServerPolicy.grappleMaxDistance]
 * and draws a small target reticle near the crosshair.
 *
 * Colours:
 *   green  -> solid block in range
 *   amber  -> entity in range and entity grapple is enabled
 *   grey   -> nothing in range
 *
 * Registered as a HUD layer from the loader-side client setup since the
 * `GuiLayer` interface lives in NeoForge.
 */
object GrappleBeamHud {

    /** Most recent raycast target. Read by callers that want to know
     *  whether a click will hit something and what kind of anchor. */
    @Volatile
    var lastTargetType: TargetType = TargetType.NONE
        private set

    enum class TargetType { NONE, BLOCK, ENTITY }

    private const val RETICLE_OFFSET_X = 9
    private const val RETICLE_OFFSET_Y = 9
    private const val RETICLE_SIZE = 3

    private const val COLOR_BLOCK = 0xFF55FF55.toInt()
    private const val COLOR_ENTITY = 0xFFFFAA33.toInt()
    private const val COLOR_NONE = 0x80AAAAAA.toInt()

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

        val color = when (hitType) {
            TargetType.BLOCK -> COLOR_BLOCK
            TargetType.ENTITY -> COLOR_ENTITY
            TargetType.NONE -> COLOR_NONE
        }

        val window = mc.window
        val cx = window.guiScaledWidth / 2 + RETICLE_OFFSET_X
        val cy = window.guiScaledHeight / 2 + RETICLE_OFFSET_Y
        graphics.fill(cx, cy, cx + RETICLE_SIZE, cy + RETICLE_SIZE, color)
    }
}
