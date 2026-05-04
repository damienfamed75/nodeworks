package damien.nodeworks.screen

import net.minecraft.core.BlockPos
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec

/**
 * Initial settings shipped to the client when an Import Chest GUI opens. The
 * menu's [ContainerData] keeps these fields in sync after open, this struct is
 * just the open-time seed (so the GUI shows the right initial values without a
 * round-trip). [channelNbtInt] is the [damien.nodeworks.network.ChannelFilter]
 * NBT encoding (-1 = ALL, 0..15 = DyeColor.id).
 */
data class ImportChestOpenData(
    val pos: BlockPos,
    val channelNbtInt: Int,
    val redstoneMode: Int,
    val roundRobin: Boolean,
    val tickInterval: Int,
) {
    companion object {
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, ImportChestOpenData> =
            object : StreamCodec<FriendlyByteBuf, ImportChestOpenData> {
                override fun decode(buf: FriendlyByteBuf): ImportChestOpenData = ImportChestOpenData(
                    pos = buf.readBlockPos(),
                    channelNbtInt = buf.readVarInt(),
                    redstoneMode = buf.readVarInt(),
                    roundRobin = buf.readBoolean(),
                    tickInterval = buf.readVarInt(),
                )

                override fun encode(buf: FriendlyByteBuf, data: ImportChestOpenData) {
                    buf.writeBlockPos(data.pos)
                    buf.writeVarInt(data.channelNbtInt)
                    buf.writeVarInt(data.redstoneMode)
                    buf.writeBoolean(data.roundRobin)
                    buf.writeVarInt(data.tickInterval)
                }
            }
    }
}
