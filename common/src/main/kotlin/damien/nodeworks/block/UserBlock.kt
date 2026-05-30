package damien.nodeworks.block

import com.mojang.serialization.MapCodec
import damien.nodeworks.block.entity.UserBlockEntity
import damien.nodeworks.item.NetworkWrenchItem
import damien.nodeworks.platform.PlatformServices
import damien.nodeworks.screen.UserMenu
import damien.nodeworks.screen.UserOpenData
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
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
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape

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

        // Hitbox matches the block model: 14×14 cross-section inset 1 px on
        // both perpendicular axes, full 16 along the FACING axis. One shape
        // per axis - NORTH/SOUTH share, EAST/WEST share, UP/DOWN share.
        private val SHAPE_Z: VoxelShape = Block.box(1.0, 1.0, 0.0, 15.0, 15.0, 16.0)
        private val SHAPE_X: VoxelShape = Block.box(0.0, 1.0, 1.0, 16.0, 15.0, 15.0)
        private val SHAPE_Y: VoxelShape = Block.box(1.0, 0.0, 1.0, 15.0, 16.0, 15.0)
    }

    init {
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(FACING)
    }

    override fun codec(): MapCodec<out BaseEntityBlock> = CODEC
    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.MODEL

    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape =
        when (state.getValue(FACING).axis) {
            Direction.Axis.X -> SHAPE_X
            Direction.Axis.Y -> SHAPE_Y
            else -> SHAPE_Z
        }

    /** Lets levers / buttons / redstone components attach to any face despite
     *  the inset collision shape. */
    override fun getBlockSupportShape(state: BlockState, level: BlockGetter, pos: BlockPos): VoxelShape =
        Shapes.block()

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
     *  Convenience shortcut so a player can configure the filter without
     *  opening the GUI when they already have the item in hand. Falls through
     *  to the empty-hand path (which opens the GUI) when the held item is a
     *  Wrench / Diagnostic tool so those keep their own block interactions. */
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
        // Build the canonical filter string for the held stack so a variant-
        // bearing item (potion, dyed armor, enchanted book) sets a rule that
        // matches only that specific variant. Plain stacks fall back to the
        // bare itemId.
        val rule = damien.nodeworks.script.FilterRule.format(stack, level.registryAccess())
        be.filterRule = rule
        player.sendSystemMessage(Component.literal("User filter set to $rule"))
        return InteractionResult.SUCCESS
    }

    /** Right-click with empty hand opens the settings GUI. */
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

        val entity = level.getBlockEntity(pos) as? UserBlockEntity ?: return InteractionResult.PASS
        val serverPlayer = player as ServerPlayer
        PlatformServices.menu.openExtendedMenu(
            serverPlayer,
            Component.translatable("container.nodeworks.user"),
            UserOpenData(
                pos = pos,
                deviceName = entity.deviceName,
                channelId = entity.channel.id,
                filterRule = entity.filterRule,
                redstoneMode = entity.redstoneMode,
                modeOrdinal = entity.mode.ordinal,
                previewArea = entity.previewArea,
            ),
            UserOpenData.STREAM_CODEC,
            { syncId, inv, _ -> UserMenu.createServer(syncId, inv, entity) },
        )
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
