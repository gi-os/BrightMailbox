package com.gios.brightmailbox.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gios.brightmailbox.auth.AuthKind
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
 *
 * The two doors are no longer the same door twice. Gmail asks for an app password and
 * never leaves the app; Outlook opens the browser for OAuth, which Microsoft requires
 * since Basic auth for IMAP finished retiring in April 2026. Neither asks the user to
 * register anything, which is the whole point of v2 — see [Service].
 */
@Composable
fun SetupScreen(vm: MailboxViewModel) {
    val g = LocalGrid.current
    val t = LocalType.current
    val context = LocalContext.current

    /*
     * Scrollable, and no weight(1f) anywhere in it.
     *
     * The title is `title` — 115 design pixels — and with the paragraph under it and a
     * spacer that ate the remainder, the second service row sat below the fold on a
     * 472 dp screen with nothing to scroll. That is not a cosmetic problem: ADD OUTLOOK
     * was the row underneath, so one of the two ways into the app could not be reached
     * at all.
     *
     * A setup screen is the one screen that must survive any screen height and any font
     * scale, because the person reading it has no account yet and therefore no way past
     * it. Fixed spacers plus a scroller; never a weight that assumes the content fits.
     */
    Frame {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
        TopBar("MAILBOX")
        Spacer(Modifier.height(g * 2.2f))
        T("Letters and\nnotices.", t.title)
        Spacer(Modifier.height(g * 1f))
        T(
            "Mail from people goes in Letters. Everything else goes in Notices.",
            t.detail,
            Secondary,
        )
        Spacer(Modifier.height(g * 2f))

        for (s in Service.entries) {
            val ready = vm.repo.auth.isConfigured(s)
            Column(
                Modifier
                    .fillMaxWidth(0.8f)
                    /*
                     * A service with no client id used to be an unclickable row reading
                     * "not set up in this build", which is a wall with the fix written on
                     * the other side of it. Tapping it now asks for the id.
                     */
                    .lightClickable {
                        if (!ready) return@lightClickable vm.go(Screen.ClientId(s))
                        when (s.authKind) {
                            AuthKind.APP_PASSWORD -> vm.go(Screen.Password(s))
                            /*
                             * A plain ACTION_VIEW, deliberately. AppAuth cannot sign in
                             * on this device: its BrowserSelector keeps only browsers
                             * whose intent filter claims both CATEGORY_BROWSABLE and the
                             * bare http scheme with no host, LightOS's browser fails that
                             * test, and the library throws before making a request. An
                             * implicit intent consults neither package visibility nor
                             * "full browser"-ness.
                             */
                            AuthKind.OAUTH -> runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, vm.repo.auth.authorizationUri(s))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }.onFailure { vm.said("No browser here to sign in with.") }
                        }
                    }
                    .padding(bottom = g * 1.1f),
            ) {
                T("ADD ${s.label.uppercase()}", t.button, if (ready) Content else Secondary)
                Spacer(Modifier.height(g * 0.35f))
                Box(Modifier.fillMaxWidth().height(2.dp).background(Content))
                Spacer(Modifier.height(g * 0.25f))
                T(
                    when {
                        !ready -> "needs a client id — tap to add one"
                        s.authKind == AuthKind.APP_PASSWORD -> "an app password, no browser"
                        else -> "opens the browser once"
                    },
                    t.superfine,
                    Secondary,
                )
            }
        }
        Spacer(Modifier.height(g * 1.4f))
        }
    }
}

/**
 * Type an app password.
 *
 * Sixteen characters is a lot on a 3.9" keyboard, so the field forgives the shape Google
 * actually prints them in — four groups of four with spaces — and strips the whitespace
 * before it goes anywhere. The credential is tried against the IMAP server before it is
 * stored, because the alternative is an app that says "added" and then silently never
 * syncs.
 */
