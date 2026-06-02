package damien.nodeworks.client

import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.player.LocalPlayer
import net.minecraft.world.entity.HumanoidArm
import net.minecraft.world.item.ItemStack
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions
import org.joml.Matrix4f

/**
 * Per-frame transform hook for the Grapple Beam item in first-person.
 * Reads the lerped animation values from [GrappleBeamAnimState] and
 * applies them as PoseStack adjustments on top of the vanilla hand
 * transform. Returns `false` so vanilla's bobbing / swing / equip
 * matrices still run, our adjustments become a rest pose the engine
 * then animates further.
 *
 * Three transforms:
 *  - **Tilt around X**: the staff visually pitches toward the anchor.
 *  - **Forward translation along Z**: extends/retracts the item.
 *  - **Uniform scale**: subtle scale-up while grappling.
 *
 * Third-person renders unaffected, the body's swing arm already reads
 * as "holding the grapple" without extra geometry tweaking.
 */
object GrappleBeamClientExtensions : IClientItemExtensions {

    override fun applyForgeHandTransform(
        poseStack: PoseStack,
        player: LocalPlayer,
        arm: HumanoidArm,
        itemInHand: ItemStack,
        partialTick: Float,
        equipProcess: Float,
        swingProcess: Float,
    ): Boolean {
        val extension = GrappleBeamAnimState.extensionAt(partialTick)
        val tilt = GrappleBeamAnimState.tiltAt(partialTick)
        val scale = GrappleBeamAnimState.scaleAt(partialTick)

        // Mirror tilt sign for the offhand so both hands lean the same
        // direction toward the anchor instead of one tipping the wrong way.
        val signedTilt = if (arm == HumanoidArm.LEFT) -tilt else tilt

        // Pivot near the wrist before tilting so the staff swings around
        // a believable point instead of orbiting its center.
        poseStack.translate(0.0f, 0.05f, -0.05f)
        poseStack.mulPose(com.mojang.math.Axis.XP.rotation(signedTilt))
        poseStack.translate(0.0f, -0.05f, 0.05f - extension)
        poseStack.scale(scale, scale, scale)

        // Capture the cube's view-space centre so the beam renderer
        // can anchor the start of the beam exactly at the rendered
        // cube, tracking it through camera rotation/bob without a
        // formula approximation. See GrappleBeamAnimState.captureFocusPos.
        GrappleBeamAnimState.captureFocusPos(
            Matrix4f(poseStack.last().pose()),
            arm == HumanoidArm.LEFT,
            equipProcess,
            partialTick,
        )

        return false
    }
}
