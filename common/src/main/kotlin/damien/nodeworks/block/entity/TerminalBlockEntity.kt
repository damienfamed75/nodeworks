package damien.nodeworks.block.entity

import damien.nodeworks.compat.getBlockPosList
import damien.nodeworks.compat.getStringOrNull
import damien.nodeworks.compat.putBlockPosList
import damien.nodeworks.item.ScriptItem
import damien.nodeworks.item.ScriptProgram
import damien.nodeworks.network.Connectable
import damien.nodeworks.network.NodeConnectionHelper
import damien.nodeworks.platform.PlatformServices
import damien.nodeworks.registry.ModBlockEntities
import damien.nodeworks.registry.ModDataComponents
import damien.nodeworks.registry.ModItems
import damien.nodeworks.script.ScriptHost
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.HolderLookup
import net.minecraft.core.NonNullList
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.Container
import net.minecraft.world.ContainerHelper
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
import java.util.UUID

/**
 * Script Runner. Eight slots for Script items, each running in its own sandbox on the
 * network the Runner sits on. An enabled slot starts on world load and on a redstone
 * pulse. A script that times out disables its slot and records why, so a redstone clock
 * cannot re-fire it. Enabling the slot again, or swapping the Script, forgives the fault.
 *
 * Worlds from before the Runner carry a Scripting Terminal tab set under this block. On
 * load that tab set becomes a Script item in the first slot, with auto-run carried into
 * the slot toggle, so nothing stops working.
 */
