package damien.nodeworks.block.entity

import damien.nodeworks.network.Connectable
import damien.nodeworks.network.NodeConnectionHelper
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

class VariableBlockEntity(
    pos: BlockPos,
    state: BlockState
) : BlockEntity(ModBlockEntities.VARIABLE, pos, state), Connectable {

    private val connections = LinkedHashSet<BlockPos>()
    override var blockDestroyed: Boolean = false
    override var networkId: UUID? = null

    var variableName: String = ""
        set(value) {
            field = value.take(32)
            markDirtyAndSync()
        }

    var variableType: VariableType = VariableType.NUMBER
        private set

    var variableValue: String = VariableType.NUMBER.defaultValue
        private set

    fun setType(type: VariableType) {
        variableType = type
        variableValue = type.defaultValue
        markDirtyAndSync()
    }

    fun setValue(value: String) {
        if (variableType.validate(value)) {
            variableValue = variableType.sanitize(value)
            markDirtyAndSync()
        }
    }

    // --- Atomic operations ---

    @Synchronized
    fun compareAndSet(expected: String, new: String): Boolean {
        if (variableValue == expected && variableType.validate(new)) {
            variableValue = variableType.sanitize(new)
            markDirtyAndSync()
            return true
        }
        return false
    }

    // Number atomics
    @Synchronized
    fun increment(amount: Double): Double {
        val current = variableValue.toDoubleOrNull() ?: 0.0
        val result = current + amount
        variableValue = formatNumber(result)
        markDirtyAndSync()
        return result
    }

    @Synchronized
    fun decrement(amount: Double): Double = increment(-amount)

    @Synchronized
    fun atomicMin(value: Double): Double {
        val current = variableValue.toDoubleOrNull() ?: 0.0
        val result = minOf(current, value)
        variableValue = formatNumber(result)
        markDirtyAndSync()
        return result
    }

    @Synchronized
    fun atomicMax(value: Double): Double {
        val current = variableValue.toDoubleOrNull() ?: 0.0
        val result = maxOf(current, value)
        variableValue = formatNumber(result)
        markDirtyAndSync()
        return result
    }

    // String atomics
    @Synchronized
    fun appendValue(suffix: String): String {
        variableValue = (variableValue + suffix).take(256)
        markDirtyAndSync()
        return variableValue
    }

    @Synchronized
    fun clearValue() {
        variableValue = ""
        markDirtyAndSync()
    }

    // Bool atomics
    @Synchronized
    fun toggleValue(): Boolean {
        val current = variableValue == "true"
        val result = !current
        variableValue = result.toString()
        markDirtyAndSync()
        return result
    }

    @Synchronized
    fun tryLock(): Boolean = compareAndSet("false", "true")

    fun unlock() {
        if (variableType == VariableType.BOOL) {
            variableValue = "false"
            markDirtyAndSync()
        }
    }

    private fun formatNumber(d: Double): String {
        return if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()
    }

    private fun markDirtyAndSync() {
        setChanged()
        level?.sendBlockUpdated(worldPosition, blockState, blockState, Block.UPDATE_ALL)
    }

    // --- Connectable ---

    override fun getConnections(): Set<BlockPos> = connections.toSet()

    override fun addConnection(pos: BlockPos): Boolean {
        if (!connections.add(pos)) return false
        markDirtyAndSync()
        return true
    }

    override fun removeConnection(pos: BlockPos): Boolean {
        if (!connections.remove(pos)) return false
        markDirtyAndSync()
        return true
    }

    override fun hasConnection(pos: BlockPos): Boolean = connections.contains(pos)

    // --- Lifecycle ---

    override fun setLevel(level: net.minecraft.world.level.Level) {
        super.setLevel(level)
        if (level is ServerLevel) {
            NodeConnectionHelper.trackNode(level, worldPosition)
        }
        damien.nodeworks.render.NodeConnectionRenderer.trackConnectable(worldPosition, true)
    }

    override fun setRemoved() {
        damien.nodeworks.render.NodeConnectionRenderer.trackConnectable(worldPosition, false)
        val lvl = level
        if (lvl is ServerLevel) {
            NodeConnectionHelper.removeAllConnections(lvl, this)
            NodeConnectionHelper.untrackNode(lvl, worldPosition)
        }
        super.setRemoved()
    }

    // --- Serialization ---

    override fun saveAdditional(output: ValueOutput) {
        super.saveAdditional(output)
        output.putString("variableName", variableName)
        output.putInt("variableType", variableType.ordinal)
        output.putString("variableValue", variableValue)
        networkId?.let { output.putString("networkId", it.toString()) }
        output.putBlockPosList("connections", connections)
    }

    override fun loadAdditional(input: ValueInput) {
        super.loadAdditional(input)
        variableName = input.getStringOr("variableName", "")
        variableType = VariableType.fromOrdinal(input.getIntOr("variableType", 0))
        variableValue = input.getStringOr("variableValue", "").ifEmpty { variableType.defaultValue }
        networkId = input.getStringOrNull("networkId")?.takeIf { it.isNotEmpty() }?.let {
            try { UUID.fromString(it) } catch (_: Exception) { null }
        }
        damien.nodeworks.network.NetworkSettingsRegistry.notifyConnectableChanged(networkId)
        connections.clear()
        connections.addAll(input.getBlockPosList("connections"))
    }

    override fun getUpdateTag(registries: HolderLookup.Provider): CompoundTag {
        return saveWithoutMetadata(registries)
    }

    override fun getUpdatePacket(): Packet<ClientGamePacketListener> {
        return ClientboundBlockEntityDataPacket.create(this)
    }
}
