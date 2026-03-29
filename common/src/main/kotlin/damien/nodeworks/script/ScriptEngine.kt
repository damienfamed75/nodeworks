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

    /** Routing callback set by network:onInsert(). Called when items enter network storage. */
    var onInsertCallback: LuaFunction? = null
        private set

    /** Precomputed route table set by network:route(). */
    var routeTable: RouteTable? = null
        private set

    /** Cached inventory index across all network storage. */
    var inventoryCache: NetworkInventoryCache? = null
        private set

    /** Processing handlers registered by network:handle(). Keyed by card name. */
    val processingHandlers = mutableMapOf<String, LuaFunction>()


    fun start(scripts: Map<String, String>): Boolean {
        stop()

        val mainScript = scripts["main"]
        if (mainScript.isNullOrBlank()) {
            logCallback("Error: no 'main' script found.", true)
            return false
        }

        // Discover network
        networkSnapshot = NetworkDiscovery.discoverNetwork(level, networkEntryNode)
        inventoryCache = NetworkInventoryCache.getOrCreate(level, networkEntryNode)

        // Create sandboxed globals
        val g = Globals()
        g.load(JseBaseLib())
        g.load(PackageLib())
        g.load(Bit32Lib())
        g.load(TableLib())
        g.load(StringLib())
        g.load(CoroutineLib())
        g.load(JseMathLib())

        // Install the Lua compiler
        LuaC.install(g)

        // Remove dangerous globals
        g.set("dofile", LuaValue.NIL)
        g.set("loadfile", LuaValue.NIL)
        g.set("io", LuaValue.NIL)
        g.set("os", LuaValue.NIL)
        g.set("luajava", LuaValue.NIL)

        // Custom require() that resolves modules from the scripts map
        val loaded = LuaTable()
        g.set("require", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue {
                val modName = arg.checkjstring()

                // Return cached module if already loaded
                val cached = loaded.get(modName)
                if (!cached.isnil()) return cached

                val source = scripts[modName]
                    ?: throw LuaError("module '$modName' not found")

                // Mark as loading (prevents circular require)
                loaded.set(modName, LuaValue.TRUE)

                val chunk = g.load(stripTypeAnnotations(source), modName)
                val result = chunk.call()

                // If the module returned a value, cache that; otherwise cache true
                val moduleValue = if (result.isnil()) LuaValue.TRUE else result
                loaded.set(modName, moduleValue)
                return moduleValue
            }
        })

        // Initialize scheduler with the current server tick
        scheduler.initialize(PlatformServices.modState.tickCount)

        // Inject Nodeworks API
        injectApi(g)

        globals = g

        // Compile and run the main script (top-level code: variable setup, scheduler registrations)
        return try {
            val chunk = g.load(stripTypeAnnotations(mainScript), "main")
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
        onInsertCallback = null
        routeTable = null
        inventoryCache = null // clear local reference, cache lives in global registry
        processingHandlers.clear()
        scheduler.clear()
        globals = null
        networkSnapshot = null
    }

    fun isRunning(): Boolean = globals != null

    /** Whether this engine should stay alive — has scheduler tasks, handlers, or routing. */
    fun hasWork(): Boolean = scheduler.hasActiveTasks()
        || processingHandlers.isNotEmpty()
        || onInsertCallback != null
        || routeTable?.hasRoutes() == true


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

    /**
     * Creates a routing callback for NetworkStorageHelper.insertItems().
     * Invokes the Lua onInsert callback and extracts the target storage from the returned CardHandle.
     */
    private fun createRoutingCallback(snapshot: NetworkSnapshot): ((String, Long) -> damien.nodeworks.platform.ItemStorageHandle?)? {
        val callback = onInsertCallback ?: return null
        return { itemId, count ->
            try {
                val identifier = net.minecraft.resources.ResourceLocation.tryParse(itemId)
                val itemName = if (identifier != null) {
                    val item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(identifier)
                    net.minecraft.world.item.ItemStack(item).hoverName.string ?: itemId
                } else itemId

                // Create an ItemsHandle for the callback
                val itemsTable = ItemsHandle.toLuaTable(ItemsHandle.forCraftResult(
                    itemId = itemId,
                    itemName = itemName,
                    count = count.toInt(),
                    sourceStorage = { null }, // source doesn't matter for routing decision
                    level = level
                ))

                val result = callback.call(itemsTable)

                // If callback returned a CardHandle table, extract its storage
                if (!result.isnil() && result.istable()) {
                    val storageGetter = result.get("_getStorage")
                    if (storageGetter is CardHandle.StorageGetter) {
                        storageGetter.getStorage()
                    } else null
                } else null
            } catch (e: LuaError) {
                logCallback("onInsert error: ${e.message}", true)
                null
            }
        }
    }

    companion object {
        /**
         * Strips Luau-style type annotations from script text before Lua compilation.
         * Handles: function params `(x: Type)`, return types `): Type`, local vars `local x: Type =`
         */
        fun stripTypeAnnotations(source: String): String {
            var result = source

            // Function parameter types: (param:Type) or (param: Type) or (param :Type)
            // Also handles optional: (param: Type?)
            // Match `: TypeName?` after a word inside parentheses context
            result = result.replace(Regex("""\b(\w+)\s*:\s*([A-Z]\w*\??)""")) { match ->
                // Only strip if it looks like a type annotation (type starts with uppercase)
                match.groupValues[1]
            }

            // Return type annotations: ): TypeName or ): TypeName?
            result = result.replace(Regex("""\)\s*:\s*([A-Z]\w*\??)""")) { ")" }

            return result
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

        // network:getAll(type) → list of CardHandles matching that type
        networkTable.set("getAll", object : TwoArgFunction() {
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

        // network:find(filter) → ItemsHandle or nil (scans real storage, aggregated count)
        networkTable.set("find", object : TwoArgFunction() {
            override fun call(selfArg: LuaValue, filterArg: LuaValue): LuaValue {
                val filter = filterArg.checkjstring()
                // Get metadata from first match
                val (info, _) = NetworkStorageHelper.findFirstItemInfoAcrossNetwork(level, snapshot, filter)
                    ?: return LuaValue.NIL
                // Get total count across all storage
                val totalCount = NetworkStorageHelper.countItems(level, snapshot, filter)
                val aggregatedInfo = info.copy(count = totalCount)

                val sourceStorage: () -> damien.nodeworks.platform.ItemStorageHandle? = {
                    NetworkStorageHelper.getStorageCards(snapshot).firstNotNullOfOrNull { card ->
                        val storage = NetworkStorageHelper.getStorage(level, card)
                        if (storage != null) {
                            val has = damien.nodeworks.platform.PlatformServices.storage.countItems(storage) {
                                CardHandle.matchesFilter(it, filter)
                            }
                            if (has > 0) storage else null
                        } else null
                    }
                }

                return ItemsHandle.toLuaTable(ItemsHandle.fromItemInfo(aggregatedInfo, filter, sourceStorage, level))
            }
        })

        // network:findStack(filter) → ItemsHandle or nil (single stack from first storage card)
        networkTable.set("findStack", object : TwoArgFunction() {
            override fun call(selfArg: LuaValue, filterArg: LuaValue): LuaValue {
                val filter = filterArg.checkjstring()
                val (info, _) = NetworkStorageHelper.findFirstItemInfoAcrossNetwork(level, snapshot, filter)
                    ?: return LuaValue.NIL

                val sourceStorage: () -> damien.nodeworks.platform.ItemStorageHandle? = {
                    NetworkStorageHelper.getStorageCards(snapshot).firstNotNullOfOrNull { card ->
                        val storage = NetworkStorageHelper.getStorage(level, card)
                        if (storage != null) {
                            val has = damien.nodeworks.platform.PlatformServices.storage.countItems(storage) {
                                CardHandle.matchesFilter(it, filter)
                            }
                            if (has > 0) storage else null
                        } else null
                    }
                }

                return ItemsHandle.toLuaTable(ItemsHandle.fromItemInfo(info, filter, sourceStorage, level))
            }
        })

        // network:findAll(filter) → table of ItemsHandles (scans real storage)
        networkTable.set("findAll", object : TwoArgFunction() {
            override fun call(selfArg: LuaValue, filterArg: LuaValue): LuaValue {
                val filter = filterArg.checkjstring()
                val allItems = NetworkStorageHelper.findAllItemInfoAcrossNetwork(level, snapshot, filter)
                val result = LuaTable()
                for ((i, pair) in allItems.withIndex()) {
                    val (info, _) = pair
                    val sourceStorage: () -> damien.nodeworks.platform.ItemStorageHandle? = {
                        NetworkStorageHelper.getStorageCards(snapshot).firstNotNullOfOrNull { card ->
                            val storage = NetworkStorageHelper.getStorage(level, card)
                            if (storage != null) {
                                val has = damien.nodeworks.platform.PlatformServices.storage.countItems(storage) {
                                    it == info.itemId
                                }
                                if (has > 0) storage else null
                            } else null
                        }
                    }
                    val handle = ItemsHandle.fromItemInfo(info, info.itemId, sourceStorage, level)
                    result.set(i + 1, ItemsHandle.toLuaTable(handle))
                }
                return result
            }
        })

        // network:count(filter) → number (scans real storage)
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
                    minOf(maxCount, itemsHandle.count.toLong()),
                    routeTable,
                    createRoutingCallback(snapshot),
                    inventoryCache
                )
                return LuaValue.valueOf(moved.toInt())
            }
        })

        // network:craft(identifier, count?) → ItemsHandle or nil
        networkTable.set("craft", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val identifier = args.checkjstring(2)
                val count = if (args.narg() >= 3 && !args.arg(3).isnil()) args.checkint(3) else 1

                val result = CraftingHelper.craft(identifier, count, level, snapshot, cache = inventoryCache, processingHandlers = processingHandlers.takeIf { it.isNotEmpty() })
                if (result == null) {
                    CraftingHelper.lastFailReason?.let { logCallback(it, true) }
                    return LuaValue.NIL
                }

                // Return an ItemsHandle pointing to the crafted items in network storage
                val storageCards = NetworkStorageHelper.getStorageCards(snapshot)
                val sourceStorage: () -> damien.nodeworks.platform.ItemStorageHandle? = {
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

                return ItemsHandle.toLuaTable(ItemsHandle.forCraftResult(
                    itemId = result.outputItemId,
                    itemName = result.outputName,
                    count = result.count,
                    sourceStorage = sourceStorage,
                    level = level
                ))
            }
        })

        // network:route(alias, predicate) — register a declarative storage route
        // Items where predicate(itemsHandle) returns true go to that storage
        routeTable = RouteTable(level, snapshot)
        networkTable.set("route", object : ThreeArgFunction() {
            override fun call(selfArg: LuaValue, aliasArg: LuaValue, predicateArg: LuaValue): LuaValue {
                val alias = aliasArg.checkjstring()
                val predicate = predicateArg.checkfunction()
                routeTable?.addRoute(alias, predicate)
                return LuaValue.NIL
            }
        })

        // network:onInsert(fn) — register a routing callback for items entering network storage
        networkTable.set("onInsert", object : TwoArgFunction() {
            override fun call(selfArg: LuaValue, fnArg: LuaValue): LuaValue {
                if (!fnArg.isfunction()) throw LuaError("onInsert expects a function")
                onInsertCallback = fnArg.checkfunction()
                return LuaValue.NIL
            }
        })

        // network:shapeless(item1, count1, item2?, count2?, ...) → ItemsHandle or nil
        // Crafts using vanilla shapeless recipes. Inputs are item/count pairs.
        networkTable.set("shapeless", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                // Parse item/count pairs from varargs (self is arg1)
                val ingredients = mutableMapOf<String, Int>()
                var i = 2
                while (i <= args.narg()) {
                    val itemId = args.checkjstring(i)
                    val count = if (i + 1 <= args.narg() && !args.arg(i + 1).isnil() && args.arg(i + 1).isnumber()) {
                        i++
                        args.checkint(i)
                    } else {
                        1
                    }
                    ingredients[itemId] = (ingredients[itemId] ?: 0) + count
                    i++
                }

                if (ingredients.isEmpty()) throw LuaError("shapeless requires at least one item")

                val result = ShapelessCraftHelper.craft(ingredients, level, snapshot, cache = inventoryCache)
                    ?: return LuaValue.NIL

                val storageCards2 = NetworkStorageHelper.getStorageCards(snapshot)
                val sourceStorage2: () -> damien.nodeworks.platform.ItemStorageHandle? = {
                    storageCards2.firstNotNullOfOrNull { card ->
                        val storage = NetworkStorageHelper.getStorage(level, card)
                        if (storage != null) {
                            val has = damien.nodeworks.platform.PlatformServices.storage.countItems(storage) {
                                CardHandle.matchesFilter(it, result.outputItemId)
                            }
                            if (has > 0) storage else null
                        } else null
                    }
                }

                return ItemsHandle.toLuaTable(ItemsHandle.forCraftResult(
                    itemId = result.outputItemId,
                    itemName = result.outputName,
                    count = result.count,
                    sourceStorage = sourceStorage2,
                    level = level
                ))
            }
        })

        // network:handle(cardName, handlerFn) — register a processing handler
        // cardName matches the name set on a Processing API Card in API Storage.
        // The handler function receives input items as arguments and should return
        // the result ItemsHandle from the processing machine's output.
        networkTable.set("handle", object : ThreeArgFunction() {
            override fun call(selfArg: LuaValue, nameArg: LuaValue, handlerArg: LuaValue): LuaValue {
                val name = nameArg.checkjstring()
                val handler = handlerArg.checkfunction()
                processingHandlers[name] = handler
                return LuaValue.NIL
            }
        })

        networkTable.set("var", object : TwoArgFunction() {
            override fun call(self: LuaValue, nameArg: LuaValue): LuaValue {
                val name = nameArg.checkjstring()
                val varSnapshot = snapshot.findVariable(name)
                    ?: throw LuaError("Variable not found on network: '$name'")
                return VariableHandle.create(varSnapshot, level)
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
