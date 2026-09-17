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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gios.brightmailbox.hw.WheelScroll
import com.gios.brightmailbox.ui.theme.Content
import com.gios.brightmailbox.ui.theme.LocalGrid
import com.gios.brightmailbox.ui.theme.LocalType
import com.gios.brightmailbox.ui.theme.Screen as Frame
import com.gios.brightmailbox.ui.theme.Secondary
import com.gios.brightmailbox.ui.theme.T
import com.gios.brightmailbox.ui.theme.lightClickable
import kotlinx.coroutines.delay

/**
 * Everything that is not reading today's mail.
 *
 * The gear used to sit on the front screen and go to one place. Five destinations cannot
 * each have a corner of a 27-unit row, so they share one, and the list behind it is a
 * plain column of words — no icons, no rows of chevrons, no sheet sliding up. On this
 * phone a menu is a page, the same as everything else.
 *
 * Ordered by how often it is wanted, not alphabetically: looking something up and looking
 * in the archive are ordinary; clearing the inbox is occasional; settings is rare.
 */
@Composable
fun MenuScreen(vm: MailboxViewModel) {
    val g = LocalGrid.current
    val t = LocalType.current

    Frame {
        TopBar("MENU")
        Spacer(Modifier.height(g * 1.2f))

        /*
         * The list scrolls, because it outgrew the screen.
         *
         * Five destinations fitted; seven do not — each is two lines and about three and a
         * half grid units, and SENT pushed SETTINGS under the action bar on a 472 dp panel
         * at any font scale above the default. This is the same failure the setup screen
         * had in v2.8, and it has the same shape: a fixed Column plus a `weight(1f)` that
         * assumed the content fitted, so the overflow is silently unreachable rather than
         * visibly cut off. A menu that hides one of its own items is worse than no menu.
         */
        val scroll = rememberScrollState()
        WheelScroll(scroll)
        Column(Modifier.weight(1f).verticalScroll(scroll)) {
            /*
             * PARCELS is first, and it is always here.
             *
             * It was drawn only while a parcel was in flight, on the argument that a row
             * that is sometimes absent does not lengthen the page. That was wrong twice
             * over: a route you cannot see is a route you never learn, and the one moment
             * the list is worth opening is when it is empty and you suspect it should not
             * be — the state in which the row used to hide itself. It says what it knows in
             * its second line either way.
             */
            // No scan to trigger: the list is a table and this is a Flow over it.
            val parcels by vm.parcels.collectAsStateWithLifecycle()
            MenuItem(
                "PARCELS",
                if (parcels.isEmpty()) "Nothing on its way yet." else parcelSummary(parcels),
            ) { vm.go(Screen.Parcels) }
            MenuItem("SEARCH", "Every pile, archive included.") { vm.go(Screen.Search) }
            MenuItem("VIEW ARCHIVE", "Mail you have put away.") { vm.go(Screen.Archive) }
            MenuItem("SENT", "What you have written.") { vm.go(Screen.Sent) }
            val waiting by vm.queued.collectAsStateWithLifecycle()
            MenuItem(
                "DRAFTS",
                // A queued message is a draft that will send itself, and this is the only
                // place anybody would look for it — so this line has to say so.
                when (waiting) {
                    0 -> "Messages you started and did not send."
                    1 -> "1 waiting to go out."
                    else -> "$waiting waiting to go out."
                },
            ) { vm.go(Screen.Drafts) }
            MenuItem("DOWNLOADS", "Files saved out of attachments.") { vm.go(Screen.Downloads) }
            MenuItem("ARCHIVE ALL", "Clear the inbox. Nothing is deleted.") { vm.archiveInbox() }
            MenuItem("SETTINGS", "Ration, sound, accounts, signature.") { vm.go(Screen.Settings) }
        }
        ActionBar(left = "BACK" to { vm.go(Screen.Home) }, right = null)
    }
}

/** A destination: the word at button tracking, and one line saying what is through it. */
@Composable
private fun MenuItem(label: String, detail: String, onClick: () -> Unit) {
    val g = LocalGrid.current
    val t = LocalType.current
    Column(
        Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onClick)
            .padding(vertical = g * 0.55f),
    ) {
        T(label, t.button, maxLines = 1)
        Spacer(Modifier.height(g * 0.15f))
        T(detail, t.detail, Secondary, maxLines = 1)
    }
}

