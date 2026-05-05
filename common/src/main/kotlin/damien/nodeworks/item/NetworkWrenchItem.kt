package damien.nodeworks.item

import damien.nodeworks.block.NodeBlock
import damien.nodeworks.block.PipeBlock
import damien.nodeworks.network.Connectable
import damien.nodeworks.network.NodeConnectionHelper
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.InteractionResult
import net.minecraft.world.item.Item
import net.minecraft.world.item.context.UseOnContext
import net.minecraft.world.level.block.Block
import net.minecraft.world.phys.Vec3
import kotlin.math.abs

/**
 * Wrench: shift+right-click any [Connectable] face to toggle its force-block
 * flag. A blocked face stops rendering its pipe stub, drops out of the
 * network adjacency walk, and (on a Node) frees its card slots.
 *
 * Click resolution uses a dominant-axis heuristic on the hit location, not
 * the hit face. This way clicking anywhere on a stub (its end OR any of its
 * sides) flips that stub's direction. Clicking the bare core face after a
 * connection has been broken flips the same bit again to reconnect.
 */
class NetworkWrenchItem(properties: Properties) : Item(properties) {

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION") // Item.appendHoverText, the non-deprecated path is data components, overkill for a static line.
    override fun appendHoverText(
        itemStack: net.minecraft.world.item.ItemStack,
        context: TooltipContext,
        display: net.minecraft.world.item.component.TooltipDisplay,
        builder: java.util.function.Consumer<Component>,
        tooltipFlag: net.minecraft.world.item.TooltipFlag
    ) {
        builder.accept(Component.literal("Toggles pipe connections").withStyle(ChatFormatting.GRAY))
        builder.accept(
            Component.literal("Shift + right-click a pipe / node face").withStyle(ChatFormatting.DARK_GRAY)
        )
    }

    override fun useOn(context: UseOnContext): InteractionResult {
        val level = context.level
        val pos = context.clickedPos
        val player = context.player ?: return InteractionResult.PASS

        // The wrench is always shift-only. A plain right-click is a no-op so
        // the player can hold the wrench without accidentally placing items
        // or interacting with vanilla blocks.
        if (!player.isShiftKeyDown) return InteractionResult.PASS

        val be = level.getBlockEntity(pos) as? Connectable ?: return InteractionResult.PASS

        if (level.isClientSide) return InteractionResult.SUCCESS

        val serverLevel = level as ServerLevel
        val side = resolveClickedSide(pos, context.clickLocation)
        val neighborPos = pos.relative(side)
        val neighborBe = level.getBlockEntity(neighborPos) as? Connectable

        // The flag is per-BE but the connection is shared between the two
        // BEs, so logic operates on the joint state. If *either* side has its
        // touching face flagged, the connection is broken, the click should
        // clear all flags so the joint goes back to live. If neither side has
        // a flag, we're cutting a live connection, set the clicked BE's flag.
        // This way a player can disconnect from one side and reconnect from
        // either side.
        val selfBlocked = be.forcedPipeBlocked(side)
        val neighborBlocked = neighborBe?.forcedPipeBlocked(side.opposite) == true
        if (selfBlocked || neighborBlocked) {
            if (selfBlocked) be.toggleForcedPipeBlock(side)
            if (neighborBlocked) neighborBe.toggleForcedPipeBlock(side.opposite)
        } else {
            be.toggleForcedPipeBlock(side)
        }

        // Self + neighbour both need their multipart blockstate rebuilt so
        // the stub appears/disappears on both sides simultaneously. The model
        // flag is computed against `forcedPipeBlocked` on both BEs, but
        // updateShape doesn't fire for a wrench toggle (we changed BE state,
        // not a neighbour block), so we rebuild explicitly here.
        refreshPipeState(serverLevel, pos)
        refreshPipeState(serverLevel, neighborPos)

        // Network membership may have just split or rejoined. Propagate from
        // both sides because either could now be the new orphaned subgraph.
        NodeConnectionHelper.propagateNetworkId(serverLevel, pos)
        NodeConnectionHelper.propagateNetworkId(serverLevel, neighborPos)

        return InteractionResult.SUCCESS
    }

    /** Pick the direction the player meant by clicking. Stubs are small and
     *  off-axis (e.g. the top face of an east-going stub returns UP from
     *  vanilla's hit-face). Vector from block centre to hit point points
     *  outward in the direction of the clicked stub or face, so the
     *  dominant-magnitude axis is the right answer for both stub-side and
     *  bare-core clicks. */
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

    /** Recompute Pipe / Node multipart booleans against current `forcedPipeBlocked`
     *  state. Skip non-Pipe / non-Node Connectables, they have no `pipe_*`
     *  properties (Controller / Terminal / chests render their own model). */
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
