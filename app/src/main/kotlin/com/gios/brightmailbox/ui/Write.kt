package com.gios.brightmailbox.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.TextRange
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gios.brightmailbox.data.Msg
import com.gios.brightmailbox.mail.Addr
import com.gios.brightmailbox.mail.Outgoing
import com.gios.brightmailbox.ui.theme.Background
import com.gios.brightmailbox.ui.theme.Content
import com.gios.brightmailbox.ui.theme.LocalGrid
import com.gios.brightmailbox.ui.theme.LocalType
import com.gios.brightmailbox.ui.theme.Screen as Frame
import com.gios.brightmailbox.ui.theme.Secondary
import com.gios.brightmailbox.ui.theme.T
import com.gios.brightmailbox.ui.theme.lightClickable

/**
 * About eight megabytes of files, before base64.
 *
 * Encoding inflates by a third, so this leaves a message under the 25 MB most providers
 * accept with room for the body. The cap exists at all because the bytes are held in
 * memory to survive the picker going away, and a phone can pick a video.
 */
private const val MAX_ATTACHED = 8 * 1024 * 1024

/** How long after the last keystroke the draft is written. */
private const val AUTOSAVE_MS = 1_500L

/**
 * Read a picked file into memory, with its real name.
 *
 * The display name comes from the provider's own cursor, not from the URI — a content URI
 * for "Q3 report.pdf" routinely ends in `/document/1423`, and using the last path segment
 * sends somebody a file called 1423.
 */
private fun readPicked(
    context: android.content.Context,
    uri: android.net.Uri,
): com.gios.brightmailbox.mail.Outfile? = try {
    val resolver = context.contentResolver
    val name = resolver.query(uri, null, null, null, null)?.use { c ->
        val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (i >= 0 && c.moveToFirst()) c.getString(i) else null
    } ?: uri.lastPathSegment ?: "attachment"
    val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
    com.gios.brightmailbox.mail.Outfile(
        name = name,
        mime = resolver.getType(uri) ?: "application/octet-stream",
        bytes = bytes,
    )
} catch (e: Exception) {
    // The exception, not the URI: a content URI can name the file and the file is
    // somebody's.
    com.gios.light.common.report.Trouble.record(
        "read a file to attach",
        com.gios.brightmailbox.report.Detail.of(e),
    )
    null
}

/**
 * Compose and reply.
 *
 * A reply opens with the recipient settled and the cursor in the body — no subject field
 * unless it is edited, and no quoted original appended. The keyboard takes roughly half
 * this screen, so the whole thing is laid out for ~230 dp of usable height and the body
 * scrolls to keep the cursor visible.
 */
