package com.gios.brightmailbox.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.gios.brightmailbox.data.Repo
import com.gios.brightmailbox.notify.Notifier
import java.util.concurrent.TimeUnit

/**
 * The background pass.
 *
 * WorkManager rather than anything Firebase-shaped: it sits on JobScheduler and needs no
 * Play Services, which is the only reason background sync is possible on LightOS at all.
 * There is no push here — Gmail's watch API goes through Pub/Sub to Firebase, and Graph
 * webhooks need a public endpoint to deliver to. Fifteen minutes is WorkManager's floor
 * for periodic work and is fine for mail that is deliberately not urgent.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val repo = Repo.get(applicationContext)
        if (!repo.auth.isSignedIn) return Result.success()

        val out = try {
            repo.sync(limit = 20)
        } catch (e: Exception) {
            // Retry rather than fail: a failed periodic worker is not rescheduled, and
            // the app would then quietly stop syncing until it is next opened.
            return Result.retry()
        }

        if (out.newLetters > 0) {
            Notifier(applicationContext).letters(
                count = out.newLetters,
                sender = out.firstLetter?.senderName,
                subject = out.firstLetter?.subject,
                chime = repo.chime,
                customUri = repo.customSound,
            )
        }

        // Cheap, and it is what makes ranking improve without the user doing anything.
        if (out.fetched > 0) repo.retrain()
        return Result.success()
    }

    companion object {
        private const val NAME = "mailbox-sync"

        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()
            WorkManager.getInstance(context)
                // KEEP, not UPDATE: REPLACE on every app start resets the period and a
                // phone that is opened often would never actually reach a run.
                .enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, req)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(NAME)
        }
    }
}
