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

        MenuItem("SEARCH", "Every pile, archive included.") { vm.go(Screen.Search) }
        MenuItem("VIEW ARCHIVE", "Mail you have put away.") { vm.go(Screen.Archive) }
        MenuItem("DOWNLOADS", "Files saved out of attachments.") { vm.go(Screen.Downloads) }
        MenuItem("ARCHIVE ALL", "Clear the inbox. Nothing is deleted.") { vm.archiveInbox() }
        MenuItem("SETTINGS", "Ration, sound, accounts, signature.") { vm.go(Screen.Settings) }

        Spacer(Modifier.weight(1f))
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

    Frame {
        Row(
            Modifier.fillMaxWidth().height(g.topBar),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            T("ARCHIVE", t.subheading)
            T("${rows.size}", t.detail, Secondary)
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
                        onSwipe = { vm.unarchive(m) },
                        swipeLabel = "UNARCHIVE",
                    )
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
        }

        ActionBar(left = "BACK" to { vm.go(Screen.Menu) }, right = null)
    }
}
