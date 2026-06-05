package damien.nodeworks.api

import net.minecraft.core.BlockPos

/** Snapshot of one named device captured during a [damien.nodeworks.network.NetworkDiscovery]
 *  BFS. Extension mods implementing [DeviceType] return a subtype of this for each of their
 *  device BEs the walk visits. The snapshot is what scripts later look up by name through
 *  `network:get(name)`, so it should carry enough state to back the Lua handle without
 *  needing to re-walk the network or hit the BE on every script call.
 *
 *  Implementers add their own fields freely (channel, type tag, mod-specific metadata).
 *  Snapshots are short-lived (one tick by default, cached by the discovery layer) and
 *  field values are immutable in practice — [autoAlias] is the one exception, the
 *  discovery walk writes it once after the BFS finishes.
 *
 *  Naming model, mirroring Breakers / Placers / Users:
 *
 *    * [name] is what the player typed (anvil rename, device GUI). Null when unnamed.
 *    * [autoAlias] is the disambiguating `<base>_N` slug assigned by
 *      [damien.nodeworks.network.assignAliasSuffixes] when two or more devices share a
 *      base namespace.
 *    * [effectiveAlias] is the script-facing identifier — auto-alias when assigned,
 *      otherwise the literal [name]. `network:get(effectiveAlias)` always finds the
 *      device. `network:get(name)` finds it when the name is unique on the network.
 *
 *  The discovery layer writes [autoAlias] after the walk completes. Concrete impls must
 *  back it with a `var` or otherwise mutable storage. */
interface NamedDeviceSnapshot {
    /** Block position of the device's BE. Used by the handle to reach the live BE through
     *  the level on each script call (avoids stale state if the player edits the device
     *  mid-tick). */
    val pos: BlockPos

    /** Player-facing name. Null = unnamed, in which case [autoAlias] will be assigned
     *  with the device type's prefix (`tank_1`, `tank_2`, ...). */
    val name: String?

    /** Disambiguating `<base>_N` slug. Set by the discovery walk after the BFS finishes
     *  when this device shares a name with another, or when [name] is null. Stays null
     *  for uniquely-named devices, which then resolve to their literal [name] through
     *  [effectiveAlias]. */
    var autoAlias: String?

    /** The script-facing identifier. `network:get(effectiveAlias)` resolves the device.
     *  Auto-alias when set, otherwise the literal [name]. */
    val effectiveAlias: String get() = autoAlias ?: name ?: ""
}
