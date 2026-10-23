package damien.nodeworks.block

import com.mojang.serialization.MapCodec
import damien.nodeworks.block.entity.TerminalBlockEntity
import damien.nodeworks.platform.PlatformServices
import damien.nodeworks.screen.ScriptRunnerMenu
import damien.nodeworks.screen.ScriptRunnerOpenData
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
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

class TerminalBlock(properties: Properties) : BaseEntityBlock(properties), Wrenchable {

    companion object {
        val CODEC: MapCodec<TerminalBlock> = simpleCodec(::TerminalBlock)
        val FACING = BlockStateProperties.HORIZONTAL_FACING
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

    /** Capture the placer's UUID so script-driven block mutations carry an actor identity
     *  to claim mods (FTB Chunks etc.) and to vanilla spawn protection. Stack-restored
     *  block-entity-data already carries the saved ownerUuid via the BLOCK_ENTITY_DATA
     *  load, so we only set it when the freshly-placed BE has a null owner. */
    override fun setPlacedBy(level: Level, pos: BlockPos, state: BlockState, placer: LivingEntity?, stack: ItemStack) {
        super.setPlacedBy(level, pos, state, placer, stack)
        if (level.isClientSide) return
        val terminal = level.getBlockEntity(pos) as? TerminalBlockEntity ?: return
        if (terminal.ownerUuid == null && placer is Player) {
            terminal.ownerUuid = placer.uuid
            terminal.setChanged()
        }
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

        val runner = level.getBlockEntity(pos) as? TerminalBlockEntity ?: return InteractionResult.PASS
        val serverPlayer = player as ServerPlayer
        val errors = List(TerminalBlockEntity.SLOT_COUNT) { runner.lastError(it) ?: "" }
        PlatformServices.menu.openExtendedMenu(
            serverPlayer,
            Component.translatable("block.nodeworks.terminal"),
            ScriptRunnerOpenData(pos, errors),
            ScriptRunnerOpenData.STREAM_CODEC,
            { syncId, inv, _ -> ScriptRunnerMenu.createServer(syncId, inv, runner) },
        )
        return InteractionResult.SUCCESS
    }

    /**
     * A rising redstone edge toggles the Runner: stop every script if any runs, otherwise
     * start every enabled slot. Slots with a recorded timeout stay off, so a redstone clock
     * pointed at a misbehaving script cannot re-fire it every cycle.
     */
    override fun neighborChanged(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        neighborBlock: Block,
        orientation: net.minecraft.world.level.redstone.Orientation?,
        movedByPiston: Boolean
    ) {
        super.neighborChanged(state, level, pos, neighborBlock, orientation, movedByPiston)
        if (level !is ServerLevel) return
        val terminal = level.getBlockEntity(pos) as? TerminalBlockEntity ?: return
        val signal = level.getBestNeighborSignal(pos)
        val prev = terminal.lastRedstoneSignal
        terminal.lastRedstoneSignal = signal
        if (prev < 0) return  // first call since BE load, just capture the baseline
        if (prev == 0 && signal > 0) {
            if (PlatformServices.modState.isScriptRunning(level, pos)) {
                PlatformServices.modState.stopScript(level, pos)
            } else if (terminal.autoStartSlots().isNotEmpty()) {
                PlatformServices.modState.startScript(level, pos)
            }
        }
    }

    override fun playerWillDestroy(level: Level, pos: BlockPos, state: BlockState, player: Player): BlockState {
        val entity = level.getBlockEntity(pos) as? TerminalBlockEntity
        entity?.blockDestroyed = true
        return super.playerWillDestroy(level, pos, state, player)
    }

    override fun affectNeighborsAfterRemoval(
        state: BlockState,
        level: ServerLevel,
        pos: BlockPos,
        movedByPiston: Boolean
    ) {
        val entity = level.getBlockEntity(pos) as? TerminalBlockEntity
        if (entity != null) entity.blockDestroyed = true
        super.affectNeighborsAfterRemoval(state, level, pos, movedByPiston)
    }
}
