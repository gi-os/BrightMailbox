package com.gios.brightmailbox.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gios.brightmailbox.R
import com.gios.brightmailbox.data.Msg
import com.gios.brightmailbox.data.Ration
import com.gios.brightmailbox.ui.theme.Content
import com.gios.brightmailbox.ui.theme.LocalGrid
import com.gios.brightmailbox.ui.theme.LocalType
import com.gios.brightmailbox.ui.theme.Screen as Frame
import com.gios.brightmailbox.ui.theme.Secondary
import com.gios.brightmailbox.ui.theme.T
import com.gios.brightmailbox.ui.theme.lightClickable
import com.gios.brightmailbox.ui.theme.lightHoldable
import com.gios.brightmailbox.ui.theme.swipeAway
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Home — the two piles.
 *
 * Not an inbox and it must not read as one. A Letter row puts roughly 2.3x the lit
 * pixels on the panel that a Notice row does, and on a black ground that ratio is what
 * reads as loudness. There is no rule between the sections: a label plus two units of
 * black does the work, which is how the SDK does it (it ships no dividers at all).
 */
@Composable
fun HomeScreen(vm: MailboxViewModel) {
    val g = LocalGrid.current
    val t = LocalType.current

    val all by vm.letters.collectAsStateWithLifecycle()
    val notices by vm.notices.collectAsStateWithLifecycle()
    // The counted total, not `notices.size` — that list stops at 300 rows.
    val noticeTotal by vm.noticeTotal.collectAsStateWithLifecycle()
    val waiting by vm.waiting.collectAsStateWithLifecycle()
    val allowed by vm.allowed.collectAsStateWithLifecycle()

    val unlimited = vm.repo.ration == Ration.UNLIMITED
    val visible = vm.visibleLetters(all)

    /*
     * The finished-day screen is now the empty case only.
     *
     * It used to take over the moment the fifth letter was read, which under v2.14 would
     * throw away the very thing that release is about: the five letters you just read are
     * still on the list, greyed, and replacing them with a screen that says "Five of five"
     * is the same disappearance one level up. When there are rows to show, the same two
     * sentences go under them as a footer instead — see the end of the list below.
     *
     * It still earns its place when there is nothing left to draw: the day's letters read
     * and then archived away, with more waiting for tomorrow.
     */
    if (vm.dayDone && visible.isEmpty() && all.isNotEmpty()) {
        DayDone(vm, waiting, noticeTotal)
        return
    }

    Frame {
        /*
         * One line, not two.
         *
         * There used to be a MAILBOX title bar above a LETTERS / count row. The title
         * said nothing — the app is already open and its name is on the launcher — and
         * the two rows cost six grid units of a 31-unit screen before a single letter.
         * LETTERS, the count and the settings icon share the top bar's height now, so the
         * first row of mail sits that much higher.
         */
        Row(
            Modifier.fillMaxWidth().height(g.topBar),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            /*
             * No label. The first screen of a mail app is its letters; saying so is the
             * same redundancy MAILBOX was, one row further down. What is left is the
             * count, which is the only part that ever changes.
             */
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (unlimited) {
                    T("${all.size} today", t.copy, Secondary, maxLines = 1)
                } else {
                    Row {
                        T("${visible.size}", t.copy, maxLines = 1)
                        T(" of ${Ration.FIVE.perDay}", t.copy, Secondary, maxLines = 1)
                    }
                }
                Spacer(Modifier.width(g * 0.8f))
                /*
                 * Refresh, then settings. Sync already happens on open and every fifteen
                 * minutes, so this is for the moment you are waiting on something and
                 * would otherwise leave and come back to force it.
                 *
                 * It greys while a sync runs rather than spinning: there is no spinner
                 * anywhere in this app, and a second tap during a sync is ignored by
                 * syncNow() regardless.
                 */
                val busy by vm.busy.collectAsStateWithLifecycle()
                androidx.compose.foundation.Image(
                    painter = painterResource(R.drawable.ic_refresh_white),
                    contentDescription = "Check for mail",
                    contentScale = ContentScale.Fit,
                    alpha = if (busy) 0.4f else 1f,
                    modifier = Modifier.size(g.icon).lightClickable(enabled = !busy) {
                        vm.syncNow()
                    },
                )
                Spacer(Modifier.width(g * 0.8f))
                androidx.compose.foundation.Image(
                    painter = painterResource(R.drawable.ic_settings_white),
                    contentDescription = "Settings",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(g.icon)
                        .lightClickable { vm.go(Screen.Settings) },
                )
            }
        }

        Spacer(Modifier.height(g * 1.1f))

        /*
         * "Nothing yet" means nothing at all — letters AND notices.
         *
         * This used to be `if (all.isEmpty())`, and `letters()` is
         * `pile='LETTER' AND NOT archived AND NOT readHere`. So the moment you had read
         * your letters, Home took this early return and drew a screen whose action bar is
         * WRITE and nothing else — no notice rows, no NOTICES button, and that button is
         * the only route to Screen.Notices there has ever been. Mail was fetched, sorted,
         * stored, and unreachable.
         *
         * That is the bug behind the first field report this app got ("a notice came in
         * and it isn't populating even after refreshing"). The sync was fine. The report
         * even said so — `last sync: 0 min ago` with no error beside it.
         */
        if (all.isEmpty() && notices.isEmpty()) {
            Nothing(vm)
            return@Frame
        }

        LazyColumn(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(g * 1.1f),
        ) {
            items(visible, key = { it.key }) { m ->
                LetterRow(
                    m,
                    onClick = { vm.open(m) },
                    onHold = { vm.star(m) },
                    onSwipe = { vm.archiveHere(m) },
                )
            }

            if (all.isEmpty()) {
                item { T("No letters. Notices below.", t.detail, Secondary) }
            }

            /*
             * The day's close, as a footer rather than a screen.
             *
             * Same two facts the takeover carried — that there were five, and what happens
             * next — under the five greyed rows that prove it, which is more than the
             * takeover could say because the takeover had to hide them to say it.
             */
            if (vm.dayDone) {
                item {
                    Spacer(Modifier.height(g * 0.6f))
                    T(
                        if (waiting > 0) "Five of five. More tomorrow at 7am."
                        else "Five of five. Nothing else waiting.",
                        t.detail,
                        Secondary,
                    )
                    Spacer(Modifier.height(g * 0.5f))
                    // A line, not a button. It should not sit there tempting you.
                    T(
                        "Hold the wheel to read a sixth",
                        t.detail,
                        Secondary,
                        Modifier.fillMaxWidth().lightClickable { vm.unlockOneMore() },
                        maxLines = 1,
                    )
                }
            }

            /*
             * Unlimited normally puts notices behind the bar button so letters fill the
             * screen. `all.isEmpty()` is the exception: with no letters to fill it, that
             * rule leaves a blank page above a button, which is what "it isn't
             * populating" looked like.
             */
            if ((!unlimited || all.isEmpty()) && notices.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(g * 1.1f))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        T("NOTICES", t.detail, Secondary)
                        T("$noticeTotal", t.detail, Secondary)
                    }
                    Spacer(Modifier.height(g * 0.6f))
                }
                items(notices.take(4), key = { "n" + it.key }) { m ->
                    NoticeRow(
                        m,
                        onClick = { vm.open(m) },
                        onHold = { vm.star(m) },
                        onSwipe = { vm.archiveHere(m) },
                    )
                }
                item {
                    T(
                        "see all $noticeTotal →",
                        t.detail,
                        Secondary,
                        Modifier
                            .fillMaxWidth()
                            .lightClickable { vm.go(Screen.Notices) }
                            .padding(top = g * 0.3f),
                        maxLines = 1,
                    )
                }
            }
        }

        /*
         * The badge counts every notice, not the unread ones.
         *
         * `noticeCount` is `… AND unread`, so a notice that arrived already read on the
         * server — which is most of them, if you have the mailbox open anywhere else —
         * made the button read "NOTICES 0" over a list with a dozen things in it. A route
         * that says zero is a route nobody takes.
         */
        ActionBar(
            left = null,
            leftIcon = Triple(R.drawable.ic_send_white, "Write") { vm.go(Screen.Write()) },
            /*
             * Clear today's letters in one go — the ones on screen, not the mailbox.
             * Hidden when there is nothing to clear, because a bulk action over an empty
             * list is a control that can only disappoint.
             */
            middleStacked = if (visible.isEmpty()) null else {
                Triple(R.drawable.ic_archive_white, "ALL") { vm.archiveThese(visible) }
            },
            right = if (unlimited || notices.isNotEmpty()) {
                "NOTICES $noticeTotal" to { vm.go(Screen.Notices) }
            } else {
                "MARK ALL READ" to { vm.markAllNoticesRead() }
            },
        )
    }
}

