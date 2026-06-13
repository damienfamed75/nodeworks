package damien.nodeworks.screen

import damien.nodeworks.block.entity.StorageRepoBlockEntity
import damien.nodeworks.card.StorageCard
import damien.nodeworks.registry.ModScreenHandlers
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.SimpleContainerData
import net.minecraft.world.item.DyeColor
import net.minecraft.world.item.ItemStack

/**
 * Menu for the Storage Repo's filter/channel/priority editor. Mirrors
 * [StorageCardMenu] feature-for-feature except:
 *
 *   * Targets a BE at [pos] instead of a held card.
 *   * No name editing (Repos rename via anvil on the block item).
 *   * No custom side picker (Repo geometry is symmetric, no defaultFace concept).
 *
 * All writes route through the cluster anchor via [StorageRepoBlockEntity.getAnchor]
 * so a click anywhere in the cluster edits the shared settings.
 */
class StorageRepoMenu(
    syncId: Int,
    playerInventory: Inventory,
    val pos: BlockPos,
    initialFilterMode: StorageCard.Companion.FilterMode = StorageCard.Companion.FilterMode.ALLOW,
    initialStackability: StorageCard.Companion.StackabilityFilter = StorageCard.Companion.StackabilityFilter.ANY,
    initialNbtFilter: StorageCard.Companion.NbtFilter = StorageCard.Companion.NbtFilter.ANY,
    initialFilterRules: List<String> = emptyList(),
    initialPriority: Int = 0,
    initialChannel: DyeColor = DyeColor.WHITE,
) : AbstractContainerMenu(ModScreenHandlers.STORAGE_REPO, syncId) {

    val priorityData = SimpleContainerData(1)
    val channelData = SimpleContainerData(1)

    /** Three filter-dimension ordinals, auto-synced to the viewer. Same encoding as
     *  [StorageCardMenu]: filterMode 0/1 = ALLOW/DENY, stackability/nbt 0/1/2 =
     *  ANY/STACKABLE-or-HAS_DATA/NON_STACKABLE-or-NO_DATA. */
    val filterModeData = SimpleContainerData(1)
    val stackabilityData = SimpleContainerData(1)
    val nbtFilterData = SimpleContainerData(1)

    /** Authoritative filter rules. Server-side: filled from the anchor BE at open
     *  time. Mutations come over the wire via [SetStorageRepoFilterRulesPayload]
     *  and write here; [removed] flushes the final state to the anchor BE. */
    var filterRules: MutableList<String> = initialFilterRules.toMutableList()
        private set

    private var dirty: Boolean = false

    init {
        val level = playerInventory.player.level()
        if (!level.isClientSide) {
            // Server side: re-read settings from the cluster anchor so the menu's
            // initial state is canonical, even if the open payload was stale.
            val be = level.getBlockEntity(pos) as? StorageRepoBlockEntity
            val anchor = be?.getAnchor() ?: be
            if (anchor != null) {
                priorityData.set(0, anchor.priority)
                channelData.set(0, anchor.channel.id)
                filterModeData.set(0, anchor.filterMode.ordinal)
                stackabilityData.set(0, anchor.stackability.ordinal)
                nbtFilterData.set(0, anchor.nbtFilter.ordinal)
                filterRules = anchor.filterRules.toMutableList()
            } else {
                priorityData.set(0, initialPriority)
                channelData.set(0, initialChannel.id)
                filterModeData.set(0, initialFilterMode.ordinal)
                stackabilityData.set(0, initialStackability.ordinal)
                nbtFilterData.set(0, initialNbtFilter.ordinal)
            }
        } else {
            priorityData.set(0, initialPriority)
            channelData.set(0, initialChannel.id)
            filterModeData.set(0, initialFilterMode.ordinal)
            stackabilityData.set(0, initialStackability.ordinal)
            nbtFilterData.set(0, initialNbtFilter.ordinal)
        }
        addDataSlots(priorityData)
        addDataSlots(channelData)
        addDataSlots(filterModeData)
        addDataSlots(stackabilityData)
        addDataSlots(nbtFilterData)
    }

    fun getPriority(): Int = priorityData.get(0)

    fun getChannel(): DyeColor =
        runCatching { DyeColor.byId(channelData.get(0)) }.getOrDefault(DyeColor.WHITE)

    fun getFilterMode(): StorageCard.Companion.FilterMode {
        val ord = filterModeData.get(0)
        return StorageCard.Companion.FilterMode.entries.getOrNull(ord)
            ?: StorageCard.Companion.FilterMode.ALLOW
    }

    fun getStackabilityFilter(): StorageCard.Companion.StackabilityFilter {
        val ord = stackabilityData.get(0)
        return StorageCard.Companion.StackabilityFilter.entries.getOrNull(ord)
            ?: StorageCard.Companion.StackabilityFilter.ANY
    }

    fun getNbtFilter(): StorageCard.Companion.NbtFilter {
        val ord = nbtFilterData.get(0)
        return StorageCard.Companion.NbtFilter.entries.getOrNull(ord)
            ?: StorageCard.Companion.NbtFilter.ANY
    }

    fun toggleFilterMode() {
        val next = if (getFilterMode() == StorageCard.Companion.FilterMode.ALLOW)
            StorageCard.Companion.FilterMode.DENY
        else
            StorageCard.Companion.FilterMode.ALLOW
        filterModeData.set(0, next.ordinal)
        dirty = true
    }

    fun cycleStackability() {
        val current = getStackabilityFilter().ordinal
        val next = (current + 1) % StorageCard.Companion.StackabilityFilter.entries.size
        stackabilityData.set(0, next)
        dirty = true
    }

    fun cycleNbtFilter() {
        val current = getNbtFilter().ordinal
        val next = (current + 1) % StorageCard.Companion.NbtFilter.entries.size
        nbtFilterData.set(0, next)
        dirty = true
    }

    fun replaceFilterRules(rules: List<String>) {
        val cleaned = rules
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .take(StorageCardOpenData.MAX_RULES)
        if (filterRules == cleaned) return
        filterRules = cleaned.toMutableList()
        dirty = true
    }

    override fun clickMenuButton(player: Player, id: Int): Boolean {
        when {
            id == 0 -> {
                priorityData.set(0, (priorityData.get(0) - 1).coerceIn(0, 999)); dirty = true
            }
            id == 1 -> {
                priorityData.set(0, (priorityData.get(0) + 1).coerceIn(0, 999)); dirty = true
            }
            id in 100..1099 -> {
                priorityData.set(0, (id - 100).coerceIn(0, 999)); dirty = true
            }
            id in 2000..2015 -> {
                channelData.set(0, id - 2000); dirty = true
            }
            id == 3000 -> toggleFilterMode()
            id == 3001 -> cycleStackability()
            id == 3002 -> cycleNbtFilter()
        }
        return true
    }

    override fun removed(player: Player) {
        super.removed(player)
        if (player.level().isClientSide) return
        if (!dirty) return
        val be = player.level().getBlockEntity(pos) as? StorageRepoBlockEntity ?: return
        be.applySettings(
            channel = getChannel(),
            filterMode = getFilterMode(),
            filterRules = filterRules,
            stackability = getStackabilityFilter(),
            nbtFilter = getNbtFilter(),
            priority = priorityData.get(0),
        )
    }

    override fun quickMoveStack(player: Player, slotIndex: Int): ItemStack = ItemStack.EMPTY

    override fun stillValid(player: Player): Boolean {
        val be = player.level().getBlockEntity(pos)
        return be is StorageRepoBlockEntity
    }

    companion object {
        fun clientFactory(syncId: Int, playerInventory: Inventory, data: StorageRepoOpenData): StorageRepoMenu {
            val mode = StorageCard.Companion.FilterMode.entries.getOrNull(data.filterMode)
                ?: StorageCard.Companion.FilterMode.ALLOW
            val stackability = StorageCard.Companion.StackabilityFilter.entries.getOrNull(data.stackability)
                ?: StorageCard.Companion.StackabilityFilter.ANY
            val nbt = StorageCard.Companion.NbtFilter.entries.getOrNull(data.nbtFilter)
                ?: StorageCard.Companion.NbtFilter.ANY
            val channel = runCatching { DyeColor.byId(data.channelId) }.getOrDefault(DyeColor.WHITE)
            return StorageRepoMenu(
                syncId, playerInventory, data.pos,
                mode, stackability, nbt, data.filterRules,
                data.priority, channel,
            )
        }
    }
}
