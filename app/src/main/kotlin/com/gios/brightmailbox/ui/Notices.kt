package com.gios.brightmailbox.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gios.brightmailbox.data.Msg
import com.gios.brightmailbox.ui.theme.LocalGrid
import com.gios.brightmailbox.ui.theme.LocalType
import com.gios.brightmailbox.ui.theme.Screen as Frame
import com.gios.brightmailbox.ui.theme.Secondary
import com.gios.brightmailbox.ui.theme.T
import java.util.Calendar

/**
 * Notices in full, grouped by day.
 *
 * Grouping is by LABEL, not by "the day changed since the last row" — cutting a header
 * on change produces duplicate keys the moment the list is filtered or trimmed, which is
 * the bucketed-list bug that has bitten three of these apps.
 */
@Composable
fun NoticesScreen(vm: MailboxViewModel) {
    val g = LocalGrid.current
    val t = LocalType.current
    val notices by vm.notices.collectAsStateWithLifecycle()
    val count by vm.noticeCount.collectAsStateWithLifecycle()

    val groups: List<Pair<String, List<Msg>>> =
        androidx.compose.runtime.remember(notices) {
            notices.groupBy { dayLabel(it.receivedAt) }.toList()
        }

    Frame {
        androidx.compose.foundation.layout.Row(
            Modifier.fillMaxWidth().height(g.topBar),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            T("NOTICES", t.subheading)
            T("$count", t.detail, Secondary)
        }

        LazyColumn(Modifier.weight(1f)) {
            for ((label, rows) in groups) {
                item(key = "h:$label") {
                    Spacer(Modifier.height(g * 0.8f))
                    T(label.uppercase(), t.superfine, Secondary)
                    Spacer(Modifier.height(g * 0.5f))
                }
                items(rows.size, key = { i -> rows[i].key }) { i ->
                    NoticeRow(
                        rows[i],
                        onClick = { vm.open(rows[i]) },
                        onHold = { vm.star(rows[i]) },
                    )
                    Spacer(Modifier.height(g * 0.45f))
                }
            }
        }

        /*
         * READ / ARCHIVE ALL / BACK.
         *
         * Three items is the SDK's limit for a bar with any text in it, which is what
         * cost "MARK ALL READ" two of its words. ARCHIVE ALL is a move to All Mail, not
         * a delete — every notice it clears is still in the mailbox — which is the only
         * reason a one-tap bulk action on somebody's mail belongs on a bar at all.
         */
        ActionBar(
            left = "READ" to { vm.markAllNoticesRead() },
            middle = "ARCHIVE ALL" to { vm.archiveAllNotices() },
            right = "BACK" to { vm.go(Screen.Home) },
        )
    }
}

private fun dayLabel(at: Long): String {
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = at }
    val sameYear = now.get(Calendar.YEAR) == then.get(Calendar.YEAR)
    val dd = now.get(Calendar.DAY_OF_YEAR) - then.get(Calendar.DAY_OF_YEAR)
    return when {
        sameYear && dd == 0 -> "Today"
        sameYear && dd == 1 -> "Yesterday"
        sameYear && dd in 2..6 ->
            java.text.SimpleDateFormat("EEEE", java.util.Locale.getDefault())
                .format(java.util.Date(at))
        else -> java.text.SimpleDateFormat("d MMMM", java.util.Locale.getDefault())
            .format(java.util.Date(at))
    }
}
