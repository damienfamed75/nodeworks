package damien.nodeworks.screen

import net.minecraft.core.BlockPos
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec

/**
 * Server → client payload fired when an Inventory Terminal menu opens. Describes the
 * *shape* of the menu the client is about to instantiate so its slot layout matches
 * the server's.
 *
 * [terminalPos], null when opened from a Handheld (no world position). Drives
 * client-side decisions about whether to send server-round-trip packets tagged with
 * a block position (SetLayoutPayload etc.).
 *
 * [hasCrystalSlot], true for Handheld menus. Client uses it to size the menu's slot
 * list identically to the server, which is critical because
 * [net.minecraft.world.inventory.AbstractContainerMenu] syncs slot contents by
 * *index*, a client with one fewer slot than the server would misalign every sync.
 */
data class InventoryTerminalOpenData(
    val terminalPos: BlockPos?,
    val hasCrystalSlot: Boolean = false,
) {
    companion object {
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, InventoryTerminalOpenData> = object : StreamCodec<FriendlyByteBuf, InventoryTerminalOpenData> {
            // Shape: [hasPos:bool] [pos?:BlockPos] [hasCrystalSlot:bool]. Two boolean
            // bytes + optional BlockPos. Cheap, open packets fire once per menu.
            override fun decode(buf: FriendlyByteBuf): InventoryTerminalOpenData {
                val hasPos = buf.readBoolean()
                val pos = if (hasPos) buf.readBlockPos() else null
                val hasCrystalSlot = buf.readBoolean()
                return InventoryTerminalOpenData(pos, hasCrystalSlot)
            }

            override fun encode(buf: FriendlyByteBuf, data: InventoryTerminalOpenData) {
                if (data.terminalPos != null) {
                    buf.writeBoolean(true)
                    buf.writeBlockPos(data.terminalPos)
                } else {
                    buf.writeBoolean(false)
                }
                buf.writeBoolean(data.hasCrystalSlot)
            }
        }
    }
}
