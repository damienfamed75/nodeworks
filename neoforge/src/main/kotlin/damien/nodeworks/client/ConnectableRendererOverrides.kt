package damien.nodeworks.client

import damien.nodeworks.block.entity.NodeBlockEntity
import damien.nodeworks.render.FocusBeamRenderer
import damien.nodeworks.render.NodeRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.world.phys.AABB

/**
 * NeoForge-only BER override of `getRenderBoundingBox(BE)` so the frustum
 * culler keeps the BER alive whenever any of its laser links is on screen.
 * The default unit-cube box drops the BER (and its beams) the moment the
 * source block leaves the viewport.
 *
 * Scoped to Focus Nodes only because every other Connectable has an empty
 * connection set, so [FocusBeamRenderer.computeBoundingBox] would collapse
 * to a unit cube anyway. Lives in `:neoforge` because `getRenderBoundingBox`
 * comes from `IBlockEntityRendererExtension`, a NeoForge-only interface.
 */
class FocusNodeRenderer(ctx: BlockEntityRendererProvider.Context) : NodeRenderer(ctx) {
    override fun getRenderBoundingBox(blockEntity: NodeBlockEntity): AABB =
        FocusBeamRenderer.computeBoundingBox(blockEntity)
}
