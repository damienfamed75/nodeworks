package damien.nodeworks.client.model

import damien.nodeworks.client.GrappleBeamAnimState
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart
import net.minecraft.client.renderer.item.ItemModel
import net.minecraft.client.renderer.item.ItemModelResolver
import net.minecraft.client.renderer.item.ItemStackRenderState
import net.minecraft.client.renderer.item.ModelRenderProperties
import net.minecraft.world.entity.ItemOwner
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.item.ItemStack
import org.joml.Matrix4f

/**
 * Composite item model for the Grapple Beam. Emits the baseline staff
 * model on layer 1, then appends the floating cube as layer 2 with a
 * per-frame transform built from [GrappleBeamAnimState] (passive spin,
 * pulse, anchor tracking).
 *
 * The cube layer uses the staff's [ModelRenderProperties] so its
 * display transforms (firstperson_righthand etc.) match, then
 * [setLocalTransform] adds the animation matrix on top. Rotation
 * pivots around the cube's geometric centre in baked /16 space. An
 * extra uniform scale factor [CUBE_VISUAL_SCALE] lets the rendered
 * size be tuned without re-exporting the model.
 */
class GrappleBeamItemModel(
    private val baselineStaff: ItemModel,
    private val cubeProperties: ModelRenderProperties,
    cubePart: BlockStateModelPart,
) : ItemModel {

    /** Cube layer renders through a SpecialModelRenderer so we can pass
     *  FULL_BRIGHT in place of the entity-light value the parent layer
     *  would otherwise propagate, making the cube read as an emissive
     *  energy ball that matches the beam glow. */
    private val cubeRenderer: GrappleBeamCubeSpecialRenderer =
        GrappleBeamCubeSpecialRenderer(cubePart)

    companion object {
        /** Cube centre in /16 baked space. Matches the element's
         *  geometric centroid in Blockbench divided by 16, so the
         *  values land in the same coordinate frame as the baked quads. */
        private const val CUBE_PIVOT_X: Float = 7.95f / 16f
        private const val CUBE_PIVOT_Y: Float = 23.85f / 16f
        private const val CUBE_PIVOT_Z: Float = 8f / 16f

        /** Uniform scale applied to the cube on top of its baked size.
         *  Multiplied with the animation pulse value, so total rendered
         *  scale is `CUBE_VISUAL_SCALE * pulse`. 1.0 = native Blockbench
         *  size, no extra scaling. */
        private const val CUBE_VISUAL_SCALE: Float = 1.0f

        /** Extra Y translation in baked space applied AFTER the
         *  rotate-around-pivot. 0 = cube stays exactly where you placed
         *  it in Blockbench. Positive lifts the cube up. */
        private const val CUBE_LIFT_Y: Float = 0.0f
    }

    override fun update(
        output: ItemStackRenderState,
        item: ItemStack,
        resolver: ItemModelResolver,
        displayContext: ItemDisplayContext,
        level: ClientLevel?,
        owner: ItemOwner?,
        seed: Int,
    ) {
        baselineStaff.update(output, item, resolver, displayContext, level, owner, seed)

        val partial = Minecraft.getInstance().deltaTracker
            .getGameTimeDeltaPartialTick(true)

        val cubeLayer = output.newLayer()
        cubeProperties.applyToLayer(cubeLayer, displayContext)
        cubeLayer.setLocalTransform(buildCubeTransform(partial))
        cubeLayer.setupSpecialModel(cubeRenderer, Unit)

        output.appendModelIdentityElement(this)
    }

    /** Translate-rotate-translate around the cube's baked center, with an
     *  extra uniform scale factor folded into the same pivot so the cube
     *  scales around its center too. */
    private fun buildCubeTransform(partial: Float): Matrix4f {
        val spin = GrappleBeamAnimState.spinAt(partial)
        val pulse = GrappleBeamAnimState.pulseAt(partial)
        val yaw = GrappleBeamAnimState.anchorYawAt(partial)
        val pitch = GrappleBeamAnimState.anchorPitchAt(partial)

        val totalScale = CUBE_VISUAL_SCALE * pulse

        val matrix = Matrix4f()
        // CUBE_LIFT_Y is applied OUTSIDE the rotate/scale sandwich so
        // it shifts the whole transformed cube up by that amount in
        // baked space, independent of the rotation around the cube
        // centre.
        matrix.translate(0f, CUBE_LIFT_Y, 0f)
        matrix.translate(CUBE_PIVOT_X, CUBE_PIVOT_Y, CUBE_PIVOT_Z)
        matrix.rotateY(yaw)
        matrix.rotateX(pitch)
        matrix.rotateY(spin)
        matrix.scale(totalScale, totalScale, totalScale)
        matrix.translate(-CUBE_PIVOT_X, -CUBE_PIVOT_Y, -CUBE_PIVOT_Z)
        return matrix
    }
}
