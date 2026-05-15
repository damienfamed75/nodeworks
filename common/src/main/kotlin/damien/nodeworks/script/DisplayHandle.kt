package damien.nodeworks.script

import damien.nodeworks.block.entity.DisplayBlockEntity
import damien.nodeworks.block.entity.DisplayElement
import damien.nodeworks.block.entity.DisplayElementType
import damien.nodeworks.network.DisplaySnapshot
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.DyeColor
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.luaj.vm2.lib.OneArgFunction
import org.luaj.vm2.lib.ThreeArgFunction
import org.luaj.vm2.lib.TwoArgFunction
import org.luaj.vm2.lib.VarArgFunction

/**
 * Creates the Lua `DisplayHandle` returned by `network:display(name)`.
 *
 * The Display is a pure draw surface, retained-mode, builder-style API.
 * `d:box/text/line/item/entity(...)` append an element and return a small
 * typed builder; chaining cosmetic methods configures the element. The chain
 * is scoped to one element, you go back to `d` for the next. `d:flush()`
 * commits the frame. There are no interactive elements, player input lives on
 * the separate Widget block.
 *
 *     d:clear()
 *     d:box(-1, -1, 2, 2):color("blue")
 *     d:text(-0.9, 0.8, "ONLINE"):color("lime"):size(1.5)
 *     d:flush()
 *
 * Coordinates are normalized (`-1..1`, `(-1,-1)` bottom-left, `+y` up).
 */
object DisplayHandle {

    fun create(snapshot: DisplaySnapshot, level: ServerLevel): LuaTable {
        val pos = snapshot.pos
        val accumulator = mutableListOf<DisplayElement>()
        var nextId = 0

        fun getEntity(): DisplayBlockEntity =
            level.getBlockEntity(pos) as? DisplayBlockEntity
                ?: throw LuaError("Display block '${snapshot.name}' has been removed")

        // ---- shared builder-method installers ----

        fun addColor(t: LuaTable, tn: String, e: DisplayElement) {
            t.setGuarded(tn, "color", object : TwoArgFunction() {
                override fun call(self: LuaValue, c: LuaValue): LuaValue {
                    e.color = (e.color and 0xFF000000.toInt()) or dyeRgb(c.checkjstring())
                    return self
                }
            })
        }

        fun addOpacity(t: LuaTable, tn: String, e: DisplayElement) {
            t.setGuarded(tn, "opacity", object : TwoArgFunction() {
                override fun call(self: LuaValue, o: LuaValue): LuaValue {
                    val a = (o.checkdouble().coerceIn(0.0, 1.0) * 255.0).toInt() shl 24
                    e.color = a or (e.color and 0xFFFFFF)
                    e.color2 = a or (e.color2 and 0xFFFFFF)
                    return self
                }
            })
        }

        fun addGradient(t: LuaTable, tn: String, e: DisplayElement) {
            t.setGuarded(tn, "gradient", object : ThreeArgFunction() {
                override fun call(self: LuaValue, a: LuaValue, b: LuaValue): LuaValue {
                    val alpha = e.color and 0xFF000000.toInt()
                    e.color = alpha or dyeRgb(a.checkjstring())
                    e.color2 = alpha or dyeRgb(b.checkjstring())
                    e.gradient = true
                    return self
                }
            })
        }

        fun addZ(t: LuaTable, tn: String, e: DisplayElement) {
            t.setGuarded(tn, "z", object : TwoArgFunction() {
                override fun call(self: LuaValue, v: LuaValue): LuaValue {
                    e.z = finite(v.checkdouble())
                    return self
                }
            })
        }

        fun addDepth(t: LuaTable, tn: String, e: DisplayElement) {
            t.setGuarded(tn, "depth", object : TwoArgFunction() {
                override fun call(self: LuaValue, v: LuaValue): LuaValue {
                    e.d = finite(v.checkdouble())
                    return self
                }
            })
        }

        fun addBillboard(t: LuaTable, tn: String, e: DisplayElement) {
            t.setGuarded(tn, "billboard", object : OneArgFunction() {
                override fun call(self: LuaValue): LuaValue {
                    e.billboard = true
                    return self
                }
            })
        }

        fun addWireframe(t: LuaTable, tn: String, e: DisplayElement) {
            t.setGuarded(tn, "wireframe", object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs {
                    e.wireframe = if (args.narg() >= 2 && !args.arg(2).isnil()) {
                        args.checkint(2).coerceIn(1, 16)
                    } else 1
                    return args.arg1()
                }
            })
        }

