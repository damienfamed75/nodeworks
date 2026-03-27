package damien.nodeworks.script

import damien.nodeworks.network.CardSnapshot
import damien.nodeworks.network.NetworkDiscovery
import damien.nodeworks.network.NetworkSnapshot
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import damien.nodeworks.platform.PlatformServices
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

        // Initialize scheduler with the current server tick
        scheduler.initialize(PlatformServices.modState.tickCount)

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
        if (globals == null) return

        try {
            scheduler.tick(tickCount)
        } catch (e: LuaError) {
            logCallback("Runtime error: ${e.message}", true)
            logger.warn("Script runtime error: {}", e.message)
            stop()
        } catch (e: Exception) {
            logCallback("Runtime error: ${e.message}", true)
            logger.warn("Script runtime exception: {}", e.message, e)
            stop()
        }
    }

    private fun injectApi(g: Globals) {
        val snapshot = networkSnapshot!!

        // scheduler object
        g.set("scheduler", scheduler.createLuaTable())

        // network object
        val networkTable = LuaTable()

        // network:get(alias) → CardHandle or error
        networkTable.set("get", object : TwoArgFunction() {
            override fun call(selfArg: LuaValue, aliasArg: LuaValue): LuaValue {
                val alias = aliasArg.checkjstring()
                val card = snapshot.findByAlias(alias)
                    ?: throw LuaError("Not found on network: '$alias'")
                return CardHandle.create(card, level)
            }
        })

        // network:find(type) → list of CardHandles matching that type
        networkTable.set("find", object : TwoArgFunction() {
            override fun call(selfArg: LuaValue, typeArg: LuaValue): LuaValue {
                val type = typeArg.checkjstring()
                val cards = snapshot.allCards().filter { it.capability.type == type }
                val result = LuaTable()
                for ((i, card) in cards.withIndex()) {
                    result.set(i + 1, CardHandle.create(card, level))
                }
                return result
            }
        })

        // network:count(filter) → number (across all Storage Cards)
        networkTable.set("count", object : TwoArgFunction() {
            override fun call(selfArg: LuaValue, filterArg: LuaValue): LuaValue {
                val filter = filterArg.checkjstring()
                val count = NetworkStorageHelper.countItems(level, snapshot, filter)
                return LuaValue.valueOf(count.toInt())
            }
        })

        // network:insert(itemsHandle, count?) → number moved into network storage
        networkTable.set("insert", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val itemsTable = args.checktable(2)
                val maxCount = if (args.narg() >= 3 && !args.arg(3).isnil()) {
                    args.checklong(3)
                } else {
                    Long.MAX_VALUE
                }

                val ref = itemsTable.get("_itemsHandle")
                if (ref.isnil() || ref !is ItemsHandle.ItemsHandleRef) {
                    throw LuaError("Expected an ItemsHandle from :find() or network:craft()")
                }
                val itemsHandle = ref.handle

                val sourceStorage = itemsHandle.sourceStorage() ?: return LuaValue.valueOf(0)

                val moved = NetworkStorageHelper.insertItems(
                    level, snapshot, sourceStorage, itemsHandle.filter,
                    minOf(maxCount, itemsHandle.count.toLong())
                )
                return LuaValue.valueOf(moved.toInt())
            }
        })

        // network:craft(identifier, count?) → ItemsHandle or nil
        networkTable.set("craft", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val identifier = args.checkjstring(2)
                val count = if (args.narg() >= 3 && !args.arg(3).isnil()) args.checkint(3) else 1

                val result = CraftingHelper.craft(identifier, count, level, snapshot)
                    ?: return LuaValue.NIL

                // Return an ItemsHandle pointing to the crafted items in network storage
                // The items are already in storage, so find them there
                val storageCards = NetworkStorageHelper.getStorageCards(snapshot)
                val sourceStorage: () -> damien.nodeworks.platform.ItemStorageHandle? = {
                    // Find the first storage card that has the crafted item
                    storageCards.firstNotNullOfOrNull { card ->
                        val storage = NetworkStorageHelper.getStorage(level, card)
                        if (storage != null) {
                            val has = damien.nodeworks.platform.PlatformServices.storage.countItems(storage) {
                                CardHandle.matchesFilter(it, result.outputItemId)
                            }
                            if (has > 0) storage else null
                        } else null
                    }
                }

                return ItemsHandle.toLuaTable(ItemsHandle(
                    itemId = result.outputItemId,
                    itemName = result.outputName,
                    count = result.count,
                    filter = result.outputItemId,
                    sourceStorage = sourceStorage,
                    level = level
                ))
            }
        })

        g.set("network", networkTable)

        // clock() -> seconds since script started (as a decimal)
        val startTime = System.currentTimeMillis()
        g.set("clock", object : ZeroArgFunction() {
            override fun call(): LuaValue {
                val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
                return LuaValue.valueOf(elapsed)
            }
        })

        // print(message)
        g.set("print", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue {
                logCallback(arg.tojstring(), false)
                return LuaValue.NIL
            }
        })
    }
}
