package com.gios.brightmailbox.ui

import androidx.compose.foundation.background
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gios.brightmailbox.data.Ration
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

            Section("ACCOUNTS")
            val accounts = vm.repo.auth.accounts()
            if (accounts.isEmpty()) {
                T("None yet.", t.detail, Secondary)
            } else {
                for (a in accounts) {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = g * 0.3f),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        T(a.email, t.detail, maxLines = 1, modifier = Modifier.weight(1f))
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
            T("Last checked ${clock(vm.repo.lastSync)}.", t.detail, Secondary)
            Spacer(Modifier.height(g * 0.6f))
            T("CHECK NOW", t.button, modifier = Modifier.lightClickable { vm.syncNow() })

            Spacer(Modifier.height(g * 2f))
        }
        ActionBar(left = "BACK" to { vm.go(Screen.Home) }, right = null)
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
