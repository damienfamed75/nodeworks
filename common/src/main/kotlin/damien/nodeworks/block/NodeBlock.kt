package damien.nodeworks.block

import com.mojang.serialization.MapCodec
import damien.nodeworks.block.entity.NodeBlockEntity
import damien.nodeworks.item.MonitorItem
import damien.nodeworks.item.NetworkWrenchItem
import damien.nodeworks.registry.ModItems
import damien.nodeworks.screen.NodeSideOpenData
import damien.nodeworks.screen.NodeSideScreenHandler
import damien.nodeworks.platform.PlatformServices
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.Containers
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.ContainerLevelAccess
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape

class NodeBlock(properties: Properties) : BaseEntityBlock(properties) {

    companion object {
        val CODEC: MapCodec<NodeBlock> = simpleCodec(::NodeBlock)

        // 6x6x6 pixel centered cube (5..11 on each axis)
        val NODE_SHAPE: VoxelShape = Block.box(5.0, 5.0, 5.0, 11.0, 11.0, 11.0)

        // Monitor panel shapes per face (2 pixels thick, extends from node to block edge)
        val MONITOR_SHAPES: Map<Direction, VoxelShape> = mapOf(
            Direction.NORTH to Block.box(2.0, 2.0, 0.0, 14.0, 14.0, 2.0),
            Direction.SOUTH to Block.box(2.0, 2.0, 14.0, 14.0, 14.0, 16.0),
            Direction.WEST  to Block.box(0.0, 2.0, 2.0, 2.0, 14.0, 14.0),
            Direction.EAST  to Block.box(14.0, 2.0, 2.0, 16.0, 14.0, 14.0),
            Direction.DOWN  to Block.box(2.0, 0.0, 2.0, 14.0, 2.0, 14.0),
            Direction.UP    to Block.box(2.0, 14.0, 2.0, 14.0, 16.0, 14.0)
        )
    }

    override fun codec(): MapCodec<out BaseEntityBlock> = CODEC

    override fun getShape(
        state: BlockState,
        level: BlockGetter,
        pos: BlockPos,
        context: CollisionContext
    ): VoxelShape {
        val entity = level.getBlockEntity(pos) as? NodeBlockEntity ?: return NODE_SHAPE
        var shape = NODE_SHAPE
        for (face in entity.getMonitorFaces()) {
            MONITOR_SHAPES[face]?.let { shape = Shapes.or(shape, it) }
        }
        return shape
    }

    override fun getCollisionShape(
        state: BlockState,
        level: BlockGetter,
        pos: BlockPos,
        context: CollisionContext
    ): VoxelShape = getShape(state, level, pos, context)

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

        // If this face has a monitor, right-click with empty hand clears the tracked item
        if (blockEntity.hasMonitor(side) && player.mainHandItem.isEmpty) {
            blockEntity.setMonitorItem(side, null)
            return InteractionResult.SUCCESS
        }

        // If holding a monitor item, attach it to this face
        if (player.mainHandItem.item is MonitorItem) {
            if (!blockEntity.hasMonitor(side)) {
                blockEntity.attachMonitor(side)
                if (!player.abilities.instabuild) {
                    player.mainHandItem.shrink(1)
                }
            }
            return InteractionResult.SUCCESS
        }

        // If this face has a monitor, right-click with an item sets the tracked item
        if (blockEntity.hasMonitor(side) && !player.mainHandItem.isEmpty) {
            val itemId = BuiltInRegistries.ITEM.getKey(player.mainHandItem.item)?.toString()
            if (itemId != null) {
                blockEntity.setMonitorItem(side, itemId)
            }
            return InteractionResult.SUCCESS
        }

        // Default: open node side inventory
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

    override fun attack(state: BlockState, level: Level, pos: BlockPos, player: Player) {
        if (level.isClientSide) return

        val entity = level.getBlockEntity(pos) as? NodeBlockEntity ?: return

        // Determine which face the player is looking at
        val hitResult = player.pick(5.0, 0f, false)
        if (hitResult is BlockHitResult && hitResult.blockPos == pos) {
            val face = hitResult.direction
            if (entity.hasMonitor(face)) {
                // Pop off the monitor
                entity.removeMonitor(face)
                Containers.dropItemStack(level, pos.x + 0.5, pos.y + 0.5, pos.z + 0.5, ItemStack(ModItems.MONITOR))
                return
            }
        }
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
        if (entity != null) {
            entity.blockDestroyed = true
            // Drop all monitors
            if (!level.isClientSide) {
                for (face in entity.getMonitorFaces()) {
                    Containers.dropItemStack(level, pos.x + 0.5, pos.y + 0.5, pos.z + 0.5, ItemStack(ModItems.MONITOR))
                }
            }
        }
        return super.playerWillDestroy(level, pos, state, player)
    }
}
