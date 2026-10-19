package damien.nodeworks.screen

/**
 * A card menu whose rule list the client edits and pushes whole through
 * [damien.nodeworks.network.SetStorageCardFilterRulesPayload]. The server handler
 * routes the payload to whichever open menu implements this, so every card with a
 * Storage-Card-style rule panel shares one payload and one commit path.
 */
interface FilterRuleMenu {
    fun replaceFilterRules(rules: List<String>)
}
