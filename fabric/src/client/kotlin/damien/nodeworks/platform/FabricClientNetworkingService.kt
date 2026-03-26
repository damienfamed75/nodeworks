package damien.nodeworks.platform

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.network.protocol.common.custom.CustomPacketPayload

class FabricClientNetworkingService : ClientNetworkingService {
    override fun sendToServer(payload: CustomPacketPayload) {
        ClientPlayNetworking.send(payload)
    }
}
