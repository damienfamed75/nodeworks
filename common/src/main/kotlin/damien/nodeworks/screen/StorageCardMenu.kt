package damien.nodeworks.screen

import damien.nodeworks.card.CardChannel
import damien.nodeworks.card.StorageCard
import damien.nodeworks.network.ChannelFilter
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
    initialCustomSide: damien.nodeworks.screen.widget.RelDir? = null,
    initialMode: StorageCard.Companion.Mode = StorageCard.Companion.Mode.STORAGE,
    initialTickInterval: Int = StorageCard.DEFAULT_TICK_INTERVAL,
    initialRoundRobin: Boolean = false,
    initialBatch: Int = 0,
    initialKeep: Int = 0,
    val initialTags: List<String> = emptyList(),
    val knownTags: List<String> = emptyList(),
) : AbstractContainerMenu(ModScreenHandlers.STORAGE_CARD, syncId), FilterRuleMenu {

    val priorityData = SimpleContainerData(1)

    /** [ChannelFilter.toNbtInt]: -1 when no channel is set, else the colour id. In
     *  Storage mode an unset channel reads as white, in Export and Import as every card. */
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

    /** -1 = no override (use default face), 0..5 = [RelDir] ordinal. */
    val customSideData = SimpleContainerData(1)

    /** [StorageCard.Companion.Mode] ordinal. */
    val modeData = SimpleContainerData(1)
    val tickIntervalData = SimpleContainerData(1)
    /** 0 = off, 1 = on. */
    val roundRobinData = SimpleContainerData(1)
    val batchData = SimpleContainerData(1)
    val keepData = SimpleContainerData(1)

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
        val stack = if (hand != null) playerInventory.player.getItemInHand(hand) else null
        if (stack != null) {
            priorityData.set(0, StorageCard.getPriority(stack))
            channelData.set(0, StorageCard.getChannelFilter(stack).toNbtInt())
        }
        // Server side: re-read everything from the live stack so the menu's initial state
        // matches NBT exactly. The client takes the open payload and lets the data slots
        // correct it.
        if (stack != null && !playerInventory.player.level().isClientSide) {
            filterModeData.set(0, StorageCard.getFilterMode(stack).ordinal)
            stackabilityData.set(0, StorageCard.getStackabilityFilter(stack).ordinal)
            nbtFilterData.set(0, StorageCard.getNbtFilter(stack).ordinal)
            filterRules = StorageCard.getFilterRules(stack).toMutableList()
            customSideData.set(0, StorageCard.getCustomSide(stack)?.ordinal ?: -1)
            modeData.set(0, StorageCard.getMode(stack).ordinal)
            tickIntervalData.set(0, StorageCard.getTickInterval(stack))
            roundRobinData.set(0, if (StorageCard.getRoundRobin(stack)) 1 else 0)
            batchData.set(0, StorageCard.getBatch(stack))
            keepData.set(0, StorageCard.getKeep(stack))
        } else {
            filterModeData.set(0, initialFilterMode.ordinal)
            stackabilityData.set(0, initialStackability.ordinal)
            nbtFilterData.set(0, initialNbtFilter.ordinal)
            customSideData.set(0, initialCustomSide?.ordinal ?: -1)
            modeData.set(0, initialMode.ordinal)
            tickIntervalData.set(0, initialTickInterval)
            roundRobinData.set(0, if (initialRoundRobin) 1 else 0)
            batchData.set(0, initialBatch)
            keepData.set(0, initialKeep)
        }
        addDataSlots(priorityData)
        addDataSlots(channelData)
        addDataSlots(filterModeData)
        addDataSlots(stackabilityData)
        addDataSlots(nbtFilterData)
        addDataSlots(customSideData)
        addDataSlots(modeData)
        addDataSlots(tickIntervalData)
        addDataSlots(roundRobinData)
        addDataSlots(batchData)
        addDataSlots(keepData)
    }

    fun getPriority(): Int = priorityData.get(0)

    fun getChannelFilter(): ChannelFilter = ChannelFilter.fromNbtInt(channelData.get(0))

    /** The colour the card shows: the set channel, else white. */
    fun getChannel(): DyeColor = (getChannelFilter() as? ChannelFilter.Color)?.color ?: DyeColor.WHITE

    fun getMode(): StorageCard.Companion.Mode =
        StorageCard.Companion.Mode.entries.getOrNull(modeData.get(0)) ?: StorageCard.Companion.Mode.STORAGE

    fun getTickInterval(): Int = tickIntervalData.get(0).coerceIn(StorageCard.MIN_TICK_INTERVAL, StorageCard.MAX_TICK_INTERVAL)

    fun getRoundRobin(): Boolean = roundRobinData.get(0) != 0

    fun getBatch(): Int = batchData.get(0).coerceIn(0, StorageCard.MAX_BATCH)

    fun getKeep(): Int = keepData.get(0).coerceIn(0, StorageCard.MAX_KEEP)

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

    fun getCustomSide(): damien.nodeworks.screen.widget.RelDir? {
        val ord = customSideData.get(0)
        if (ord < 0) return null
        return damien.nodeworks.screen.widget.RelDir.entries.getOrNull(ord)
    }

    fun setCustomSide(side: damien.nodeworks.screen.widget.RelDir?) {
        val target = side?.ordinal ?: -1
        if (customSideData.get(0) == target) return
        customSideData.set(0, target)
        dirty = true
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

    override fun replaceFilterRules(rules: List<String>) {
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

    /** Tags are written as they change, so the chip the player sees is already saved. */
    fun setCardTags(player: Player, tags: List<String>) {
        if (hand == null) return
        val stack = player.getItemInHand(hand)
        if (stack.item !is StorageCard) return
        damien.nodeworks.card.CardTags.set(stack, tags)
        damien.nodeworks.card.KnownCardTags.record(damien.nodeworks.card.CardTags.get(stack), player.level().gameTime)
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
            // controls can't accidentally collide if one expands later. 2099 clears it.
            id in 2000..2015 -> {
                channelData.set(0, id - 2000); dirty = true
            }
            id == BUTTON_CHANNEL_NONE -> {
                channelData.set(0, -1); dirty = true
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
            // Side picker: 4000 = clear (use default face),
            // 4001..(4000 + RelDir.entries.size) = pick a RelDir (id - 4001).
            id == 4000 -> setCustomSide(null)
            id in 4001..(4000 + damien.nodeworks.screen.widget.RelDir.entries.size) -> {
                val rel = damien.nodeworks.screen.widget.RelDir.entries.getOrNull(id - 4001)
                setCustomSide(rel)
            }
            // Card mode. Target values rather than a cycle, so a click that repeats under
            // lag lands on the same setting.
            id in BUTTON_SET_MODE_BASE until BUTTON_SET_MODE_BASE + StorageCard.Companion.Mode.entries.size -> {
                modeData.set(0, id - BUTTON_SET_MODE_BASE); dirty = true
            }
            id == BUTTON_SET_ROUND_ROBIN_BASE || id == BUTTON_SET_ROUND_ROBIN_BASE + 1 -> {
                roundRobinData.set(0, id - BUTTON_SET_ROUND_ROBIN_BASE); dirty = true
            }
            // Value controls carry the value in the id.
            id in (BUTTON_INTERVAL_BASE + StorageCard.MIN_TICK_INTERVAL)..(BUTTON_INTERVAL_BASE + StorageCard.MAX_TICK_INTERVAL) -> {
                tickIntervalData.set(0, id - BUTTON_INTERVAL_BASE); dirty = true
            }
            id in BUTTON_BATCH_BASE..(BUTTON_BATCH_BASE + StorageCard.MAX_BATCH) -> {
                batchData.set(0, id - BUTTON_BATCH_BASE); dirty = true
            }
            id in BUTTON_KEEP_BASE..(BUTTON_KEEP_BASE + StorageCard.MAX_KEEP) -> {
                keepData.set(0, id - BUTTON_KEEP_BASE); dirty = true
            }
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
            StorageCard.setMode(stack, getMode())
            StorageCard.setPriority(stack, priorityData.get(0))
            StorageCard.setChannelFilter(stack, getChannelFilter())
            StorageCard.setFilterMode(stack, getFilterMode())
            StorageCard.setStackabilityFilter(stack, getStackabilityFilter())
            StorageCard.setNbtFilter(stack, getNbtFilter())
            StorageCard.setFilterRules(stack, filterRules)
            StorageCard.setCustomSide(stack, getCustomSide())
            StorageCard.setTickInterval(stack, getTickInterval())
            StorageCard.setRoundRobin(stack, getRoundRobin())
            StorageCard.setBatch(stack, getBatch())
            StorageCard.setKeep(stack, getKeep())
        }
    }

    override fun quickMoveStack(player: Player, slotIndex: Int): ItemStack = ItemStack.EMPTY

    override fun stillValid(player: Player): Boolean {
        if (hand == null) return true
        return player.getItemInHand(hand).item is StorageCard
    }

    companion object {
        const val BUTTON_CHANNEL_NONE = 2099
        const val BUTTON_SET_MODE_BASE = 5000
        const val BUTTON_SET_ROUND_ROBIN_BASE = 5010
        const val BUTTON_INTERVAL_BASE = 6000
        const val BUTTON_BATCH_BASE = 7000
        const val BUTTON_KEEP_BASE = 8000

        fun clientFactory(syncId: Int, playerInventory: Inventory, data: StorageCardOpenData): StorageCardMenu {
            val hand =
                if (data.handOrdinal < InteractionHand.entries.size) InteractionHand.entries[data.handOrdinal] else null
            val mode = StorageCard.Companion.FilterMode.entries.getOrNull(data.filterMode)
                ?: StorageCard.Companion.FilterMode.ALLOW
            val stackability = StorageCard.Companion.StackabilityFilter.entries.getOrNull(data.stackability)
                ?: StorageCard.Companion.StackabilityFilter.ANY
            val nbt = StorageCard.Companion.NbtFilter.entries.getOrNull(data.nbtFilter)
                ?: StorageCard.Companion.NbtFilter.ANY
            val side = if (data.customSideOrdinal < 0) null
                else damien.nodeworks.screen.widget.RelDir.entries.getOrNull(data.customSideOrdinal)
            val cardMode = StorageCard.Companion.Mode.entries.getOrNull(data.mode) ?: StorageCard.Companion.Mode.STORAGE
            return StorageCardMenu(
                syncId, playerInventory, hand, mode, stackability, nbt, data.filterRules, data.cardName, side,
                cardMode, data.tickInterval, data.roundRobin, data.batch, data.keep, data.tags, data.knownTags,
            )
        }
    }
}