@Composable
fun WriteScreen(
    vm: MailboxViewModel,
    replyTo: Msg?,
    openDraftId: Long = 0L,
    mode: WriteMode = if (replyTo != null) WriteMode.REPLY else WriteMode.NEW,
    from: Screen = Screen.Home,
) {
    val g = LocalGrid.current
    val t = LocalType.current
    // Not vm.busy — that is on for every background sync, and this screen is asking a
    // question about this message, not about the network.
    val busy by vm.sending.collectAsStateWithLifecycle()

    val accounts = vm.repo.auth.accounts()
    var accountId by remember { mutableStateOf(replyTo?.accountId ?: accounts.firstOrNull()?.id.orEmpty()) }

    /*
     * Who a reply goes to, and who else.
     *
     * Reply-all keeps everyone who was on it minus you — your own addresses across every
     * signed-in mailbox, not only the one it arrived at, because a message sent to two of
     * your accounts would otherwise copy you on your own reply. The sender goes in To and
     * everybody else in Cc, which is the convention every client follows: it says who the
     * answer is for and who is only being kept informed.
     */
    val mine = remember(accounts) { accounts.map { it.email.lowercase() }.toSet() }
    val others = remember(replyTo, mode) {
        if (mode != WriteMode.REPLY_ALL || replyTo == null) {
            emptyList()
        } else {
            (replyTo.toAddrs.split(',') + replyTo.ccAddrs.split(','))
                .map { it.trim().lowercase() }
                .filter { it.isNotBlank() && it !in mine && it != replyTo.sender.lowercase() }
                .distinct()
        }
    }

    var to by remember {
        mutableStateOf(
            TextFieldValue(if (mode == WriteMode.FORWARD) "" else replyTo?.sender.orEmpty()),
        )
    }
    var cc by remember { mutableStateOf(TextFieldValue(others.joinToString(", "))) }
    var subject by remember {
        mutableStateOf(
            TextFieldValue(
                when {
                    replyTo == null -> ""
                    mode == WriteMode.FORWARD -> Addr.forwardSubject(replyTo.subject)
                    else -> Addr.replySubject(replyTo.subject)
                },
            ),
        )
    }
    var body by remember { mutableStateOf(TextFieldValue("")) }
    var editingSubject by remember { mutableStateOf(replyTo == null) }

    /*
     * Has a person changed anything?
     *
     * A reply opens with To and Subject already filled, so "is there text in the
     * fields" cannot tell an untouched screen from a written one — and an untouched
     * screen must not become a draft, or every reply you open and back out of leaves one
     * behind. Set by the fields and the file picker, never by what the screen fills in
     * itself, and it gates both the autosave and the save on the way out.
     */
    var edited by remember { mutableStateOf(false) }
    /** The file list changed since it was last written; the next save carries it. */
    var filesDirty by remember { mutableStateOf(false) }
    /**
     * Bumped on every change to the file list, and what the autosave is keyed on in its
     * place. Keying on the list itself would compare it on every recomposition, and an
     * Outfile compares by its bytes — eight megabytes of `contentEquals` per frame.
     */
    var filesStamp by remember { mutableStateOf(0) }

    /*
     * A forward carries the original. A reply does not.
     *
     * Quoting on reply is the habit that turns a five-line exchange into a scroll of its
     * own history, and the person receiving it already has every word. A forward is the
     * opposite: the whole point is the message, and without it the recipient gets a
     * subject line and nothing else.
     */
    val bodyText by vm.body.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(bodyText, mode) {
        if (mode != WriteMode.FORWARD || replyTo == null) return@LaunchedEffect
        if (body.text.isNotBlank()) return@LaunchedEffect
        val quoted = bodyText?.text.orEmpty()
        body = TextFieldValue(
            "\n\n---------- Forwarded ----------\n" +
                "From: ${replyTo.senderName.ifBlank { replyTo.sender }} <${replyTo.sender}>\n" +
                "Subject: ${replyTo.subject}\n\n" + quoted,
        )
    }

    /*
     * Files to send.
     *
     * Read into memory the moment they are picked, not held as a content URI: by the time
     * SMTP runs the picker is gone, and a URI permission granted to it can go with it.
     * Capped, because base64 inflates by a third and a phone can pick a video.
     */
    var files by remember { mutableStateOf(listOf<com.gios.brightmailbox.mail.Outfile>()) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val pick = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val f = readPicked(context, uri)
        when {
            f == null -> vm.said("Could not read that file.")
            files.sumOf { it.bytes.size } + f.bytes.size > MAX_ATTACHED ->
                vm.said("That is too big to send. About 8 MB in total is the limit.")
            else -> {
                files = files + f
                edited = true
                filesDirty = true
                filesStamp++
            }
        }
    }

    /*
     * The draft this screen is editing, 0 until it has been written once.
     *
     * Restored on open: the one the drafts screen named, or the most recent draft for this
     * conversation. Both matter — "the newest standalone draft" alone was what stranded a
     * second unsent message, because every one of them matches it and only the first is
     * ever offered.
     */
    var draftId by remember { mutableStateOf(0L) }
    val drafts by vm.drafts.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(drafts.isNotEmpty()) {
        if (draftId != 0L) return@LaunchedEffect
        /*
         * The named draft wins, then the newest one for this conversation.
         *
         * Without the first clause every standalone draft matched `inReplyTo == null` and
         * the same one was restored every time, so a second unsent message was kept and
         * never offered again. The drafts screen passes an id; this honours it.
         */
        val saved = drafts.firstOrNull { openDraftId != 0L && it.id == openDraftId }
            ?: drafts.firstOrNull { openDraftId == 0L && it.inReplyTo == replyTo?.messageId }
            ?: return@LaunchedEffect
        draftId = saved.id
        accountId = saved.accountId.ifBlank { accountId }
        if (saved.to.isNotBlank()) to = TextFieldValue(saved.to)
        if (saved.cc.isNotBlank()) cc = TextFieldValue(saved.cc)
        if (saved.subject.isNotBlank()) subject = TextFieldValue(saved.subject)
        if (saved.body.isNotBlank()) body = TextFieldValue(saved.body)
        // The files come back too. Read off disk, so a draft queued with a deck reopens
        // with the deck rather than with a line saying it needs attaching again.
        if (saved.files.isNotBlank() && files.isEmpty()) files = vm.draftFiles(saved)
        // What the draft still says about itself: a FAILED one says why, once, here.
        if (saved.error.isNotBlank() &&
            saved.state == com.gios.brightmailbox.data.SendState.FAILED.name
        ) vm.said("Could not send last time — ${saved.error}")
    }

    /** Everything the screen is holding, as a row. */
    fun snapshot() = com.gios.brightmailbox.data.Draft(
        id = draftId,
        accountId = accountId,
        to = to.text,
        cc = cc.text,
        subject = subject.text,
        body = body.text,
        inReplyTo = replyTo?.messageId,
        references = replyTo?.references,
        threadId = replyTo?.threadId,
        updatedAt = System.currentTimeMillis(),
    )

    /*
     * Save on the way out, however you leave.
     *
     * DisposableEffect's onDispose runs when this screen leaves the composition — which
     * covers CANCEL, the hardware back gesture, and the app being killed behind you. A
     * save wired only to the CANCEL button would miss the two ways people actually leave.
     */
    /*
     * …except when it has just been sent.
     *
     * Sending navigates away, which disposes this screen, which would otherwise save a
     * draft of the message that had just left — so every sent message would leave a copy
     * of itself behind. The flag is read inside onDispose rather than keyed on, because by
     * then the send has already happened.
     */
    var sent by remember { mutableStateOf(false) }

    /**
     * One save, whichever moment asked for it.
     *
     * The snapshot is a lambda because the ViewModel reads it under its draft lock, after
     * any save already in flight has finished and handed back the row id — so a second
     * save writes into the same row rather than a new one. Null tells it there is
     * nothing to keep: sent, or never touched.
     */
    fun save() {
        val carry = filesDirty
        val toSave = files
        filesDirty = false
        vm.keepDraft(
            snapshot = { if (sent || !edited) null else snapshot() },
            files = { if (carry) toSave else null },
            onSaved = { id -> if (id != 0L) draftId = id },
        )
    }

    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { save() }
    }

    /*
     * …and while you type.
     *
     * Leaving the screen was the only moment a draft was written, and the two ways a
     * message is lost on this phone happen before that moment: the process is killed
     * behind an incoming call, or the battery goes. A pause of a second and a half after
     * the last keystroke is long enough not to write on every letter and short enough
     * that what is lost is a word. Keyed on the text, so every edit restarts the wait.
     */
    LaunchedEffect(to.text, cc.text, subject.text, body.text, accountId, filesStamp) {
        if (!edited || sent) return@LaunchedEffect
        kotlinx.coroutines.delay(AUTOSAVE_MS)
        save()
    }

    Frame {
        Row(
            Modifier.fillMaxWidth().height(g.topBar),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            T(
                when (mode) {
                    WriteMode.REPLY -> "REPLY"
                    WriteMode.REPLY_ALL -> "REPLY ALL"
                    WriteMode.FORWARD -> "FORWARD"
                    WriteMode.NEW -> "WRITE"
                },
                t.screenTitle,
                maxLines = 1,
            )
            if (accounts.size > 1) {
                T(
                    accountWord(accountId),
                    t.superfine,
                    Secondary,
                    Modifier.lightClickable {
                        val i = accounts.indexOfFirst { it.id == accountId }
                        accountId = accounts[(i + 1) % accounts.size].id
                        edited = true
                    },
                )
            }
        }

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).imePadding()) {
            Field("To", to, { to = it; edited = true }, g, t)

            /*
             * Who you have written to before.
             *
             * The correspondents table has been written on every send since v1 and read by
             * nothing — the app knew everyone you had ever emailed and still made you type
             * the whole address on a 3.9" keyboard.
             *
             * Matches on any part of the address, not just the start: people search for
             * somebody by the half they remember, and on this phone that is as often the
             * domain as the name. Three suggestions, because a fourth line pushes the body
             * off the fold, and only after two characters — one letter matches everyone.
             */
            val book by vm.addressBook.collectAsStateWithLifecycle()
            LaunchedEffect(Unit) { vm.loadAddressBook() }
            val typed = to.text.substringAfterLast(',').trim()
            val hits = if (typed.length < 2) {
                emptyList()
            } else {
                book.filter {
                    it.contains(typed, ignoreCase = true) && !it.equals(typed, ignoreCase = true)
                }.take(3)
            }
            for (hit in hits) {
                T(
                    hit,
                    t.detail,
                    Secondary,
                    Modifier
                        .fillMaxWidth()
                        .lightClickable {
                            // Replace the fragment being typed, keep any addresses before
                            // it, and leave the caret after a separator ready for the next.
                            val before = to.text.substringBeforeLast(',', "")
                            val whole =
                                (if (before.isBlank()) "" else before.trimEnd() + ", ") + hit + ", "
                            to = TextFieldValue(whole, TextRange(whole.length))
                            edited = true
                        }
                        .padding(vertical = g * 0.3f),
                    maxLines = 1,
                )
            }
            if (editingSubject) {
                Spacer(Modifier.height(g * 0.8f))
                Field("Subject", subject, { subject = it; edited = true }, g, t)
            } else {
                Spacer(Modifier.height(g * 0.6f))
                T(
                    subject.text,
                    t.detail,
                    Secondary,
                    Modifier.lightClickable { editingSubject = true },
                    maxLines = 1,
                )
            }
            // Only when there is one. A blank Cc row on every reply is a field nobody
            // fills in taking a line from a 472dp screen.
            if (cc.text.isNotBlank() || mode == WriteMode.REPLY_ALL) {
                Spacer(Modifier.height(g * 0.8f))
                Field("Cc", cc, { cc = it; edited = true }, g, t)
            }

            Spacer(Modifier.height(g * 0.9f))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                T(
                    "ATTACH",
                    t.button,
                    Secondary,
                    Modifier.lightClickable { pick.launch(arrayOf("*/*")) },
                    maxLines = 1,
                )
                if (files.isNotEmpty()) {
                    Spacer(Modifier.width(g * 0.8f))
                    T("${files.size} file${if (files.size == 1) "" else "s"}", t.superfine, Secondary)
                }
            }
            for (f in files) {
                T(
                    "${f.name}  ·  remove",
                    t.superfine,
                    Secondary,
                    Modifier
                        .fillMaxWidth()
                        .lightClickable {
                            files = files - f
                            edited = true
                            filesDirty = true
                            filesStamp++
                        }
                        .padding(vertical = g * 0.2f),
                    maxLines = 1,
                )
            }

            Spacer(Modifier.height(g * 1.2f))
            BasicTextField(
                value = body,
                onValueChange = { body = it; edited = true },
                textStyle = t.paragraph.copy(color = Content),
                cursorBrush = SolidColor(Content),
                modifier = Modifier.fillMaxWidth().background(Background),
            )
            Spacer(Modifier.height(g * 4f))
        }

        val ready = Addr.addresses(to.text).isNotEmpty() && body.text.isNotBlank() && !busy
        ActionBar(
            left = (if (busy) "SENDING" else "SEND") to {
                if (ready) {
                    sent = true
                    vm.send(
                        accountId,
                        Outgoing(
                            to = Addr.addresses(to.text),
                            cc = Addr.addresses(cc.text),
                            subject = subject.text.ifBlank { "(no subject)" },
                            body = body.text,
                            // A forward is a new message about an old one, not a reply to
                            // it: threading it would file it under a conversation the new
                            // recipient has never seen.
                            inReplyTo = if (mode == WriteMode.FORWARD) null else replyTo?.messageId,
                            references = if (mode == WriteMode.FORWARD) null else replyTo?.references,
                            threadId = if (mode == WriteMode.FORWARD) null else replyTo?.threadId,
                            files = files,
                        ),
                        draftId = { draftId },
                        onFailed = { sent = false },
                        // The queued row is this screen's draft from now on, so the save
                        // on the way out writes into it rather than beside it.
                        onQueued = { draftId = it },
                    )
                }
            },
            // Back where it was opened from — the drafts list, or a message being
            // replied to — rather than always to the inbox.
            right = "CANCEL" to { vm.go(from) },
        )
    }
}

/**
 * The only field style in the app: a label, then an 80%-width rule three design pixels
 * thick. No floating label, no filled container, no focus color.
 *
 * Internal rather than private because the sign-in screen needs the same field, and two
 * field styles in one app is how an app stops looking like one thing.
 */
@Composable
internal fun Field(
    label: String,
    value: TextFieldValue,
    onChange: (TextFieldValue) -> Unit,
    g: com.gios.brightmailbox.ui.theme.Grid,
    t: com.gios.brightmailbox.ui.theme.Type,
    mask: Boolean = false,
) {
    Column(Modifier.fillMaxWidth()) {
        T(label, t.detail, Secondary)
        Spacer(Modifier.height(g * 0.3f))
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = t.copy.copy(color = Content),
            cursorBrush = SolidColor(Content),
            visualTransformation = if (mask) {
                androidx.compose.ui.text.input.PasswordVisualTransformation()
            } else {
                androidx.compose.ui.text.input.VisualTransformation.None
            },
            modifier = Modifier.fillMaxWidth(0.8f),
        )
        Spacer(Modifier.height(g * 0.25f))
        androidx.compose.foundation.layout.Box(
            Modifier.fillMaxWidth(0.8f).height(2.dp).background(Content),
        )
    }
}