/**
 * Day done. Variant C from the design board.
 *
 * Left-aligned to the same x as every other screen, no bars, and one fact about what
 * happens next instead of a count. A tells the user nothing about tomorrow and B is
 * still an inbox with a hole in it; this is the only one that reads as a closed mail
 * slot rather than an empty list. There is deliberately no button here — the whole point
 * is that there is nothing to do.
 */
@Composable
private fun DayDone(vm: MailboxViewModel, waiting: Int, notices: Int) {
    val g = LocalGrid.current
    val t = LocalType.current
    Frame {
        Spacer(Modifier.height(g.topBar))
        Spacer(Modifier.height(g * 9f))
        T("Five of five.", t.title)
        Spacer(Modifier.height(g * 1f))
        T(
            if (waiting > 0) "More tomorrow at 7am." else "Nothing else waiting.",
            t.detail,
            Secondary,
        )
        Spacer(Modifier.weight(1f))
        // The override is a line, not a button. It should not sit there tempting you.
        T(
            "Hold the wheel to read a sixth",
            t.detail,
            Secondary,
            Modifier
                .fillMaxWidth()
                .lightClickable { vm.unlockOneMore() }
                .padding(bottom = g * 1.7f),
        )
        /*
         * The day being done says nothing about the notices, and this screen used to be
         * another dead end: no bar, no route, and the receipts still sitting there. The
         * point of the screen is that there is nothing left to DO, not that there is
         * nothing left to see.
         */
        if (notices > 0) {
            ActionBar(left = null, right = "NOTICES $notices" to { vm.go(Screen.Notices) })
        }
    }
}

