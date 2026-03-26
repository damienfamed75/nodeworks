package damien.nodeworks.block.entity

import damien.nodeworks.block.TerminalBlock
import damien.nodeworks.network.TerminalPackets
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
 * Block entity for the Script Terminal. Stores script text and auto-run setting.
 * Network connection is derived lazily from adjacent blocks — not persisted.
 */
class TerminalBlockEntity(
    pos: BlockPos,
    state: BlockState
) : BlockEntity(ModBlockEntities.TERMINAL, pos, state) {

    /** The Lua script source code. */
    var scriptText: String = ""
        private set

    /** Whether the script should auto-run on world startup. */
    var autoRun: Boolean = false
        private set

    /** Terminal screen layout index (0=small, 1=wide, 2=tall, 3=large). */
    var layoutIndex: Int = 0
        private set

    fun setScriptText(text: String) {
        scriptText = text
        setChanged()
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
        output.putString("scriptText", scriptText)
        output.putBoolean("autoRun", autoRun)
        output.putInt("layoutIndex", layoutIndex)
    }

    override fun loadAdditional(input: ValueInput) {
        super.loadAdditional(input)
        scriptText = input.getString("scriptText").orElse("")
        autoRun = input.getBooleanOr("autoRun", false)
        layoutIndex = input.getIntOr("layoutIndex", 0)
    }

    override fun setLevel(newLevel: net.minecraft.world.level.Level) {
        super.setLevel(newLevel)
        if (!newLevel.isClientSide && autoRun && scriptText.isNotBlank()) {
            TerminalPackets.registerPendingAutoRun(newLevel as net.minecraft.server.level.ServerLevel, worldPosition)
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
