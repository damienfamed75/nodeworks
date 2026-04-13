package damien.nodeworks.command

import damien.nodeworks.network.InventorySyncPayload
import damien.nodeworks.screen.CraftingCoreMenu
import damien.nodeworks.screen.CraftingCoreScreen
import damien.nodeworks.screen.InventoryTerminalMenu
import damien.nodeworks.screen.InventoryTerminalScreen
import damien.nodeworks.script.CraftTreeBuilder.CraftTreeNode
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.world.inventory.SimpleContainerData
import net.minecraft.world.item.ItemStack

/**
 * Opens debug GUI screens with fake data for visual testing.
 * Called from the client-side packet handler for /nwdebug commands.
 */
object DebugScreens {

    fun openCraftingCore() {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return

        // Create a fake menu with populated data
        val data = SimpleContainerData(CraftingCoreMenu.DATA_SLOTS)
        data.set(0, 47)    // bufferUsed
        data.set(1, 1280)  // bufferCapacity
        data.set(2, 1)     // isFormed
        data.set(3, 1)     // isCrafting
        val menu = CraftingCoreMenu(999, BlockPos.ZERO, data)

        // Fake buffer: 60 item types to test scrolling
        val fakeBuffer = mutableListOf<Pair<String, Long>>()
        val items = listOf(
            "minecraft:iron_ingot" to 2048, "minecraft:gold_ingot" to 512,
            "minecraft:diamond" to 256, "minecraft:emerald" to 128,
            "minecraft:redstone" to 384, "minecraft:lapis_lazuli" to 192,
            "minecraft:coal" to 320, "minecraft:copper_ingot" to 160,
            "minecraft:netherite_scrap" to 16, "minecraft:amethyst_shard" to 96,
            "minecraft:quartz" to 144, "minecraft:glowstone_dust" to 80,
            "minecraft:iron_nugget" to 64, "minecraft:gold_nugget" to 48,
            "minecraft:raw_iron" to 200, "minecraft:raw_gold" to 100,
            "minecraft:raw_copper" to 150, "minecraft:flint" to 32,
            "minecraft:bone" to 24, "minecraft:gunpowder" to 40,
            "minecraft:string" to 56, "minecraft:feather" to 16,
            "minecraft:leather" to 28, "minecraft:rabbit_hide" to 12,
            "minecraft:blaze_rod" to 8, "minecraft:ender_pearl" to 4,
            "minecraft:ghast_tear" to 2, "minecraft:magma_cream" to 6,
            "minecraft:slime_ball" to 20, "minecraft:phantom_membrane" to 3,
            "minecraft:oak_log" to 512, "minecraft:spruce_log" to 256,
            "minecraft:birch_log" to 128, "minecraft:dark_oak_log" to 64,
            "minecraft:oak_planks" to 1024, "minecraft:cobblestone" to 4096,
            "minecraft:stone" to 2048, "minecraft:granite" to 512,
            "minecraft:diorite" to 256, "minecraft:andesite" to 384,
            "minecraft:sand" to 640, "minecraft:gravel" to 320,
            "minecraft:clay_ball" to 96, "minecraft:brick" to 192,
            "minecraft:nether_brick" to 80, "minecraft:obsidian" to 32,
            "minecraft:glass" to 256, "minecraft:glass_pane" to 512,
            "minecraft:iron_block" to 16, "minecraft:gold_block" to 8,
            "minecraft:diamond_block" to 4, "minecraft:emerald_block" to 2,
            "minecraft:stick" to 1024, "minecraft:torch" to 384,
            "minecraft:arrow" to 256, "minecraft:bow" to 3,
            "minecraft:fishing_rod" to 2, "minecraft:shears" to 1,
            "minecraft:bucket" to 12, "minecraft:water_bucket" to 6,
            "minecraft:lava_bucket" to 3, "minecraft:milk_bucket" to 4,
            "minecraft:bread" to 48, "minecraft:apple" to 24,
            "minecraft:golden_apple" to 8, "minecraft:cooked_beef" to 64,
            "minecraft:porkchop" to 32, "minecraft:egg" to 16
        )
        for ((id, count) in items) fakeBuffer.add(id to count.toLong())
        menu.clientBufferContents = fakeBuffer

        // Fake craft tree: netherite sword with deep prerequisites
        menu.craftTree = CraftTreeNode(
            "minecraft:netherite_sword", "Netherite Sword", 1, "craft_template", "netherite_sword", "", 0,
            listOf(
                CraftTreeNode("minecraft:netherite_ingot", "Netherite Ingot", 1, "craft_template", "netherite_ingot", "", 0,
                    listOf(
                        CraftTreeNode("minecraft:netherite_scrap", "Netherite Scrap", 4, "process_template", "smelt_debris", "local", 2,
                            listOf(
                                CraftTreeNode("minecraft:ancient_debris", "Ancient Debris", 2, "storage", "", "storage", 2, emptyList()),
                                CraftTreeNode("minecraft:ancient_debris", "Ancient Debris", 2, "missing", "", "", 0, emptyList())
                            )
                        ),
                        CraftTreeNode("minecraft:gold_ingot", "Gold Ingot", 4, "process_template", "smelt_gold", "local", 0,
                            listOf(
                                CraftTreeNode("minecraft:raw_gold", "Raw Gold", 4, "storage", "", "storage", 10, emptyList())
                            )
                        )
                    )
                ),
                CraftTreeNode("minecraft:diamond_sword", "Diamond Sword", 1, "craft_template", "diamond_sword", "", 0,
                    listOf(
                        CraftTreeNode("minecraft:diamond", "Diamond", 2, "storage", "", "storage", 64, emptyList()),
                        CraftTreeNode("minecraft:stick", "Stick", 1, "craft_template", "sticks", "", 0,
                            listOf(
                                CraftTreeNode("minecraft:oak_planks", "Oak Planks", 2, "craft_template", "planks", "", 0,
                                    listOf(
                                        CraftTreeNode("minecraft:oak_log", "Oak Log", 1, "storage", "", "storage", 32, emptyList())
                                    )
                                )
                            )
                        )
                    )
                )
            )
        )
        menu.activeSteps = setOf("minecraft:netherite_scrap", "minecraft:gold_ingot")

        // Open the screen
        val screen = CraftingCoreScreen(menu, player.inventory, Component.literal("Crafting CPU"))
        mc.setScreen(screen)
    }

