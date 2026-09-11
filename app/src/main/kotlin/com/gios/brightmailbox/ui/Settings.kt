package com.gios.brightmailbox.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gios.brightmailbox.data.Depth
import com.gios.brightmailbox.data.Ration
import com.gios.brightmailbox.data.Reading
import com.gios.brightmailbox.notify.Chime
import com.gios.brightmailbox.ui.theme.Content
import com.gios.brightmailbox.ui.theme.LocalGrid
import com.gios.brightmailbox.ui.theme.LocalType
import com.gios.brightmailbox.ui.theme.Screen as Frame
import com.gios.brightmailbox.ui.theme.Secondary
import com.gios.brightmailbox.ui.theme.T
import com.gios.brightmailbox.ui.theme.lightClickable

@Composable
fun SettingsScreen(vm: MailboxViewModel) {
    val g = LocalGrid.current
    val t = LocalType.current
    var chime by remember { mutableStateOf(vm.repo.chime) }
    var ration by remember { mutableStateOf(vm.repo.ration) }
    var depth by remember { mutableStateOf(vm.repo.depth) }

    Frame {
        TopBar("SETTINGS")
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {

            Section("DAILY LETTERS")
            /*
             * The two-word switch, borrowed verbatim from BrightMarket's release-channel
             * control. Two words, one underlined; no toggle, no switch, no checkbox.
             */
            Row(horizontalArrangement = Arrangement.spacedBy(g * 1.6f)) {
                for (r in Ration.entries) {
                    Column(Modifier.lightClickable {
                        ration = r
                        vm.setRation(r)
                    }) {
                        T(r.label, t.copy, if (r == ration) Content else Secondary)
                        Spacer(Modifier.height(g * 0.2f))
                        Box(
                            Modifier
                                .width(g * (if (r == Ration.FIVE) 2.2f else 4.2f))
                                .height(2.dp)
                                .background(if (r == ration) Content else androidx.compose.ui.graphics.Color.Transparent),
                        )
                    }
                }
            }
            Spacer(Modifier.height(g * 0.7f))
            T(
                if (ration == Ration.FIVE)
                    "Five Letters a day. The rest wait for tomorrow — nothing is deleted."
                else
                    "Every Letter, as it arrives.",
                t.detail,
                Secondary,
            )

            Section("SOUND")
            T("Letters make a sound. Notices never do.", t.detail, Secondary)
            Spacer(Modifier.height(g * 0.7f))
            for (c in Chime.entries) {
                Row(
                    Modifier.fillMaxWidth().lightClickable {
                        chime = c
                        vm.repo.chime = c
                        com.gios.brightmailbox.notify.Notifier(vm.getApplication())
                            .configure(c, vm.repo.customSound)
                    }.padding(vertical = g * 0.35f),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    T(c.label, t.copy, if (c == chime) Content else Secondary)
                    if (c == chime) T("·", t.copy)
                }
            }

            Section("HOW FAR BACK")
            T(
                "How much of the past a new mailbox reads on its first sync. Mail that " +
                    "arrives afterwards always comes, whatever this says — and choosing less " +
                    "deletes nothing that is already here.",
                t.detail,
                Secondary,
            )
            Spacer(Modifier.height(g * 0.7f))
            /*
             * A list, not the two-word switch above: four options do not fit across 3.9"
             * at the body size, and a row that wraps is worse than a row of rows.
             */
            for (d in Depth.entries) {
                Row(
                    Modifier.fillMaxWidth().lightClickable {
                        depth = d
                        vm.setDepth(d)
                    }.padding(vertical = g * 0.35f),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    T(d.label, t.copy, if (d == depth) Content else Secondary, maxLines = 1)
                    if (d == depth) T("·", t.copy)
                }
            }
            Spacer(Modifier.height(g * 0.5f))
            T(
                "Asking for more than you have runs the deep sync again. It can take a while.",
                t.superfine,
                Secondary,
            )

            Section("ACCOUNTS")
            val accounts by vm.accounts.collectAsStateWithLifecycle()
            if (accounts.isEmpty()) {
                T("None yet.", t.detail, Secondary)
            } else {
                // Tapping one opens it: a mailbox is a thing with a name and a way out,
                // not a line of text.
                for (a in accounts) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .lightClickable { vm.go(Screen.AccountScreen(a.id)) }
                            .padding(vertical = g * 0.3f),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        Column(Modifier.weight(1f)) {
                            T(a.title, t.detail, maxLines = 1)
                            if (a.name.isNotBlank()) {
                                T(a.email, t.superfine, Secondary, maxLines = 1)
                            }
                        }
                        T(a.label, t.superfine, Secondary)
                    }
                }
            }
            Spacer(Modifier.height(g * 0.6f))
            T(
                "ADD ACCOUNT",
                t.button,
                modifier = Modifier.lightClickable { vm.go(Screen.Setup) },
            )

            Section("READING")
            /*
             * What a message opens as.
             *
             * The two-word switch again, the same control the ration uses — two options,
             * one underlined. The ··· sheet in the reader still switches whichever message
             * is on screen; this is only where they start.
             */
            var reading by remember { mutableStateOf(vm.repo.reading) }
            Row(horizontalArrangement = Arrangement.spacedBy(g * 1.6f)) {
                for (r in Reading.entries) {
                    Column(Modifier.lightClickable {
                        reading = r
                        vm.setReading(r)
                    }) {
                        T(r.label, t.copy, if (r == reading) Content else Secondary, maxLines = 1)
                        Spacer(Modifier.height(g * 0.2f))
                        Box(
                            Modifier
                                .width(g * (if (r == Reading.FORMATTED) 2.9f else 3.4f))
                                .height(2.dp)
                                .background(
                                    if (r == reading) Content
                                    else androidx.compose.ui.graphics.Color.Transparent,
                                ),
                        )
                    }
                }
            }
            Spacer(Modifier.height(g * 0.7f))
            T(
                if (reading == Reading.FORMATTED)
                    "Messages open the way their sender built them."
                else
                    "Messages open as text. No images, no columns, no typefaces.",
                t.detail,
                Secondary,
            )
            Spacer(Modifier.height(g * 1.1f))

            var images by remember { mutableStateOf(vm.repo.showImages) }
            T(
                "Messages are shown the way their sender built them. Remote images are " +
                    "part of that — and a remote image tells the sender the moment you " +
                    "opened the message.",
                t.detail,
                Secondary,
            )
            Spacer(Modifier.height(g * 0.7f))
            Row(
                Modifier.fillMaxWidth().lightClickable {
                    images = !images
                    vm.repo.showImages = images
                }.padding(vertical = g * 0.35f),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                T("Load images", t.copy, if (images) Content else Secondary)
                T(if (images) "on" else "off", t.copy, Secondary)
            }

            Section("SIGNATURE")
            var signature by remember { mutableStateOf(TextFieldValue(vm.repo.signature)) }
            T(
                "Added to the end of everything you send, after the \"--\" line every " +
                    "mail client uses to fold a signature away when quoting.",
                t.detail,
                Secondary,
            )
            Spacer(Modifier.height(g * 0.7f))
            BasicTextField(
                value = signature,
                onValueChange = {
                    signature = it
                    // Saved as typed. There is no Save button anywhere in this app and
                    // adding one here would be the only one.
                    vm.repo.signature = it.text
                },
                textStyle = t.copy.copy(color = Content),
                cursorBrush = SolidColor(Content),
                modifier = Modifier.fillMaxWidth(0.9f),
            )
            Spacer(Modifier.height(g * 0.3f))
            Box(Modifier.fillMaxWidth(0.9f).height(2.dp).background(Content))
            if (signature.text.isBlank()) {
                Spacer(Modifier.height(g * 0.3f))
                T("Nothing is added while this is empty.", t.superfine, Secondary)
            }

            Section("SORTING")
            val rules by vm.rules.collectAsStateWithLifecycle()
            T(
                if (rules.isEmpty()) "No senders moved yet."
                else "${rules.size} sender${if (rules.size == 1) "" else "s"} moved by hand.",
                t.detail,
                Secondary,
            )
            Spacer(Modifier.height(g * 0.6f))
            T("SENDER RULES", t.button, modifier = Modifier.lightClickable { vm.go(Screen.Rules) })

            Section("SYNC")
            T(
                if (vm.repo.lastSync == 0L) "Not checked yet."
                else "Last checked ${clock(vm.repo.lastSync)}.",
                t.detail,
                Secondary,
            )
            // White, not Secondary: this is the line that answers "why is there no mail",
            // and it has to be the thing the eye lands on rather than more grey.
            vm.repo.lastError?.let {
                Spacer(Modifier.height(g * 0.4f))
                T("Last check failed — $it", t.detail)
            }
            Spacer(Modifier.height(g * 0.6f))
            T("CHECK NOW", t.button, modifier = Modifier.lightClickable { vm.syncNow() })

            Spacer(Modifier.height(g * 2f))
        }
        ActionBar(left = "BACK" to { vm.go(Screen.Home) }, right = null)
    }
}

