package damien.nodeworks.script

import damien.nodeworks.block.entity.VariableBlockEntity
import damien.nodeworks.block.entity.VariableType
import damien.nodeworks.network.VariableSnapshot
import net.minecraft.server.level.ServerLevel
import org.luaj.vm2.*
import org.luaj.vm2.lib.*

/**
 * Creates a Lua table handle for a network variable block.
 * Each method accesses the live block entity for current values.
 * All methods use `:` calling convention (self is first arg).
 */
object VariableHandle {

    fun create(snapshot: VariableSnapshot, level: ServerLevel): LuaTable {
        val pos = snapshot.pos
        val table = LuaTable()

        fun getEntity(): VariableBlockEntity {
            return level.getBlockEntity(pos) as? VariableBlockEntity
                ?: throw LuaError("Variable block '${snapshot.name}' has been removed")
        }

        // --- Universal methods ---

        table.setGuarded("VariableHandle", "get", object : OneArgFunction() {
            override fun call(self: LuaValue): LuaValue {
                val entity = getEntity()
                return when (entity.variableType) {
                    VariableType.NUMBER -> LuaValue.valueOf(entity.variableValue.toDoubleOrNull() ?: 0.0)
                    VariableType.STRING -> LuaValue.valueOf(entity.variableValue)
                    VariableType.BOOL -> LuaValue.valueOf(entity.variableValue == "true")
                }
            }
        })

        table.setGuarded("VariableHandle", "set", object : TwoArgFunction() {
            override fun call(self: LuaValue, arg: LuaValue): LuaValue {
                val entity = getEntity()
                val strVal = when (entity.variableType) {
                    VariableType.NUMBER -> {
                        val n = arg.checknumber()
                        formatNumber(n.todouble())
                    }
                    VariableType.STRING -> arg.checkjstring()
                    VariableType.BOOL -> arg.checkboolean().toString()
                }
                entity.setValue(strVal)
                return LuaValue.NONE
            }
        })

        table.setGuarded("VariableHandle", "cas", object : ThreeArgFunction() {
            override fun call(self: LuaValue, expected: LuaValue, new: LuaValue): LuaValue {
                val entity = getEntity()
                val expStr = toLuaString(expected, entity.variableType)
                val newStr = toLuaString(new, entity.variableType)
                return LuaValue.valueOf(entity.compareAndSet(expStr, newStr))
            }
        })

        table.setGuarded("VariableHandle", "type", object : OneArgFunction() {
            override fun call(self: LuaValue): LuaValue {
                return LuaValue.valueOf(getEntity().variableType.name.lowercase())
            }
        })

        // .name, readable identifier for the variable, matching how CardHandle
        // exposes `.name`. Snapshotted at handle creation so `print(v.name)`
        // returns the name the script used when it looked the variable up,
        // even if the variable is later renamed in its GUI.
        table.set("name", LuaValue.valueOf(snapshot.name))
        table.set("kind", LuaValue.valueOf("variable"))

        // --- Number methods ---

        table.setGuarded("VariableHandle", "increment", object : TwoArgFunction() {
            override fun call(self: LuaValue, arg: LuaValue): LuaValue {
                val entity = getEntity()
                checkType(entity, VariableType.NUMBER, "increment")
                return LuaValue.valueOf(entity.increment(arg.checkdouble()))
            }
        })

        table.setGuarded("VariableHandle", "decrement", object : TwoArgFunction() {
            override fun call(self: LuaValue, arg: LuaValue): LuaValue {
                val entity = getEntity()
                checkType(entity, VariableType.NUMBER, "decrement")
                return LuaValue.valueOf(entity.decrement(arg.checkdouble()))
            }
        })

        table.setGuarded("VariableHandle", "min", object : TwoArgFunction() {
            override fun call(self: LuaValue, arg: LuaValue): LuaValue {
                val entity = getEntity()
                checkType(entity, VariableType.NUMBER, "min")
                return LuaValue.valueOf(entity.atomicMin(arg.checkdouble()))
            }
        })

        table.setGuarded("VariableHandle", "max", object : TwoArgFunction() {
            override fun call(self: LuaValue, arg: LuaValue): LuaValue {
                val entity = getEntity()
                checkType(entity, VariableType.NUMBER, "max")
                return LuaValue.valueOf(entity.atomicMax(arg.checkdouble()))
            }
        })

        // --- String methods ---

        table.setGuarded("VariableHandle", "append", object : TwoArgFunction() {
            override fun call(self: LuaValue, arg: LuaValue): LuaValue {
                val entity = getEntity()
                checkType(entity, VariableType.STRING, "append")
                return LuaValue.valueOf(entity.appendValue(arg.checkjstring()))
            }
        })

        table.setGuarded("VariableHandle", "length", object : OneArgFunction() {
            override fun call(self: LuaValue): LuaValue {
                val entity = getEntity()
                checkType(entity, VariableType.STRING, "length")
                return LuaValue.valueOf(entity.variableValue.length)
            }
        })

        table.setGuarded("VariableHandle", "clear", object : OneArgFunction() {
            override fun call(self: LuaValue): LuaValue {
                val entity = getEntity()
                checkType(entity, VariableType.STRING, "clear")
                entity.clearValue()
                return LuaValue.NONE
            }
        })

        // --- Bool methods ---

        table.setGuarded("VariableHandle", "toggle", object : OneArgFunction() {
            override fun call(self: LuaValue): LuaValue {
                val entity = getEntity()
                checkType(entity, VariableType.BOOL, "toggle")
                return LuaValue.valueOf(entity.toggleValue())
            }
        })

        table.setGuarded("VariableHandle", "tryLock", object : OneArgFunction() {
            override fun call(self: LuaValue): LuaValue {
                val entity = getEntity()
                checkType(entity, VariableType.BOOL, "tryLock")
                return LuaValue.valueOf(entity.tryLock())
            }
        })

        table.setGuarded("VariableHandle", "unlock", object : OneArgFunction() {
            override fun call(self: LuaValue): LuaValue {
                val entity = getEntity()
                checkType(entity, VariableType.BOOL, "unlock")
                entity.unlock()
                return LuaValue.NONE
            }
        })

        return table
    }

    private fun checkType(entity: VariableBlockEntity, expected: VariableType, method: String) {
        if (entity.variableType != expected) {
            throw LuaError("Cannot call '$method' on a ${entity.variableType.name.lowercase()} variable (expected ${expected.name.lowercase()})")
        }
    }

    private fun toLuaString(value: LuaValue, type: VariableType): String = when (type) {
        VariableType.NUMBER -> formatNumber(value.checkdouble())
        VariableType.STRING -> value.checkjstring()
        VariableType.BOOL -> value.checkboolean().toString()
    }

    private fun formatNumber(d: Double): String {
        return if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()
    }
}
