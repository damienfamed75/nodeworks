package damien.nodeworks.script.preset

import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs

/**
 * A source or target reference accepted by preset builders (Importer / Stocker).
 *
 * Players can pass three different Lua values as a source or target:
 *   * A string, the card alias to resolve against the network snapshot.
 *   * A CardHandle table returned from `network:get(...)`.
 *   * The `network` global itself, a sentinel meaning "the whole Network Storage pool."
 *
 * [CardRefs.fromLua] normalises all three into either a [Named] or [Pool] variant.
 * The name form always goes through name resolution at tick time (via the current
 * network snapshot), so a card that gets broken and replaced with the same name
 * picks up again on the next snapshot refresh without any preset restart.
 */
sealed class CardRef {
    data class Named(val alias: String) : CardRef()
    data object Pool : CardRef()
}

object CardRefs {

    /** Attempt to build a [CardRef] out of a single Lua value. Throws [LuaError] for
     *  anything that isn't a string, a CardHandle table, or the `network` pool sentinel. */
    fun fromLua(v: LuaValue): CardRef {
        if (v.isstring()) return CardRef.Named(v.checkjstring())
        if (v.istable()) {
            val tbl = v.checktable()
            if (tbl.get("_isNetworkPool") == LuaValue.TRUE) return CardRef.Pool
            val cardName = tbl.get("_cardRefName")
            if (cardName.isstring()) return CardRef.Named(cardName.checkjstring())
        }
        throw LuaError("expected card alias, CardHandle, or network")
    }

    /** Collect a list of [CardRef]s from a varargs range. Inclusive 1-based index, matching
     *  luaj's own varargs convention. Returns an empty list if [startIndex] is past the end. */
    fun fromVarargs(args: Varargs, startIndex: Int): List<CardRef> {
        if (startIndex > args.narg()) return emptyList()
        val out = ArrayList<CardRef>(args.narg() - startIndex + 1)
        for (i in startIndex..args.narg()) {
            out.add(fromLua(args.arg(i)))
        }
        return out
    }
}
