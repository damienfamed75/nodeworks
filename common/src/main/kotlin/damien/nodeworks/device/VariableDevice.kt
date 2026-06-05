package damien.nodeworks.device

import damien.nodeworks.api.DeviceDisplay
import damien.nodeworks.api.DeviceType
import damien.nodeworks.api.NamedDeviceSnapshot
import damien.nodeworks.block.entity.VariableBlockEntity
import damien.nodeworks.network.VariableSnapshot
import damien.nodeworks.registry.ModBlocks
import damien.nodeworks.screen.Icons
import damien.nodeworks.script.VariableHandle
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.ItemStack
import org.luaj.vm2.LuaTable

/**
 * Built-in [DeviceType] for the Variable device. First dogfood of the SPI: Variable was
 * historically reached through a hardcoded `is VariableBlockEntity` chain across the
 * discovery walk, the diagnostic tool, and the scripting terminal. Migrating it through
 * the SPI removes those branches and proves the SPI shape supports the existing built-in
 * surfaces (snapshot, Lua handle, diagnostic details, terminal sidebar).
 *
 * Registered from `Nodeworks.kt` init, see [damien.nodeworks.api.DeviceRegistry.register].
 *
 * Note on rate limiting: this device's [handle] returns the raw [VariableHandle] table.
 * The engine's other entry points (`network:get(name)`, `network:channel(c):get(...)`)
 * wrap that table with [damien.nodeworks.script.ScriptEngine]'s rate limiter via a
 * private helper. `network:device(...)` bypasses that wrapper, which is consistent with
 * the SPI's promise to return a raw handle and let the engine layer concerns on top.
 */
object VariableDevice : DeviceType<VariableBlockEntity> {
    override val typeId: String = "nodeworks:variable"

    override val beClass: Class<VariableBlockEntity> = VariableBlockEntity::class.java

    override fun snapshot(be: VariableBlockEntity): VariableSnapshot? {
        if (be.variableName.isEmpty()) return null
        return VariableSnapshot(be.blockPos, be.variableName, be.variableType, be.channel)
    }

    override fun handle(snap: NamedDeviceSnapshot, level: ServerLevel): LuaTable =
        VariableHandle.create(snap as VariableSnapshot, level)

    override val displayInfo: DeviceDisplay = DeviceDisplay(
        displayName = "Variable",
        tintColor = TINT,
        icon = { ItemStack(ModBlocks.VARIABLE) },
        // Pixel-tuned atlas sprite, matches the pre-migration look. Extension mods
        // wanting the same crispness ship their own texture and blit it the same way
        // here, with their own atlas / NineSlice / whatever the mod's UI layer uses.
        sidebarRender = { graphics, x, y -> Icons.VARIABLE.drawSmall(graphics, x, y) },
    )

    override fun diagnosticDetails(be: VariableBlockEntity): List<String> {
        val details = mutableListOf<String>()
        if (be.variableName.isNotEmpty()) {
            // Marker prefix consumed by the Diagnostic Screen renderer, same format
            // [damien.nodeworks.item.DiagnosticToolItem.aliasMarker] emits for the built-in
            // path. Keeping the wire format stable lets the screen stay device-agnostic.
            details.add("__alias:$TINT:${be.variableName}")
        }
        details.add("Type: ${be.variableType}")
        return details
    }

    /** Topology tint shared with the diagnostic tool's variable rendering and the
     *  scripting terminal's sidebar pip color. */
    const val TINT: Int = 0xFFFFAA33.toInt()
}
