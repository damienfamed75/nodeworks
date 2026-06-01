package damien.nodeworks.screen

import net.minecraft.core.BlockPos
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.world.item.ItemStack

/**
 * Open-payload for the Craft Requester settings GUI. Live-syncing fields
 * (batchSize, channel) ride on [CraftRequesterMenu]'s
 * [net.minecraft.world.inventory.ContainerData], non-syncing fields (target)
 * seed here and commit through the menu's click handler or
 * [damien.nodeworks.network.SetCraftRequesterTargetPayload]. Error lines are
 * read off the client BE directly each frame.
 */
data class CraftRequesterOpenData(
    val pos: BlockPos,
    val target: ItemStack,
    val batchSize: Int,
    val channelId: Int,
) {
    companion object {
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, CraftRequesterOpenData> =
            object : StreamCodec<FriendlyByteBuf, CraftRequesterOpenData> {
                override fun decode(buf: FriendlyByteBuf): CraftRequesterOpenData {
                    val regBuf = buf as RegistryFriendlyByteBuf
                    val pos = buf.readBlockPos()
                    val target = ItemStack.OPTIONAL_STREAM_CODEC.decode(regBuf)
                    val batch = buf.readVarInt()
                    val channelId = buf.readVarInt()
                    return CraftRequesterOpenData(pos, target, batch, channelId)
                }

                override fun encode(buf: FriendlyByteBuf, data: CraftRequesterOpenData) {
                    val regBuf = buf as RegistryFriendlyByteBuf
                    buf.writeBlockPos(data.pos)
                    ItemStack.OPTIONAL_STREAM_CODEC.encode(regBuf, data.target)
                    buf.writeVarInt(data.batchSize)
                    buf.writeVarInt(data.channelId)
                }
            }
    }
}
