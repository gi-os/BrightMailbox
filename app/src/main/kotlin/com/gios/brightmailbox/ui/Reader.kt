package com.gios.brightmailbox.ui

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
import androidx.compose.ui.res.painterResource
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

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(g * 1.4f))
            Row(verticalAlignment = Alignment.Bottom) {
                T(msg.senderName, t.heading, maxLines = 2, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.height(g * 0.5f))
                T(" " + accountWord(msg.accountId), t.superfine, Secondary)
            }
            Spacer(Modifier.height(g * 0.4f))
            T(longStamp(msg.receivedAt), t.detail, Secondary)
            Spacer(Modifier.height(g * 1.5f))
            T(msg.subject.ifBlank { "(no subject)" }, t.copy)

            // Three clear units. This is the whole trick.
            Spacer(Modifier.height(g * 3f))

            T(
                body?.text ?: msg.snippet,
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
            T("SHOW ORIGINAL", t.button, Secondary, Modifier.lightClickable { }, maxLines = 1)
            T("CLOSE", t.button, Secondary, Modifier.lightClickable(onClick = onClose), maxLines = 1)
        }
    }
}

private fun longStamp(at: Long): String =
    SimpleDateFormat("EEE h:mma", Locale.getDefault()).format(Date(at)).lowercase()
