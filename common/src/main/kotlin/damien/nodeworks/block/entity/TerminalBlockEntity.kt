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
            if (blockDestroyed) {
                NodeConnectionHelper.removeAllConnections(currentLevel, this)
                NodeConnectionHelper.untrackNode(currentLevel, worldPosition)
            }
        }
        super.setRemoved()
    }

    // --- Serialization ---

    override fun saveAdditional(output: ValueOutput) {
        super.saveAdditional(output)
        val names = scripts.keys.toList()
        output.putInt("scriptCount", names.size)
        for ((i, name) in names.withIndex()) {
            output.putString("scriptName_$i", name)
            output.putString("scriptText_$i", scripts[name] ?: "")
        }
        output.putBoolean("autoRun", autoRun)
        output.putInt("layoutIndex", layoutIndex)
        if (connections.isNotEmpty()) {
            output.store("connections", BlockPos.CODEC.listOf(), connections.toList())
        }
    }

    override fun loadAdditional(input: ValueInput) {
        super.loadAdditional(input)
        scripts.clear()
        val count = input.getIntOr("scriptCount", 0)
        for (i in 0 until count) {
            val name = input.getString("scriptName_$i").orElse("")
            val text = input.getString("scriptText_$i").orElse("")
            if (name.isNotEmpty()) {
                scripts[name] = text
            }
        }
        if ("main" !in scripts) {
            scripts["main"] = ""
        }
        autoRun = input.getBooleanOr("autoRun", false)
        layoutIndex = input.getIntOr("layoutIndex", 0)
        connections.clear()
        input.read("connections", BlockPos.CODEC.listOf()).ifPresent { connections.addAll(it) }
    }

    // --- Client sync ---

    override fun getUpdateTag(registries: HolderLookup.Provider): CompoundTag {
        return saveWithoutMetadata(registries)
    }

    override fun getUpdatePacket(): Packet<ClientGamePacketListener> {
        return ClientboundBlockEntityDataPacket.create(this)
    }
}
