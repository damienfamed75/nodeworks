package damien.nodeworks.client

import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.util.Util
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture

/**
 * Sorted registry id and tag lists for client autocomplete, shared by the Scripting
 * Terminal and every device filter field. Large packs carry tens of thousands of ids,
 * sorting them on the render thread whenever a screen opens is a visible stutter, so
 * the lists are built once in the background at login and on tag reload.
 */
object RegistryIdCache {
    private val logger = LoggerFactory.getLogger("nodeworks-registry-ids")

    /** A candidate list with its lowercase forms precomputed, so ranking a keystroke
     *  doesn't lowercase every candidate again. */
    class SearchPool(val values: List<String>) {
        val lower: List<String> = values.map { it.lowercase() }
    }

    class Snapshot internal constructor(
        val itemIds: List<String>,
        val fluidIds: List<String>,
        val blockIds: List<String>,
        /** Tag ids without the leading `#`. */
        val itemTags: List<String>,
        val fluidTags: List<String>,
        val componentTypeIds: List<String>,
    ) {
        // Candidate pools for device filter fields, in the order the field ranks them.
        val filterAll = SearchPool(
            listOf("*", "\$item:", "\$fluid:") + itemIds + itemTags.map { "#$it" } +
                fluidIds.map { "\$fluid:$it" } + fluidTags.map { "#$it" }
        )
        val filterTags = SearchPool(itemTags.map { "#$it" } + fluidTags.map { "#$it" })
        val filterItemSigil = SearchPool(itemIds.map { "\$item:$it" })
        val filterFluidSigil = SearchPool(fluidIds.map { "\$fluid:$it" })
    }

    /** Registry contents copied on the client thread. Copying references is cheap,
     *  the sorting and string work happens off-thread in [build]. */
    private class Raw(
        val items: List<Identifier>,
        val fluids: List<Identifier>,
        val blocks: List<Identifier>,
        val itemTags: List<Identifier>,
        val fluidTags: List<Identifier>,
        val components: List<Identifier>,
    ) {
        fun build(): Snapshot {
            fun sorted(ids: List<Identifier>) = ids.map { it.toString() }.sorted()
            return Snapshot(
                itemIds = sorted(items),
                // Source fluids only, tanks hold minecraft:water, not flowing_water.
                fluidIds = sorted(fluids).filter { !it.endsWith(":empty") && !it.contains(":flowing_") },
                blockIds = sorted(blocks),
                itemTags = sorted(itemTags),
                fluidTags = sorted(fluidTags),
                componentTypeIds = sorted(components),
            )
        }

        companion object {
            fun capture() = Raw(
                items = ArrayList(BuiltInRegistries.ITEM.keySet()),
                fluids = ArrayList(BuiltInRegistries.FLUID.keySet()),
                blocks = ArrayList(BuiltInRegistries.BLOCK.keySet()),
                itemTags = BuiltInRegistries.ITEM.getTags().map { it.key().location }.toList(),
                fluidTags = BuiltInRegistries.FLUID.getTags().map { it.key().location }.toList(),
                components = ArrayList(BuiltInRegistries.DATA_COMPONENT_TYPE.keySet()),
            )
        }
    }

    @Volatile private var ready: Snapshot? = null
    @Volatile private var pending: CompletableFuture<Snapshot>? = null

    /** Start a background rebuild. Call on the client thread, at login and after
     *  tags reload. Readers keep the previous snapshot until the new one lands. */
    fun preload() {
        val raw = Raw.capture()
        val future = CompletableFuture.supplyAsync({ raw.build() }, Util.backgroundExecutor())
        pending = future
        future.whenComplete { snapshot, error ->
            if (error != null) logger.warn("Failed to build registry id lists, building on demand", error)
            // A newer preload may have started meanwhile, only it gets to publish.
            if (pending === future) {
                if (snapshot != null) ready = snapshot
                pending = null
            }
        }
    }

    /** Current lists. Waits for an in-flight first build rather than duplicating it,
     *  and builds synchronously only if nothing was ever preloaded. */
    fun get(): Snapshot {
        ready?.let { return it }
        pending?.let { future ->
            try {
                return future.join().also { ready = it }
            } catch (e: Exception) {
                logger.warn("Registry id preload failed, building on demand", e)
            }
        }
        return Raw.capture().build().also { ready = it }
    }
}
