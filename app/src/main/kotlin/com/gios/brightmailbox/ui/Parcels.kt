package com.gios.brightmailbox.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gios.brightmailbox.hw.WheelScroll
import com.gios.brightmailbox.parcel.Live
import com.gios.brightmailbox.parcel.Parcels
import com.gios.brightmailbox.ui.theme.LocalGrid
import com.gios.brightmailbox.ui.theme.LocalType
import com.gios.brightmailbox.ui.theme.Screen as Frame
import com.gios.brightmailbox.ui.theme.Secondary
import com.gios.brightmailbox.ui.theme.T
import com.gios.brightmailbox.ui.theme.lightHoldable
import org.json.JSONTokener

/**
 * What is on its way.
 *
 * One row per tracking number, not per email: a shop mails "shipped", the carrier mails
 * "out for delivery" and "delivered", and the newest of those is the truth about the
 * parcel. The merge happens in [com.gios.brightmailbox.data.Repo.scanParcels]; this draws
 * the result.
 *
 * Deliberately silent. In this app a shipping email is a Notice, and Notices never make a
 * sound — being told a parcel is coming is worth a notification, and the email announcing
 * it is not, which is the whole reason a second app exists. Reading the list here is the
 * quiet version: it answers "is anything arriving" without ever interrupting.
 *
 * A tap hands the carrier's own tracking page to whatever opens links, the same as a link
 * in a message — see [open]. A hold reads that page here instead, in a hidden browser, and
 * puts the carrier's own status under the row: the mail says what it was told and when,
 * and this says what the carrier says now. Neither replaces the other.
 *
 * No screen here tries to draw a map of a parcel's journey, because the carrier already
 * has one and it is better.
 */
