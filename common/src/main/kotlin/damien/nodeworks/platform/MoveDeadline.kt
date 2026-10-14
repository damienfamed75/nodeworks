package damien.nodeworks.platform

/**
 * Wall-clock limit for a best-effort move. A caller installs one with [within], and
 * storage loops check [expired] between stacks, slots or rounds, stopping early and
 * keeping what already moved. Without one installed nothing ever expires, which is
 * what atomic moves rely on, since stopping one halfway would break all-or-nothing.
 *
 * The limit only applies once the move has landed something, so slow setup work (a
 * dry run, scanning a big source) can't use up the time and leave a call moving
 * nothing. A move that still hasn't landed anything stops at twice the limit, so a
 * full or refusing destination can't keep it scanning.
 */
object MoveDeadline {
    private class Scope(val deadline: Long, val hardDeadline: Long) {
        var progressed = false
    }

    private val current = ThreadLocal<Scope?>()

    fun <T> within(nanos: Long, block: () -> T): T {
        val previous = current.get()
        val now = System.nanoTime()
        val budget = nanos.coerceAtLeast(0L)
        // A nested move never gets more time than the one around it.
        val scope = Scope(
            deadline = if (previous != null) minOf(previous.deadline, now + budget) else now + budget,
            hardDeadline = if (previous != null) minOf(previous.hardDeadline, now + 2 * budget) else now + 2 * budget,
        )
        current.set(scope)
        try {
            return block()
        } finally {
            if (scope.progressed) previous?.progressed = true
            current.set(previous)
        }
    }

    /** Run [block] with no deadline, for an atomic move inside a timed caller. */
    fun <T> suspended(block: () -> T): T {
        val previous = current.get()
        current.set(null)
        try {
            return block()
        } finally {
            current.set(previous)
        }
    }

    /** Called by storage inserts when items actually landed. */
    fun progressed() {
        current.get()?.progressed = true
    }

    fun expired(): Boolean {
        val scope = current.get() ?: return false
        val now = System.nanoTime()
        return now - scope.hardDeadline >= 0L || (scope.progressed && now - scope.deadline >= 0L)
    }
}
