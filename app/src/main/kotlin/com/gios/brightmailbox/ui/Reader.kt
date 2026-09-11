package com.gios.brightmailbox.ui

import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
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
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
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

    /**
     * The PDF currently being read, if any, as (file, name).
     *
     * Held here rather than made a [Screen] because it belongs to this message: leaving
     * the letter should take the document with it, and a screen in the flat navigation
     * would outlive the thing it came out of.
     */
    var reading by remember(msg.key) { mutableStateOf<Pair<java.io.File, Attachment>?>(null) }

    /*
     * `key(msg.key)` throws the whole reader away between messages.
     *
     * The WebView is reused otherwise, so a frame of the PREVIOUS email was visible
     * before the new one painted. A fresh view cannot show the last message.
     *
     * The slide itself lives in MainActivity, not here: an animation inside this screen
     * can only start once this screen has replaced the last one, and the point is that
     * the list is still on screen, fading, while the letter comes up past it.
     */
    /*
     * A document takes over the whole screen while it is open.
     *
     * Returned before the reader rather than drawn over it, so the letter underneath is
     * not composed at all — a WebView left alive behind a forty-page PDF is memory this
     * device does not have spare. The system back gesture closes the document first,
     * which is the only thing that makes a screen-within-a-screen safe on LightOS.
     */
    reading?.let { (file, att) ->
        androidx.activity.compose.BackHandler { reading = null }
        PdfScreen(
            file = file,
            name = att.name,
            onSave = { vm.saveAttachment(msg, att) },
            onClose = { reading = null },
        )
        return
    }

    key(msg.key) {
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
        /*
         * A PDF opens here; anything else is handed to whatever opens that kind of file,
         * and falls back to saving it.
         *
         * The old behaviour was ACTION_VIEW for everything, and on a phone with almost no
         * apps on it that mostly resolved to "Nothing here opens pdf files" — a statement
         * or a boarding pass arriving on the phone it was sent to and being unreadable on
         * it. PDFs are the overwhelming majority of what anybody attaches, and Android can
         * render them with no dependency at all, so they are worth handling ourselves.
         *
         * For a .docx there is no such answer, and inventing one is not the job of a mail
         * client. Offer it to the system, and when nothing takes it, put the file where
         * the person can reach it from a computer instead of telling them no.
         */
        val openFile: (Attachment) -> Unit = { att ->
            vm.openAttachment(msg, att) { file ->
                val isPdf = att.mime.equals("application/pdf", true) ||
                    att.name.endsWith(".pdf", true)
                if (isPdf) {
                    reading = file to att
                } else {
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
                    }.onFailure {
                        vm.said("Nothing here opens that. Saving it instead…")
                        vm.saveAttachment(msg, att)
                    }
                }
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
                onDismiss = { vm.go(Screen.Home) },
            ) { url ->
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(url))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }.onFailure { vm.said("No browser here to open that.") }
            }
        } else {
            val scroll = rememberScrollState()
            /*
             * Pull down to go home, WITHOUT eating the scroll.
             *
             * This was a `pointerInput { detectVerticalDragGestures { … } }` on the same
             * element, and it broke reading entirely: that detector waits for vertical
             * slop and then owns the gesture, and it sits inside the scrolling modifier,
             * so it saw every drag first. The text could not be scrolled at all, and any
             * downward drag — which is how you scroll UP — left the message. The guard
             * inside it was right and never got the chance to matter.
             *
             * A nested-scroll connection is the correct tool because of what it is
             * handed: `onPostScroll` only ever receives what the scroller could NOT
             * consume. At the top of a message that is the whole downward drag; anywhere
             * else it is zero. So the gesture cannot compete with scrolling by
             * construction rather than by a condition.
             *
             * The leftover is accumulated rather than tested per event, since one drag
             * arrives as a stream of small deltas and any single one of them is under any
             * sane threshold.
             */
            var overscroll by remember(msg.key) { mutableStateOf(0f) }
            val pull = remember(msg.key) {
                object : NestedScrollConnection {
                    override fun onPostScroll(
                        consumed: Offset,
                        available: Offset,
                        source: NestedScrollSource,
                    ): Offset {
                        if (available.y > 0f) {
                            overscroll += available.y
                            if (overscroll > 140f) {
                                overscroll = 0f
                                vm.go(Screen.Home)
                            }
                        } else if (available.y < 0f) {
                            overscroll = 0f
                        }
                        return Offset.Zero
                    }
                }
            }
            Column(
                Modifier
                    .weight(1f)
                    .nestedScroll(pull)
                    .verticalScroll(scroll),
            ) {
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
    onDismiss: () -> Unit,
    onLink: (String) -> Unit,
) {
    /*
     * How wide this view actually is, in CSS pixels.
     *
     * CSS pixels are dp on Android, so no density arithmetic — but the screen's width is
     * not the view's: `Frame` insets every screen by a grid unit on each side, so the
     * WebView is two units narrower than the panel. Passing the screen width would
     * compute a zoom slightly too large and leave the message scrolling inside its own
     * box by a couple of dozen pixels — the old bug back in miniature.
     */
    val screenDp = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp
    val viewDp = (screenDp - 2 * LocalGrid.current.inset.value).toInt()
    val document = remember(html, msg.key, account, attachments, viewDp) {
        document(html, msg, account, attachments, viewDp)
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
    /*
     * Hard switch, not a fade.
     *
     * The letter is either not there or fully there — it must never fade in, because it
     * arrives by sliding up and a slide that also changes opacity reads as two different
     * animations arguing. Zero until the page has actually painted, so what slides up is
     * a rendered letter rather than an empty sheet that fills in afterwards.
     */
    var painted by remember(msg.key) { mutableStateOf(false) }

    AndroidView(
        modifier = modifier.fillMaxWidth().alpha(if (painted) 1f else 0f),
        factory = { ctx ->
            val web = WebView(ctx).apply {
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
            // The frame is the thing returned, with the web view inside it — the pull
            // has to be caught above the WebView to be caught at all.
            PullDownFrame(ctx).apply {
                addView(web)
                atTop = { web.scrollY == 0 }
                onPull = onDismiss
            }
        },
        update = { frame ->
            (frame.getChildAt(0) as? WebView)
                ?.loadDataWithBaseURL(null, document, "text/html", "UTF-8", null)
        },
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
/**
 * The width a message was built for, if it says so.
 *
 * Bulk mail is laid out on a fixed grid and declares it — `width="600"` on the outer
 * table, or `width:600px` in a style — and 600 or 640 covers most of what exists.
 *
 * Finding that number is the real fix for horizontal overflow. Forcing such a message
 * into a `width=device-width` viewport asks a 600-pixel layout to fit in 390 and it
 * simply will not: it overflows, and no amount of `overflow` juggling makes the content
 * narrower, it only decides who does the scrolling. Laying the page out at the width it
 * expects and letting the WebView scale the result to the screen is what actually makes
 * it fit — which is what every mail client does, and why mail looks zoomed-out on a
 * phone rather than clipped.
 *
 * Only large, plausible values count. A `width="1"` spacer gif and a `width:100%` are
 * both extremely common and neither says anything about the layout.
 */
private fun declaredWidth(html: String): Int? =
    Regex("width\\s*[:=]\\s*[\"']?\\s*(\\d{3,4})\\s*(?:px)?", RegexOption.IGNORE_CASE)
        .findAll(html)
        .mapNotNull { it.groupValues[1].toIntOrNull() }
        .filter { it in 480..1280 }
        .maxOrNull()

private fun document(
    html: String,
    msg: Msg,
    account: String,
    attachments: List<Attachment>,
    /** The WebView's own width in CSS pixels (dp), insets already taken off. */
    viewDp: Int,
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
    /*
     * `<html>` carries the transparent background too, and that is not belt and braces.
     *
     * CSS propagates a background to the canvas from `body` — unless body has none, in
     * which case it takes `html`'s. Marketing mail very often ships
     * `html { background: #f4f4f4 }`, and because our transparent declaration was only on
     * `body`, that grey propagated to the whole canvas and painted the strip above the
     * message grey instead of leaving the app's black showing. Inline on both elements
     * beats a stylesheet rule at every specificity short of !important.
     */
    /*
     * The page itself never scrolls sideways; wide content scrolls inside its own box.
     *
     * `width=device-width` pins the layout viewport to the screen, so a message built
     * around a 600 px table overflows it — and the overflow becomes horizontal scroll on
     * the whole document, which is why you could drag the masthead sideways and end up
     * past the end of the message in dead space.
     *
     * `overflow-x:hidden` on html and body stops the *document* scrolling, and the
     * wrapper below takes it instead. Nothing is clipped: a 600 px table still scrolls,
     * it just scrolls within itself, so the page around it stays put. Clamping images is
     * the other half — an 800 px header image is the most common single cause.
     *
     * The style block sits BEFORE the message so a sender who really means to override
     * it still can; only the inline `overflow-x` is non-negotiable.
     */
    /*
     * Lay the page out at the width the message was built for, and let the WebView scale
     * it down to the screen.
     *
     * `width=device-width` was the mistake. It pins the layout to 390-odd pixels, and a
     * message designed on a 600-pixel grid then overflows — which is the overflow that
     * kept coming back, first as the whole document sliding sideways and then as content
     * spilling out of its box. `overflow` rules only decide who scrolls; they cannot make
     * a 600-pixel table narrower.
     *
     * With `width=600` here and `loadWithOverviewMode` on the view, the page is laid out
     * at 600 and zoomed to fit, so the whole message is on screen and there is nothing to
     * scroll sideways at all. Mail with no declared width keeps `device-width` and stays
     * full size, which is most personal mail.
     */
    /*
     * The message is zoomed. The page is NOT.
     *
     * v2.13 put the declared width in the viewport meta, which laid the whole document
     * out at 600 and let the WebView scale everything down to fit. That fixed the
     * overflow and broke two things with it. Our sheet was scaled too, so its rounded top
     * corners landed on fractional device pixels and the right one — the one at the far
     * edge, where the rounding error accumulates — came out clipped. And the masthead
     * shrank along with the mail, which was never the intent.
     *
     * `zoom` on the message wrapper alone does the same job in the right place: the
     * content inside is laid out at the width it was built for and then rendered smaller,
     * with the surrounding box shrinking to match — which is what separates `zoom` from
     * `transform: scale`, where the parent keeps the unscaled height and leaves a hole
     * below. The sheet, its corners and the masthead stay at device scale and stay crisp.
     *
     * The floor stops a message that declares 1280 from being rendered at a size nobody
     * can read; anything past it scrolls sideways inside its own box, as before.
     */
    val gutter = 36
    val room = (viewDp - gutter).coerceAtLeast(240)
    val declared = declaredWidth(html)
    /*
     * Three per cent of slack.
     *
     * The declared width is what the message says its grid is, not what it measures. A
     * cell padding, a border, a nested table with a margin — any of them puts the real
     * content a few pixels past the number, and at exactly 1.0 those few pixels are the
     * sliver still clipped off the right edge. Nothing can measure the true width without
     * JavaScript, so the honest move is to assume the declaration is slightly optimistic.
     *
     * Three per cent is under half a percent of a line of text at this size — invisible —
     * and covers the ordinary case of a 600 px grid whose outer table actually renders at
     * 616.
     */
    val zoom = declared?.takeIf { it > room }
        ?.let { (room.toFloat() / it * 0.97f).coerceAtLeast(0.55f) }
    /*
     * Locale.US, and it is not a nicety. The default locale formats a decimal with a
     * comma in most of Europe, and `zoom:0,5900` is not a number CSS will parse — the
     * declaration is dropped, the message renders at full width, and the overflow is
     * back for exactly the users least likely to be able to report why.
     */
    val openZoom = if (zoom == null) "" else
        """<div style="zoom:${String.format(java.util.Locale.US, "%.4f", zoom)};width:${declared}px;margin:0 auto">"""
    val closeZoom = if (zoom == null) "" else "</div>"

    /*
     * When there is no declared width, let wide tables shrink.
     *
     * A centered message is the case that shows this up. `<center>` and `align="center"`
     * center a block INSIDE its container, and a block wider than its container is not
     * centered by anything — it starts at the left edge and hangs off the right. So the
     * one layout whose whole point is to be centered is the one that most obviously is
     * not, which is exactly what Gio saw.
     *
     * With a declared width there is a real answer: lay it out at that width and zoom, so
     * the centering is preserved exactly as built. Without one there is no number to scale
     * by — the width is whatever the content happens to compute to, and reading that needs
     * JavaScript, which this view will never have. `max-width:100%` is the honest fallback:
     * the table reflows to fit, which can loosen a fixed grid, and a loosened grid that
     * fits beats a faithful one you can only see the left third of.
     *
     * Scoped to the no-zoom case for that reason. A message we CAN scale is never reflowed.
     */
    /*
     * `!important` because the width being fought is almost always an inline
     * `style="width:600px"` or a `width="600"` attribute, and an important author rule is
     * the one thing that outranks an inline declaration. `max-width` and not `width`, so
     * a table narrower than the screen is left exactly as it is.
     *
     * Tables only. Not `div`, which would catch our own wrapper, and not a blanket rule on
     * everything, which mangles more mail than it saves.
     */
    val shrink = if (zoom != null) "" else
        "  table { max-width: 100% !important; }\n"

    return """<!doctype html><html style="background:transparent;overflow-x:hidden"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<style>
  /*
   * The page's own geometry, pinned.
   *
   * An email arrives with its <style> block intact and that block lands in OUR document,
   * so a sender who writes `body { width: 640px }` or `body { margin: 24px }` — and bulk
   * senders write exactly that, because in a real mail client the body IS their message —
   * resizes the whole page including our sheet. That was the last of the right-edge
   * cropping: not the content overflowing its box, but the box itself being made wider
   * than the screen by the message's own stylesheet.
   *
   * !important is what makes it stick: these have to beat a rule in a stylesheet that
   * appears later in the document than this one does.
   */
  html, body {
    margin: 0 !important; padding: 0 !important;
    width: auto !important; min-width: 0 !important; max-width: none !important;
  }
  img { max-width: 100%; height: auto; }
  pre, code { white-space: pre-wrap; word-break: break-word; }
  td, th, p, div, a { word-break: break-word; overflow-wrap: anywhere; }
$shrink</style>
</head><body style="margin:0;background:transparent;overflow-x:hidden;-webkit-text-size-adjust:100%">
<div style="margin-top:14px;background:#fff;border-radius:14px 14px 0 0;overflow:hidden">
<div style="padding:22px 20px 0;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;color:#000">
  <div style="font-size:25px;line-height:1.2;font-weight:400;color:#000">$sender</div>
  <div style="font-size:13px;line-height:1.5;color:#777;margin-top:5px">$stamp</div>
  <div style="font-size:17px;line-height:1.35;color:#000;margin-top:14px">$subject</div>
$files
</div>
<div style="height:1px;background:#e2e2e2;margin:20px 20px 0"></div>
<!--
  A gutter, so text does not run into the edge of the sheet.

  Horizontal only, and none at the bottom: a message that ends with a full-width image
  or a coloured footer band should keep touching both sides, the way it was designed to.
  Padding all the way round would put a white frame under every newsletter footer.
-->
<div style="padding:16px 18px 0;overflow-x:auto;-webkit-overflow-scrolling:touch">
$openZoom$html$closeZoom
</div>
<div style="height:24px"></div>
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
