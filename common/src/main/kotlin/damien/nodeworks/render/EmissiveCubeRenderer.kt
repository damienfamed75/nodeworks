package damien.nodeworks.render

import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.rendertype.RenderSetup
import net.minecraft.client.renderer.rendertype.RenderType
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.core.Direction
import net.minecraft.resources.Identifier

/**
 * Shared helper for BERs that render an emissive "glow" cube over the block's base
 * model using the vanilla EYES pipeline (EMISSIVE + NO_CARDINAL_LIGHTING + NO_OVERLAY
 * + TRANSLUCENT blend). Gives every emissive-overlay block the same true fullbright
 * glow you get on mob eyes — unlike a plain `light_emission: 15` on a model face,
 * which just cranks the packed light but still samples the block-atlas shader.
 *
 * Use [renderType] to get a memoized EYES render type for a direct texture path and
 * [submit] to emit the selected faces of the 1×1×1 block with per-face culling.
 *
 * Replaces the old `light_emission: 15` overlay elements on every Nodeworks block
 * model — the BER takes over the emissive element, the JSON model keeps the solid
 * base cube.
 */
object EmissiveCubeRenderer {

    // Face bit-mask. Kept as ints so callers can OR them together cheaply.
    const val FACE_NORTH = 1
    const val FACE_SOUTH = 2
    const val FACE_WEST = 4
    const val FACE_EAST = 8
    const val FACE_UP = 16
    const val FACE_DOWN = 32

    /** All six faces — for blocks whose emissive overlay covered the whole cube (Crafting
     *  Core, Co-Processor, Crafting Storage + overheating variants). */
    const val ALL_FACES = FACE_NORTH or FACE_SOUTH or FACE_WEST or FACE_EAST or FACE_UP or FACE_DOWN

    /** Four horizontal sides only — for blocks whose emissive overlay covered sides but
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
    private const val LIGHT = 15728880
    private val OVERLAY = OverlayTexture.NO_OVERLAY

    private val renderTypeCache = HashMap<Identifier, RenderType>()

    /**
     * Memoized EYES-pipeline [RenderType] that samples [texture] directly (not via the
     * block atlas — the texture is looked up as `assets/<ns>/<path>` at upload time).
     * The same [RenderType] instance is returned for repeat calls with the same texture
     * so the render-queue batches BER submissions across blocks.
     */
    fun renderType(texture: Identifier): RenderType = renderTypeCache.getOrPut(texture) {
        // Include the texture in the name so each cached RenderType gets a unique
        // debug label; the vanilla RenderType.create registry doesn't dedupe by name
        // but the labels make profiler output readable.
        val safe = texture.path.replace('/', '_').replace('.', '_')
        RenderType.create(
            "nodeworks_emissive_${texture.namespace}_${safe}",
            RenderSetup.builder(RenderPipelines.EYES)
                .withTexture("Sampler0", texture)
                .createRenderSetup()
        )
    }

