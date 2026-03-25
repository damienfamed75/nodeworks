package damien.nodeworks

import damien.nodeworks.render.NodeConnectionRenderer
import net.fabricmc.api.ClientModInitializer

object NodeworksClient : ClientModInitializer {
    override fun onInitializeClient() {
        NodeConnectionRenderer.register()
    }
}