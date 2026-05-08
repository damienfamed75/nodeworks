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
import java.util.EnumSet
import kotlin.math.sqrt

/**
 * Renders the per-card-slot laser beams that connect a node face to its adjacent
 * block. The Node body itself comes from the JSON model and gets no extra
 * emissive overlay, the wireframe + grey core read fine on their own.
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
        var cardLinks: List<CardLink> = emptyList()
        /** Faces touching another Connectable. The shared [PipeLaserBeam] uses
         *  these to draw one half-beam per direction from the Node centre, so
         *  the laser flows through the Node like a junction pipe. */
        var pipeDirections: EnumSet<Direction> = EnumSet.noneOf(Direction::class.java)
        /** Network tint for the through-Node laser. Comes from the BE's
         *  networkColor() (which already handles null networkId → grey). */
        var networkColor: Int = NodeConnectionRenderer.DEFAULT_NETWORK_COLOR
        var laserMode: Int = NetworkSettingsRegistry.LASER_MODE_FANCY
        /** False when the Node has no controller. Suppresses the through-Node
         *  laser, the per-card-link beams stay visible because cards are
         *  attached locally and don't need a network to be meaningful. */
        var hasNetwork: Boolean = false
        /** True when the Node's networkId is a Processing Handler micro-net.
         *  Switches the laser texture to the hazard-stripe variant. */
        var isMicro: Boolean = false
    }

    companion object {
        private val LASER_TEXTURE = Identifier.fromNamespaceAndPath("nodeworks", "textures/block/laser_trail.png")

        /** Per-card-type beam colour (r, g, b 0-255). */
        private val CARD_COLORS = mapOf(
            "io" to Triple(0x83, 0xE0, 0x86), // green
            "storage" to Triple(0xAA, 0x83, 0xE0), // purple
            "redstone" to Triple(0xF5, 0x3B, 0x68), // red
            "observer" to Triple(0xFF, 0xEB, 0x3B)  // yellow
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
        state.pipeDirections.clear()
        for (dir in Direction.entries) {
            if (blockEntity.faceRole(dir) == NodeBlockEntity.FaceRole.PIPE) state.pipeDirections.add(dir)
        }
        val settings = NetworkSettingsRegistry.get(blockEntity.networkId)
        state.networkColor = settings.color
        state.laserMode = settings.laserMode
        state.hasNetwork = blockEntity.networkId != null
        state.isMicro = MicroNetworkClientRegistry.isMicro(blockEntity.networkId)

        val level = blockEntity.level
        if (level != null) {
            val links = mutableListOf<CardLink>()
            for (side in Direction.entries) {
                // PIPE-roled faces (touching a Connectable) consume the face for
                // network connectivity, no cards are valid there. Skip card beams
                // on those faces.
                if (blockEntity.faceRole(side) == NodeBlockEntity.FaceRole.PIPE) continue
                val adjacentPos = blockEntity.blockPos.relative(side)
                val targetIsAir = level.getBlockState(adjacentPos).isAir
                for (card in blockEntity.getCards(side)) {
                    val (r, g, b) = CARD_COLORS[card.card.cardType] ?: continue
                    // Inventory cards (io / storage) and the redstone card need a real
                    // adjacent block to do anything, so we don't draw their beam into
                    // empty air. Observer cards are useful pointed at air, they fire
                    // onChange when something *appears* there, so their beam stays
                    // visible regardless. Future card types that should beam into air
                    // get added here.
                    if (targetIsAir && card.card.cardType != "observer") continue
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
        // Through-Node network laser, same per-direction-from-centre layout
        // pipes use, so the Node reads as a junction in the middle of a run.
        // Always emit the centre core, the Node body's wireframe geometry
        // makes even a straight-through configuration's beam-meeting-point
        // visible enough to want masking.
        if (state.hasNetwork) {
            PipeLaserBeam.submit(
                poseStack, submitNodeCollector, state.pos, camera.pos,
                state.pipeDirections, state.networkColor,
                laserMode = state.laserMode,
                drawCenterCore = state.pipeDirections.isNotEmpty(),
                isMicro = state.isMicro,
            )
        }
        submitCardLinks(state, poseStack, submitNodeCollector, camera)
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
        val hw = 0.3f / 16f

        submitNodeCollector.submitCustomGeometry(poseStack, RenderTypes.beaconBeam(LASER_TEXTURE, true)) { pose, vc ->
            for (link in state.cardLinks) {
                val (offA, offB) = SLOT_OFFSETS[link.slotIndex]

                val bx = link.side.stepX.toFloat()
                val by = link.side.stepY.toFloat()
                val bz = link.side.stepZ.toFloat()

                val ox: Float;
                val oy: Float;
                val oz: Float
                val fx: Float;
                val fy: Float;
                val fz: Float
                when (link.side) {
                    Direction.NORTH -> {
                        ox = 0.5f + offA; oy = 0.5f + offB; oz = 0.5f; fx = ox; fy = oy; fz = 0f
                    }

                    Direction.SOUTH -> {
                        ox = 0.5f - offA; oy = 0.5f + offB; oz = 0.5f; fx = ox; fy = oy; fz = 1f
                    }

                    Direction.WEST -> {
                        ox = 0.5f; oy = 0.5f + offB; oz = 0.5f - offA; fx = 0f; fy = oy; fz = oz
                    }

                    Direction.EAST -> {
                        ox = 0.5f; oy = 0.5f + offB; oz = 0.5f + offA; fx = 1f; fy = oy; fz = oz
                    }

                    Direction.DOWN -> {
                        ox = 0.5f + offA; oy = 0.5f; oz = 0.5f + offB; fx = ox; fy = 0f; fz = oz
                    }

                    Direction.UP -> {
                        ox = 0.5f + offA; oy = 0.5f; oz = 0.5f + offB; fx = ox; fy = 1f; fz = oz
                    }
                }

                val blockX = state.pos.x.toFloat()
                val blockY = state.pos.y.toFloat()
                val blockZ = state.pos.z.toFloat()
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
                vc.addVertex(pose, ox - px, oy - py, oz - pz).setUv(0f, 0f).setColor(link.r, link.g, link.b, a)
                    .setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
                vc.addVertex(pose, ox + px, oy + py, oz + pz).setUv(0.3f, 0f).setColor(link.r, link.g, link.b, a)
                    .setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
                vc.addVertex(pose, fx + px, fy + py, fz + pz).setUv(0.3f, 1f).setColor(link.r, link.g, link.b, a)
                    .setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
                vc.addVertex(pose, fx - px, fy - py, fz - pz).setUv(0f, 1f).setColor(link.r, link.g, link.b, a)
                    .setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
            }
        }
    }
}
