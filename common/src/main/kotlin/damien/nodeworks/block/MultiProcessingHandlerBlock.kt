package damien.nodeworks.block

import com.mojang.serialization.MapCodec
import damien.nodeworks.block.entity.MultiProcessingHandlerBlockEntity
import damien.nodeworks.block.entity.ProcessingHandlerBlockEntity
import damien.nodeworks.platform.PlatformServices
import damien.nodeworks.screen.MultiProcessingHandlerMenu
import damien.nodeworks.screen.MultiProcessingHandlerOpenData
import damien.nodeworks.screen.MultiProcessingHandlerServerLogic
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState

/**
 * The Processing Handler that owns many sets. Same shape, faces, placement and
 * micro-network as [ProcessingHandlerBlock], only the entity and the menu differ.
 */
class MultiProcessingHandlerBlock(properties: Properties) : ProcessingHandlerBlock(properties) {

    companion object {
        val CODEC: MapCodec<MultiProcessingHandlerBlock> = simpleCodec(::MultiProcessingHandlerBlock)
    }

    override fun codec(): MapCodec<out BaseEntityBlock> = CODEC

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity =
        MultiProcessingHandlerBlockEntity(pos, state)

    override fun openMenu(level: ServerLevel, player: ServerPlayer, entity: ProcessingHandlerBlockEntity) {
        val multi = entity as? MultiProcessingHandlerBlockEntity ?: return
        val openData = MultiProcessingHandlerServerLogic.buildOpenData(level, multi)
        PlatformServices.menu.openExtendedMenu(
            player,
            Component.translatable("container.nodeworks.multi_processing_handler"),
            openData,
            MultiProcessingHandlerOpenData.STREAM_CODEC,
            { syncId, inv, _ -> MultiProcessingHandlerMenu.createServer(syncId, inv, multi) },
        )
    }
}
