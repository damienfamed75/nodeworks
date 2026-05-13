package damien.nodeworks.screen

import damien.nodeworks.block.entity.ProcessingHandlerBlockEntity
import damien.nodeworks.network.NetworkDiscovery
import damien.nodeworks.network.ProcessingHandlerStateSyncPayload
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer

/**
 * Server-side logic shared between the bind C2S handler and the open-data
 * builder. Lives outside [ProcessingHandlerBlockEntity] so the BE doesn't need
 * to know about ScriptEngine internals when checking "is this Set already
 * claimed by a Lua handler".
 */
object ProcessingHandlerServerLogic {

    /**
     * Whether [apiName] currently has a handler other than [self] (Lua or
     * another block) on the parent network anchored at [self]'s back face.
     * Used by the picker to hide already-claimed Sets and by [bind] to refuse
     * a stale bind request (the player's screen may be out of date relative
     * to a recent Lua registration).
     */
    fun isSetClaimedByOther(
        level: ServerLevel,
        self: ProcessingHandlerBlockEntity,
        apiName: String,
    ): Boolean {
        val parentSnapshot = NetworkDiscovery.discoverNetwork(
            level, self.blockPos.relative(self.backFace),
        )
        // Check Lua handlers registered on any terminal of the parent network.
        val hasLuaHandler = parentSnapshot.terminalPositions.any { terminalPos ->
            val engine = damien.nodeworks.platform.PlatformServices.modState
                .findProcessingEngine(level, listOf(terminalPos), apiName)
            engine != null
        }
        if (hasLuaHandler) return true
        // Check other block-side Processing Handlers on the same parent
        // network via the BlockHandlerRegistry. Same-pos entries are this
        // handler itself (re-binding to the same recipe is fine).
        val networkId = parentSnapshot.networkId
        if (networkId != null) {
            val claimedBy = damien.nodeworks.script.cpu.BlockHandlerRegistry.find(networkId, apiName)
            if (claimedBy != null && claimedBy != self.blockPos) return true
        }
        return false
    }

    /**
     * Validate + apply a bind request. The Set must be on the parent network
     * AND not already claimed by another handler. On success the BE's
     * [ProcessingHandlerBlockEntity.processingApiName] is set and the input
     * channel map is reset to the Set's input itemIds at the default channel.
     */
    fun bind(
        level: ServerLevel,
        self: ProcessingHandlerBlockEntity,
        apiName: String,
    ) {
        if (apiName.isEmpty()) {
            self.unbind()
            return
        }
        val parentSnapshot = NetworkDiscovery.discoverNetwork(
            level, self.blockPos.relative(self.backFace),
        )
        // Resolve the set on the parent network so we can pull the input
        // itemIds for the channel-map default.
        val match = parentSnapshot.processingApis
            .flatMap { it.apis }
            .firstOrNull { it.name == apiName } ?: return
        if (isSetClaimedByOther(level, self, apiName)) return
        self.bindToProcessingSet(apiName, match.inputs)
    }

    /**
     * Fresh open-payload snapshot for the player's currently-open
     * [ProcessingHandlerMenu]. Mirrors the server-side build performed when
     * the GUI first opens, but reused after every binding-affecting state
     * change so the screen's `boundSet` and `availableSets` refresh without a
     * close/reopen.
     */
    fun buildOpenData(level: ServerLevel, entity: ProcessingHandlerBlockEntity): ProcessingHandlerOpenData {
        val parentSnapshot = NetworkDiscovery.discoverNetwork(
            level, entity.blockPos.relative(entity.backFace),
        )
        val allOnNetwork = parentSnapshot.processingApis.flatMap { it.apis }
        val available = allOnNetwork
            .filter { it.name != entity.processingApiName }
            .filter { !isSetClaimedByOther(level, entity, it.name) }
            .map { ProcessingHandlerOpenData.AvailableSet(it.name, it.inputs, it.outputs) }
        val boundOnNetwork = allOnNetwork.firstOrNull { it.name == entity.processingApiName }
        val boundSet = boundOnNetwork?.let {
            ProcessingHandlerOpenData.AvailableSet(it.name, it.inputs, it.outputs)
        }
        val boundSetMissing = entity.processingApiName.isNotEmpty() && boundOnNetwork == null
        val inputChannelEntries = entity.snapshotInputChannels().map { (key, color) ->
            ProcessingHandlerOpenData.InputChannelEntry(key.itemId, key.componentsHash, color.id)
        }
        return ProcessingHandlerOpenData(
            pos = entity.blockPos,
            processingApiName = entity.processingApiName,
            boundSetMissing = boundSetMissing,
            inputChannels = inputChannelEntries,
            outputChannelId = entity.outputChannel.id,
            boundSet = boundSet,
            available = available,
        )
    }

    /**
     * Push a fresh [ProcessingHandlerOpenData] snapshot down to [player] when
     * they have a [ProcessingHandlerMenu] open targeting [entity]. No-op
     * otherwise. Called after every bind / unbind to keep the screen's
     * recipe-strip + button row in sync.
     */
    fun pushStateSyncIfOpen(level: ServerLevel, player: ServerPlayer, entity: ProcessingHandlerBlockEntity) {
        val menu = player.containerMenu as? ProcessingHandlerMenu ?: return
        if (menu.devicePos != entity.blockPos) return
        val data = buildOpenData(level, entity)
        player.connection.send(
            net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket(
                ProcessingHandlerStateSyncPayload(data)
            )
        )
    }
}

