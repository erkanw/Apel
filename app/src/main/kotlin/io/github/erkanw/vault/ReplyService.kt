package io.github.erkanw.vault

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import io.github.erkanw.vault.store.AgentProfile

/**
 * Runs while the agent is answering, and does nothing else: a foreground service keeps Android from suspending the app
 * when the screen locks or you switch to another app, which would cut long answers off. It shows the quiet "Working"
 * notification (PLAN §6.13) and holds a wake lock for at most ten minutes. The turn itself runs in [ChatViewModel] until
 * Phase 3 moves it into AgentService.
 */
class ReplyService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.channel_working), NotificationManager.IMPORTANCE_LOW).apply {
                description = getString(R.string.channel_working_description, AgentProfile.name(this@ReplyService))
                setShowBadge(false)
            },
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.working_title, AgentProfile.name(this)))
            .setContentText("Tap to open the chat")
            .setOngoing(true)
            .setContentIntent(open)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                }
            }
            .build()
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "apel:reply")
                .apply { acquire(WAKE_LOCK_MS) }
        }
        // A stop that arrived right after the start: Android insists on startForeground first (done above), then it may end.
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ReplyService"
        private const val CHANNEL = "reply"
        private const val NOTIFICATION_ID = 1
        private const val WAKE_LOCK_MS = 10 * 60_000L
        private const val ACTION_STOP = "io.github.erkanw.vault.STOP_REPLY"

        /** True between [start] and [stop]: a stop then goes through the service itself (see [stop]). */
        @Volatile private var requested = false

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, ReplyService::class.java))
                requested = true
            } catch (e: Exception) {
                // Not allowed right now (or no notification permission handling): the chat still works, it just
                // may be cut off if the app is sent to the background.
                Log.w(TAG, "could not start the reply service", e)
            }
        }

        /**
         * Ends the service. A turn can end before the service has called startForeground (one that fails at once, offline),
         * and stopping it then makes Android end the app; so the stop is delivered to the service, which starts
         * foreground first and then stops itself.
         */
        fun stop(context: Context) {
            if (!requested) return
            requested = false
            try {
                context.startService(Intent(context, ReplyService::class.java).setAction(ACTION_STOP))
            } catch (e: Exception) {
                context.stopService(Intent(context, ReplyService::class.java))
            }
        }
    }
}
