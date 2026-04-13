package damien.nodeworks.block

import com.mojang.serialization.MapCodec
import damien.nodeworks.block.entity.CraftingCoreBlockEntity
import damien.nodeworks.item.NetworkWrenchItem
import net.minecraft.core.BlockPos
import net.minecraft.world.Containers
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BooleanProperty
import net.minecraft.world.phys.BlockHitResult

/**
 * Crafting Core — the brain of the multiblock Crafting CPU.
 * Connects to the network via laser. Right-click to open the CPU GUI.
 * When broken, drops any items held in the buffer.
 */
class CraftingCoreBlock(properties: Properties) : BaseEntityBlock(properties) {

    companion object {
        val CODEC: MapCodec<CraftingCoreBlock> = simpleCodec(::CraftingCoreBlock)
        val FORMED: BooleanProperty = BooleanProperty.create("formed")
    }

    init {
        registerDefaultState(stateDefinition.any().setValue(FORMED, false))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(FORMED)
    }

    override fun codec(): MapCodec<out BaseEntityBlock> = CODEC
    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.MODEL

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity {
        return CraftingCoreBlockEntity(pos, state)
    }

    override fun useWithoutItem(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hitResult: BlockHitResult
    ): InteractionResult {
        if (player.mainHandItem.item is NetworkWrenchItem || player.mainHandItem.item is damien.nodeworks.item.DiagnosticToolItem) return InteractionResult.PASS
        if (level.isClientSide) return InteractionResult.SUCCESS

        val entity = level.getBlockEntity(pos) as? CraftingCoreBlockEntity ?: return InteractionResult.PASS
        val serverPlayer = player as net.minecraft.server.level.ServerPlayer

        damien.nodeworks.platform.PlatformServices.menu.openExtendedMenu(
            serverPlayer,
            net.minecraft.network.chat.Component.translatable("container.nodeworks.crafting_core"),
            damien.nodeworks.screen.CraftingCoreOpenData(
                pos,
                entity.bufferUsed,
                entity.bufferCapacity,
                entity.bufferTypesUsed,
                entity.bufferTypesCapacity,
                entity.isFormed,
                entity.isCrafting
            ),
            damien.nodeworks.screen.CraftingCoreOpenData.STREAM_CODEC,
            { syncId, inv, _ -> damien.nodeworks.screen.CraftingCoreMenu.createServer(syncId, inv, entity) }
        )

        return InteractionResult.SUCCESS
    }

    override fun neighborChanged(state: BlockState, level: Level, pos: BlockPos, neighborBlock: Block, neighborPos: BlockPos, movedByPiston: Boolean) {
        super.neighborChanged(state, level, pos, neighborBlock, neighborPos, movedByPiston)
        val entity = level.getBlockEntity(pos) as? CraftingCoreBlockEntity ?: return
        entity.recalculateCapacity()
        // Update block state to reflect formed status (drives emissive model variant)
        val formed = entity.isFormed
        if (state.getValue(FORMED) != formed) {
            level.setBlock(pos, state.setValue(FORMED, formed), Block.UPDATE_ALL)
        }
    }

    override fun playerWillDestroy(level: Level, pos: BlockPos, state: BlockState, player: Player): BlockState {
        val entity = level.getBlockEntity(pos) as? CraftingCoreBlockEntity
        if (entity != null) {
            entity.blockDestroyed = true
            // Drop buffer contents as items
            if (!level.isClientSide) {
                for ((itemId, count) in entity.clearBuffer()) {
                    val id = net.minecraft.resources.ResourceLocation.tryParse(itemId) ?: continue
                    val item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(id) ?: continue
                    var remaining = count
                    while (remaining > 0L) {
                        val dropCount = minOf(remaining, item.getDefaultMaxStackSize().toLong()).toInt()
                        Containers.dropItemStack(level, pos.x + 0.5, pos.y + 0.5, pos.z + 0.5, ItemStack(item, dropCount))
                        remaining -= dropCount.toLong()
                    }
                }
            }
        }
        return super.playerWillDestroy(level, pos, state, player)
    }
}