        fun addThickness(t: LuaTable, tn: String, e: DisplayElement) {
            t.setGuarded(tn, "thickness", object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs {
                    e.wireframe = if (args.narg() >= 2 && !args.arg(2).isnil()) {
                        args.checkint(2).coerceIn(1, 16)
                    } else 1
                    return args.arg1()
                }
            })
        }

        fun addRot(t: LuaTable, tn: String, e: DisplayElement, text: Boolean) {
            t.setGuarded(tn, "rot", object : TwoArgFunction() {
                override fun call(self: LuaValue, v: LuaValue): LuaValue {
                    val deg = finite(v.checkdouble())
                    if (text) e.textRotation = deg else e.rotation = deg
                    return self
                }
            })
        }

        fun addSize(t: LuaTable, tn: String, e: DisplayElement) {
            t.setGuarded(tn, "size", object : TwoArgFunction() {
                override fun call(self: LuaValue, v: LuaValue): LuaValue {
                    e.textSize = finite(v.checkdouble()).coerceIn(0.1f, 16f)
                    return self
                }
            })
        }

        // ---- builder factories ----

        fun boxBuilder(e: DisplayElement): LuaTable {
            val t = LuaTable()
            addColor(t, "DisplayBox", e); addOpacity(t, "DisplayBox", e)
            addGradient(t, "DisplayBox", e); addZ(t, "DisplayBox", e)
            addDepth(t, "DisplayBox", e); addBillboard(t, "DisplayBox", e)
            addWireframe(t, "DisplayBox", e); addRot(t, "DisplayBox", e, text = false)
            return t
        }

        fun textBuilder(e: DisplayElement): LuaTable {
            val t = LuaTable()
            addColor(t, "DisplayText", e); addOpacity(t, "DisplayText", e)
            addZ(t, "DisplayText", e); addBillboard(t, "DisplayText", e)
            addSize(t, "DisplayText", e); addRot(t, "DisplayText", e, text = true)
            return t
        }

        fun lineBuilder(e: DisplayElement): LuaTable {
            val t = LuaTable()
            addColor(t, "DisplayLine", e); addOpacity(t, "DisplayLine", e)
            addGradient(t, "DisplayLine", e); addThickness(t, "DisplayLine", e)
            addZ(t, "DisplayLine", e); addBillboard(t, "DisplayLine", e)
            addRot(t, "DisplayLine", e, text = false)
            return t
        }

        fun itemBuilder(e: DisplayElement): LuaTable {
            val t = LuaTable()
            addZ(t, "DisplayItem", e); addBillboard(t, "DisplayItem", e)
            addSize(t, "DisplayItem", e); addRot(t, "DisplayItem", e, text = false)
            return t
        }

        fun entityBuilder(e: DisplayElement): LuaTable {
            val t = LuaTable()
            addZ(t, "DisplayEntity", e); addBillboard(t, "DisplayEntity", e)
            addSize(t, "DisplayEntity", e); addRot(t, "DisplayEntity", e, text = false)
            return t
        }

        // ---- the handle ----

        val table = LuaTable()
        table.set("name", LuaValue.valueOf(snapshot.name))
        table.set("kind", LuaValue.valueOf("display"))

        table.setGuarded("DisplayHandle", "clear", object : OneArgFunction() {
            override fun call(self: LuaValue): LuaValue {
                accumulator.clear()
                return self
            }
        })

        table.setGuarded("DisplayHandle", "box", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val e = DisplayElement(DisplayElementType.BOX, nextId++).apply {
                    x = finite(args.checkdouble(2)); y = finite(args.checkdouble(3))
                    w = finite(args.checkdouble(4)); h = finite(args.checkdouble(5))
                }
                accumulator += e
                return boxBuilder(e)
            }
        })

        table.setGuarded("DisplayHandle", "text", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val e = DisplayElement(DisplayElementType.TEXT, nextId++).apply {
                    x = finite(args.checkdouble(2)); y = finite(args.checkdouble(3))
                    label = args.checkjstring(4)
                    color = 0xFFFFFFFF.toInt()
                }
                accumulator += e
                return textBuilder(e)
            }
        })

        table.setGuarded("DisplayHandle", "line", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val e = DisplayElement(DisplayElementType.LINE, nextId++).apply {
                    x = finite(args.checkdouble(2)); y = finite(args.checkdouble(3))
                    x2 = finite(args.checkdouble(4)); y2 = finite(args.checkdouble(5))
                }
                accumulator += e
                return lineBuilder(e)
            }
        })

        table.setGuarded("DisplayHandle", "item", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val e = DisplayElement(DisplayElementType.ITEM, nextId++).apply {
                    label = args.checkjstring(2)
                    x = finite(args.checkdouble(3)); y = finite(args.checkdouble(4))
                }
                accumulator += e
                return itemBuilder(e)
            }
        })

        table.setGuarded("DisplayHandle", "entity", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val e = DisplayElement(DisplayElementType.ENTITY, nextId++).apply {
                    label = args.checkjstring(2)
                    x = finite(args.checkdouble(3)); y = finite(args.checkdouble(4))
                }
                accumulator += e
                return entityBuilder(e)
            }
        })

        // flush / render: commit the frame, then reset the accumulator so a
        // handle reused across ticks doesn't pile up.
        val flush = object : OneArgFunction() {
            override fun call(self: LuaValue): LuaValue {
                getEntity().setElements(accumulator.toList())
                accumulator.clear()
                return LuaValue.NONE
            }
        }
        table.setGuarded("DisplayHandle", "flush", flush)
        table.setGuarded("DisplayHandle", "render", flush)

        return table
    }

    /** Resolve a dye-color name to a packed RGB int (no alpha). Unknown names fall back to gray. */
    private fun dyeRgb(name: String): Int =
        (DyeColor.byName(name.lowercase(), DyeColor.GRAY) ?: DyeColor.GRAY)
            .textureDiffuseColor and 0xFFFFFF

    private fun finite(v: Double): Float {
        val f = v.toFloat()
        return if (f.isFinite()) f else 0f
    }
}
