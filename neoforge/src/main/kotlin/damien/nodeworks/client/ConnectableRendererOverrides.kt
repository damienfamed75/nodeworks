package damien.nodeworks.client

import damien.nodeworks.block.entity.NodeBlockEntity
import damien.nodeworks.render.FocusBeamRenderer
import damien.nodeworks.render.NodeRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.world.phys.AABB

/**
 * NeoForge-only BER subclasses that override `getRenderBoundingBox(BE)` to
 * encompass every block this Connectable has a laser link to. The default
 * unit-cube box causes the frustum culler to drop the BER — and thus the
 * connection beams it submits — the moment the source block leaves the
 * viewport, making long-range lasers vanish whenever the player looks away
 * from the source endpoint.
 *
 * The override lives here (not in `:common`) because `getRenderBoundingBox`
 * comes from `IBlockEntityRendererExtension`, a NeoForge-only extension of
 * `BlockEntityRenderer`; `:common` compiles against pure NeoForm and can't
 * see that interface.
 *
 * Scoped to the Focus Node renderer only — regular Nodes / Pipes / etc. have
 * empty `getConnections()` so [FocusBeamRenderer.computeBoundingBox] would
 * collapse to a unit cube anyway. No reason to wrap their renderers.
 */
class FocusNodeRenderer(ctx: BlockEntityRendererProvider.Context) : NodeRenderer(ctx) {
    override fun getRenderBoundingBox(blockEntity: NodeBlockEntity): AABB =
        FocusBeamRenderer.computeBoundingBox(blockEntity)
}
