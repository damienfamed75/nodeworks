package damien.nodeworks.screen

import damien.nodeworks.block.entity.MultiProcessingHandlerBlockEntity
import damien.nodeworks.network.MultiProcessingHandlerStateSyncPayload
import damien.nodeworks.script.ServerPolicy
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer

/**
 * Server-side logic for the Multi-Processing Handler GUI. The claim rule and the
 * parent network walk are [ProcessingHandlerServerLogic]'s, so a set one Handler kind
 * refuses the other refuses too.
 */
object MultiProcessingHandlerServerLogic {

    /** Bind or unbind one set. A bind must find the set on the parent network, see
     *  nobody else handling it, and have room under the cap. Anything else is a stale
     *  or forged click and is ignored. */
    fun toggle(level: ServerLevel, self: MultiProcessingHandlerBlockEntity, apiName: String, selected: Boolean) {
        if (apiName.isEmpty()) return
        if (!selected) {
            self.unbindSet(apiName)
            return
        }
        if (self.handles(apiName)) return
        if (self.boundSetNames().size >= ServerPolicy.current.multiHandlerMaxSets) return
        val snapshot = ProcessingHandlerServerLogic.parentSnapshot(level, self)
        val onNetwork = snapshot.processingApis.any { storage -> storage.apis.any { it.name == apiName } }
        if (!onNetwork) return
        if (ProcessingHandlerServerLogic.claimedBy(level, self, apiName, snapshot) != null) return
        self.bindSet(apiName)
    }

    fun buildOpenData(level: ServerLevel, entity: MultiProcessingHandlerBlockEntity): MultiProcessingHandlerOpenData {
        val snapshot = ProcessingHandlerServerLogic.parentSnapshot(level, entity)
        val onNetwork = snapshot.processingApis.asSequence()
            .flatMap { it.apis.asSequence() }
            .distinctBy { it.name }
            .take(MultiProcessingHandlerOpenData.MAX_SETS_ON_WIRE)
            .map { api ->
                MultiProcessingHandlerOpenData.SetEntry(
                    ProcessingHandlerOpenData.AvailableSet(api.name, api.inputs, api.outputs),
                    ProcessingHandlerServerLogic.claimedBy(level, entity, api.name, snapshot) ?: "",
                )
            }
            .toList()
        // A bound set that left the network still shows, with no recipe, so the player
        // can see it is missing and uncheck it. It comes back to life when the set does.
        val present = onNetwork.mapTo(HashSet()) { it.set.name }
        val missing = entity.boundSetNames().filter { it !in present }.map { name ->
            MultiProcessingHandlerOpenData.SetEntry(ProcessingHandlerOpenData.AvailableSet(name, emptyList(), emptyList()), "")
        }
        val sets = onNetwork + missing
        return MultiProcessingHandlerOpenData(
            pos = entity.blockPos,
            maxSets = ServerPolicy.current.multiHandlerMaxSets,
            bound = entity.boundSetNames(),
            inputChannelId = entity.inputChannel.id,
            outputChannelId = entity.outputChannel.id,
            sets = sets,
        )
    }

    /** Refreshes the open menu after a change so the list, the counter and the greyed
     *  rows follow without a close and reopen. */
    fun pushStateSyncIfOpen(level: ServerLevel, player: ServerPlayer, entity: MultiProcessingHandlerBlockEntity) {
        val menu = player.containerMenu as? MultiProcessingHandlerMenu ?: return
        if (menu.devicePos != entity.blockPos) return
        player.connection.send(
            net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket(
                MultiProcessingHandlerStateSyncPayload(buildOpenData(level, entity))
            )
        )
    }
}
