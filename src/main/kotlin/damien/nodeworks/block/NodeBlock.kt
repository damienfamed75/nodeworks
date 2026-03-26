package damien.nodeworks.block

import com.mojang.serialization.MapCodec
import damien.nodeworks.block.entity.NodeBlockEntity
import damien.nodeworks.item.NetworkWrenchItem
import damien.nodeworks.screen.NodeSideScreenHandler
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerFactory
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
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

        // 6x6x6 pixel centered cube (5..11 on each axis out of 16)
        // Small enough to easily click individual faces, like LaserIO nodes
        val SHAPE: VoxelShape = Block.box(5.0, 5.0, 5.0, 11.0, 11.0, 11.0)
    }

    override fun codec(): MapCodec<out BaseEntityBlock> = CODEC

    override fun getShape(
        state: BlockState,
        level: BlockGetter,
        pos: BlockPos,
        context: CollisionContext
    ): VoxelShape = SHAPE

    override fun getCollisionShape(
        state: BlockState,
        level: BlockGetter,
        pos: BlockPos,
        context: CollisionContext
    ): VoxelShape = SHAPE

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
        // Don't open GUI if player is holding the wrench (wrench handles its own interaction)
        if (player.mainHandItem.item is NetworkWrenchItem) return InteractionResult.PASS

        if (level.isClientSide) return InteractionResult.SUCCESS

        val blockEntity = level.getBlockEntity(pos) as? NodeBlockEntity ?: return InteractionResult.PASS
        val side = hitResult.direction
        val serverPlayer = player as ServerPlayer

        serverPlayer.openMenu(object : ExtendedScreenHandlerFactory<Int> {
            override fun getScreenOpeningData(player: ServerPlayer): Int = side.ordinal

            override fun getDisplayName(): Component {
                val sideName = side.name.replaceFirstChar { it.uppercase() }
                return Component.translatable("container.nodeworks.node_side", sideName)
            }

            override fun createMenu(syncId: Int, playerInventory: Inventory, player: Player): AbstractContainerMenu {
                return NodeSideScreenHandler(
                    syncId, playerInventory, blockEntity, side,
                    ContainerLevelAccess.create(level, pos)
                )
            }
        })

        return InteractionResult.SUCCESS
    }

    override fun playerWillDestroy(level: Level, pos: BlockPos, state: BlockState, player: Player): BlockState {
        // Mark the block entity so setRemoved knows this is a real destruction, not a chunk unload
        val entity = level.getBlockEntity(pos) as? NodeBlockEntity
        entity?.blockDestroyed = true
        return super.playerWillDestroy(level, pos, state, player)
    }
}
