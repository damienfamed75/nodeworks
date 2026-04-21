package damien.nodeworks.render

import com.mojang.blaze3d.vertex.PoseStack
import damien.nodeworks.block.MonitorBlock
import damien.nodeworks.block.entity.MonitorBlockEntity
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.renderer.feature.ModelFeatureRenderer
import net.minecraft.client.renderer.item.ItemModelResolver
import net.minecraft.client.renderer.item.ItemStackRenderState
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.Vec3
import org.joml.Quaternionf

/**
 * Block Entity renderer for the standalone Monitor block.
 *
 *  1. Emissive front face — tinted with the network colour, same treatment as the
 *     Terminal. Drawn via the shared [EmissiveCubeRenderer] so the pipeline matches
 *     every other emissive block in the mod. Only the facing direction's face is
 *     emitted.
 *
 *  2. Tracked item icon — a scaled-down 3D item render centered on the front face,
 *     sticking out ~2 px. Resolved on the main thread via [ItemModelResolver] so the
 *     submit pass doesn't touch the registry.
 *
 *  The displayed count text is drawn by [NodeConnectionRenderer.renderMonitorText]
 *  (world-space, needs the bufferSource + pose stack context from the level render
 *  pass — easier to share that hook than to re-wire a font-in-BER path).
 */
open class MonitorRenderer(context: BlockEntityRendererProvider.Context) :
    ConnectableBER<MonitorBlockEntity, MonitorRenderer.MonitorState>(context) {

    private val itemModelResolver: ItemModelResolver = context.itemModelResolver()

    class MonitorState : ConnectableRenderState() {
        var facing: Direction = Direction.NORTH
        var color: Int = NodeConnectionRenderer.DEFAULT_NETWORK_COLOR
        val itemRS: ItemStackRenderState = ItemStackRenderState()
        var hasItem: Boolean = false
    }

    companion object {
        private val FRONT_EMISSIVE_TEXTURE = Identifier.fromNamespaceAndPath("nodeworks", "textures/block/monitor_front_emissive.png")
        private val BACK_EMISSIVE_TEXTURE = Identifier.fromNamespaceAndPath("nodeworks", "textures/block/monitor_back_emissive.png")
        private val FRONT_RENDER_TYPE = EmissiveCubeRenderer.renderType(FRONT_EMISSIVE_TEXTURE)
        private val BACK_RENDER_TYPE = EmissiveCubeRenderer.renderType(BACK_EMISSIVE_TEXTURE)
    }

    override fun createRenderState(): MonitorState = MonitorState()

    override fun extractConnectable(
        blockEntity: MonitorBlockEntity,
        state: MonitorState,
        partialTicks: Float,
        cameraPosition: Vec3,
        breakProgress: ModelFeatureRenderer.CrumblingOverlay?,
    ) {
        state.facing = blockEntity.blockState.getValue(MonitorBlock.FACING)
        state.color = resolveNetworkColor(blockEntity)

        // Resolve the item icon on the main thread. Submit pass reads the cached
        // ItemStackRenderState only.
        val itemId = blockEntity.trackedItemId
        if (itemId != null) {
            val ident = Identifier.tryParse(itemId)
            val item = if (ident != null) BuiltInRegistries.ITEM.getValue(ident) else null
            if (item != null) {
                itemModelResolver.updateForTopItem(
                    state.itemRS,
                    ItemStack(item, 1),
                    ItemDisplayContext.GUI,
                    blockEntity.level,
                    null,
                    0
                )
                state.hasItem = true
            } else {
                state.hasItem = false
            }
        } else {
            state.hasItem = false
        }
    }

    override fun submitConnectable(
        state: MonitorState,
        poseStack: PoseStack,
        submitNodeCollector: SubmitNodeCollector,
        camera: CameraRenderState,
    ) {
        // Emissive front + back faces — both tinted with the network colour. Separate
        // render types because each face has its own texture (the back design usually
        // differs from the front screen).
        val r = (state.color shr 16) and 0xFF
        val g = (state.color shr 8) and 0xFF
        val b = state.color and 0xFF
        EmissiveCubeRenderer.submit(
            submitNodeCollector, poseStack, FRONT_RENDER_TYPE,
            EmissiveCubeRenderer.faceOf(state.facing), r, g, b, 255
        )
        EmissiveCubeRenderer.submit(
            submitNodeCollector, poseStack, BACK_RENDER_TYPE,
            EmissiveCubeRenderer.faceOf(state.facing.opposite), r, g, b, 255
        )

        // Item icon centered on the front face. Pose sequence mirrors the old
        // node-monitor renderer that was visually working:
        //   translate(block centre) → rotate to face → translate to front face
        //   (local +Z) → flip Z (items render facing +Z in GUI, which after flip
        //   becomes world-outward) → small translate in post-flip -Z (= OUTWARD in
        //   world) to avoid z-fighting with the emissive layer → scale down.
        if (state.hasItem) {
            poseStack.pushPose()
            poseStack.translate(0.5, 0.5, 0.5)
            rotateToFace(poseStack, state.facing)
            poseStack.translate(0.0, 0.0, 0.5)
            poseStack.scale(1f, 1f, -1f)
            // +Y shifts the icon up 1 pixel (1/16 block) so the count text below sits
            // cleanly under it. The -Z offset (post-flip) pushes the icon outward so
            // it doesn't z-fight with the emissive layer.
            poseStack.translate(0.0, 1.0 / 16.0, -0.03)
            poseStack.scale(0.42f, 0.42f, 0.001f)
            state.itemRS.submit(poseStack, submitNodeCollector, 0xF000F0, OverlayTexture.NO_OVERLAY, 0)
            poseStack.popPose()
        }
    }

    private fun rotateToFace(poseStack: PoseStack, face: Direction) {
        // Matches the pose convention in the old MonitorRenderer: SOUTH is the identity,
        // other directions rotate accordingly.
        when (face) {
            Direction.SOUTH -> Unit
            Direction.NORTH -> poseStack.mulPose(Quaternionf().rotateY(Math.PI.toFloat()))
            Direction.EAST  -> poseStack.mulPose(Quaternionf().rotateY((Math.PI / 2).toFloat()))
            Direction.WEST  -> poseStack.mulPose(Quaternionf().rotateY((-Math.PI / 2).toFloat()))
            else -> Unit
        }
    }
}