@Composable
fun ParcelsScreen(vm: MailboxViewModel) {
    val g = LocalGrid.current
    val t = LocalType.current
    val parcels by vm.parcels.collectAsStateWithLifecycle()
    val context = LocalContext.current

    /*
     * Live tracking, and the rules it lives under.
     *
     * The list itself is free: it is read out of mail already on the phone and talks to
     * nobody. This is the one thing on the screen that goes to the network, it happens
     * only when a row is held, and it stops when the screen does. No polling, no
     * background refresh, nothing kept — hold a row, get an answer, and the answer lives
     * as long as the screen is open.
     *
     * A WebView because plain HTTP cannot get through: UPS, FedEx, USPS and DHL all answer
     * an ordinary client with a bot wall before they look at the number. A WebView is a
     * real browser with a real fingerprint. See [Live] for what is done with the text.
     */
    var live by remember { mutableStateOf<Map<String, Reading>>(emptyMap()) }
    var asking by remember { mutableStateOf<Parcels.Parcel?>(null) }
    var attempt by remember { mutableStateOf(0) }

    /*
     * Read on the way in, and asked for when the answer is nothing.
     *
     * An empty list means one of two things and they look identical from here: nothing is
     * coming, or the mail that says otherwise is not on this phone. The cheap scan cannot
     * tell them apart — which is why the first empty look of a session pays for the search,
     * and only the first.
     */
    LaunchedEffect(Unit) { if (parcels.isEmpty()) vm.sweepOnce() else vm.scanParcels() }

    Frame {
        Row(
            Modifier.fillMaxWidth().height(g.topBar),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            T("PARCELS", t.subheading)
            T("${parcels.size}", t.detail, Secondary)
        }

        if (parcels.isEmpty()) {
            Spacer(Modifier.height(g * 3f))
            T("Nothing on its way.", t.copy)
            Spacer(Modifier.height(g * 0.6f))
            T(
                "A parcel appears here once a shop or a carrier has mailed about it — the " +
                    "tracking number is read out of that message and never leaves the phone. " +
                    "SEARCH AGAIN asks the server, archive included, for mail this phone has " +
                    "not read yet.",
                t.detail,
                Secondary,
            )
            Spacer(Modifier.weight(1f))
        } else {
            val list = rememberLazyListState()
            WheelScroll(list)
            LazyColumn(
                Modifier.weight(1f),
                state = list,
                verticalArrangement = Arrangement.spacedBy(g * 0.9f),
            ) {
                items(parcels, key = { it.id }) { p ->
                    ParcelRow(
                        p,
                        reading = live[p.id],
                        onOpen = { open(context, it) },
                        onRead = {
                            live = live + (p.id to Reading.Looking)
                            asking = p
                            attempt++
                        },
                    )
                    Spacer(Modifier.height(g * 0.45f))
                }
                item {
                    Spacer(Modifier.height(g * 0.6f))
                    T("Hold a parcel to read the carrier's page.", t.superfine, Secondary)
                }
            }
        }

        /*
         * The hidden browser, alive only while a reading is running and keyed by the
         * attempt so that holding the same row twice loads the page again rather than
         * re-showing what the last one saw.
         */
        val target = asking
        val targetUrl = target?.url
        if (target != null && targetUrl != null) {
            androidx.compose.runtime.key(attempt) {
                AndroidView(
                    modifier = Modifier.size(1.dp),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView, page: String?) {
                                    /*
                                     * The status arrives from the page's own JavaScript
                                     * after the page has finished, so reading on the
                                     * finished event reads an empty shell. UPS holds a
                                     * bot-check interstitial for about five seconds before
                                     * the real page appears at all.
                                     *
                                     * Two looks, and the second is skipped once the first
                                     * has an answer.
                                     */
                                    for (wait in listOf(6_000L, 14_000L)) {
                                        view.postDelayed({
                                            if (live[target.id] !is Reading.Read) {
                                                pageText(view) { text ->
                                                    val status = text
                                                        ?.let { Live.read(target.number, it) }
                                                    live = live + (
                                                        target.id to (
                                                            status?.let { Reading.Read(it) }
                                                                ?: Reading.Unreadable
                                                            )
                                                        )
                                                }
                                            }
                                        }, wait)
                                    }
                                    // Give up out loud rather than leaving a row saying
                                    // "reading…" for the rest of the session.
                                    view.postDelayed({
                                        if (live[target.id] is Reading.Looking) {
                                            live = live + (target.id to Reading.Unreadable)
                                        }
                                        asking = null
                                    }, 18_000L)
                                }
                            }
                            loadUrl(targetUrl)
                        }
                    },
                )
            }
        }

        /*
         * The refresh. Also the whole of the old SEARCH AGAIN, which is the better name for
         * what it does: searches the archive on the server for shipping mail, stores what it
         * finds as real messages, then reads the list again. A work bar sweeps at the bottom
         * while it runs.
         */
        ActionBar(
            left = "BACK" to { vm.go(Screen.Menu) },
            right = "REFRESH" to { vm.sweepParcels() },
        )
    }
}

/**
 * What a live reading of a carrier's page is doing, for one parcel.
 *
 * [Unreadable] is a real outcome, not an error to hide. The bot walls do sometimes win,
 * pages get redesigned, and a number can be too new for the carrier to know it — and in
 * every one of those cases the honest screen is one that says so and offers the page,
 * rather than one that silently shows what the email said and lets you believe it came
 * from the carrier.
 */
private sealed interface Reading {
    data object Looking : Reading
    data class Read(val status: Live.Status) : Reading
    data object Unreadable : Reading
}

/**
 * The shop leads, because that is what the parcel is *of* — "Amazon.com" says more than
 * "USPS" when both are true. A carrier's own notice has no shop on it, so the carrier
 * leads there instead. See `Parcels.merchantOf`.
 *
 * State sits opposite in words a person would use ("out for delivery"). The head of the row
 * is the item when a mail named one and the shop otherwise, with the shop, carrier, number
 * and ETA underneath — the number is the one thing on this screen nobody can read at a
 * glance, so it is the smallest line.
 *
 * A live reading, when one has been asked for, goes under all of that in white: the mail's
 * own word for the state stays where it is, and the carrier's word sits below it. Both are
 * true and they are true at different times, so neither replaces the other.
 */
