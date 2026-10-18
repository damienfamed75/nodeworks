package damien.nodeworks.screen

import damien.nodeworks.network.InventorySyncPayload
import damien.nodeworks.platform.ItemInfo

/**
 * Client-side mirror of the network inventory. Receives delta updates from the server
 * and maintains a sorted/filtered view for the terminal screen.
 *
 * Supports JEI-style search syntax:
 * - Plain text: search by item name
 * - @mod: filter by mod/namespace (e.g. @minecraft)
 * - #tag: filter by tag, matching any tag id that contains the text, so `#gems` finds
 *   everything in `c:gems` and `c:gems/diamond` and `#c:ingots` narrows to that tag
 */
class InventoryRepo {

    data class RepoEntry(
        val serial: Long,
        val info: ItemInfo,
        /** 0 = item, 1 = fluid, controls grid rendering and click-to-fill behavior. */
        val kind: Byte = 0
    ) {
        val isFluid: Boolean get() = kind == 1.toByte()
    }

    enum class SortMode { ALPHA, COUNT_DESC, COUNT_ASC }
    enum class FilterMode { STORAGE, RECIPES, BOTH }
    enum class KindMode { BOTH, ITEMS_ONLY, FLUIDS_ONLY }

    /** All entries keyed by serial. */
    private val entries = LinkedHashMap<Long, RepoEntry>()

    /** Filtered and sorted view for display. */
    var view: List<RepoEntry> = emptyList()
        private set

    private var viewDirty = true

    /** Current search filter text. */
    var searchText: String = ""
        set(value) {
            if (field != value) {
                field = value
                viewDirty = true
            }
        }

    var sortMode: SortMode = SortMode.ALPHA
        set(value) {
            if (field != value) {
                field = value
                viewDirty = true
            }
        }

    var filterMode: FilterMode = FilterMode.BOTH
        set(value) {
            if (field != value) {
                field = value
                viewDirty = true
            }
        }

    var kindMode: KindMode = KindMode.BOTH
        set(value) {
            if (field != value) {
                field = value
                viewDirty = true
            }
        }

    /** Whether a chunked full sync is in progress. */
    var isLoadingFullSync = false
        private set

    /**
     * Handle a sync packet from the server.
     * Full syncs may arrive in multiple chunks, entries are buffered and
     * the view is only rebuilt after the final chunk arrives.
     */
    fun handleUpdate(payload: InventorySyncPayload) {
        if (payload.fullUpdate) {
            // First chunk of a full sync: clear existing entries
            if (payload.chunkIndex == 0) {
                entries.clear()
                isLoadingFullSync = payload.totalChunks > 1
            }

            // Apply this chunk's entries
            applyEntries(payload.entries)

            // Last chunk: mark loading complete and rebuild view
            if (payload.chunkIndex >= payload.totalChunks - 1) {
                isLoadingFullSync = false
                viewDirty = true
            }
        } else {
            // Delta update, apply immediately
            for (serial in payload.removedSerials) {
                entries.remove(serial)
            }
            applyEntries(payload.entries)
            viewDirty = true
        }
    }

    private fun applyEntries(syncEntries: List<InventorySyncPayload.SyncEntry>) {
        for (syncEntry in syncEntries) {
            val existing = entries[syncEntry.serial]
            if (syncEntry.itemId != null) {
                entries[syncEntry.serial] = RepoEntry(
                    serial = syncEntry.serial,
                    info = ItemInfo(
                        itemId = syncEntry.itemId,
                        name = syncEntry.name ?: syncEntry.itemId,
                        count = syncEntry.count,
                        maxStackSize = syncEntry.maxStackSize,
                        hasData = syncEntry.hasData,
                        isCraftable = syncEntry.craftable,
                        componentsPatch = syncEntry.componentsPatch,
                    ),
                    kind = syncEntry.kind
                )
            } else if (existing != null) {
                entries[syncEntry.serial] = existing.copy(
                    info = existing.info.copy(count = syncEntry.count)
                )
            }
        }
    }

