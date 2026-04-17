package damien.nodeworks.block

import com.mojang.serialization.MapCodec
import damien.nodeworks.block.entity.VariableBlockEntity
import damien.nodeworks.item.NetworkWrenchItem
import damien.nodeworks.platform.PlatformServices
import damien.nodeworks.screen.VariableMenu
import damien.nodeworks.screen.VariableOpenData
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
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

class VariableBlock(properties: Properties) : BaseEntityBlock(properties) {

    companion object {
        val CODEC: MapCodec<VariableBlock> = simpleCodec(::VariableBlock)
    }

    override fun codec(): MapCodec<out BaseEntityBlock> = CODEC
    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.MODEL

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity {
        return VariableBlockEntity(pos, state)
    }

    override fun useWithoutItem(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hitResult: BlockHitResult
    ): InteractionResult {
        if (player.mainHandItem.item is NetworkWrenchItem || player.mainHandItem.item is damien.nodeworks.item.DiagnosticToolItem) return InteractionResult.PASS
        if (level.isClientSide) return InteractionResult.SUCCESS

        val entity = level.getBlockEntity(pos) as? VariableBlockEntity ?: return InteractionResult.PASS
        val serverPlayer = player as ServerPlayer

        PlatformServices.menu.openExtendedMenu(
            serverPlayer,
            Component.translatable("container.nodeworks.variable"),
            VariableOpenData(pos, entity.variableName, entity.variableType.ordinal, entity.variableValue),
            VariableOpenData.STREAM_CODEC,
            { syncId, inv, _ -> VariableMenu.createServer(syncId, inv, entity) }
        )

        return InteractionResult.SUCCESS
    }

    override fun playerWillDestroy(level: Level, pos: BlockPos, state: BlockState, player: Player): BlockState {
        val entity = level.getBlockEntity(pos) as? VariableBlockEntity
        entity?.blockDestroyed = true
        return super.playerWillDestroy(level, pos, state, player)
    }
}
