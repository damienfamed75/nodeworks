package damien.nodeworks.render

import com.mojang.blaze3d.vertex.PoseStack
import damien.nodeworks.block.entity.NodeBlockEntity
import damien.nodeworks.network.NetworkSettingsRegistry
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.renderer.feature.ModelFeatureRenderer
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.core.Direction
import net.minecraft.resources.Identifier
import net.minecraft.world.phys.Vec3
import kotlin.math.sqrt

/**
 * Renders the glowing emissive overlay on the outside of each Node's central core
 * (tinted to the network colour) plus the per-card-slot laser beams that connect a
 * node face to its adjacent block.
 *
 * The monitor-face rendering previously lived here, but the Monitor is now its own
 * standalone block with its own BER ([MonitorRenderer]) — this class is purely a
 * Node renderer.
 */
open class NodeRenderer(context: BlockEntityRendererProvider.Context) :
    ConnectableBER<NodeBlockEntity, NodeRenderer.NodeRenderState>(context) {

    /** One laser beam from a card slot on a node face to the adjacent block. Extracted
     *  on the main thread so `submit` can emit the geometry without touching the BE. */
    data class CardLink(
        val side: Direction,
        val slotIndex: Int,
        val r: Int, val g: Int, val b: Int
    )

    class NodeRenderState : ConnectableRenderState() {
        var networkColor: Int = NodeConnectionRenderer.DEFAULT_NETWORK_COLOR
        var glowStyle: Int = 0
        var hasGlow: Boolean = false
        var cardLinks: List<CardLink> = emptyList()
    }

    companion object {
        private val GLOW_TEXTURES = arrayOf(
            Identifier.fromNamespaceAndPath("nodeworks", "textures/block/node_glow_square.png"),
            Identifier.fromNamespaceAndPath("nodeworks", "textures/block/node_glow_circle.png"),
            Identifier.fromNamespaceAndPath("nodeworks", "textures/block/node_glow_dot.png"),
            Identifier.fromNamespaceAndPath("nodeworks", "textures/block/node_glow_creeper.png"),
            Identifier.fromNamespaceAndPath("nodeworks", "textures/block/node_glow_spiral.png")
        )
        // glowStyle 5 = NONE in the controller GUI — skip rendering
        private const val GLOW_STYLE_NONE = 5

        private val LASER_TEXTURE = Identifier.fromNamespaceAndPath("nodeworks", "textures/block/laser_trail.png")

        /** Per-card-type beam colour (r, g, b 0–255). */
        private val CARD_COLORS = mapOf(
            "io"       to Triple(0x83, 0xE0, 0x86), // green
            "storage"  to Triple(0xAA, 0x83, 0xE0), // purple
            "redstone" to Triple(0xF5, 0x3B, 0x68)  // red
        )

        /** Fixed 3×3 grid offsets for the 9 card slots on a node face, centered around 0. */
        private val SLOT_OFFSETS: Array<Pair<Float, Float>> = run {
            val spacing = 1f / 16f
            Array(9) { i ->
                val col = 1 - i % 3
                val row = 1 - i / 3
                Pair(col * spacing, row * spacing)
            }
        }
    }

    override fun createRenderState(): NodeRenderState = NodeRenderState()

    override fun extractConnectable(
        blockEntity: NodeBlockEntity,
        state: NodeRenderState,
        partialTicks: Float,
        cameraPosition: Vec3,
        breakProgress: ModelFeatureRenderer.CrumblingOverlay?,
    ) {
        val settings = NetworkSettingsRegistry.get(blockEntity.networkId)
        state.networkColor = settings.color
        state.glowStyle = settings.glowStyle
        state.hasGlow = settings.glowStyle != GLOW_STYLE_NONE

        // Card-link beams: one per card on each face whose adjacent block isn't air.
        val level = blockEntity.level
        if (level != null) {
            val links = mutableListOf<CardLink>()
            for (side in Direction.entries) {
                val adjacentPos = blockEntity.blockPos.relative(side)
                if (level.getBlockState(adjacentPos).isAir) continue
                for (card in blockEntity.getCards(side)) {
                    val (r, g, b) = CARD_COLORS[card.card.cardType] ?: continue
                    links.add(CardLink(side, card.slotIndex, r, g, b))
                }
            }
            state.cardLinks = links
        } else {
            state.cardLinks = emptyList()
        }
    }

    override fun submitConnectable(
        state: NodeRenderState,
        poseStack: PoseStack,
        submitNodeCollector: SubmitNodeCollector,
        camera: CameraRenderState,
    ) {
        submitCardLinks(state, poseStack, submitNodeCollector, camera)

        if (!state.hasGlow) return

        val texIndex = state.glowStyle.coerceIn(0, GLOW_TEXTURES.size - 1)
        val renderType = RenderTypes.entityTranslucentEmissive(GLOW_TEXTURES[texIndex])
        val r = (state.networkColor shr 16) and 0xFF
        val g = (state.networkColor shr 8) and 0xFF
        val b = state.networkColor and 0xFF

        // Overlay cube just outside the 4x4x4 center core (pixels 6–10).
        val min = 5.9f / 16f
        val max = 10.1f / 16f
        val overlay = OverlayTexture.NO_OVERLAY
        val light = 15728880

        submitNodeCollector.submitCustomGeometry(poseStack, renderType) { pose, vc ->
            // +Z
            vc.addVertex(pose, max, min, max).setUv(1f, 1f).setColor(r, g, b, 255).setOverlay(overlay).setUv2(light, light).setNormal(pose, 0f, 0f, 1f)
            vc.addVertex(pose, max, max, max).setUv(1f, 0f).setColor(r, g, b, 255).setOverlay(overlay).setUv2(light, light).setNormal(pose, 0f, 0f, 1f)
            vc.addVertex(pose, min, max, max).setUv(0f, 0f).setColor(r, g, b, 255).setOverlay(overlay).setUv2(light, light).setNormal(pose, 0f, 0f, 1f)
            vc.addVertex(pose, min, min, max).setUv(0f, 1f).setColor(r, g, b, 255).setOverlay(overlay).setUv2(light, light).setNormal(pose, 0f, 0f, 1f)
            // -Z
            vc.addVertex(pose, min, min, min).setUv(1f, 1f).setColor(r, g, b, 255).setOverlay(overlay).setUv2(light, light).setNormal(pose, 0f, 0f, -1f)
            vc.addVertex(pose, min, max, min).setUv(1f, 0f).setColor(r, g, b, 255).setOverlay(overlay).setUv2(light, light).setNormal(pose, 0f, 0f, -1f)
            vc.addVertex(pose, max, max, min).setUv(0f, 0f).setColor(r, g, b, 255).setOverlay(overlay).setUv2(light, light).setNormal(pose, 0f, 0f, -1f)
            vc.addVertex(pose, max, min, min).setUv(0f, 1f).setColor(r, g, b, 255).setOverlay(overlay).setUv2(light, light).setNormal(pose, 0f, 0f, -1f)
            // +X
            vc.addVertex(pose, max, min, min).setUv(1f, 1f).setColor(r, g, b, 255).setOverlay(overlay).setUv2(light, light).setNormal(pose, 1f, 0f, 0f)
            vc.addVertex(pose, max, max, min).setUv(1f, 0f).setColor(r, g, b, 255).setOverlay(overlay).setUv2(light, light).setNormal(pose, 1f, 0f, 0f)
            vc.addVertex(pose, max, max, max).setUv(0f, 0f).setColor(r, g, b, 255).setOverlay(overlay).setUv2(light, light).setNormal(pose, 1f, 0f, 0f)
            vc.addVertex(pose, max, min, max).setUv(0f, 1f).setColor(r, g, b, 255).setOverlay(overlay).setUv2(light, light).setNormal(pose, 1f, 0f, 0f)
            // -X
            vc.addVertex(pose, min, min, max).setUv(1f, 1f).setColor(r, g, b, 255).setOverlay(overlay).setUv2(light, light).setNormal(pose, -1f, 0f, 0f)
            vc.addVertex(pose, min, max, max).setUv(1f, 0f).setColor(r, g, b, 255).setOverlay(overlay).setUv2(light, light).setNormal(pose, -1f, 0f, 0f)
            vc.addVertex(pose, min, max, min).setUv(0f, 0f).setColor(r, g, b, 255).setOverlay(overlay).setUv2(light, light).setNormal(pose, -1f, 0f, 0f)
            vc.addVertex(pose, min, min, min).setUv(0f, 1f).setColor(r, g, b, 255).setOverlay(overlay).setUv2(light, light).setNormal(pose, -1f, 0f, 0f)
            // +Y
            vc.addVertex(pose, min, max, max).setUv(0f, 1f).setColor(r, g, b, 255).setOverlay(overlay).setUv2(light, light).setNormal(pose, 0f, 1f, 0f)
            vc.addVertex(pose, max, max, max).setUv(1f, 1f).setColor(r, g, b, 255).setOverlay(overlay).setUv2(light, light).setNormal(pose, 0f, 1f, 0f)
            vc.addVertex(pose, max, max, min).setUv(1f, 0f).setColor(r, g, b, 255).setOverlay(overlay).setUv2(light, light).setNormal(pose, 0f, 1f, 0f)
            vc.addVertex(pose, min, max, min).setUv(0f, 0f).setColor(r, g, b, 255).setOverlay(overlay).setUv2(light, light).setNormal(pose, 0f, 1f, 0f)
            // -Y
            vc.addVertex(pose, min, min, min).setUv(0f, 0f).setColor(r, g, b, 255).setOverlay(overlay).setUv2(light, light).setNormal(pose, 0f, -1f, 0f)
            vc.addVertex(pose, max, min, min).setUv(1f, 0f).setColor(r, g, b, 255).setOverlay(overlay).setUv2(light, light).setNormal(pose, 0f, -1f, 0f)
            vc.addVertex(pose, max, min, max).setUv(1f, 1f).setColor(r, g, b, 255).setOverlay(overlay).setUv2(light, light).setNormal(pose, 0f, -1f, 0f)
            vc.addVertex(pose, min, min, max).setUv(0f, 1f).setColor(r, g, b, 255).setOverlay(overlay).setUv2(light, light).setNormal(pose, 0f, -1f, 0f)
        }
    }

    /** Emits one billboarded beam per [CardLink] from the card slot's exact position on
     *  the node face out to the adjacent block's near face. Billboarding uses the camera
     *  position so the beam always shows its 1px-wide silhouette to the viewer. */
    private fun submitCardLinks(
        state: NodeRenderState,
        poseStack: PoseStack,
        submitNodeCollector: SubmitNodeCollector,
        camera: CameraRenderState,
    ) {
        if (state.cardLinks.isEmpty()) return
        val camPos = camera.pos
        val blockX = state.pos.x.toFloat()
        val blockY = state.pos.y.toFloat()
        val blockZ = state.pos.z.toFloat()
        val hw = 0.3f / 16f

        submitNodeCollector.submitCustomGeometry(poseStack, RenderTypes.beaconBeam(LASER_TEXTURE, true)) { pose, vc ->
            for (link in state.cardLinks) {
                val (offA, offB) = SLOT_OFFSETS[link.slotIndex]

                val bx = link.side.stepX.toFloat()
                val by = link.side.stepY.toFloat()
                val bz = link.side.stepZ.toFloat()

                val ox: Float; val oy: Float; val oz: Float
                val fx: Float; val fy: Float; val fz: Float
                when (link.side) {
                    Direction.NORTH -> { ox = 0.5f + offA; oy = 0.5f + offB; oz = 0.5f; fx = ox; fy = oy; fz = 0f }
                    Direction.SOUTH -> { ox = 0.5f - offA; oy = 0.5f + offB; oz = 0.5f; fx = ox; fy = oy; fz = 1f }
                    Direction.WEST  -> { ox = 0.5f; oy = 0.5f + offB; oz = 0.5f - offA; fx = 0f; fy = oy; fz = oz }
                    Direction.EAST  -> { ox = 0.5f; oy = 0.5f + offB; oz = 0.5f + offA; fx = 1f; fy = oy; fz = oz }
                    Direction.DOWN  -> { ox = 0.5f + offA; oy = 0.5f; oz = 0.5f + offB; fx = ox; fy = 0f; fz = oz }
                    Direction.UP    -> { ox = 0.5f + offA; oy = 0.5f; oz = 0.5f + offB; fx = ox; fy = 1f; fz = oz }
                }

                val midX = (ox + fx) / 2f + blockX
                val midY = (oy + fy) / 2f + blockY
                val midZ = (oz + fz) / 2f + blockZ
                val toCamX = (camPos.x - midX).toFloat()
                val toCamY = (camPos.y - midY).toFloat()
                val toCamZ = (camPos.z - midZ).toFloat()
                var px = by * toCamZ - bz * toCamY
                var py = bz * toCamX - bx * toCamZ
                var pz = bx * toCamY - by * toCamX
                val plen = sqrt(px * px + py * py + pz * pz)
                if (plen < 0.001f) continue
                px = px / plen * hw; py = py / plen * hw; pz = pz / plen * hw

                val overlay = OverlayTexture.NO_OVERLAY
                val a = 180
                vc.addVertex(pose, ox - px, oy - py, oz - pz).setUv(0f, 0f).setColor(link.r, link.g, link.b, a).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
                vc.addVertex(pose, ox + px, oy + py, oz + pz).setUv(0.3f, 0f).setColor(link.r, link.g, link.b, a).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
                vc.addVertex(pose, fx + px, fy + py, fz + pz).setUv(0.3f, 1f).setColor(link.r, link.g, link.b, a).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
                vc.addVertex(pose, fx - px, fy - py, fz - pz).setUv(0f, 1f).setColor(link.r, link.g, link.b, a).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
            }
        }
    }
}
