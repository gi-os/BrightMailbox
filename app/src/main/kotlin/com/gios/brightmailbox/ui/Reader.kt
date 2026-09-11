package com.gios.brightmailbox.ui

import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.FileProvider
import com.gios.brightmailbox.R
import com.gios.brightmailbox.data.Msg
import com.gios.brightmailbox.mail.Attachment
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

/** Our own URL scheme for an attachment tap, so no JavaScript is needed to catch one. */
private const val ATTACHMENT_SCHEME = "bm-attachment:"

/** "412 KB". Bytes are not a thing anybody wants to read off a 3.9" screen. */
private fun size(bytes: Long): String = when {
    bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}

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
        /*
         * No top bar at all. The message is the screen.
         *
         * Back moved into the bottom bar beside the other two verbs, which is where a
         * thumb already is on a 3.9" phone, and the ration counter went with the bar — it
         * is a fact about the day, and the day belongs to Home.
         */
        val html by vm.html.collectAsStateWithLifecycle()
        val plainText by vm.plainText.collectAsStateWithLifecycle()
        val attachments by vm.attachments.collectAsStateWithLifecycle()
        val formatted = !plainText && !html.isNullOrBlank()

        /*
         * Hand the file to whatever opens that kind of file. A content:// URI from our
         * FileProvider plus the read grant, never a file:// path — that has thrown
         * FileUriExposedException on every Android since 7.
         */
        val openFile: (Attachment) -> Unit = { att ->
            vm.openAttachment(msg, att) { file ->
                runCatching {
                    val uri = FileProvider.getUriForFile(
                        context, context.packageName + ".files", file,
                    )
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW)
                            .setDataAndType(uri, att.mime)
                            .addFlags(
                                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                    Intent.FLAG_ACTIVITY_NEW_TASK,
                            ),
                    )
                }.onFailure { vm.said("Nothing here opens ${att.name.substringAfterLast('.')} files.") }
            }
        }

        /*
         * The masthead is part of the document, not a bar above it.
         *
         * It was fixed above the WebView, because a WebView brings its own scrolling and
         * nesting that in a scrollable Column gives a page where neither container knows
         * who should move. That worked and read wrong: the sender and subject sat there
         * anchored while the message slid under them. Rendering them *into* the page
         * instead solves both — one scroller, and the header goes away with the content
         * like it does in any other mail client.
         */
        if (formatted) {
            HtmlBody(
                html = html.orEmpty(),
                msg = msg,
                account = accountWord(msg.accountId),
                images = vm.repo.showImages,
                attachments = attachments,
                modifier = Modifier.weight(1f),
                onAttachment = openFile,
            ) { url ->
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(url))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }.onFailure { vm.said("No browser here to open that.") }
            }
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
                if (attachments.isNotEmpty()) {
                    Spacer(Modifier.height(g * 1.2f))
                    for (a in attachments) {
                        T(
                            a.name + if (a.size > 0) "  ·  " + size(a.size) else "",
                            t.detail,
                            modifier = Modifier
                                .fillMaxWidth()
                                .lightClickable { openFile(a) }
                                .padding(vertical = g * 0.35f),
                        )
                    }
                } else if (msg.hasAttachments) {
                    Spacer(Modifier.height(g * 0.8f))
                    T("getting the file list…", t.detail, Secondary)
                }
                Spacer(Modifier.height(g * 1.5f))
            }
        }

        if (showWhy) {
            WhySheet(vm, msg) { showWhy = false }
        } else {
            /*
             * Icons, not words, and back at the head of them.
             *
             * Three verbs at `button` tracking filled most of a 27-unit row, which left
             * no room for back once the top bar went. Icons are the SDK's own answer —
             * LightBottomBar takes up to five icon items but only three if any of them is
             * text — so dropping the words is what buys the fourth slot.
             */
            Row(
                Modifier.fillMaxWidth().height(g.actionBar),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BarIcon(R.drawable.ic_back_white, "Back") { vm.go(Screen.Home) }
                BarIcon(R.drawable.ic_reply_white, "Reply") { vm.go(Screen.Write(msg)) }
                BarIcon(R.drawable.ic_archive_white, "Archive") { vm.archive(msg) }
                T(
                    "···",
                    t.button,
                    Secondary,
                    Modifier.lightClickable { showWhy = true },
                    maxLines = 1,
                )
            }
        }
    }
}

