package damien.nodeworks.block.entity

import damien.nodeworks.registry.ModBlockEntities
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.DyeColor
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput

/**
 * A Processing Handler that owns many Processing Sets at once, for a machine bank that
 * runs them all, a furnace array cooking every ore. One input channel feeds every
 * input of every set and one output channel collects every output, so taking on a set
 * is one checkbox. Everything else, the two faces, the micro-network, routing and
 * pulling, is the Processing Handler's, inherited unchanged.
 *
 * The bound list is the only state of its own. The CPU, the registry and the claim
 * check all ask [boundSetNames] and [handles], the same questions they ask the
 * single Handler, so the two kinds cannot answer differently.
 */
class MultiProcessingHandlerBlockEntity(
    pos: BlockPos,
    state: BlockState,
) : ProcessingHandlerBlockEntity(ModBlockEntities.MULTI_PROCESSING_HANDLER, pos, state) {

    private val boundSets = LinkedHashSet<String>()

    /** Every input of every bound set routes to Storage Cards on this channel. */
    var inputChannel: DyeColor = DyeColor.BLUE
        private set

    override val singleBinding: Boolean get() = false

    /** Off by default: many sets share one machine bank, so one job runs through it at a
     *  time and co-processors spend their threads elsewhere. On, when the bank has room
     *  for several recipes at once, jobs dispatch into it freely and the machine room
     *  probe keeps them apart. */
    var parallel: Boolean = false
        private set

    override val exclusiveUse: Boolean get() = !parallel

    fun setParallel(enabled: Boolean) {
        if (parallel == enabled) return
        parallel = enabled
        markDirtyAndSync()
    }

    override fun boundSetNames(): List<String> = boundSets.toList()

    override fun handles(apiName: String): Boolean = apiName in boundSets

    override fun getInputChannel(key: damien.nodeworks.script.BufferKey.Key): DyeColor = inputChannel

    override fun channelKeyFor(ingr: damien.nodeworks.script.RecipeIngredient): damien.nodeworks.script.BufferKey.Key =
        ingr.bufferKey()

    /** True when the set was not bound before. The caller checks the cap and the
     *  claim rule first, this only records the result. */
    fun bindSet(name: String): Boolean {
        if (name.isEmpty() || !boundSets.add(name)) return false
        markDirtyAndSync()
        syncRegistry()
        return true
    }

    fun unbindSet(name: String): Boolean {
        if (!boundSets.remove(name)) return false
        markDirtyAndSync()
        syncRegistry()
        return true
    }

    fun setInputChannel(color: DyeColor) {
        if (inputChannel == color) return
        inputChannel = color
        markDirtyAndSync()
    }

    private fun syncRegistry() {
        if (level is ServerLevel) damien.nodeworks.script.cpu.BlockHandlerRegistry.syncFromBE(this)
    }

    override fun saveAdditional(output: ValueOutput) {
        super.saveAdditional(output)
        output.putInt("inputChannel", inputChannel.id)
        output.putBoolean("parallel", parallel)
        output.putInt("boundCount", boundSets.size)
        for ((index, name) in boundSets.withIndex()) output.putString("bound$index", name)
    }

    override fun loadAdditional(input: ValueInput) {
        super.loadAdditional(input)
        inputChannel = runCatching { DyeColor.byId(input.getIntOr("inputChannel", DyeColor.BLUE.id)) }
            .getOrDefault(DyeColor.BLUE)
        parallel = input.getBooleanOr("parallel", false)
        boundSets.clear()
        // Bounded by the config's ceiling, not its current value, so lowering the
        // cap later never silently drops sets a world already bound.
        val count = input.getIntOr("boundCount", 0).coerceIn(0, MAX_SAVED_SETS)
        for (index in 0 until count) {
            val name = input.getStringOr("bound$index", "")
            if (name.isNotEmpty()) boundSets.add(name)
        }
    }

    companion object {
        const val MAX_SAVED_SETS = 64
    }
}
