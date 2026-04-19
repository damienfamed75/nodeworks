package damien.nodeworks.block

import com.mojang.serialization.MapCodec
import damien.nodeworks.block.entity.NodeBlockEntity
import damien.nodeworks.item.NetworkWrenchItem
import damien.nodeworks.screen.NodeSideOpenData
import damien.nodeworks.screen.NodeSideScreenHandler
import damien.nodeworks.platform.PlatformServices
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.ContainerLevelAccess
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

class NodeBlock(properties: Properties) : BaseEntityBlock(properties) {

    companion object {
        val CODEC: MapCodec<NodeBlock> = simpleCodec(::NodeBlock)

        // 6x6x6 pixel centered cube (5..11 on each axis)
        val NODE_SHAPE: VoxelShape = Block.box(5.0, 5.0, 5.0, 11.0, 11.0, 11.0)
    }

    override fun codec(): MapCodec<out BaseEntityBlock> = CODEC

    override fun getShape(
        state: BlockState,
        level: BlockGetter,
        pos: BlockPos,
        context: CollisionContext
    ): VoxelShape = NODE_SHAPE

    override fun getCollisionShape(
        state: BlockState,
        level: BlockGetter,
        pos: BlockPos,
        context: CollisionContext
    ): VoxelShape = NODE_SHAPE

    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.MODEL

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity {
        return NodeBlockEntity(pos, state)
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

        val blockEntity = level.getBlockEntity(pos) as? NodeBlockEntity ?: return InteractionResult.PASS
        val side = hitResult.direction

        // Shift+Right Click opens the opposite side
        val openSide = if (player.isCrouching) side.opposite else side
        val serverPlayer = player as ServerPlayer
        val sideName = openSide.name.replaceFirstChar { it.uppercase() }
        PlatformServices.menu.openExtendedMenu(
            serverPlayer,
            Component.translatable("container.nodeworks.node_side", sideName),
            NodeSideOpenData(pos, openSide.ordinal),
            NodeSideOpenData.STREAM_CODEC,
            { syncId, inv, p -> NodeSideScreenHandler(syncId, inv, blockEntity, openSide, pos, ContainerLevelAccess.create(level, pos)) }
        )

        return InteractionResult.SUCCESS
    }

    // --- Redstone emission ---

    override fun isSignalSource(state: BlockState): Boolean = true

    override fun getSignal(state: BlockState, level: BlockGetter, pos: BlockPos, direction: Direction): Int {
        // `direction` is the side of the querying block — the node side is the opposite
        val entity = level.getBlockEntity(pos) as? NodeBlockEntity ?: return 0
        return entity.getRedstoneOutput(direction.opposite)
    }

    override fun getDirectSignal(state: BlockState, level: BlockGetter, pos: BlockPos, direction: Direction): Int {
        return getSignal(state, level, pos, direction)
    }

    override fun playerWillDestroy(level: Level, pos: BlockPos, state: BlockState, player: Player): BlockState {
        val entity = level.getBlockEntity(pos) as? NodeBlockEntity
        if (entity != null) entity.blockDestroyed = true
        return super.playerWillDestroy(level, pos, state, player)
    }
}
