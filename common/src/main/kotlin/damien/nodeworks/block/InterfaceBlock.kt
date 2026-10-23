package damien.nodeworks.block

import com.mojang.serialization.MapCodec
import damien.nodeworks.block.entity.InterfaceBlockEntity
import damien.nodeworks.item.NetworkWrenchItem
import damien.nodeworks.platform.PlatformServices
import damien.nodeworks.screen.InterfaceMenu
import damien.nodeworks.screen.InterfaceOpenData
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult

/** Interface. Presents its network's storage to whatever faces it. See [InterfaceBlockEntity]. */
class InterfaceBlock(properties: Properties) : BaseEntityBlock(properties), Wrenchable {

    companion object {
        val CODEC: MapCodec<InterfaceBlock> = simpleCodec(::InterfaceBlock)
    }

    override fun codec(): MapCodec<out BaseEntityBlock> = CODEC
    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.MODEL

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = InterfaceBlockEntity(pos, state)

    override fun <T : BlockEntity> getTicker(
        level: Level,
        state: BlockState,
        blockEntityType: BlockEntityType<T>,
    ): BlockEntityTicker<T>? = if (level.isClientSide) null else BlockEntityTicker { lvl, _, _, be ->
        if (be is InterfaceBlockEntity && lvl is ServerLevel) be.serverTick(lvl)
    }

    override fun useWithoutItem(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hitResult: BlockHitResult,
    ): InteractionResult {
        if (player.mainHandItem.item is NetworkWrenchItem ||
            player.mainHandItem.item is damien.nodeworks.item.DiagnosticToolItem
        ) return InteractionResult.PASS
        if (level.isClientSide) return InteractionResult.SUCCESS
        val entity = level.getBlockEntity(pos) as? InterfaceBlockEntity ?: return InteractionResult.PASS
        val serverPlayer = player as ServerPlayer
        PlatformServices.menu.openExtendedMenu(
            serverPlayer,
            Component.translatable("container.nodeworks.interface"),
            InterfaceOpenData(pos),
            InterfaceOpenData.STREAM_CODEC,
            { syncId, inv, _ -> InterfaceMenu.createServer(syncId, inv, entity) },
        )
        return InteractionResult.SUCCESS
    }

    override fun playerWillDestroy(level: Level, pos: BlockPos, state: BlockState, player: Player): BlockState {
        // Mark the entity destroyed before super removes it so the network connections
        // are torn down on the destruction path, not the chunk-unload one.
        val entity = level.getBlockEntity(pos) as? InterfaceBlockEntity
        entity?.blockDestroyed = true
        if (entity != null && !level.isClientSide) {
            net.minecraft.world.Containers.dropContents(level, pos, entity)
        }
        return super.playerWillDestroy(level, pos, state, player)
    }
}
