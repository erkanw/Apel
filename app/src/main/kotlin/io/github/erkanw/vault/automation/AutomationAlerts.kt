package io.github.erkanw.vault.automation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.erkanw.vault.MainActivity
import io.github.erkanw.vault.R
import io.github.erkanw.vault.chat.ChatAlerts
import io.github.erkanw.vault.chat.notificationText
import io.github.erkanw.vault.store.AgentProfile

/**
 * The "Automations" notifications (PLAN §6.13): a run's result, one per automation (the next run replaces it). Tapping
 * one opens the chat it ran in. On the lock screen it only says "New message".
 */
object AutomationAlerts {
    private const val CHANNEL = "automations"
    private const val FIRST_ID = 6000

    private fun idOf(automationId: String): Int = FIRST_ID + (automationId.hashCode() and 0x7fffffff) % 2000

    fun show(context: Context, automationId: String, title: String, chatId: String, text: String) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.channel_automations), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = context.getString(R.string.channel_automations_description, AgentProfile.name(context))
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            },
        )
        val id = idOf(automationId)
        val open = PendingIntent.getActivity(
            context, id,
            Intent(context, MainActivity::class.java)
                .setAction(ChatAlerts.ACTION_OPEN)
                .putExtra(ChatAlerts.EXTRA_CHAT, chatId)
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
            // No notification permission: the run log and the unread dot still say it.
            Log.w("AutomationAlerts", "could not post the notification", e)
        }
    }
}
