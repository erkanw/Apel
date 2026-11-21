package io.github.erkanw.vault.agent

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import io.github.erkanw.vault.MainActivity
import io.github.erkanw.vault.R
import io.github.erkanw.vault.chat.ChatAlerts
import io.github.erkanw.vault.store.AgentProfile

/**
 * The "Approvals" notification (PLAN §6.13): when a tool waits for your OK while the app isn't on screen, or during an
 * automation, so the turn doesn't sit unseen until it times out. Deny answers from the notification; tapping it opens
 * the chat, where the card waits (Allow is given there, behind the fingerprint lock when it is on). It goes away once the
 * request is answered or given up.
 */
object ApprovalAlerts {
    private const val CHANNEL = "approvals"
    private const val ID = 4100
    const val ACTION_DENY = "io.github.erkanw.vault.DENY_APPROVAL"
    const val EXTRA_REQUEST = "request"

    fun show(context: Context, summary: String, requestId: String, chatId: String) {
        if (android.os.Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val name = AgentProfile.name(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.channel_approvals), NotificationManager.IMPORTANCE_HIGH).apply {
                description = context.getString(R.string.channel_approvals_description, name)
            },
        )
        val open = PendingIntent.getActivity(
            context, requestId.hashCode(),
            Intent(context, MainActivity::class.java)
                .setAction(ChatAlerts.ACTION_OPEN)
                .putExtra(ChatAlerts.EXTRA_CHAT, chatId)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val deny = PendingIntent.getBroadcast(
            context, requestId.hashCode(),
            Intent(context, DenyReceiver::class.java).setAction(ACTION_DENY).putExtra(EXTRA_REQUEST, requestId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("$name wants your OK")
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(summary))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(open)
            .addAction(0, "Deny", deny)
            .addAction(0, "Open", open)
            .setAutoCancel(true)
            .build()
        // One notification per request (tagged with its id): a second never replaces the first, and answering one clears only it.
        manager.notify(requestId, ID, n)
    }

    fun clear(context: Context, requestId: String) = context.getSystemService(NotificationManager::class.java).cancel(requestId, ID)

    /** Deny, from the notification. */
    class DenyReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_DENY) return
            val request = intent.getStringExtra(EXTRA_REQUEST) ?: return
            ApprovalGate.answer(request, ApprovalGate.Answer.Deny)
            clear(context, request)
        }
    }
}
