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
    private val packetSender: ((net.minecraft.network.protocol.common.custom.CustomPacketPayload) -> Unit)? = null
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
            val player = playerInventory.player as? net.minecraft.server.level.ServerPlayer
            val sender: ((net.minecraft.network.protocol.common.custom.CustomPacketPayload) -> Unit)? = if (player != null) { payload ->
                player.connection.send(net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket(payload))
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

    /** Client-side craft tree, populated by CraftingCpuTreePayload handler. */
    var craftTree: damien.nodeworks.script.CraftTreeBuilder.CraftTreeNode? = null

    /** Client-side active step item IDs (highlighted in the tree). */
    var activeSteps: Set<String> = emptySet()

    private var syncTimer = 0
    private var lastBufferHash = 0
    private var lastTreeHash = 0

    init {
        addDataSlots(data)
    }

    override fun broadcastChanges() {
        super.broadcastChanges()

        val entity = serverEntity ?: return
        val sender = packetSender ?: return

        if (++syncTimer < BUFFER_SYNC_INTERVAL) return
        syncTimer = 0

        // Sync buffer contents
        val contents = entity.getBufferContents()
        val hash = contents.hashCode()
        if (hash != lastBufferHash) {
            lastBufferHash = hash
            sender(BufferSyncPayload(containerId, contents.entries.map { it.key to it.value }))
        }

        // Sync craft tree
        syncCraftTree(entity)
    }

    private fun syncCraftTree(entity: CraftingCoreBlockEntity) {
        val sender = packetSender ?: return
        val level = entity.level as? net.minecraft.server.level.ServerLevel ?: return

        var tree: damien.nodeworks.script.CraftTreeBuilder.CraftTreeNode?
        val steps: List<String>

        if (entity.isCrafting && entity.originalCraftId.isNotEmpty()) {
            // Use the tree snapshot taken at craft start — reflects original storage state
            tree = entity.craftTreeSnapshot
            if (tree == null) {
                // Fallback: build now (e.g., after world reload where transient field was lost)
                val snapshot = damien.nodeworks.network.NetworkDiscovery.discoverNetwork(level, entity.blockPos)
                tree = damien.nodeworks.script.CraftTreeBuilder.buildCraftTree(
                    entity.originalCraftId, entity.originalCraftCount, level, snapshot
                )
                entity.craftTreeSnapshot = tree
            }

            // Active steps update every sync cycle
            steps = entity.pendingOutputs.map { it.first }.distinct().ifEmpty {
                if (entity.pendingCount > 0) emptyList() else listOf(entity.originalCraftId)
            }
        } else {
            tree = null
            steps = emptyList()
        }

        val treeHash = (tree?.hashCode() ?: 0) + steps.hashCode()
        if (treeHash == lastTreeHash) return
        lastTreeHash = treeHash

        sender(damien.nodeworks.network.CraftingCpuTreePayload(containerId, tree, steps))
    }

    override fun quickMoveStack(player: Player, index: Int): ItemStack = ItemStack.EMPTY

    override fun stillValid(player: Player): Boolean {
        return player.blockPosition().closerThan(corePos, 8.0)
    }
}
