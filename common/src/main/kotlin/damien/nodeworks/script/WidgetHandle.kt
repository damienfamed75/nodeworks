package damien.nodeworks.script

import damien.nodeworks.block.entity.WidgetBlockEntity
import damien.nodeworks.network.WidgetSnapshot
import net.minecraft.server.level.ServerLevel
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.lib.OneArgFunction

/**
 * Lua table handle for a Widget block, returned by `network:get(name)`.
 *
 * One unified handle covers all four widget types, each method is documented
 * for its per-type behaviour, and methods that don't apply to a type return a
 * harmless default (a switch's `:option()` is `""`, a button's `:min()` is 0).
 * The interaction callback `:on(fn)` is bound by [ScriptEngine] rather than
 * here, because it registers into the engine's per-tick poller, the same split
 * the redstone / observer card `:onChange` handlers use.
 */
object WidgetHandle {

    fun create(snapshot: WidgetSnapshot, level: ServerLevel): LuaTable {
        val pos = snapshot.pos
        val table = LuaTable()

        fun getEntity(): WidgetBlockEntity {
            return level.getBlockEntity(pos) as? WidgetBlockEntity
                ?: throw LuaError("Widget '${snapshot.name}' has been removed")
        }

        // value() -> number. BUTTON always 0, SWITCH 0/1, RADIO selected index,
        // SLIDER the number.
        table.setGuarded("WidgetHandle", "value", object : OneArgFunction() {
            override fun call(self: LuaValue): LuaValue =
                LuaValue.valueOf(getEntity().value.toDouble())
        })

        // type() -> string, one of "button" / "switch" / "radio" / "slider".
        table.setGuarded("WidgetHandle", "type", object : OneArgFunction() {
            override fun call(self: LuaValue): LuaValue =
                LuaValue.valueOf(getEntity().widgetType.name.lowercase())
        })

        // --- RADIO ---

        // option() -> string. The selected RADIO option text, "" otherwise.
        table.setGuarded("WidgetHandle", "option", object : OneArgFunction() {
            override fun call(self: LuaValue): LuaValue {
                val e = getEntity()
                return LuaValue.valueOf(e.radioOptions.getOrNull(e.value.toInt()) ?: "")
            }
        })

        // options() -> table. The RADIO option list as a 1-indexed array,
        // empty for other types.
        table.setGuarded("WidgetHandle", "options", object : OneArgFunction() {
            override fun call(self: LuaValue): LuaValue {
                val e = getEntity()
                val t = LuaTable()
                e.radioOptions.forEachIndexed { i, s -> t.set(i + 1, LuaValue.valueOf(s)) }
                return t
            }
        })

        // --- SLIDER ---

        table.setGuarded("WidgetHandle", "min", object : OneArgFunction() {
            override fun call(self: LuaValue): LuaValue =
                LuaValue.valueOf(getEntity().sliderMin.toDouble())
        })
        table.setGuarded("WidgetHandle", "max", object : OneArgFunction() {
            override fun call(self: LuaValue): LuaValue =
                LuaValue.valueOf(getEntity().sliderMax.toDouble())
        })
        table.setGuarded("WidgetHandle", "step", object : OneArgFunction() {
            override fun call(self: LuaValue): LuaValue =
                LuaValue.valueOf(getEntity().sliderStep.toDouble())
        })

        table.set("name", LuaValue.valueOf(snapshot.name))
        table.set("kind", LuaValue.valueOf("widget"))

        return table
    }
}
