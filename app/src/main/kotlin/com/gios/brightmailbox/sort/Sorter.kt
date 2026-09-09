package com.gios.brightmailbox.sort

/** Tokenisation shared by the learner and the tests. */
internal fun words(s: String): List<String> =
    s.lowercase()
        .split(Regex("[^\\p{L}\\p{N}']+"))
        .filter { it.length in 2..24 }

internal fun bigrams(w: List<String>): List<String> =
    if (w.size < 2) emptyList() else w.zipWithNext { a, b -> "$a|$b" }

/**
 * What the sorter decided, and how sure it is about the ranking.
 */
data class Sorted(
    val verdict: Verdict,
    /** 0..1 from the learned model. Only meaningful for ranking Letters. */
    val importance: Double,
)

/**
 * The two tiers, wired together.
 *
 * Division of labour, and it matters that it stays this way:
 *
 *   Tier 0 decides the PILE. Deterministic, explainable, offline, instant.
 *   Tier 1 decides the ORDER within Letters, and nothing else by default.
 *
 * The learned model is allowed to move a message between piles only when it is very
 * confident and the header rules had nothing to go on — never against an explicit
 * marker. A List-Unsubscribe header is a fact; a model output is an opinion, and an
 * opinion that can silently bury a person's mail is not worth the ranking it buys.
 */
class Sorter(
    private val learner: Learner,
    /** Per-sender decisions the user has made. Address -> pile. */
    private val overrides: Map<String, Pile> = emptyMap(),
    /** Address -> how many times the user replied to it. */
    private val replies: Map<String, Int> = emptyMap(),
) {

    fun sort(e: Envelope): Sorted {
        val v0 = Headers.classify(
            e,
            repliedTo = replies[e.from] ?: 0,
            override = overrides[e.from],
        )
        val p = learner.predict(e)

        if (v0.rule in SOFT && learner.seen > MIN_SEEN && p < DEMOTE_BELOW) {
            return Sorted(Verdict(Pile.NOTICE, "reads like mail you don't open", "learned"), p)
        }
        return Sorted(v0, p)
    }

    private companion object {
        /**
         * Verdicts the learned model is allowed to overrule.
         *
         * Both are the ABSENCE of evidence, not evidence. "no-bulk-markers" means no
         * header claimed automation; "addressed-to-me" means the sender put the user in
         * To:, which every marketing email in the world also does. Neither is a fact
         * about the message, so a confident model may disagree with them.
         *
         * Everything else in Headers IS a fact — List-Unsubscribe, Precedence,
         * Auto-Submitted, a no-reply sender, a VERP return path, and above all
         * "you have replied to this address". A model output is an opinion, and an
         * opinion is not allowed to bury a person's mail behind a stated fact.
         */
        val SOFT = setOf("no-bulk-markers", "addressed-to-me")

        /** Below this the model has not seen enough of this mailbox to have a view. */
        const val MIN_SEEN = 200L

        /** Deliberately far from 0.5. Demoting is destructive; it needs conviction. */
        const val DEMOTE_BELOW = 0.12
    }

    /**
     * Today's Letters, best first.
     *
     * Ranking is the learned score, but recency breaks near-ties: two messages the model
     * cannot separate should come in the order they arrived, because a mailbox that
     * reorders itself for no visible reason feels broken.
     */
    fun rank(items: List<Pair<Envelope, Long>>): List<Pair<Envelope, Double>> =
        items.map { (e, at) -> Triple(e, learner.predict(e), at) }
            .sortedWith(
                compareByDescending<Triple<Envelope, Double, Long>> { (it.second * 20).toInt() }
                    .thenByDescending { it.third },
            )
            .map { it.first to it.second }
}
