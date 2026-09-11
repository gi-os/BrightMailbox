package com.gios.brightmailbox.ui

import androidx.compose.foundation.layout.Arrangement
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gios.brightmailbox.R
import com.gios.brightmailbox.data.Msg
import com.gios.brightmailbox.data.Ration
import com.gios.brightmailbox.ui.theme.LocalGrid
import com.gios.brightmailbox.ui.theme.LocalType
import com.gios.brightmailbox.ui.theme.Screen as Frame
import com.gios.brightmailbox.ui.theme.Secondary
import com.gios.brightmailbox.ui.theme.T
import com.gios.brightmailbox.ui.theme.lightClickable
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
    val waiting by vm.waiting.collectAsStateWithLifecycle()
    val allowed by vm.allowed.collectAsStateWithLifecycle()

    val unlimited = vm.repo.ration == Ration.UNLIMITED
    val visible = vm.visibleLetters(all)

    if (vm.dayDone && all.isNotEmpty()) {
        DayDone(vm, waiting, notices.size)
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
             * LETTERS yields, the count does not.
             *
             * The count used to sit in a fixed five-unit Box so that switching to
             * Unlimited could not reflow the header. Five units is not enough for
             * "12 today" and the word was clipped at every ration — a fixed width is only
             * safe when you know the widest string, and "today" made that false. The
             * label gives way instead, which it can: LETTERS is the one word here that
             * the screen does not need to finish reading.
             */
            T(
                "LETTERS",
                t.subheading,
                maxLines = 1,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(g * 0.5f))
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
            items(visible, key = { it.key }) { m -> LetterRow(m) { vm.open(m) } }

            if (all.isEmpty()) {
                item { T("No letters. Notices below.", t.detail, Secondary) }
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
                        T("${notices.size}", t.detail, Secondary)
                    }
                    Spacer(Modifier.height(g * 0.6f))
                }
                items(notices.take(4), key = { "n" + it.key }) { m ->
                    NoticeRow(m) { vm.open(m) }
                }
                item {
                    T(
                        "see all ${notices.size} →",
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
            left = "WRITE" to { vm.go(Screen.Write()) },
            right = if (unlimited || notices.isNotEmpty()) {
                "NOTICES ${notices.size}" to { vm.go(Screen.Notices) }
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

/** Empty of everything. Heading scale — an empty morning is smaller news than a finished day. */
@Composable
private fun Nothing(vm: MailboxViewModel) {
    val g = LocalGrid.current
    val t = LocalType.current
    Column(Modifier.fillMaxSize()) {
        Spacer(Modifier.height(g * 8f))
        T("Nothing yet.", t.heading)
        Spacer(Modifier.height(g * 0.8f))
        T("Last checked ${clock(vm.repo.lastSync)}.", t.detail, Secondary)
        Spacer(Modifier.weight(1f))
        ActionBar(left = "WRITE" to { vm.go(Screen.Write()) }, right = null)
    }
}

/* ------------------------------------------------------------------------- rows */

/**
 * A Letter: sender at copy, account as a superfine secondary word after it, subject at
 * detail with the time pushed right. Four grid units tall.
 */
@Composable
fun LetterRow(m: Msg, onClick: () -> Unit) {
    val g = LocalGrid.current
    val t = LocalType.current
    Column(Modifier.fillMaxWidth().lightClickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.Bottom) {
            T(m.senderName, t.copy, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(g * 0.45f))
            T(accountWord(m.accountId), t.superfine, Secondary, maxLines = 1)
        }
        Spacer(Modifier.height(g * 0.25f))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            T(m.subject.ifBlank { "(no subject)" }, t.detail, maxLines = 1, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(g * 0.5f))
            T(stamp(m.receivedAt), t.detail, Secondary, maxLines = 1)
        }
    }
}

/**
 * A Notice: one line, sender locked to a fixed column so the eye can run straight down
 * it, subject hard-truncated. No time — none of these are urgent.
 */
@Composable
fun NoticeRow(m: Msg, onClick: () -> Unit) {
    val g = LocalGrid.current
    val t = LocalType.current
    Row(
        Modifier.fillMaxWidth().lightClickable(onClick = onClick),
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
) {
    val g = LocalGrid.current
    val t = LocalType.current
    Row(
        Modifier.fillMaxWidth().height(g.actionBar),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        left?.let { (label, f) -> T(label, t.button, modifier = Modifier.lightClickable(onClick = f), maxLines = 1) }
        middle?.let { (label, f) -> T(label, t.button, Secondary, Modifier.lightClickable(onClick = f), maxLines = 1) }
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
