package damien.nodeworks.block.entity

import damien.nodeworks.block.ProcessingHandlerBlock
import damien.nodeworks.compat.getBlockPosList
import damien.nodeworks.compat.getStringOrNull
import damien.nodeworks.compat.putBlockPosList
import damien.nodeworks.network.Connectable
import damien.nodeworks.network.NodeConnectionHelper
import damien.nodeworks.registry.ModBlockEntities
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.DyeColor
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
import java.util.UUID

/**
 * Block-based equivalent of `network:handle(...)`. Two distinct sides:
 *
 *   - **Back face** participates in the parent network (where the Crafting CPU
 *     and Processing Storage live). Discovered like any other Connectable.
 *   - **Front face** anchors a "micro-network" of pipes / nodes / storage cards
 *     that the player wires up to feed machines and pull outputs back. The
 *     Handler IS the anchor, no Network Controller required (or allowed) on
 *     the micro side.
 *
 * The directional split is enforced by [Connectable.connectionsFromFace] and
 * [Connectable.adjacencyFaceAllowed]: a BFS that arrives via the back face
 * only walks back-side neighbors, and vice versa.
 *
 * Phase 1 stores the binding state but does not yet integrate with the CPU.
 */
class ProcessingHandlerBlockEntity(
    pos: BlockPos,
    state: BlockState,
) : BlockEntity(ModBlockEntities.PROCESSING_HANDLER, pos, state), Connectable {

    private val connections = LinkedHashSet<BlockPos>()
    override var blockDestroyed: Boolean = false

    /** The id of the network on the BACK face (parent). Same field every other
     *  Connectable uses; legacy code that reads `entity.networkId` keeps
     *  working as a parent-side query. Custom setter so the
     *  [damien.nodeworks.script.cpu.BlockHandlerRegistry] index can move with
     *  the BE when the parent network gets reassigned by `propagateNetworkId`. */
    private var _networkId: UUID? = null
    override var networkId: UUID?
        get() = _networkId
        set(value) {
            if (value == _networkId) return
            val previous = _networkId
            _networkId = value
            onParentNetworkChanged(previous, value)
        }

    /** The id of the micro-network on the FRONT face. Anchored by THIS
     *  Handler's [permanentId] when the Handler is alone, or by the
     *  lowest-positioned Handler when several merge. Stored separately from
     *  [networkId] because the two sides are independent networks. Custom
     *  setter keeps the client-side [damien.nodeworks.render.MicroNetworkClientRegistry]
     *  in sync as the id changes (NBT load on chunk-load, BFS reassignment
     *  via update packets) so renderers can swap textures for micro nets. */
    private var _microNetworkId: UUID? = null
    var microNetworkId: UUID?
        get() = _microNetworkId
        private set(value) {
            if (_microNetworkId == value) return
            val previous = _microNetworkId
            _microNetworkId = value
            val lvl = level
            if (lvl != null && lvl.isClientSide) {
                previous?.let { damien.nodeworks.render.MicroNetworkClientRegistry.unregister(it) }
                value?.let { damien.nodeworks.render.MicroNetworkClientRegistry.register(it) }
            }
        }

    /** Stable UUID generated on first construction, lives across reload and
     *  through any mid-conflict transitions. Mirrors NetworkController's
     *  [NetworkControllerBlockEntity.permanentId]. */
    var permanentId: UUID = UUID.randomUUID()
        private set

    val frontFace: Direction get() = blockState.getValue(ProcessingHandlerBlock.FACING)
    val backFace: Direction get() = frontFace.opposite

    /** Canonical name of the Processing Set this Handler is bound to. Empty
     *  when nothing is bound (the GUI shows the picker). Survives reload so
     *  the binding sticks across sessions; survives the Set being removed
     *  from the parent network's Processing Storage too (orphan state, the
     *  GUI surfaces a warning and the binding restores when the Set comes
     *  back). Resolved against the parent network at handler-invoke time. */
    var processingApiName: String = ""
        private set

    /** Per-input-itemId channel. Routed inputs land on storage cards in the
     *  micro-network whose channel matches the entry for that itemId.
     *  Defaults to [DyeColor.BLUE] for new entries, which the UI populates
     *  the first time a player binds a Set. */
    private val inputChannelsByItem = mutableMapOf<String, DyeColor>()

    /** Output channel. The UI doesn't expose per-output editing, so a single
     *  shared color suffices. Storage cards on the micro-network with this
     *  channel are where the Handler pulls outputs from. */
    var outputChannel: DyeColor = DyeColor.RED
        private set

    fun getInputChannel(itemId: String): DyeColor =
        inputChannelsByItem[itemId] ?: DyeColor.BLUE

    /** Snapshot of the per-input channel map. Returns a copy so callers can't
     *  mutate the BE's internal state. */
    fun snapshotInputChannels(): Map<String, DyeColor> = inputChannelsByItem.toMap()

    fun bindToProcessingSet(name: String, inputItemIds: Collection<String>) {
        processingApiName = name
        inputChannelsByItem.clear()
        for (id in inputItemIds) inputChannelsByItem[id] = DyeColor.BLUE
        outputChannel = DyeColor.RED
        markDirtyAndSync()
        if (level is ServerLevel) {
            damien.nodeworks.script.cpu.BlockHandlerRegistry.syncFromBE(this)
        }
    }

    fun unbind() {
        processingApiName = ""
        inputChannelsByItem.clear()
        outputChannel = DyeColor.RED
        markDirtyAndSync()
        if (level is ServerLevel) {
            damien.nodeworks.script.cpu.BlockHandlerRegistry.syncFromBE(this)
        }
    }

    /** Called from the [networkId] setter when the parent network's id changes
     *  (BFS reassignment, controller swap, network split/merge). Reconciles
     *  the registry with the new state via the idempotent helper. Server-side
     *  only; the client BE also has a networkId field but doesn't run the
     *  CPU executor. */
    private fun onParentNetworkChanged(previous: UUID?, current: UUID?) {
        if (level !is ServerLevel) return
        damien.nodeworks.script.cpu.BlockHandlerRegistry.syncFromBE(this)
    }

    fun setInputChannel(itemId: String, color: DyeColor) {
        if (!inputChannelsByItem.containsKey(itemId)) return
        if (inputChannelsByItem[itemId] == color) return
        inputChannelsByItem[itemId] = color
        markDirtyAndSync()
    }

    fun setAllInputChannels(color: DyeColor) {
        var changed = false
        for (key in inputChannelsByItem.keys.toList()) {
            if (inputChannelsByItem[key] != color) {
                inputChannelsByItem[key] = color
                changed = true
            }
        }
        if (changed) markDirtyAndSync()
    }

    fun setOutputChannel(color: DyeColor) {
        if (outputChannel == color) return
        outputChannel = color
        markDirtyAndSync()
    }

    private fun markDirtyAndSync() {
        setChanged()
        level?.sendBlockUpdated(worldPosition, blockState, blockState, Block.UPDATE_ALL)
    }

    /** Set the micro-network's id. Called by propagateNetworkId when a
     *  micro-side BFS resolves an anchor. Marks dirty so the new id persists.
     *  Named `assignMicroNetworkId` (not `setMicroNetworkId`) to avoid a JVM
     *  signature clash with the property's synthesized private setter. */
    fun assignMicroNetworkId(id: UUID?) {
        if (microNetworkId == id) return
        microNetworkId = id
        markDirtyAndSync()
    }

    // --- Connectable ---

    override fun getConnections(): Collection<BlockPos> = connections

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

    override fun hasConnection(pos: BlockPos): Boolean = pos in connections

    /** Laser-link connections (wrench-placed) are a parent-network feature
     *  - the micro side is local pipes/nodes only. Expose them when the
     *  BFS arrived through the back face (or started here, where the
     *  per-side ID resolver routes them to the back-side anchor); a
     *  front-side BFS gets nothing back so it can't leak into the parent
     *  via the laser graph. */
    override fun connectionsFromFace(entryFace: Direction?): Collection<BlockPos> = when (entryFace) {
        null, backFace -> connections
        else -> emptyList()
    }

    /** Back face joins parent network, front face joins micro. All four side
     *  faces are inert (the Handler is not pipe-shaped and shouldn't pick up
     *  random network adjacency through its sides). */
    override fun adjacencyFaceAllowed(side: Direction, entryFace: Direction?): Boolean {
        if (side != frontFace && side != backFace) return false
        // No entry yet (start node): the BFS that started here exits through
        // whichever face it cares about. Allow both, the propagation routes
        // each to its own network independently.
        if (entryFace == null) return true
        // Entered through back: only back-side neighbors are visible.
        if (entryFace == backFace) return side == backFace
        // Entered through front: only front-side neighbors are visible.
        if (entryFace == frontFace) return side == frontFace
        // Side-face entry is impossible (sides are inert above) but guard
        // defensively.
        return false
    }

    override fun isMicroNetworkAnchor(): Boolean = true
    override val microNetworkPermanentId: UUID? get() = permanentId
    override fun allowsRepeatVisitAcrossFaces(): Boolean = true

    // --- Lifecycle ---

    override fun setLevel(level: net.minecraft.world.level.Level) {
        super.setLevel(level)
        if (level is ServerLevel) {
            NodeConnectionHelper.trackNode(level, worldPosition)
            // Queue revalidation from each NEIGHBOR position rather than from
            // this PHandler's own pos. propagateNetworkId(handlerPos) starts
            // with entryFace=null, which would walk both sides into a single
            // network and break the directional split. Starting from a
            // neighbor lets the BFS enter the PHandler with a definite face
            // so each side resolves to its own id independently.
            NodeConnectionHelper.queueRevalidation(level, worldPosition.relative(backFace))
            NodeConnectionHelper.queueRevalidation(level, worldPosition.relative(frontFace))
            // If we loaded with a binding already in NBT, sync the registry to
            // match. propagateNetworkId also calls syncFromBE for PHandlers
            // when the BFS visits us, so even if our networkId field is stale
            // here (controller chunk hasn't loaded yet), the registry catches
            // up on the next propagation pass.
            damien.nodeworks.script.cpu.BlockHandlerRegistry.syncFromBE(this)
        }
        if (level.isClientSide) {
            damien.nodeworks.render.NodeConnectionRenderer.trackConnectable(level, worldPosition, true)
            // Register the loaded micro id (custom setter only triggers on
            // CHANGE; the initial NBT-load assignment happens before level
            // is set, so the setter's level==null guard skipped registry
            // updates and we need to backfill here).
            _microNetworkId?.let { damien.nodeworks.render.MicroNetworkClientRegistry.register(it) }
        }
    }

    override fun setRemoved() {
        if (level?.isClientSide == true) {
            damien.nodeworks.render.NodeConnectionRenderer.trackConnectable(level, worldPosition, false)
            _microNetworkId?.let { damien.nodeworks.render.MicroNetworkClientRegistry.unregister(it) }
        }
        val lvl = level
        if (lvl is ServerLevel) {
            NodeConnectionHelper.removeAllConnections(lvl, this)
            NodeConnectionHelper.untrackNode(lvl, worldPosition)
            // Drop the index entry by position so we don't leave a stale
            // mapping behind even if the BE's networkId was already cleared
            // mid-removal.
            damien.nodeworks.script.cpu.BlockHandlerRegistry.unregisterByPos(worldPosition)
        }
        super.setRemoved()
    }

    // --- Serialization ---

    override fun saveAdditional(output: ValueOutput) {
        super.saveAdditional(output)
        output.putString("permanentId", permanentId.toString())
        networkId?.let { output.putString("networkId", it.toString()) }
        microNetworkId?.let { output.putString("microNetworkId", it.toString()) }
        output.putBlockPosList("connections", connections)
        output.putString("processingApiName", processingApiName)
        output.putInt("outputChannel", outputChannel.id)
        // Per-input channels packed as parallel itemId / channelId lists.
        // Two parallel arrays beat a sub-tag map for predictable codec order
        // and small payload size when many handlers share an inventory cache.
        val inputIds = inputChannelsByItem.keys.toList()
        output.putInt("inputCount", inputIds.size)
        for ((index, id) in inputIds.withIndex()) {
            output.putString("inputId$index", id)
            output.putInt("inputChannel$index", (inputChannelsByItem[id] ?: DyeColor.BLUE).id)
        }
    }

    override fun loadAdditional(input: ValueInput) {
        super.loadAdditional(input)
        input.getStringOrNull("permanentId")?.takeIf { it.isNotEmpty() }?.let {
            try {
                permanentId = UUID.fromString(it)
            } catch (_: Exception) {
                // Malformed; keep the ctor-generated UUID.
            }
        }
        networkId = input.getStringOrNull("networkId")?.takeIf { it.isNotEmpty() }?.let {
            try { UUID.fromString(it) } catch (_: Exception) { null }
        }
        microNetworkId = input.getStringOrNull("microNetworkId")?.takeIf { it.isNotEmpty() }?.let {
            try { UUID.fromString(it) } catch (_: Exception) { null }
        }
        damien.nodeworks.network.NetworkSettingsRegistry.notifyConnectableChanged(networkId)
        connections.clear()
        connections.addAll(input.getBlockPosList("connections"))
        processingApiName = input.getStringOr("processingApiName", "")
        outputChannel = runCatching { DyeColor.byId(input.getIntOr("outputChannel", DyeColor.RED.id)) }
            .getOrDefault(DyeColor.RED)
        inputChannelsByItem.clear()
        val count = input.getIntOr("inputCount", 0).coerceAtLeast(0)
        for (index in 0 until count) {
            val id = input.getStringOr("inputId$index", "")
            if (id.isEmpty()) continue
            val ch = runCatching { DyeColor.byId(input.getIntOr("inputChannel$index", DyeColor.BLUE.id)) }
                .getOrDefault(DyeColor.BLUE)
            inputChannelsByItem[id] = ch
        }
    }

    override fun getUpdateTag(registries: HolderLookup.Provider): CompoundTag = saveWithoutMetadata(registries)
    override fun getUpdatePacket(): Packet<ClientGamePacketListener> =
        ClientboundBlockEntityDataPacket.create(this)
}