    fun openInventoryTerminal() {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return

        val menu = InventoryTerminalMenu(998, player.inventory, null, BlockPos.ZERO)

        // Build a sync payload with every registered item, billions of counts
        val entries = mutableListOf<InventorySyncPayload.SyncEntry>()
        var serial = 1L
        val random = java.util.Random(42)

        for (item in BuiltInRegistries.ITEM) {
            val id = BuiltInRegistries.ITEM.getKey(item)?.toString() ?: continue
            val stack = ItemStack(item)
            if (stack.isEmpty) continue
            val name = stack.hoverName.string
            val count = when (random.nextInt(10)) {
                0 -> random.nextLong(1_000_000_000, 10_000_000_000)  // billions
                1 -> random.nextLong(1_000_000, 999_999_999)          // millions
                2 -> random.nextLong(1_000, 999_999)                  // thousands
                3 -> 0L                                                // craftable only (phantom)
                else -> random.nextLong(1, 999)                       // normal small amounts
            }
            val craftable = random.nextInt(4) == 0  // 25% chance craftable
            entries.add(InventorySyncPayload.SyncEntry(
                serial = serial++,
                itemId = id,
                name = name,
                count = count,
                maxStackSize = item.getDefaultMaxStackSize(),
                hasData = false,
                craftable = craftable
            ))
        }

        // Open the screen and populate via handleUpdate
        val screen = InventoryTerminalScreen(menu, player.inventory, Component.literal("Inventory Terminal"))
        mc.setScreen(screen)
        screen.repo.handleUpdate(InventorySyncPayload(true, entries, emptyList()))
    }
}
