package damien.nodeworks.script

import damien.nodeworks.block.entity.PlacerBlockEntity
import damien.nodeworks.network.PlacerSnapshot
import damien.nodeworks.platform.PlatformServices
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.BlockItem
import net.minecraft.world.level.block.Block
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.lib.OneArgFunction
import org.luaj.vm2.lib.TwoArgFunction

/**
 * Lua handle for a Placer device, pulls one item from network storage and places
 * it as a block in front of the device. Synchronous: `:place(...)` returns
 * `true` / `false` in the same tick the script called it.
 *
 * Mirrors [VariableHandle]'s shape, a `getEntity()` closure refetches the live
 * BlockEntity each call so the handle survives BlockEntity churn (player breaks
 * the placer, places a new one, the script keeps working on the next snapshot).
 */
object PlacerHandle {

    fun create(
        snapshot: PlacerSnapshot,
        networkSnapshot: damien.nodeworks.network.NetworkSnapshot,
        level: ServerLevel,
    ): LuaTable {
        val pos = snapshot.pos
        val table = LuaTable()
        val alias = snapshot.effectiveAlias

        fun getEntity(): PlacerBlockEntity =
            level.getBlockEntity(pos) as? PlacerBlockEntity
                ?: throw LuaError("Placer '$alias' has been removed")

        // .name, same convention as VariableHandle / CardHandle
        table.set("name", LuaValue.valueOf(alias))
        table.set("kind", LuaValue.valueOf("placer"))

        // :place(idOrItemsHandle) → boolean
        // String form pulls from network storage, ItemsHandle form pulls from the
        // referenced source. Returns false on any failure (no item available, target
        // not replaceable, item isn't a BlockItem, claim mod cancellation) so the
        // script can branch on the return value rather than relying on a callback.
        table.setGuarded("PlacerHandle", "place", object : TwoArgFunction() {
            override fun call(self: LuaValue, arg: LuaValue): LuaValue {
                val entity = getEntity()
                val target = entity.targetPos
                val targetState = level.getBlockState(target)
                if (!targetState.isAir && !targetState.canBeReplaced()) {
                    return LuaValue.FALSE
                }

                val itemId = resolvePlaceTargetItemId(arg) ?: return LuaValue.FALSE
                val identifier = Identifier.tryParse(itemId) ?: return LuaValue.FALSE
                val item = BuiltInRegistries.ITEM.getValue(identifier) ?: return LuaValue.FALSE
                val blockItem = item as? BlockItem ?: return LuaValue.FALSE
                val newState = blockItem.block.defaultBlockState()
                val placedAgainst = level.getBlockState(entity.blockPos)

                // The placer block is itself the "placed against" block (one step
                // back along its facing). Routing through FakePlayerService gates
                // on spawn protection + EntityPlaceEvent so claim mods can deny
                // the placement, in which case we refund the pulled item via
                // [onRollback].
                var pulled = false
                val ok = PlatformServices.fakePlayer.tryPlace(
                    level, target, placedAgainst, entity.ownerUuid,
                    mutate = {
                        if (!extractOneFromNetwork(level, networkSnapshot, itemId)) return@tryPlace false
                        pulled = true
                        level.setBlock(target, newState, Block.UPDATE_ALL)

                        val soundType = newState.soundType
                        level.playSound(
                            null, target,
                            soundType.placeSound,
                            net.minecraft.sounds.SoundSource.BLOCKS,
                            (soundType.volume + 1f) / 2f,
                            soundType.pitch * 0.8f,
                        )
                        true
                    },
                    onRollback = {
                        if (pulled) {
                            val refund = net.minecraft.world.item.ItemStack(item, 1)
                            damien.nodeworks.script.NetworkStorageHelper.insertItemStack(level, networkSnapshot, refund)
                        }
                    },
                )
                return if (ok) LuaValue.TRUE else LuaValue.FALSE
            }
        })

        // :block() → string, current block id at the targeted position. Useful
        // for "is the slot still empty" checks before calling :place.
        table.setGuarded("PlacerHandle", "block", object : OneArgFunction() {
            override fun call(self: LuaValue): LuaValue {
                val entity = getEntity()
                val state = level.getBlockState(entity.targetPos)
                return LuaValue.valueOf(BuiltInRegistries.BLOCK.getKey(state.block).toString())
            }
        })

        // :isBlocked() → boolean, true if a place would fail because the target
        // is non-air and not replaceable.
        table.setGuarded("PlacerHandle", "isBlocked", object : OneArgFunction() {
            override fun call(self: LuaValue): LuaValue {
                val entity = getEntity()
                val state = level.getBlockState(entity.targetPos)
                return LuaValue.valueOf(!state.isAir && !state.canBeReplaced())
            }
        })

        return table
    }

    /** Resolve a place-target item id from the user's argument. Accepts string ids
     *  ("minecraft:oak_sapling") or ItemsHandle Lua tables (uses `.id`). Returns
     *  null when neither shape applies. */
    private fun resolvePlaceTargetItemId(arg: LuaValue): String? {
        if (arg.isstring()) return arg.checkjstring()
        if (arg.istable()) {
            val id = arg.get("id")
            if (!id.isnil() && id.isstring()) return id.checkjstring()
        }
        return null
    }

    /** Walk storage cards in priority order, calling extractItems on each until
     *  one of them yields a single matching item. Returns true on success. */
    private fun extractOneFromNetwork(
        level: ServerLevel,
        snapshot: damien.nodeworks.network.NetworkSnapshot,
        itemId: String,
    ): Boolean {
        val matches: (String) -> Boolean = { it == itemId }
        for (card in NetworkStorageHelper.getStorageCards(snapshot)) {
            val storage = NetworkStorageHelper.getStorage(level, card) ?: continue
            val pulled = PlatformServices.storage.extractItems(storage, matches, 1L)
            if (pulled >= 1L) return true
        }
        return false
    }
}