@Composable
private fun ParcelRow(
    p: Parcels.Parcel,
    reading: Reading?,
    onOpen: (String) -> Unit,
    onRead: () -> Unit,
) {
    val g = LocalGrid.current
    val t = LocalType.current
    Column(
        Modifier
            .fillMaxWidth()
            // Tap goes to the carrier; hold reads the page here. The hold is the heavier
            // gesture and the one that reaches the network, which is the right way round.
            .lightHoldable(
                enabled = p.url != null,
                onLongClick = { if (p.url != null) onRead() },
                onClick = { p.url?.let(onOpen) },
            )
            .padding(vertical = g * 0.35f),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            /*
             * The item first, because that is the thing you are waiting for, and the shop
             * only because most mail names the shop and not the thing. Nothing here guesses
             * an item out of a body: when a mail does not name one, this is the shop's name,
             * which is what it always was.
             */
            val head = p.item ?: p.merchant ?: p.carrier.label
            T(head, t.button, maxLines = 1)
            T(stateWord(p.state), t.detail, Secondary, maxLines = 1)
        }
        Spacer(Modifier.height(g * 0.15f))
        T(
            listOfNotNull(
                p.merchant.takeIf { p.item != null },
                p.carrier.label.takeIf { it != (p.item ?: p.merchant ?: p.carrier.label) },
                p.number,
                p.eta,
            ).joinToString(" · "),
            t.superfine,
            Secondary,
            maxLines = 1,
        )

        when (reading) {
            null -> Unit
            Reading.Looking -> {
                Spacer(Modifier.height(g * 0.3f))
                T("reading " + hostOf(p.url) + "…", t.superfine, Secondary, maxLines = 1)
            }
            is Reading.Read -> {
                Spacer(Modifier.height(g * 0.3f))
                T(reading.status.headline, t.detail, maxLines = 1)
                for ((label, value) in reading.status.facts) {
                    T("$label · $value", t.superfine, Secondary, maxLines = 1)
                }
            }
            Reading.Unreadable -> {
                Spacer(Modifier.height(g * 0.3f))
                // What to do about it, not just that it happened.
                T("Could not read that page. Tap to open it.", t.superfine, Secondary)
            }
        }
    }
}

/** Just the host, for saying which page is being read without printing a URL. */
private fun hostOf(url: String?): String =
    runCatching { Uri.parse(url).host?.removePrefix("www.") }.getOrNull() ?: "the carrier"

/**
 * What the page says once its JavaScript has run.
 *
 * `innerText`, so it is what a person would read rather than markup. The value arrives as
 * a JSON string, which is why it goes back through a tokenizer rather than being used as
 * it lands.
 *
 * Nothing is logged. While this was a probe it printed the page to logcat, which was the
 * fastest way to see what carriers send — and is exactly the wrong thing to leave in a
 * shipping feature, where the page is about somebody's parcel and their address is on it.
 */
private fun pageText(view: WebView, done: (String?) -> Unit) {
    runCatching {
        view.evaluateJavascript("document.body ? document.body.innerText : ''") { raw ->
            val text = runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull()
            done(text?.takeIf { it.isNotBlank() })
        }
    }
}

/**
 * The carrier's page, in whatever the phone opens links with.
 *
 * Same intent the reader uses for a link in a body — and for the same reason there is no
 * in-app browser: a tracking page is a web page, and this app is a mailbox.
 */
private fun open(context: Context, url: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/** A state in the words a person would use, not the words the enum uses. */
internal fun stateWord(state: Parcels.State): String = when (state) {
    Parcels.State.UNKNOWN -> "label created"
    Parcels.State.SHIPPED -> "on its way"
    Parcels.State.OUT_FOR_DELIVERY -> "out for delivery"
    Parcels.State.DELAYED -> "delayed"
    Parcels.State.DELIVERED -> "delivered"
}

/**
 * The menu row's second line, in the shape DRAFTS already uses: what is in flight, then
 * what is closest. "3 parcels" tells nobody whether to expect a knock.
 */
internal fun parcelSummary(parcels: List<Parcels.Parcel>): String {
    val here = parcels.count { it.state == Parcels.State.OUT_FOR_DELIVERY }
    val away = parcels.size - here
    val soon = when (here) {
        0 -> null
        1 -> "1 out for delivery"
        else -> "$here out for delivery"
    }
    val rest = when (away) {
        0 -> null
        1 -> "1 on its way"
        else -> "$away on their way"
    }
    val line = listOfNotNull(soon, rest).joinToString(", ")
    return line.replaceFirstChar { it.uppercase() } + "."
}
