package damien.nodeworks.script

/**
 * How one poll's haul of a single item type splits between the craft's
 * guaranteed count (kept with the craft), the declared optional count (sent
 * to network storage), and SURPLUS past both (also storage).
 *
 * The guaranteed/optional attribution is an estimate: the machine makes an
 * operation's outputs in one tick, so a haul of [compute]'s `k` implies at
 * least `ceil(k / perOp)` operations, each of which made its guaranteed
 * count. Machines whose bonuses are multiplied past the declared optional
 * count (a SAG mill with grinding balls) inflate `k` and overestimate the
 * operation count, filing bonus copies as guaranteed; the surplus bucket and
 * [ProcessingJob]'s post-completion sweep absorb what that leaves behind, and
 * [ProcessingJob.borrowShortfall] covers the opposite misfiling. Pure
 * arithmetic, unit-tested in OutputSplitTest.
 */
object OutputSplit {
    data class Split(val toRequired: Long, val toOptional: Long, val surplus: Long)

    fun compute(k: Long, reqLeft: Long, optLeft: Long, perOpRequired: Long, perOpOptional: Long): Split {
        if (k <= 0L) return Split(0L, 0L, 0L)
        val toRequired = when {
            reqLeft <= 0L -> 0L
            optLeft <= 0L -> minOf(k, reqLeft)
            else -> {
                val perOp = (perOpRequired + perOpOptional).coerceAtLeast(1L)
                val ops = (k + perOp - 1) / perOp
                minOf(k, ops * perOpRequired, reqLeft)
            }
        }
        val toOptional = minOf(k - toRequired, optLeft.coerceAtLeast(0L))
        return Split(toRequired, toOptional, k - toRequired - toOptional)
    }
}