/**
 * One mailbox: give it a name, or remove it.
 *
 * The name is the point. With one account the provider's word is enough — every row says
 * "gmail" and every row means the same mailbox. With two it identifies nothing, and the
 * question a row has to answer is "which of mine did this come to". So a name typed here
 * replaces that word everywhere an account is named: the Letters rows, the reader's
 * header, the line above a reply.
 *
 * Removing is on this screen rather than in the list because a mailbox is not something to
 * lose to a mis-tap: it takes a second tap that says what will happen.
 */
@Composable
fun AccountDetailScreen(vm: MailboxViewModel, id: String) {
    val g = LocalGrid.current
    val t = LocalType.current
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val account = accounts.firstOrNull { it.id == id }

    // The account can go away under this screen — REMOVE does exactly that — and a screen
    // whose subject no longer exists should leave rather than draw blanks.
    if (account == null) {
        LaunchedEffect(Unit) { vm.go(Screen.Settings) }
        return
    }

    var name by remember(id) { mutableStateOf(TextFieldValue(account.name)) }
    var confirming by remember(id) { mutableStateOf(false) }

    Frame {
        TopBar(account.label.uppercase())
        Spacer(Modifier.height(g * 1.2f))
        T(account.email, t.heading, maxLines = 2)

        Spacer(Modifier.height(g * 1.6f))
        T("CALL IT", t.superfine, Secondary)
        Spacer(Modifier.height(g * 0.4f))
        Field("NAME", name, { name = it }, g, t)
        Spacer(Modifier.height(g * 0.5f))
        T(
            "Shown wherever this mailbox is named. Leave it empty to go back to " +
                "\"${account.label}\".",
            t.detail,
            Secondary,
        )

        Spacer(Modifier.weight(1f))

        if (confirming) {
            T("Remove ${account.title}?", t.copy)
            Spacer(Modifier.height(g * 0.4f))
            T(
                "Its mail goes with it. Nothing is deleted on the server.",
                t.detail,
                Secondary,
            )
            Spacer(Modifier.height(g * 0.8f))
            ActionBar(
                left = "KEEP IT" to { confirming = false },
                right = "REMOVE" to { vm.forgetAccount(id) },
            )
        } else {
            T(
                "REMOVE THIS MAILBOX",
                t.superfine,
                Secondary,
                Modifier.lightClickable { confirming = true },
            )
            Spacer(Modifier.height(g * 0.8f))
            ActionBar(
                left = "BACK" to { vm.go(Screen.Settings) },
                right = "SAVE" to { vm.renameAccount(id, name.text) },
            )
        }
    }
}

