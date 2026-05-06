package damien.nodeworks.render

import com.mojang.blaze3d.pipeline.BlendFunction
import com.mojang.blaze3d.pipeline.ColorTargetState
import com.mojang.blaze3d.pipeline.DepthStencilState
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.platform.CompareOp
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexFormat
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.rendertype.RenderSetup
import net.minecraft.client.renderer.rendertype.RenderType
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.core.Direction
import net.minecraft.resources.Identifier

/**
 * Shared helper for BERs that render an emissive "glow" cube over the block's
 * base model. Mirrors vanilla `RenderPipelines.EYES` (`core/entity` shader
 * with EMISSIVE / NO_OVERLAY / NO_CARDINAL_LIGHTING) but swaps TRANSLUCENT
 * blend for [BlendFunction.ADDITIVE] so the glow brightens the underlying
 * pixels instead of replacing them. Same approach `PinHighlightRenderType`
 * uses for the wrench-selection halo, which is why it actually pops on screen.
 *
 * The pipeline reuses `MATRICES_FOG_SNIPPET` directly (exposed via AT) so the
 * shader gets the exact uniform-buffer wiring it expects. Reconstructing those
 * uniforms by hand looked equivalent but produced silent no-output, the
 * snippet must carry binding metadata the bare uniform names don't.
 */
object EmissiveCubeRenderer {

    // Face bit-mask. Kept as ints so callers can OR them together cheaply.
    const val FACE_NORTH = 1
    const val FACE_SOUTH = 2
    const val FACE_WEST = 4
    const val FACE_EAST = 8
    const val FACE_UP = 16
    const val FACE_DOWN = 32

    /** All six faces, for blocks whose emissive overlay covered the whole cube (Crafting
     *  Core, Co-Processor, Crafting Storage + overheating variants). */
    const val ALL_FACES = FACE_NORTH or FACE_SOUTH or FACE_WEST or FACE_EAST or FACE_UP or FACE_DOWN

    /** Four horizontal sides only, for blocks whose emissive overlay covered sides but
     *  not top/bottom (Variable, Receiver Antenna). */
    const val HORIZONTAL_SIDES = FACE_NORTH or FACE_SOUTH or FACE_WEST or FACE_EAST

    /** Map a [Direction] to the matching face-mask bit. Used by BERs whose emissive
     *  overlay is on a single face that rotates with the block's `facing` property
     *  (Terminal, Processing Storage, Instruction Storage). */
    fun faceOf(direction: Direction): Int = when (direction) {
        Direction.NORTH -> FACE_NORTH
        Direction.SOUTH -> FACE_SOUTH
        Direction.WEST -> FACE_WEST
        Direction.EAST -> FACE_EAST
        Direction.UP -> FACE_UP
        Direction.DOWN -> FACE_DOWN
    }

    // Matches the -0.01..16.01 overlay offset used by the pre-BER JSON models. Keeps
    // the glow shell flush with the base cube without Z-fighting.
    private const val INSET = -0.000625f
    private const val EXTENT = 1f - INSET
    private val OVERLAY = OverlayTexture.NO_OVERLAY

    private val renderTypeCache = HashMap<Identifier, RenderType>()

