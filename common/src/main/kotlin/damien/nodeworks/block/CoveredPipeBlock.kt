package damien.nodeworks.block

import com.mojang.serialization.MapCodec
import damien.nodeworks.block.entity.CoveredPipeBlockEntity
import net.minecraft.core.BlockPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState

/**
 * Full-block "Covered Vacuum Pipe" - the camouflage variant of [PipeBlock].
 * Same network-connectivity behaviour (face-adjacent BFS via the
 * Connectable interface on its BE) but renders as a full block matching
 * the wrapped camo block, so players can hide pipe runs inside walls.
 *
 * Rendering is BER-only: [RenderShape.INVISIBLE] suppresses the chunk
 * mesher so the camo body and the pipe-indicator overlay are drawn solely
 * by [damien.nodeworks.render.CoveredPipeRenderer], which delegates to
 * the camo state's baked model. The block has no FACING, no per-direction
 * blockstate properties, and uses the default full-cube collision shape.
 *
 * Cannot be swapped with a Node (the [damien.nodeworks.item.NodeBlockItem]
 * replace-in-place path matches `is PipeBlock` specifically, so Covered
 * Pipes fall through to vanilla adjacent-placement).
 */
class CoveredPipeBlock(properties: Properties) : BaseEntityBlock(properties) {

    companion object {
        val CODEC: MapCodec<CoveredPipeBlock> = simpleCodec(::CoveredPipeBlock)
    }

    override fun codec(): MapCodec<out BaseEntityBlock> = CODEC

    /** Chunk geometry handles everything via a dynamic [BlockStateModel]
     *  injected at bake time (`ModelEvent.ModifyBakingResult` in
     *  `NeoForgeClientSetup`). The model delegates to the camo block's
     *  baked quads and appends the pipe-connectable indicator overlay,
     *  giving us free per-vertex AO + smooth lighting without a BER. */
    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.MODEL

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity =
        CoveredPipeBlockEntity(pos, state)

    /** Read the camo from the placed item's [damien.nodeworks.registry.ModDataComponents.CAMO_BLOCK_STATE]
     *  component and stash it on the BE. Falls back to the BE's default
     *  ([net.minecraft.world.level.block.Blocks.STONE]) if the item has no
     *  component - this would only happen for `/setblock` or creative-tab
     *  entries that bypass the recipe. */
    override fun setPlacedBy(
        level: Level,
        pos: BlockPos,
        state: BlockState,
        placer: net.minecraft.world.entity.LivingEntity?,
        stack: net.minecraft.world.item.ItemStack,
    ) {
        super.setPlacedBy(level, pos, state, placer, stack)
        if (level.isClientSide) return
        val be = level.getBlockEntity(pos) as? CoveredPipeBlockEntity ?: return
        val camo = stack.get(damien.nodeworks.registry.ModDataComponents.CAMO_BLOCK_STATE)
        if (camo != null) be.camoBlockState = camo
    }

    /** Re-attach the camo when the player middle-clicks (pick block) or
     *  silk-touch-mines the Covered Pipe, so the cloned item keeps its
     *  disguise. Mirrors how shulker boxes / banners ship their state. */
    override fun getCloneItemStack(
        level: net.minecraft.world.level.LevelReader,
        pos: BlockPos,
        state: BlockState,
        includeData: Boolean,
    ): net.minecraft.world.item.ItemStack {
        val stack = super.getCloneItemStack(level, pos, state, includeData)
        val be = level.getBlockEntity(pos) as? CoveredPipeBlockEntity ?: return stack
        stack.set(damien.nodeworks.registry.ModDataComponents.CAMO_BLOCK_STATE, be.camoBlockState)
        return stack
    }

    override fun playerWillDestroy(
        level: Level,
        pos: BlockPos,
        state: BlockState,
        player: net.minecraft.world.entity.player.Player,
    ): BlockState {
        val entity = level.getBlockEntity(pos) as? CoveredPipeBlockEntity
        entity?.blockDestroyed = true
        return super.playerWillDestroy(level, pos, state, player)
    }

    /** Drops the Covered Pipe back with its camo attached. The vanilla loot
     *  table just lists `nodeworks:covered_pipe`, this override stamps the
     *  camo onto every dropped stack so the player gets their disguise back
     *  (silk-touch or otherwise) instead of a stock Covered Pipe (Stone). */
    override fun getDrops(
        state: BlockState,
        params: net.minecraft.world.level.storage.loot.LootParams.Builder,
    ): List<net.minecraft.world.item.ItemStack> {
        val drops = super.getDrops(state, params)
        if (drops.isEmpty()) return drops
        val beParam = net.minecraft.world.level.storage.loot.parameters.LootContextParams.BLOCK_ENTITY
        val be = params.getOptionalParameter(beParam) as? CoveredPipeBlockEntity ?: return drops
        for (stack in drops) {
            if (stack.item == this.asItem()) {
                stack.set(damien.nodeworks.registry.ModDataComponents.CAMO_BLOCK_STATE, be.camoBlockState)
            }
        }
        return drops
    }
}
