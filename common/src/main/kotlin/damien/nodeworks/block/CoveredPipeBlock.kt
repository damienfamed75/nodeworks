package damien.nodeworks.block

import com.mojang.serialization.MapCodec
import damien.nodeworks.block.entity.CoveredPipeBlockEntity
import damien.nodeworks.registry.ModDataComponents
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.LevelReader
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.storage.loot.LootParams
import net.minecraft.world.level.storage.loot.parameters.LootContextParams

/**
 * Camouflage variant of [PipeBlock]: a full-cube block whose visual is
 * dynamically swapped to match an arbitrary "camo" [BlockState] stored on
 * the BE. Network connectivity is identical to a regular Pipe; rendering
 * goes through the dynamic baked model registered in
 * `NeoForgeClientSetup.onModifyBakingResult`, which delegates to the
 * camo's baked quads (free per-vertex AO + smooth lighting) and appends
 * a pipe-indicator overlay on every face.
 *
 * The Node "replace this Pipe with a Node" placement path in
 * [damien.nodeworks.item.NodeBlockItem] keys on `is PipeBlock`, so
 * Covered Pipes naturally aren't substitutable.
 */
class CoveredPipeBlock(properties: Properties) : BaseEntityBlock(properties) {

    companion object {
        val CODEC: MapCodec<CoveredPipeBlock> = simpleCodec(::CoveredPipeBlock)
    }

    override fun codec(): MapCodec<out BaseEntityBlock> = CODEC

    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.MODEL

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity =
        CoveredPipeBlockEntity(pos, state)

    override fun setPlacedBy(
        level: Level,
        pos: BlockPos,
        state: BlockState,
        placer: LivingEntity?,
        stack: ItemStack,
    ) {
        super.setPlacedBy(level, pos, state, placer, stack)
        if (level.isClientSide) return
        val be = level.getBlockEntity(pos) as? CoveredPipeBlockEntity ?: return
        stack.get(ModDataComponents.CAMO_BLOCK_STATE)?.let { be.camoBlockState = it }
    }

    /** Carry the camo through pick-block and silk-touch drops so the
     *  cloned stack keeps its disguise instead of reverting to default. */
    override fun getCloneItemStack(
        level: LevelReader,
        pos: BlockPos,
        state: BlockState,
        includeData: Boolean,
    ): ItemStack {
        val stack = super.getCloneItemStack(level, pos, state, includeData)
        val be = level.getBlockEntity(pos) as? CoveredPipeBlockEntity ?: return stack
        stack.set(ModDataComponents.CAMO_BLOCK_STATE, be.camoBlockState)
        return stack
    }

    override fun playerWillDestroy(level: Level, pos: BlockPos, state: BlockState, player: Player): BlockState {
        (level.getBlockEntity(pos) as? CoveredPipeBlockEntity)?.blockDestroyed = true
        return super.playerWillDestroy(level, pos, state, player)
    }

    /** Stamp the camo onto every dropped stack so loot honours the
     *  disguise. The loot table itself just lists the item id - the
     *  data-component injection lives here because the loot codec can't
     *  read arbitrary BE fields. */
    override fun getDrops(state: BlockState, params: LootParams.Builder): List<ItemStack> {
        val drops = super.getDrops(state, params)
        if (drops.isEmpty()) return drops
        val be = params.getOptionalParameter(LootContextParams.BLOCK_ENTITY) as? CoveredPipeBlockEntity
            ?: return drops
        for (stack in drops) {
            if (stack.item == this.asItem()) {
                stack.set(ModDataComponents.CAMO_BLOCK_STATE, be.camoBlockState)
            }
        }
        return drops
    }
}
