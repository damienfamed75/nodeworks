package damien.nodeworks.screen

import damien.nodeworks.card.TransferCard
import damien.nodeworks.registry.ModScreenHandlers
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.SimpleContainerData
import net.minecraft.world.item.ItemStack

/**
 * Menu for the held Transfer Card. Same persistence pattern as [StorageCardMenu]: the
 * working values live in container data slots the client sees, button clicks mutate
 * them, and [removed] writes the lot back to the held card once.
 */
class TransferCardMenu(
    syncId: Int,
    playerInventory: Inventory,
    private val hand: InteractionHand?,
    initialMode: TransferCard.Companion.Mode = TransferCard.Companion.Mode.IMPORT,
    initialChannel: damien.nodeworks.network.ChannelFilter = damien.nodeworks.network.ChannelFilter.All,
    initialRoundRobin: Boolean = false,
    initialTickInterval: Int = TransferCard.DEFAULT_TICK_INTERVAL,
    initialFilterRules: List<String> = emptyList(),
    val initialName: String = "",
    initialCustomSide: damien.nodeworks.screen.widget.RelDir? = null,
) : AbstractContainerMenu(ModScreenHandlers.TRANSFER_CARD, syncId), FilterRuleMenu {

    /** [damien.nodeworks.network.ChannelFilter.toNbtInt], -1 = any Storage Card. */
    val channelData = SimpleContainerData(1)
    /** [TransferCard.Companion.Mode] ordinal. */
    val modeData = SimpleContainerData(1)
    /** 0 = off, 1 = on. */
    val roundRobinData = SimpleContainerData(1)
    val tickIntervalData = SimpleContainerData(1)
    /** -1 = no override (use default face), 0..5 = [RelDir] ordinal. */
    val customSideData = SimpleContainerData(1)

    /** Authoritative server-side rules, see [StorageCardMenu.filterRules] for the
     *  client and server split. */
    var filterRules: MutableList<String> = initialFilterRules.toMutableList()
        private set

    private var dirty: Boolean = false

    init {
        if (hand != null) {
            val stack = playerInventory.player.getItemInHand(hand)
            if (!playerInventory.player.level().isClientSide) {
                channelData.set(0, TransferCard.getChannelFilter(stack).toNbtInt())
                modeData.set(0, TransferCard.getMode(stack).ordinal)
                roundRobinData.set(0, if (TransferCard.getRoundRobin(stack)) 1 else 0)
                tickIntervalData.set(0, TransferCard.getTickInterval(stack))
                filterRules = TransferCard.getFilterRules(stack).toMutableList()
                customSideData.set(0, TransferCard.getCustomSide(stack)?.ordinal ?: -1)
            } else {
                applyInitial(initialMode, initialChannel, initialRoundRobin, initialTickInterval, initialCustomSide)
            }
        } else {
            applyInitial(initialMode, initialChannel, initialRoundRobin, initialTickInterval, initialCustomSide)
        }
        addDataSlots(channelData)
        addDataSlots(modeData)
        addDataSlots(roundRobinData)
        addDataSlots(tickIntervalData)
        addDataSlots(customSideData)
    }

    private fun applyInitial(
        mode: TransferCard.Companion.Mode,
        channel: damien.nodeworks.network.ChannelFilter,
        roundRobin: Boolean,
        tickInterval: Int,
        side: damien.nodeworks.screen.widget.RelDir?,
    ) {
        channelData.set(0, channel.toNbtInt())
        modeData.set(0, mode.ordinal)
        roundRobinData.set(0, if (roundRobin) 1 else 0)
        tickIntervalData.set(0, tickInterval)
        customSideData.set(0, side?.ordinal ?: -1)
    }

    fun getChannelFilter(): damien.nodeworks.network.ChannelFilter =
        damien.nodeworks.network.ChannelFilter.fromNbtInt(channelData.get(0))

    fun getMode(): TransferCard.Companion.Mode =
        TransferCard.Companion.Mode.entries.getOrNull(modeData.get(0)) ?: TransferCard.Companion.Mode.IMPORT

    fun getRoundRobin(): Boolean = roundRobinData.get(0) != 0

    fun getTickInterval(): Int =
        tickIntervalData.get(0).coerceIn(TransferCard.MIN_TICK_INTERVAL, TransferCard.MAX_TICK_INTERVAL)

    fun getCustomSide(): damien.nodeworks.screen.widget.RelDir? {
        val ord = customSideData.get(0)
        if (ord < 0) return null
        return damien.nodeworks.screen.widget.RelDir.entries.getOrNull(ord)
    }

    private fun setCustomSide(side: damien.nodeworks.screen.widget.RelDir?) {
        val target = side?.ordinal ?: -1
        if (customSideData.get(0) == target) return
        customSideData.set(0, target)
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

    fun setCardName(player: Player, name: String) {
        if (hand == null) return
        val stack = player.getItemInHand(hand)
        if (stack.item !is TransferCard) return
        applyCardName(stack, name)
    }

    override fun clickMenuButton(player: Player, id: Int): Boolean {
        when {
            // Channel picker, the same colour ids the Storage Card uses, plus none.
            id in 2000..2015 -> { channelData.set(0, id - 2000); dirty = true }
            id == BUTTON_CHANNEL_NONE -> { channelData.set(0, -1); dirty = true }
            // Side picker: 4000 = default face, 4001.. = a RelDir.
            id == 4000 -> setCustomSide(null)
            id in 4001..(4000 + damien.nodeworks.screen.widget.RelDir.entries.size) ->
                setCustomSide(damien.nodeworks.screen.widget.RelDir.entries.getOrNull(id - 4001))
            id == BUTTON_TOGGLE_MODE -> {
                val next = if (getMode() == TransferCard.Companion.Mode.IMPORT) TransferCard.Companion.Mode.EXPORT
                    else TransferCard.Companion.Mode.IMPORT
                modeData.set(0, next.ordinal); dirty = true
            }
            id == BUTTON_TOGGLE_ROUND_ROBIN -> { roundRobinData.set(0, if (getRoundRobin()) 0 else 1); dirty = true }
            // Target values, so a click that repeats under lag lands on the same setting.
            id in BUTTON_SET_MODE_BASE until BUTTON_SET_MODE_BASE + TransferCard.Companion.Mode.entries.size -> {
                modeData.set(0, id - BUTTON_SET_MODE_BASE); dirty = true
            }
            id == BUTTON_SET_ROUND_ROBIN_BASE || id == BUTTON_SET_ROUND_ROBIN_BASE + 1 -> {
                roundRobinData.set(0, id - BUTTON_SET_ROUND_ROBIN_BASE); dirty = true
            }
            // Interval set directly, the id carries the value.
            id in (BUTTON_INTERVAL_BASE + TransferCard.MIN_TICK_INTERVAL)..(BUTTON_INTERVAL_BASE + TransferCard.MAX_TICK_INTERVAL) -> {
                tickIntervalData.set(0, id - BUTTON_INTERVAL_BASE); dirty = true
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
        if (stack.item is TransferCard) {
            TransferCard.setChannelFilter(stack, getChannelFilter())
            TransferCard.setMode(stack, getMode())
            TransferCard.setRoundRobin(stack, getRoundRobin())
            TransferCard.setTickInterval(stack, getTickInterval())
            TransferCard.setFilterRules(stack, filterRules)
            TransferCard.setCustomSide(stack, getCustomSide())
        }
    }

    override fun quickMoveStack(player: Player, slotIndex: Int): ItemStack = ItemStack.EMPTY

    override fun stillValid(player: Player): Boolean {
        if (hand == null) return true
        return player.getItemInHand(hand).item is TransferCard
    }

    companion object {
        const val BUTTON_CHANNEL_NONE = 2099
        const val BUTTON_TOGGLE_MODE = 5000
        const val BUTTON_TOGGLE_ROUND_ROBIN = 5001
        const val BUTTON_SET_MODE_BASE = 5100
        const val BUTTON_SET_ROUND_ROBIN_BASE = 5200
        const val BUTTON_INTERVAL_BASE = 6000

        fun clientFactory(syncId: Int, playerInventory: Inventory, data: TransferCardOpenData): TransferCardMenu {
            val hand =
                if (data.handOrdinal < InteractionHand.entries.size) InteractionHand.entries[data.handOrdinal] else null
            val mode = TransferCard.Companion.Mode.entries.getOrNull(data.mode) ?: TransferCard.Companion.Mode.IMPORT
            val side = if (data.customSideOrdinal < 0) null
                else damien.nodeworks.screen.widget.RelDir.entries.getOrNull(data.customSideOrdinal)
            return TransferCardMenu(
                syncId, playerInventory, hand, mode, damien.nodeworks.network.ChannelFilter.fromNbtInt(data.channelNbt),
                data.roundRobin, data.tickInterval, data.filterRules, data.cardName, side,
            )
        }
    }
}
