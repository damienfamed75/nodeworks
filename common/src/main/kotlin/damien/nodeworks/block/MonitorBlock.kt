package damien.nodeworks.block

import com.mojang.serialization.MapCodec
import damien.nodeworks.block.entity.MonitorBlockEntity
import damien.nodeworks.item.DiagnosticToolItem
import damien.nodeworks.item.NetworkWrenchItem
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.phys.BlockHitResult

/**
 * Full-block Monitor. Connectable, like Terminal, joins the node network so a
 * right-click-assigned item can be counted across all reachable storage cards.
 *
 * Interactions:
 *   - Right-click with an item/block in hand → set that item as the tracked item.
 *     The held item is NOT consumed, the right-click only records the identity.
 *   - Right-click with an empty hand → clear the tracking.
 *   - Wrench / Diagnostic Tool → pass-through (wrench wires network connections,
 *     diagnostic tool opens the inspector UI instead).
 */
class MonitorBlock(properties: Properties) : BaseEntityBlock(properties) {

    companion object {
        val CODEC: MapCodec<MonitorBlock> = simpleCodec(::MonitorBlock)
        val FACING = BlockStateProperties.HORIZONTAL_FACING
    }

    init {
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(FACING)
    }

    override fun getStateForPlacement(context: BlockPlaceContext): BlockState {
        // `horizontalDirection` is the direction the player is facing, the monitor's
        // front face should look AT the player → use .opposite, matching Terminal.
        return defaultBlockState().setValue(FACING, context.horizontalDirection.opposite)
    }

    override fun codec(): MapCodec<out BaseEntityBlock> = CODEC
    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.MODEL

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity {
        return MonitorBlockEntity(pos, state)
    }

    override fun useWithoutItem(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hitResult: BlockHitResult
    ): InteractionResult {
        // Wrench + Diagnostic tool use their own item-use handlers, let them run.
        val handItem = player.mainHandItem.item
        if (handItem is NetworkWrenchItem || handItem is DiagnosticToolItem) return InteractionResult.PASS
        if (level.isClientSide) return InteractionResult.SUCCESS

        val be = level.getBlockEntity(pos) as? MonitorBlockEntity ?: return InteractionResult.PASS
        if (player.mainHandItem.isEmpty) {
            // Clear, `useWithoutItem` only fires when main hand is empty, so this is
            // the only code path that ever clears the tracking.
            be.setTrackedItem(null)
        }
        return InteractionResult.SUCCESS
    }

    // Right-click with an item in hand fires useItemOn BEFORE useWithoutItem (when hand
    // is non-empty). We intercept here so holding any item → records that item as the
    // tracked target without consuming or placing anything from the stack.
    override fun useItemOn(
        stack: net.minecraft.world.item.ItemStack,
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hand: net.minecraft.world.InteractionHand,
        hitResult: BlockHitResult
    ): InteractionResult {
        // Empty hand → fall through to `useWithoutItem` which clears tracking. MC
        // still routes empty-hand clicks through useItemOn with stack=ItemStack.EMPTY
        // (item = minecraft:air), we'd otherwise record "minecraft:air" as the tracked
        // target and never fire the clear path at all.
        if (stack.isEmpty) return InteractionResult.TRY_WITH_EMPTY_HAND
        // Let the wrench and diagnostic tool keep their own useOn behavior, the
        // wrench hits MonitorBlock in its allow-list and handles connection toggling
        // there. TRY_WITH_EMPTY_HAND (not PASS) is the 26.1 "fall through" sentinel
        // that lets the chain keep trying other handlers, PASS short-circuits the
        // interaction silently.
        if (stack.item is NetworkWrenchItem || stack.item is DiagnosticToolItem) {
            return InteractionResult.TRY_WITH_EMPTY_HAND
        }
        if (level.isClientSide) return InteractionResult.SUCCESS
        val be = level.getBlockEntity(pos) as? MonitorBlockEntity ?: return InteractionResult.TRY_WITH_EMPTY_HAND
        val itemId = BuiltInRegistries.ITEM.getKey(stack.item)?.toString() ?: return InteractionResult.TRY_WITH_EMPTY_HAND
        be.setTrackedItem(itemId)
        return InteractionResult.SUCCESS
    }

    override fun playerWillDestroy(level: Level, pos: BlockPos, state: BlockState, player: Player): BlockState {
        val entity = level.getBlockEntity(pos) as? MonitorBlockEntity
        entity?.blockDestroyed = true
        return super.playerWillDestroy(level, pos, state, player)
    }
}
