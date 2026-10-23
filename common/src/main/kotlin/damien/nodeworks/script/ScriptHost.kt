package damien.nodeworks.script

/**
 * What a running script's owner has to offer the engine: a place to record a fault that
 * must not re-fire, and whether the owner would start the script again by itself. A
 * Script Runner implements this once per slot.
 */
interface ScriptHost {
    /** True when the host restarts this script on its own, on world load or a redstone pulse. */
    val autoRestarts: Boolean

    /** Record a fault the host must not restart into. The host stops restarting the script. */
    fun markTimedOut(reason: String)

    /** A clean start wipes the recorded fault. */
    fun clearLastError()
}