/**
 * Empty of everything.
 *
 * The most-looked-at screen in the app and until now the least designed: two lines in the
 * top-left corner of a black rectangle, which reads as a screen that failed to load rather
 * than one with nothing on it. An empty mailbox is the good outcome and should look like a
 * finished thing.
 *
 * So: the fact at title scale, sat at the optical centre rather than jammed under the bar,
 * with a rule and the state of the machine under it — when it last looked, whether
 * anything is waiting for tomorrow, and, when it could not look at all, why. That last
 * line is the one that matters. An unreachable mailbox used to produce this exact screen,
 * silently, which is how "it isn't populating" happens.
 *
 * Title scale, not heading, because on this panel a short sentence at 115 design px IS the
 * design — it is what the SDK does with a screen that has one thing to say.
 */
@Composable
private fun Nothing(vm: MailboxViewModel) {
    val g = LocalGrid.current
    val t = LocalType.current
    val waiting by vm.waiting.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error = vm.repo.lastError

    Column(Modifier.fillMaxSize()) {
        // A third of the way down rather than centred: optically centred text sits above
        // the true middle, and the action bar takes four units off the bottom anyway.
        Spacer(Modifier.height(g * 6f))

        T(if (error != null) "Can't reach\nyour mail." else "Nothing\nyet.", t.title)

        Spacer(Modifier.height(g * 1.6f))
        Box(Modifier.width(g * 6f).height(2.dp).background(Secondary))
        Spacer(Modifier.height(g * 1.2f))

        /*
         * The state of the machine, one fact per line, quietest first. Every line is
         * conditional — a screen that says "0 waiting" and "no errors" to say nothing is
         * worse than a screen with one line on it.
         */
        T(
            when {
                busy -> "Looking now…"
                else -> "Last looked ${clock(vm.repo.lastSync)}."
            },
            t.detail,
            Secondary,
        )
        if (error != null) {
            Spacer(Modifier.height(g * 0.5f))
            T(error, t.detail, maxLines = 3)
        }
        if (waiting > 0) {
            Spacer(Modifier.height(g * 0.5f))
            T(
                if (waiting == 1) "1 letter waiting for tomorrow."
                else "$waiting letters waiting for tomorrow.",
                t.detail,
                Secondary,
            )
        }

        Spacer(Modifier.weight(1f))
        /*
         * CHECK NOW earns the second slot here and nowhere else. On a screen with mail on
         * it the refresh icon in the header is enough; on a screen with nothing on it,
         * "check again" is the only thing anybody wants to do, and hunting for an icon to
         * do it is how you end up believing the app is broken.
         */
        ActionBar(
            left = null,
            leftIcon = Triple(R.drawable.ic_send_white, "Write") { vm.go(Screen.Write()) },
            right = if (busy) null else "CHECK NOW" to { vm.syncNow() },
        )
    }
}

