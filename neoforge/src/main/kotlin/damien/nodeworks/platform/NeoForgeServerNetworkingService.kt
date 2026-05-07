package damien.nodeworks.platform

import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.server.level.ServerLevel
import net.neoforged.neoforge.network.PacketDistributor

class NeoForgeServerNetworkingService : ServerNetworkingService {
    override fun sendToPlayersInDimension(
        level: ServerLevel,
        payload: CustomPacketPayload,
    ) {
        for (player in level.players()) {
            PacketDistributor.sendToPlayer(player, payload)
        }
    }
}
