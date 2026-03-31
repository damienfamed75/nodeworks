package damien.nodeworks.block

import com.mojang.serialization.MapCodec
import damien.nodeworks.block.entity.ProcessingStorageBlockEntity
import damien.nodeworks.platform.PlatformServices
import damien.nodeworks.screen.ProcessingStorageOpenData
import damien.nodeworks.screen.ProcessingStorageScreenHandler
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.Containers
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult

class ProcessingStorageBlock(properties: Properties) : BaseEntityBlock(properties) {

    companion object {
        val CODEC: MapCodec<ProcessingStorageBlock> = simpleCodec(::ProcessingStorageBlock)
    }

    override fun codec(): MapCodec<out BaseEntityBlock> = CODEC
    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.MODEL

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity {
        return ProcessingStorageBlockEntity(pos, state)
    }

    override fun useWithoutItem(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hitResult: BlockHitResult
    ): InteractionResult {
        if (player.mainHandItem.item is damien.nodeworks.item.NetworkWrenchItem || player.mainHandItem.item is damien.nodeworks.item.DiagnosticToolItem) return InteractionResult.PASS
        if (level.isClientSide) return InteractionResult.SUCCESS

        val blockEntity = level.getBlockEntity(pos) as? ProcessingStorageBlockEntity ?: return InteractionResult.PASS
        val serverPlayer = player as ServerPlayer

        PlatformServices.menu.openExtendedMenu(
            serverPlayer,
            Component.translatable("container.nodeworks.processing_storage"),
            ProcessingStorageOpenData(pos, blockEntity.upgradeLevel),
            ProcessingStorageOpenData.STREAM_CODEC,
            { syncId, inv, p -> ProcessingStorageScreenHandler.createServer(syncId, inv, blockEntity, pos) }
        )

        return InteractionResult.SUCCESS
    }

    override fun onRemove(state: BlockState, level: Level, pos: BlockPos, newState: BlockState, movedByPiston: Boolean) {
        if (!state.`is`(newState.block)) {
            Containers.dropContents(level, pos, level.getBlockEntity(pos) as? ProcessingStorageBlockEntity ?: return)
        }
        super.onRemove(state, level, pos, newState, movedByPiston)
    }

    override fun playerWillDestroy(level: Level, pos: BlockPos, state: BlockState, player: Player): BlockState {
        val entity = level.getBlockEntity(pos) as? ProcessingStorageBlockEntity
        entity?.blockDestroyed = true
        return super.playerWillDestroy(level, pos, state, player)
    }
}