/* ------------------------------------------------------------------------- rows */

/**
 * A Letter: sender at copy, account as a superfine secondary word after it, subject at
 * detail with the time pushed right. Four grid units tall.
 *
 * **Read is a shade, not a disappearance.** A letter that has been opened drops from
 * white to [Secondary] and stays exactly where it was until the day turns. There is no
 * other state to draw: the row does not move, resize, indent or gain a rule, because the
 * one thing the list has to keep is a stable place for every letter — a row that jumps
 * when you come back from reading it is a row you have to find again.
 *
 * Grey rather than dimmed white for the reason in the theme header: alpha on a matte
 * monochrome LCD dithers into a texture, a flat grey does not.
 */
@Composable
fun LetterRow(
    m: Msg,
    onClick: () -> Unit,
    onHold: () -> Unit = {},
    onSwipe: (() -> Unit)? = null,
) {
    val g = LocalGrid.current
    val t = LocalType.current
    val ink = if (m.readHere) Secondary else Content
    SwipeRow(onSwipe) { rowModifier ->
    Column(
        rowModifier.fillMaxWidth().lightHoldable(onLongClick = onHold, onClick = onClick),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            T(
                m.senderName,
                t.copy,
                ink,
                maxLines = 1,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(g * 0.45f))
            T(accountWord(m.accountId), t.superfine, Secondary, maxLines = 1)
            if (m.starred) {
                Spacer(Modifier.weight(1f))
                Star()
            }
        }
        Spacer(Modifier.height(g * 0.25f))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            T(
                m.subject.ifBlank { "(no subject)" },
                t.detail,
                ink,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(g * 0.5f))
            T(stamp(m.receivedAt), t.detail, Secondary, maxLines = 1)
        }
    }
    }
}

/**
 * A row you can push off the left edge, with the word for what that does behind it.
 *
 * The label is underneath and the row paints its own black over it, so the word is
 * uncovered by the row moving rather than faded in — the row is the shutter. That is also
 * why the background belongs *inside* the swipe: a background applied outside the offset
 * stays where it was and you get a black bar sitting still while its contents slide out
 * from under it.
 *
 * With no [onSwipe] this is nothing at all — not a disabled gesture, just the row.
 */
