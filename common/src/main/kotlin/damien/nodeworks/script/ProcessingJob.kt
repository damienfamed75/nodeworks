package damien.nodeworks.script

import damien.nodeworks.block.entity.ProcessingStorageBlockEntity
import damien.nodeworks.block.entity.CraftingCoreBlockEntity
import damien.nodeworks.platform.PlatformServices
import net.minecraft.server.level.ServerLevel
import org.luaj.vm2.*
import org.luaj.vm2.lib.*

/**
 * Represents a processing job context passed as the first argument to handler functions.
 *
 * job:pull(card, ...) checks if outputs are available. If ready, extracts atomically
 * and signals completion. If not, registers a pending poll on the scheduler.
 * Handler runs synchronously on the server thread — no coroutines.
 */
class ProcessingJob(
    private val api: ProcessingStorageBlockEntity.ProcessingApiInfo,
    private val cpu: CraftingCoreBlockEntity,
    private val level: ServerLevel,
    private val scheduler: SchedulerImpl,
    private val pendingResult: CraftingHelper.PendingHandlerJob
) {
    private val remaining = api.outputs.map { it.first to it.second }.toMutableList()
    private val startGeneration = cpu.jobGeneration

    fun toLuaTable(): LuaTable {
        val table = LuaTable()

        val outputsTable = LuaTable()
        for ((i, pair) in api.outputs.withIndex()) {
            val entry = LuaTable()
            entry.set("id", pair.first)
            entry.set("count", pair.second)
            outputsTable.set(i + 1, entry)
        }
        table.set("outputs", outputsTable)

        table.set("pull", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val getters = mutableListOf<CardHandle.StorageGetter>()
                for (i in 2..args.narg()) {
                    val arg = args.arg(i)
                    if (!arg.istable()) throw LuaError("job:pull() expects CardHandle arguments")
                    val ref = arg.get("_getStorage")
                    if (ref is CardHandle.StorageGetter) {
                        getters.add(ref)
                    } else {
                        throw LuaError("job:pull() expects CardHandle arguments from network:get()")
                    }
                }
                if (getters.isEmpty()) throw LuaError("job:pull() requires at least one card argument")

                // Try immediate extraction
                if (tryExtract(getters)) {
                    pendingResult.complete(true)
                    return LuaValue.TRUE
                }

                // Not ready — register for polling each tick
                val timeoutTicks = if (api.timeout > 0) api.timeout.toLong() else 6000L
                scheduler.addPendingJob(SchedulerImpl.PendingJob(
                    pollFn = { tryExtract(getters) },
                    timeoutAt = scheduler.currentTick + timeoutTicks,
                    onComplete = { success ->
                        if (!success) {
                            // Timeout or error — release CPU with whatever is in the buffer
                            releaseCpu()
                        }
                        pendingResult.complete(success)
                    },
                    label = "pull:${api.name}"
                ))

                return LuaValue.NIL
            }
        })

        return table
    }

    /** Flush CPU buffer to network storage and mark idle. */
    private fun releaseCpu() {
        val snapshot = damien.nodeworks.network.NetworkDiscovery.discoverNetwork(level, cpu.blockPos)
        val leftovers = cpu.clearBuffer()
        for ((bufItemId, bufCount) in leftovers) {
            val bufId = net.minecraft.resources.ResourceLocation.tryParse(bufItemId) ?: continue
            val bufItem = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(bufId) ?: continue
            var rem = bufCount
            while (rem > 0) {
                val batch = minOf(rem, bufItem.getDefaultMaxStackSize())
                val stack = net.minecraft.world.item.ItemStack(bufItem, batch)
                val inserted = NetworkStorageHelper.insertItemStack(level, snapshot, stack, null)
                rem -= if (inserted > 0) inserted else batch
            }
        }
        cpu.setCrafting(false)
    }

    private val isStale: Boolean get() = cpu.jobGeneration != startGeneration

    private fun tryExtract(getters: List<CardHandle.StorageGetter>): Boolean {
        for ((outputId, needed) in remaining) {
            var available = 0L
            for (getter in getters) {
                val storage = getter.getStorage() ?: continue
                available += PlatformServices.storage.countItems(storage) {
                    CardHandle.matchesFilter(it, outputId)
                }
                if (available >= needed) break
            }
            if (available < needed) return false
        }

        // Items are ready — extract and route to network storage directly
        val snapshot = damien.nodeworks.network.NetworkDiscovery.discoverNetwork(level, cpu.blockPos)

        for ((outputId, needed) in remaining.toList()) {
            var stillNeeded = needed.toLong()
            for (getter in getters) {
                if (stillNeeded <= 0) break
                val storage = getter.getStorage() ?: continue
                val extracted = PlatformServices.storage.extractItems(
                    storage, { CardHandle.matchesFilter(it, outputId) }, stillNeeded
                )
                if (extracted > 0) {
                    // Route extracted items directly to network storage
                    val id = net.minecraft.resources.ResourceLocation.tryParse(outputId)
                    val item = id?.let { net.minecraft.core.registries.BuiltInRegistries.ITEM.get(it) }
                    if (item != null) {
                        NetworkStorageHelper.insertItemStack(level, snapshot, net.minecraft.world.item.ItemStack(item, extracted.toInt()), null)
                    }
                    stillNeeded -= extracted
                }
            }
        }
        remaining.clear()

        // Flush any remaining CPU buffer contents to network and release the CPU
        val leftovers = cpu.clearBuffer()
        for ((bufItemId, bufCount) in leftovers) {
            val bufId = net.minecraft.resources.ResourceLocation.tryParse(bufItemId) ?: continue
            val bufItem = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(bufId) ?: continue
            var rem = bufCount
            while (rem > 0) {
                val batch = minOf(rem, bufItem.getDefaultMaxStackSize())
                val stack = net.minecraft.world.item.ItemStack(bufItem, batch)
                val inserted = NetworkStorageHelper.insertItemStack(level, snapshot, stack, null)
                rem -= if (inserted > 0) inserted else batch
            }
        }
        cpu.setCrafting(false)

        return true
    }
}
