package damien.nodeworks.block.entity

import damien.nodeworks.block.TerminalBlock
import damien.nodeworks.platform.PlatformServices
import damien.nodeworks.registry.ModBlockEntities
import net.minecraft.core.BlockPos
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput

/**
 * Block entity for the Script Terminal. Stores multiple named scripts and settings.
 * Network connection is derived lazily from adjacent blocks — not persisted.
 */
class TerminalBlockEntity(
    pos: BlockPos,
    state: BlockState
) : BlockEntity(ModBlockEntities.TERMINAL, pos, state) {

    companion object {
        const val MAX_TABS = 8
        val SCRIPT_NAME_REGEX = Regex("^[a-z0-9_]+$")
    }

    /** All scripts stored in this terminal. Always contains "main". */
    private val scripts: LinkedHashMap<String, String> = linkedMapOf("main" to "")

    /** Whether the script should auto-run on world startup. */
    var autoRun: Boolean = false
        private set

    /** Terminal screen layout index (0=small, 1=wide, 2=tall, 3=large). */
    var layoutIndex: Int = 0
        private set

    // --- Script access ---

    fun getScripts(): Map<String, String> = scripts.toMap()

    fun getScriptNames(): List<String> = scripts.keys.toList()

    fun getScript(name: String): String = scripts[name] ?: ""

    /** For backwards compatibility and auto-run checks. */
    val scriptText: String get() = scripts["main"] ?: ""

    fun setScript(name: String, text: String) {
        if (name in scripts) {
            scripts[name] = text
            setChanged()
        }
    }

    fun createScript(name: String): Boolean {
        if (scripts.size >= MAX_TABS) return false
        if (!SCRIPT_NAME_REGEX.matches(name)) return false
        if (name in scripts) return false
        scripts[name] = ""
        setChanged()
        return true
    }

    fun deleteScript(name: String): Boolean {
        if (name == "main") return false
        if (scripts.remove(name) != null) {
            setChanged()
            return true
        }
        return false
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

    // --- Serialization ---

    override fun saveAdditional(output: ValueOutput) {
        super.saveAdditional(output)
        // Save scripts as parallel lists of names and texts
        val names = scripts.keys.toList()
        output.putInt("scriptCount", names.size)
        for ((i, name) in names.withIndex()) {
            output.putString("scriptName_$i", name)
            output.putString("scriptText_$i", scripts[name] ?: "")
        }
        output.putBoolean("autoRun", autoRun)
        output.putInt("layoutIndex", layoutIndex)
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
        // Ensure main always exists
        if ("main" !in scripts) {
            scripts["main"] = ""
        }
        autoRun = input.getBooleanOr("autoRun", false)
        layoutIndex = input.getIntOr("layoutIndex", 0)
    }

    override fun setLevel(newLevel: net.minecraft.world.level.Level) {
        super.setLevel(newLevel)
        if (!newLevel.isClientSide && autoRun && scriptText.isNotBlank()) {
            PlatformServices.modState.registerPendingAutoRun(newLevel as net.minecraft.server.level.ServerLevel, worldPosition)
        }
    }

    override fun setRemoved() {
        val currentLevel = level
        if (currentLevel is net.minecraft.server.level.ServerLevel) {
            PlatformServices.modState.stopScript(currentLevel, worldPosition)
        }
        super.setRemoved()
    }

    // --- Client sync ---

    override fun getUpdateTag(registries: HolderLookup.Provider): CompoundTag {
        return saveWithoutMetadata(registries)
    }

    override fun getUpdatePacket(): Packet<ClientGamePacketListener> {
        return ClientboundBlockEntityDataPacket.create(this)
    }
}