/* ----------------------------------------------------------------------- archive */

/**
 * Mail that has been put away.
 *
 * Reads the same as the piles it came from, and opens into the same reader — an archived
 * message is a message.
 *
 * Swiping a row puts it back, and that is a real move on the server rather than a local
 * flag, because a local one would be undone by the next sync: reconciliation only ever
 * archives, so the server is the authority on where mail lives and the only way to
 * disagree with it is to change its mind. See `Repo.unarchive` for why this costs a
 * Message-ID search and a deleted row.
 */
@Composable
fun ArchiveScreen(vm: MailboxViewModel) {
    val g = LocalGrid.current
    val t = LocalType.current
    val rows by vm.archived.collectAsStateWithLifecycle()
    val total by vm.archivedTotal.collectAsStateWithLifecycle()
    val page by vm.archivePage.collectAsStateWithLifecycle()
    val size = vm.archivePageSize
    val first = page * size + 1
    val last = (page * size + rows.size)

    Frame {
        Row(
            Modifier.fillMaxWidth().height(g.topBar),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            T("ARCHIVE", t.screenTitle)
            // Which part of how much, not just how many are on screen — a page has to
            // say where it sits or it reads as the whole archive.
            T(
                if (total == 0) "0" else "$first–$last of $total",
                t.detail,
                Secondary,
                maxLines = 1,
            )
        }

        if (rows.isEmpty()) {
            Spacer(Modifier.height(g * 3f))
            T("Nothing archived yet.", t.copy)
            Spacer(Modifier.height(g * 0.6f))
            T(
                "Swiping a row away, or ARCHIVE ALL, moves mail here — and to All Mail " +
                    "on the server, where every other client can still see it.",
                t.detail,
                Secondary,
            )
            Spacer(Modifier.weight(1f))
        } else {
            val list = rememberLazyListState()
            WheelScroll(list)
            LazyColumn(
                Modifier.weight(1f),
                state = list,
                verticalArrangement = Arrangement.spacedBy(g * 0.9f),
            ) {
                items(rows, key = { it.key }) { m ->
                    /*
                     * Swipe to put it back.
                     *
                     * The mirror of the swipe that archived it, and the same gesture, so
                     * the archive is a place you can move things out of rather than a
                     * one-way chute. Under it this is a real IMAP move back to INBOX, not
                     * a local flag — see `Repo.unarchive`.
                     */
                    LetterRow(
                        m,
                        onClick = { vm.open(m, Screen.Archive) },
                        onHold = { vm.star(m) },
                        left = SwipeSpec("UNARCHIVE") { vm.unarchive(m) },
                    )
                }
            }
        }

        /*
         * Page back and forward, and only when there is somewhere to go.
         *
         * A disabled control on a three-item bar is worth less than the space it takes:
         * on the first page there is no "previous", so there is nothing drawn there.
         */
        ActionBar(
            left = "BACK" to { vm.go(Screen.Menu) },
            middle = if (page > 0) "‹ NEWER" to { vm.archiveGo(page - 1) } else null,
            right = if (last < total) "OLDER ›" to { vm.archiveGo(page + 1) } else null,
        )
    }
}

/* ----------------------------------------------------------------------- flagged */

/**
 * Everything held.
 *
 * Reached from the one line at the top of Home rather than from the menu, because holding
 * a message is a thing you do *to today's mail* and the place you go looking for it is the
 * screen you did it on.
 *
 * Crosses the archive line, which no other list here does: putting a message away does not
 * stop it being held, and a held message you archived is exactly the one you cannot find by
 * remembering who sent it. Archived rows say so, because "why is this not in my inbox" is
 * the obvious next question.
 *
 * Swiping releases, rather than archiving. On every other list the swipe puts a message
 * away; here the thing you want rid of is the hold itself, and archiving from a list of
 * held messages would leave the row exactly where it was.
 */
