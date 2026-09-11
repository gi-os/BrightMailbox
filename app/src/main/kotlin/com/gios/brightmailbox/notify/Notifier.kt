package com.gios.brightmailbox.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.net.Uri
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.gios.brightmailbox.MainActivity
import com.gios.brightmailbox.R

/** The three sounds offered in Settings, in this order. */
enum class Chime(val key: String, val label: String) {
    DEFAULT("default", "Default"),
    MAIL("mail", "You've Got Mail"),
    MUSIC_BOX("music_box", "Music Box"),
    CUSTOM("custom", "Custom");

    companion object {
        fun of(key: String?): Chime = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}

/**
 * Notifications.
 *
 * Two decisions worth not relitigating:
 *
 * **Letters notify. Notices never do.** That single rule is most of the reason to install
 * this app rather than any other mail client, and it is stated in Settings in one
 * sentence so it is a promise rather than a behavior someone has to infer.
 *
 * **No heads-up box of our own.** BrightControl has drawn banners for any app off its
 * notification listener since v3.65, and it draws the lock-face row too. All this has to
 * do is post a standard notification at importance >= 3 — which is the filter LightOS
 * itself uses — and BrightControl picks it up for free. Drawing our own box would mean
 * joining AlertHandoff.CONSUMERS and getting two boxes stacked until BrightControl is
 * taught about us, which is exactly the bug that hit BrightNotebook.
 */
class Notifier(private val context: Context) {

    private val nm = NotificationManagerCompat.from(context)

    /**
     * Rebuild the channel.
     *
     * A NotificationChannel's sound is immutable after creation, so changing the chime
     * means deleting the channel and making a new one with a new id. Anything else
     * silently keeps the old sound and looks like the setting does nothing.
     */
    fun configure(chime: Chime, customUri: String?) {
        val id = channelId(chime, customUri)
        // Drop channels for every other chime, or the shade fills with dead entries.
        nm.notificationChannels
            .filter { it.id.startsWith(PREFIX) && it.id != id }
            .forEach { nm.deleteNotificationChannel(it.id) }

        if (nm.getNotificationChannel(id) != null) return

        val channel = NotificationChannel(id, "Letters", NotificationManager.IMPORTANCE_DEFAULT)
        channel.description = "New mail from people. Notices never notify."
        channel.enableVibration(true)
        soundFor(chime, customUri)?.let {
            channel.setSound(
                it,
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .build(),
            )
        }
        nm.createNotificationChannel(channel)
    }

    private fun soundFor(chime: Chime, customUri: String?): Uri? = when (chime) {
        Chime.DEFAULT -> Settings.System.DEFAULT_NOTIFICATION_URI
        Chime.MAIL -> raw("snd_youve_got_mail")
        Chime.MUSIC_BOX -> raw("snd_music_box")
        Chime.CUSTOM -> customUri?.let(Uri::parse) ?: Settings.System.DEFAULT_NOTIFICATION_URI
    }

    /**
     * Look the sound up by NAME, not by an R.raw constant.
     *
     * The sounds are synthesized by scripts/build_sounds.py before the Gradle build, so
     * res/raw is empty in a fresh checkout. Referencing R.raw.snd_* directly would make
     * the app fail to COMPILE on a machine without python3 — this way it compiles, and a
     * missing sound simply falls back to the system default at runtime.
     */
    private fun raw(name: String): Uri? {
        val id = context.resources.getIdentifier(name, "raw", context.packageName)
        if (id == 0) return Settings.System.DEFAULT_NOTIFICATION_URI
        return Uri.parse("android.resource://${context.packageName}/$id")
    }

    /**
     * The id encodes the sound, so switching chimes forces a fresh channel. The custom
     * URI is hashed in for the same reason — picking a different file has to change the
     * id or the old sound survives.
     */
    private fun channelId(chime: Chime, customUri: String?): String =
        PREFIX + chime.key + if (chime == Chime.CUSTOM) "_" + (customUri?.hashCode() ?: 0) else ""

    /**
     * Announce new Letters.
     *
     * One notification for one Letter names the sender; several collapse to a count.
     * No preview body: this is a phone people carry partly so their mail is not readable
     * over a shoulder, and a heads-up banner is the most readable surface there is.
     */
    fun letters(count: Int, sender: String?, subject: String?, chime: Chime, customUri: String?) {
        if (count <= 0) return
        configure(chime, customUri)

        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val title = if (count == 1 && !sender.isNullOrBlank()) sender else "$count letters"
        val text = if (count == 1) subject.orEmpty() else "from people, waiting"

        val n = NotificationCompat.Builder(context, channelId(chime, customUri))
            .setSmallIcon(R.drawable.ic_stat_mail)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            // DEFAULT is importance 3, which is the cutoff LightOS and BrightControl
            // both use. PRIORITY_LOW here would post a notification that no banner and
            // no lock-face row would ever show.
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_EMAIL)
            .build()

        runCatching { nm.notify(NOTE_ID, n) }   // throws if POST_NOTIFICATIONS was refused
    }

    fun clear() = nm.cancel(NOTE_ID)

    private companion object {
        const val PREFIX = "letters_"
        const val NOTE_ID = 1
    }
}
