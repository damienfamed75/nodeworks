package damien.nodeworks.script

/**
 * Client-side mirror of the server's script-sandbox policy. Populated by the
 * [damien.nodeworks.network.ServerPolicySyncPayload] handler on join and on
 * server `/reload`, read by the autocomplete popup so methods the server has
 * disabled don't show up as completions.
 *
 * Server-side [LuaExecGate]/[GuardedBinding] enforcement is the actual security
 * boundary, this is purely UX, a script that types out a disabled call still
 * gets a "disabled on this server" Lua error. Until the first sync arrives,
 * defaults match [ServerSafetySettings.Defaults] (full module set, empty deny
 * list) so singleplayer / pre-sync edits stay usable.
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
