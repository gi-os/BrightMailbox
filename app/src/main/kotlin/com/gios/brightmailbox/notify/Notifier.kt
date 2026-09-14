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

    /**
     * Play a chime, so choosing one is a decision you can hear.
     *
     * Picking a notification sound and being told nothing is a setting you have to guess
     * at — the only way to hear it was to wait for mail to arrive, which is the one thing
     * you cannot arrange. A channel's sound cannot be previewed by posting a notification
     * either: Android rate-limits repeats of the same channel and would go silent after
     * the first tap.
     *
     * Uses the NOTIFICATION stream on purpose. A preview that comes out at media volume
     * is not a preview of what the phone will actually do.
     */
    fun play(chime: Chime, customUri: String?) {
        val uri = soundFor(chime, customUri) ?: return
        runCatching {
            android.media.RingtoneManager.getRingtone(context, uri)?.apply {
                if (android.os.Build.VERSION.SDK_INT >= 28) {
                    audioAttributes = android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                }
                play()
            }
        }
    }

    private fun soundFor(chime: Chime, customUri: String?): Uri? = when (chime) {
        Chime.DEFAULT -> Settings.System.DEFAULT_NOTIFICATION_URI
        Chime.MAIL -> raw("snd_youve_got_mail")
        Chime.MUSIC_BOX -> raw("snd_music_box")
        Chime.CUSTOM -> customUri?.let(Uri::parse) ?: Settings.System.DEFAULT_NOTIFICATION_URI
    }

    /**
     * Look the sound up by NAME, and hand out a URI that names it too.
     *
     * The sounds are synthesized by scripts/build_sounds.py before the Gradle build, so
     * res/raw is empty in a fresh checkout. Referencing R.raw.snd_* directly would make
     * the app fail to COMPILE on a machine without python3 — this way it compiles, and a
     * missing sound simply falls back to the system default at runtime.
     *
     * **The URI must be the `/raw/<name>` form, never `android.resource://pkg/<number>`.**
     * A numeric resource id is assigned by aapt2 at build time and is renumbered whenever
     * the set of resources changes — adding one drawable can shift every id after it. That
     * is harmless for an id read at runtime and fatal for one that gets **stored**, and a
     * notification channel stores its sound URI forever, in the system's own settings,
     * outside the app.
     *
     * That is exactly what happened here: the live channel pointed at
     * `android.resource://com.gios.brightmailbox/2131361793`, a number that meant
     * `snd_youve_got_mail` in whichever build created the channel and points into a
     * different resource type today. The chosen chime never played, and new mail arrived
     * silent. The preview in Settings was fine throughout, because it resolves the id
     * fresh on every tap — which is why the setting looked like it worked.
     *
     * The path form is resolved by name, by the system, at the moment it plays. It cannot
     * go stale.
     */
    private fun raw(name: String): Uri? {
        val id = context.resources.getIdentifier(name, "raw", context.packageName)
        if (id == 0) return Settings.System.DEFAULT_NOTIFICATION_URI
        return Uri.parse("android.resource://${context.packageName}/raw/$name")
    }

    /**
     * The id encodes the sound ITSELF, not just which chime was picked.
     *
     * A channel's sound cannot be changed after creation, so the only way to change it is
     * to create a different channel — which means any difference in the sound has to show
     * up in the id. Keying on the chime alone was not enough: a channel created with a
     * stale URI kept the same id, `configure` saw the id already existed and returned, and
     * the wrong sound survived every launch and every update.
     *
     * Hashing the resolved URI closes that for good. Anything that changes what would be
     * set — a different chime, a different custom file, or the same chime resolving to a
     * different URI than the stored channel was built with — produces a new id, and the
     * sweep above deletes the old one. It also repairs itself on the next launch for
     * anybody carrying a channel from an earlier build, with nothing to uninstall.
     */
    private fun channelId(chime: Chime, customUri: String?): String {
        val sound = soundFor(chime, customUri)?.toString().orEmpty()
        return PREFIX + chime.key + "_" + Integer.toHexString(sound.hashCode())
    }

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
