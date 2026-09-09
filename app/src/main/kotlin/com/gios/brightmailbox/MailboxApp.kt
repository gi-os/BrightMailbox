package com.gios.brightmailbox

import android.app.Application
import com.gios.brightmailbox.data.Repo
import com.gios.brightmailbox.notify.Notifier

class MailboxApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val repo = Repo.get(this)
        // Create the channel up front so the first notification is never silent while
        // Android decides what sound a brand-new channel should have.
        Notifier(this).configure(repo.chime, repo.customSound)
    }
}
