package damien.nodeworks.platform

import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
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

    override fun sendToPlayer(
        player: ServerPlayer,
        payload: CustomPacketPayload,
    ) {
        PacketDistributor.sendToPlayer(player, payload)
    }
}
