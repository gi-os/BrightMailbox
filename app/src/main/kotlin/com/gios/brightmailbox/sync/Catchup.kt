package com.gios.brightmailbox.sync

/**
 * Where a folder's sync got to, and how far to go next.
 *
 * **The bug this exists for is a gap, not a failure.** The ordinary sync used to read the
 * newest twenty messages, throw away the page cursor, and stop. Eighty messages arriving
 * while the phone was in a drawer meant every refresh re-read the same twenty newest,
 * agreed they were already stored, and never looked at the sixty underneath. Nothing
 * failed; the mail was simply never asked for. The only fix is to remember where the
 * last pass stopped and to keep going from there.
 *
 * **The checkpoint is a UID, and a UID is only meaningful under one UIDVALIDITY.** IMAP
 * assigns UIDs in arrival order and promises they never change or repeat inside a folder
 * — for as long as the folder's UIDVALIDITY stays the same. That makes "the highest UID
 * already stored" the natural bookmark: everything above it is new, everything at or
 * below it has been seen. When the server changes UIDVALIDITY the whole numbering is
 * void, and a bookmark from the old numbering would either skip real mail or re-read the
 * folder from the wrong place. So the validity travels with the UID and a mismatch means
 * "start over", never "carry on".
 *
 * This file is plain Kotlin on purpose: the decision "given what came back, do I fetch
 * more, and from where" is the part that can be wrong in four quiet ways, and it is
 * tested without a phone or a server.
 */
object Catchup {

    /** How many pages one run will walk before handing the rest to the next run. */
    const val MAX_PAGES = 8

    /**
     * A bookmark in one folder of one account.
     *
     * [uid] is the highest UID already stored under [validity]. Zero means "nothing stored
     * yet under this numbering", which is a real state — a freshly emptied mailbox — and
     * not the same as having no checkpoint at all.
     */
    data class Checkpoint(val validity: Long, val uid: Long) {
        fun encode(): String = "$validity-$uid"

        companion object {
            fun decode(s: String?): Checkpoint? {
                if (s.isNullOrBlank()) return null
                val p = s.split('-')
                val v = p.getOrNull(0)?.toLongOrNull() ?: return null
                val u = p.getOrNull(1)?.toLongOrNull() ?: return null
                return Checkpoint(v, u)
            }
        }
    }

    /**
     * One request to the transport.
     *
     * [after] null means "the newest [limit] by position" — the bootstrap, used when
     * there is no bookmark worth trusting. Otherwise: everything with a UID above
     * [after], oldest first, at most [limit] of it.
     */
    data class Ask(val after: Long?, val limit: Int)

    /** What the transport said about one page. */
    data class Page(
        /** The folder's UIDVALIDITY at the time of the fetch. */
        val validity: Long,
        /** The lowest and highest UID in the slice the server handed back; null when empty. */
        val lowest: Long?,
        val highest: Long?,
        /** Whether the server had more above this slice than [Ask.limit] allowed. */
        val more: Boolean,
    )

    sealed class Step {
        data class More(val ask: Ask) : Step()

        /**
         * Nothing further this run. [caughtUp] is false when the per-run cap stopped
         * the walk with mail still above the bookmark; the next run continues from it.
         */
        data class Done(val caughtUp: Boolean) : Step()
    }

    /**
     * The highest UID among provider ids that belong to [validity].
     *
     * Ids are `"$validity-$uid"` for INBOX and carry a folder tag otherwise; a tagged id
     * is another folder's numbering and is skipped whatever it says.
     */
    fun highestStored(providerIds: Iterable<String>, validity: Long): Long? {
        var best: Long? = null
        for (id in providerIds) {
            val dash = id.indexOf('-')
            if (dash <= 0) continue
            val v = id.substring(0, dash).toLongOrNull() ?: continue
            if (v != validity) continue
            val u = id.substring(dash + 1).toLongOrNull() ?: continue
            if (best == null || u > best) best = u
        }
        return best
    }

    /**
     * Does the caller need to look up what the database holds before storing this page?
     *
     * Only for a bootstrap page and for a page whose validity does not match the
     * bookmark — the two moments the rows are the only bookmark there is. Asked before
     * the page is written, because afterwards the rows include the page and can only
     * say "the top".
     */
    fun needsKnown(checkpoint: Checkpoint?, asked: Ask, validity: Long): Boolean =
        asked.after == null || (checkpoint != null && checkpoint.validity != validity)

    /**
     * One folder's walk, from a bookmark to the top of the folder or the per-run cap.
     *
     * Feed it what each page said and it says what to ask for next. It never sees a
     * message: only UIDs, which is all the decision needs.
     */
    class Run(
        stored: Checkpoint?,
        private val pageSize: Int,
        private val maxPages: Int = MAX_PAGES,
    ) {
        /** Where the walk has got to. Persist after every page; it only ever rises. */
        var checkpoint: Checkpoint? = stored
            private set

        var pages: Int = 0
            private set

        fun first(): Ask = Ask(checkpoint?.uid, pageSize)

        /**
         * Digest one page and decide.
         *
         * @param asked what was requested, so a bootstrap page is known for one.
         * @param known the highest UID the database already held under [Page.validity]
         *   **before this page was stored**, when [needsKnown] said to look; null when
         *   it did not, or when nothing was stored under that numbering.
         */
        fun advance(asked: Ask, page: Page, known: Long? = null): Step {
            pages++
            val cp = checkpoint
            if (cp != null && cp.validity != page.validity) {
                /*
                 * The numbering changed under us. Whatever this page held was fetched
                 * relative to a dead UID space, and the caller has already stored it —
                 * that is harmless, every message on it is real mail from the folder —
                 * but the bookmark is void and the walk restarts from what the database
                 * knew under the new numbering, or from the top if it knew nothing.
                 */
                val fresh = known?.let { Checkpoint(page.validity, it) }
                checkpoint = fresh
                if (pages >= maxPages) return Step.Done(caughtUp = false)
                return Step.More(Ask(fresh?.uid, pageSize))
            }

            if (asked.after == null) {
                /*
                 * A bootstrap page is the newest by position, so its top is the top of
                 * the folder. Below it is history, and history is the first sync's job,
                 * not this one's: a per-run walk that tried to read a 30,000-message
                 * inbox downward would never finish inside a background budget.
                 *
                 * Unless the rows say otherwise. The first run after the update has no
                 * stored bookmark and a database full of mail; if what it holds stops
                 * below the bottom of this page, the space between is exactly the gap
                 * this whole file exists for, and the walk starts from what the rows
                 * knew rather than pretending the newest page is all there is.
                 */
                val top = page.highest ?: 0L
                val bottom = page.lowest ?: 0L
                if (known != null && known < bottom) {
                    checkpoint = Checkpoint(page.validity, known)
                    if (pages >= maxPages) return Step.Done(caughtUp = false)
                    return Step.More(Ask(known, pageSize))
                }
                checkpoint = Checkpoint(page.validity, maxOf(top, known ?: 0L))
                return Step.Done(caughtUp = true)
            }

            val high = maxOf(cp?.uid ?: 0L, page.highest ?: 0L)
            checkpoint = Checkpoint(page.validity, high)
            if (!page.more) return Step.Done(caughtUp = true)
            if (pages >= maxPages) return Step.Done(caughtUp = false)
            return Step.More(Ask(high, pageSize))
        }
    }
}
