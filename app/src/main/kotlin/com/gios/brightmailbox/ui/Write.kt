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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
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
 * Compose and reply.
 *
 * A reply opens with the recipient settled and the cursor in the body — no subject field
 * unless it is edited, and no quoted original appended. The keyboard takes roughly half
 * this screen, so the whole thing is laid out for ~230 dp of usable height and the body
 * scrolls to keep the cursor visible.
 */
@Composable
fun WriteScreen(vm: MailboxViewModel, replyTo: Msg?) {
    val g = LocalGrid.current
    val t = LocalType.current
    // Not vm.busy — that is on for every background sync, and this screen is asking a
    // question about this message, not about the network.
    val busy by vm.sending.collectAsStateWithLifecycle()

    val accounts = vm.repo.auth.accounts()
    var accountId by remember { mutableStateOf(replyTo?.accountId ?: accounts.firstOrNull()?.id.orEmpty()) }
    var to by remember { mutableStateOf(TextFieldValue(replyTo?.sender.orEmpty())) }
    var subject by remember {
        mutableStateOf(TextFieldValue(replyTo?.let { Addr.replySubject(it.subject) }.orEmpty()))
    }
    var body by remember { mutableStateOf(TextFieldValue("")) }
    var editingSubject by remember { mutableStateOf(replyTo == null) }

    /*
     * The draft this screen is editing, 0 until it has been written once.
     *
     * Restored on open: the most recent draft for this reply, or the most recent standalone
     * one. There is no draft list screen and this is deliberate — on a phone with a
     * five-a-day ration, a folder of abandoned half-messages is another pile to feel bad
     * about. What people actually want is for the thing they were writing to still be
     * there, which is this.
     */
    var draftId by remember { mutableStateOf(0L) }
    val drafts by vm.drafts.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(drafts.isNotEmpty()) {
        if (draftId != 0L) return@LaunchedEffect
        val mine = drafts.firstOrNull { it.inReplyTo == replyTo?.messageId } ?: return@LaunchedEffect
        draftId = mine.id
        accountId = mine.accountId.ifBlank { accountId }
        if (mine.to.isNotBlank()) to = TextFieldValue(mine.to)
        if (mine.subject.isNotBlank()) subject = TextFieldValue(mine.subject)
        if (mine.body.isNotBlank()) body = TextFieldValue(mine.body)
    }

    /** Everything the screen is holding, as a row. */
    fun snapshot() = com.gios.brightmailbox.data.Draft(
        id = draftId,
        accountId = accountId,
        to = to.text,
        cc = "",
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
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { if (!sent) vm.keepDraft(snapshot()) }
    }

    Frame {
        Row(
            Modifier.fillMaxWidth().height(g.topBar),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            T(if (replyTo != null) "REPLY" else "WRITE", t.subheading)
            if (accounts.size > 1) {
                T(
                    accountWord(accountId),
                    t.superfine,
                    Secondary,
                    Modifier.lightClickable {
                        val i = accounts.indexOfFirst { it.id == accountId }
                        accountId = accounts[(i + 1) % accounts.size].id
                    },
                )
            }
        }

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).imePadding()) {
            Field("To", to, { to = it }, g, t)
            if (editingSubject) {
                Spacer(Modifier.height(g * 0.8f))
                Field("Subject", subject, { subject = it }, g, t)
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
            Spacer(Modifier.height(g * 1.2f))
            BasicTextField(
                value = body,
                onValueChange = { body = it },
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
                            subject = subject.text.ifBlank { "(no subject)" },
                            body = body.text,
                            inReplyTo = replyTo?.messageId,
                            references = replyTo?.references,
                            threadId = replyTo?.threadId,
                        ),
                        draftId,
                        onFailed = { sent = false },
                    )
                }
            },
            right = "CANCEL" to { vm.go(Screen.Home) },
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
