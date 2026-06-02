package damien.nodeworks.script

/**
 * Client-side mirror of the server's script-sandbox policy and gameplay
 * config knobs. Populated by [damien.nodeworks.network.ServerPolicySyncPayload]
 * on join and on `/reload`.
 *
 * Server-side [LuaExecGate] / [GuardedBinding] enforcement is the actual
 * security boundary, this is UX. Defaults match [ServerSafetySettings.Defaults]
 * so the editor still suggests the full surface in singleplayer / before the
 * first sync arrives.
 */
object ClientServerPolicy {
    @Volatile
    var enabledModules: Set<String> = ServerSafetySettings.Defaults.enabledModules
        private set

    @Volatile
    var disabledMethods: Set<String> = ServerSafetySettings.Defaults.disabledMethods
        private set

    /** Mirrors [ServerSafetySettings.networkControllerChunkLoading]. */
    @Volatile
    var networkControllerChunkLoading: Boolean = ServerSafetySettings.Defaults.networkControllerChunkLoading
        private set

    /** Mirrors [ServerSafetySettings.grappleMaxDistance]. */
    @Volatile
    var grappleMaxDistance: Int = ServerSafetySettings.Defaults.grappleMaxDistance
        private set

    /** Mirrors [ServerSafetySettings.grappleEntities]. */
    @Volatile
    var grappleEntities: Boolean = ServerSafetySettings.Defaults.grappleEntities
        private set

    fun update(
        modules: Set<String>,
        disabled: Set<String>,
        chunkLoading: Boolean,
        grappleMaxDistance: Int,
        grappleEntities: Boolean,
    ) {
        enabledModules = modules
        disabledMethods = disabled
        networkControllerChunkLoading = chunkLoading
        this.grappleMaxDistance = grappleMaxDistance
        this.grappleEntities = grappleEntities
    }

    /** Is `Type:method` callable under the current server policy? Cheap, hot
     *  path for autocomplete filtering, single set lookup. */
    fun isMethodAllowed(typeName: String, methodName: String): Boolean =
        "$typeName:$methodName" !in disabledMethods
}
