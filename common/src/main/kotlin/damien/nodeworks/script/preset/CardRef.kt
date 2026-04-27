package damien.nodeworks.script.preset

import net.minecraft.core.Direction
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs

/**
 * A source or target reference accepted by preset builders (Importer / Stocker).
 *
 * Players can pass several different Lua values as a source or target:
 *   * A string, the card alias to resolve against the network snapshot.
 *   * A CardHandle table returned from `network:get(...)` (optionally `:face(...)`).
 *   * A HandleList table from `network:cards(...)` / `network:getAll(...)`,
 *     expanded into one ref per member.
 *   * The `network` global itself, a sentinel meaning "the whole Network Storage pool."
 *
 * [CardRefs.fromLua] normalises the per-value forms into [Named] (with optional
 * face override) or [Pool]. The name form always goes through name resolution at
 * tick time (via the current network snapshot), so a card that gets broken and
 * replaced with the same name picks up again on the next snapshot refresh
 * without any preset restart.
 */
sealed class CardRef {
    /** A card by alias. [faceOverride], when non-null, replaces the card's stored
     *  access face for the duration of preset operations. Carried in from a
     *  CardHandle that was produced via `:face(...)` or from a HandleList whose
     *  members were face-overridden. */
    data class Named(val alias: String, val faceOverride: Direction? = null) : CardRef()
    data object Pool : CardRef()
}

object CardRefs {

    /** Attempt to build a [CardRef] out of a single Lua value. Throws [LuaError] for
     *  anything that isn't a string, a CardHandle table, or the `network` pool sentinel.
     *  HandleLists are not handled here, [fromVarargs] expands them inline. */
    fun fromLua(v: LuaValue): CardRef {
        if (v.isstring()) return CardRef.Named(v.checkjstring())
        if (v.istable()) {
            val tbl = v.checktable()
            if (tbl.get("_isNetworkPool") == LuaValue.TRUE) return CardRef.Pool
            val cardName = tbl.get("_cardRefName")
            if (cardName.isstring()) {
                val face = readFaceOverride(tbl)
                return CardRef.Named(cardName.checkjstring(), face)
            }
        }
        throw LuaError("expected card alias, CardHandle, or network")
    }

    /** Read the `_cardRefFace` marker (a Direction.ordinal) off a CardHandle table.
     *  Returns null when the handle wasn't face-overridden, the caller falls back
     *  to the card's stored access face. */
    private fun readFaceOverride(tbl: LuaTable): Direction? {
        val face = tbl.get("_cardRefFace")
        if (!face.isnumber()) return null
        val idx = face.checkint()
        return Direction.entries.getOrNull(idx)
    }

    /** Collect a list of [CardRef]s from a varargs range. Inclusive 1-based index, matching
     *  luaj's own varargs convention. Returns an empty list if [startIndex] is past the end.
     *
     *  HandleList tables (marked with `_isHandleList = true`) expand inline into one ref
     *  per member, so `importer:from(network:cards("io_*"):face("bottom"))` produces the
     *  same ref list as if the user had typed each card explicitly. The expansion happens
     *  at script time, so the source set is fixed at the moment `:from` is called. Use
     *  the bare-string wildcard form (`:from("io_*")`) to get tick-time re-resolution. */
    fun fromVarargs(args: Varargs, startIndex: Int): List<CardRef> {
        if (startIndex > args.narg()) return emptyList()
        val out = ArrayList<CardRef>(args.narg() - startIndex + 1)
        for (i in startIndex..args.narg()) {
            val v = args.arg(i)
            if (v.istable() && v.checktable().get("_isHandleList") == LuaValue.TRUE) {
                val tbl = v.checktable()
                val arr = tbl.get("list").call(tbl)
                var k = 1
                while (true) {
                    val m = arr.get(k)
                    if (m.isnil()) break
                    out.add(fromLua(m))
                    k++
                }
            } else {
                out.add(fromLua(v))
            }
        }
        return out
    }
}
