package damien.nodeworks.item

import damien.nodeworks.block.NodeBlock
import damien.nodeworks.block.PipeBlock
import damien.nodeworks.block.entity.FocusNodeBlockEntity
import damien.nodeworks.network.Connectable
import damien.nodeworks.network.NetworkRuntimeConfig
import damien.nodeworks.network.NodeConnectionHelper
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.context.UseOnContext
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.phys.Vec3
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * Network Wrench. Three gestures:
 *
 *  * **Shift + right-click on a Focus Node face that is NOT touching another
 *    Connectable** → select that Focus Node as the pending link source.
 *    Subsequent plain-right-clicks on other Focus Nodes link from this one.
 *  * **Shift + right-click on any other Connectable face (or a Focus Node face
 *    that IS touching a Connectable)** → toggle the per-face force-block
 *    flag on that face. Existing pipe/cable disconnect path.
 *  * **Plain right-click on a Focus Node** → toggle the laser link between
 *    the pending selection and this Focus Node. Selection is kept after a
 *    successful link so the player can chain-bind from one source to many
 *    targets without re-selecting between each.
 */
class NetworkWrenchItem(properties: Properties) : Item(properties) {

    private data class Selection(val pos: BlockPos, val dimension: ResourceKey<Level>)

    companion object {
        private val selections = ConcurrentHashMap<UUID, Selection>()

        @JvmField
        var clientSelectedPos: BlockPos? = null

        fun clearSelection(playerUuid: UUID) {
            selections.remove(playerUuid)
        }
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION") // Item.appendHoverText, the non-deprecated path is data components, overkill for a static line.
    override fun appendHoverText(
        itemStack: net.minecraft.world.item.ItemStack,
        context: TooltipContext,
        display: net.minecraft.world.item.component.TooltipDisplay,
        builder: java.util.function.Consumer<Component>,
        tooltipFlag: net.minecraft.world.item.TooltipFlag
    ) {
        builder.accept(Component.literal("Shift + right-click a Focus Node's free face to select it").withStyle(ChatFormatting.GRAY))
        builder.accept(Component.literal("Right-click another Focus Node to link / unlink").withStyle(ChatFormatting.GRAY))
        builder.accept(Component.literal("Shift + right-click a pipe / node face to toggle that connection").withStyle(ChatFormatting.DARK_GRAY))
    }

    override fun useOn(context: UseOnContext): InteractionResult {
        val level = context.level
        val pos = context.clickedPos
        val player = context.player ?: return InteractionResult.PASS
        val be = level.getBlockEntity(pos) as? Connectable ?: return InteractionResult.PASS

        return if (player.isShiftKeyDown) shiftClick(context, level, pos, be)
        else plainClick(level, pos, player, be)
    }

    /** Plain right-click on a Focus Node: link/unlink it against the pending
     *  selection. Non-Focus-Node clicks PASS so vanilla blocks behave normally. */
    private fun plainClick(
        level: Level,
        pos: BlockPos,
        player: Player,
        @Suppress("UNUSED_PARAMETER") be: Connectable,
    ): InteractionResult {
        if (level.getBlockEntity(pos) !is FocusNodeBlockEntity) return InteractionResult.PASS

        if (level.isClientSide) return InteractionResult.SUCCESS

        val serverLevel = level as ServerLevel
        val pending = selections[player.uuid]

        if (pending == null) {
            player.sendSystemMessage(Component.translatable("message.nodeworks.no_selection"))
            return InteractionResult.SUCCESS
        }

        if (pending.dimension != level.dimension()) {
            selections.remove(player.uuid)
            player.sendSystemMessage(Component.translatable("message.nodeworks.selection_invalid"))
            return InteractionResult.SUCCESS
        }

        val result = NodeConnectionHelper.toggleConnection(serverLevel, pending.pos, pos)
        // Keep the selection across successful links so the player can
        // chain-bind one source to many targets. Drop only when the source
        // BE itself vanished mid-flow.
        if (result is NodeConnectionHelper.ConnectResult.InvalidEndpoint) {
            selections.remove(player.uuid)
        }
        sendResultMessage(player, result)
        return InteractionResult.SUCCESS
    }

    /** Shift-click flow. Free-face Focus Node clicks select that node as the
     *  link source. Every other case falls through to the per-face
     *  force-block toggle (the original disconnect path). */
    private fun shiftClick(
        context: UseOnContext,
        level: Level,
        pos: BlockPos,
        be: Connectable,
    ): InteractionResult {
        val side = resolveClickedSide(pos, context.clickLocation)
        val isFocusNode = level.getBlockEntity(pos) is FocusNodeBlockEntity
        if (isFocusNode) {
            val neighborPos = pos.relative(side)
            val hasAdjacent = level.getBlockEntity(neighborPos) is Connectable
            if (!hasAdjacent) {
                return selectFocusNode(level, pos, context.player!!)
            }
        }
        return faceToggle(level, pos, side, be)
    }

    private fun selectFocusNode(level: Level, pos: BlockPos, player: Player): InteractionResult {
        if (level.isClientSide) {
            clientSelectedPos = pos
            return InteractionResult.SUCCESS
        }
        selections[player.uuid] = Selection(pos, level.dimension())
        player.sendSystemMessage(Component.translatable("message.nodeworks.endpoint_selected", pos.x, pos.y, pos.z))
        return InteractionResult.SUCCESS
    }

    private fun sendResultMessage(player: Player, result: NodeConnectionHelper.ConnectResult) {
        val msg: Component = when (result) {
            is NodeConnectionHelper.ConnectResult.Connected ->
                Component.translatable("message.nodeworks.connected")
            is NodeConnectionHelper.ConnectResult.AlreadyConnected ->
                Component.translatable("message.nodeworks.disconnected")
            is NodeConnectionHelper.ConnectResult.OutOfRange ->
                Component.translatable("message.nodeworks.too_far", NetworkRuntimeConfig.FOCUS_NODE_MAX_DISTANCE.toInt())
            is NodeConnectionHelper.ConnectResult.NoLineOfSight ->
                Component.translatable("message.nodeworks.no_line_of_sight")
            is NodeConnectionHelper.ConnectResult.MaxLinksReached ->
                Component.translatable("message.nodeworks.max_links")
            is NodeConnectionHelper.ConnectResult.DuplicateController ->
                Component.translatable("message.nodeworks.duplicate_controller")
            is NodeConnectionHelper.ConnectResult.SameEndpoint ->
                Component.translatable("message.nodeworks.same_endpoint")
            is NodeConnectionHelper.ConnectResult.InvalidEndpoint ->
                Component.translatable("message.nodeworks.selection_invalid")
        }
        player.sendSystemMessage(msg)
    }

    /** Per-face force-block toggle. Symmetric: clearing the flag on either
     *  side reconnects, setting on either side disconnects. */
    private fun faceToggle(level: Level, pos: BlockPos, side: Direction, be: Connectable): InteractionResult {
        if (level.isClientSide) return InteractionResult.SUCCESS

        val serverLevel = level as ServerLevel
        val neighborPos = pos.relative(side)
        val neighborBe = level.getBlockEntity(neighborPos) as? Connectable

        val selfBlocked = be.forcedPipeBlocked(side)
        val neighborBlocked = neighborBe?.forcedPipeBlocked(side.opposite) == true
        if (selfBlocked || neighborBlocked) {
            if (selfBlocked) be.toggleForcedPipeBlock(side)
            if (neighborBlocked) neighborBe.toggleForcedPipeBlock(side.opposite)
        } else {
            be.toggleForcedPipeBlock(side)
        }

        refreshPipeState(serverLevel, pos)
        refreshPipeState(serverLevel, neighborPos)

        NodeConnectionHelper.propagateNetworkId(serverLevel, pos)
        NodeConnectionHelper.propagateNetworkId(serverLevel, neighborPos)

        return InteractionResult.SUCCESS
    }

    /** Resolve which direction the player aimed at. Vanilla's hit-face is
     *  unreliable for thin stubs (clicking the top of an east-going stub
     *  returns UP), so we use the dominant-magnitude axis of the
     *  centre-to-hit-point vector instead. Works for stub sides, stub ends,
     *  and bare core faces. */
    private fun resolveClickedSide(pos: BlockPos, hit: Vec3): Direction {
        val dx = hit.x - (pos.x + 0.5)
        val dy = hit.y - (pos.y + 0.5)
        val dz = hit.z - (pos.z + 0.5)
        val ax = abs(dx); val ay = abs(dy); val az = abs(dz)
        return when {
            ax >= ay && ax >= az -> if (dx >= 0) Direction.EAST else Direction.WEST
            ay >= az -> if (dy >= 0) Direction.UP else Direction.DOWN
            else -> if (dz >= 0) Direction.SOUTH else Direction.NORTH
        }
    }

    /** Recompute Pipe / Node multipart booleans against current
     *  `forcedPipeBlocked` state. Other Connectables have no `pipe_*`
     *  properties so we skip them. */
    private fun refreshPipeState(level: ServerLevel, pos: BlockPos) {
        if (!level.isLoaded(pos)) return
        val state = level.getBlockState(pos)
        val rebuilt = when (state.block) {
            is PipeBlock -> PipeBlock.rebuildState(level, pos, state)
            is NodeBlock -> NodeBlock.rebuildState(level, pos, state)
            else -> return
        }
        if (rebuilt != state) level.setBlock(pos, rebuilt, Block.UPDATE_ALL)
    }
}
