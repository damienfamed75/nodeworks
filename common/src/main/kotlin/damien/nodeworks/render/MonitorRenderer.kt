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
    }

    data class MonitorFace(
        val direction: Direction,
        val itemId: String?,
        val count: Long
    )

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

        // Find network settings via registry, fallback to BFS if not registered yet
        val settings = damien.nodeworks.network.NetworkSettingsRegistry.get(entity.networkId)
        val networkColor: Int
        val nodeGlowStyle: Int
        if (entity.networkId != null && settings.color != NodeConnectionRenderer.DEFAULT_NETWORK_COLOR) {
            networkColor = settings.color
            nodeGlowStyle = settings.glowStyle
        } else {
            val controller = NodeConnectionRenderer.findController(entity.level, entity.blockPos)
            networkColor = controller?.networkColor ?: NodeConnectionRenderer.DEFAULT_NETWORK_COLOR
            nodeGlowStyle = controller?.nodeGlowStyle ?: 0
        }

        // Beams are now rendered by NodeConnectionRenderer (world render event, no frustum culling)

        // Render glowing overlay cube (eyes render type for emissive effect)
        if (nodeGlowStyle != 5) { // 5 = NONE
            renderGlowingOverlay(poseStack, bufferSource, networkColor, nodeGlowStyle)
        }

        // Render card link lines to adjacent blocks
        renderCardLinks(entity, poseStack, bufferSource)

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

    private val cardColors = mapOf(
        "io" to Triple(0x83, 0xE0, 0x86),       // green
        "storage" to Triple(0xAA, 0x83, 0xE0),   // purple
        "redstone" to Triple(0xF5, 0x3B, 0x68)   // red
    )

    /** Fixed 3x3 grid offsets for the 9 card slots, centered around 0. */
    private val slotOffsets: Array<Pair<Float, Float>> = run {
        val spacing = 1f / 16f
        Array(9) { i ->
            val col = 1 - i % 3  // 1, 0, -1 (left to right when facing the node)
            val row = 1 - i / 3  // 1, 0, -1 (top to bottom)
            Pair(col * spacing, row * spacing)
        }
    }

    private fun renderCardLinks(entity: NodeBlockEntity, poseStack: PoseStack, bufferSource: MultiBufferSource) {
        val level = entity.level ?: return
        val vc = bufferSource.getBuffer(RenderType.beaconBeam(LASER_TEXTURE, true))
        val pose = poseStack.last()
        val hw = 0.3f / 16f

        // Camera direction for billboarding
        val cam = Minecraft.getInstance().gameRenderer.mainCamera
        val camPos = cam.position
        val blockX = entity.blockPos.x.toFloat()
        val blockY = entity.blockPos.y.toFloat()
        val blockZ = entity.blockPos.z.toFloat()

        for (side in Direction.entries) {
            val cards = entity.getCards(side)
            if (cards.isEmpty()) continue

            val adjacentPos = entity.blockPos.relative(side)
            if (level.getBlockState(adjacentPos).isAir) continue

            // Beam direction (unit vector along the face)
            val bx = side.stepX.toFloat()
            val by = side.stepY.toFloat()
            val bz = side.stepZ.toFloat()

            for (card in cards) {
                val (r, g, b) = cardColors[card.card.cardType] ?: continue
                val (offA, offB) = slotOffsets[card.slotIndex]

                // Compute endpoints: node center → block face
                val ox: Float; val oy: Float; val oz: Float
                val fx: Float; val fy: Float; val fz: Float

                when (side) {
                    Direction.NORTH -> { ox = 0.5f + offA; oy = 0.5f + offB; oz = 0.5f; fx = ox; fy = oy; fz = 0f }
                    Direction.SOUTH -> { ox = 0.5f - offA; oy = 0.5f + offB; oz = 0.5f; fx = ox; fy = oy; fz = 1f }
                    Direction.WEST  -> { ox = 0.5f; oy = 0.5f + offB; oz = 0.5f - offA; fx = 0f; fy = oy; fz = oz }
                    Direction.EAST  -> { ox = 0.5f; oy = 0.5f + offB; oz = 0.5f + offA; fx = 1f; fy = oy; fz = oz }
                    Direction.DOWN  -> { ox = 0.5f + offA; oy = 0.5f; oz = 0.5f + offB; fx = ox; fy = 0f; fz = oz }
                    Direction.UP    -> { ox = 0.5f + offA; oy = 0.5f; oz = 0.5f + offB; fx = ox; fy = 1f; fz = oz }
                }

                // Billboard: compute perpendicular to both beam direction and camera-to-beam vector
                val midX = (ox + fx) / 2f + blockX
                val midY = (oy + fy) / 2f + blockY
                val midZ = (oz + fz) / 2f + blockZ
                val toCamX = (camPos.x - midX).toFloat()
                val toCamY = (camPos.y - midY).toFloat()
                val toCamZ = (camPos.z - midZ).toFloat()

                // cross(beamDir, toCam) = perpendicular vector
                var px = by * toCamZ - bz * toCamY
                var py = bz * toCamX - bx * toCamZ
                var pz = bx * toCamY - by * toCamX
                val plen = sqrt(px * px + py * py + pz * pz)
                if (plen < 0.001f) continue
                px = px / plen * hw; py = py / plen * hw; pz = pz / plen * hw

                val a = 180
                vc.addVertex(pose, ox - px, oy - py, oz - pz).setUv(0f, 0f).setColor(r, g, b, a).setOverlay(OverlayTexture.NO_OVERLAY).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
                vc.addVertex(pose, ox + px, oy + py, oz + pz).setUv(0.3f, 0f).setColor(r, g, b, a).setOverlay(OverlayTexture.NO_OVERLAY).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
                vc.addVertex(pose, fx + px, fy + py, fz + pz).setUv(0.3f, 1f).setColor(r, g, b, a).setOverlay(OverlayTexture.NO_OVERLAY).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
                vc.addVertex(pose, fx - px, fy - py, fz - pz).setUv(0f, 1f).setColor(r, g, b, a).setOverlay(OverlayTexture.NO_OVERLAY).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
            }
        }
    }
}
