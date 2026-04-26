package damien.nodeworks.script

import damien.nodeworks.block.entity.BreakerBlockEntity
import damien.nodeworks.network.BreakerSnapshot
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.ItemStack
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaFunction
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.lib.OneArgFunction
import org.luaj.vm2.lib.TwoArgFunction

/**
 * Lua handle for a Breaker device, initiates a multi-tick break of the block at
 * the device's facing position. `:mine()` returns a [BreakBuilder]-shaped Lua
 * table that the script can chain `:connect(fn)` onto for redirecting drops, if
 * no chain follows, drops route to network storage automatically.
 *
 * Per-method `getEntity()` closure mirrors [VariableHandle]'s pattern so the
 * handle gracefully errors if the breaker block has been broken between
 * `network:get(...)` and a method call.
 */
object BreakerHandle {

    fun create(
        snapshot: BreakerSnapshot,
        networkSnapshot: damien.nodeworks.network.NetworkSnapshot,
        level: ServerLevel,
    ): LuaTable {
        val pos = snapshot.pos
        val alias = snapshot.effectiveAlias
        val table = LuaTable()

        fun getEntity(): BreakerBlockEntity =
            level.getBlockEntity(pos) as? BreakerBlockEntity
                ?: throw LuaError("Breaker '$alias' has been removed")

        table.set("name", LuaValue.valueOf(alias))

        // :mine() → BreakBuilder
        // Named "mine" (not "break") because `break` is a reserved Lua keyword,
        // `:break()` would be a syntax error in scripts. "destroy" was rejected
        // because it suggests the resulting drops are deleted, `:mine()` mirrors
        // what a player does with a pickaxe and yields drops by default. Starts
        // the multi-tick break sequence and returns a builder Lua table the
        // script can chain `:connect(fn)` onto. When the breaker is busy or the
        // target is invalid (air, above-tier, unbreakable), returns a no-op
        // builder so chained calls don't crash and the broadcast
        // `HandleList:mine()` shape remains uniform.
        table.set("mine", object : OneArgFunction() {
            override fun call(self: LuaValue): LuaValue {
                val entity = getEntity()
                val started = entity.startBreak(level)
                return BreakBuilder.create(if (started) entity else null)
            }
        })

        // :cancel(), abort the in-progress break, if any. Safe to call when idle.
        table.set("cancel", object : OneArgFunction() {
            override fun call(self: LuaValue): LuaValue {
                getEntity().cancel()
                return LuaValue.NONE
            }
        })

        // :block() / :state(), mirror Observer Card. The Breaker is already querying
        // BlockState internally for the duration formula so exposing these reads is
        // free, saves the user from placing a separate Observer Card next to every
        // breaker for stage-gated farms.
        table.set("block", object : OneArgFunction() {
            override fun call(self: LuaValue): LuaValue {
                val entity = getEntity()
                val state = level.getBlockState(entity.targetPos)
                return LuaValue.valueOf(BuiltInRegistries.BLOCK.getKey(state.block).toString())
            }
        })
        table.set("state", object : OneArgFunction() {
            override fun call(self: LuaValue): LuaValue {
                val entity = getEntity()
                val state = level.getBlockState(entity.targetPos)
                return blockStateToLua(state)
            }
        })
        table.set("isMining", object : OneArgFunction() {
            override fun call(self: LuaValue): LuaValue =
                LuaValue.valueOf(getEntity().isBreaking)
        })
        table.set("progress", object : OneArgFunction() {
            override fun call(self: LuaValue): LuaValue =
                LuaValue.valueOf(getEntity().progressFraction.toDouble())
        })

        return table
    }

