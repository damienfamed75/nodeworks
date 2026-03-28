package damien.nodeworks.render

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import damien.nodeworks.block.entity.NodeBlockEntity
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.item.ItemStack
import org.joml.Quaternionf
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Renders item icons and counts on node monitor faces,
 * and beacon-style laser beams to connected nodes.
 */
class MonitorRenderer(context: BlockEntityRendererProvider.Context) : BlockEntityRenderer<NodeBlockEntity> {

    companion object {
        private val LASER_TEXTURE = ResourceLocation.fromNamespaceAndPath("nodeworks", "textures/block/laser_trail.png")

        /** Beam width in blocks. */
        var beamWidth = 1.0f / 16f

        /** UV scroll speed (units per second). */
        var beamScrollSpeed = 0.8f

        /** Rotation speed (radians per second). */
        var beamRotationSpeed = 1.0f
    }

    data class MonitorFace(
        val direction: Direction,
        val itemId: String?,
        val count: Long
    )

    data class BeamTarget(val dx: Float, val dy: Float, val dz: Float)

    override fun shouldRenderOffScreen(entity: NodeBlockEntity): Boolean = true

    override fun render(
        entity: NodeBlockEntity,
        partialTick: Float,
        poseStack: PoseStack,
        bufferSource: MultiBufferSource,
        packedLight: Int,
        packedOverlay: Int
    ) {
        val mc = Minecraft.getInstance()

        // Extract faces data directly from the entity
        val faces = entity.getMonitorFaces().map { face ->
            val monitor = entity.getMonitor(face)
            val itemId = monitor?.trackedItemId
            MonitorFace(face, itemId, monitor?.displayCount ?: 0L)
        }

        // Find network settings from the controller
        val controller = NodeConnectionRenderer.findController(entity.level, entity.blockPos)
        val networkColor = controller?.networkColor ?: NodeConnectionRenderer.DEFAULT_NETWORK_COLOR
        val nodeGlowStyle = controller?.nodeGlowStyle ?: 0

        // Collect beam connection targets (relative positions)
        val beamTargets = if (NodeConnectionRenderer.beamEffectEnabled) {
            val thisPos = entity.blockPos
            entity.getConnections().map { targetPos ->
                BeamTarget(
                    (targetPos.x - thisPos.x).toFloat(),
                    (targetPos.y - thisPos.y).toFloat(),
                    (targetPos.z - thisPos.z).toFloat()
                )
            }
        } else {
            emptyList()
        }

        // Render beacon-style laser beams to connected blocks
        if (beamTargets.isNotEmpty()) {
            renderBeams(beamTargets, poseStack, bufferSource, networkColor)
        }

        // Render glowing overlay cube (eyes render type for emissive effect)
        if (nodeGlowStyle != 5) { // 5 = NONE
            renderGlowingOverlay(poseStack, bufferSource, networkColor, nodeGlowStyle)
        }

        for (face in faces) {

            poseStack.pushPose()

            // Move to block center
            poseStack.translate(0.5, 0.5, 0.5)
            // Rotate so +Z points out of the face
            rotateToFace(poseStack, face.direction)
            // Push to the face surface (centered vertically)
            poseStack.translate(0.0, 0.0, 0.5)
            // Flip Z to face outward (toward the player)
            poseStack.scale(1f, 1f, -1f)

            // Render monitor as a thin 3D box matching the hitbox (12x12x2 pixels)
            run {
                val hw = 0.375f  // half-width (12/16 / 2)
                val hh = 0.375f  // half-height
                val depth = 0.125f // 2/16
                val overlay = OverlayTexture.NO_OVERLAY
                val light = 15728880

                val faceTex = RenderType.entityCutoutNoCull(
                    ResourceLocation.fromNamespaceAndPath("nodeworks", "textures/block/monitor_face.png")
                )
                val sideTex = RenderType.entityCutoutNoCull(
                    ResourceLocation.fromNamespaceAndPath("nodeworks", "textures/block/monitor_side.png")
                )
                val backTex = RenderType.entityCutoutNoCull(
                    ResourceLocation.fromNamespaceAndPath("nodeworks", "textures/block/monitor_back.png")
                )

                // Front face (screen)
                run {
                    val vc = bufferSource.getBuffer(faceTex)
                    val pose = poseStack.last()
                    vc.addVertex(pose,  hw, -hh, 0f).setUv(1f, 0f).setColor(255, 255, 255, 255).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
                    vc.addVertex(pose,  hw,  hh, 0f).setUv(1f, 1f).setColor(255, 255, 255, 255).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
                    vc.addVertex(pose, -hw,  hh, 0f).setUv(0f, 1f).setColor(255, 255, 255, 255).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
                    vc.addVertex(pose, -hw, -hh, 0f).setUv(0f, 0f).setColor(255, 255, 255, 255).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
                }
                // Back face
                run {
                    val vc = bufferSource.getBuffer(backTex)
                    val pose = poseStack.last()
                    vc.addVertex(pose, -hw, -hh, depth).setUv(0f, 0f).setColor(255, 255, 255, 255).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
                    vc.addVertex(pose, -hw,  hh, depth).setUv(0f, 1f).setColor(255, 255, 255, 255).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
                    vc.addVertex(pose,  hw,  hh, depth).setUv(1f, 1f).setColor(255, 255, 255, 255).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
                    vc.addVertex(pose,  hw, -hh, depth).setUv(1f, 0f).setColor(255, 255, 255, 255).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
                }
                // Top, bottom, left, right faces (sides)
                run {
                    val vc = bufferSource.getBuffer(sideTex)
                    val pose = poseStack.last()
                    // Top
                    vc.addVertex(pose,  hw, hh, 0f).setUv(1f, 0f).setColor(255, 255, 255, 255).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
                    vc.addVertex(pose,  hw, hh, depth).setUv(1f, 0.125f).setColor(255, 255, 255, 255).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
                    vc.addVertex(pose, -hw, hh, depth).setUv(0f, 0.125f).setColor(255, 255, 255, 255).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
                    vc.addVertex(pose, -hw, hh, 0f).setUv(0f, 0f).setColor(255, 255, 255, 255).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
                    // Bottom
                    vc.addVertex(pose, -hw, -hh, 0f).setUv(0f, 0f).setColor(255, 255, 255, 255).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
                    vc.addVertex(pose, -hw, -hh, depth).setUv(0f, 0.125f).setColor(255, 255, 255, 255).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
                    vc.addVertex(pose,  hw, -hh, depth).setUv(1f, 0.125f).setColor(255, 255, 255, 255).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
                    vc.addVertex(pose,  hw, -hh, 0f).setUv(1f, 0f).setColor(255, 255, 255, 255).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
                    // Right
                    vc.addVertex(pose, hw, -hh, 0f).setUv(0f, 0f).setColor(255, 255, 255, 255).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
                    vc.addVertex(pose, hw, -hh, depth).setUv(0.125f, 0f).setColor(255, 255, 255, 255).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
                    vc.addVertex(pose, hw,  hh, depth).setUv(0.125f, 1f).setColor(255, 255, 255, 255).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
                    vc.addVertex(pose, hw,  hh, 0f).setUv(0f, 1f).setColor(255, 255, 255, 255).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
                    // Left
                    vc.addVertex(pose, -hw,  hh, 0f).setUv(0f, 1f).setColor(255, 255, 255, 255).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
                    vc.addVertex(pose, -hw,  hh, depth).setUv(0.125f, 1f).setColor(255, 255, 255, 255).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
                    vc.addVertex(pose, -hw, -hh, depth).setUv(0.125f, 0f).setColor(255, 255, 255, 255).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
                    vc.addVertex(pose, -hw, -hh, 0f).setUv(0f, 0f).setColor(255, 255, 255, 255).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
                }
            }

            // Render item icon if set — push in front of the panel to avoid z-fighting
            if (face.itemId != null) {
                val identifier = ResourceLocation.tryParse(face.itemId)
                val item = if (identifier != null) BuiltInRegistries.ITEM.get(identifier) else null
                if (item != null) {
                    val itemStack = ItemStack(item, 1)
                    poseStack.pushPose()
                    poseStack.translate(0.0, 0.02, -0.02)
                    poseStack.scale(0.3f, 0.3f, 0.001f)
                    mc.itemRenderer.renderStatic(
                        itemStack,
                        ItemDisplayContext.GUI,
                        0xF000F0,
                        OverlayTexture.NO_OVERLAY,
                        poseStack,
                        bufferSource,
                        entity.level,
                        0
                    )
                    poseStack.popPose()
                }
            }

            poseStack.popPose()
        }
    }