class TerminalBlockEntity(
    pos: BlockPos,
    state: BlockState
) : BlockEntity(ModBlockEntities.TERMINAL, pos, state), Connectable, Container {

    companion object {
        const val SLOT_COUNT = 8
        /** Name given to the program migrated from an old Scripting Terminal. */
        const val LEGACY_PROGRAM_NAME = "terminal"
    }

    private val connections = LinkedHashSet<BlockPos>()
    override var blockDestroyed: Boolean = false
    override var networkId: UUID? = null

    /** UUID of the player who placed this block, the FakePlayer identity for scripts that
     *  touch the world. Null on worlds from before ownership was recorded. */
    var ownerUuid: UUID? = null

    private val items: NonNullList<ItemStack> = NonNullList.withSize(SLOT_COUNT, ItemStack.EMPTY)
    private val enabled = BooleanArray(SLOT_COUNT)
    private val slotErrors = arrayOfNulls<String>(SLOT_COUNT)

    /**
     * Previous best-neighbor redstone signal, for rising-edge detection so a pulse toggles
     * the scripts. Seeded in [setLevel] so a permanently powered Runner does not start on
     * chunk load and the first press after joining is a real edge. -1 until seeded.
     */
    var lastRedstoneSignal: Int = -1

    fun programAt(slot: Int): ScriptProgram? =
        if (slot in 0 until SLOT_COUNT) ScriptItem.program(items[slot]) else null

    fun isEnabled(slot: Int): Boolean = slot in 0 until SLOT_COUNT && enabled[slot]

    fun lastError(slot: Int): String? = slotErrors.getOrNull(slot)

    fun slotLabel(slot: Int): String = programAt(slot)?.name ?: "slot ${slot + 1}"

    /** Enabling is the player's decision, so it also forgives a recorded fault. */
    fun setEnabled(slot: Int, value: Boolean) {
        if (slot !in 0 until SLOT_COUNT) return
        enabled[slot] = value
        if (value) slotErrors[slot] = null
        setChanged()
    }

    /** Slots the Runner starts on its own: enabled, holding a program, no recorded fault. */
    fun autoStartSlots(): List<Int> =
        (0 until SLOT_COUNT).filter { enabled[it] && slotErrors[it] == null && programAt(it) != null }

    fun host(slot: Int): ScriptHost = object : ScriptHost {
        override val autoRestarts: Boolean get() = enabled[slot]

        override fun markTimedOut(reason: String) {
            slotErrors[slot] = reason
            enabled[slot] = false
            setChanged()
        }

        override fun clearLastError() {
            if (slotErrors[slot] != null) {
                slotErrors[slot] = null
                setChanged()
            }
        }
    }

    /**
     * The Runner's own position when [damien.nodeworks.network.NetworkDiscovery] can walk
     * out from it, or null. An adjacency-only path needs the neighbour to opt in through
     * [Connectable.usesAdjacency], so a Runner touching only a Node returns null.
     */
    fun getNetworkStartPos(): BlockPos? {
        if (connections.isNotEmpty()) return worldPosition
        val lvl = level ?: return null
        for (dir in Direction.entries) {
            val neighbor = lvl.getBlockEntity(worldPosition.relative(dir)) as? Connectable ?: continue
            if (neighbor.usesAdjacency()) return worldPosition
        }
        return null
    }

    // --- Connectable ---

    override fun getConnections(): Set<BlockPos> = connections

    override fun addConnection(pos: BlockPos): Boolean {
        if (!connections.add(pos)) return false
        setChanged()
        level?.sendBlockUpdated(worldPosition, blockState, blockState, Block.UPDATE_ALL)
        return true
    }

    override fun removeConnection(pos: BlockPos): Boolean {
        if (!connections.remove(pos)) return false
        setChanged()
        level?.sendBlockUpdated(worldPosition, blockState, blockState, Block.UPDATE_ALL)
        return true
    }

    override fun hasConnection(pos: BlockPos): Boolean = connections.contains(pos)

    // --- Lifecycle ---

    override fun setLevel(newLevel: net.minecraft.world.level.Level) {
        super.setLevel(newLevel)
        if (newLevel is ServerLevel) {
            NodeConnectionHelper.trackNode(newLevel, worldPosition)
            NodeConnectionHelper.queueRevalidation(newLevel, worldPosition)
            lastRedstoneSignal = newLevel.getBestNeighborSignal(worldPosition)
            if (autoStartSlots().isNotEmpty()) PlatformServices.modState.registerPendingAutoRun(newLevel, worldPosition)
        }
        damien.nodeworks.render.NodeConnectionRenderer.trackConnectable(level, worldPosition, true)
    }

    override fun setRemoved() {
        damien.nodeworks.render.NodeConnectionRenderer.trackConnectable(level, worldPosition, false)
        val currentLevel = level
        if (currentLevel is ServerLevel) {
            PlatformServices.modState.stopScript(currentLevel, worldPosition)
            NodeConnectionHelper.removeAllConnections(currentLevel, this)
            NodeConnectionHelper.untrackNode(currentLevel, worldPosition)
            if (blockDestroyed) {
                // The block has no loot table, the Runner and its Scripts drop here.
                net.minecraft.world.Containers.dropContents(currentLevel, worldPosition, this)
                net.minecraft.world.Containers.dropItemStack(
                    currentLevel,
                    worldPosition.x + 0.5, worldPosition.y + 0.5, worldPosition.z + 0.5,
                    ItemStack(damien.nodeworks.registry.ModBlocks.TERMINAL),
                )
            }
        }
        super.setRemoved()
    }

    // --- Container, the Script slots ---

    private fun slotChanged(slot: Int) {
        val lvl = level
        if (lvl is ServerLevel) PlatformServices.modState.stopScriptSlot(lvl, worldPosition, slot)
        slotErrors[slot] = null
        setChanged()
    }

    override fun getContainerSize(): Int = SLOT_COUNT
    override fun isEmpty(): Boolean = items.all { it.isEmpty }
    override fun getItem(slot: Int): ItemStack = if (slot in items.indices) items[slot] else ItemStack.EMPTY
    override fun getMaxStackSize(): Int = 1
    override fun canPlaceItem(slot: Int, stack: ItemStack): Boolean = stack.item is ScriptItem

    override fun removeItem(slot: Int, amount: Int): ItemStack {
        val result = ContainerHelper.removeItem(items, slot, amount)
        if (!result.isEmpty) slotChanged(slot)
        return result
    }

    override fun removeItemNoUpdate(slot: Int): ItemStack {
        val result = ContainerHelper.takeItem(items, slot)
        if (!result.isEmpty && slot in items.indices) slotChanged(slot)
        return result
    }

    override fun setItem(slot: Int, stack: ItemStack) {
        if (slot !in items.indices) return
        val changed = !ItemStack.isSameItemSameComponents(items[slot], stack)
        items[slot] = stack
        if (changed) slotChanged(slot) else setChanged()
    }

    override fun stillValid(player: Player): Boolean =
        player.distanceToSqr(worldPosition.x + 0.5, worldPosition.y + 0.5, worldPosition.z + 0.5) <= 64.0

    override fun clearContent() {
        for (i in items.indices) if (!items[i].isEmpty) {
            items[i] = ItemStack.EMPTY
            slotChanged(i)
        }
    }

    // --- Serialization ---

    override fun saveAdditional(output: ValueOutput) {
        super.saveAdditional(output)
        ContainerHelper.saveAllItems(output, items)
        var mask = 0
        for (i in 0 until SLOT_COUNT) if (enabled[i]) mask = mask or (1 shl i)
        output.putInt("enabledMask", mask)
        val errors = output.childrenList("slotErrors")
        for (i in 0 until SLOT_COUNT) {
            val err = slotErrors[i] ?: continue
            val child = errors.addChild()
            child.putInt("slot", i)
            child.putString("message", err)
        }
        networkId?.let { output.putString("networkId", it.toString()) }
        ownerUuid?.let { output.putString("ownerUuid", it.toString()) }
        output.putBlockPosList("connections", connections)
    }

    override fun loadAdditional(input: ValueInput) {
        super.loadAdditional(input)
        networkId = input.getStringOrNull("networkId")?.takeIf { it.isNotEmpty() }?.let {
            try { UUID.fromString(it) } catch (_: Exception) { null }
        }
        ownerUuid = input.getStringOrNull("ownerUuid")?.takeIf { it.isNotEmpty() }?.let {
            try { UUID.fromString(it) } catch (_: Exception) { null }
        }
        items.clear()
        ContainerHelper.loadAllItems(input, items)
        val mask = input.getIntOr("enabledMask", 0)
        for (i in 0 until SLOT_COUNT) enabled[i] = (mask shr i) and 1 != 0
        slotErrors.fill(null)
        for (child in input.childrenListOrEmpty("slotErrors")) {
            val slot = child.getIntOr("slot", -1)
            val message = child.getStringOr("message", "")
            if (slot in 0 until SLOT_COUNT && message.isNotEmpty()) slotErrors[slot] = message
        }
        migrateLegacyScripts(input)
        if (level != null) damien.nodeworks.network.NetworkSettingsRegistry.notifyBlockSynced(worldPosition)
        connections.clear()
        connections.addAll(input.getBlockPosList("connections"))
    }

    /** A Scripting Terminal's tab set becomes a Script in the first slot, once. */
    private fun migrateLegacyScripts(input: ValueInput) {
        val tabs = linkedMapOf<String, String>()
        for (child in input.childrenListOrEmpty("scripts")) {
            val name = child.getStringOr("name", "")
            val text = child.getStringOr("text", "")
            if (name.isNotEmpty()) tabs[name] = text
        }
        if (tabs.values.none { it.isNotBlank() } || items.any { !it.isEmpty }) return
        if (ScriptProgram.MAIN_TAB !in tabs) tabs[ScriptProgram.MAIN_TAB] = ""
        val stack = ItemStack(ModItems.SCRIPT)
        stack.set(ModDataComponents.SCRIPT_PROGRAM, ScriptProgram(LEGACY_PROGRAM_NAME, tabs, ownerUuid, ""))
        items[0] = stack
        enabled[0] = input.getBooleanOr("autoRun", false)
        input.getStringOrNull("lastError")?.takeIf { it.isNotEmpty() }?.let {
            slotErrors[0] = it
            enabled[0] = false
        }
    }

    // --- Client sync ---

    override fun getUpdateTag(registries: HolderLookup.Provider): CompoundTag {
        return saveWithoutMetadata(registries)
    }

    override fun getUpdatePacket(): Packet<ClientGamePacketListener> {
        return ClientboundBlockEntityDataPacket.create(this)
    }
}