@Composable
private fun SwipeRow(
    onSwipe: (() -> Unit)?,
    content: @Composable (Modifier) -> Unit,
) {
    if (onSwipe == null) {
        content(Modifier)
        return
    }
    val g = LocalGrid.current
    val t = LocalType.current
    androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth()) {
        T(
            "ARCHIVE",
            t.detail,
            Secondary,
            Modifier.align(Alignment.CenterEnd),
            maxLines = 1,
        )
        content(
            Modifier
                .swipeAway(threshold = g * 5f, onSwiped = onSwipe)
                .background(com.gios.brightmailbox.ui.theme.Background),
        )
    }
}

/**
 * A Notice: one line, sender locked to a fixed column so the eye can run straight down
 * it, subject hard-truncated. No time — none of these are urgent.
 */
@Composable
fun NoticeRow(
    m: Msg,
    onClick: () -> Unit,
    onHold: () -> Unit = {},
    onSwipe: (() -> Unit)? = null,
) {
    val g = LocalGrid.current
    val t = LocalType.current
    SwipeRow(onSwipe) { rowModifier ->
    Row(
        rowModifier.fillMaxWidth().lightHoldable(onLongClick = onHold, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        T(m.senderName, t.detail, Secondary, Modifier.width(g * 6.5f), maxLines = 1)
        Spacer(Modifier.width(g * 0.6f))
        T(
            com.gios.brightmailbox.text.Clean.noticeLine(m.subject),
            t.detail,
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        if (m.starred) {
            Spacer(Modifier.width(g * 0.4f))
            Star()
        }
    }
    }
}

/**
 * The held mark.
 *
 * Deliberately small — 0.7 of a grid unit, about two thirds the height of the word beside
 * it. It is a state, not a control: there is nothing to tap here, and a mark drawn at
 * icon size would read as a button that does not work.
 */
@Composable
private fun Star() {
    val g = LocalGrid.current
    androidx.compose.foundation.Image(
        painter = painterResource(R.drawable.ic_star_white),
        contentDescription = "Held",
        contentScale = ContentScale.Fit,
        modifier = Modifier.size(g * 0.7f),
    )
}

/* ------------------------------------------------------------------------- toast */

/**
 * What the app just said.
 *
 * This is new in v2.14, and it should have existed since v2.0: `MailboxViewModel.said()`
 * has always written to a StateFlow that **nothing collected**. Every sentence the app
 * tried to say went into it and stopped there — "Sent.", "3 new.", "Nothing new.", the
 * per-account failure line added specifically so a sync that cannot reach a mailbox says
 * so, and the sign-in errors from the QR scanner. The work to produce those sentences was
 * done and correct; there was no surface, so a refresh that failed looked exactly like a
 * refresh that found nothing, which is the complaint that prompted writing them.
 *
 * It clears itself after a few seconds. A line that has to be dismissed is a dialog, and
 * a dialog for "Held." would be worse than saying nothing.
 */
@Composable
fun Said(vm: MailboxViewModel, modifier: Modifier = Modifier) {
    val g = LocalGrid.current
    val t = LocalType.current
    val message by vm.toast.collectAsStateWithLifecycle()

    // Keyed on the text, so a second message restarts the clock rather than inheriting
    // the tail of the first one's.
    androidx.compose.runtime.LaunchedEffect(message) {
        if (message != null) {
            kotlinx.coroutines.delay(2600)
            vm.said(null)
        }
    }

    val text = message ?: return
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = g.inset)
            .background(com.gios.brightmailbox.ui.theme.Background)
            .padding(vertical = g * 0.4f),
    ) {
        T(text, t.detail, maxLines = 2, modifier = Modifier.weight(1f))
    }
}

/* ------------------------------------------------------------------------- bars */

@Composable
fun TopBar(title: String = "MAILBOX", onSettings: (() -> Unit)? = null) {
    val g = LocalGrid.current
    val t = LocalType.current
    Row(
        Modifier.fillMaxWidth().height(g.topBar),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        T(title, t.subheading, maxLines = 1)
        if (onSettings != null) {
            androidx.compose.foundation.Image(
                painter = painterResource(R.drawable.ic_settings_white),
                contentDescription = "Settings",
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(g.icon).lightClickable(onClick = onSettings),
            )
        }
    }
}

