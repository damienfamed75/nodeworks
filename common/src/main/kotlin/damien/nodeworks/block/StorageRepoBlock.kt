package damien.nodeworks.block

import com.mojang.serialization.MapCodec
import damien.nodeworks.block.entity.StorageRepoBlockEntity
import damien.nodeworks.block.repo.StorageRepoTier
import damien.nodeworks.platform.PlatformServices
import damien.nodeworks.screen.StorageRepoMenu
import damien.nodeworks.screen.StorageRepoOpenData
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult

/**
 * One block instance per [StorageRepoTier]. Cluster BFS in
 * [StorageRepoBlockEntity.walkCluster] keys on block identity, so different tiers
 * placed face-to-face stay as independent silos.
 */
class StorageRepoBlock(
    val tier: StorageRepoTier,
    properties: Properties,
) : BaseEntityBlock(properties), Wrenchable {

    private val codec: MapCodec<StorageRepoBlock> = MapCodec.unit(this)

    override fun codec(): MapCodec<out BaseEntityBlock> = codec

    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.MODEL

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity {
        return StorageRepoBlockEntity(tier, pos, state)
    }

    override fun useWithoutItem(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hitResult: BlockHitResult,
    ): InteractionResult {
        val heldItem = player.mainHandItem.item
        // Pass through wrench / diagnostic interactions — same convention as other
        // Connectable blocks in the mod.
        if (heldItem is damien.nodeworks.item.NetworkWrenchItem ||
            heldItem is damien.nodeworks.item.DiagnosticToolItem
        ) return InteractionResult.PASS
        // Hold a Storage Repo (any tier) → fall through to BlockItem.useOn so the
        // new block places. Without this, useWithoutItem's SUCCESS consumes the
        // action and players can't stack Repos by right-clicking neighbours.
        if (heldItem is net.minecraft.world.item.BlockItem && heldItem.block is StorageRepoBlock) {
            return InteractionResult.PASS
        }
        if (level.isClientSide) return InteractionResult.SUCCESS

        val be = level.getBlockEntity(pos) as? StorageRepoBlockEntity ?: return InteractionResult.PASS
        val anchor = be.getAnchor() ?: be
        val serverPlayer = player as ServerPlayer
        val openData = StorageRepoOpenData(
            pos = pos,
            filterMode = anchor.filterMode.ordinal,
            stackability = anchor.stackability.ordinal,
            nbtFilter = anchor.nbtFilter.ordinal,
            priority = anchor.priority,
            channelId = anchor.channel.id,
            filterRules = anchor.filterRules,
        )
        PlatformServices.menu.openExtendedMenu(
            serverPlayer,
            Component.translatable("container.nodeworks.storage_repo"),
            openData,
            StorageRepoOpenData.STREAM_CODEC,
            { syncId, inv, _ -> StorageRepoMenu(syncId, inv, pos) },
        )
        return InteractionResult.CONSUME
    }

    override fun affectNeighborsAfterRemoval(
        state: BlockState,
        level: ServerLevel,
        pos: BlockPos,
        movedByPiston: Boolean,
    ) {
        val be = level.getBlockEntity(pos) as? StorageRepoBlockEntity
        if (be != null) {
            net.minecraft.world.Containers.dropContents(level, pos, be)
        }
        super.affectNeighborsAfterRemoval(state, level, pos, movedByPiston)
    }

    override fun playerWillDestroy(level: Level, pos: BlockPos, state: BlockState, player: Player): BlockState {
        val entity = level.getBlockEntity(pos) as? StorageRepoBlockEntity
        entity?.blockDestroyed = true
        // No explicit neighbour invalidation here — the BE's setRemoved (called after
        // this hook returns and the block is gone) bumps the global cluster epoch,
        // which forces every other cached cluster to recompute on next read.
        return super.playerWillDestroy(level, pos, state, player)
    }

    /** Analog comparator output: fraction of the tier's slot capacity used,
     *  scaled to 0..15. Empty → 0, any filled slot → 1+, full → 15. Per-block
     *  (not cluster-wide) so a comparator next to one Repo reads that block's
     *  fill level. */
    override fun hasAnalogOutputSignal(state: BlockState): Boolean = true

    override fun getAnalogOutputSignal(state: BlockState, level: Level, pos: BlockPos, direction: Direction): Int {
        val be = level.getBlockEntity(pos) as? StorageRepoBlockEntity ?: return 0
        return net.minecraft.world.inventory.AbstractContainerMenu.getRedstoneSignalFromContainer(be)
    }
}
