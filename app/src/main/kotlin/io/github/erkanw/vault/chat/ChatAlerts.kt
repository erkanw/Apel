package io.github.erkanw.vault.chat

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.erkanw.vault.MainActivity
import io.github.erkanw.vault.R
import io.github.erkanw.vault.store.AgentProfile

/** The "Replies" notifications: a reply that finished while the app was in the background. Tapping one opens its chat. */
object ChatAlerts {
    const val ACTION_OPEN = "io.github.erkanw.vault.OPEN_CHAT"
    const val EXTRA_CHAT = "chatId"
    private const val CHANNEL = "replies"
    private const val FIRST_ID = 100

    /** True while the app is on screen: a reply is then read as it arrives, with no notification. */
    @Volatile
    var appVisible = false

    private fun idOf(chatId: String): Int = FIRST_ID + (chatId.hashCode() and 0x7fffffff) % 5000

    fun show(context: Context, title: String, chatId: String, text: String) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.channel_replies), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = context.getString(R.string.channel_replies_description, AgentProfile.name(context))
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            },
        )
        val id = idOf(chatId)
        val open = PendingIntent.getActivity(
            context, id,
            Intent(context, MainActivity::class.java)
                .setAction(ACTION_OPEN)
                .putExtra(EXTRA_CHAT, chatId)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val body = notificationText(text)
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body.lineSequence().firstOrNull().orEmpty())
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(
                Notification.Builder(context, CHANNEL)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(context.getString(R.string.notification_new_message))
                    .build(),
            )
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            manager.notify(id, notification)
        } catch (e: SecurityException) {
            // No notification permission: the unread dot in the drawer still says it.
            Log.w("ChatAlerts", "could not post the notification", e)
        }
    }

    fun cancel(context: Context, chatId: String) {
        context.getSystemService(NotificationManager::class.java)?.cancel(idOf(chatId))
    }
}

/**
 * A message as a notification shows it: Markdown marks and runs of blank lines taken out, cut at about 600 characters
 * (on a word where it can).
 */
fun notificationText(text: String, limit: Int = 600): String {
    val plain = text
        .replace(Regex("""(?m)^\s{0,3}#{1,6}\s*"""), "")
        .replace(Regex("""(?m)^\s*[-*•]\s+"""), "• ")
        .replace(Regex("""[*_`]{1,3}"""), "")
        .replace(Regex("""\n{3,}"""), "\n\n")
        .trim()
    if (plain.length <= limit) return plain
    val cut = plain.take(limit)
    val atWord = cut.lastIndexOf(' ').takeIf { it > limit * 0.7 } ?: cut.length
    return cut.take(atWord).trimEnd() + "…"
}
