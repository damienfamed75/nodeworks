package damien.nodeworks

import damien.nodeworks.registry.ModBlockEntities
import damien.nodeworks.registry.ModBlocks
import net.fabricmc.api.ModInitializer
import org.slf4j.LoggerFactory

object Nodeworks : ModInitializer {
    const val MOD_ID = "nodeworks"
    private val logger = LoggerFactory.getLogger(MOD_ID)

    override fun onInitialize() {
        ModBlocks.initialize()
        ModBlockEntities.initialize()

        logger.info("Nodeworks initialized")
    }
}