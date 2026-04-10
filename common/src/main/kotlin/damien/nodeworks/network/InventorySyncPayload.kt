package damien.nodeworks.network

import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation

/**
 * Server → Client: Syncs inventory terminal data.
 * fullUpdate=true: complete inventory — may be chunked across multiple packets.
 *   chunkIndex/totalChunks track pagination. Client clears on first chunk,
 *   buffers entries, and marks view dirty when last chunk arrives.
 * fullUpdate=false: delta (only changed/removed entries, always single packet)
 */
data class InventorySyncPayload(
    val fullUpdate: Boolean,
    val entries: List<SyncEntry>,
    val removedSerials: List<Long>,
    val chunkIndex: Int = 0,
    val totalChunks: Int = 1
) : CustomPacketPayload {

    data class SyncEntry(
        val serial: Long,
        val itemId: String?,  // null = amount-only update for known serial
        val name: String?,
        val count: Long,
        val maxStackSize: Int,
        val hasData: Boolean,
        val craftable: Boolean = false
    )

    companion object {
        val TYPE: CustomPacketPayload.Type<InventorySyncPayload> = CustomPacketPayload.Type(
            ResourceLocation.fromNamespaceAndPath("nodeworks", "inventory_sync")
        )
        val CODEC: StreamCodec<FriendlyByteBuf, InventorySyncPayload> = CustomPacketPayload.codec(
            { p, buf ->
                buf.writeBoolean(p.fullUpdate)
                buf.writeVarInt(p.chunkIndex)
                buf.writeVarInt(p.totalChunks)
                buf.writeVarInt(p.entries.size)
                for (entry in p.entries) {
                    buf.writeVarLong(entry.serial)
                    val hasItemId = entry.itemId != null
                    buf.writeBoolean(hasItemId)
                    if (hasItemId) {
                        buf.writeUtf(entry.itemId!!, 256)
                        buf.writeUtf(entry.name ?: "", 256)
                        buf.writeVarInt(entry.maxStackSize)
                        buf.writeBoolean(entry.hasData)
                        buf.writeBoolean(entry.craftable)
                    }
                    buf.writeVarLong(entry.count)
                }
                buf.writeVarInt(p.removedSerials.size)
                for (serial in p.removedSerials) {
                    buf.writeVarLong(serial)
                }
            },
            { buf ->
                val fullUpdate = buf.readBoolean()
                val chunkIndex = buf.readVarInt()
                val totalChunks = buf.readVarInt()
                val entryCount = buf.readVarInt()
                val entries = (0 until entryCount).map {
                    val serial = buf.readVarLong()
                    val hasItemId = buf.readBoolean()
                    val itemId: String?
                    val name: String?
                    val maxStackSize: Int
                    val hasData: Boolean
                    val craftable: Boolean
                    if (hasItemId) {
                        itemId = buf.readUtf(256)
                        name = buf.readUtf(256)
                        maxStackSize = buf.readVarInt()
                        hasData = buf.readBoolean()
                        craftable = buf.readBoolean()
                    } else {
                        itemId = null
                        name = null
                        maxStackSize = 64
                        hasData = false
                        craftable = false
                    }
                    val count = buf.readVarLong()
                    SyncEntry(serial, itemId, name, count, maxStackSize, hasData, craftable)
                }
                val removedCount = buf.readVarInt()
                val removedSerials = (0 until removedCount).map { buf.readVarLong() }
                InventorySyncPayload(fullUpdate, entries, removedSerials, chunkIndex, totalChunks)
            }
        )
    }

    override fun type() = TYPE
}
