package damien.nodeworks.script

import damien.nodeworks.block.entity.UserBlockEntity
import damien.nodeworks.network.UserSnapshot
import net.minecraft.server.level.ServerLevel
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.lib.OneArgFunction
import org.luaj.vm2.lib.TwoArgFunction

/**
 * Lua handle for a User device, the script-driven counterpart to the redstone
 * trigger. Pulls one item from network storage and "right-clicks" with it on
 * whatever's in front. INSTANT mode fires once per `:use()` call, HOLD mode
 * starts a hold that runs across multiple ticks until `:stop()` (or the 30 s
 * timeout, or item-broke).
 *
 * Per-method `getEntity()` re-fetches the live BlockEntity each call so the
 * handle survives BE churn (player breaks the User, places a new one, the
 * script keeps working on the next snapshot) the same way [PlacerHandle] does.
 *
 * Filter strings use the same syntax as Storage Card filters: bare ids
 * (`"minecraft:brush"`), `*` wildcard, `#namespace:tag`, `/regex/`, and the
 * `$item:` / `$fluid:` namespace prefixes. Resolved by [CardHandle.matchesFilter]
 * for consistency across the entire scripting surface.
 */
object UserHandle {

    fun create(
        snapshot: UserSnapshot,
        level: ServerLevel,
    ): LuaTable {
        val pos = snapshot.pos
        val alias = snapshot.effectiveAlias
        val table = LuaTable()

        fun getEntity(): UserBlockEntity =
            level.getBlockEntity(pos) as? UserBlockEntity
                ?: throw LuaError("User '$alias' has been removed")

        table.set("name", LuaValue.valueOf(alias))
        table.set("kind", LuaValue.valueOf("user"))

        // :filter() -> string. Empty string when no filter is set.
        table.setGuarded("UserHandle", "filter", object : OneArgFunction() {
            override fun call(self: LuaValue): LuaValue =
                LuaValue.valueOf(getEntity().filterRule)
        })

        // :setFilter(filter) -> nil. Storage Card filter syntax.
        table.setGuarded("UserHandle", "setFilter", object : TwoArgFunction() {
            override fun call(self: LuaValue, arg: LuaValue): LuaValue {
                if (!arg.isstring()) throw LuaError("setFilter expects a filter string")
                getEntity().filterRule = arg.checkjstring()
                return LuaValue.NIL
            }
        })

        // :mode() -> "instant" | "hold"
        table.setGuarded("UserHandle", "mode", object : OneArgFunction() {
            override fun call(self: LuaValue): LuaValue =
                LuaValue.valueOf(getEntity().mode.name.lowercase())
        })

        // :setMode(mode) -> nil. Validates against UserMode constants.
        table.setGuarded("UserHandle", "setMode", object : TwoArgFunction() {
            override fun call(self: LuaValue, arg: LuaValue): LuaValue {
                if (!arg.isstring()) throw LuaError("setMode expects 'instant' or 'hold'")
                val parsed = parseMode(arg.checkjstring())
                    ?: throw LuaError("Unknown UserMode '${arg.checkjstring()}', expected 'instant' or 'hold'")
                getEntity().mode = parsed
                return LuaValue.NIL
            }
        })

        // :facing() -> direction name
        table.setGuarded("UserHandle", "facing", object : OneArgFunction() {
            override fun call(self: LuaValue): LuaValue =
                LuaValue.valueOf(getEntity().facing.name.lowercase())
        })

        // :use() -> bool. Dispatches based on the BE's current mode. INSTANT
        // fires once and returns whether a use actually landed (false on
        // cooldown, denied filter, no item, no controller). HOLD starts a
        // hold and returns whether it entered the holding state (false on
        // any of the above OR if the item is one-shot and didn't enter
        // using-item mode). When already holding, returns false rather than
        // restarting -- script wanting a fresh hold should :stop() first.
        table.setGuarded("UserHandle", "use", object : OneArgFunction() {
            override fun call(self: LuaValue): LuaValue {
                val entity = getEntity()
                if (entity.isUsing) return LuaValue.FALSE
                val ok = when (entity.mode) {
                    UserBlockEntity.UseMode.INSTANT -> entity.tryUse(level)
                    UserBlockEntity.UseMode.HOLD -> entity.tryStartHold(level)
                }
                return LuaValue.valueOf(ok)
            }
        })

        // :stop() -> bool. Cancels a pending apex fire (extend phase) OR
        // releases an in-progress hold. Returns false (not an error) when
        // the User is idle so a script can call :stop() unconditionally
        // without guards. Retract phase is non-cancellable -- it's the
        // animated return of the reserved stack to network storage.
        table.setGuarded("UserHandle", "stop", object : OneArgFunction() {
            override fun call(self: LuaValue): LuaValue =
                LuaValue.valueOf(getEntity().stop(level))
        })

        // :isUsing() -> bool. True while a hold is active.
        table.setGuarded("UserHandle", "isUsing", object : OneArgFunction() {
            override fun call(self: LuaValue): LuaValue =
                LuaValue.valueOf(getEntity().isUsing)
        })

        return table
    }

    /** Parse a Lua-supplied mode string back into the BE enum. Lowercase is the
     *  canonical form (matches `UserMode.INSTANT = "instant"`), uppercase
     *  accepted as a convenience so `setMode("HOLD")` works the same as
     *  `setMode(UserMode.HOLD)`. */
    private fun parseMode(name: String): UserBlockEntity.UseMode? = when (name.lowercase()) {
        "instant" -> UserBlockEntity.UseMode.INSTANT
        "hold" -> UserBlockEntity.UseMode.HOLD
        else -> null
    }
}
