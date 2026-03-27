package damien.nodeworks.screen

import damien.nodeworks.network.InventorySyncPayload
import damien.nodeworks.platform.ItemInfo

/**
 * Client-side mirror of the network inventory. Receives delta updates from the server
 * and maintains a sorted/filtered view for the terminal screen.
 */
class InventoryRepo {

    data class RepoEntry(
        val serial: Long,
        val info: ItemInfo
    )

    /** All entries keyed by serial. */
    private val entries = LinkedHashMap<Long, RepoEntry>()

    /** Filtered and sorted view for display. */
    var view: List<RepoEntry> = emptyList()
        private set

    /** Current search filter text. */
    var searchText: String = ""
        set(value) {
            if (field != value) {
                field = value
                updateView()
            }
        }

    /** Sort mode. */
    enum class SortMode { NAME, COUNT, MOD }
    var sortMode: SortMode = SortMode.NAME
        set(value) {
            if (field != value) {
                field = value
                updateView()
            }
        }

    var sortAscending: Boolean = true
        set(value) {
            if (field != value) {
                field = value
                updateView()
            }
        }

    /** Handle a sync packet from the server. */
    fun handleUpdate(payload: InventorySyncPayload) {
        if (payload.fullUpdate) {
            entries.clear()
        }

        // Remove deleted entries
        for (serial in payload.removedSerials) {
            entries.remove(serial)
        }

        // Add or update entries
        for (syncEntry in payload.entries) {
            val existing = entries[syncEntry.serial]
            if (syncEntry.itemId != null) {
                // Full entry (new or replacement)
                entries[syncEntry.serial] = RepoEntry(
                    serial = syncEntry.serial,
                    info = ItemInfo(
                        itemId = syncEntry.itemId,
                        name = syncEntry.name ?: syncEntry.itemId,
                        count = syncEntry.count,
                        maxStackSize = syncEntry.maxStackSize,
                        hasData = syncEntry.hasData
                    )
                )
            } else if (existing != null) {
                // Amount-only update
                entries[syncEntry.serial] = existing.copy(
                    info = existing.info.copy(count = syncEntry.count)
                )
            }
        }

        updateView()
    }

    /** Rebuild the filtered/sorted view. */
    fun updateView() {
        val search = searchText.lowercase().trim()

        view = entries.values
            .filter { entry ->
                if (search.isEmpty()) true
                else {
                    val info = entry.info
                    info.name.lowercase().contains(search) ||
                    info.itemId.lowercase().contains(search)
                }
            }
            .let { list ->
                val comparator = when (sortMode) {
                    SortMode.NAME -> compareBy<RepoEntry> { it.info.name }
                    SortMode.COUNT -> compareBy { it.info.count }
                    SortMode.MOD -> compareBy { it.info.itemId.substringBefore(":") }
                }
                if (sortAscending) list.sortedWith(comparator)
                else list.sortedWith(comparator.reversed())
            }
    }

    /** Get entry at a specific index in the view (for virtual slot rendering). */
    fun getViewEntry(index: Int): RepoEntry? {
        return if (index in view.indices) view[index] else null
    }

    /** Total items in the view (for scrollbar). */
    val viewSize: Int get() = view.size
}
