package damien.nodeworks.script

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [OutputSplit.compute] — the per-poll haul split between guaranteed copies
 * (kept with the craft), declared optionals (storage), and surplus past both
 * (bonus-multiplied machines: grinding balls). The grinding-ball cases pin
 * the behavior the SAG-mill stranding fix relies on: surplus always lands in
 * a bucket, never in no-man's-land.
 */
class OutputSplitTest {

    private fun split(k: Long, req: Long, opt: Long, perReq: Long = 1L, perOpt: Long = 1L) =
        OutputSplit.compute(k, req, opt, perReq, perOpt)

    @Test
    fun `nothing hauled splits to nothing`() {
        assertEquals(OutputSplit.Split(0, 0, 0), split(0, 20, 20))
    }

    @Test
    fun `no optional side files everything required up to the count`() {
        assertEquals(OutputSplit.Split(3, 0, 0), split(3, 20, 0, perOpt = 0))
        assertEquals(OutputSplit.Split(20, 0, 2), split(22, 20, 0, perOpt = 0))
    }

    @Test
    fun `required exhausted files into optional then surplus`() {
        assertEquals(OutputSplit.Split(0, 3, 0), split(3, 0, 5))
        assertEquals(OutputSplit.Split(0, 5, 2), split(7, 0, 5))
    }

    @Test
    fun `one op with its bonus splits one and one`() {
        // k=2 from one op (1 guaranteed + 1 bonus): ceil(2/2)=1 op -> 1 required.
        assertEquals(OutputSplit.Split(1, 1, 0), split(2, 20, 20))
    }

    @Test
    fun `two ops without bonuses misfile one as optional - the borrowShortfall case`() {
        // Known, compensated elsewhere: 2 plain ops in one tick look like 1 op + bonus.
        assertEquals(OutputSplit.Split(1, 1, 0), split(2, 2, 2))
    }

    @Test
    fun `grinding balls - one op hauling four files surplus not phantom ops beyond counts`() {
        // One op emitting 1 guaranteed + 3 multiplied bonuses: the estimate sees
        // ceil(4/2)=2 ops -> 2 required (the known overestimate), 1 declared
        // optional... with optLeft 1 per remaining op budget:
        val s = split(4, 20, 1)
        assertEquals(2, s.toRequired)
        assertEquals(1, s.toOptional)
        assertEquals(1, s.surplus)
        assertEquals(4, s.toRequired + s.toOptional + s.surplus)
    }

    @Test
    fun `haul past both budgets is all accounted`() {
        // End of the batch: 1 required and 1 optional left, machine coughs up 7.
        val s = split(7, 1, 1)
        assertEquals(1, s.toRequired)
        assertEquals(1, s.toOptional)
        assertEquals(5, s.surplus)
    }

    @Test
    fun `required cap holds under inflated ops estimate`() {
        // reqLeft 2, huge haul: ops estimate would allow more, cap wins.
        val s = split(10, 2, 3)
        assertEquals(2, s.toRequired)
        assertEquals(3, s.toOptional)
        assertEquals(5, s.surplus)
    }

    @Test
    fun `multi-count recipes keep the per-op ratio`() {
        // 2 guaranteed + 1 optional per op; one op's worth plus its bonus.
        assertEquals(OutputSplit.Split(2, 1, 0), split(3, 10, 5, perReq = 2, perOpt = 1))
        // Two ops' worth: ceil(6/3)=2 ops -> 4 required.
        assertEquals(OutputSplit.Split(4, 2, 0), split(6, 10, 2, perReq = 2, perOpt = 1))
    }

    @Test
    fun `conservation - every item lands in exactly one bucket`() {
        for (k in 0L..12L) for (req in 0L..4L) for (opt in 0L..4L) {
            val s = split(k, req, opt)
            assertEquals(k, s.toRequired + s.toOptional + s.surplus, "k=$k req=$req opt=$opt")
            assertTrue(s.toRequired <= req, "required over budget k=$k req=$req opt=$opt")
            assertTrue(s.toOptional <= opt, "optional over budget k=$k req=$req opt=$opt")
        }
    }
}
