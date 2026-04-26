package damien.nodeworks.script.api

/**
 * One-shot registration of every DSL-declared API surface into [LuaApiRegistry], plus
 * the validator pass that catches dangling type refs and key-shape violations.
 *
 * Called lazily from [damien.nodeworks.script.LuaApiDocs] on first access so the
 * registry is populated before any consumer (hover tooltips, guidebook tag,
 * autocomplete) reads from it. Idempotent, repeat calls are a no-op.
 *
 * As surfaces migrate from the legacy [LuaApiDocs.entries] map to DSL specs, add their
 * [ApiSurface] to the [register] block here. The registry validator runs at [seal] and
 * fails the call if any spec has dangling type references, so adding a typo'd
 * `returns(SomeType)` blows up at init in dev rather than silently sometime later.
 */
object LuaApiBootstrap {

    @Volatile
    private var initialized = false

    fun ensureInitialized() {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            register()
            LuaApiRegistry.seal()
            initialized = true
        }
    }

    private fun register() {
        for (stringType in ALL_STRING_TYPES) {
            LuaApiRegistry.registerStringType(stringType)
        }
        LuaApiRegistry.register(SchedulerApi)
        LuaApiRegistry.register(ItemsHandleApi)
        LuaApiRegistry.register(CardHandleApi)
        LuaApiRegistry.register(JobApi)
        LuaApiRegistry.register(RedstoneCardApi)
        LuaApiRegistry.register(ObserverCardApi)
        LuaApiRegistry.register(BreakerHandleApi)
        LuaApiRegistry.register(BreakBuilderApi)
        LuaApiRegistry.register(PlacerHandleApi)
        LuaApiRegistry.register(VariableHandleApi)
        LuaApiRegistry.register(NumberVariableHandleApi)
        LuaApiRegistry.register(StringVariableHandleApi)
        LuaApiRegistry.register(BoolVariableHandleApi)
    }
}
