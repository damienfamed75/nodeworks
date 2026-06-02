package damien.nodeworks.client.model

import com.mojang.blaze3d.vertex.PoseStack
import damien.nodeworks.render.EmissiveCubeRenderer
import damien.nodeworks.render.RenderUtils
import net.minecraft.client.model.geom.builders.UVPair
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart
import net.minecraft.client.renderer.special.SpecialModelRenderer
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.client.resources.model.geometry.BakedQuad
import net.minecraft.core.Direction
import net.minecraft.world.item.ItemStack
import org.joml.Vector3f
import org.joml.Vector3fc
import java.util.function.Consumer

/**
 * Renders the Grapple Beam cube layer through a translucent-emissive
 * pipeline (see [EmissiveCubeRenderer.BLOCK_ATLAS_TRANSLUCENT_RENDER_TYPE])
 * that pins both the lightmap (EMISSIVE define) and the per-face
 * directional shading (NO_CARDINAL_LIGHTING define) regardless of
 * model `"shade"` flags or ambient light. The cube reads as a
 * uniformly bright textured surface that still respects texture alpha.
 *
 * Read by [ItemStackRenderState.LayerRenderState.submit] via
 * `setupSpecialModel`, which applies the layer's display + local
 * transforms to the pose stack before calling [submit] so the cube
 * still rotates / pulses / pivots as configured in
 * [GrappleBeamItemModel.buildCubeTransform].
 */
class GrappleBeamCubeSpecialRenderer(
    cubePart: BlockStateModelPart,
) : SpecialModelRenderer<Unit> {

    companion object {
        private val FACE_DIRECTIONS: Array<Direction?> = arrayOf(
            Direction.DOWN, Direction.UP, Direction.NORTH,
            Direction.SOUTH, Direction.WEST, Direction.EAST, null,
        )

        /** Bounding-box corners in baked /16 space. Matches the outer
         *  cube element from models/item/grapple_beam_cube.json. */
        private val EXTENT_MIN: Vector3fc = Vector3f(6.25f / 16f, 22.15f / 16f, 6.3f / 16f)
        private val EXTENT_MAX: Vector3fc = Vector3f(9.65f / 16f, 25.55f / 16f, 9.7f / 16f)
    }

    private val cubeQuads: List<BakedQuad> = buildList {
        for (dir in FACE_DIRECTIONS) {
            addAll(cubePart.getQuads(dir))
        }
    }

    override fun submit(
        argument: Unit?,
        poseStack: PoseStack,
        submitNodeCollector: SubmitNodeCollector,
        lightCoords: Int,
        overlayCoords: Int,
        hasFoil: Boolean,
        outlineColor: Int,
    ) {
        submitNodeCollector.submitCustomGeometry(
            poseStack,
            EmissiveCubeRenderer.BLOCK_ATLAS_TRANSLUCENT_RENDER_TYPE,
        ) { pose, vc ->
            for (quad in cubeQuads) {
                val n = quad.direction().unitVec3f
                for (i in 0 until BakedQuad.VERTEX_COUNT) {
                    val p = quad.position(i)
                    val packed = quad.packedUV(i)
                    vc.addVertex(pose, p.x(), p.y(), p.z())
                        .setUv(UVPair.unpackU(packed), UVPair.unpackV(packed))
                        .setColor(255, 255, 255, 255)
                        .setOverlay(OverlayTexture.NO_OVERLAY)
                        .setUv2(RenderUtils.FULL_BRIGHT, RenderUtils.FULL_BRIGHT)
                        .setNormal(pose, n.x(), n.y(), n.z())
                }
            }
        }
    }

    override fun getExtents(output: Consumer<Vector3fc>) {
        output.accept(EXTENT_MIN)
        output.accept(EXTENT_MAX)
    }

    override fun extractArgument(stack: ItemStack): Unit = Unit
}
