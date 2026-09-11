package com.gios.brightmailbox.ui

import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gios.brightmailbox.R
import com.gios.brightmailbox.data.Msg
import com.gios.brightmailbox.sort.Pile
import com.gios.brightmailbox.ui.theme.LocalGrid
import com.gios.brightmailbox.ui.theme.LocalType
import com.gios.brightmailbox.ui.theme.Screen as Frame
import com.gios.brightmailbox.ui.theme.Secondary
import com.gios.brightmailbox.ui.theme.T
import com.gios.brightmailbox.ui.theme.lightClickable
import com.gios.brightmailbox.ui.theme.readerLeading
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One Letter, set as a page.
 *
 * The masthead rhythm is the design: sender at heading two units down, time at detail
 * secondary half a unit under it, subject at copy 1.5 units below that, then THREE clear
 * units before the first body line. That gap is what makes it read as a page instead of
 * a header, and it is the first thing to go wrong if anyone tightens the layout.
 *
 * Leading is locked to two grid units by [readerLeading] so every line lands on shared
 * baselines and a wheel notch moves a whole number of lines.
 */
@Composable
fun ReaderScreen(vm: MailboxViewModel, msg: Msg) {
    val g = LocalGrid.current
    val t = LocalType.current
    val context = LocalContext.current
    val body by vm.body.collectAsStateWithLifecycle()
    var showWhy by remember { mutableStateOf(false) }

    Frame {
        Row(
            Modifier.fillMaxWidth().height(g.topBar),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                painter = painterResource(R.drawable.ic_back_white),
                contentDescription = "Back",
                modifier = Modifier.size(g * 1.6f).lightClickable { vm.go(Screen.Home) },
            )
            val read by vm.readToday.collectAsStateWithLifecycle()
            val allowed by vm.allowed.collectAsStateWithLifecycle()
            T(
                if (allowed == Int.MAX_VALUE) "" else "$read / ${read + allowed}",
                t.detail,
                Secondary,
            )
        }

        val html by vm.html.collectAsStateWithLifecycle()
        val plainText by vm.plainText.collectAsStateWithLifecycle()
        val formatted = !plainText && !html.isNullOrBlank()

        /*
         * The masthead does not scroll when the message is formatted.
         *
         * A WebView brings its own scrolling, and nesting that inside a scrollable Column
         * gives a page where neither container knows who should move. So a formatted
         * message is a fixed masthead with the sender's own page scrolling underneath it —
         * which is also how it reads: a sheet of their paper on our ground. Plain text
         * keeps the original single scroll, masthead and all, because that is the
         * page-of-a-book layout the app was built around and it should not change.
         */
        if (formatted) {
            Spacer(Modifier.height(g * 1.4f))
            Masthead(vm, msg)
            Spacer(Modifier.height(g * 1.2f))
            HtmlBody(html.orEmpty(), vm.repo.showImages, Modifier.weight(1f)) { url ->
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(url))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }.onFailure { vm.said("No browser here to open that.") }
            }
            Spacer(Modifier.height(g * 0.6f))
        } else {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Spacer(Modifier.height(g * 1.4f))
                Masthead(vm, msg)

                // Three clear units. This is the whole trick.
                Spacer(Modifier.height(g * 3f))

                T(
                    // IMAP sends no snippet, so an uncached message has nothing to show
                    // while its text is fetched. Say what is happening rather than draw a
                    // blank page.
                    body?.text ?: msg.snippet.ifBlank { "getting the text…" },
                    t.paragraph,
                    lineHeight = readerLeading(),
                    modifier = Modifier.fillMaxWidth(),
                )

                body?.let { b ->
                    if (b.quotedMessages > 0) {
                        Spacer(Modifier.height(g * 1.6f))
                        T(
                            if (b.quotedMessages == 1) "1 earlier message"
                            else "${b.quotedMessages} earlier messages",
                            t.detail,
                            Secondary,
                        )
                    }
                }
                if (msg.hasAttachments) {
                    Spacer(Modifier.height(g * 0.8f))
                    T("attachments held", t.detail, Secondary)
                }
                Spacer(Modifier.height(g * 1.5f))
            }
        }

        if (showWhy) {
            WhySheet(vm, msg) { showWhy = false }
        } else {
            ActionBar(
                left = "REPLY" to { vm.go(Screen.Write(msg)) },
                middle = "ARCHIVE" to { vm.archive(msg) },
                right = "···" to { showWhy = true },
            )
        }
    }
}

