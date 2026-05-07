package damien.nodeworks.script

import damien.nodeworks.block.PlacerBlock
import damien.nodeworks.block.entity.PlacerBlockEntity
import damien.nodeworks.network.PlacerSnapshot
import damien.nodeworks.platform.PlatformServices
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.context.UseOnContext
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
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
        networkSnapshotFn: () -> damien.nodeworks.network.NetworkSnapshot,
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
                val placedAgainst = level.getBlockState(entity.blockPos)

                // Route placement through [BlockItem.useOn] so multi-block items
                // (doors, beds, tall flowers, candles) place all their parts and
                // get the right facing / half / axis state from a player-style
                // BlockPlaceContext. The FakePlayer is briefly given a single
                // copy of the item, the synthetic [BlockHitResult] points at the
                // Placer's own outward face, useOn does the rest. Hand stack is
                // restored after so we don't leak the item if the FP is reused.
                val facing = entity.blockState.getValue(PlacerBlock.FACING)
                val hitVec = Vec3(
                    entity.blockPos.x + 0.5 + facing.stepX * 0.5,
                    entity.blockPos.y + 0.5 + facing.stepY * 0.5,
                    entity.blockPos.z + 0.5 + facing.stepZ * 0.5,
                )
                val hit = BlockHitResult(hitVec, facing, entity.blockPos, false)

                val ok = PlatformServices.fakePlayer.tryPlace(
                    level, target, placedAgainst, entity.ownerUuid,
                    mutate = {
                        if (!extractOneFromNetwork(level, networkSnapshotFn(), itemId)) return@tryPlace false
                        val fp = PlatformServices.fakePlayer.get(level, entity.ownerUuid)
                        val savedHand = fp.getItemInHand(InteractionHand.MAIN_HAND).copy()
                        fp.setItemInHand(InteractionHand.MAIN_HAND, ItemStack(item, 1))
                        val placed = try {
                            val ctx = UseOnContext(fp, InteractionHand.MAIN_HAND, hit)
                            val result = blockItem.useOn(ctx)
                            // useOn shrinks the hand stack only when the placement
                            // actually wrote blocks to the world. Use the hand-count
                            // delta as ground truth, the InteractionResult alone can
                            // return CONSUME for items that ate the click but didn't
                            // place anything.
                            result.consumesAction() && fp.getItemInHand(InteractionHand.MAIN_HAND).count < 1
                        } finally {
                            fp.setItemInHand(InteractionHand.MAIN_HAND, savedHand)
                        }
                        if (!placed) {
                            // useOn declined after we already extracted, refund here
                            // since [tryPlace] only invokes [onRollback] on the
                            // EntityPlaceEvent-cancel path.
                            val refund = ItemStack(item, 1)
                            damien.nodeworks.script.NetworkStorageHelper.insertItemStack(level, networkSnapshotFn(), refund)
                        }
                        placed
                    },
                    onRollback = {
                        // EntityPlaceEvent canceled by a claim mod after a successful
                        // useOn. The block snapshot is restored by tryPlace, refund
                        // the extracted item back to network storage here.
                        val refund = ItemStack(item, 1)
                        damien.nodeworks.script.NetworkStorageHelper.insertItemStack(level, networkSnapshotFn(), refund)
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