/**
 * The action bar. LightOS allows at most three items, and at most three when any item
 * carries text — which is every bar in this app.
 */
@Composable
fun ActionBar(
    left: Pair<String, () -> Unit>?,
    right: Pair<String, () -> Unit>? = null,
    middle: Pair<String, () -> Unit>? = null,
    /**
     * An icon in the left slot instead of a word: drawable, description, action.
     *
     * Takes precedence over [left] when both are given. The SDK counts a bar with any
     * text in it as a three-item bar, so trading a word for a glyph is also what buys
     * room on the right — WRITE at `button` tracking was the widest thing on the bar.
     */
    leftIcon: Triple<Int, String, () -> Unit>? = null,
    /**
     * An icon with a word under it: drawable, caption, action.
     *
     * The stacked form exists because "ARCHIVE ALL" spelled out at `button` tracking is
     * most of a 27-unit row on its own, and this bar already carries two other things.
     * The icon says what happens and the caption says how much — which reads faster than
     * the sentence did, in a third of the width.
     */
    middleStacked: Triple<Int, String, () -> Unit>? = null,
) {
    val g = LocalGrid.current
    val t = LocalType.current
    Row(
        Modifier.fillMaxWidth().height(g.actionBar),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leftIcon != null) {
            val (drawable, description, f) = leftIcon
            androidx.compose.foundation.Image(
                painter = painterResource(drawable),
                contentDescription = description,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(g.icon).lightClickable(onClick = f),
            )
        } else {
            left?.let { (label, f) -> T(label, t.button, modifier = Modifier.lightClickable(onClick = f), maxLines = 1) }
        }
        middle?.let { (label, f) -> T(label, t.button, Secondary, Modifier.lightClickable(onClick = f), maxLines = 1) }
        middleStacked?.let { (drawable, caption, f) ->
            Column(
                Modifier.lightClickable(onClick = f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                androidx.compose.foundation.Image(
                    painter = painterResource(drawable),
                    contentDescription = caption,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(g.icon * 0.85f),
                )
                T(caption, t.superfine, Secondary, maxLines = 1)
            }
        }
        right?.let { (label, f) -> T(label, t.button, Secondary, Modifier.lightClickable(onClick = f), maxLines = 1) }
    }
}

/* ----------------------------------------------------------------------- format */

/** "9:12a" today, "Mon" this week, "3 Sep" beyond. A date column of noise helps nobody. */
fun stamp(at: Long): String {
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = at }
    return when {
        now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
            now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR) ->
            SimpleDateFormat("h:mma", Locale.getDefault()).format(Date(at))
                .lowercase().removeSuffix("m")
        now.timeInMillis - at < 6L * 24 * 3600 * 1000 ->
            SimpleDateFormat("EEE", Locale.getDefault()).format(Date(at))
        else -> SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(at))
    }
}

fun clock(at: Long): String =
    if (at == 0L) "never" else SimpleDateFormat("h:mma", Locale.getDefault())
        .format(Date(at)).lowercase()

/**
 * What to call the mailbox a message came to.
 *
 * The user's name for it when there is one, and the provider otherwise. Read from a
 * composition local rather than passed down because the three places that need it — a
 * Letter row, the reader's header, the line above a reply — are at three different depths,
 * and none of them has any other reason to know about accounts.
 *
 * `compositionLocalOf`, not `staticCompositionLocalOf`: a rename has to repaint the rows
 * that show it, and a static local does not invalidate its readers.
 */
val LocalAccountWords = compositionLocalOf { emptyMap<String, String>() }

@Composable
fun accountWord(accountId: String): String =
    LocalAccountWords.current[accountId] ?: providerWord(accountId)

/** "google:gio@x.com" -> "gmail". The fallback, and what an unnamed account shows. */
fun providerWord(accountId: String): String =
    when (accountId.substringBefore(':')) {
        "google" -> "gmail"
        "microsoft" -> "outlook"
        else -> ""
    }
