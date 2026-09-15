package com.gios.brightmailbox.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gios.brightmailbox.hw.WheelScroll
import com.gios.brightmailbox.parcel.Parcels
import com.gios.brightmailbox.ui.theme.LocalGrid
import com.gios.brightmailbox.ui.theme.LocalType
import com.gios.brightmailbox.ui.theme.Screen as Frame
import com.gios.brightmailbox.ui.theme.Secondary
import com.gios.brightmailbox.ui.theme.T
import com.gios.brightmailbox.ui.theme.lightClickable

/**
 * What is on its way.
 *
 * One row per tracking number, not per email: a shop mails "shipped", the carrier mails
 * "out for delivery" and "delivered", and the newest of those is the truth about the
 * parcel. The merge happens in [com.gios.brightmailbox.data.Repo.scanParcels]; this draws
 * the result.
 *
 * Deliberately silent. In this app a shipping email is a Notice, and Notices never make a
 * sound — being told a parcel is coming is worth a notification, and the email announcing
 * it is not, which is the whole reason a second app exists. Reading the list here is the
 * quiet version: it answers "is anything arriving" without ever interrupting.
 *
 * Every row is a tap that hands the carrier's own tracking page to whatever opens links,
 * the same as a link in a message — see [open]. No screen here tries to draw a map of a
 * parcel's journey, because the carrier already has one and it is better.
 */
@Composable
fun ParcelsScreen(vm: MailboxViewModel) {
    val g = LocalGrid.current
    val t = LocalType.current
    val parcels by vm.parcels.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Bodies arrive while the app is open — a sync, or the prefetch behind it — so the
    // list is re-read on the way in rather than watched.
    LaunchedEffect(Unit) { vm.scanParcels() }

    Frame {
        Row(
            Modifier.fillMaxWidth().height(g.topBar),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            T("PARCELS", t.subheading)
            T("${parcels.size}", t.detail, Secondary)
        }

        if (parcels.isEmpty()) {
            Spacer(Modifier.height(g * 3f))
            T("Nothing on its way.", t.copy)
            Spacer(Modifier.height(g * 0.6f))
            T(
                "A parcel appears here once a shop or a carrier has mailed about it — the " +
                    "tracking number is read out of that message and never leaves the phone. " +
                    "SEARCH AGAIN asks the server, archive included, for mail this phone has " +
                    "not read yet.",
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
                items(parcels, key = { it.id }) { p ->
                    ParcelRow(p) { open(context, it) }
                    Spacer(Modifier.height(g * 0.45f))
                }
            }
        }

        /*
         * The way to ask twice.
         *
         * What is on this list comes from bodies already on the phone, which is the cheap
         * and usually complete answer — but a parcel whose mail was filed on another device,
         * or arrived before its text was ever cached, is simply not here, and a list that
         * is quietly incomplete is worse than one that says so. This searches the archive on
         * the server for shipping mail, stores what it finds as real messages, then reads
         * the list again. A work bar sweeps at the bottom while it runs.
         */
        ActionBar(
            left = "BACK" to { vm.go(Screen.Menu) },
            right = "SEARCH AGAIN" to { vm.sweepParcels() },
        )
    }
}

/**
 * The shop leads, because that is what the parcel is *of* — "Amazon.com" says more than
 * "USPS" when both are true. A carrier's own notice has no shop on it, so the carrier
 * leads there instead. See `Parcels.merchantOf`.
 *
 * State sits opposite in words a person would use ("out for delivery"), and the number
 * underneath with the ETA when the mail stated one. The number is the one thing on this
 * screen nobody can read at a glance, so it is the smallest line.
 */
@Composable
private fun ParcelRow(p: Parcels.Parcel, onOpen: (String) -> Unit) {
    val g = LocalGrid.current
    val t = LocalType.current
    Column(
        Modifier
            .fillMaxWidth()
            .lightClickable(enabled = p.url != null) { p.url?.let(onOpen) }
            .padding(vertical = g * 0.35f),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            T(p.merchant ?: p.carrier.label, t.button, maxLines = 1)
            T(stateWord(p.state), t.detail, Secondary, maxLines = 1)
        }
        Spacer(Modifier.height(g * 0.15f))
        T(
            listOfNotNull(p.carrier.label.takeIf { p.merchant != null }, p.number, p.eta)
                .joinToString(" · "),
            t.superfine,
            Secondary,
            maxLines = 1,
        )
    }
}

/**
 * The carrier's page, in whatever the phone opens links with.
 *
 * Same intent the reader uses for a link in a body — and for the same reason there is no
 * in-app browser: a tracking page is a web page, and this app is a mailbox.
 */
private fun open(context: Context, url: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/** A state in the words a person would use, not the words the enum uses. */
internal fun stateWord(state: Parcels.State): String = when (state) {
    Parcels.State.UNKNOWN -> "label created"
    Parcels.State.SHIPPED -> "on its way"
    Parcels.State.OUT_FOR_DELIVERY -> "out for delivery"
    Parcels.State.DELAYED -> "delayed"
    Parcels.State.DELIVERED -> "delivered"
}

/**
 * The menu row's second line, in the shape DRAFTS already uses: what is in flight, then
 * what is closest. "3 parcels" tells nobody whether to expect a knock.
 */
internal fun parcelSummary(parcels: List<Parcels.Parcel>): String {
    val here = parcels.count { it.state == Parcels.State.OUT_FOR_DELIVERY }
    val away = parcels.size - here
    val soon = when (here) {
        0 -> null
        1 -> "1 out for delivery"
        else -> "$here out for delivery"
    }
    val rest = when (away) {
        0 -> null
        1 -> "1 on its way"
        else -> "$away on their way"
    }
    val line = listOfNotNull(soon, rest).joinToString(", ")
    return line.replaceFirstChar { it.uppercase() } + "."
}
