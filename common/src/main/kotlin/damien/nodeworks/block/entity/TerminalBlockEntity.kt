package damien.nodeworks.block.entity

import damien.nodeworks.block.TerminalBlock
import damien.nodeworks.network.Connectable
import damien.nodeworks.network.NodeConnectionHelper
import damien.nodeworks.platform.PlatformServices
import damien.nodeworks.registry.ModBlockEntities
import net.minecraft.core.BlockPos
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
import damien.nodeworks.compat.getBlockPosList
import damien.nodeworks.compat.getStringOrNull
import damien.nodeworks.compat.putBlockPosList
import java.util.UUID

/**
 * Block entity for the Script Terminal. Stores multiple named scripts and settings.
 */
class TerminalBlockEntity(
    pos: BlockPos,
    state: BlockState
) : BlockEntity(ModBlockEntities.TERMINAL, pos, state), Connectable {

    companion object {
        const val MAX_TABS = 8
        val SCRIPT_NAME_REGEX = Regex("^[a-z0-9_]+$")
    }

    private val connections = LinkedHashSet<BlockPos>()
    override var blockDestroyed: Boolean = false
    override var networkId: UUID? = null

    /** UUID of the player who placed this terminal. Captured in [TerminalBlock.setPlacedBy]
     *  and used as the FakePlayer identity when scripts mutate the world (Breaker/Placer
     *  routed through this terminal's network). Legacy null on pre-update worlds, the
     *  FakePlayer service falls back to a static "Nodeworks" profile in that case. */
    var ownerUuid: UUID? = null

    // Script storage
    val scripts: MutableMap<String, String> = linkedMapOf("main" to "")
    var autoRun: Boolean = false
        private set
    var layoutIndex: Int = 0
        private set

    /** The current script text (active tab = "main"). */
    val scriptText: String get() = scripts["main"] ?: ""

    /**
     * Previous best-neighbor redstone signal seen by the Terminal block's neighborChanged
     * hook. Used for rising-edge detection so a redstone pulse (button, pressure plate,
     * fresh torch) toggles the script. Seeded in [onLoad] with the block's current
     * neighbor signal so:
     *   - a permanently-powered terminal doesn't auto-start on chunk-load
     *   - the FIRST button press after the player joins the world is detected as a
     *     rising edge instead of being consumed as baseline capture
     * -1 is a safety sentinel if [onLoad] somehow runs before the level is reachable,
     * [TerminalBlock.neighborChanged] performs the same capture-without-trigger in
     * that case.
     */
    var lastRedstoneSignal: Int = -1

    fun getScriptsCopy(): Map<String, String> = scripts.toMap()

    fun setScript(name: String, text: String) {
        scripts[name] = text
        setChanged()
    }

    fun createScript(name: String) {
        if (scripts.size < MAX_TABS && SCRIPT_NAME_REGEX.matches(name) && name !in scripts) {
            scripts[name] = ""
            setChanged()
        }
    }

    fun deleteScript(name: String) {
        if (name != "main" && scripts.remove(name) != null) {
            setChanged()
        }
    }

    fun setAutoRun(enabled: Boolean) {
        autoRun = enabled
        setChanged()
    }

    fun setLayoutIndex(index: Int) {
        layoutIndex = index.coerceIn(0, 3)
        setChanged()
    }

    /**
     * Finds the adjacent node this terminal connects through.
     * Scanned on demand, not cached or persisted.
     */
    fun getConnectedNodePos(): BlockPos? {
        val currentLevel = level ?: return null
        return TerminalBlock.findAdjacentNode(currentLevel, worldPosition)
    }

    /**
     * Returns the best starting position for network discovery.
     * Prefers laser connections (own pos), falls back to adjacent node.
     */
    fun getNetworkStartPos(): BlockPos? {
        if (connections.isNotEmpty()) return worldPosition
        return getConnectedNodePos()
    }

    // --- Connectable ---

    override fun getConnections(): Set<BlockPos> = connections.toSet()

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
            // Seed the redstone baseline on BE load. Without this the first call to
            // TerminalBlock.neighborChanged (which typically *is* the player's first
            // button press after joining the world) would be consumed as baseline
            // capture, taking two presses to actually run the script. setLevel runs
            // when the chunk loads the BE and the chunk's blocks are already in place,
            // so getBestNeighborSignal returns the correct current power level.
            lastRedstoneSignal = newLevel.getBestNeighborSignal(worldPosition)
        }
        damien.nodeworks.render.NodeConnectionRenderer.trackConnectable(worldPosition, true)
        if (!newLevel.isClientSide && autoRun && scriptText.isNotBlank()) {
            PlatformServices.modState.registerPendingAutoRun(newLevel as ServerLevel, worldPosition)
        }
    }


    override fun setRemoved() {
        damien.nodeworks.render.NodeConnectionRenderer.trackConnectable(worldPosition, false)
        val currentLevel = level
        if (currentLevel is ServerLevel) {
            PlatformServices.modState.stopScript(currentLevel, worldPosition)
            NodeConnectionHelper.removeAllConnections(currentLevel, this)
            NodeConnectionHelper.untrackNode(currentLevel, worldPosition)
            if (blockDestroyed) {
                dropAsItem(currentLevel)
            }
        }
        super.setRemoved()
    }

    private fun dropAsItem(level: ServerLevel) {
        val stack = net.minecraft.world.item.ItemStack(damien.nodeworks.registry.ModBlocks.TERMINAL)
        val hasContent = scripts.any { it.value.isNotEmpty() } || autoRun
        if (hasContent) {
            val tag = saveWithoutMetadata(level.registryAccess())
            tag.remove("connections")
            // NeoForge requires "id" in BLOCK_ENTITY_DATA for serialization
            val typeKey = net.minecraft.core.registries.BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(type)
            if (typeKey != null) tag.putString("id", typeKey.toString())
            stack.set(net.minecraft.core.component.DataComponents.BLOCK_ENTITY_DATA,
                net.minecraft.world.item.component.TypedEntityData.of(type, tag))
        }
        net.minecraft.world.Containers.dropItemStack(level,
            worldPosition.x + 0.5, worldPosition.y + 0.5, worldPosition.z + 0.5, stack)
    }

    // --- Serialization ---

    override fun saveAdditional(output: ValueOutput) {
        super.saveAdditional(output)
        val scriptList = output.childrenList("scripts")
        for ((name, text) in scripts) {
            val child = scriptList.addChild()
            child.putString("name", name)
            child.putString("text", text)
        }
        output.putBoolean("autoRun", autoRun)
        output.putInt("layoutIndex", layoutIndex)
        networkId?.let { output.putString("networkId", it.toString()) }
        ownerUuid?.let { output.putString("ownerUuid", it.toString()) }
        output.putBlockPosList("connections", connections)
    }

    override fun loadAdditional(input: ValueInput) {
        super.loadAdditional(input)
        scripts.clear()
        for (child in input.childrenListOrEmpty("scripts")) {
            val name = child.getStringOr("name", "")
            val text = child.getStringOr("text", "")
            if (name.isNotEmpty()) scripts[name] = text
        }
        if ("main" !in scripts) {
            scripts["main"] = ""
        }
        autoRun = input.getBooleanOr("autoRun", false)
        layoutIndex = input.getIntOr("layoutIndex", 0)
        networkId = input.getStringOrNull("networkId")?.takeIf { it.isNotEmpty() }?.let {
            try { UUID.fromString(it) } catch (_: Exception) { null }
        }
        ownerUuid = input.getStringOrNull("ownerUuid")?.takeIf { it.isNotEmpty() }?.let {
            try { UUID.fromString(it) } catch (_: Exception) { null }
        }
        damien.nodeworks.network.NetworkSettingsRegistry.notifyConnectableChanged(networkId)
        connections.clear()
        connections.addAll(input.getBlockPosList("connections"))
    }

    // --- Client sync ---

    override fun getUpdateTag(registries: HolderLookup.Provider): CompoundTag {
        return saveWithoutMetadata(registries)
    }

    override fun getUpdatePacket(): Packet<ClientGamePacketListener> {
        return ClientboundBlockEntityDataPacket.create(this)
    }
}
