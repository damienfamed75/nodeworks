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
    private val packetSender: ((net.minecraft.network.protocol.common.custom.CustomPacketPayload) -> Unit)? = null,
    initialFailureReason: String = ""
) : AbstractContainerMenu(ModScreenHandlers.CRAFTING_CORE, syncId) {

    companion object {
        // ContainerData layout:
        //   [0][1] = bufferUsed (hi, lo), Long packed as two Ints
        //   [2][3] = bufferCapacity (hi, lo)
        //   [4]    = bufferTypesUsed (small int)
        //   [5]    = bufferTypesCapacity
        //   [6]    = isFormed (0/1)
        //   [7]    = isCrafting (0/1)
        //   [8]    = heatGenerated
        //   [9]    = heatCooled
        //   [10]   = throttle × 100 (fixed-point, e.g. 125 = 1.25×)
        const val DATA_SLOTS = 11
        private const val IDX_USED_HI = 0
        private const val IDX_USED_LO = 1
        private const val IDX_CAP_HI = 2
        private const val IDX_CAP_LO = 3
        private const val IDX_TYPES_USED = 4
        private const val IDX_TYPES_CAP = 5
        private const val IDX_FORMED = 6
        private const val IDX_CRAFTING = 7
        private const val IDX_HEAT_GEN = 8
        private const val IDX_HEAT_COOL = 9
        private const val IDX_THROTTLE_X100 = 10

        // Buffer sync runs slower (it's mostly inventory state, doesn't need to be smooth),
        // tree sync runs faster while the player has the GUI open so active-node highlights
        // and progression dots feel responsive (5 ticks ≈ 4 updates/sec).
        private const val BUFFER_SYNC_INTERVAL = 20
        private const val TREE_SYNC_INTERVAL = 5

        private fun longHi(v: Long): Int = (v ushr 32).toInt()
        private fun longLo(v: Long): Int = v.toInt()
        private fun packLong(hi: Int, lo: Int): Long =
            (hi.toLong() shl 32) or (lo.toLong() and 0xFFFFFFFFL)

        fun clientFactory(syncId: Int, playerInventory: Inventory, openData: CraftingCoreOpenData): CraftingCoreMenu {
            val data = SimpleContainerData(DATA_SLOTS)
            data.set(IDX_USED_HI, longHi(openData.bufferUsed))
            data.set(IDX_USED_LO, longLo(openData.bufferUsed))
            data.set(IDX_CAP_HI, longHi(openData.bufferCapacity))
            data.set(IDX_CAP_LO, longLo(openData.bufferCapacity))
            data.set(IDX_TYPES_USED, openData.bufferTypesUsed)
            data.set(IDX_TYPES_CAP, openData.bufferTypesCapacity)
            data.set(IDX_FORMED, if (openData.isFormed) 1 else 0)
            data.set(IDX_CRAFTING, if (openData.isCrafting) 1 else 0)
            return CraftingCoreMenu(syncId, openData.pos, data, initialFailureReason = openData.lastFailureReason)
        }

        fun createServer(syncId: Int, playerInventory: Inventory, entity: CraftingCoreBlockEntity): CraftingCoreMenu {
            val data = object : ContainerData {
                override fun get(index: Int): Int = when (index) {
                    IDX_USED_HI -> longHi(entity.bufferUsed)
                    IDX_USED_LO -> longLo(entity.bufferUsed)
                    IDX_CAP_HI -> longHi(entity.bufferCapacity)
                    IDX_CAP_LO -> longLo(entity.bufferCapacity)
                    IDX_TYPES_USED -> entity.bufferTypesUsed
                    IDX_TYPES_CAP -> entity.bufferTypesCapacity
                    IDX_FORMED -> if (entity.isFormed) 1 else 0
                    IDX_CRAFTING -> if (entity.isCrafting) 1 else 0
                    IDX_HEAT_GEN -> entity.heatGenerated
                    IDX_HEAT_COOL -> entity.heatCooled
                    IDX_THROTTLE_X100 -> (entity.throttle * 100f).toInt()
                    else -> 0
                }
                override fun set(index: Int, value: Int) {}
                override fun getCount(): Int = DATA_SLOTS
            }
            val player = playerInventory.player as? net.minecraft.server.level.ServerPlayer
            val sender: ((net.minecraft.network.protocol.common.custom.CustomPacketPayload) -> Unit)? = if (player != null) { payload ->
                player.connection.send(net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket(payload))
            } else null
            return CraftingCoreMenu(syncId, entity.blockPos, data, entity, sender, entity.lastFailureReason)
        }
    }

    val bufferUsed: Long get() = packLong(data.get(IDX_USED_HI), data.get(IDX_USED_LO))
    val bufferCapacity: Long get() = packLong(data.get(IDX_CAP_HI), data.get(IDX_CAP_LO))
    val bufferTypesUsed: Int get() = data.get(IDX_TYPES_USED)
    val bufferTypesCapacity: Int get() = data.get(IDX_TYPES_CAP)
    val isFormed: Boolean get() = data.get(IDX_FORMED) != 0
    val isCrafting: Boolean get() = data.get(IDX_CRAFTING) != 0
    val heatGenerated: Int get() = data.get(IDX_HEAT_GEN)
    val heatCooled: Int get() = data.get(IDX_HEAT_COOL)
    val throttle: Float get() = data.get(IDX_THROTTLE_X100) / 100f

    /** Client-side buffer contents, populated by BufferSyncPayload handler. */
    var clientBufferContents: List<Pair<String, Long>> = emptyList()

    /** Client-side craft tree, populated by CraftingCpuTreePayload handler. */
    var craftTree: damien.nodeworks.script.CraftTreeBuilder.CraftTreeNode? = null

    /** Client-side active node IDs, currently being worked on, amber-highlighted. */
    var activeNodeIds: Set<Int> = emptySet()

    /** Client-side completed node IDs, green-highlighted (this branch is done). */
    var completedNodeIds: Set<Int> = emptySet()

    /** Last-craft-failure reason for the footer bar. Initialized from openData, kept in
     *  sync via [damien.nodeworks.network.CpuFailurePayload] pushes. */
    var lastFailureReason: String = initialFailureReason

    private var bufferSyncTimer = 0
    private var treeSyncTimer = 0
    private var lastBufferHash = 0
    private var lastTreeHash = 0
    private var lastSentFailure: String = initialFailureReason

    init {
        addDataSlots(data)
    }

    override fun broadcastChanges() {
        super.broadcastChanges()

        val entity = serverEntity ?: return
        val sender = packetSender ?: return

        if (++bufferSyncTimer >= BUFFER_SYNC_INTERVAL) {
            bufferSyncTimer = 0
            val contents = entity.getBufferContents()
            val hash = contents.hashCode()
            if (hash != lastBufferHash) {
                lastBufferHash = hash
                sender(BufferSyncPayload(containerId, contents.entries.map { it.key to it.value }))
            }
        }
        if (++treeSyncTimer >= TREE_SYNC_INTERVAL) {
            treeSyncTimer = 0
            syncCraftTree(entity)
        }
        // Failure-reason sync, sent any time the string changes. Not throttled, changes
        // only fire on plan boundaries (rare).
        val currentFailure = entity.lastFailureReason
        if (currentFailure != lastSentFailure) {
            lastSentFailure = currentFailure
            sender(damien.nodeworks.network.CpuFailurePayload(containerId, currentFailure))
        }
    }

    private fun syncCraftTree(entity: CraftingCoreBlockEntity) {
        val sender = packetSender ?: return
        val level = entity.level as? net.minecraft.server.level.ServerLevel ?: return

        var tree: damien.nodeworks.script.CraftTreeBuilder.CraftTreeNode?
        val active: List<Int>
        val done: List<Int>

        if (entity.isCrafting && entity.originalCraftId.isNotEmpty()) {
            tree = entity.craftTreeSnapshot
            if (tree == null) {
                val snapshot = damien.nodeworks.network.NetworkDiscovery.discoverNetwork(level, entity.blockPos)
                val clampedCount = entity.originalCraftCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                tree = damien.nodeworks.script.CraftTreeBuilder.buildCraftTree(
                    entity.originalCraftId, clampedCount, level, snapshot
                )
                entity.craftTreeSnapshot = tree
            }
            active = entity.activeNodeIds.toList()
            done = entity.completedNodeIds.toList()
        } else {
            tree = null
            active = emptyList()
            done = emptyList()
        }

        // Hash includes both sets so any change in active/done triggers a resend.
        val treeHash = (tree?.hashCode() ?: 0) xor active.hashCode() xor (done.hashCode() shl 1)
        if (treeHash == lastTreeHash) return
        lastTreeHash = treeHash

        sender(damien.nodeworks.network.CraftingCpuTreePayload(containerId, tree, active, done))
    }

    override fun quickMoveStack(player: Player, index: Int): ItemStack = ItemStack.EMPTY

    override fun stillValid(player: Player): Boolean {
        return player.blockPosition().closerThan(corePos, 8.0)
    }
}
