package com.gios.brightmailbox.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
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
 * Every row is a tap that hands the carrier's own tracking page to whatever opens links,
 * the same as a link in a message — see [open]. No screen here tries to draw a map of a
 * parcel's journey, because the carrier already has one and it is better.
 */
@Composable
fun ParcelsScreen(vm: MailboxViewModel) {
    val g = LocalGrid.current
    val t = LocalType.current
    val parcels by vm.parcels.collectAsStateWithLifecycle()
    val context = LocalContext.current

    /*
     * The live-tracking probe.
     *
     * Holding a parcel row loads the carrier's page in a hidden WebView and puts what the
     * page ends up saying on screen. It exists because a plain HTTP client cannot get past
     * the carriers' bot walls — all four answer an OkHttp request with Access Denied or
     * "tracking attempt has been blocked" — while a WebView is a real browser with a real
     * fingerprint, so it is the one client that might. This build answers one question: does
     * it get through, and if so what does the page look like? It is not a feature yet, and
     * it is the only thing in this app that fetches on its own.
     */
    var probing by remember { mutableStateOf<String?>(null) }
    var probeText by remember { mutableStateOf<String?>(null) }
    var probeN by remember { mutableStateOf(0) }

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
                        onOpen = { open(context, it) },
                        onProbe = { u ->
                            probeText = null
                            probing = u
                            probeN++
                        },
                    )
                    Spacer(Modifier.height(g * 0.45f))
                }
            }
        }

        /*
         * The hidden browser, alive only while a probe is running, and keyed by the probe
         * count so that holding the same row twice loads the page twice rather than
         * re-showing what the first one saw.
         */
        val url = probing
        if (url != null) {
            androidx.compose.runtime.key(probeN) {
                AndroidView(
                    modifier = Modifier.size(1.dp),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView, page: String?) {
                                    /*
                                     * The status arrives from the page's own JavaScript after
                                     * the page has finished, so reading on the finished event
                                     * reads an empty shell. Two looks, because these pages
                                     * take their time, and the second one is discarded when
                                     * the first already had something to say.
                                     */
                                    for (wait in listOf(8_000L, 20_000L)) {
                                        view.postDelayed({ read(view, url) { probeText = it } }, wait)
                                    }
                                    view.postDelayed({ probing = null }, 26_000L)
                                }
                            }
                            loadUrl(url)
                        }
                    },
                )
            }
        }

        val said = probeText
        if (said != null) {
            Spacer(Modifier.height(g * 0.8f))
            T("LIVE PROBE", t.detail, Secondary)
            Spacer(Modifier.height(g * 0.3f))
            T(said, t.superfine)
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
 * The shop leads, because that is what the parcel is *of* — "Amazon.com" says more than
 * "USPS" when both are true. A carrier's own notice has no shop on it, so the carrier
 * leads there instead. See `Parcels.merchantOf`.
 *
 * State sits opposite in words a person would use ("out for delivery"). The head of the row
 * is the item when a mail named one and the shop otherwise, with the shop, carrier, number
 * and ETA underneath — the number is the one thing on this screen nobody can read at a
 * glance, so it is the smallest line.
 */
@Composable
private fun ParcelRow(p: Parcels.Parcel, onOpen: (String) -> Unit, onProbe: (String) -> Unit) {
    val g = LocalGrid.current
    val t = LocalType.current
    Column(
        Modifier
            .fillMaxWidth()
            // Tap goes to the carrier; hold runs the live probe. The hold is the heavier
            // gesture and the one that reaches the network, which is the right way round.
            .lightHoldable(
                enabled = p.url != null,
                onLongClick = { p.url?.let(onProbe) },
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
    }
}

/**
 * What the page says once its JavaScript has run.
 *
 * `innerText`, so it is what a person would read rather than markup, and truncated — this is
 * a probe and the first screenful is the answer. The value arrives as a JSON string, which
 * is why it goes back through a tokenizer rather than being used as it lands. Logged as well
 * as shown, so `adb logcat -s parcelprobe` is an alternative to reading a small screen.
 */
private fun read(view: WebView, url: String, done: (String) -> Unit) {
    runCatching {
        view.evaluateJavascript("document.body ? document.body.innerText : ''") { raw ->
            val text = runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull()
            val said = text?.replace(Regex("""\n{2,}"""), "\n")?.trim()
            Log.i("parcelprobe", if (said.isNullOrBlank()) "EMPTY $url" else "SAID $url\n$said")
            done(said?.takeIf { it.isNotBlank() }?.take(1200) ?: "(the page said nothing at all)")
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
