package damien.nodeworks.script.cpu

import damien.nodeworks.script.CraftTreeBuilder.CraftTreeNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CpuFeasibilityShortfallTest {

    private fun node(itemId: String, count: Int, source: String, inStorage: Int, resolvedBy: String = "") =
        CraftTreeNode(
            itemId = itemId,
            itemName = itemId.substringAfter(':'),
            count = count,
            source = source,
            templateName = "",
            resolvedBy = resolvedBy,
            inStorage = inStorage,
            children = emptyList(),
        )

    private fun root(vararg children: CraftTreeNode) = CraftTreeNode(
        itemId = "test:machine",
        itemName = "machine",
        count = 1,
        source = "craft_template",
        templateName = "machine",
        resolvedBy = "local",
        inStorage = 0,
        children = children.toList(),
    )

    @Test
    fun oneItemUsedInManyBranchesIsOneLineOfWhatIsShort() {
        // 100 redstone in storage. Two branches take 28 and 68 from it, leaving 4. The
        // other two branches need 128 and 50 and find only those 4 free.
        val tree = root(
            node("minecraft:redstone", 28, "storage", 100),
            node("minecraft:redstone", 68, "storage", 72),
            node("minecraft:redstone", 128, "missing", 4),
            node("minecraft:redstone", 50, "missing", 4),
        )
        val short = CpuFeasibility.shortfalls(tree)
        assertEquals(1, short.size)
        assertEquals("redstone", short[0].name)
        // 274 asked for in all, 100 in storage.
        assertEquals(174L, short[0].count)
    }

    @Test
    fun theItemMostShortComesFirst() {
        val tree = root(
            node("minecraft:gold_ingot", 3, "missing", 0),
            node("minecraft:redstone", 64, "missing", 10),
        )
        val short = CpuFeasibility.shortfalls(tree)
        assertEquals(listOf("redstone" to 54L, "gold_ingot" to 3L), short.map { it.name to it.count })
    }

    @Test
    fun aCircularStandInReservesNothing() {
        val tree = root(
            node("minecraft:redstone", 8, "storage", 20, resolvedBy = "circular"),
            node("minecraft:redstone", 30, "missing", 20),
        )
        assertEquals(10L, CpuFeasibility.shortfalls(tree).single().count)
    }
}
