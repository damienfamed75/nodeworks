package damien.nodeworks.registry

import damien.nodeworks.screen.NodeSideScreenHandler
import damien.nodeworks.screen.InstructionSetScreenHandler
import damien.nodeworks.screen.TerminalScreenHandler
import net.minecraft.world.inventory.MenuType

/**
 * Menu type references. Populated by the platform-specific module at init time
 * since ExtendedScreenHandlerType / MenuType creation differs between Fabric and NeoForge.
 */
object ModScreenHandlers {

    lateinit var TERMINAL: MenuType<TerminalScreenHandler>
    lateinit var INSTRUCTION_SET: MenuType<InstructionSetScreenHandler>
    lateinit var NODE_SIDE: MenuType<NodeSideScreenHandler>

    fun initialize() {
        // Platform module must call registerAll() before this
    }
}
