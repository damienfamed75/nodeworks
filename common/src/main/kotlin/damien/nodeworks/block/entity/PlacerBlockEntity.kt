package damien.nodeworks.block.entity

import damien.nodeworks.block.PlacerBlock
import damien.nodeworks.network.Connectable
import damien.nodeworks.network.NodeConnectionHelper
import damien.nodeworks.platform.PlatformServices
import damien.nodeworks.registry.ModBlockEntities
import damien.nodeworks.script.CardHandle
import damien.nodeworks.script.NetworkStorageHelper
import net.minecraft.core.BlockPos
import net.minecraft.core.HolderLookup
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.DyeColor
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
import damien.nodeworks.compat.getBlockPosList
import damien.nodeworks.compat.getStringOrNull
import damien.nodeworks.compat.putBlockPosList
import java.util.UUID

class PlacerBlockEntity(
    pos: BlockPos,
    state: BlockState,
) : BlockEntity(ModBlockEntities.PLACER, pos, state), Connectable {

    companion object {
        const val REDSTONE_IGNORED = 0
        const val REDSTONE_LOW = 1
        const val REDSTONE_HIGH = 2

        /** 20-tick interval between auto-place attempts. Matches the User
         *  device's per-use cooldown so a Placer + User pair driven by the
         *  same redstone signal stays in lockstep. */
        const val PLACE_INTERVAL_TICKS = 20
    }

    private val connections = LinkedHashSet<BlockPos>()
    override var blockDestroyed: Boolean = false
    override var networkId: UUID? = null

    /** UUID of the player who placed this placer. Drives the FakePlayer identity used
     *  for [BlockEvent.EntityPlaceEvent] dispatch + spawn protection. Legacy null on
     *  pre-update worlds, falls back to the static "Nodeworks" profile. */
    var ownerUuid: UUID? = null

    var deviceName: String = ""
        set(value) {
            field = value.take(32)
            markDirtyAndSync()
        }

    var channel: DyeColor = DyeColor.WHITE
        set(value) {
            field = value
            markDirtyAndSync()
        }

    /** Item-id / tag / pattern that gates the auto-place loop. Empty leaves
     *  the Placer idle (Lua-only). Non-empty + matching block-item in the
     *  network + non-block target = auto-place once per
     *  [PLACE_INTERVAL_TICKS]. */
    var filterRule: String = ""
        set(value) {
            field = value
            markDirtyAndSync()
        }

    var redstoneMode: Int = REDSTONE_IGNORED
        set(value) {
            field = value.coerceIn(0, 2)
            markDirtyAndSync()
        }

    /** Toggles the [UserPreviewRenderer] wireframe over the single block at
     *  [targetPos]. Persisted so preview survives chunk unload. */
    var previewArea: Boolean = false
        set(value) {
            field = value
            if (level?.isClientSide == true) {
                damien.nodeworks.render.UserPreviewRenderer.TrackedPlacers.setPreview(this, value)
            }
            markDirtyAndSync()
        }

    /** Server-tick of the last auto-place attempt (success or failure). The
     *  `gameTime - lastAttemptTick < PLACE_INTERVAL_TICKS` guard rate-limits
     *  the loop to 1 Hz. Negative initial so the first tick after world load
     *  is always past the cooldown. */
    @Transient
    private var lastAttemptTick: Long = -PLACE_INTERVAL_TICKS.toLong()

    /** Position the placer is targeting, one block away in the FACING direction. */
    val targetPos: BlockPos
        get() = worldPosition.relative(blockState.getValue(PlacerBlock.FACING))

    private fun markDirtyAndSync() {
        setChanged()
        level?.sendBlockUpdated(worldPosition, blockState, blockState, Block.UPDATE_ALL)
    }

    // --- Connectable ---
    override fun getConnections(): Set<BlockPos> = connections
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
            NodeConnectionHelper.queueRevalidation(level, worldPosition)
        }
        // Client-only render trackers, see UserBlockEntity.setLevel for
        // the rationale.
        if (level.isClientSide) {
            damien.nodeworks.render.NodeConnectionRenderer.trackConnectable(level, worldPosition, true)
            damien.nodeworks.render.UserPreviewRenderer.TrackedPlacers.add(this)
        }
    }

    override fun setRemoved() {
        if (level?.isClientSide == true) {
            damien.nodeworks.render.NodeConnectionRenderer.trackConnectable(level, worldPosition, false)
            damien.nodeworks.render.UserPreviewRenderer.TrackedPlacers.remove(worldPosition)
        }
        val lvl = level
        if (lvl is ServerLevel) {
            NodeConnectionHelper.removeAllConnections(lvl, this)
            NodeConnectionHelper.untrackNode(lvl, worldPosition)
        }
        super.setRemoved()
    }

    // --- Auto-place ---

    /** Per-tick driver. No-op when the filter is empty (idle), the redstone
     *  gate blocks the placement, the cooldown is still running, or the
     *  target slot is occupied by a non-replaceable block. Lua-driven
     *  placement via [PlacerHandle] is unaffected and ignores this loop. */
    fun serverTick(level: ServerLevel) {
        if (filterRule.isEmpty()) return
        // Ignored = script-only, mirrors UserBlockEntity. Auto-place stays off
        // until the player switches to LOW or HIGH; Lua [PlacerHandle.place]
        // bypasses this gate by calling the placement helpers directly.
        if (redstoneMode == REDSTONE_IGNORED) return
        if (level.gameTime - lastAttemptTick < PLACE_INTERVAL_TICKS) return
        if (!redstoneAllows(level)) return
        val target = targetPos
        val targetState = level.getBlockState(target)
        if (!targetState.isAir && !targetState.canBeReplaced()) return
        lastAttemptTick = level.gameTime
        tryAutoPlace(level)
    }

    private fun redstoneAllows(level: ServerLevel): Boolean {
        if (redstoneMode == REDSTONE_IGNORED) return true
        val powered = level.hasNeighborSignal(worldPosition)
        return when (redstoneMode) {
            REDSTONE_LOW -> !powered
            REDSTONE_HIGH -> powered
            else -> true
        }
    }

    /** Walk channel-matching storage cards looking for the first item id that
     *  passes the filter and resolves to a [BlockItem]. The match is then
     *  used as the exact extraction target so the resulting place reflects
     *  exactly the item that left storage (not "any matching item" - the
     *  filter could span multiple block ids, and we want the visible block
     *  to match what was deducted). */
    private fun tryAutoPlace(level: ServerLevel): Boolean {
        val snapshot = damien.nodeworks.network.NetworkDiscovery.discoverNetwork(level, worldPosition)
        if (snapshot.controller == null) return false

        val filterPred: (String) -> Boolean = { CardHandle.matchesFilter(it, filterRule) }
        val blockItemPred: (String) -> Boolean = { id ->
            filterPred(id) && resolveBlockItem(id) != null
        }

        for (card in NetworkStorageHelper.getStorageCards(snapshot)) {
            if (!isChannelMatching(card)) continue
            val storage = NetworkStorageHelper.getStorage(level, card) ?: continue
            val foundItemId = PlatformServices.storage.findFirstItem(storage, blockItemPred) ?: continue
            val blockItem = resolveBlockItem(foundItemId) ?: continue
            if (placeBlockFromCard(level, snapshot, storage, foundItemId, blockItem)) return true
        }
        return false
    }

    /** Extract one [foundItemId] from [storage] and set [blockItem]'s default
     *  state at [targetPos], gated by [PlatformServices.fakePlayer.tryPlace]
     *  for spawn-protection / claim-mod / EntityPlaceEvent dispatch. Refunds
     *  the pulled item on rollback. Returns whether the place succeeded. */
    private fun placeBlockFromCard(
        level: ServerLevel,
        snapshot: damien.nodeworks.network.NetworkSnapshot,
        storage: damien.nodeworks.platform.ItemStorageHandle,
        foundItemId: String,
        blockItem: BlockItem,
    ): Boolean {
        val target = targetPos
        val newState = blockItem.block.defaultBlockState()
        val placedAgainst = level.getBlockState(worldPosition)
        val exactMatch: (String) -> Boolean = { it == foundItemId }
        var pulled = false
        return PlatformServices.fakePlayer.tryPlace(
            level, target, placedAgainst, ownerUuid,
            mutate = {
                val extracted = PlatformServices.storage.extractItems(storage, exactMatch, 1L)
                if (extracted < 1L) return@tryPlace false
                pulled = true
                level.setBlock(target, newState, Block.UPDATE_ALL)
                val soundType = newState.soundType
                level.playSound(
                    null, target,
                    soundType.placeSound,
                    net.minecraft.sounds.SoundSource.BLOCKS,
                    (soundType.volume + 1f) / 2f,
                    soundType.pitch * 0.8f,
                )
                true
            },
            onRollback = {
                if (pulled) {
                    val refund = ItemStack(blockItem, 1)
                    NetworkStorageHelper.insertItemStack(level, snapshot, refund)
                }
            },
        )
    }

    private fun isChannelMatching(card: damien.nodeworks.network.CardSnapshot): Boolean =
        channel == DyeColor.WHITE || card.channel == channel

    private fun resolveBlockItem(id: String): BlockItem? {
        val ident = Identifier.tryParse(id) ?: return null
        return BuiltInRegistries.ITEM.getValue(ident) as? BlockItem
    }

    // --- Serialization ---
    override fun saveAdditional(output: ValueOutput) {
        super.saveAdditional(output)
        output.putString("deviceName", deviceName)
        output.putInt("channel", channel.id)
        output.putString("filterRule", filterRule)
        output.putInt("redstoneMode", redstoneMode)
        output.putBoolean("previewArea", previewArea)
        networkId?.let { output.putString("networkId", it.toString()) }
        ownerUuid?.let { output.putString("ownerUuid", it.toString()) }
        output.putBlockPosList("connections", connections)
    }

    override fun loadAdditional(input: ValueInput) {
        super.loadAdditional(input)
        deviceName = input.getStringOr("deviceName", "")
        channel = runCatching { DyeColor.byId(input.getIntOr("channel", 0)) }.getOrDefault(DyeColor.WHITE)
        filterRule = input.getStringOr("filterRule", "")
        redstoneMode = input.getIntOr("redstoneMode", REDSTONE_IGNORED).coerceIn(0, 2)
        previewArea = input.getBooleanOr("previewArea", false)
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

    override fun getUpdateTag(registries: HolderLookup.Provider): CompoundTag = saveWithoutMetadata(registries)
    override fun getUpdatePacket(): Packet<ClientGamePacketListener> =
        ClientboundBlockEntityDataPacket.create(this)
}