@Composable
fun FlaggedScreen(vm: MailboxViewModel) {
    val g = LocalGrid.current
    val t = LocalType.current
    val rows by vm.flagged.collectAsStateWithLifecycle()

    Frame {
        Row(
            Modifier.fillMaxWidth().height(g.topBar),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            T("FLAGGED", t.screenTitle)
            T(if (rows.isEmpty()) "" else "${rows.size}", t.detail, Secondary)
        }

        if (rows.isEmpty()) {
            Spacer(Modifier.height(g * 3f))
            T("Nothing held.", t.copy)
            Spacer(Modifier.height(g * 0.6f))
            T(
                "Hold a message on any list to keep it here. A held message ignores the " +
                    "day's ration, stays past midnight and is skipped by ARCHIVE ALL — " +
                    "and it is flagged on the server too, so it is starred wherever else " +
                    "you read your mail.",
                t.detail,
                Secondary,
            )
            Spacer(Modifier.weight(1f))
        } else {
            val list = rememberLazyListState()
            WheelScroll(list)
            LazyColumn(
                Modifier.weight(1f),
                state = list,
                verticalArrangement = Arrangement.spacedBy(g * 0.9f),
            ) {
                items(rows, key = { it.key }) { m ->
                    Column {
                        LetterRow(
                            m,
                            onClick = { vm.open(m, Screen.Flagged) },
                            onHold = { vm.star(m) },
                            left = SwipeSpec("RELEASE") { vm.star(m) },
                        )
                        // Where it is, for the ones that are not in the inbox any more.
                        if (m.archived) {
                            T("archived", t.superfine, Secondary, maxLines = 1)
                        }
                    }
                }
            }
        }

        ActionBar(left = "BACK" to { vm.go(Screen.Home) }, right = null)
    }
}

/* -------------------------------------------------------------------------- sent */

/**
 * What you have written.
 *
 * Read off the server's sent folder each time this opens and held nowhere — the one list
 * in this app that is not a query. Sent mail has no unread state, no ration, no pile and
 * nothing to archive, so the row is the same row with the recipient where the sender
 * would be, and there is no swipe and no hold on it.
 *
 * Sixty messages, which is a long way back for a phone that is not meant to be a desk.
 */
@Composable
fun SentScreen(vm: MailboxViewModel) {
    val g = LocalGrid.current
    val t = LocalType.current
    val rows by vm.sent.collectAsStateWithLifecycle()
    val loading by vm.sentLoading.collectAsStateWithLifecycle()
    val more by vm.sentMore.collectAsStateWithLifecycle()

    // Only if there is nothing yet. Coming back from a message must not refetch the
    // folder — see [MailboxViewModel.loadSent].
    LaunchedEffect(Unit) { vm.loadSent() }

    Frame {
        Row(
            Modifier.fillMaxWidth().height(g.topBar),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            T("SENT", t.screenTitle)
            T(if (rows.isEmpty()) "" else "${rows.size}", t.detail, Secondary)
        }

        if (rows.isEmpty()) {
            Spacer(Modifier.height(g * 3f))
            /*
             * Two sentences, and which one depends on whether the server has answered.
             *
             * This is the only list in the app that can be empty because it has not
             * arrived yet, and "Nothing sent yet" would be a lie for the second or two
             * that takes.
             */
            T(if (loading) "Reading the sent folder…" else "Nothing sent yet.", t.copy)
            if (!loading) {
                Spacer(Modifier.height(g * 0.6f))
                T(
                    "Anything you send from this phone, or from anywhere else, appears " +
                        "here. It is read off the server and kept nowhere.",
                    t.detail,
                    Secondary,
                )
            }
            Spacer(Modifier.weight(1f))
        } else {
            val list = rememberLazyListState()
            WheelScroll(list)
            LazyColumn(
                Modifier.weight(1f),
                state = list,
                verticalArrangement = Arrangement.spacedBy(g * 0.9f),
            ) {
                items(rows, key = { it.key }) { m ->
                    LetterRow(m, onClick = { vm.open(m, Screen.Sent) }, onHold = {})
                }
                /*
                 * The next twenty, asked for by arriving at the bottom.
                 *
                 * An item rather than a scroll listener: a LazyColumn only composes what
                 * is on screen, so this line existing *is* the signal that the end has
                 * been reached. No thresholds, no index arithmetic, and nothing runs while
                 * you are sitting at the top of the list.
                 */
                if (more) {
                    item(key = "more") {
                        LaunchedEffect(rows.size) { vm.loadMoreSent() }
                        T(
                            if (loading) "Getting more…" else "…",
                            t.superfine,
                            Secondary,
                            Modifier.padding(vertical = g * 0.6f),
                        )
                    }
                }
            }
        }

        ActionBar(
            left = "BACK" to { vm.go(Screen.Menu) },
            right = if (rows.isEmpty()) null else "REFRESH" to { vm.loadSent(force = true) },
        )
    }
}

