package damien.nodeworks.config

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File

/**
 * Client-side preferences that persist across sessions.
 * Saved to config/nodeworks_client.json.
 */
object ClientConfig {

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private var configFile: File? = null
    private var data = ConfigData()

    data class ConfigData(
        var invTerminalLayout: String = "SMALL",
        var invTerminalCraftingCollapsed: Boolean = false,
        var invTerminalAutoPull: Boolean = true,
        var invTerminalAutoFocusSearch: Boolean = true,
        var invTerminalSortMode: String = "ALPHA",
        var invTerminalFilterMode: String = "BOTH",
        var invTerminalKindMode: String = "BOTH",
        var invTerminalJeiSync: Boolean = false,
        // Inventory Terminal channel filter per network id, ChannelFilter NBT encoding.
        var invTerminalChannels: MutableMap<String, Int>? = HashMap(),
        var scriptTerminalLayout: Int = 0
    )

    fun init(configDir: File) {
        configFile = File(configDir, "nodeworks_client.json")
        load()
    }

    private fun load() {
        val file = configFile ?: return
        if (file.exists()) {
            try {
                data = gson.fromJson(file.readText(), ConfigData::class.java) ?: ConfigData()
            } catch (_: Exception) {
                data = ConfigData()
            }
        }
    }

    fun save() {
        val file = configFile ?: return
        try {
            file.parentFile?.mkdirs()
            file.writeText(gson.toJson(data))
        } catch (_: Exception) {}
    }

    // --- Inventory Terminal ---

    var invTerminalLayout: String
        get() = data.invTerminalLayout
        set(value) { data.invTerminalLayout = value; save() }

    var invTerminalCraftingCollapsed: Boolean
        get() = data.invTerminalCraftingCollapsed
        set(value) { data.invTerminalCraftingCollapsed = value; save() }

    var invTerminalAutoPull: Boolean
        get() = data.invTerminalAutoPull
        set(value) { data.invTerminalAutoPull = value; save() }

    var invTerminalAutoFocusSearch: Boolean
        get() = data.invTerminalAutoFocusSearch
        set(value) { data.invTerminalAutoFocusSearch = value; save() }

    var invTerminalSortMode: String
        get() = data.invTerminalSortMode
        set(value) { data.invTerminalSortMode = value; save() }

    var invTerminalFilterMode: String
        get() = data.invTerminalFilterMode
        set(value) { data.invTerminalFilterMode = value; save() }

    var invTerminalKindMode: String
        get() = data.invTerminalKindMode
        set(value) { data.invTerminalKindMode = value; save() }

    var invTerminalJeiSync: Boolean
        get() = data.invTerminalJeiSync
        set(value) { data.invTerminalJeiSync = value; save() }

    /** Channel last shown on [networkId], -1 (all channels) when never set. */
    fun invTerminalChannel(networkId: String): Int = data.invTerminalChannels?.get(networkId) ?: -1

    fun invTerminalChannels(): Map<String, Int> = HashMap(data.invTerminalChannels ?: emptyMap())

    fun setInvTerminalChannel(networkId: String, channel: Int) {
        val map = data.invTerminalChannels ?: HashMap<String, Int>().also { data.invTerminalChannels = it }
        if (channel < 0) map.remove(networkId) else map[networkId] = channel
        save()
    }

    // --- Scripting Terminal ---

    var scriptTerminalLayout: Int
        get() = data.scriptTerminalLayout
        set(value) { data.scriptTerminalLayout = value; save() }
}
