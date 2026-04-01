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

    // Script storage
    val scripts: MutableMap<String, String> = linkedMapOf("main" to "")
    var autoRun: Boolean = false
        private set
    var layoutIndex: Int = 0
        private set

    /** The current script text (active tab = "main"). */
    val scriptText: String get() = scripts["main"] ?: ""

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
     * Scanned on demand — not cached or persisted.
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
                net.minecraft.world.item.component.CustomData.of(tag))
        }
        net.minecraft.world.Containers.dropItemStack(level,
            worldPosition.x + 0.5, worldPosition.y + 0.5, worldPosition.z + 0.5, stack)
    }

    // --- Serialization ---

    override fun saveAdditional(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.saveAdditional(tag, registries)
        val names = scripts.keys.toList()
        tag.putInt("scriptCount", names.size)
        for ((i, name) in names.withIndex()) {
            tag.putString("scriptName_$i", name)
            tag.putString("scriptText_$i", scripts[name] ?: "")
        }
        tag.putBoolean("autoRun", autoRun)
        tag.putInt("layoutIndex", layoutIndex)
        if (connections.isNotEmpty()) {
            tag.putLongArray("connections", connections.map { it.asLong() }.toLongArray())
        }
    }

    override fun loadAdditional(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.loadAdditional(tag, registries)
        scripts.clear()
        val count = if (tag.contains("scriptCount")) tag.getInt("scriptCount") else 0
        for (i in 0 until count) {
            val name = tag.getString("scriptName_$i")
            val text = tag.getString("scriptText_$i")
            if (name.isNotEmpty()) {
                scripts[name] = text
            }
        }
        if ("main" !in scripts) {
            scripts["main"] = ""
        }
        autoRun = tag.getBoolean("autoRun")
        layoutIndex = if (tag.contains("layoutIndex")) tag.getInt("layoutIndex") else 0
        connections.clear()
        if (tag.contains("connections")) {
            tag.getLongArray("connections").forEach { connections.add(BlockPos.of(it)) }
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