    // --- Glowing overlay ---

    private val GLOW_TEXTURES = arrayOf(
        ResourceLocation.fromNamespaceAndPath("nodeworks", "textures/block/node_glow_square.png"),
        ResourceLocation.fromNamespaceAndPath("nodeworks", "textures/block/node_glow_circle.png"),
        ResourceLocation.fromNamespaceAndPath("nodeworks", "textures/block/node_glow_dot.png"),
        ResourceLocation.fromNamespaceAndPath("nodeworks", "textures/block/node_glow_creeper.png"),
        ResourceLocation.fromNamespaceAndPath("nodeworks", "textures/block/node_glow_spiral.png")
    )

    private fun renderGlowingOverlay(poseStack: PoseStack, bufferSource: MultiBufferSource, networkColor: Int, glowStyle: Int = 0) {
        val color = networkColor
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF

        val texIndex = glowStyle.coerceIn(0, GLOW_TEXTURES.size - 1)
        val eyesType = RenderType.entityTranslucentEmissive(GLOW_TEXTURES[texIndex])
        val light = 15728880
        val overlay = OverlayTexture.NO_OVERLAY

        // Overlay cube just outside the 4x4x4 center core (pixels 6-10)
        val min = 5.9f / 16f
        val max = 10.1f / 16f

        val vc = bufferSource.getBuffer(eyesType)
        val pose = poseStack.last()

        // South face (+Z)
        vc.addVertex(pose, max, min, max).setUv(1f, 1f).setColor(r, g, b, 255).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 0f, 1f)
        vc.addVertex(pose, max, max, max).setUv(1f, 0f).setColor(r, g, b, 255).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 0f, 1f)
        vc.addVertex(pose, min, max, max).setUv(0f, 0f).setColor(r, g, b, 255).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 0f, 1f)
        vc.addVertex(pose, min, min, max).setUv(0f, 1f).setColor(r, g, b, 255).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 0f, 1f)

        // North face (-Z)
        vc.addVertex(pose, min, min, min).setUv(1f, 1f).setColor(r, g, b, 255).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 0f, 1f)
        vc.addVertex(pose, min, max, min).setUv(1f, 0f).setColor(r, g, b, 255).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 0f, 1f)
        vc.addVertex(pose, max, max, min).setUv(0f, 0f).setColor(r, g, b, 255).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 0f, 1f)
        vc.addVertex(pose, max, min, min).setUv(0f, 1f).setColor(r, g, b, 255).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 0f, 1f)

        // East face (+X)
        vc.addVertex(pose, max, min, min).setUv(1f, 1f).setColor(r, g, b, 255).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 0f, 1f)
        vc.addVertex(pose, max, max, min).setUv(1f, 0f).setColor(r, g, b, 255).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 0f, 1f)
        vc.addVertex(pose, max, max, max).setUv(0f, 0f).setColor(r, g, b, 255).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 0f, 1f)
        vc.addVertex(pose, max, min, max).setUv(0f, 1f).setColor(r, g, b, 255).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 0f, 1f)

        // West face (-X)
        vc.addVertex(pose, min, min, max).setUv(1f, 1f).setColor(r, g, b, 255).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 0f, 1f)
        vc.addVertex(pose, min, max, max).setUv(1f, 0f).setColor(r, g, b, 255).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 0f, 1f)
        vc.addVertex(pose, min, max, min).setUv(0f, 0f).setColor(r, g, b, 255).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 0f, 1f)
        vc.addVertex(pose, min, min, min).setUv(0f, 1f).setColor(r, g, b, 255).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 0f, 1f)

        // Up face (+Y)
        vc.addVertex(pose, min, max, max).setUv(0f, 1f).setColor(r, g, b, 255).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 0f, 1f)
        vc.addVertex(pose, max, max, max).setUv(1f, 1f).setColor(r, g, b, 255).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 0f, 1f)
        vc.addVertex(pose, max, max, min).setUv(1f, 0f).setColor(r, g, b, 255).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 0f, 1f)
        vc.addVertex(pose, min, max, min).setUv(0f, 0f).setColor(r, g, b, 255).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 0f, 1f)

        // Down face (-Y)
        vc.addVertex(pose, min, min, min).setUv(0f, 0f).setColor(r, g, b, 255).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 0f, 1f)
        vc.addVertex(pose, max, min, min).setUv(1f, 0f).setColor(r, g, b, 255).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 0f, 1f)
        vc.addVertex(pose, max, min, max).setUv(1f, 1f).setColor(r, g, b, 255).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 0f, 1f)
        vc.addVertex(pose, min, min, max).setUv(0f, 1f).setColor(r, g, b, 255).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 0f, 1f)
    }

    // --- Beam rendering ---

    private fun renderBeams(targets: List<BeamTarget>, poseStack: PoseStack, bufferSource: MultiBufferSource, networkColor: Int) {
        val time = (System.currentTimeMillis() % 100000) / 1000f
        val color = networkColor
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF

        // Opaque core beam
        val opaqueType = RenderType.beaconBeam(LASER_TEXTURE, false)
        // Translucent glow
        val translucentType = RenderType.beaconBeam(LASER_TEXTURE, true)

        for (target in targets) {
            renderSingleBeam(poseStack, bufferSource, opaqueType, target, time, 255, 255, 255, 255, beamWidth, 0f)
            renderSingleBeam(poseStack, bufferSource, translucentType, target, time, r, g, b, 120, beamWidth * 3.5f, Math.PI.toFloat() / 4f)
        }
    }

    private fun renderSingleBeam(
        poseStack: PoseStack,
        bufferSource: MultiBufferSource,
        renderType: RenderType,
        target: BeamTarget,
        time: Float,
        r: Int, g: Int, b: Int, a: Int,
        width: Float,
        angleOffset: Float
    ) {
        // Beam from block center (0.5, 0.5, 0.5) to target center
        val fromX = 0.5f; val fromY = 0.5f; val fromZ = 0.5f
        val toX = target.dx + 0.5f; val toY = target.dy + 0.5f; val toZ = target.dz + 0.5f

        val dx = toX - fromX
        val dy = toY - fromY
        val dz = toZ - fromZ
        val len = sqrt(dx * dx + dy * dy + dz * dz)
        if (len < 0.01f) return

        // Normalized beam direction
        val dirX = dx / len; val dirY = dy / len; val dirZ = dz / len

        // Find two perpendicular axes
        val refX: Float; val refY: Float; val refZ: Float
        if (abs(dirY) < 0.9f) {
            refX = 0f; refY = 1f; refZ = 0f
        } else {
            refX = 1f; refY = 0f; refZ = 0f
        }

        // axis1 = cross(dir, ref)
        var a1x = dirY * refZ - dirZ * refY
        var a1y = dirZ * refX - dirX * refZ
        var a1z = dirX * refY - dirY * refX
        val a1len = sqrt(a1x * a1x + a1y * a1y + a1z * a1z)
        a1x /= a1len; a1y /= a1len; a1z /= a1len

        // axis2 = cross(dir, axis1)
        var a2x = dirY * a1z - dirZ * a1y
        var a2y = dirZ * a1x - dirX * a1z
        var a2z = dirX * a1y - dirY * a1x
        val a2len = sqrt(a2x * a2x + a2y * a2y + a2z * a2z)
        a2x /= a2len; a2y /= a2len; a2z /= a2len

        // Rotate axes around beam direction for animation
        val angle = time * beamRotationSpeed + angleOffset
        val cosA = cos(angle); val sinA = sin(angle)
        val r1x = a1x * cosA + a2x * sinA
        val r1y = a1y * cosA + a2y * sinA
        val r1z = a1z * cosA + a2z * sinA
        val r2x = -a1x * sinA + a2x * cosA
        val r2y = -a1y * sinA + a2y * cosA
        val r2z = -a1z * sinA + a2z * cosA

        val hw = width / 2f

        // UV mapping: beam occupies first 5px of 16px wide texture
        val uMax = 5f / 16f
        val uvScroll = time * beamScrollSpeed
        val v0 = uvScroll
        val v1 = uvScroll + len * 0.5f  // less tiling for a less squished look

        val light = 15728880
        val overlay = OverlayTexture.NO_OVERLAY

        val vc = bufferSource.getBuffer(renderType)
        val pose = poseStack.last()

        // Quad 1 front: along axis1
        vc.addVertex(pose, fromX - r1x * hw, fromY - r1y * hw, fromZ - r1z * hw)
            .setUv(0f, v0).setColor(r, g, b, a).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
        vc.addVertex(pose, fromX + r1x * hw, fromY + r1y * hw, fromZ + r1z * hw)
            .setUv(uMax, v0).setColor(r, g, b, a).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
        vc.addVertex(pose, toX + r1x * hw, toY + r1y * hw, toZ + r1z * hw)
            .setUv(uMax, v1).setColor(r, g, b, a).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
        vc.addVertex(pose, toX - r1x * hw, toY - r1y * hw, toZ - r1z * hw)
            .setUv(0f, v1).setColor(r, g, b, a).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)

        // Quad 1 back: reversed winding
        vc.addVertex(pose, fromX + r1x * hw, fromY + r1y * hw, fromZ + r1z * hw)
            .setUv(uMax, v0).setColor(r, g, b, a).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
        vc.addVertex(pose, fromX - r1x * hw, fromY - r1y * hw, fromZ - r1z * hw)
            .setUv(0f, v0).setColor(r, g, b, a).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
        vc.addVertex(pose, toX - r1x * hw, toY - r1y * hw, toZ - r1z * hw)
            .setUv(0f, v1).setColor(r, g, b, a).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
        vc.addVertex(pose, toX + r1x * hw, toY + r1y * hw, toZ + r1z * hw)
            .setUv(uMax, v1).setColor(r, g, b, a).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)

        // Quad 2 front: along axis2
        vc.addVertex(pose, fromX - r2x * hw, fromY - r2y * hw, fromZ - r2z * hw)
            .setUv(0f, v0).setColor(r, g, b, a).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
        vc.addVertex(pose, fromX + r2x * hw, fromY + r2y * hw, fromZ + r2z * hw)
            .setUv(uMax, v0).setColor(r, g, b, a).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
        vc.addVertex(pose, toX + r2x * hw, toY + r2y * hw, toZ + r2z * hw)
            .setUv(uMax, v1).setColor(r, g, b, a).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
        vc.addVertex(pose, toX - r2x * hw, toY - r2y * hw, toZ - r2z * hw)
            .setUv(0f, v1).setColor(r, g, b, a).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)

        // Quad 2 back: reversed winding
        vc.addVertex(pose, fromX + r2x * hw, fromY + r2y * hw, fromZ + r2z * hw)
            .setUv(uMax, v0).setColor(r, g, b, a).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
        vc.addVertex(pose, fromX - r2x * hw, fromY - r2y * hw, fromZ - r2z * hw)
            .setUv(0f, v0).setColor(r, g, b, a).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
        vc.addVertex(pose, toX - r2x * hw, toY - r2y * hw, toZ - r2z * hw)
            .setUv(0f, v1).setColor(r, g, b, a).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
        vc.addVertex(pose, toX + r2x * hw, toY + r2y * hw, toZ + r2z * hw)
            .setUv(uMax, v1).setColor(r, g, b, a).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
    }

    // --- Monitor helpers ---

    private fun rotateToFace(poseStack: PoseStack, face: Direction) {
        when (face) {
            Direction.SOUTH -> {}
            Direction.NORTH -> poseStack.mulPose(Quaternionf().rotateY(Math.PI.toFloat()))
            Direction.EAST  -> poseStack.mulPose(Quaternionf().rotateY((Math.PI / 2).toFloat()))
            Direction.WEST  -> poseStack.mulPose(Quaternionf().rotateY((-Math.PI / 2).toFloat()))
            Direction.DOWN  -> poseStack.mulPose(Quaternionf().rotateX((-Math.PI / 2).toFloat()))
            Direction.UP    -> poseStack.mulPose(Quaternionf().rotateX((Math.PI / 2).toFloat()))
        }
    }

    private fun formatCount(count: Long): String {
        return when {
            count >= 1_000_000 -> String.format("%.1fM", count / 1_000_000.0)
            count >= 1_000 -> String.format("%.1fK", count / 1_000.0)
            else -> count.toString()
        }
    }
}
