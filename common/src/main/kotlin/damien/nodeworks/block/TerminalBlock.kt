package damien.nodeworks.block

import com.mojang.serialization.MapCodec
import damien.nodeworks.block.entity.NodeBlockEntity
import damien.nodeworks.block.entity.TerminalBlockEntity
import damien.nodeworks.platform.PlatformServices
import damien.nodeworks.screen.TerminalOpenData
import damien.nodeworks.screen.TerminalScreenHandler
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

        val terminal = level.getBlockEntity(pos) as? TerminalBlockEntity ?: return InteractionResult.PASS

        val startPos = terminal.getNetworkStartPos()
        if (startPos == null) {
            player.sendSystemMessage(Component.translatable("message.nodeworks.terminal_no_network"))
            return InteractionResult.SUCCESS
        }

        val serverPlayer = player as ServerPlayer
        val serverLevel = level as ServerLevel

        // 1.0 scoping decision: only one player can edit a terminal at a time.
        // Skips the headache of real-time collaborative editing (OT/CRDT, cursor
        // sync, edit-conflict resolution) for the typical multi-player flow,
        // which is "co-debug a script" rather than "two devs writing different
        // halves at the same time." If anyone already has this terminal's
        // [TerminalScreenHandler] open, refuse and tell the second player who's
        // in there. The break-event listener in `Nodeworks.onBlockBreak`
        // handles the cleanup case if the occupant logs off and gets evicted.
        val occupant = serverLevel.players().firstOrNull { other ->
            val menu = other.containerMenu
            menu is damien.nodeworks.screen.TerminalScreenHandler &&
                    menu.blockBackingPos == terminal.blockPos
        }
        if (occupant != null) {
            player.sendSystemMessage(
                Component.translatable("message.nodeworks.terminal_in_use", occupant.name.string)
            )
            return InteractionResult.SUCCESS
        }

        // Walk the server-side network and pull any cross-dim remote Processing APIs
        // (reached via Receiver Antennas paired to a remote Broadcast Antenna). The
        // client can't read these itself because the broadcast BE lives in another
        // dimension, surface them in openData so the script editor's autocomplete
        // can suggest remote recipe names in network:craft("..."). Local APIs are
        // still scanned client-side in TerminalScreen, we only ship what the client
        // genuinely can't reach.
        val snapshot = damien.nodeworks.network.NetworkDiscovery.discoverNetwork(serverLevel, startPos)
        val remoteApis = snapshot.processingApis
            .filter { it.remoteDimension != null }
            .flatMap { it.apis }

        val openData = TerminalOpenData(
            terminal.blockPos,
            terminal.getScriptsCopy(),
            PlatformServices.modState.isScriptRunning(serverLevel, terminal.blockPos),
            terminal.autoRun,
            terminal.layoutIndex,
            remoteApis,
            terminal.lastError,
        )

        PlatformServices.menu.openExtendedMenu(
            serverPlayer,
            Component.translatable("block.nodeworks.terminal"),
            openData,
            TerminalOpenData.STREAM_CODEC,
            { syncId, inv, p -> TerminalScreenHandler.createServer(syncId, p, terminal) }
        )

        return InteractionResult.SUCCESS
    }

    /**
     * Rising-edge redstone pulse toggles the terminal's script: start it if stopped,
     * stop it if running. Button taps / pressure plate steps / fresh torches all count
     * as a rising edge from 0 to >0. `lastRedstoneSignal = -1` on a freshly-loaded BE
     * means the first neighborChanged call just initializes the tracker, so a
     * permanently-powered terminal doesn't auto-start every time the chunk reloads.
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
            } else if (terminal.scriptText.isNotBlank()) {
                // Refuse to restart a script that previously hit a wall-clock
                // soft-abort. Without this, a redstone clock pointed at a
                // misbehaving terminal (e.g. `while true do print() end`) would
                // re-trigger the bad script every other tick, eating the per-tick
                // budget on each cycle. The player must edit the script which
                // clears [TerminalBlockEntity.lastError] in [setScript] before
                // it's eligible to run again.
                if (terminal.lastError != null) return
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