    /**
     * Emit the 4 faces perpendicular to [facing] with each face's UV rotated so the
     * texture's top edge points toward [facing] in world space — the same piston-style
     * convention used by `_side.png` block models (Breaker, Placer). Use this when the
     * underlying JSON model wraps a single side texture around the FACING axis with
     * per-face `"rotation"` tags; the BER's emissive overlay must apply the same UV
     * permutation or the glow won't align with the base texture.
     *
     * The lookup table below maps each (FACING, perpendicular world face) pair to a
     * rotation step (0–3, in 90° CW units). Derived by composing the model's per-face
     * rotation (down=180, up=0, east=90, west=270 at FACING=north) with the blockstate
     * rotation that puts FACING on the front; for vertical FACINGs (UP/DOWN) the model
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
        // layout): UP/DOWN baselines have top at -Z; N/S/E/W baselines have top at
        // +Y. The DOWN face is special — its v-axis runs north→south rather than
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
    // [rot] × 90° CW on the face — matching the MC model JSON `"rotation"` tag.
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
        // branch in [submit] below; only the UVs are passed through [rotUv] here.
        when (face) {
            Direction.SOUTH -> {
                val (u0, v0) = rotUv(1f, 1f, rot); val (u1, v1) = rotUv(1f, 0f, rot)
                val (u2, v2) = rotUv(0f, 0f, rot); val (u3, v3) = rotUv(0f, 1f, rot)
                vc.addVertex(p, mx, mn, mx).setUv(u0, v0).setColor(r, g, b, a).setOverlay(OVERLAY).setUv2(LIGHT, LIGHT).setNormal(p, 0f, 0f, 1f)
                vc.addVertex(p, mx, mx, mx).setUv(u1, v1).setColor(r, g, b, a).setOverlay(OVERLAY).setUv2(LIGHT, LIGHT).setNormal(p, 0f, 0f, 1f)
                vc.addVertex(p, mn, mx, mx).setUv(u2, v2).setColor(r, g, b, a).setOverlay(OVERLAY).setUv2(LIGHT, LIGHT).setNormal(p, 0f, 0f, 1f)
                vc.addVertex(p, mn, mn, mx).setUv(u3, v3).setColor(r, g, b, a).setOverlay(OVERLAY).setUv2(LIGHT, LIGHT).setNormal(p, 0f, 0f, 1f)
            }
            Direction.NORTH -> {
                val (u0, v0) = rotUv(1f, 1f, rot); val (u1, v1) = rotUv(1f, 0f, rot)
                val (u2, v2) = rotUv(0f, 0f, rot); val (u3, v3) = rotUv(0f, 1f, rot)
                vc.addVertex(p, mn, mn, mn).setUv(u0, v0).setColor(r, g, b, a).setOverlay(OVERLAY).setUv2(LIGHT, LIGHT).setNormal(p, 0f, 0f, -1f)
                vc.addVertex(p, mn, mx, mn).setUv(u1, v1).setColor(r, g, b, a).setOverlay(OVERLAY).setUv2(LIGHT, LIGHT).setNormal(p, 0f, 0f, -1f)
                vc.addVertex(p, mx, mx, mn).setUv(u2, v2).setColor(r, g, b, a).setOverlay(OVERLAY).setUv2(LIGHT, LIGHT).setNormal(p, 0f, 0f, -1f)
                vc.addVertex(p, mx, mn, mn).setUv(u3, v3).setColor(r, g, b, a).setOverlay(OVERLAY).setUv2(LIGHT, LIGHT).setNormal(p, 0f, 0f, -1f)
            }
            Direction.EAST -> {
                val (u0, v0) = rotUv(1f, 1f, rot); val (u1, v1) = rotUv(1f, 0f, rot)
                val (u2, v2) = rotUv(0f, 0f, rot); val (u3, v3) = rotUv(0f, 1f, rot)
                vc.addVertex(p, mx, mn, mn).setUv(u0, v0).setColor(r, g, b, a).setOverlay(OVERLAY).setUv2(LIGHT, LIGHT).setNormal(p, 1f, 0f, 0f)
                vc.addVertex(p, mx, mx, mn).setUv(u1, v1).setColor(r, g, b, a).setOverlay(OVERLAY).setUv2(LIGHT, LIGHT).setNormal(p, 1f, 0f, 0f)
                vc.addVertex(p, mx, mx, mx).setUv(u2, v2).setColor(r, g, b, a).setOverlay(OVERLAY).setUv2(LIGHT, LIGHT).setNormal(p, 1f, 0f, 0f)
                vc.addVertex(p, mx, mn, mx).setUv(u3, v3).setColor(r, g, b, a).setOverlay(OVERLAY).setUv2(LIGHT, LIGHT).setNormal(p, 1f, 0f, 0f)
            }
            Direction.WEST -> {
                val (u0, v0) = rotUv(1f, 1f, rot); val (u1, v1) = rotUv(1f, 0f, rot)
                val (u2, v2) = rotUv(0f, 0f, rot); val (u3, v3) = rotUv(0f, 1f, rot)
                vc.addVertex(p, mn, mn, mx).setUv(u0, v0).setColor(r, g, b, a).setOverlay(OVERLAY).setUv2(LIGHT, LIGHT).setNormal(p, -1f, 0f, 0f)
                vc.addVertex(p, mn, mx, mx).setUv(u1, v1).setColor(r, g, b, a).setOverlay(OVERLAY).setUv2(LIGHT, LIGHT).setNormal(p, -1f, 0f, 0f)
                vc.addVertex(p, mn, mx, mn).setUv(u2, v2).setColor(r, g, b, a).setOverlay(OVERLAY).setUv2(LIGHT, LIGHT).setNormal(p, -1f, 0f, 0f)
                vc.addVertex(p, mn, mn, mn).setUv(u3, v3).setColor(r, g, b, a).setOverlay(OVERLAY).setUv2(LIGHT, LIGHT).setNormal(p, -1f, 0f, 0f)
            }
            Direction.UP -> {
                val (u0, v0) = rotUv(0f, 1f, rot); val (u1, v1) = rotUv(1f, 1f, rot)
                val (u2, v2) = rotUv(1f, 0f, rot); val (u3, v3) = rotUv(0f, 0f, rot)
                vc.addVertex(p, mn, mx, mx).setUv(u0, v0).setColor(r, g, b, a).setOverlay(OVERLAY).setUv2(LIGHT, LIGHT).setNormal(p, 0f, 1f, 0f)
                vc.addVertex(p, mx, mx, mx).setUv(u1, v1).setColor(r, g, b, a).setOverlay(OVERLAY).setUv2(LIGHT, LIGHT).setNormal(p, 0f, 1f, 0f)
                vc.addVertex(p, mx, mx, mn).setUv(u2, v2).setColor(r, g, b, a).setOverlay(OVERLAY).setUv2(LIGHT, LIGHT).setNormal(p, 0f, 1f, 0f)
                vc.addVertex(p, mn, mx, mn).setUv(u3, v3).setColor(r, g, b, a).setOverlay(OVERLAY).setUv2(LIGHT, LIGHT).setNormal(p, 0f, 1f, 0f)
            }
            Direction.DOWN -> {
                val (u0, v0) = rotUv(0f, 0f, rot); val (u1, v1) = rotUv(1f, 0f, rot)
                val (u2, v2) = rotUv(1f, 1f, rot); val (u3, v3) = rotUv(0f, 1f, rot)
                vc.addVertex(p, mn, mn, mn).setUv(u0, v0).setColor(r, g, b, a).setOverlay(OVERLAY).setUv2(LIGHT, LIGHT).setNormal(p, 0f, -1f, 0f)
                vc.addVertex(p, mx, mn, mn).setUv(u1, v1).setColor(r, g, b, a).setOverlay(OVERLAY).setUv2(LIGHT, LIGHT).setNormal(p, 0f, -1f, 0f)
                vc.addVertex(p, mx, mn, mx).setUv(u2, v2).setColor(r, g, b, a).setOverlay(OVERLAY).setUv2(LIGHT, LIGHT).setNormal(p, 0f, -1f, 0f)
                vc.addVertex(p, mn, mn, mx).setUv(u3, v3).setColor(r, g, b, a).setOverlay(OVERLAY).setUv2(LIGHT, LIGHT).setNormal(p, 0f, -1f, 0f)
            }
        }
    }

    /**
     * Emit the selected faces of a 1×1×1 cube centered on the block origin (expanded
     * slightly outward via [INSET]) into [submitter]. Only faces whose bit is set in
     * [faceMask] are emitted — skipping unwanted faces keeps the quad count minimal.
     *
     * Vertex colour is `(r, g, b, a)` — for network-tinted overlays, callers pass the
     * network colour's RGB with full alpha; for plain white overlays,
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
                vc.addVertex(p, mx, mn, mx).setUv(1f, 1f).setColor(r, g, b, a).setOverlay(OVERLAY).setUv2(LIGHT, LIGHT).setNormal(p, 0f, 0f, 1f)
                vc.addVertex(p, mx, mx, mx).setUv(1f, 0f).setColor(r, g, b, a).setOverlay(OVERLAY).setUv2(LIGHT, LIGHT).setNormal(p, 0f, 0f, 1f)
                vc.addVertex(p, mn, mx, mx).setUv(0f, 0f).setColor(r, g, b, a).setOverlay(OVERLAY).setUv2(LIGHT, LIGHT).setNormal(p, 0f, 0f, 1f)
                vc.addVertex(p, mn, mn, mx).setUv(0f, 1f).setColor(r, g, b, a).setOverlay(OVERLAY).setUv2(LIGHT, LIGHT).setNormal(p, 0f, 0f, 1f)
            }
            if ((faceMask and FACE_NORTH) != 0) {
                // -Z
                vc.addVertex(p, mn, mn, mn).setUv(1f, 1f).setColor(r, g, b, a).setOverlay(OVERLAY).setUv2(LIGHT, LIGHT).setNormal(p, 0f, 0f, -1f)
                vc.addVertex(p, mn, mx, mn).setUv(1f, 0f).setColor(r, g, b, a).setOverlay(OVERLAY).setUv2(LIGHT, LIGHT).setNormal(p, 0f, 0f, -1f)
                vc.addVertex(p, mx, mx, mn).setUv(0f, 0f).setColor(r, g, b, a).setOverlay(OVERLAY).setUv2(LIGHT, LIGHT).setNormal(p, 0f, 0f, -1f)
                vc.addVertex(p, mx, mn, mn).setUv(0f, 1f).setColor(r, g, b, a).setOverlay(OVERLAY).setUv2(LIGHT, LIGHT).setNormal(p, 0f, 0f, -1f)
            }
            if ((faceMask and FACE_EAST) != 0) {
                // +X
                vc.addVertex(p, mx, mn, mn).setUv(1f, 1f).setColor(r, g, b, a).setOverlay(OVERLAY).setUv2(LIGHT, LIGHT).setNormal(p, 1f, 0f, 0f)
                vc.addVertex(p, mx, mx, mn).setUv(1f, 0f).setColor(r, g, b, a).setOverlay(OVERLAY).setUv2(LIGHT, LIGHT).setNormal(p, 1f, 0f, 0f)
                vc.addVertex(p, mx, mx, mx).setUv(0f, 0f).setColor(r, g, b, a).setOverlay(OVERLAY).setUv2(LIGHT, LIGHT).setNormal(p, 1f, 0f, 0f)
                vc.addVertex(p, mx, mn, mx).setUv(0f, 1f).setColor(r, g, b, a).setOverlay(OVERLAY).setUv2(LIGHT, LIGHT).setNormal(p, 1f, 0f, 0f)
            }
            if ((faceMask and FACE_WEST) != 0) {
                // -X
                vc.addVertex(p, mn, mn, mx).setUv(1f, 1f).setColor(r, g, b, a).setOverlay(OVERLAY).setUv2(LIGHT, LIGHT).setNormal(p, -1f, 0f, 0f)
                vc.addVertex(p, mn, mx, mx).setUv(1f, 0f).setColor(r, g, b, a).setOverlay(OVERLAY).setUv2(LIGHT, LIGHT).setNormal(p, -1f, 0f, 0f)
                vc.addVertex(p, mn, mx, mn).setUv(0f, 0f).setColor(r, g, b, a).setOverlay(OVERLAY).setUv2(LIGHT, LIGHT).setNormal(p, -1f, 0f, 0f)
                vc.addVertex(p, mn, mn, mn).setUv(0f, 1f).setColor(r, g, b, a).setOverlay(OVERLAY).setUv2(LIGHT, LIGHT).setNormal(p, -1f, 0f, 0f)
            }
            if ((faceMask and FACE_UP) != 0) {
                // +Y
                vc.addVertex(p, mn, mx, mx).setUv(0f, 1f).setColor(r, g, b, a).setOverlay(OVERLAY).setUv2(LIGHT, LIGHT).setNormal(p, 0f, 1f, 0f)
                vc.addVertex(p, mx, mx, mx).setUv(1f, 1f).setColor(r, g, b, a).setOverlay(OVERLAY).setUv2(LIGHT, LIGHT).setNormal(p, 0f, 1f, 0f)
                vc.addVertex(p, mx, mx, mn).setUv(1f, 0f).setColor(r, g, b, a).setOverlay(OVERLAY).setUv2(LIGHT, LIGHT).setNormal(p, 0f, 1f, 0f)
                vc.addVertex(p, mn, mx, mn).setUv(0f, 0f).setColor(r, g, b, a).setOverlay(OVERLAY).setUv2(LIGHT, LIGHT).setNormal(p, 0f, 1f, 0f)
            }
            if ((faceMask and FACE_DOWN) != 0) {
                // -Y
                vc.addVertex(p, mn, mn, mn).setUv(0f, 0f).setColor(r, g, b, a).setOverlay(OVERLAY).setUv2(LIGHT, LIGHT).setNormal(p, 0f, -1f, 0f)
                vc.addVertex(p, mx, mn, mn).setUv(1f, 0f).setColor(r, g, b, a).setOverlay(OVERLAY).setUv2(LIGHT, LIGHT).setNormal(p, 0f, -1f, 0f)
                vc.addVertex(p, mx, mn, mx).setUv(1f, 1f).setColor(r, g, b, a).setOverlay(OVERLAY).setUv2(LIGHT, LIGHT).setNormal(p, 0f, -1f, 0f)
                vc.addVertex(p, mn, mn, mx).setUv(0f, 1f).setColor(r, g, b, a).setOverlay(OVERLAY).setUv2(LIGHT, LIGHT).setNormal(p, 0f, -1f, 0f)
            }
        }
    }
}
