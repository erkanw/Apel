package io.github.erkanw.vault.agent

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import io.github.erkanw.vault.MainActivity
import io.github.erkanw.vault.R
import io.github.erkanw.vault.store.AgentProfile

/**
 * The "Approvals" notification (PLAN §6.13): when a tool waits for your OK while the app isn't on screen, so the turn
 * doesn't sit unseen until it times out. Tapping it opens the app, where the card waits in the chat. It goes away once
 * the request is answered or given up.
 */
object ApprovalAlerts {
    private const val CHANNEL = "approvals"
    private const val ID = 4100

    fun show(context: Context, summary: String) {
        if (android.os.Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val name = AgentProfile.name(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.channel_approvals), NotificationManager.IMPORTANCE_HIGH).apply {
                description = context.getString(R.string.channel_approvals_description, name)
            },
        )
        val open = PendingIntent.getActivity(
            context, ID, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("$name wants your OK")
            .setContentText(summary)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        manager.notify(ID, n)
    }

    fun clear(context: Context) = context.getSystemService(NotificationManager::class.java).cancel(ID)
}
