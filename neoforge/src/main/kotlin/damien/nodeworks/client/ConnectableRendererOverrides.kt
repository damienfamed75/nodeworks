package damien.nodeworks.client

import damien.nodeworks.block.entity.BreakerBlockEntity
import damien.nodeworks.block.entity.CraftingCoreBlockEntity
import damien.nodeworks.block.entity.InstructionStorageBlockEntity
import damien.nodeworks.block.entity.InventoryTerminalBlockEntity
import damien.nodeworks.block.entity.MonitorBlockEntity
import damien.nodeworks.block.entity.NetworkControllerBlockEntity
import damien.nodeworks.block.entity.NodeBlockEntity
import damien.nodeworks.block.entity.PlacerBlockEntity
import damien.nodeworks.block.entity.ProcessingStorageBlockEntity
import damien.nodeworks.block.entity.ReceiverAntennaBlockEntity
import damien.nodeworks.block.entity.TerminalBlockEntity
import damien.nodeworks.block.entity.VariableBlockEntity
import damien.nodeworks.render.BreakerRenderer
import damien.nodeworks.render.ConnectionBeamRenderer
import damien.nodeworks.render.ControllerRenderer
import damien.nodeworks.render.CraftingCoreRenderer
import damien.nodeworks.render.InstructionStorageRenderer
import damien.nodeworks.render.InventoryTerminalRenderer
import damien.nodeworks.render.MonitorRenderer
import damien.nodeworks.render.NodeRenderer
import damien.nodeworks.render.PlacerRenderer
import damien.nodeworks.render.ProcessingStorageRenderer
import damien.nodeworks.render.ReceiverAntennaRenderer
import damien.nodeworks.render.TerminalRenderer
import damien.nodeworks.render.VariableRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.world.phys.AABB

/**
 * NeoForge-only BER subclasses that override [getRenderBoundingBox] to encompass every
 * block this Connectable has a laser link to. The default unit-cube box causes 26.1's
 * frustum culler to drop the BER — and thus the connection beams it submits — the moment
 * the source block leaves the viewport, making long-range lasers vanish whenever the
 * player looks away from either endpoint.
 *
 * The override lives here (not in :common) because [getRenderBoundingBox] comes from
 * `IBlockEntityRendererExtension`, a NeoForge-only extension of `BlockEntityRenderer`;
 * the common module compiles against pure NeoForm and can't see that interface.
 */
class NeoNodeRenderer(ctx: BlockEntityRendererProvider.Context) : NodeRenderer(ctx) {
    override fun getRenderBoundingBox(blockEntity: NodeBlockEntity): AABB =
        ConnectionBeamRenderer.computeBoundingBox(blockEntity)
}

class NeoControllerRenderer(ctx: BlockEntityRendererProvider.Context) : ControllerRenderer(ctx) {
    override fun getRenderBoundingBox(blockEntity: NetworkControllerBlockEntity): AABB =
        ConnectionBeamRenderer.computeBoundingBox(blockEntity)
}

class NeoReceiverAntennaRenderer(ctx: BlockEntityRendererProvider.Context) : ReceiverAntennaRenderer(ctx) {
    override fun getRenderBoundingBox(blockEntity: ReceiverAntennaBlockEntity): AABB =
        ConnectionBeamRenderer.computeBoundingBox(blockEntity)
}

class NeoMonitorRenderer(ctx: BlockEntityRendererProvider.Context) : MonitorRenderer(ctx) {
    override fun getRenderBoundingBox(blockEntity: MonitorBlockEntity): AABB =
        ConnectionBeamRenderer.computeBoundingBox(blockEntity)
}

class NeoVariableRenderer(ctx: BlockEntityRendererProvider.Context) : VariableRenderer(ctx) {
    override fun getRenderBoundingBox(blockEntity: VariableBlockEntity): AABB =
        ConnectionBeamRenderer.computeBoundingBox(blockEntity)
}

class NeoTerminalRenderer(ctx: BlockEntityRendererProvider.Context) : TerminalRenderer(ctx) {
    override fun getRenderBoundingBox(blockEntity: TerminalBlockEntity): AABB =
        ConnectionBeamRenderer.computeBoundingBox(blockEntity)
}

class NeoProcessingStorageRenderer(ctx: BlockEntityRendererProvider.Context) : ProcessingStorageRenderer(ctx) {
    override fun getRenderBoundingBox(blockEntity: ProcessingStorageBlockEntity): AABB =
        ConnectionBeamRenderer.computeBoundingBox(blockEntity)
}

class NeoInstructionStorageRenderer(ctx: BlockEntityRendererProvider.Context) : InstructionStorageRenderer(ctx) {
    override fun getRenderBoundingBox(blockEntity: InstructionStorageBlockEntity): AABB =
        ConnectionBeamRenderer.computeBoundingBox(blockEntity)
}

class NeoCraftingCoreRenderer(ctx: BlockEntityRendererProvider.Context) : CraftingCoreRenderer(ctx) {
    override fun getRenderBoundingBox(blockEntity: CraftingCoreBlockEntity): AABB =
        ConnectionBeamRenderer.computeBoundingBox(blockEntity)
}

class NeoInventoryTerminalRenderer(ctx: BlockEntityRendererProvider.Context) : InventoryTerminalRenderer(ctx) {
    override fun getRenderBoundingBox(blockEntity: InventoryTerminalBlockEntity): AABB =
        ConnectionBeamRenderer.computeBoundingBox(blockEntity)
}

class NeoBreakerRenderer(ctx: BlockEntityRendererProvider.Context) : BreakerRenderer(ctx) {
    override fun getRenderBoundingBox(blockEntity: BreakerBlockEntity): AABB =
        ConnectionBeamRenderer.computeBoundingBox(blockEntity)
}

class NeoPlacerRenderer(ctx: BlockEntityRendererProvider.Context) : PlacerRenderer(ctx) {
    override fun getRenderBoundingBox(blockEntity: PlacerBlockEntity): AABB =
        ConnectionBeamRenderer.computeBoundingBox(blockEntity)
}
