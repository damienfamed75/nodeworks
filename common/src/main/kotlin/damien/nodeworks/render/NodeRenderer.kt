package damien.nodeworks.render

import com.mojang.blaze3d.vertex.PoseStack
import damien.nodeworks.block.entity.NodeBlockEntity
import damien.nodeworks.network.NetworkSettingsRegistry
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.renderer.feature.ModelFeatureRenderer
import net.minecraft.client.renderer.rendertype.RenderType
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

    /** Per-face tint mode for the inner glow cube. Resolves to an RGB at submit
     *  time (so [Mixed] can animate via the frame clock without re-extracting). */
    sealed class FaceTint {
        /** No cards on this face, fall back to the network color the controller
         *  publishes for the whole node. */
        data object Network : FaceTint()

        /** Every card on this face shares one channel, render that channel's
         *  dye color, including DyeColor.WHITE when the user hasn't dyed any
         *  card on this face. */
        data class Single(val rgb: Int) : FaceTint()

        /** Cards on this face span ≥2 distinct channels. [rgbs] holds the
         *  distinct channel colours actually present, sorted by
         *  [net.minecraft.world.item.DyeColor.ordinal] so the segment order
         *  is stable across save/reload. The lip on each face is split into
         *  N equal-width segments showing those colours side-by-side, so a
         *  blue+white face reads as half-blue, half-white. WHITE shows as
         *  actual white here, the [Single]/[Network] white-fold exists
         *  because a single-white face looked indistinguishable from the
         *  plain frame texture, but on a mixed face the neighbouring
         *  segments make the white slot read as intentional. */
        data class Mixed(val rgbs: List<Int>) : FaceTint()
    }

    class NodeRenderState : ConnectableRenderState() {
        var networkColor: Int = NodeConnectionRenderer.DEFAULT_NETWORK_COLOR
        var glowStyle: Int = 0
        var hasGlow: Boolean = false
        var cardLinks: List<CardLink> = emptyList()

        /** Indexed by Direction.ordinal (DOWN=0, UP=1, NORTH=2, SOUTH=3, WEST=4,
         *  EAST=5). Defaults every face to [FaceTint.Network] so a freshly-spawned
         *  state with no extract still renders correctly. */
        var faceTints: Array<FaceTint> = Array(6) { FaceTint.Network }
    }

    companion object {
        private val GLOW_TEXTURES = arrayOf(
            Identifier.fromNamespaceAndPath("nodeworks", "textures/block/node_glow_square.png"),
            Identifier.fromNamespaceAndPath("nodeworks", "textures/block/node_glow_circle.png"),
            Identifier.fromNamespaceAndPath("nodeworks", "textures/block/node_glow_dot.png"),
            Identifier.fromNamespaceAndPath("nodeworks", "textures/block/node_glow_creeper.png"),
            Identifier.fromNamespaceAndPath("nodeworks", "textures/block/node_glow_spiral.png")
        )

        // glowStyle 5 = NONE in the controller GUI, skip rendering
        private const val GLOW_STYLE_NONE = 5

        private val LASER_TEXTURE = Identifier.fromNamespaceAndPath("nodeworks", "textures/block/laser_trail.png")

        /** Solid-emissive base used for the per-face channel-color "lip" overlay.
         *  Reuses the same `node_glow_square.png` the main glow cube uses, on a
         *  4×1-pixel strip the square pattern reads as a uniform colour, and the
         *  EYES pipeline adds the channel tint via vertex colour. */
        private val LIP_TEXTURE = Identifier.fromNamespaceAndPath("nodeworks", "textures/block/node_glow_square.png")
        private val LIP_RENDER_TYPE: RenderType = EmissiveCubeRenderer.renderType(LIP_TEXTURE)

        /** Fraction of each [FaceTint.Mixed] strip's long axis trimmed off
         *  each end to produce the inset look that matches the single-
         *  colour strip's natural texture falloff. 0.25 leaves a centred
         *  band that's half the strip's long-axis width, with the outer
         *  quarters showing the underlying frame texture. */
        private const val LIP_INSET = 0.25f

        /** Per-card-type beam colour (r, g, b 0–255). */
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
        val settings = NetworkSettingsRegistry.get(blockEntity.networkId)
        state.networkColor = settings.color
        state.glowStyle = settings.glowStyle
        state.hasGlow = settings.glowStyle != GLOW_STYLE_NONE

        // Card-link beams: one per card on each face whose adjacent block isn't air.
        // Per-face channel-tint resolution piggybacks on the same per-side walk so
        // we visit the inventory once.
        val level = blockEntity.level
        if (level != null) {
            val links = mutableListOf<CardLink>()
            for (side in Direction.entries) {
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
                state.faceTints[side.ordinal] = resolveFaceTint(blockEntity.getFaceChannels(side))
            }
            state.cardLinks = links
        } else {
            state.cardLinks = emptyList()
            for (side in Direction.entries) state.faceTints[side.ordinal] = FaceTint.Network
        }
    }

    /** Map a face's per-card channel list to the [FaceTint] that should paint
     *  the glow cube on that face. Empty list (or all-white, the default
     *  unconfigured channel) → [FaceTint.Network] so the lip stays the plain
     *  frame texture, a white indicator looked indistinguishable from the
     *  default lip and was visually noisy. Single non-white channel →
     *  [FaceTint.Single]. ≥2 distinct channels → [FaceTint.Mixed] carrying
     *  exactly those distinct channel RGBs, with WHITE included as-is so a
     *  white+blue face actually cycles to white rather than to whatever the
     *  network colour happens to be. */
    private fun resolveFaceTint(channels: List<net.minecraft.world.item.DyeColor>): FaceTint {
        if (channels.isEmpty()) return FaceTint.Network
        val distinct = channels.distinct()
        if (distinct.size == 1) {
            val only = distinct[0]
            if (only == net.minecraft.world.item.DyeColor.WHITE) return FaceTint.Network
            return FaceTint.Single(only.textureDiffuseColor and 0xFFFFFF)
        }
        // Sort by ordinal so the cycle direction stays put across save/reload,
        // otherwise the order would track inventory iteration and flip whenever
        // the player rearranges cards on the face.
        val rgbs = distinct.sortedBy { it.ordinal }.map { it.textureDiffuseColor and 0xFFFFFF }
        return FaceTint.Mixed(rgbs)
    }

    override fun submitConnectable(
        state: NodeRenderState,
        poseStack: PoseStack,
        submitNodeCollector: SubmitNodeCollector,
        camera: CameraRenderState,
    ) {
        submitCardLinks(state, poseStack, submitNodeCollector, camera)

        // Per-face channel tint goes on the inner lip of each face's frame ring,
        // the main glow cube stays uniformly network-coloured. Run the lip pass
        // even when glowStyle == NONE so faces still indicate their channel.
        submitFaceLips(state, poseStack, submitNodeCollector)

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

        submitNodeCollector.submitCustomGeometry(poseStack, renderType) { pose, vc ->
            // +Z (SOUTH)
            vc.addVertex(pose, max, min, max).setUv(1f, 1f).setColor(r, g, b, 255).setOverlay(overlay)
                .setUv2(RenderUtils.FULL_BRIGHT, RenderUtils.FULL_BRIGHT).setNormal(pose, 0f, 0f, 1f)
            vc.addVertex(pose, max, max, max).setUv(1f, 0f).setColor(r, g, b, 255).setOverlay(overlay)
                .setUv2(RenderUtils.FULL_BRIGHT, RenderUtils.FULL_BRIGHT).setNormal(pose, 0f, 0f, 1f)
            vc.addVertex(pose, min, max, max).setUv(0f, 0f).setColor(r, g, b, 255).setOverlay(overlay)
                .setUv2(RenderUtils.FULL_BRIGHT, RenderUtils.FULL_BRIGHT).setNormal(pose, 0f, 0f, 1f)
            vc.addVertex(pose, min, min, max).setUv(0f, 1f).setColor(r, g, b, 255).setOverlay(overlay)
                .setUv2(RenderUtils.FULL_BRIGHT, RenderUtils.FULL_BRIGHT).setNormal(pose, 0f, 0f, 1f)
            // -Z (NORTH)
            vc.addVertex(pose, min, min, min).setUv(1f, 1f).setColor(r, g, b, 255).setOverlay(overlay)
                .setUv2(RenderUtils.FULL_BRIGHT, RenderUtils.FULL_BRIGHT).setNormal(pose, 0f, 0f, -1f)
            vc.addVertex(pose, min, max, min).setUv(1f, 0f).setColor(r, g, b, 255).setOverlay(overlay)
                .setUv2(RenderUtils.FULL_BRIGHT, RenderUtils.FULL_BRIGHT).setNormal(pose, 0f, 0f, -1f)
            vc.addVertex(pose, max, max, min).setUv(0f, 0f).setColor(r, g, b, 255).setOverlay(overlay)
                .setUv2(RenderUtils.FULL_BRIGHT, RenderUtils.FULL_BRIGHT).setNormal(pose, 0f, 0f, -1f)
            vc.addVertex(pose, max, min, min).setUv(0f, 1f).setColor(r, g, b, 255).setOverlay(overlay)
                .setUv2(RenderUtils.FULL_BRIGHT, RenderUtils.FULL_BRIGHT).setNormal(pose, 0f, 0f, -1f)
            // +X (EAST)
            vc.addVertex(pose, max, min, min).setUv(1f, 1f).setColor(r, g, b, 255).setOverlay(overlay)
                .setUv2(RenderUtils.FULL_BRIGHT, RenderUtils.FULL_BRIGHT).setNormal(pose, 1f, 0f, 0f)
            vc.addVertex(pose, max, max, min).setUv(1f, 0f).setColor(r, g, b, 255).setOverlay(overlay)
                .setUv2(RenderUtils.FULL_BRIGHT, RenderUtils.FULL_BRIGHT).setNormal(pose, 1f, 0f, 0f)
            vc.addVertex(pose, max, max, max).setUv(0f, 0f).setColor(r, g, b, 255).setOverlay(overlay)
                .setUv2(RenderUtils.FULL_BRIGHT, RenderUtils.FULL_BRIGHT).setNormal(pose, 1f, 0f, 0f)
            vc.addVertex(pose, max, min, max).setUv(0f, 1f).setColor(r, g, b, 255).setOverlay(overlay)
                .setUv2(RenderUtils.FULL_BRIGHT, RenderUtils.FULL_BRIGHT).setNormal(pose, 1f, 0f, 0f)
            // -X (WEST)
            vc.addVertex(pose, min, min, max).setUv(1f, 1f).setColor(r, g, b, 255).setOverlay(overlay)
                .setUv2(RenderUtils.FULL_BRIGHT, RenderUtils.FULL_BRIGHT).setNormal(pose, -1f, 0f, 0f)
            vc.addVertex(pose, min, max, max).setUv(1f, 0f).setColor(r, g, b, 255).setOverlay(overlay)
                .setUv2(RenderUtils.FULL_BRIGHT, RenderUtils.FULL_BRIGHT).setNormal(pose, -1f, 0f, 0f)
            vc.addVertex(pose, min, max, min).setUv(0f, 0f).setColor(r, g, b, 255).setOverlay(overlay)
                .setUv2(RenderUtils.FULL_BRIGHT, RenderUtils.FULL_BRIGHT).setNormal(pose, -1f, 0f, 0f)
            vc.addVertex(pose, min, min, min).setUv(0f, 1f).setColor(r, g, b, 255).setOverlay(overlay)
                .setUv2(RenderUtils.FULL_BRIGHT, RenderUtils.FULL_BRIGHT).setNormal(pose, -1f, 0f, 0f)
            // +Y (UP)
            vc.addVertex(pose, min, max, max).setUv(0f, 1f).setColor(r, g, b, 255).setOverlay(overlay)
                .setUv2(RenderUtils.FULL_BRIGHT, RenderUtils.FULL_BRIGHT).setNormal(pose, 0f, 1f, 0f)
            vc.addVertex(pose, max, max, max).setUv(1f, 1f).setColor(r, g, b, 255).setOverlay(overlay)
                .setUv2(RenderUtils.FULL_BRIGHT, RenderUtils.FULL_BRIGHT).setNormal(pose, 0f, 1f, 0f)
            vc.addVertex(pose, max, max, min).setUv(1f, 0f).setColor(r, g, b, 255).setOverlay(overlay)
                .setUv2(RenderUtils.FULL_BRIGHT, RenderUtils.FULL_BRIGHT).setNormal(pose, 0f, 1f, 0f)
            vc.addVertex(pose, min, max, min).setUv(0f, 0f).setColor(r, g, b, 255).setOverlay(overlay)
                .setUv2(RenderUtils.FULL_BRIGHT, RenderUtils.FULL_BRIGHT).setNormal(pose, 0f, 1f, 0f)
            // -Y (DOWN)
            vc.addVertex(pose, min, min, min).setUv(0f, 0f).setColor(r, g, b, 255).setOverlay(overlay)
                .setUv2(RenderUtils.FULL_BRIGHT, RenderUtils.FULL_BRIGHT).setNormal(pose, 0f, -1f, 0f)
            vc.addVertex(pose, max, min, min).setUv(1f, 0f).setColor(r, g, b, 255).setOverlay(overlay)
                .setUv2(RenderUtils.FULL_BRIGHT, RenderUtils.FULL_BRIGHT).setNormal(pose, 0f, -1f, 0f)
            vc.addVertex(pose, max, min, max).setUv(1f, 1f).setColor(r, g, b, 255).setOverlay(overlay)
                .setUv2(RenderUtils.FULL_BRIGHT, RenderUtils.FULL_BRIGHT).setNormal(pose, 0f, -1f, 0f)
            vc.addVertex(pose, min, min, max).setUv(0f, 1f).setColor(r, g, b, 255).setOverlay(overlay)
                .setUv2(RenderUtils.FULL_BRIGHT, RenderUtils.FULL_BRIGHT).setNormal(pose, 0f, -1f, 0f)
        }
    }

    /** Emit emissive overlay quads on each face's inner-lip surfaces (the inward-
     *  facing walls of the 4 frame edges that ring the central opening on a side).
     *  Faces with [FaceTint.Network] are skipped so their lip stays the plain
     *  `#frame` texture from the JSON model. [FaceTint.Single] paints all 4
     *  strips one colour, [FaceTint.Mixed] splits each strip into N equal-width
     *  segments along its long axis so the player sees every channel on the
     *  face at once. */
    private fun submitFaceLips(
        state: NodeRenderState,
        poseStack: PoseStack,
        submitNodeCollector: SubmitNodeCollector,
    ) {
        var anyTinted = false
        for (i in 0 until 6) {
            if (state.faceTints[i] !is FaceTint.Network) {
                anyTinted = true; break
            }
        }
        if (!anyTinted) return

        submitNodeCollector.submitCustomGeometry(poseStack, LIP_RENDER_TYPE) { pose, vc ->
            for (side in Direction.entries) {
                val rgbs = segmentColors(state.faceTints[side.ordinal]) ?: continue
                emitLipQuadsForFace(pose, vc, side, rgbs)
            }
        }
    }

    /** Build the per-segment colour list for one face. Returns null for
     *  [FaceTint.Network] so the caller skips the strip emit, the plain
     *  frame texture stays visible. Single-element array for [FaceTint.Single]
     *  paints all 4 strips uniformly. N-element array for [FaceTint.Mixed]
     *  drives the per-strip subdivision. */
    private fun segmentColors(tint: FaceTint): IntArray? = when (tint) {
        is FaceTint.Network -> null
        is FaceTint.Single -> intArrayOf(tint.rgb)
        is FaceTint.Mixed -> tint.rgbs.toIntArray()
    }

    /** Emit the 4 lip quads for a single face. Each quad is a 1-pixel-deep strip on
     *  the inward-facing wall of one of the face's 4 frame edges, together they
     *  form a thin rectangular ring around the face's central opening. CCW vertex
     *  winding from the cavity-facing side so back-face culling keeps them.
     *
     *  Two pairs of lateral spans: [a5,aB] (6 px) for strips that sit on the
     *  long horizontal frame edges (bottom-N/S, top-N/S), [a6,aA] (4 px) for
     *  strips that sit on the verticals or on the short edges of the U/D rings
     *  (bottom/top-west/east). Each strip is offset by [eps] along its normal so
     *  it sits just inside the JSON model's underlying frame face, keeps the
     *  EYES pipeline (depth-test, no depth-write) from z-fighting that face. */
    private fun emitLipQuadsForFace(
        pose: PoseStack.Pose,
        vc: com.mojang.blaze3d.vertex.VertexConsumer,
        side: Direction,
        rgbs: IntArray,
    ) {
        val a5 = 5f / 16f
        val a6 = 6f / 16f
        val aA = 10f / 16f
        val aB = 11f / 16f
        val eps = 0.01f / 16f
        val a6e = a6 + eps  // strip plane just inward of a6 (use with +X/+Y/+Z normals)
        val aAe = aA - eps  // strip plane just inward of aA (use with -X/-Y/-Z normals)
        val outer: Float
        val inner: Float
        when (side) {
            Direction.SOUTH, Direction.EAST, Direction.UP -> {
                outer = 11f / 16f; inner = 10f / 16f
            }

            Direction.NORTH, Direction.WEST, Direction.DOWN -> {
                outer = 5f / 16f; inner = 6f / 16f
            }
        }
        when (side) {
            Direction.SOUTH -> {
                // bottom-south top face (long, x=a5..aB)
                lipQuad(pose, vc, a5, a6e, outer, aB, a6e, outer, aB, a6e, inner, a5, a6e, inner, 0f, 1f, 0f, rgbs)
                // top-south bottom face (long, x=a5..aB)
                lipQuad(pose, vc, a5, aAe, inner, aB, aAe, inner, aB, aAe, outer, a5, aAe, outer, 0f, -1f, 0f, rgbs)
                // vertical-SW inner face (short, y=a6..aA)
                lipQuad(pose, vc, a6e, aA, outer, a6e, a6, outer, a6e, a6, inner, a6e, aA, inner, 1f, 0f, 0f, rgbs)
                // vertical-SE inner face (short, y=a6..aA)
                lipQuad(pose, vc, aAe, a6, inner, aAe, a6, outer, aAe, aA, outer, aAe, aA, inner, -1f, 0f, 0f, rgbs)
            }

            Direction.NORTH -> {
                lipQuad(pose, vc, a5, a6e, inner, aB, a6e, inner, aB, a6e, outer, a5, a6e, outer, 0f, 1f, 0f, rgbs)
                lipQuad(pose, vc, a5, aAe, outer, aB, aAe, outer, aB, aAe, inner, a5, aAe, inner, 0f, -1f, 0f, rgbs)
                lipQuad(pose, vc, a6e, a6, inner, a6e, a6, outer, a6e, aA, outer, a6e, aA, inner, 1f, 0f, 0f, rgbs)
                lipQuad(pose, vc, aAe, a6, outer, aAe, a6, inner, aAe, aA, inner, aAe, aA, outer, -1f, 0f, 0f, rgbs)
            }

            Direction.EAST -> {
                // East ring is 4-px on all 4 strips, bottom/top-east are short (z=a6..aA),
                // vertical-NE/SE are short (y=a6..aA).
                lipQuad(pose, vc, inner, a6e, aA, outer, a6e, aA, outer, a6e, a6, inner, a6e, a6, 0f, 1f, 0f, rgbs)
                lipQuad(pose, vc, inner, aAe, a6, outer, aAe, a6, outer, aAe, aA, inner, aAe, aA, 0f, -1f, 0f, rgbs)
                lipQuad(pose, vc, inner, a6, a6e, outer, a6, a6e, outer, aA, a6e, inner, aA, a6e, 0f, 0f, 1f, rgbs)
                lipQuad(pose, vc, outer, a6, aAe, inner, a6, aAe, inner, aA, aAe, outer, aA, aAe, 0f, 0f, -1f, rgbs)
            }

            Direction.WEST -> {
                lipQuad(pose, vc, outer, a6e, aA, inner, a6e, aA, inner, a6e, a6, outer, a6e, a6, 0f, 1f, 0f, rgbs)
                lipQuad(pose, vc, outer, aAe, a6, inner, aAe, a6, inner, aAe, aA, outer, aAe, aA, 0f, -1f, 0f, rgbs)
                lipQuad(pose, vc, outer, a6, a6e, inner, a6, a6e, inner, aA, a6e, outer, aA, a6e, 0f, 0f, 1f, rgbs)
                lipQuad(pose, vc, inner, a6, aAe, outer, a6, aAe, outer, aA, aAe, inner, aA, aAe, 0f, 0f, -1f, rgbs)
            }

            Direction.UP -> {
                // top-north/top-south are long in X (a5..aB), top-west/top-east are short in Z.
                lipQuad(pose, vc, a5, inner, a6e, aB, inner, a6e, aB, outer, a6e, a5, outer, a6e, 0f, 0f, 1f, rgbs)
                lipQuad(pose, vc, aB, inner, aAe, a5, inner, aAe, a5, outer, aAe, aB, outer, aAe, 0f, 0f, -1f, rgbs)
                lipQuad(pose, vc, a6e, inner, aA, a6e, inner, a6, a6e, outer, a6, a6e, outer, aA, 1f, 0f, 0f, rgbs)
                lipQuad(pose, vc, aAe, inner, a6, aAe, inner, aA, aAe, outer, aA, aAe, outer, a6, -1f, 0f, 0f, rgbs)
            }

            Direction.DOWN -> {
                lipQuad(pose, vc, a5, outer, a6e, aB, outer, a6e, aB, inner, a6e, a5, inner, a6e, 0f, 0f, 1f, rgbs)
                lipQuad(pose, vc, aB, outer, aAe, a5, outer, aAe, a5, inner, aAe, aB, inner, aAe, 0f, 0f, -1f, rgbs)
                lipQuad(pose, vc, a6e, outer, aA, a6e, outer, a6, a6e, inner, a6, a6e, inner, aA, 1f, 0f, 0f, rgbs)
                lipQuad(pose, vc, aAe, outer, a6, aAe, outer, aA, aAe, inner, aA, aAe, inner, a6, -1f, 0f, 0f, rgbs)
            }
        }
    }

    /** Emit one lip strip, optionally split into N equal-width sub-quads along
     *  the strip's long axis when [rgbs] has more than one entry. Vertex
     *  ordering is CCW (v0→v1→v2→v3) from the cavity-facing side. The strip's
     *  long axis can be either v0→v1 or v0→v3 depending on the call site, so
     *  we measure both edge pairs and split along the longer one.
     *
     *  Layout: the single-colour strip uses the full 0..1 UV range so the
     *  texture's natural alpha falloff at the U/V edges paints a smaller
     *  emissive bar inside the strip's geometry. The multi-segment path
     *  can't reuse that — proportional UV slicing gives the middle segment
     *  of a 3-way split the bright plateau while the outer two get the
     *  faded edges, so the middle reads as much wider. Instead we keep the
     *  "emissive smaller than the lip" look by shrinking the *geometry* on
     *  *both* axes to the central [LIP_INSET..1-LIP_INSET] band: long-axis
     *  inset trims the outer ends of the strip, depth-axis inset trims the
     *  two long edges. Inside that band, each sub-quad covers an equal 1/N
     *  slice along the long axis and samples the texture's bright centre
     *  at UV (0.5, 0.5) flat so segment boundaries are clean colour
     *  switches. The trimmed margins on all four sides show the underlying
     *  frame texture, which produces the inset effect. */
    private fun lipQuad(
        pose: PoseStack.Pose,
        vc: com.mojang.blaze3d.vertex.VertexConsumer,
        x0: Float, y0: Float, z0: Float, // v0
        x1: Float, y1: Float, z1: Float, // v1
        x2: Float, y2: Float, z2: Float, // v2
        x3: Float, y3: Float, z3: Float, // v3
        nx: Float, ny: Float, nz: Float, // normal
        rgbs: IntArray,
    ) {
        val n = rgbs.size
        if (n <= 1) {
            emitLipSubQuad(
                pose, vc,
                x0, y0, z0, x1, y1, z1, x2, y2, z2, x3, y3, z3,
                0f, 1f, 1f, 1f, 1f, 0f, 0f, 0f,
                nx, ny, nz, rgbs[0],
            )
            return
        }
        // |v0→v1|² vs |v0→v3|², pick the longer pair as the segmentation axis.
        // Strip 4-on-each-vertical-face has v0→v1 as the 1px depth and v0→v3
        // as the 4px length, the others have it the other way round, so the
        // detection saves us hand-normalising 24 call sites.
        val d01x = x1 - x0; val d01y = y1 - y0; val d01z = z1 - z0
        val d03x = x3 - x0; val d03y = y3 - y0; val d03z = z3 - z0
        val len01sq = d01x * d01x + d01y * d01y + d01z * d01z
        val len03sq = d03x * d03x + d03y * d03y + d03z * d03z
        val along01 = len01sq >= len03sq
        val emissiveSpan = 1f - 2f * LIP_INSET
        for (i in 0 until n) {
            val t0 = LIP_INSET + emissiveSpan * i.toFloat() / n
            val t1 = LIP_INSET + emissiveSpan * (i + 1).toFloat() / n
            val rgb = rgbs[i]
            if (along01) {
                // Original (un-inset) corners.
                // q0 = lerp(v0,v1,t0), q1 = lerp(v0,v1,t1) — depth-1 edge
                // q2 = lerp(v3,v2,t1), q3 = lerp(v3,v2,t0) — depth-0 edge
                // Depth-opposite pairs: (q0, q3) at long t0, (q1, q2) at long t1.
                val q0x = x0 + d01x * t0; val q0y = y0 + d01y * t0; val q0z = z0 + d01z * t0
                val q1x = x0 + d01x * t1; val q1y = y0 + d01y * t1; val q1z = z0 + d01z * t1
                val d32x = x2 - x3; val d32y = y2 - y3; val d32z = z2 - z3
                val q2x = x3 + d32x * t1; val q2y = y3 + d32y * t1; val q2z = z3 + d32z * t1
                val q3x = x3 + d32x * t0; val q3y = y3 + d32y * t0; val q3z = z3 + d32z * t0
                // Pull each corner toward its depth-opposite by LIP_INSET.
                val p0x = q0x + (q3x - q0x) * LIP_INSET; val p0y = q0y + (q3y - q0y) * LIP_INSET; val p0z = q0z + (q3z - q0z) * LIP_INSET
                val p1x = q1x + (q2x - q1x) * LIP_INSET; val p1y = q1y + (q2y - q1y) * LIP_INSET; val p1z = q1z + (q2z - q1z) * LIP_INSET
                val p2x = q2x + (q1x - q2x) * LIP_INSET; val p2y = q2y + (q1y - q2y) * LIP_INSET; val p2z = q2z + (q1z - q2z) * LIP_INSET
                val p3x = q3x + (q0x - q3x) * LIP_INSET; val p3y = q3y + (q0y - q3y) * LIP_INSET; val p3z = q3z + (q0z - q3z) * LIP_INSET
                emitLipSubQuad(
                    pose, vc,
                    p0x, p0y, p0z, p1x, p1y, p1z, p2x, p2y, p2z, p3x, p3y, p3z,
                    0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f,
                    nx, ny, nz, rgb,
                )
            } else {
                // Original (un-inset) corners.
                // q0 = lerp(v0,v3,t0), q3 = lerp(v0,v3,t1) — depth-0 edge
                // q1 = lerp(v1,v2,t0), q2 = lerp(v1,v2,t1) — depth-1 edge
                // Depth-opposite pairs: (q0, q1) at long t0, (q3, q2) at long t1.
                val q0x = x0 + d03x * t0; val q0y = y0 + d03y * t0; val q0z = z0 + d03z * t0
                val q3x = x0 + d03x * t1; val q3y = y0 + d03y * t1; val q3z = z0 + d03z * t1
                val d12x = x2 - x1; val d12y = y2 - y1; val d12z = z2 - z1
                val q1x = x1 + d12x * t0; val q1y = y1 + d12y * t0; val q1z = z1 + d12z * t0
                val q2x = x1 + d12x * t1; val q2y = y1 + d12y * t1; val q2z = z1 + d12z * t1
                val p0x = q0x + (q1x - q0x) * LIP_INSET; val p0y = q0y + (q1y - q0y) * LIP_INSET; val p0z = q0z + (q1z - q0z) * LIP_INSET
                val p1x = q1x + (q0x - q1x) * LIP_INSET; val p1y = q1y + (q0y - q1y) * LIP_INSET; val p1z = q1z + (q0z - q1z) * LIP_INSET
                val p2x = q2x + (q3x - q2x) * LIP_INSET; val p2y = q2y + (q3y - q2y) * LIP_INSET; val p2z = q2z + (q3z - q2z) * LIP_INSET
                val p3x = q3x + (q2x - q3x) * LIP_INSET; val p3y = q3y + (q2y - q3y) * LIP_INSET; val p3z = q3z + (q2z - q3z) * LIP_INSET
                emitLipSubQuad(
                    pose, vc,
                    p0x, p0y, p0z, p1x, p1y, p1z, p2x, p2y, p2z, p3x, p3y, p3z,
                    0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f,
                    nx, ny, nz, rgb,
                )
            }
        }
    }

    /** Yea it's ugly but it's a hot path */
    private fun emitLipSubQuad(
        pose: PoseStack.Pose,
        vc: com.mojang.blaze3d.vertex.VertexConsumer,
        x0: Float, y0: Float, z0: Float,
        x1: Float, y1: Float, z1: Float,
        x2: Float, y2: Float, z2: Float,
        x3: Float, y3: Float, z3: Float,
        u0: Float, v0: Float,
        u1: Float, v1: Float,
        u2: Float, v2: Float,
        u3: Float, v3: Float,
        nx: Float, ny: Float, nz: Float,
        rgb: Int,
    ) {
        val r = (rgb shr 16) and 0xFF
        val g = (rgb shr 8) and 0xFF
        val b = rgb and 0xFF
        val overlay = OverlayTexture.NO_OVERLAY
        vc.addVertex(pose, x0, y0, z0)
            .setUv(u0, v0)
            .setColor(r, g, b, 255)
            .setOverlay(overlay)
            .setUv2(RenderUtils.FULL_BRIGHT, RenderUtils.FULL_BRIGHT)
            .setNormal(pose, nx, ny, nz)
        vc.addVertex(pose, x1, y1, z1)
            .setUv(u1, v1)
            .setColor(r, g, b, 255)
            .setOverlay(overlay)
            .setUv2(RenderUtils.FULL_BRIGHT, RenderUtils.FULL_BRIGHT)
            .setNormal(pose, nx, ny, nz)
        vc.addVertex(pose, x2, y2, z2)
            .setUv(u2, v2)
            .setColor(r, g, b, 255)
            .setOverlay(overlay)
            .setUv2(RenderUtils.FULL_BRIGHT, RenderUtils.FULL_BRIGHT)
            .setNormal(pose, nx, ny, nz)
        vc.addVertex(pose, x3, y3, z3)
            .setUv(u3, v3)
            .setColor(r, g, b, 255)
            .setOverlay(overlay)
            .setUv2(RenderUtils.FULL_BRIGHT, RenderUtils.FULL_BRIGHT)
            .setNormal(pose, nx, ny, nz)
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