/* ------------------------------------------------------------------------ drafts */

/**
 * Messages you started and did not send.
 *
 * This screen exists because of a bug it fixes rather than a feature anybody asked for.
 * The compose screen restores "the newest draft with no reply target", so writing two
 * separate messages and leaving both saved the older one somewhere with no way back to
 * it. A draft the app has kept and will not show you is worse than one it threw away.
 */
@Composable
fun DraftsScreen(vm: MailboxViewModel) {
    val g = LocalGrid.current
    val t = LocalType.current
    val drafts by vm.drafts.collectAsStateWithLifecycle()

    Frame {
        Row(
            Modifier.fillMaxWidth().height(g.topBar),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            T("DRAFTS", t.screenTitle)
            T("${drafts.size}", t.detail, Secondary)
        }

        if (drafts.isEmpty()) {
            Spacer(Modifier.height(g * 3f))
            T("Nothing half-written.", t.copy)
            Spacer(Modifier.height(g * 0.6f))
            T(
                "Leaving the compose screen keeps what you had typed, and it waits here.",
                t.detail,
                Secondary,
            )
            Spacer(Modifier.weight(1f))
        } else {
            val list = rememberLazyListState()
            WheelScroll(list)
            LazyColumn(
                Modifier.weight(1f),
                state = list,
                verticalArrangement = Arrangement.spacedBy(g * 0.8f),
            ) {
                items(drafts.size, key = { i -> drafts[i].id }) { i ->
                    val d = drafts[i]
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .lightClickable {
                                vm.go(Screen.Write(draftId = d.id, from = Screen.Drafts))
                            },
                    ) {
                        T(
                            d.to.ifBlank { "(no recipient)" },
                            t.copy,
                            maxLines = 1,
                        )
                        Spacer(Modifier.height(g * 0.2f))
                        T(
                            d.subject.ifBlank { d.body.take(60).ifBlank { "(empty)" } },
                            t.detail,
                            Secondary,
                            maxLines = 1,
                        )
                        /*
                         * Where it came from, for the ones that did not start here.
                         *
                         * An imported draft behaves like any other — tap it, finish it,
                         * send it — but it also still exists in the drafts folder of
                         * whatever wrote it, and sending from here does not remove it
                         * there. Saying so is cheaper than pretending the two are one
                         * thing and letting somebody discover the duplicate later.
                         */
                        if (d.remoteId.isNotBlank()) {
                            T("from your mailbox", t.superfine, Secondary, maxLines = 1)
                        }
                        if (d.queued) {
                            T(
                                if (d.tries == 0) "waiting to send"
                                else "waiting to send · ${d.tries} tries",
                                t.superfine,
                                Secondary,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }

        ActionBar(left = "BACK" to { vm.go(Screen.Menu) }, right = null)
    }
}

/* --------------------------------------------------------------------- downloads */

/**
 * Files saved out of attachments.
 *
 * Our own short list, not the phone's Downloads folder read back — that holds everything
 * every app has ever saved and none of it is this app's business. Tapping one hands it to
 * whatever opens that kind of file; the file itself lives in Downloads either way, so this
 * screen going empty would lose nothing.
 */
@Composable
fun DownloadsScreen(vm: MailboxViewModel) {
    val g = LocalGrid.current
    val t = LocalType.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val files = remember { vm.downloads() }

    Frame {
        TopBar("DOWNLOADS")

        if (files.isEmpty()) {
            Spacer(Modifier.height(g * 3f))
            T("Nothing saved yet.", t.copy)
            Spacer(Modifier.height(g * 0.6f))
            T(
                "Open an attachment and choose SAVE, and it goes to the phone's " +
                    "Downloads folder — reachable from a computer, and from any other app.",
                t.detail,
                Secondary,
            )
            Spacer(Modifier.weight(1f))
        } else {
            val list = rememberLazyListState()
            WheelScroll(list)
            LazyColumn(Modifier.weight(1f), state = list) {
                items(files.size, key = { i -> files[i].first }) { i ->
                    val (uri, name, mime) = files[i]
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .lightClickable {
                                runCatching {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW)
                                            .setDataAndType(Uri.parse(uri), mime)
                                            .addFlags(
                                                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                                    Intent.FLAG_ACTIVITY_NEW_TASK,
                                            ),
                                    )
                                }.onFailure { vm.said("Nothing here opens that.") }
                            }
                            .padding(vertical = g * 0.4f),
                    ) {
                        T(name, t.copy, maxLines = 1)
                        T(mime.ifBlank { "file" }, t.superfine, Secondary, maxLines = 1)
                    }
                }
            }
        }

        ActionBar(left = "BACK" to { vm.go(Screen.Menu) }, right = null)
    }
}

/* ------------------------------------------------------------------------ search */

/**
 * One box, every pile.
 *
 * Searches sender, name and subject across Letters, Notices and the archive together,
 * because "where did that go" is a question about a mailbox, not about a pile — and the
 * piles are this app's own invention, so making you remember which one something landed in
 * would be a poor joke.
 *
 * Bodies are not searched: they are files on disk rather than rows, so matching them would
 * mean opening a few hundred of them per keystroke. The line under the box says so, rather
 * than leaving you to conclude the search is broken.
 */
@Composable
fun SearchScreen(vm: MailboxViewModel) {
    val g = LocalGrid.current
    val t = LocalType.current
    var query by remember { mutableStateOf(TextFieldValue("")) }
    val results by vm.results.collectAsStateWithLifecycle()

    // Debounced, so a word typed at speed is one query rather than one per letter.
    LaunchedEffect(query.text) {
        delay(180)
        vm.search(query.text)
    }

    Frame {
        TopBar("SEARCH")
        Spacer(Modifier.height(g * 0.8f))

        BasicTextField(
            value = query,
            onValueChange = { query = it },
            textStyle = t.copy.copy(color = Content),
            cursorBrush = SolidColor(Content),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(g * 0.3f))
        Box(Modifier.fillMaxWidth().height(2.dp).background(Content))
        Spacer(Modifier.height(g * 0.5f))
        T(
            when {
                query.text.length < 2 -> "Sender or subject. Two letters to start."
                results.isEmpty() -> "Nothing matches."
                else -> "${results.size} found"
            },
            t.superfine,
            Secondary,
        )
        Spacer(Modifier.height(g * 0.8f))

        val list = rememberLazyListState()
        WheelScroll(list)
        LazyColumn(
            Modifier.weight(1f),
            state = list,
            verticalArrangement = Arrangement.spacedBy(g * 0.9f),
        ) {
            items(results, key = { it.key }) { m ->
                LetterRow(m, onClick = { vm.open(m, Screen.Search) }, onHold = { vm.star(m) })
            }
            /*
             * The end of what the phone holds is not the end of the mailbox.
             *
             * Local search covers what has been downloaded, which under a short history
             * setting is a few weeks. This asks the server to search the rest — sender,
             * subject and body — and stores what it finds, so a result is a real message
             * that opens and can be replied to rather than a preview.
             *
             * At the bottom, after the local results, because that is where you arrive
             * having decided the answer is not here.
             */
            if (query.text.length >= 2) {
                item {
                    Column(Modifier.fillMaxWidth().padding(top = g * 0.8f)) {
                        T(
                            "SEARCH FURTHER BACK",
                            t.button,
                            modifier = Modifier.lightClickable { vm.searchFurther(query.text) },
                        )
                        Spacer(Modifier.height(g * 0.2f))
                        T("Asks the server for mail this phone never downloaded.", t.superfine, Secondary)
                    }
                }
            }
        }

        ActionBar(left = "BACK" to { vm.go(Screen.Menu) }, right = null)
    }
}
