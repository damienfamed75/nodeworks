package damien.nodeworks.screen.widget

/**
 * Fuzzy string matching for autocompletion.
 * Scores candidates against input using subsequence matching with bonuses for:
 * - Prefix matches (highest priority)
 * - Consecutive character matches
 * - Word boundary matches (camelCase, underscore)
 * - Match position (earlier = better)
 *
 * Returns 0 for no match, higher scores for better matches.
 */
object FuzzyMatch {

    /**
     * Score a candidate string against the input query.
     * Returns 0 if no match, positive score if matched.
     */
    fun score(query: String, candidate: String): Int {
        if (query.isEmpty()) return 1 // empty query matches everything with minimal score

        val queryLower = query.lowercase()
        val candidateLower = candidate.lowercase()

        // Exact prefix match — highest score
        if (candidateLower.startsWith(queryLower)) {
            return 1000 + (100 - candidate.length) // shorter candidates rank higher
        }

        // Subsequence match with scoring
        var score = 0
        var queryIdx = 0
        var prevMatchIdx = -2 // -2 so first match isn't "consecutive"
        var consecutiveBonus = 0
        var firstMatchIdx = -1

        for (candidateIdx in candidateLower.indices) {
            if (queryIdx >= queryLower.length) break

            if (candidateLower[candidateIdx] == queryLower[queryIdx]) {
                if (firstMatchIdx == -1) firstMatchIdx = candidateIdx

                // Base match score
                score += 10

                // Consecutive match bonus (characters in a row)
                if (candidateIdx == prevMatchIdx + 1) {
                    consecutiveBonus += 5
                    score += consecutiveBonus
                } else {
                    consecutiveBonus = 0
                }

                // Word boundary bonus (start of word)
                if (candidateIdx == 0) {
                    score += 20 // first character
                } else {
                    val prevChar = candidate[candidateIdx - 1]
                    if (prevChar == '_' || prevChar == '.' || prevChar == ':') {
                        score += 15 // after separator
                    } else if (candidate[candidateIdx].isUpperCase() && prevChar.isLowerCase()) {
                        score += 15 // camelCase boundary
                    }
                }

                prevMatchIdx = candidateIdx
                queryIdx++
            }
        }

        // All query characters must be found
        if (queryIdx < queryLower.length) return 0

        // Bonus for early first match
        if (firstMatchIdx >= 0) {
            score += maxOf(0, 20 - firstMatchIdx * 3)
        }

        // Penalty for longer candidates (prefer shorter, more specific matches)
        score -= candidate.length / 2

        return maxOf(1, score) // minimum score of 1 if matched
    }

    /**
     * Filter and sort suggestions by fuzzy match score.
     * Returns only matching suggestions, sorted best-first.
     */
    fun filter(query: String, suggestions: List<AutocompletePopup.Suggestion>): List<AutocompletePopup.Suggestion> {
        if (query.isEmpty()) return suggestions

        return suggestions
            .map { it to score(query, it.insertText.removeSuffix("(")) }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .map { it.first }
    }
}
