package damien.nodeworks.screen

import damien.nodeworks.block.entity.CraftingCoreBlockEntity
import damien.nodeworks.network.BufferSyncPayload
import damien.nodeworks.registry.ModScreenHandlers
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.ContainerData
import net.minecraft.world.inventory.SimpleContainerData
import net.minecraft.world.item.ItemStack

class CraftingCoreMenu(
    syncId: Int,
    val corePos: BlockPos,
    private val data: ContainerData = SimpleContainerData(DATA_SLOTS),
    private val serverEntity: CraftingCoreBlockEntity? = null,
    private val packetSender: ((BufferSyncPayload) -> Unit)? = null
) : AbstractContainerMenu(ModScreenHandlers.CRAFTING_CORE, syncId) {

    companion object {
        const val DATA_SLOTS = 4
        private const val BUFFER_SYNC_INTERVAL = 20

        fun clientFactory(syncId: Int, playerInventory: Inventory, openData: CraftingCoreOpenData): CraftingCoreMenu {
            val data = SimpleContainerData(DATA_SLOTS)
            data.set(0, openData.bufferUsed)
            data.set(1, openData.bufferCapacity)
            data.set(2, if (openData.isFormed) 1 else 0)
            data.set(3, if (openData.isCrafting) 1 else 0)
            return CraftingCoreMenu(syncId, openData.pos, data)
        }

        fun createServer(syncId: Int, playerInventory: Inventory, entity: CraftingCoreBlockEntity): CraftingCoreMenu {
            val data = object : ContainerData {
                override fun get(index: Int): Int = when (index) {
                    0 -> entity.bufferUsed
                    1 -> entity.bufferCapacity
                    2 -> if (entity.isFormed) 1 else 0
                    3 -> if (entity.isCrafting) 1 else 0
                    else -> 0
                }
                override fun set(index: Int, value: Int) {}
                override fun getCount(): Int = DATA_SLOTS
            }
            // Create a packet sender that uses the player's connection
            // This is platform-agnostic — both Fabric and NeoForge support ServerPlayer.connection
            val player = playerInventory.player as? net.minecraft.server.level.ServerPlayer
            val sender: ((BufferSyncPayload) -> Unit)? = if (player != null) { payload ->
                // Use the vanilla custom payload packet wrapper
                val packet = net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket(payload)
                player.connection.send(packet)
            } else null
            return CraftingCoreMenu(syncId, entity.blockPos, data, entity, sender)
        }
    }

    val bufferUsed: Int get() = data.get(0)
    val bufferCapacity: Int get() = data.get(1)
    val isFormed: Boolean get() = data.get(2) != 0
    val isCrafting: Boolean get() = data.get(3) != 0

    /** Client-side buffer contents, populated by BufferSyncPayload handler. */
    var clientBufferContents: List<Pair<String, Int>> = emptyList()

    private var syncTimer = 0
    private var lastBufferHash = 0

    init {
        addDataSlots(data)
    }

    override fun broadcastChanges() {
        super.broadcastChanges()

        val entity = serverEntity ?: return
        val sender = packetSender ?: return

        if (++syncTimer < BUFFER_SYNC_INTERVAL) return
        syncTimer = 0

        val contents = entity.getBufferContents()
        val hash = contents.hashCode()
        if (hash == lastBufferHash) return
        lastBufferHash = hash

        sender(BufferSyncPayload(containerId, contents.entries.map { it.key to it.value }))
    }

    override fun quickMoveStack(player: Player, index: Int): ItemStack = ItemStack.EMPTY

    override fun stillValid(player: Player): Boolean {
        return player.blockPosition().closerThan(corePos, 8.0)
    }
}
