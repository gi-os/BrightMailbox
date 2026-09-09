package com.gios.brightmailbox.ui

import android.content.Intent
import android.net.Uri
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gios.brightmailbox.auth.Service
import com.gios.brightmailbox.ui.theme.Content
import com.gios.brightmailbox.ui.theme.LocalGrid
import com.gios.brightmailbox.ui.theme.LocalType
import com.gios.brightmailbox.ui.theme.Screen as Frame
import com.gios.brightmailbox.ui.theme.Secondary
import com.gios.brightmailbox.ui.theme.T
import com.gios.brightmailbox.ui.theme.lightClickable

/**
 * Fresh install. One line of what the app is, two ways in, nothing else.
 */
@Composable
fun SetupScreen(vm: MailboxViewModel) {
    val g = LocalGrid.current
    val t = LocalType.current
    val context = LocalContext.current

    Frame {
        TopBar("MAILBOX")
        Spacer(Modifier.height(g * 4f))
        T("Letters and\nnotices.", t.title)
        Spacer(Modifier.height(g * 1.3f))
        T(
            "Mail from people goes in Letters. Everything else goes in Notices. " +
                "You set how many Letters a day.",
            t.detail,
            Secondary,
        )
        Spacer(Modifier.weight(1f))

        for (s in Service.entries) {
            val configured = vm.repo.auth.isConfigured(s)
            Column(
                Modifier
                    .fillMaxWidth(0.8f)
                    .lightClickable(enabled = configured) {
                        /*
                         * A plain ACTION_VIEW, deliberately. AppAuth cannot sign in on
                         * this device: its BrowserSelector keeps only browsers whose
                         * intent filter claims both CATEGORY_BROWSABLE and the bare http
                         * scheme with no host, LightOS's browser fails that test, and
                         * the library throws before making a request. An implicit intent
                         * consults neither package visibility nor "full browser"-ness.
                         */
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, vm.repo.auth.authorizationUri(s))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }.onFailure { vm.said("No browser here. Use the QR route below.") }
                    }
                    .padding(bottom = g * 1.1f),
            ) {
                T(
                    "ADD ${s.label.uppercase()}",
                    t.button,
                    if (configured) Content else Secondary,
                )
                Spacer(Modifier.height(g * 0.35f))
                Box(Modifier.fillMaxWidth().height(2.dp).background(Content))
                if (!configured) {
                    Spacer(Modifier.height(g * 0.25f))
                    T("needs a client id — scan one in Settings", t.superfine, Secondary)
                }
            }
        }
        Spacer(Modifier.height(g * 1.4f))
    }
}

/**
 * First sync. Progress is a count and the two piles fill in front of you.
 *
 * No spinner. There is no spinner anywhere in this app: progress is either a number of
 * things done out of things to do, or it is a static line of text.
 */
@Composable
fun FirstSyncScreen(vm: MailboxViewModel) {
    val g = LocalGrid.current
    val t = LocalType.current
    val p by vm.progress.collectAsStateWithLifecycle()

    Frame {
        TopBar("FIRST SYNC")
        Spacer(Modifier.height(g * 3f))
        Row(verticalAlignment = Alignment.Bottom) {
            T("${p?.done ?: 0}", t.title)
            T(
                if ((p?.total ?: 0) > 0) " of ${p?.total}" else "",
                t.title,
                Secondary,
            )
        }
        Spacer(Modifier.height(g * 0.7f))
        T("messages read and sorted", t.detail, Secondary)

        Spacer(Modifier.height(g * 2.4f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            T("Letters", t.copy)
            T("${p?.letters ?: 0}", t.copy)
        }
        Spacer(Modifier.height(g * 0.8f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            T("Notices", t.copy, Secondary)
            T("${p?.notices ?: 0}", t.copy, Secondary)
        }

        Spacer(Modifier.height(g * 2f))
        T(
            "Sorting keeps going with the screen off.",
            t.detail,
            Secondary,
        )
        Spacer(Modifier.weight(1f))
        ActionBar(left = "START READING" to { vm.go(Screen.Home) }, right = null)
    }
}
