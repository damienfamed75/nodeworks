package damien.nodeworks

import damien.nodeworks.item.NetworkWrenchItem
import damien.nodeworks.registry.ModBlockEntities
import damien.nodeworks.registry.ModBlocks
import damien.nodeworks.registry.ModItems
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import org.slf4j.LoggerFactory

object Nodeworks : ModInitializer {
    const val MOD_ID = "nodeworks"
    private val logger = LoggerFactory.getLogger(MOD_ID)

    override fun onInitialize() {
        ModBlocks.initialize()
        ModBlockEntities.initialize()
        ModItems.initialize()

        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ ->
            NetworkWrenchItem.clearSelection(handler.player.uuid)
        }

        logger.info("Nodeworks initialized")
    }
}