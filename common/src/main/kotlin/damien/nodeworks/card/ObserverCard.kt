package damien.nodeworks.card

/**
 * Observer Card — reads the block at the adjacent position and exposes its id and
 * state property table to scripts. Drives `block()`, `state()`, and `onChange()`
 * in the scripting API; the change callback is poll-based (one read per registered
 * card per tick) and fires when the block id or any property differs from the
 * previously seen state.
 */
class ObserverCard(properties: Properties) : NodeCard(properties) {
    override val cardType: String = "observer"
}
