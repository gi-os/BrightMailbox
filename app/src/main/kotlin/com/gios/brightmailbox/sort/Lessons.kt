package com.gios.brightmailbox.sort

/**
 * What one stored message teaches the model, and how much it counts.
 *
 * Pure Kotlin on purpose, like the rest of this package: the labelling policy is the part
 * most likely to be wrong, so it has to be testable on a JVM with no emulator.
 */
data class Lesson(val worthReading: Boolean, val weight: Double)

/**
 * Where a training label comes from.
 *
 * Until v2.60 there was only one answer — `m.pile == LETTER`, the verdict Tier 0 had
 * already reached. Training a model on the output of the rules it sits behind means it
 * can only ever reproduce those rules, however much mail it sees. Measured against
 * labels drawn from a mailbox's Sent folder, the header rules rank this question at
 * about 0.62 AUC and a model trained on what the reader actually did reaches about 0.97.
 * The rules were never the ceiling; they were being copied.
 *
 * So the label now comes from evidence, ordered by how little the app itself could have
 * caused it:
 *
 *  - **Starred** is an explicit act with no default. Nothing in the app stars anything.
 *  - **You have written to this address** comes from the Sent folder, which the sort has
 *    no hand in at all. This is the strongest honest signal available on the phone, and
 *    it is the one that carried the measurement above.
 *  - **Archived without ever being opened** is a rejection, and it is most telling for
 *    mail the rules had already called a Letter.
 *  - **Opened here** is real but weak, and it is the one signal the app contaminates:
 *    Letters are shown first and rationed, so they get opened more because of where they
 *    were put. Weighted accordingly.
 *  - **Tier 0's verdict** stays as the floor, at a weight low enough to yield to any of
 *    the above. A fresh install has no history, and something has to start the model.
 */
object Lessons {

    fun of(
        starred: Boolean,
        wroteToSender: Boolean,
        readHere: Boolean,
        archived: Boolean,
        tier0SaysLetter: Boolean,
    ): Lesson = when {
        starred -> Lesson(true, STARRED)
        wroteToSender -> Lesson(true, CORRESPONDENT)
        archived && !readHere -> Lesson(false, ARCHIVED_UNREAD)
        readHere -> Lesson(true, OPENED)
        else -> Lesson(tier0SaysLetter, BOOTSTRAP)
    }

    /**
     * Deliberately spread far apart rather than nudged.
     *
     * A weight here is "how many plain observations is this worth", and the gap between
     * the bootstrap and the rest is the whole change: one starred message outweighs ten
     * rows that Tier 0 merely guessed at.
     */
    const val STARRED = 4.0
    const val CORRESPONDENT = 3.0
    const val ARCHIVED_UNREAD = 1.5
    const val OPENED = 1.0
    const val BOOTSTRAP = 0.4
}
