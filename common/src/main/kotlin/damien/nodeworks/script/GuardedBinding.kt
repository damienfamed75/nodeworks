package damien.nodeworks.script

import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.luaj.vm2.lib.VarArgFunction

/**
 * Extension helpers for binding script-callable methods that respect the
 * server's [ServerSafetySettings.disabledMethods] deny-list.
 *
 * Replace
 * ```
 * table.set("insert", insertFn)
 * ```
 * with
 * ```
 * table.setGuarded("Network", "insert", insertFn)
 * ```
 * and the binding becomes a no-op error path when the admin has listed
 * `"Network:insert"` in `nodeworks-server.toml` under
 * `[scripting.sandbox] disabledMethods`. Server-side enforcement is the
 * actual security boundary, the eventual client-side autocomplete filter
 * is a UX nicety on top.
 *
 * Pass-through when [fn] is nil so previously-disabled bindings (e.g.
 * inventory methods nilled on a Redstone Card's table) stay nil.
 */
fun LuaTable.setGuarded(typeName: String, methodName: String, fn: LuaValue): LuaTable {
    if (!fn.isfunction()) {
        this.set(methodName, fn)
        return this
    }
    val key = "$typeName:$methodName"
    val guarded = object : VarArgFunction() {
        override fun invoke(args: Varargs): Varargs {
            if (key in ServerPolicy.current.disabledMethods) {
                throw LuaError("$key is disabled on this server.")
            }
            return fn.invoke(args)
        }
    }
    this.set(methodName, guarded)
    return this
}