@Composable
fun PasswordScreen(vm: MailboxViewModel, service: Service, onScan: () -> Unit = {}) {
    val g = LocalGrid.current
    val t = LocalType.current
    val busy by vm.busy.collectAsStateWithLifecycle()

    var email by remember { mutableStateOf(TextFieldValue("")) }
    var password by remember { mutableStateOf(TextFieldValue("")) }
    var error by remember { mutableStateOf<String?>(null) }

    Frame {
        TopBar(service.label.uppercase())
        Spacer(Modifier.height(g * 1.4f))
        T("An app\npassword.", t.title)
        Spacer(Modifier.height(g * 0.8f))
        T("Not your Google password. A separate sixteen characters.", t.detail, Secondary)

        /*
         * The code route first, because it is the better one and almost nobody would
         * find it if it sat under the keyboard. The password is made on a computer
         * anyway; drawing it there and pointing the camera at it beats typing sixteen
         * characters on a 3.9" screen, and the page it comes from is static and offline.
         *
         * Everything on this screen is measured against the LP3's 472 dp: the title is
         * two lines, the paragraph is one, and there is exactly one weight(1f) so the
         * action bar sits on the fold. Adding a third line of prose here pushes CONNECT
         * off the bottom, where nothing hints that it exists.
         */
        Spacer(Modifier.height(g * 1.3f))
        Column(
            Modifier
                .fillMaxWidth(0.8f)
                .lightClickable(enabled = !busy) { error = null; onScan() },
        ) {
            T("SCAN A CODE", t.button, if (busy) Secondary else Content)
            Spacer(Modifier.height(g * 0.35f))
            Box(Modifier.fillMaxWidth().height(2.dp).background(Content))
            Spacer(Modifier.height(g * 0.25f))
            T("make one at gi-os.github.io/BrightMailbox", t.superfine, Secondary)
        }

        Spacer(Modifier.height(g * 1.3f))
        T("OR TYPE IT", t.detail, Secondary)
        Spacer(Modifier.height(g * 0.4f))
        Field("ADDRESS", email, { email = it; error = null }, g, t)
        Spacer(Modifier.height(g * 1.1f))
        Field("APP PASSWORD", password, { password = it; error = null }, g, t, mask = true)

        error?.let {
            Spacer(Modifier.height(g * 0.7f))
            T(it, t.detail)
        }

        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            T(
                "BACK",
                t.button,
                Secondary,
                Modifier
                    .align(Alignment.CenterVertically)
                    .lightClickable(enabled = !busy) { vm.go(Screen.Setup) },
            )
            T(
                if (busy) "CHECKING" else "CONNECT",
                t.button,
                if (busy) Secondary else Content,
                Modifier
                    .align(Alignment.CenterVertically)
                    .lightClickable(enabled = !busy) {
                        vm.signInWithPassword(
                            service,
                            email.text,
                            password.text,
                        ) { failure -> error = failure }
                    },
            )
        }
        Spacer(Modifier.height(g * 0.6f))
    }
}

/**
 * The OAuth client id, typed in.
 *
 * Only reachable when a build shipped without one. The id is public by design — a mobile
 * client has no secret, and the redirect URI is fixed by the package name — so there is
 * nothing here that should not be typed on a phone. What it is and how to make one is in
 * SETUP.md; the short version is on this screen because nobody reads a file from a phone.
 */
@Composable
fun ClientIdScreen(vm: MailboxViewModel, service: Service) {
    val g = LocalGrid.current
    val t = LocalType.current
    var id by remember { mutableStateOf(TextFieldValue("")) }

    Frame {
        TopBar(service.label.uppercase())
        Spacer(Modifier.height(g * 1.2f))
        T("A client\nid.", t.title)
        Spacer(Modifier.height(g * 0.8f))
        T(
            "This build has none, so ${service.label} cannot sign in yet. Register a free " +
                "app at entra.microsoft.com and paste its Application (client) ID here. " +
                "SETUP.md has the five steps.",
            t.detail,
            Secondary,
        )
        Spacer(Modifier.height(g * 1.2f))
        Field("CLIENT ID", id, { id = it }, g, t)
        Spacer(Modifier.height(g * 0.5f))
        T("Eight-four-four-four-twelve characters, with dashes.", t.superfine, Secondary)

        Spacer(Modifier.weight(1f))
        ActionBar(
            left = "BACK" to { vm.go(Screen.Setup) },
            right = "SAVE" to { vm.setClientId(service, id.text) },
        )
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
        /*
         * "of 1600" is not the number, so it is not drawn at the size of the number.
         *
         * Both halves used to be `t.title` — around 90 sp on this screen — and four digits
         * either side of it is wider than the phone, so the row wrapped and the count sat
         * on two lines. The count keeps the title size and the total sits beside it in the
         * body size, on the same baseline. Neither half wraps: `softWrap = false` is the
         * guarantee, because a count is a number that happens to be long sometimes.
         */
        Row(verticalAlignment = Alignment.Bottom) {
            T("${p?.done ?: 0}", t.title, maxLines = 1, softWrap = false)
            if ((p?.total ?: 0) > 0) {
                T(
                    " of ${p?.total}",
                    t.copy,
                    Secondary,
                    Modifier.padding(bottom = g * 0.55f),
                    maxLines = 1,
                    softWrap = false,
                )
            }
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
