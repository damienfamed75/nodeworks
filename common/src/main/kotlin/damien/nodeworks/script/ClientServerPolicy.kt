package damien.nodeworks.script

/**
 * Client-side mirror of the server's script-sandbox policy. Populated by the
 * [damien.nodeworks.network.ServerPolicySyncPayload] handler on join and on
 * `/reload`, read by [damien.nodeworks.screen.widget.AutocompletePopup] so
 * disabled methods don't show up as completions.
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

    fun update(modules: Set<String>, disabled: Set<String>) {
        enabledModules = modules
        disabledMethods = disabled
    }

    /** Is `Type:method` callable under the current server policy? Cheap, hot
     *  path for autocomplete filtering, single set lookup. */
    fun isMethodAllowed(typeName: String, methodName: String): Boolean =
        "$typeName:$methodName" !in disabledMethods
}