/** Sender rules — every override, each removable. The escape hatch. */
@Composable
fun RulesScreen(vm: MailboxViewModel) {
    val g = LocalGrid.current
    val t = LocalType.current
    val rules by vm.rules.collectAsStateWithLifecycle()

    Frame {
        TopBar("SENDER RULES")
        if (rules.isEmpty()) {
            Spacer(Modifier.height(g * 4f))
            T("Nothing here yet.", t.heading)
            Spacer(Modifier.height(g * 0.6f))
            T(
                "Move a sender between Letters and Notices and it will appear here.",
                t.detail,
                Secondary,
            )
            Spacer(Modifier.weight(1f))
        } else {
            LazyColumn(Modifier.weight(1f)) {
                items(rules.size, key = { i -> rules[i].address }) { i ->
                    val r = rules[i]
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = g * 0.4f),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            T(r.address, t.detail, maxLines = 1)
                            T(
                                if (r.pile == "LETTER") "always a Letter" else "always a Notice",
                                t.superfine,
                                Secondary,
                            )
                        }
                        T(
                            "REMOVE",
                            t.superfine,
                            Secondary,
                            Modifier.lightClickable { vm.dropRule(r.address) },
                        )
                    }
                }
            }
        }
        ActionBar(left = "BACK" to { vm.go(Screen.Settings) }, right = null)
    }
}

@Composable
private fun Section(label: String) {
    val g = LocalGrid.current
    val t = LocalType.current
    Spacer(Modifier.height(g * 1.6f))
    T(label, t.superfine, Secondary)
    Spacer(Modifier.height(g * 0.6f))
}
