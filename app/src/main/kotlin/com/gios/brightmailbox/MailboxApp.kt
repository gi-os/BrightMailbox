package com.gios.brightmailbox

import android.app.Application
import com.gios.brightmailbox.data.Repo
import com.gios.brightmailbox.notify.Notifier
import com.gios.light.common.report.LightReport
import java.util.concurrent.TimeUnit

class MailboxApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val repo = Repo.get(this)
        // Create the channel up front so the first notification is never silent while
        // Android decides what sound a brand-new channel should have.
        Notifier(this).configure(repo.chime, repo.customSound)

        // Shake, crash and failure reports go to gi-os/light-reports through the
        // family's shared reporter. The label is what the triage skill filters on.
        LightReport.install(
            context = this,
            appName = "Mailbox",
            label = "brightmailbox",
            token = BuildConfig.REPORT_TOKEN,
        )

        /*
         * What a report is allowed to say about a mailbox: nothing that identifies one.
         *
         * Every other app in the family can dump its state freely. This one cannot. An
         * issue carries a screenshot and this block into a tracker, and a mail client's
         * state is somebody's correspondence — so no addresses, no sender names, no
         * subjects, no folder names. Counts and settings only. If a future line here
         * needs an address to be useful, the answer is that the report is not the place.
         */
        LightReport.details = {
            val accounts = runCatching { repo.auth.accounts() }.getOrDefault(emptyList())
            buildString {
                appendLine("accounts: " + if (accounts.isEmpty()) "none" else
                    accounts.groupingBy { it.service.key }.eachCount()
                        .entries.joinToString(", ") { "${it.key} x${it.value}" })
                appendLine("auth: " + accounts.joinToString(", ") { it.service.authKind.name }
                    .ifBlank { "not signed in" })
                val since = System.currentTimeMillis() - repo.lastSync
                appendLine("last sync: " + if (repo.lastSync == 0L) "never"
                    else TimeUnit.MILLISECONDS.toMinutes(since).toString() + " min ago")
                appendLine("ration: " + repo.ration.key)
                appendLine("remote images: " + if (repo.showImages) "on" else "off")
                appendLine("chime: " + repo.chime.key)
            }
        }
    }
}