    /** Sum network stock for the given item id across every non-fluid
     *  entry. Used by the JEI handler's missing-ingredient probe so the
     *  caller doesn't have to walk the entry map. */
    fun countItem(itemId: String): Long {
        var total = 0L
        for (entry in entries.values) {
            if (entry.isFluid) continue
            if (entry.info.itemId == itemId) total += entry.info.count
        }
        return total
    }

    /** Rebuild the filtered/sorted view if dirty. Call once per frame before reading view. */
    fun ensureUpdated() {
        if (viewDirty) {
            viewDirty = false
            rebuildView()
        }
    }

    private fun rebuildView() {
        val search = searchText.trim()

        view = entries.values
            .asSequence()
            .filter { entry -> matchesKindMode(entry) }
            .filter { entry -> matchesSearch(entry, search) }
            .filter { entry -> matchesFilter(entry.info) }
            .sortedWith(getComparator())
            .toList()
    }

    private fun matchesKindMode(entry: RepoEntry): Boolean = when (kindMode) {
        KindMode.BOTH -> true
        KindMode.ITEMS_ONLY -> !entry.isFluid
        KindMode.FLUIDS_ONLY -> entry.isFluid
    }

    /** Lowercased ids of every tag a resource is in, looked up once per id. Fluids are
     *  kept apart from items, the two registries can share an id. */
    private val tagCache = HashMap<String, List<String>>()

    private fun tagsOf(id: String, fluid: Boolean): List<String> = tagCache.getOrPut(if (fluid) "fluid/$id" else id) {
        val ident = net.minecraft.resources.Identifier.tryParse(id) ?: return@getOrPut emptyList()
        val holder = if (fluid) net.minecraft.core.registries.BuiltInRegistries.FLUID.getValue(ident)?.builtInRegistryHolder()
            else net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(ident)?.builtInRegistryHolder()
        holder?.tags()?.map { it.location().toString().lowercase() }?.toList() ?: emptyList()
    }

    private fun matchesSearch(entry: RepoEntry, search: String): Boolean {
        val info = entry.info
        if (search.isEmpty()) return true

        // Parse search tokens (space-separated, each can be @mod, #tag, or plain text)
        val tokens = search.split(" ").filter { it.isNotEmpty() }
        return tokens.all { token ->
            when {
                token.startsWith("@") -> {
                    // Mod filter: @minecraft matches "minecraft:stone"
                    val mod = token.substring(1).lowercase()
                    info.itemId.lowercase().substringBefore(":").contains(mod)
                }
                token.startsWith("#") -> {
                    // Tag filter, JEI style: any of the resource's tags containing the text.
                    val tag = token.substring(1).lowercase()
                    tag.isEmpty() || tagsOf(info.itemId, entry.isFluid).any { it.contains(tag) }
                }
                else -> {
                    // Plain text: match name or itemId
                    val lower = token.lowercase()
                    info.name.lowercase().contains(lower) || info.itemId.lowercase().contains(lower)
                }
            }
        }
    }

    private fun matchesFilter(info: ItemInfo): Boolean {
        return when (filterMode) {
            FilterMode.STORAGE -> info.count > 0
            FilterMode.RECIPES -> info.isCraftable
            FilterMode.BOTH -> info.count > 0 || info.isCraftable
        }
    }

    private fun getComparator(): Comparator<RepoEntry> {
        return when (sortMode) {
            SortMode.ALPHA -> compareBy { it.info.name.lowercase() }
            SortMode.COUNT_DESC -> compareByDescending { it.info.count }
            SortMode.COUNT_ASC -> compareBy { it.info.count }
        }
    }

    /** Get entry at a specific index in the view. */
    fun getViewEntry(index: Int): RepoEntry? {
        return if (index in view.indices) view[index] else null
    }

    /** Total items in the view (for scrollbar). */
    val viewSize: Int get() = view.size
}