/** One bar verb. Sized to the SDK's bar-icon unit so it matches every other bar. */
@Composable
private fun BarIcon(res: Int, label: String, onClick: () -> Unit) {
    val g = LocalGrid.current
    Image(
        painter = painterResource(res),
        contentDescription = label,
        modifier = Modifier.size(g * 2f).lightClickable(onClick = onClick),
    )
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
    msg: Msg,
    account: String,
    images: Boolean,
    attachments: List<Attachment>,
    modifier: Modifier = Modifier,
    onAttachment: (Attachment) -> Unit,
    onLink: (String) -> Unit,
) {
    val document = remember(html, msg.key, account, attachments) {
        document(html, msg, account, attachments)
    }

    /*
     * Nothing is drawn until the page has actually painted, then it fades in.
     *
     * The first attempt at this made the WebView transparent, which only traded a white
     * flash for a black one — the view was on screen, empty, over the app's black ground,
     * and then the message appeared under it. `onPageCommitVisible` is the callback that
     * means "there are real pixels now", so the view is held at zero opacity until then
     * and crossfades from the black rather than snapping.
     */
    var painted by remember(msg.key) { mutableStateOf(false) }
    val fade by animateFloatAsState(
        targetValue = if (painted) 1f else 0f,
        animationSpec = tween(durationMillis = 180),
        label = "message",
    )

    AndroidView(
        modifier = modifier.fillMaxWidth().alpha(fade),
        factory = { ctx ->
            WebView(ctx).apply {
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
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
                /*
                 * No scrollbar. It is drawn across the whole view, so it ran over the
                 * black strip above the message — and nothing else in this app has one
                 * anyway: a Compose LazyColumn shows none, and the SDK ships none.
                 */
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                overScrollMode = WebView.OVER_SCROLL_NEVER
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest,
                    ): Boolean {
                        val url = request.url.toString()
                        /*
                         * Attachments are links with our own scheme, which is how they
                         * are tappable without JavaScript — the one thing this view must
                         * not have. The part path is the whole payload.
                         */
                        if (url.startsWith(ATTACHMENT_SCHEME)) {
                            val part = url.removePrefix(ATTACHMENT_SCHEME)
                            attachments.firstOrNull { it.part == part }?.let(onAttachment)
                            return true
                        }
                        // Never navigate in here otherwise. A link is the web, and the
                        // web is the browser's job.
                        onLink(url)
                        return true
                    }

                    // "The page has painted something." onPageFinished is too late — it
                    // waits for every image — and too early is the flash this replaces.
                    override fun onPageCommitVisible(view: WebView, url: String?) {
                        painted = true
                    }
                }
            }
        },
        update = { it.loadDataWithBaseURL(null, document, "text/html", "UTF-8", null) },
    )
}

/**
 * The sender's HTML with our masthead rendered into the top of it.
 *
 * Everything we add is **inline-styled**. An email brings its own `<style>` block, and a
 * sender who writes `h1 { color: #fff }` or `body { font-family: … }` would otherwise
 * restyle our header along with their own message. Inline declarations beat a stylesheet
 * rule at every specificity short of `!important`, which no bulk sender emits for a
 * selector this generic.
 *
 * The email's own markup goes in last and untouched — including its `<html>` and `<body>`
 * tags if it has them, which every browser drops when it finds them mid-document. That is
 * the same leniency every other mail client relies on, and it is safer than trying to cut
 * them out with a regular expression.
 */
private fun document(
    html: String,
    msg: Msg,
    account: String,
    attachments: List<Attachment>,
): String {
    fun esc(s: String) = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;")

    val sender = esc(msg.senderName.ifBlank { msg.sender })
    val subject = esc(msg.subject.ifBlank { "(no subject)" })
    val stamp = esc(longStamp(msg.receivedAt) + " · " + account)

    /*
     * Attachments sit under the subject, above the message — which is where you look to
     * decide whether the message is the point or the file is.
     *
     * Anchors with our own scheme rather than buttons: the view has no JavaScript, and
     * shouldOverrideUrlLoading is the callback that survives that. Styles are inline for
     * the same reason the masthead's are — an email's <style> block would otherwise
     * restyle these along with its own content.
     */
    val files = if (attachments.isEmpty()) "" else attachments.joinToString(
        separator = "",
        prefix = """<div style="margin-top:16px">""",
        postfix = "</div>",
    ) { a ->
        val label = esc(a.name) + if (a.size > 0) " · " + size(a.size) else ""
        """<a href="$ATTACHMENT_SCHEME${esc(a.part)}" style="display:block;margin-top:8px;padding:9px 12px;border:1px solid #d8d8d8;border-radius:6px;color:#000;text-decoration:none;font-size:14px;line-height:1.3">$label</a>"""
    }

    /*
     * The body is TRANSPARENT and the sheet inside it is white, starting 14px down.
     *
     * That gap is the app's black ground showing above the message, and because it is
     * margin inside the scrolling document rather than padding on the view, it scrolls
     * away with the content instead of sitting there as a permanent black bar at the top.
     * Making the body itself white would have put the sheet hard against the screen edge;
     * putting the gap on the WebView would have pinned it.
     */
    return """<!doctype html><html><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
</head><body style="margin:0;background:transparent;-webkit-text-size-adjust:100%">
<div style="margin-top:14px;background:#fff">
<div style="padding:22px 20px 0;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;color:#000">
  <div style="font-size:25px;line-height:1.2;font-weight:400;color:#000">$sender</div>
  <div style="font-size:13px;line-height:1.5;color:#777;margin-top:5px">$stamp</div>
  <div style="font-size:17px;line-height:1.35;color:#000;margin-top:14px">$subject</div>
$files
</div>
<div style="height:1px;background:#e2e2e2;margin:20px 20px 0"></div>
$html
</div>
</body></html>"""
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
