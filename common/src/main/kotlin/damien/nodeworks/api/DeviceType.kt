package damien.nodeworks.api

import damien.nodeworks.network.Connectable
import net.minecraft.server.level.ServerLevel
import org.luaj.vm2.LuaTable

/**
 * SPI for plugging a new device type into Nodeworks. A "device" is anything that
 * connects to a Nodeworks network: variables, breakers, placers, tanks shipped by an
 * extension mod, anything. Register an implementation through [DeviceRegistry] from
 * your mod's init and the device picks up integration with the following surfaces
 * automatically:
 *
 *  * **Network discovery** — the BFS records a snapshot for every named instance the
 *    walk visits.
 *  * **Diagnostic Tool topology** — `displayInfo` lights up the topology label, color,
 *    and icon. `diagnosticDetails(be)` provides the detail-line rows. Leave both as the
 *    default (null / empty) to stay invisible there.
 *  * **Scripting Terminal sidebar** — a device with both `displayInfo` AND a non-null
 *    `handle` shows up in the per-network device list. The sidebar pulls the name from
 *    the snapshot and the icon / color from `displayInfo`.
 *  * **Lua scripts** — `network:device(typeId, name)` resolves to whatever LuaTable
 *    `handle` returns.
 *
 * Three things this SPI deliberately does NOT cover:
 *
 *  1. **Connection geometry** (which faces participate, what counts as a valid pair,
 *     wrench blocks) — implement [Connectable] on the BE itself and override the
 *     relevant methods. The minimal "only my back face connects" pattern is:
 *
 *     ```kotlin
 *     class TankBlockEntity(...) : BlockEntity(...), Connectable {
 *         private val backFace: Direction get() = blockState.getValue(FACING).opposite
 *         override fun activeFaces(): Set<Direction> = setOf(backFace)
 *         // ...
 *     }
 *     ```
 *
 *  2. **Lua type metadata** (autocomplete signatures, hover docs for handle methods)
 *     — register a `LuaApiSpec` separately through
 *     `damien.nodeworks.script.api.LuaApiRegistry.register(...)`. That registry already
 *     accepts external entries and the Scripting Terminal's autocomplete will pick
 *     them up.
 *
 *  3. **Block / item / model / renderer registration** — that's the platform's job,
 *     use the loader's normal registration paths.
 *
 * Minimal example for an extension mod's Tank:
 *
 * ```kotlin
 * class TankSnapshot(
 *     override val pos: BlockPos,
 *     override val name: String,
 *     val fluidId: String,
 *     val amount: Long,
 * ) : NamedDeviceSnapshot
 *
 * object TankDevice : DeviceType<TankBlockEntity> {
 *     override val typeId = "yourmod:tank"
 *     override val beClass = TankBlockEntity::class.java
 *
 *     override fun snapshot(be: TankBlockEntity): TankSnapshot? {
 *         if (be.tankName.isEmpty()) return null
 *         return TankSnapshot(be.blockPos, be.tankName, be.fluidId, be.amount)
 *     }
 *
 *     override fun handle(snap: NamedDeviceSnapshot, level: ServerLevel) =
 *         TankHandle.create(snap as TankSnapshot, level)
 *
 *     override val displayInfo = DeviceDisplay(
 *         displayName = "Tank",
 *         tintColor = 0xFF55AAFF.toInt(),
 *         icon = { ItemStack(YourModItems.TANK) },
 *     )
 *
 *     override fun diagnosticDetails(be: TankBlockEntity) = listOf(
 *         "Fluid: ${be.fluidId.ifEmpty { "(empty)" }}",
 *         "Amount: ${be.amount} mB",
 *     )
 * }
 *
 * // In mod init:
 * DeviceRegistry.register(TankDevice)
 * ```
 *
 * From Lua: `local t = network:device("yourmod:tank", "fuel"); print(t:amount())`.
 */
interface DeviceType<BE : Connectable> {
    /** Stable identifier scripts use through `network:device(typeId, name)`. Use a
     *  namespaced form (`"yourmod:tank"`) so multiple mods can't collide. The namespace
     *  is opaque to Nodeworks, any non-empty string works. */
    val typeId: String

    /** The [Connectable] BE class this type owns. The discovery walk dispatches via
     *  [Class.isInstance], so subclasses match too. If two registered types both match
     *  a given BE, the first one registered wins. */
    val beClass: Class<BE>

    /** Base prefix for auto-aliased instances (`<prefix>_1`, `<prefix>_2`, ...) when a
     *  device has no [NamedDeviceSnapshot.name] OR when two share a name and need
     *  disambiguating. Default strips the namespace from [typeId] (`"yourmod:tank"` →
     *  `"tank"`), which is usually what you want. Override if you want a shorter slug
     *  for the auto-alias suffix.
     *
     *  Shared with cards / breakers / placers / users in the same namespace: a card
     *  literal-named `tank` collides with an unnamed Tank instance and both get
     *  suffixed. Mirrors the existing cross-type rule documented on
     *  [damien.nodeworks.network.assignAliasSuffixes]. */
    val autoAliasPrefix: String
        get() = typeId.substringAfterLast(':')

    /** Build the snapshot recorded in `NetworkSnapshot.customDevices` for one walk.
     *  Return null to skip this BE (e.g. unnamed devices that scripts can't address
     *  yet). The BE will still appear in the Diagnostic Tool topology through
     *  [displayInfo] / [diagnosticDetails], just not in name lookups. */
    fun snapshot(be: BE): NamedDeviceSnapshot?

    /** Declares whether this device exposes a Lua API ([handle] returns non-null). Read
     *  on the CLIENT (by the Scripting Terminal sidebar gate) where invoking [handle] is
     *  unsafe because there's no [ServerLevel] available, so the property must agree
     *  with [handle]'s return convention.
     *
     *  Default is true. Set to false for debug-only devices that show in the Diagnostic
     *  Tool but shouldn't pollute the script-author sidebar. */
    val hasLuaApi: Boolean get() = true

    /** Build a fresh Lua table exposing this device's API. Called every time a script
     *  invokes `network:device(typeId, name)`. Null means "no Lua API" — must agree
     *  with [hasLuaApi]. Devices with no Lua API are excluded from the Scripting
     *  Terminal sidebar even when [displayInfo] is set.
     *
     *  This returns a raw handle. Nodeworks may layer rate limiting or sandbox concerns
     *  on top before the script sees the table. */
    fun handle(snap: NamedDeviceSnapshot, level: ServerLevel): LuaTable? = null

    /** Cosmetic metadata for the Diagnostic Tool and Scripting Terminal sidebar. Null
     *  hides the device from both surfaces. Scripts can still address the device
     *  through `network:device(...)` regardless. */
    val displayInfo: DeviceDisplay? get() = null

    /** Per-instance detail rows for the Diagnostic Tool's expanded panel. Called with
     *  the live BE so device state (current fluid, recent error, etc.) can be surfaced.
     *  Default empty. Prefixing a row with `__error:` highlights it as an error in the
     *  diagnostic UI, see existing usage in `CraftingCoreBlockEntity`. */
    fun diagnosticDetails(be: BE): List<String> = emptyList()
}
