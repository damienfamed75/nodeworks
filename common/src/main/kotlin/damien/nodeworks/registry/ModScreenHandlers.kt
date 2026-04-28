package damien.nodeworks.registry

import damien.nodeworks.screen.DiagnosticMenu
import damien.nodeworks.screen.ProcessingStorageScreenHandler
import damien.nodeworks.screen.BroadcastAntennaMenu
import damien.nodeworks.screen.ReceiverAntennaMenu
import damien.nodeworks.screen.InstructionStorageScreenHandler
import damien.nodeworks.screen.InventoryTerminalMenu
import damien.nodeworks.screen.NetworkControllerMenu
import damien.nodeworks.screen.CraftingCoreMenu
import damien.nodeworks.screen.ProcessingSetScreenHandler
import damien.nodeworks.screen.VariableMenu
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
    lateinit var INSTRUCTION_STORAGE: MenuType<InstructionStorageScreenHandler>
    lateinit var NODE_SIDE: MenuType<NodeSideScreenHandler>
    lateinit var INVENTORY_TERMINAL: MenuType<InventoryTerminalMenu>
    lateinit var NETWORK_CONTROLLER: MenuType<NetworkControllerMenu>
    lateinit var VARIABLE: MenuType<VariableMenu>
    lateinit var CRAFTING_CORE: MenuType<CraftingCoreMenu>
    lateinit var PROCESSING_SET: MenuType<ProcessingSetScreenHandler>
    lateinit var PROCESSING_STORAGE: MenuType<ProcessingStorageScreenHandler>
    lateinit var BROADCAST_ANTENNA: MenuType<BroadcastAntennaMenu>
    lateinit var RECEIVER_ANTENNA: MenuType<ReceiverAntennaMenu>
    lateinit var DIAGNOSTIC: MenuType<DiagnosticMenu>
    lateinit var CARD_PROGRAMMER: MenuType<damien.nodeworks.screen.CardProgrammerMenu>
    lateinit var STORAGE_CARD: MenuType<damien.nodeworks.screen.StorageCardMenu>
    lateinit var CARD_SETTINGS: MenuType<damien.nodeworks.screen.CardSettingsMenu>
    lateinit var BREAKER: MenuType<damien.nodeworks.screen.BreakerMenu>
    lateinit var PLACER: MenuType<damien.nodeworks.screen.PlacerMenu>

    fun initialize() {
        // Platform module must call registerAll() before this
    }
}
