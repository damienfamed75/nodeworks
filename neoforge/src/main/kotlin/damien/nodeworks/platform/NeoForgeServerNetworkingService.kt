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

    override fun sendToPlayersTracking(
        level: ServerLevel,
        positions: Collection<net.minecraft.core.BlockPos>,
        payload: CustomPacketPayload,
    ) {
        val chunks = HashSet<net.minecraft.world.level.ChunkPos>()
        for (pos in positions) chunks.add(net.minecraft.world.level.ChunkPos(net.minecraft.core.SectionPos.blockToSectionCoord(pos.x), net.minecraft.core.SectionPos.blockToSectionCoord(pos.z)))
        val recipients = HashSet<ServerPlayer>()
        for (chunk in chunks) recipients.addAll(level.chunkSource.chunkMap.getPlayers(chunk, false))
        for (player in recipients) PacketDistributor.sendToPlayer(player, payload)
    }

    override fun sendToPlayer(
        player: ServerPlayer,
        payload: CustomPacketPayload,
    ) {
        PacketDistributor.sendToPlayer(player, payload)
    }
}
