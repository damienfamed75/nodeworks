package damien.nodeworks.script

import damien.nodeworks.block.entity.BreakerBlockEntity
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.lib.TwoArgFunction

/**
 * Lua table returned by `breaker:mine()`. Configures what happens with the
 * drops once the multi-tick break completes.
 *
 * One terminator method exists today — `:connect(fn)` — which redirects drops
 * to a script handler. If the script doesn't chain `:connect`, drops route to
 * network storage automatically (via [BreakerBlockEntity]'s default routing in
 * [BreakerBlockEntity.completeBreak]). No `:store()` method on purpose: "no
 * handler" already means "store," so a sentinel method would be redundant.
 *
 * When the underlying break didn't actually start (breaker busy, target invalid,
 * tier too low), [create] is called with a null entity and `:connect` becomes
 * a no-op so chained calls don't crash and the broadcast `HandleList:mine()`
 * shape stays uniform.
 */
object BreakBuilder {

    /** Build the builder Lua table. Pass [entity] = null for the no-op variant
     *  used when the break didn't actually start. */
    fun create(entity: BreakerBlockEntity?): LuaTable {
        val t = LuaTable()

        // :connect(fn) — set the drop-redirect handler on the entity. The function
        // is invoked once per drop ItemStack at break-completion time. If the entity
        // is null (no-op builder from a failed break-start), this discards the
        // function silently — there's nothing to attach it to.
        t.set("connect", object : TwoArgFunction() {
            override fun call(self: LuaValue, fnArg: LuaValue): LuaValue {
                val fn = fnArg.checkfunction()
                entity?.pendingHandler = fn
                return self
            }
        })

        return t
    }
}
