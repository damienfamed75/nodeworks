package damien.nodeworks.script

/**
 * Maps the full, player-actionable planning error messages (set by
 * [CraftingHelper.lastFailReason] and [BlockCraftSubmitter.Result.Failed])
 * into the short status-line labels rendered by the Storage Meter and Craft
 * Requester's job-status indicator.
 *
 * The indicator's inline space is narrow so the short label is what shows on
 * the face; the full message stays in the hover tooltip so the player can
 * still read the detail. Centralised here so other consumers (logs,
 * particles, future device ledgers) can reuse the same labels without
 * duplicating the mapping.
 *
 * When adding a new error path:
 *   1. Add a `const val` for the short label.
 *   2. Add a match arm in [shortLabel] keying off the full message's
 *      prefix or equality.
 */
object PlanningErrorLabels {
    const val MISSING_INGREDIENTS = "Missing Ingredients"
    const val MISSING_HANDLER = "Missing Handler"
    const val BUFFER_TOO_SMALL = "Buffer Too Small"
    const val CPUS_BUSY = "CPUs Busy"
    const val NO_CRAFTING_CPU = "No Crafting CPU"
    const val CPU_NOT_FORMED = "CPU Not Formed"
    const val INVALID_COUNT = "Invalid Count"
    const val INVALID_BATCH = "Invalid Batch"
    const val NO_TARGET = "No Target"
    const val PLAN_FAILED = "Plan Failed"
    const val SUBMIT_FAILED = "Submit Failed"
    const val JOB_FAILED = "Job Failed"
    const val GENERIC_ERROR = "Error"

    /** Shorten a full planning-error message for inline display. Returns
     *  [GENERIC_ERROR] when [fullMessage] doesn't match a known pattern,
     *  the tooltip still shows the full text so the detail isn't lost. */
    fun shortLabel(fullMessage: String): String = when {
        fullMessage.startsWith("Missing ingredients") -> MISSING_INGREDIENTS
        fullMessage.startsWith("No handler for") -> MISSING_HANDLER
        fullMessage.startsWith("Craft needs") -> BUFFER_TOO_SMALL
        fullMessage.startsWith("Craft requires up to") -> BUFFER_TOO_SMALL
        fullMessage.startsWith("All Crafting CPUs") -> CPUS_BUSY
        fullMessage.startsWith("No Crafting CPU on the network") -> NO_CRAFTING_CPU
        fullMessage.startsWith("No Crafting CPU at") -> NO_CRAFTING_CPU
        fullMessage.startsWith("Crafting CPU at") && fullMessage.contains("is not formed") -> CPU_NOT_FORMED
        fullMessage == "Craft count must be positive" -> INVALID_COUNT
        fullMessage == "Batch size must be positive" -> INVALID_BATCH
        fullMessage == "No target item set" -> NO_TARGET
        fullMessage == "Could not plan craft" -> PLAN_FAILED
        fullMessage == "Craft submission failed" -> SUBMIT_FAILED
        fullMessage == "Submission returned no job handle" -> SUBMIT_FAILED
        fullMessage.startsWith("Craft job failed") -> JOB_FAILED
        else -> GENERIC_ERROR
    }
}
