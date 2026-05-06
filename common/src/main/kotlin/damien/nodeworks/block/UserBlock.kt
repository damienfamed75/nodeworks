package damien.nodeworks.block

import com.mojang.serialization.MapCodec
import damien.nodeworks.block.entity.UserBlockEntity
import damien.nodeworks.item.NetworkWrenchItem
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.phys.BlockHitResult

/**
 * User device, "right-clicks" with an item from network storage on whatever's
 * in front. Targets entity > block > air in priority order. Mode-aware
 * (instant or hold), filter-driven, channel-aware, redstone-gated. Mostly a
 * companion to Breaker / Placer for closing automation loops that need a
 * use-on-block or use-on-entity step.
 */
class UserBlock(properties: Properties) : BaseEntityBlock(properties) {

    companion object {
        val CODEC: MapCodec<UserBlock> = simpleCodec(::UserBlock)
        val FACING = BlockStateProperties.FACING
    }

    init {
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(FACING)
    }

    override fun codec(): MapCodec<out BaseEntityBlock> = CODEC
    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.MODEL

    override fun getStateForPlacement(context: BlockPlaceContext): BlockState? =
        defaultBlockState().setValue(FACING, context.nearestLookingDirection.opposite)

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity =
        UserBlockEntity(pos, state)

    /** Capture the player's UUID so the FakePlayer driving uses inherits the
     *  owner's identity, letting claim mods + spawn protection resolve
     *  permissions correctly. */
    override fun setPlacedBy(level: Level, pos: BlockPos, state: BlockState, placer: LivingEntity?, stack: ItemStack) {
        super.setPlacedBy(level, pos, state, placer, stack)
        if (level.isClientSide) return
        val be = level.getBlockEntity(pos) as? UserBlockEntity ?: return
        if (be.ownerUuid == null && placer is Player) {
            be.ownerUuid = placer.uuid
            be.setChanged()
        }
    }

    /** Right-click with an item, sets the User's filter to that item's id.
     *  Test-path stub until the GUI / Lua handle land in a later phase.
     *  Doesn't consume the held stack. */
    override fun useItemOn(
        stack: ItemStack,
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hand: InteractionHand,
        hitResult: BlockHitResult,
    ): InteractionResult {
        if (stack.isEmpty) return InteractionResult.TRY_WITH_EMPTY_HAND
        if (stack.item is NetworkWrenchItem ||
            stack.item is damien.nodeworks.item.DiagnosticToolItem
        ) return InteractionResult.TRY_WITH_EMPTY_HAND
        if (level.isClientSide) return InteractionResult.SUCCESS
        val be = level.getBlockEntity(pos) as? UserBlockEntity ?: return InteractionResult.PASS
        val itemId = BuiltInRegistries.ITEM.getKey(stack.item)?.toString() ?: return InteractionResult.PASS
        be.filterRule = itemId
        player.sendSystemMessage(Component.literal("User filter set to $itemId"))
        return InteractionResult.SUCCESS
    }

    /** Right-click with empty hand cycles redstone mode IGNORED -> HIGH ->
     *  LOW. Sneak+right-click cycles use mode INSTANT <-> HOLD. Both are
     *  pre-GUI test paths until the proper screen lands. */
    override fun useWithoutItem(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hitResult: BlockHitResult,
    ): InteractionResult {
        if (player.mainHandItem.item is NetworkWrenchItem ||
            player.mainHandItem.item is damien.nodeworks.item.DiagnosticToolItem
        ) return InteractionResult.PASS
        if (level.isClientSide) return InteractionResult.SUCCESS
        val be = level.getBlockEntity(pos) as? UserBlockEntity ?: return InteractionResult.PASS

        if (player.isShiftKeyDown) {
            be.mode = if (be.mode == UserBlockEntity.UseMode.INSTANT) {
                UserBlockEntity.UseMode.HOLD
            } else {
                UserBlockEntity.UseMode.INSTANT
            }
            player.sendSystemMessage(Component.literal("User mode: ${be.mode.name}"))
            return InteractionResult.SUCCESS
        }

        be.redstoneMode = (be.redstoneMode + 1) % 3
        val label = when (be.redstoneMode) {
            UserBlockEntity.REDSTONE_LOW -> "LOW (repeats while unpowered)"
            UserBlockEntity.REDSTONE_HIGH -> "HIGH (repeats while powered)"
            else -> "IGNORED (Lua only)"
        }
        player.sendSystemMessage(Component.literal("User redstone: $label, filter: '${be.filterRule.ifEmpty { "(none)" }}'"))
        return InteractionResult.SUCCESS
    }

    override fun <T : BlockEntity> getTicker(
        level: Level,
        state: BlockState,
        blockEntityType: BlockEntityType<T>,
    ): BlockEntityTicker<T>? {
        if (level.isClientSide) return null
        return BlockEntityTicker { lvl, _, _, be ->
            if (be is UserBlockEntity && lvl is net.minecraft.server.level.ServerLevel) {
                be.serverTick(lvl)
            }
        }
    }

    override fun playerWillDestroy(level: Level, pos: BlockPos, state: BlockState, player: Player): BlockState {
        val entity = level.getBlockEntity(pos) as? UserBlockEntity
        entity?.blockDestroyed = true
        return super.playerWillDestroy(level, pos, state, player)
    }
}
