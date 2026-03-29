package damien.nodeworks.block

import com.mojang.serialization.MapCodec
import damien.nodeworks.block.entity.BroadcastAntennaBlockEntity
import damien.nodeworks.platform.PlatformServices
import damien.nodeworks.screen.BroadcastAntennaMenu
import damien.nodeworks.screen.BroadcastAntennaOpenData
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

class BroadcastAntennaBlock(properties: Properties) : BaseEntityBlock(properties) {

    companion object {
        val CODEC: MapCodec<BroadcastAntennaBlock> = simpleCodec(::BroadcastAntennaBlock)
    }

    override fun codec(): MapCodec<out BaseEntityBlock> = CODEC
    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.MODEL

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity =
        BroadcastAntennaBlockEntity(pos, state)

    override fun useWithoutItem(
        state: BlockState, level: Level, pos: BlockPos,
        player: Player, hitResult: BlockHitResult
    ): InteractionResult {
        if (level.isClientSide) return InteractionResult.SUCCESS
        val entity = level.getBlockEntity(pos) as? BroadcastAntennaBlockEntity ?: return InteractionResult.PASS
        val serverPlayer = player as ServerPlayer

        PlatformServices.menu.openExtendedMenu(
            serverPlayer,
            Component.translatable("container.nodeworks.broadcast_antenna"),
            BroadcastAntennaOpenData(pos),
            BroadcastAntennaOpenData.STREAM_CODEC,
            { syncId, inv, p -> BroadcastAntennaMenu.createServer(syncId, inv, entity, pos) }
        )
        return InteractionResult.SUCCESS
    }

    override fun onRemove(state: BlockState, level: Level, pos: BlockPos, newState: BlockState, movedByPiston: Boolean) {
        if (!state.`is`(newState.block)) {
            Containers.dropContents(level, pos, level.getBlockEntity(pos) as? BroadcastAntennaBlockEntity ?: return)
        }
        super.onRemove(state, level, pos, newState, movedByPiston)
    }
}
