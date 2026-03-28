package damien.nodeworks.block

import com.mojang.serialization.MapCodec
import damien.nodeworks.block.entity.InstructionStorageBlockEntity
import damien.nodeworks.platform.PlatformServices
import damien.nodeworks.screen.InstructionStorageOpenData
import damien.nodeworks.screen.InstructionStorageScreenHandler
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
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

class InstructionStorageBlock(properties: Properties) : BaseEntityBlock(properties) {

    companion object {
        val CODEC: MapCodec<InstructionStorageBlock> = simpleCodec(::InstructionStorageBlock)
    }

    override fun codec(): MapCodec<out BaseEntityBlock> = CODEC
    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.MODEL

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity {
        return InstructionStorageBlockEntity(pos, state)
    }

    override fun useWithoutItem(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hitResult: BlockHitResult
    ): InteractionResult {
        if (level.isClientSide) return InteractionResult.SUCCESS

        val blockEntity = level.getBlockEntity(pos) as? InstructionStorageBlockEntity ?: return InteractionResult.PASS
        val serverPlayer = player as ServerPlayer

        PlatformServices.menu.openExtendedMenu(
            serverPlayer,
            Component.translatable("container.nodeworks.instruction_storage"),
            InstructionStorageOpenData(pos, blockEntity.upgradeLevel),
            InstructionStorageOpenData.STREAM_CODEC,
            { syncId, inv, p -> InstructionStorageScreenHandler.createServer(syncId, inv, blockEntity, pos) }
        )

        return InteractionResult.SUCCESS
    }

    override fun affectNeighborsAfterRemoval(state: BlockState, level: ServerLevel, pos: BlockPos, movedByPiston: Boolean) {
        Containers.dropContents(level, pos, level.getBlockEntity(pos) as? InstructionStorageBlockEntity ?: return)
    }
}
