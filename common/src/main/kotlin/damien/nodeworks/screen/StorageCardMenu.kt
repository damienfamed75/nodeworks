package damien.nodeworks.screen

import damien.nodeworks.card.CardChannel
import damien.nodeworks.card.StorageCard
import damien.nodeworks.registry.ModScreenHandlers
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.SimpleContainerData
import net.minecraft.world.item.DyeColor
import net.minecraft.world.item.ItemStack

class StorageCardMenu(
    syncId: Int,
    playerInventory: Inventory,
    private val hand: InteractionHand?,
    initialFilterMode: StorageCard.Companion.FilterMode = StorageCard.Companion.FilterMode.ALLOW,
    initialStackability: StorageCard.Companion.StackabilityFilter = StorageCard.Companion.StackabilityFilter.ANY,
    initialNbtFilter: StorageCard.Companion.NbtFilter = StorageCard.Companion.NbtFilter.ANY,
    initialFilterRules: List<String> = emptyList(),
    val initialName: String = "",
) : AbstractContainerMenu(ModScreenHandlers.STORAGE_CARD, syncId) {

    val priorityData = SimpleContainerData(1)
    val channelData = SimpleContainerData(1)

    /** Three container-data slots, one per filter dimension. Each carries an
     *  enum ordinal:
     *  - filterModeData: 0 = ALLOW, 1 = DENY
     *  - stackabilityData: 0 = ANY, 1 = STACKABLE, 2 = NON_STACKABLE
     *  - nbtFilterData: 0 = ANY, 1 = HAS_DATA, 2 = NO_DATA
     *  Auto-synced to viewers so the screen's cycle buttons reflect server
     *  state without bespoke payloads. */
    val filterModeData = SimpleContainerData(1)
    val stackabilityData = SimpleContainerData(1)
    val nbtFilterData = SimpleContainerData(1)

    /** Authoritative server-side filter rules. The screen's add/edit/delete
     *  affordances send mutations as [SetStorageCardFilterRulesPayload], the
     *  server applies them here, and [removed] persists the final list to
     *  NBT on close.
     *
     *  No S2C broadcast: the menu is opened off the player's held stack so
     *  there's only one viewer. The client mirrors [initialFilterRules]
     *  from the open payload, edits locally, and ships each commit to the
     *  server. The server's copy here is the authoritative one to write
     *  back, the client's copy is the editing buffer for the open session. */
    var filterRules: MutableList<String> = initialFilterRules.toMutableList()
        private set

    private var dirty: Boolean = false

    init {
        if (hand != null) {
            val stack = playerInventory.player.getItemInHand(hand)
            priorityData.set(0, StorageCard.getPriority(stack))
            channelData.set(0, CardChannel.get(stack).id)
            // Server side: re-read filter from the live stack so the menu's
            // initial state matches NBT exactly, even if the client's open
            // data was somehow stale.
            if (!playerInventory.player.level().isClientSide) {
                filterModeData.set(0, StorageCard.getFilterMode(stack).ordinal)
                stackabilityData.set(0, StorageCard.getStackabilityFilter(stack).ordinal)
                nbtFilterData.set(0, StorageCard.getNbtFilter(stack).ordinal)
                filterRules = StorageCard.getFilterRules(stack).toMutableList()
            } else {
                filterModeData.set(0, initialFilterMode.ordinal)
                stackabilityData.set(0, initialStackability.ordinal)
                nbtFilterData.set(0, initialNbtFilter.ordinal)
            }
        } else {
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

    /** Server-side accessors used by the network payload handlers. The screen
     *  drives all filter mutations through these so the dirty flag stays
     *  honest, no out-of-band writes to [filterRules] / [filterModeData]. */

    fun toggleFilterMode() {
        val next = if (getFilterMode() == StorageCard.Companion.FilterMode.ALLOW)
            StorageCard.Companion.FilterMode.DENY
        else
            StorageCard.Companion.FilterMode.ALLOW
        filterModeData.set(0, next.ordinal)
        dirty = true
    }

    /** Cycle through ANY → STACKABLE → NON_STACKABLE → ANY. */
    fun cycleStackability() {
        val current = getStackabilityFilter().ordinal
        val next = (current + 1) % StorageCard.Companion.StackabilityFilter.entries.size
        stackabilityData.set(0, next)
        dirty = true
    }

    /** Cycle through ANY → HAS_DATA → NO_DATA → ANY. */
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

    /** Apply a player-supplied name to the held card. Empty / blank input
     *  clears [DataComponents.CUSTOM_NAME] so the card reverts to its
     *  translated item name. */
    fun setCardName(player: Player, name: String) {
        if (hand == null) return
        val stack = player.getItemInHand(hand)
        if (stack.item !is StorageCard) return
        applyCardName(stack, name)
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
            } // direct value set
            // Channel picker uses ids 2000..2015, outside the priority range so the two
            // controls can't accidentally collide if one expands later.
            id in 2000..2015 -> {
                channelData.set(0, id - 2000); dirty = true
            }
            // 3000 = toggle filter mode (whitelist <-> blacklist).
            // 3001 = cycle stackability (any/stackable/non-stackable).
            // 3002 = cycle NBT (any/has-data/no-data).
            // Filter rule list mutations don't fit through clickMenuButton
            // because they carry strings, those use
            // [SetStorageCardFilterRulesPayload].
            id == 3000 -> toggleFilterMode()
            id == 3001 -> cycleStackability()
            id == 3002 -> cycleNbtFilter()
        }
        return true
    }

    override fun removed(player: Player) {
        super.removed(player)
        if (player.level().isClientSide) return
        if (hand == null) return
        if (!dirty) return
        val stack = player.getItemInHand(hand)
        if (stack.item is StorageCard) {
            StorageCard.setPriority(stack, priorityData.get(0))
            CardChannel.set(stack, getChannel())
            StorageCard.setFilterMode(stack, getFilterMode())
            StorageCard.setStackabilityFilter(stack, getStackabilityFilter())
            StorageCard.setNbtFilter(stack, getNbtFilter())
            StorageCard.setFilterRules(stack, filterRules)
        }
    }

    override fun quickMoveStack(player: Player, slotIndex: Int): ItemStack = ItemStack.EMPTY

    override fun stillValid(player: Player): Boolean {
        if (hand == null) return true
        return player.getItemInHand(hand).item is StorageCard
    }

    companion object {
        fun clientFactory(syncId: Int, playerInventory: Inventory, data: StorageCardOpenData): StorageCardMenu {
            val hand =
                if (data.handOrdinal < InteractionHand.entries.size) InteractionHand.entries[data.handOrdinal] else null
            val mode = StorageCard.Companion.FilterMode.entries.getOrNull(data.filterMode)
                ?: StorageCard.Companion.FilterMode.ALLOW
            val stackability = StorageCard.Companion.StackabilityFilter.entries.getOrNull(data.stackability)
                ?: StorageCard.Companion.StackabilityFilter.ANY
            val nbt = StorageCard.Companion.NbtFilter.entries.getOrNull(data.nbtFilter)
                ?: StorageCard.Companion.NbtFilter.ANY
            return StorageCardMenu(syncId, playerInventory, hand, mode, stackability, nbt, data.filterRules, data.cardName)
        }
    }
}