/** Sender, time, subject — the same three lines whichever way the body is drawn. */
@Composable
private fun Masthead(vm: MailboxViewModel, msg: Msg) {
    val g = LocalGrid.current
    val t = LocalType.current
    Row(verticalAlignment = Alignment.Bottom) {
        T(msg.senderName, t.heading, maxLines = 2, modifier = Modifier.weight(1f, fill = false))
        Spacer(Modifier.height(g * 0.5f))
        T(" " + accountWord(msg.accountId), t.superfine, Secondary)
    }
    Spacer(Modifier.height(g * 0.4f))
    T(longStamp(msg.receivedAt), t.detail, Secondary)
    Spacer(Modifier.height(g * 1.5f))
    T(msg.subject.ifBlank { "(no subject)" }, t.copy)
}

/**
 * The message as its sender built it.
 *
 * **Faithful, on white.** No stylesheet is injected and no colour is forced. Overriding
 * an email's CSS to match this app was the obvious idea and it is a trap: a sender who
 * sets a text colour and no background, or the reverse, comes out invisible, and every
 * logo with a white matte around it glares anyway. So the message keeps its own page and
 * sits on the app's black ground like a sheet of paper.
 *
 * **JavaScript stays off.** Nothing in an email needs it, it is the whole remote-code
 * surface, and `loadDataWithBaseURL(null, …)` gives the content no origin to resolve a
 * relative reference against — which is one more way a message could phone home.
 */
@Composable
private fun HtmlBody(
    html: String,
    images: Boolean,
    modifier: Modifier = Modifier,
    onLink: (String) -> Unit,
) {
    AndroidView(
        modifier = modifier.fillMaxWidth().background(Color.White),
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = false
                settings.domStorageEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.blockNetworkImage = !images
                settings.loadsImagesAutomatically = images
                // Mail is written for a desktop column; without these a 600px table is
                // drawn at 600 device pixels and the reader scrolls sideways forever.
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                setBackgroundColor(android.graphics.Color.WHITE)
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest,
                    ): Boolean {
                        // Never navigate in here. A link is the web, and the web is the
                        // browser's job.
                        onLink(request.url.toString())
                        return true
                    }
                }
            }
        },
        update = { it.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null) },
    )
}

/**
 * Why is this here, and the correction gesture.
 *
 * The reason is stated in plain words, never a percentage. Moving a sender is one action,
 * and it acknowledges in one line what it learned — otherwise a correction feels like
 * nothing happened, when in fact it is permanent.
 */
@Composable
private fun WhySheet(vm: MailboxViewModel, msg: Msg, onClose: () -> Unit) {
    val g = LocalGrid.current
    val t = LocalType.current
    val here = Pile.valueOf(msg.pile)
    val other = if (here == Pile.LETTER) Pile.NOTICE else Pile.LETTER

    Column(Modifier.fillMaxWidth().padding(bottom = g * 0.8f)) {
        T(
            "${if (here == Pile.LETTER) "Letter" else "Notice"} — ${msg.reason}",
            t.detail,
            Secondary,
            Modifier.padding(vertical = g * 0.5f),
        )
        T(
            if (other == Pile.LETTER) "MOVE TO LETTERS" else "MOVE TO NOTICES",
            t.button,
            modifier = Modifier.fillMaxWidth().lightClickable { vm.move(msg, other) }
                .padding(vertical = g * 0.4f),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val plainText by vm.plainText.collectAsStateWithLifecycle()
            val html by vm.html.collectAsStateWithLifecycle()
            // Only offer the switch when there are two things to switch between. A plain
            // message has no formatted version and the row would do nothing.
            if (!html.isNullOrBlank()) {
                T(
                    if (plainText) "FORMATTED" else "PLAIN TEXT",
                    t.button,
                    Secondary,
                    Modifier.lightClickable { vm.togglePlainText(); onClose() },
                    maxLines = 1,
                )
            }
            T("CLOSE", t.button, Secondary, Modifier.lightClickable(onClick = onClose), maxLines = 1)
        }
    }
}

private fun longStamp(at: Long): String =
    SimpleDateFormat("EEE h:mma", Locale.getDefault()).format(Date(at)).lowercase()
