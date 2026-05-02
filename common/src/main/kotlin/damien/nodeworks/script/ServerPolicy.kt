package damien.nodeworks.script

/**
 * Server-side policy holder for script execution safety. The single source of
 * truth that [LuaExecGate] reads from on every gated entry, so live `/reload`
 * of the loader-specific config flows through to in-flight engines on their
 * next call into Lua.
 *
 * Loader-specific code populates this from its config layer:
 *   - NeoForge: [NodeworksServerConfig.SPEC]'s values, copied via
 *     [ModConfigEvent.Loading]/[ModConfigEvent.Reloading] listeners.
 *   - Fabric: deferred until the Fabric module ships.
 *
 * Plain Kotlin singleton (no platform imports) so it lives cleanly in `:common`.
 * [@Volatile] on [current] keeps cross-thread reads consistent. The config
 * thread that writes a new instance during a `/reload` and the server tick
 * thread that reads it during the next gated execution see a coherent value
 * without locks.
 */
object ServerPolicy {
    @Volatile
    var current: ServerSafetySettings = ServerSafetySettings.Defaults
        private set

    /** Replace [current] with [newSettings]. Called from the loader's config-event
     *  listener whenever the file is loaded or reloaded. Engines pick up the
     *  change on their next gated entry, no engine restart required. */
    fun update(newSettings: ServerSafetySettings) {
        current = newSettings
    }

    /** Reset to compiled-in defaults. Used by tests and by the unit-test path
     *  that constructs an engine without going through the config layer. */
    fun resetToDefaults() {
        current = ServerSafetySettings.Defaults
    }
}
