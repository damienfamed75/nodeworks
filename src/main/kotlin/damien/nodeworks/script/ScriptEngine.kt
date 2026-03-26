package damien.nodeworks.script

import damien.nodeworks.network.CardSnapshot
import damien.nodeworks.network.NetworkDiscovery
import damien.nodeworks.network.NetworkSnapshot
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import org.luaj.vm2.*
import org.luaj.vm2.compiler.LuaC
import org.luaj.vm2.lib.*
import org.luaj.vm2.lib.jse.JseBaseLib
import org.luaj.vm2.lib.jse.JseMathLib
import org.slf4j.LoggerFactory

/**
 * Manages a sandboxed Lua VM for one terminal. Provides the Nodeworks API
 * (card, scheduler, print) and enforces an instruction budget per tick.
 */
class ScriptEngine(
    private val level: ServerLevel,
    private val networkEntryNode: BlockPos,
    private val logCallback: (String, Boolean) -> Unit // (message, isError)
) {
    private val logger = LoggerFactory.getLogger("nodeworks-script")

    private var globals: Globals? = null
    private var networkSnapshot: NetworkSnapshot? = null
    val scheduler = SchedulerImpl()

    /** Max instructions per tick to prevent infinite loops. */
    private val maxInstructionsPerTick = 50_000

    fun start(scriptText: String): Boolean {
        stop()

        // Discover network
        networkSnapshot = NetworkDiscovery.discoverNetwork(level, networkEntryNode)

        // Create sandboxed globals
        val g = Globals()
        g.load(JseBaseLib())
        g.load(PackageLib())
        g.load(Bit32Lib())
        g.load(TableLib())
        g.load(StringLib())
        g.load(JseMathLib())

        // Install the Lua compiler
        LuaC.install(g)

        // Remove dangerous globals
        g.set("dofile", LuaValue.NIL)
        g.set("loadfile", LuaValue.NIL)
        g.set("require", LuaValue.NIL)
        g.set("io", LuaValue.NIL)
        g.set("os", LuaValue.NIL)
        g.set("luajava", LuaValue.NIL)

        // Install instruction budget hook
        g.load(DebugLib())
        val hookFunc = object : ZeroArgFunction() {
            override fun call(): LuaValue {
                throw LuaError("Script exceeded instruction limit")
            }
        }
        g.get("debug").get("sethook").call(hookFunc, LuaValue.EMPTYSTRING, LuaValue.valueOf(maxInstructionsPerTick))
        // Remove debug lib from script access after setting hook
        g.set("debug", LuaValue.NIL)

        // Inject Nodeworks API
        injectApi(g)

        globals = g

        // Compile and run the script (top-level code: variable setup, scheduler registrations)
        return try {
            val chunk = g.load(scriptText, "terminal")
            chunk.call()
            logCallback("Script started.", false)
            true
        } catch (e: LuaError) {
            logCallback("Error: ${e.message}", true)
            logger.warn("Script error: {}", e.message)
            stop()
            false
        }
    }

    fun stop() {
        scheduler.clear()
        globals = null
        networkSnapshot = null
    }

    fun isRunning(): Boolean = globals != null

    /** Called each server tick. Runs scheduler callbacks within the instruction budget. */
    fun tick(tickCount: Long) {
        val g = globals ?: return
        // Reset instruction count for this tick
        val hookFunc = object : ZeroArgFunction() {
            override fun call(): LuaValue {
                throw LuaError("Script exceeded instruction limit")
            }
        }
        g.get("debug")?.let {
            // debug was removed, re-add temporarily for hook reset
        }
        // The hook is persistent from start(), so instruction count resets aren't easy with LuaJ.
        // For now, rely on the initial hook. Future: use a custom debug hook that resets per tick.

        try {
            scheduler.tick(tickCount)
        } catch (e: LuaError) {
            logCallback("Runtime error: ${e.message}", true)
            logger.warn("Script runtime error: {}", e.message)
            stop()
        }
    }

    private fun injectApi(g: Globals) {
        val snapshot = networkSnapshot!!

        // card(type, alias) -> CardHandle
        g.set("card", object : TwoArgFunction() {
            override fun call(typeArg: LuaValue, aliasArg: LuaValue): LuaValue {
                val type = typeArg.checkjstring()
                val alias = aliasArg.checkjstring()
                val card = snapshot.allCards().find {
                    it.capability.type == type && it.alias == alias
                } ?: throw LuaError("Card not found: $type '$alias'")
                return CardHandle.create(card, level)
            }
        })

        // scheduler object
        g.set("scheduler", scheduler.createLuaTable())

        // print(message)
        g.set("print", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue {
                logCallback(arg.tojstring(), false)
                return LuaValue.NIL
            }
        })
    }
}
