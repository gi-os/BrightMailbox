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
    val busy by vm.busy.collectAsStateWithLifecycle()

    val accounts = vm.repo.auth.accounts()
    var accountId by remember { mutableStateOf(replyTo?.accountId ?: accounts.firstOrNull()?.id.orEmpty()) }
    var to by remember { mutableStateOf(TextFieldValue(replyTo?.sender.orEmpty())) }
    var subject by remember {
        mutableStateOf(TextFieldValue(replyTo?.let { Addr.replySubject(it.subject) }.orEmpty()))
    }
    var body by remember { mutableStateOf(TextFieldValue("")) }
    var editingSubject by remember { mutableStateOf(replyTo == null) }

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
                    )
                }
            },
            right = "CANCEL" to { vm.go(Screen.Home) },
        )
    }
}

/**
 * The only field style in the app: a label, then an 80%-width rule three design pixels
 * thick. No floating label, no filled container, no focus colour.
 */
@Composable
private fun Field(
    label: String,
    value: TextFieldValue,
    onChange: (TextFieldValue) -> Unit,
    g: com.gios.brightmailbox.ui.theme.Grid,
    t: com.gios.brightmailbox.ui.theme.Type,
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
            modifier = Modifier.fillMaxWidth(0.8f),
        )
        Spacer(Modifier.height(g * 0.25f))
        androidx.compose.foundation.layout.Box(
            Modifier.fillMaxWidth(0.8f).height(2.dp).background(Content),
        )
    }
}