    /** Convert a BlockState's properties into a Lua table. Mirrors the helper
     *  ScriptEngine uses for ObserverCard.state() so users see the same shape on
     *  Observer / Breaker reads. */
    private fun blockStateToLua(state: net.minecraft.world.level.block.state.BlockState): LuaTable {
        val t = LuaTable()
        for (prop in state.properties) {
            @Suppress("UNCHECKED_CAST")
            val typed = prop as net.minecraft.world.level.block.state.properties.Property<Comparable<Any>>
            val value = state.getValue(typed)
            val lua: LuaValue = when (value) {
                is Boolean -> LuaValue.valueOf(value)
                is Number -> LuaValue.valueOf(value.toInt())
                else -> LuaValue.valueOf(value.toString().lowercase())
            }
            t.set(prop.name, lua)
        }
        return t
    }

    // ---- Drop routing, called from BreakerBlockEntity.completeBreak ----

    /** Default routing: insert each dropped stack into network storage via the
     *  pool. Items that don't fit (storage full / no storage card matching) spawn
     *  as item entities at [breaker]'s target position so nothing is lost. */
    @JvmStatic
    fun routeDropsToNetwork(level: ServerLevel, breaker: BreakerBlockEntity, drops: List<ItemStack>) {
        if (drops.isEmpty()) return
        val target = breaker.targetPos
        // Discover the breaker's network at completion time, the BlockEntity is
        // independent of the script engine but a fresh BFS from the breaker's
        // position gives us the same storage-card list `network:onInsert` would.
        val snapshot = damien.nodeworks.network.NetworkDiscovery
            .discoverNetwork(level, breaker.blockPos)
        for (stack in drops) {
            if (stack.isEmpty) continue
            val inserted = damien.nodeworks.script.NetworkStorageHelper
                .insertItemStack(level, snapshot, stack)
            if (inserted >= stack.count) continue
            // Anything that didn't fit spills as a world entity at the target
            // position so the user can still vacuum it back via Importer / IO
            // Card. This is the same fallback the prior "always pop" code used,
            // just gated behind real storage exhaustion.
            val leftover = stack.copyWithCount(stack.count - inserted)
            net.minecraft.world.level.block.Block.popResource(level, target, leftover)
        }
    }

    /** Handler routing: pass each drop to the user's `:connect(fn)` callback as an
     *  ItemsHandle. The handler can ignore the items (they spill back to network
     *  storage) or claim them by inserting / using them. */
    @JvmStatic
    fun dispatchDropsToHandler(
        level: ServerLevel,
        breaker: BreakerBlockEntity,
        drops: List<ItemStack>,
        handler: LuaFunction,
    ) {
        if (drops.isEmpty()) return
        for (stack in drops) {
            if (stack.isEmpty) continue
            // Build a minimal ItemsHandle-shaped Lua table for the handler. The
            // BufferSource isn't wired here (we don't have the CPU context the
            // crafting handlers do), so :insert / :extract on this items handle
            // would no-op, that's OK since the handler is meant to read the
            // drop's id / count and decide what to do.
            val itemTable = buildSimpleItemsHandle(stack)
            try {
                handler.call(itemTable)
            } catch (e: LuaError) {
                // Silent, script-level errors in a connect handler shouldn't take
                // down the breaker. The BreakerBlockEntity logs a generic error if
                // we want to surface this to the terminal later.
            } catch (_: Exception) {
                // Same.
            }
        }
    }

    /** Build a minimal Lua table with the .id / .name / .count / .kind fields the
     *  user's connect handler will inspect. This is a strict subset of the full
     *  [ItemsHandle.toLuaTable] surface, the breaker drop path doesn't have the
     *  source-storage / buffer-source plumbing that crafting flows do. */
    private fun buildSimpleItemsHandle(stack: ItemStack): LuaTable {
        val t = LuaTable()
        val id = BuiltInRegistries.ITEM.getKey(stack.item).toString()
        t.set("id", LuaValue.valueOf(id))
        t.set("name", LuaValue.valueOf(stack.hoverName.string))
        t.set("count", LuaValue.valueOf(stack.count))
        t.set("kind", LuaValue.valueOf("item"))
        return t
    }
}