    /**
     * [BlendFunction.LIGHTNING] is `(SRC_ALPHA, ONE)`, alpha-modulated additive,
     * the same blend mode vanilla uses for lightning bolts and beacon-beam
     * flares. Transparent texture pixels (`alpha=0`) contribute zero, only the
     * lit pixels add to the framebuffer, so the texture's transparency is
     * respected the same way as the old TRANSLUCENT EYES path. Pure
     * [BlendFunction.ADDITIVE] is `(ONE, ONE)` and ignores alpha entirely
     * which made the whole quad glow instead of just the lit areas.
     */
    private val ADDITIVE_EMISSIVE_PIPELINE: RenderPipeline =
        RenderPipeline.builder(RenderPipelines.MATRICES_FOG_SNIPPET)
            .withLocation(Identifier.fromNamespaceAndPath("nodeworks", "pipeline/additive_emissive"))
            .withVertexShader("core/entity")
            .withFragmentShader("core/entity")
            .withShaderDefine("EMISSIVE")
            .withShaderDefine("NO_OVERLAY")
            .withShaderDefine("NO_CARDINAL_LIGHTING")
            .withSampler("Sampler0")
            .withColorTargetState(ColorTargetState(BlendFunction.LIGHTNING))
            .withVertexFormat(DefaultVertexFormat.ENTITY, VertexFormat.Mode.QUADS)
            .withDepthStencilState(DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, false))
            .build()

    /**
     * Memoized additive-emissive [RenderType] that samples [texture] directly
     * (not via the block atlas). The same [RenderType] instance is returned
     * for repeat calls with the same texture so the render-queue batches BER
     * submissions across blocks.
     */
    fun renderType(texture: Identifier): RenderType = renderTypeCache.getOrPut(texture) {
        val safe = texture.path.replace('/', '_').replace('.', '_')
        RenderType.create(
            "nodeworks_emissive_${texture.namespace}_${safe}",
            RenderSetup.builder(ADDITIVE_EMISSIVE_PIPELINE)
                .withTexture("Sampler0", texture)
                .createRenderSetup()
        )
    }

    /**
     * Single shared additive-emissive [RenderType] that samples from the
     * block atlas. Use this for rendering BakedModel quads (which carry
     * atlas-relative UVs) through the additive-glow pipeline -- e.g. the
     * User device's emissive overlay, where the underlying model's per-
     * face UVs need to be respected rather than stretching one tile per
     * face the way [submit] / [submitSides] do.
     */
    val BLOCK_ATLAS_RENDER_TYPE: RenderType by lazy {
        RenderType.create(
            "nodeworks_emissive_block_atlas",
            RenderSetup.builder(ADDITIVE_EMISSIVE_PIPELINE)
                .withTexture("Sampler0", net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS)
                .createRenderSetup()
        )
    }


    /**
     * Emit the 4 faces perpendicular to [facing] with each face's UV rotated so the
     * texture's top edge points toward [facing] in world space, the same piston-style
     * convention used by `_side.png` block models (Breaker, Placer). Use this when the
     * underlying JSON model wraps a single side texture around the FACING axis with
     * per-face `"rotation"` tags, the BER's emissive overlay must apply the same UV
     * permutation or the glow won't align with the base texture.
     *
     * The lookup table below maps each (FACING, perpendicular world face) pair to a
     * rotation step (0–3, in 90° CW units). Derived by composing the model's per-face
     * rotation (down=180, up=0, east=90, west=270 at FACING=north) with the blockstate
     * rotation that puts FACING on the front, for vertical FACINGs (UP/DOWN) the model
     * gets an X-axis rotation, so the 4 perpendicular world faces are N/S/E/W instead.
     */
    fun submitSides(
        submitter: SubmitNodeCollector,
        pose: PoseStack,
        renderType: RenderType,
        facing: Direction,
        r: Int, g: Int, b: Int, a: Int,
    ) {
        // Each row picks rot so the texture's top points toward [facing]. Derived
        // directly from each face's CW direction (in EmissiveCubeRenderer's UV
        // layout): UP/DOWN baselines have top at -Z, N/S/E/W baselines have top at
        // +Y. The DOWN face is special, its v-axis runs north→south rather than
        // the MC standard south→north, so rotation values for DOWN are NOT mirrors
        // of the UP entries.
        val rotByFace: Map<Direction, Int> = when (facing) {
            Direction.NORTH -> mapOf(
                Direction.UP    to 0,
                Direction.DOWN  to 0,
                Direction.EAST  to 1,
                Direction.WEST  to 3,
            )
            Direction.SOUTH -> mapOf(
                Direction.UP    to 2,
                Direction.DOWN  to 2,
                Direction.EAST  to 3,
                Direction.WEST  to 1,
            )
            Direction.EAST -> mapOf(
                Direction.UP    to 1,
                Direction.DOWN  to 1,
                Direction.NORTH to 3,
                Direction.SOUTH to 1,
            )
            Direction.WEST -> mapOf(
                Direction.UP    to 3,
                Direction.DOWN  to 3,
                Direction.NORTH to 1,
                Direction.SOUTH to 3,
            )
            Direction.UP -> mapOf(
                Direction.NORTH to 0,
                Direction.SOUTH to 0,
                Direction.EAST  to 0,
                Direction.WEST  to 0,
            )
            Direction.DOWN -> mapOf(
                Direction.NORTH to 2,
                Direction.SOUTH to 2,
                Direction.EAST  to 2,
                Direction.WEST  to 2,
            )
        }
        if (rotByFace.isEmpty()) return
        submitter.submitCustomGeometry(pose, renderType) { p, vc ->
            val mn = INSET
            val mx = EXTENT
            for ((face, rot) in rotByFace) {
                emitFace(p, vc, face, rot, mn, mx, r, g, b, a)
            }
        }
    }

    // Rotate a UV coordinate around (0.5, 0.5) so the visual texture rotates by
    // [rot] × 90° CW on the face, matching the MC model JSON `"rotation"` tag.
    // Visual CW rotation requires rotating each vertex's UV CCW around the center,
    // which is why rot=1 maps (u,v) → (v, 1-u) and rot=3 → (1-v, u).
    private fun rotUv(u: Float, v: Float, rot: Int): Pair<Float, Float> = when (rot and 3) {
        0 -> u to v
        1 -> v to (1f - u)
        2 -> (1f - u) to (1f - v)
        else -> (1f - v) to u
    }

    private fun emitFace(
        p: PoseStack.Pose,
        vc: com.mojang.blaze3d.vertex.VertexConsumer,
        face: Direction,
        rot: Int,
        mn: Float, mx: Float,
        r: Int, g: Int, b: Int, a: Int,
    ) {
        // Each face's vertex/UV/normal data is identical to the corresponding
        // branch in [submit] below, only the UVs are passed through [rotUv] here.
        when (face) {
            Direction.SOUTH -> {
                val (u0, v0) = rotUv(1f, 1f, rot); val (u1, v1) = rotUv(1f, 0f, rot)
                val (u2, v2) = rotUv(0f, 0f, rot); val (u3, v3) = rotUv(0f, 1f, rot)
                vc.addVertex(p, mx, mn, mx).setUv(u0, v0).setColor(r, g, b, a).setOverlay(OVERLAY).setUv2(RenderUtils.FULL_BRIGHT, RenderUtils.FULL_BRIGHT).setNormal(p, 0f, 0f, 1f)
                vc.addVertex(p, mx, mx, mx).setUv(u1, v1).setColor(r, g, b, a).setOverlay(OVERLAY).setUv2(RenderUtils.FULL_BRIGHT, RenderUtils.FULL_BRIGHT).setNormal(p, 0f, 0f, 1f)
                vc.addVertex(p, mn, mx, mx).setUv(u2, v2).setColor(r, g, b, a).setOverlay(OVERLAY).setUv2(RenderUtils.FULL_BRIGHT, RenderUtils.FULL_BRIGHT).setNormal(p, 0f, 0f, 1f)
                vc.addVertex(p, mn, mn, mx).setUv(u3, v3).setColor(r, g, b, a).setOverlay(OVERLAY).setUv2(RenderUtils.FULL_BRIGHT, RenderUtils.FULL_BRIGHT).setNormal(p, 0f, 0f, 1f)
            }
            Direction.NORTH -> {
                val (u0, v0) = rotUv(1f, 1f, rot); val (u1, v1) = rotUv(1f, 0f, rot)
                val (u2, v2) = rotUv(0f, 0f, rot); val (u3, v3) = rotUv(0f, 1f, rot)
                vc.addVertex(p, mn, mn, mn).setUv(u0, v0).setColor(r, g, b, a).setOverlay(OVERLAY).setUv2(RenderUtils.FULL_BRIGHT, RenderUtils.FULL_BRIGHT).setNormal(p, 0f, 0f, -1f)
                vc.addVertex(p, mn, mx, mn).setUv(u1, v1).setColor(r, g, b, a).setOverlay(OVERLAY).setUv2(RenderUtils.FULL_BRIGHT, RenderUtils.FULL_BRIGHT).setNormal(p, 0f, 0f, -1f)
                vc.addVertex(p, mx, mx, mn).setUv(u2, v2).setColor(r, g, b, a).setOverlay(OVERLAY).setUv2(RenderUtils.FULL_BRIGHT, RenderUtils.FULL_BRIGHT).setNormal(p, 0f, 0f, -1f)
                vc.addVertex(p, mx, mn, mn).setUv(u3, v3).setColor(r, g, b, a).setOverlay(OVERLAY).setUv2(RenderUtils.FULL_BRIGHT, RenderUtils.FULL_BRIGHT).setNormal(p, 0f, 0f, -1f)
            }
            Direction.EAST -> {
                val (u0, v0) = rotUv(1f, 1f, rot); val (u1, v1) = rotUv(1f, 0f, rot)
                val (u2, v2) = rotUv(0f, 0f, rot); val (u3, v3) = rotUv(0f, 1f, rot)
                vc.addVertex(p, mx, mn, mn).setUv(u0, v0).setColor(r, g, b, a).setOverlay(OVERLAY).setUv2(RenderUtils.FULL_BRIGHT, RenderUtils.FULL_BRIGHT).setNormal(p, 1f, 0f, 0f)
                vc.addVertex(p, mx, mx, mn).setUv(u1, v1).setColor(r, g, b, a).setOverlay(OVERLAY).setUv2(RenderUtils.FULL_BRIGHT, RenderUtils.FULL_BRIGHT).setNormal(p, 1f, 0f, 0f)
                vc.addVertex(p, mx, mx, mx).setUv(u2, v2).setColor(r, g, b, a).setOverlay(OVERLAY).setUv2(RenderUtils.FULL_BRIGHT, RenderUtils.FULL_BRIGHT).setNormal(p, 1f, 0f, 0f)
                vc.addVertex(p, mx, mn, mx).setUv(u3, v3).setColor(r, g, b, a).setOverlay(OVERLAY).setUv2(RenderUtils.FULL_BRIGHT, RenderUtils.FULL_BRIGHT).setNormal(p, 1f, 0f, 0f)
            }
            Direction.WEST -> {
                val (u0, v0) = rotUv(1f, 1f, rot); val (u1, v1) = rotUv(1f, 0f, rot)
                val (u2, v2) = rotUv(0f, 0f, rot); val (u3, v3) = rotUv(0f, 1f, rot)
                vc.addVertex(p, mn, mn, mx).setUv(u0, v0).setColor(r, g, b, a).setOverlay(OVERLAY).setUv2(RenderUtils.FULL_BRIGHT, RenderUtils.FULL_BRIGHT).setNormal(p, -1f, 0f, 0f)
                vc.addVertex(p, mn, mx, mx).setUv(u1, v1).setColor(r, g, b, a).setOverlay(OVERLAY).setUv2(RenderUtils.FULL_BRIGHT, RenderUtils.FULL_BRIGHT).setNormal(p, -1f, 0f, 0f)
                vc.addVertex(p, mn, mx, mn).setUv(u2, v2).setColor(r, g, b, a).setOverlay(OVERLAY).setUv2(RenderUtils.FULL_BRIGHT, RenderUtils.FULL_BRIGHT).setNormal(p, -1f, 0f, 0f)
                vc.addVertex(p, mn, mn, mn).setUv(u3, v3).setColor(r, g, b, a).setOverlay(OVERLAY).setUv2(RenderUtils.FULL_BRIGHT, RenderUtils.FULL_BRIGHT).setNormal(p, -1f, 0f, 0f)
            }
            Direction.UP -> {
                val (u0, v0) = rotUv(0f, 1f, rot); val (u1, v1) = rotUv(1f, 1f, rot)
                val (u2, v2) = rotUv(1f, 0f, rot); val (u3, v3) = rotUv(0f, 0f, rot)
                vc.addVertex(p, mn, mx, mx).setUv(u0, v0).setColor(r, g, b, a).setOverlay(OVERLAY).setUv2(RenderUtils.FULL_BRIGHT, RenderUtils.FULL_BRIGHT).setNormal(p, 0f, 1f, 0f)
                vc.addVertex(p, mx, mx, mx).setUv(u1, v1).setColor(r, g, b, a).setOverlay(OVERLAY).setUv2(RenderUtils.FULL_BRIGHT, RenderUtils.FULL_BRIGHT).setNormal(p, 0f, 1f, 0f)
                vc.addVertex(p, mx, mx, mn).setUv(u2, v2).setColor(r, g, b, a).setOverlay(OVERLAY).setUv2(RenderUtils.FULL_BRIGHT, RenderUtils.FULL_BRIGHT).setNormal(p, 0f, 1f, 0f)
                vc.addVertex(p, mn, mx, mn).setUv(u3, v3).setColor(r, g, b, a).setOverlay(OVERLAY).setUv2(RenderUtils.FULL_BRIGHT, RenderUtils.FULL_BRIGHT).setNormal(p, 0f, 1f, 0f)
            }
            Direction.DOWN -> {
                val (u0, v0) = rotUv(0f, 0f, rot); val (u1, v1) = rotUv(1f, 0f, rot)
                val (u2, v2) = rotUv(1f, 1f, rot); val (u3, v3) = rotUv(0f, 1f, rot)
                vc.addVertex(p, mn, mn, mn).setUv(u0, v0).setColor(r, g, b, a).setOverlay(OVERLAY).setUv2(RenderUtils.FULL_BRIGHT, RenderUtils.FULL_BRIGHT).setNormal(p, 0f, -1f, 0f)
                vc.addVertex(p, mx, mn, mn).setUv(u1, v1).setColor(r, g, b, a).setOverlay(OVERLAY).setUv2(RenderUtils.FULL_BRIGHT, RenderUtils.FULL_BRIGHT).setNormal(p, 0f, -1f, 0f)
                vc.addVertex(p, mx, mn, mx).setUv(u2, v2).setColor(r, g, b, a).setOverlay(OVERLAY).setUv2(RenderUtils.FULL_BRIGHT, RenderUtils.FULL_BRIGHT).setNormal(p, 0f, -1f, 0f)
                vc.addVertex(p, mn, mn, mx).setUv(u3, v3).setColor(r, g, b, a).setOverlay(OVERLAY).setUv2(RenderUtils.FULL_BRIGHT, RenderUtils.FULL_BRIGHT).setNormal(p, 0f, -1f, 0f)
            }
        }
    }

    /**
     * Emit the selected faces of a 1×1×1 cube centered on the block origin (expanded
     * slightly outward via [INSET]) into [submitter]. Only faces whose bit is set in
     * [faceMask] are emitted, skipping unwanted faces keeps the quad count minimal.
     *
     * Vertex colour is `(r, g, b, a)`, for network-tinted overlays, callers pass the
     * network colour's RGB with full alpha, for plain white overlays,
     * `(255, 255, 255, 255)`.
     */
    fun submit(
        submitter: SubmitNodeCollector,
        pose: PoseStack,
        renderType: RenderType,
        faceMask: Int,
        r: Int, g: Int, b: Int, a: Int,
    ) {
        if (faceMask == 0) return
        val mn = INSET
        val mx = EXTENT
        submitter.submitCustomGeometry(pose, renderType) { p, vc ->
            if ((faceMask and FACE_SOUTH) != 0) {
                // +Z
                vc.addVertex(p, mx, mn, mx).setUv(1f, 1f).setColor(r, g, b, a).setOverlay(OVERLAY).setUv2(RenderUtils.FULL_BRIGHT, RenderUtils.FULL_BRIGHT).setNormal(p, 0f, 0f, 1f)
                vc.addVertex(p, mx, mx, mx).setUv(1f, 0f).setColor(r, g, b, a).setOverlay(OVERLAY).setUv2(RenderUtils.FULL_BRIGHT, RenderUtils.FULL_BRIGHT).setNormal(p, 0f, 0f, 1f)
                vc.addVertex(p, mn, mx, mx).setUv(0f, 0f).setColor(r, g, b, a).setOverlay(OVERLAY).setUv2(RenderUtils.FULL_BRIGHT, RenderUtils.FULL_BRIGHT).setNormal(p, 0f, 0f, 1f)
                vc.addVertex(p, mn, mn, mx).setUv(0f, 1f).setColor(r, g, b, a).setOverlay(OVERLAY).setUv2(RenderUtils.FULL_BRIGHT, RenderUtils.FULL_BRIGHT).setNormal(p, 0f, 0f, 1f)
            }
            if ((faceMask and FACE_NORTH) != 0) {
                // -Z
                vc.addVertex(p, mn, mn, mn).setUv(1f, 1f).setColor(r, g, b, a).setOverlay(OVERLAY).setUv2(RenderUtils.FULL_BRIGHT, RenderUtils.FULL_BRIGHT).setNormal(p, 0f, 0f, -1f)
                vc.addVertex(p, mn, mx, mn).setUv(1f, 0f).setColor(r, g, b, a).setOverlay(OVERLAY).setUv2(RenderUtils.FULL_BRIGHT, RenderUtils.FULL_BRIGHT).setNormal(p, 0f, 0f, -1f)
                vc.addVertex(p, mx, mx, mn).setUv(0f, 0f).setColor(r, g, b, a).setOverlay(OVERLAY).setUv2(RenderUtils.FULL_BRIGHT, RenderUtils.FULL_BRIGHT).setNormal(p, 0f, 0f, -1f)
                vc.addVertex(p, mx, mn, mn).setUv(0f, 1f).setColor(r, g, b, a).setOverlay(OVERLAY).setUv2(RenderUtils.FULL_BRIGHT, RenderUtils.FULL_BRIGHT).setNormal(p, 0f, 0f, -1f)
            }
            if ((faceMask and FACE_EAST) != 0) {
                // +X
                vc.addVertex(p, mx, mn, mn).setUv(1f, 1f).setColor(r, g, b, a).setOverlay(OVERLAY).setUv2(RenderUtils.FULL_BRIGHT, RenderUtils.FULL_BRIGHT).setNormal(p, 1f, 0f, 0f)
                vc.addVertex(p, mx, mx, mn).setUv(1f, 0f).setColor(r, g, b, a).setOverlay(OVERLAY).setUv2(RenderUtils.FULL_BRIGHT, RenderUtils.FULL_BRIGHT).setNormal(p, 1f, 0f, 0f)
                vc.addVertex(p, mx, mx, mx).setUv(0f, 0f).setColor(r, g, b, a).setOverlay(OVERLAY).setUv2(RenderUtils.FULL_BRIGHT, RenderUtils.FULL_BRIGHT).setNormal(p, 1f, 0f, 0f)
                vc.addVertex(p, mx, mn, mx).setUv(0f, 1f).setColor(r, g, b, a).setOverlay(OVERLAY).setUv2(RenderUtils.FULL_BRIGHT, RenderUtils.FULL_BRIGHT).setNormal(p, 1f, 0f, 0f)
            }
            if ((faceMask and FACE_WEST) != 0) {
                // -X
                vc.addVertex(p, mn, mn, mx).setUv(1f, 1f).setColor(r, g, b, a).setOverlay(OVERLAY).setUv2(RenderUtils.FULL_BRIGHT, RenderUtils.FULL_BRIGHT).setNormal(p, -1f, 0f, 0f)
                vc.addVertex(p, mn, mx, mx).setUv(1f, 0f).setColor(r, g, b, a).setOverlay(OVERLAY).setUv2(RenderUtils.FULL_BRIGHT, RenderUtils.FULL_BRIGHT).setNormal(p, -1f, 0f, 0f)
                vc.addVertex(p, mn, mx, mn).setUv(0f, 0f).setColor(r, g, b, a).setOverlay(OVERLAY).setUv2(RenderUtils.FULL_BRIGHT, RenderUtils.FULL_BRIGHT).setNormal(p, -1f, 0f, 0f)
                vc.addVertex(p, mn, mn, mn).setUv(0f, 1f).setColor(r, g, b, a).setOverlay(OVERLAY).setUv2(RenderUtils.FULL_BRIGHT, RenderUtils.FULL_BRIGHT).setNormal(p, -1f, 0f, 0f)
            }
            if ((faceMask and FACE_UP) != 0) {
                // +Y
                vc.addVertex(p, mn, mx, mx).setUv(0f, 1f).setColor(r, g, b, a).setOverlay(OVERLAY).setUv2(RenderUtils.FULL_BRIGHT, RenderUtils.FULL_BRIGHT).setNormal(p, 0f, 1f, 0f)
                vc.addVertex(p, mx, mx, mx).setUv(1f, 1f).setColor(r, g, b, a).setOverlay(OVERLAY).setUv2(RenderUtils.FULL_BRIGHT, RenderUtils.FULL_BRIGHT).setNormal(p, 0f, 1f, 0f)
                vc.addVertex(p, mx, mx, mn).setUv(1f, 0f).setColor(r, g, b, a).setOverlay(OVERLAY).setUv2(RenderUtils.FULL_BRIGHT, RenderUtils.FULL_BRIGHT).setNormal(p, 0f, 1f, 0f)
                vc.addVertex(p, mn, mx, mn).setUv(0f, 0f).setColor(r, g, b, a).setOverlay(OVERLAY).setUv2(RenderUtils.FULL_BRIGHT, RenderUtils.FULL_BRIGHT).setNormal(p, 0f, 1f, 0f)
            }
            if ((faceMask and FACE_DOWN) != 0) {
                // -Y
                vc.addVertex(p, mn, mn, mn).setUv(0f, 0f).setColor(r, g, b, a).setOverlay(OVERLAY).setUv2(RenderUtils.FULL_BRIGHT, RenderUtils.FULL_BRIGHT).setNormal(p, 0f, -1f, 0f)
                vc.addVertex(p, mx, mn, mn).setUv(1f, 0f).setColor(r, g, b, a).setOverlay(OVERLAY).setUv2(RenderUtils.FULL_BRIGHT, RenderUtils.FULL_BRIGHT).setNormal(p, 0f, -1f, 0f)
                vc.addVertex(p, mx, mn, mx).setUv(1f, 1f).setColor(r, g, b, a).setOverlay(OVERLAY).setUv2(RenderUtils.FULL_BRIGHT, RenderUtils.FULL_BRIGHT).setNormal(p, 0f, -1f, 0f)
                vc.addVertex(p, mn, mn, mx).setUv(0f, 1f).setColor(r, g, b, a).setOverlay(OVERLAY).setUv2(RenderUtils.FULL_BRIGHT, RenderUtils.FULL_BRIGHT).setNormal(p, 0f, -1f, 0f)
            }
        }
    }
}
