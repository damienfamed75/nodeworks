package damien.nodeworks.script

import damien.nodeworks.block.entity.ProcessingStorageBlockEntity.ProcessingApiInfo
import damien.nodeworks.script.preset.RuleSet
import net.minecraft.core.HolderLookup
import org.luaj.vm2.LuaFunction

/**
 * The matching behind `network:recipes(...)`, free of Minecraft so it can be tested.
 *
 * Every input must pass [inputAllowed] and none may hit [inputDenied], so a rule for
 * ingots skips an alloy that also takes coal. [outputAllowed], when set, narrows the
 * match further: every required output must pass it. Optional outputs are left out,
 * a chance byproduct does not say what a recipe is. A set with no inputs never matches.
 */
object RecipeMatch {
    fun <T> matches(
        inputs: List<T>,
        outputs: List<T>,
        inputAllowed: (T) -> Boolean,
        inputDenied: (T) -> Boolean,
        outputAllowed: ((T) -> Boolean)?,
        isOptional: (T) -> Boolean,
    ): Boolean {
        if (inputs.isEmpty()) return false
        if (!inputs.all(inputAllowed)) return false
        if (inputs.any(inputDenied)) return false
        if (outputAllowed != null) {
            val required = outputs.filterNot(isOptional)
            if (required.isEmpty() || !required.all(outputAllowed)) return false
        }
        return true
    }
}

/**
 * One `network:recipes(...):handle(fn)` registration. The rules are copied at
 * registration, so editing the builder afterwards changes nothing already handled.
 *
 * A set's name is a hash of its contents, so whether it matches can be cached by name
 * for as long as tags stay the same. [tagEpoch] moves when tags reload, which drops
 * the cache, and the cache is bounded so a network that cycles through sets cannot
 * grow it forever.
 */
class RecipeHandlerRule internal constructor(
    private val inputs: RuleSet,
    private val outputs: RuleSet?,
    val handler: LuaFunction,
    val exclusive: Boolean,
    /** The script's own words for it, shown by the Diagnostic Tool. */
    val label: String,
) {
    /** False once `:stop()` ran or the script stopped. */
    @Volatile var active: Boolean = true

    private val cache = HashMap<String, Boolean>()
    private var cacheEpoch = tagEpoch

    fun matches(api: ProcessingApiInfo, registries: HolderLookup.Provider): Boolean {
        if (cacheEpoch != tagEpoch || cache.size >= MAX_CACHED) {
            cache.clear()
            cacheEpoch = tagEpoch
        }
        cache[api.name]?.let { return it }
        val result = try {
            RecipeMatch.matches(
                api.inputs, api.outputs,
                inputAllowed = { inputs.allow.isEmpty() || inputs.allow.any { rule -> CardHandle.matchesFilter(it.stack, rule, registries) } },
                inputDenied = { inputs.deny.any { rule -> CardHandle.matchesFilter(it.stack, rule, registries) } },
                outputAllowed = outputs?.let { o -> { ingr -> o.allow.any { rule -> CardHandle.matchesFilter(ingr.stack, rule, registries) } } },
                isOptional = { it.optional },
            )
        } catch (_: Exception) {
            // A rule that cannot be read matches nothing rather than breaking a lookup.
            false
        }
        cache[api.name] = result
        return result
    }

    companion object {
        private const val MAX_CACHED = 4096

        /** Bumped when tags reload, a tag rule may now match different items. */
        @Volatile var tagEpoch: Int = 0
            private set

        fun onTagsReloaded() {
            tagEpoch++
        }
    }
}
