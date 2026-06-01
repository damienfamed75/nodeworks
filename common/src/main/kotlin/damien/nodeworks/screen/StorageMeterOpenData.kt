package damien.nodeworks.screen

import net.minecraft.core.BlockPos
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.world.item.ItemStack

/**
 * Open-payload for the Storage Meter settings GUI. Live-syncing fields
 * (channel, threshold, displayCount, isBelowThreshold, autocraftEnabled)
 * ride on [StorageMeterMenu]'s [net.minecraft.world.inventory.ContainerData],
 * non-syncing fields (target) seed here and commit via
 * [damien.nodeworks.network.DeviceSettingsPayload] / the menu's click handler.
 */
data class StorageMeterOpenData(
    val pos: BlockPos,
    val target: ItemStack,
    val threshold: Int,
    val channelId: Int,
    val autocraftEnabled: Boolean,
) {
    companion object {
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, StorageMeterOpenData> =
            object : StreamCodec<FriendlyByteBuf, StorageMeterOpenData> {
                override fun decode(buf: FriendlyByteBuf): StorageMeterOpenData {
                    val regBuf = buf as RegistryFriendlyByteBuf
                    val pos = buf.readBlockPos()
                    val target = ItemStack.OPTIONAL_STREAM_CODEC.decode(regBuf)
                    val threshold = buf.readVarInt()
                    val channelId = buf.readVarInt()
                    val autocraftEnabled = buf.readBoolean()
                    return StorageMeterOpenData(pos, target, threshold, channelId, autocraftEnabled)
                }

                override fun encode(buf: FriendlyByteBuf, data: StorageMeterOpenData) {
                    val regBuf = buf as RegistryFriendlyByteBuf
                    buf.writeBlockPos(data.pos)
                    ItemStack.OPTIONAL_STREAM_CODEC.encode(regBuf, data.target)
                    buf.writeVarInt(data.threshold)
                    buf.writeVarInt(data.channelId)
                    buf.writeBoolean(data.autocraftEnabled)
                }
            }
    }
}
