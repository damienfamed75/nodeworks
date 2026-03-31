package damien.nodeworks.block

import com.mojang.serialization.MapCodec
import damien.nodeworks.block.entity.NetworkControllerBlockEntity
import damien.nodeworks.item.NetworkWrenchItem
import damien.nodeworks.platform.PlatformServices
import damien.nodeworks.screen.NetworkControllerMenu
import damien.nodeworks.screen.NetworkControllerOpenData
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.VoxelShape

/**
 * Network Controller — the required heart of every network.
 * Generates a UUID on placement that defines the network's identity.
 * Connects to the network via lasers. One per network.
 * Breaking this block takes the entire network offline.
 */
class NetworkControllerBlock(properties: Properties) : BaseEntityBlock(properties) {

    companion object {
        val CODEC: MapCodec<NetworkControllerBlock> = simpleCodec(::NetworkControllerBlock)

        val SHAPE: VoxelShape = Block.box(0.0, 0.0, 0.0, 16.0, 16.0, 16.0)
    }

    override fun codec(): MapCodec<out BaseEntityBlock> = CODEC
    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.MODEL

    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape = SHAPE
    override fun getCollisionShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape = SHAPE

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity {
        return NetworkControllerBlockEntity(pos, state)
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

        val entity = level.getBlockEntity(pos) as? NetworkControllerBlockEntity ?: return InteractionResult.PASS
        val serverPlayer = player as ServerPlayer

        PlatformServices.menu.openExtendedMenu(
            serverPlayer,
            Component.translatable("container.nodeworks.network_controller"),
            NetworkControllerOpenData(pos, entity.networkColor, entity.networkName, entity.redstoneMode, entity.nodeGlowStyle),
            NetworkControllerOpenData.STREAM_CODEC,
            { syncId, inv, _ -> NetworkControllerMenu.createServer(syncId, inv, entity) }
        )

        return InteractionResult.SUCCESS
    }

    override fun playerWillDestroy(level: Level, pos: BlockPos, state: BlockState, player: Player): BlockState {
        val entity = level.getBlockEntity(pos) as? NetworkControllerBlockEntity
        entity?.blockDestroyed = true
        return super.playerWillDestroy(level, pos, state, player)
    }
}
