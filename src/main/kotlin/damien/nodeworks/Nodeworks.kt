package damien.nodeworks

import damien.nodeworks.item.NetworkWrenchItem
import damien.nodeworks.network.TerminalPackets
import damien.nodeworks.registry.ModBlockEntities
import damien.nodeworks.registry.ModBlocks
import damien.nodeworks.registry.ModItems
import damien.nodeworks.registry.ModScreenHandlers
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import org.slf4j.LoggerFactory

object Nodeworks : ModInitializer {
    const val MOD_ID = "nodeworks"
    private val logger = LoggerFactory.getLogger(MOD_ID)
    var tickCount = 0L
        private set

    override fun onInitialize() {
        ModBlocks.initialize()
        ModBlockEntities.initialize()
        ModItems.initialize()
        ModScreenHandlers.initialize()

        TerminalPackets.registerPayloads()
        TerminalPackets.registerServerHandlers()

        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ ->
            NetworkWrenchItem.clearSelection(handler.player.uuid)
        }

        ServerTickEvents.END_SERVER_TICK.register { server ->
            tickCount++
            TerminalPackets.tickAll(server, tickCount)
        }

        logger.info("Nodeworks initialized")
    }
}
