package damien.nodeworks.block

import com.mojang.serialization.MapCodec
import damien.nodeworks.block.entity.NodeBlockEntity
import damien.nodeworks.network.TerminalPackets
import damien.nodeworks.block.entity.TerminalBlockEntity
import damien.nodeworks.screen.TerminalOpenData
import damien.nodeworks.screen.TerminalScreenHandler
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerFactory
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.level.Level
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.HorizontalDirectionalBlock
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.phys.BlockHitResult

class TerminalBlock(properties: Properties) : BaseEntityBlock(properties) {

    companion object {
        val CODEC: MapCodec<TerminalBlock> = simpleCodec(::TerminalBlock)
        val FACING = BlockStateProperties.HORIZONTAL_FACING

        /** Scans adjacent blocks for a node to connect to. */
        fun findAdjacentNode(level: Level, terminalPos: BlockPos): BlockPos? {
            for (dir in Direction.entries) {
                val adj = terminalPos.relative(dir)
                if (level.getBlockEntity(adj) is NodeBlockEntity) {
                    return adj
                }
            }
            return null
        }
    }

    init {
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(FACING)
    }

    override fun getStateForPlacement(context: BlockPlaceContext): BlockState {
        return defaultBlockState().setValue(FACING, context.horizontalDirection.opposite)
    }

    override fun codec(): MapCodec<out BaseEntityBlock> = CODEC

    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.MODEL

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity {
        return TerminalBlockEntity(pos, state)
    }

    override fun useWithoutItem(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hitResult: BlockHitResult
    ): InteractionResult {
        if (level.isClientSide) return InteractionResult.SUCCESS

        val terminal = level.getBlockEntity(pos) as? TerminalBlockEntity ?: return InteractionResult.PASS

        if (terminal.getConnectedNodePos() == null) {
            player.displayClientMessage(Component.translatable("message.nodeworks.terminal_no_network"), false)
            return InteractionResult.SUCCESS
        }

        val serverPlayer = player as ServerPlayer

        val nodePos = terminal.getConnectedNodePos()!!
        val serverLevel = level as ServerLevel
        val snapshot = damien.nodeworks.network.NetworkDiscovery.discoverNetwork(serverLevel, nodePos)
        val allCards = snapshot.allCards()

        serverPlayer.openMenu(object : ExtendedScreenHandlerFactory<TerminalOpenData> {
            override fun getScreenOpeningData(player: ServerPlayer): TerminalOpenData {
                val isRunning = TerminalPackets.getEngine(terminal.blockPos)?.isRunning() == true
                return TerminalOpenData(terminal.blockPos, terminal.scriptText, isRunning, terminal.autoRun, terminal.layoutIndex, allCards)
            }

            override fun getDisplayName(): Component = Component.translatable("block.nodeworks.terminal")

            override fun createMenu(syncId: Int, playerInventory: Inventory, player: Player): AbstractContainerMenu {
                return TerminalScreenHandler.createServer(syncId, player, terminal)
            }
        })

        return InteractionResult.SUCCESS
    }
}
