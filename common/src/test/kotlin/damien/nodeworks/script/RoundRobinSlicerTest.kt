package damien.nodeworks.script

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory

class RoundRobinSlicerTest {
    class Case(
        val name: String,
        val total: Int,
        val sliceCount: Int,
        val cycleIndex: Int,
        val want: List<Int>,
    )

    @TestFactory
    fun sliceReturnsExpectedWindow(): List<DynamicTest> {
        val cases = listOf(
            Case("empty list returns empty", 0, 5, 0, listOf()),
            Case("single item single slice", 1, 1, 0, listOf(0)),
            Case("zero slices treated as one", 4, 0, 0, listOf(0, 1, 2, 3)),
            Case("negative slices treated as one", 4, -3, 0, listOf(0, 1, 2, 3)),

            Case("even split first half", 4, 2, 0, listOf(0, 1)),
            Case("even split second half", 4, 2, 1, listOf(2, 3)),
            Case("even split wraps after one cycle", 4, 2, 2, listOf(0, 1)),

            Case("uneven first chunk", 5, 3, 0, listOf(0, 1)),
            Case("uneven middle chunk", 5, 3, 1, listOf(2, 3)),
            Case("uneven last chunk shorter", 5, 3, 2, listOf(4)),

            Case("more slices than items first", 3, 5, 0, listOf(0)),
            Case("more slices than items middle", 3, 5, 1, listOf(1)),
            Case("more slices than items last", 3, 5, 2, listOf(2)),
            Case("more slices than items wraps", 3, 5, 3, listOf(0)),

            Case("large positive cycle wraps", 6, 3, 100, listOf(2, 3)),
            Case("negative cycle wraps positively", 6, 3, -1, listOf(4, 5)),
        )

        val tests = ArrayList<DynamicTest>()
        for (c in cases) {
            tests.add(dynamicTest(c.name) {
                val items = (0 until c.total).toList()
                val got = RoundRobinSlicer.slice(items, c.cycleIndex, c.sliceCount)
                assertEquals(c.want, got)
            })
        }
        return tests
    }

    @TestFactory
    fun fullCycleCoversEveryItemExactlyOnce(): List<DynamicTest> {
        val combos = listOf(
            Pair(4, 2),
            Pair(5, 3),
            Pair(7, 4),
            Pair(12, 5),
            Pair(100, 8),
            Pair(3, 10), // more slices than items, capped to items.size
            Pair(5, 1),  // single slice covers everything in one go
        )

        val tests = ArrayList<DynamicTest>()
        for (combo in combos) {
            val total = combo.first
            val sliceCount = combo.second
            tests.add(dynamicTest("$total items / $sliceCount slices") {
                val items = (0 until total).toList()
                val passes = if (sliceCount > total) total else sliceCount
                val visited = ArrayList<Int>()
                for (cycle in 0 until passes) {
                    visited.addAll(RoundRobinSlicer.slice(items, cycle, sliceCount))
                }
                assertEquals(items, visited)
            })
        }
        return tests
    }
}
