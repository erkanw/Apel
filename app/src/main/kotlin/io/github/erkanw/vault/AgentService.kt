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
import io.github.erkanw.vault.automation.AutomationRunner
import io.github.erkanw.vault.store.AgentProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Runs while the agent is working, and does nothing else: a foreground service keeps Android from suspending the app
 * when the screen locks or you switch to another app, which would cut long answers off. It shows the quiet "Working"
 * notification (PLAN §6.13) and holds a wake lock while it runs. A chat's turn runs in [ChatViewModel], kept alive by this
 * service; automations run their turns here ([AutomationRunner]). It stays while either needs it (a "hold" each) and
 * stops when neither does.
 */
class AgentService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, notification(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

        // Renewed on every start, and let go when the service ends.
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "apel:reply")
            .apply { acquire(WAKE_LOCK_MS) }
        when (intent?.action) {
            ACTION_RUN -> {
                val app = applicationContext
                scope.launch {
                    try {
                        AutomationRunner.drain(app)
                    } finally {
                        release(app, HOLD_AUTOMATION)
                    }
                }
            }
            // A stop that arrived right after the start: Android insists on startForeground first (done above), then it may end.
            ACTION_STOP -> if (synchronized(holds) { holds.isEmpty() }) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "AgentService"
        private const val CHANNEL = "reply"
        private const val NOTIFICATION_ID = 1
        /** A run may wait up to 30 minutes for your OK, on top of its 10 minutes of work. */
        private const val WAKE_LOCK_MS = 45 * 60_000L
        private const val ACTION_STOP = "io.github.erkanw.vault.STOP_REPLY"
        private const val ACTION_RUN = "io.github.erkanw.vault.RUN_AUTOMATIONS"
        private const val HOLD_CHAT = "chat"
        private const val HOLD_AUTOMATION = "automation"
        private const val HOLD_SIGN_IN = "sign-in"

        /** What keeps the service running now, and how many times: a chat’s turn (once at most), automation starts. */
        private val holds = HashMap<String, Int>()

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        private fun notification(context: Context): Notification {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.channel_working), NotificationManager.IMPORTANCE_LOW).apply {
                    description = context.getString(R.string.channel_working_description, AgentProfile.name(context))
                    setShowBadge(false)
                },
            )
            val open = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            return Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.working_title, AgentProfile.name(context)))
                .setContentText("Tap to open the app")
                .setOngoing(true)
                .setContentIntent(open)
                .apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                    }
                }
                .build()
        }

        /** The same notice, for automations that run in a WorkManager job instead. */
        fun foregroundInfo(context: Context) =
            androidx.work.ForegroundInfo(NOTIFICATION_ID + 1, notification(context), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

        /** Starts the service for [hold]; false when Android doesn't allow it now (in the background, without an exact alarm). */
        private fun acquire(context: Context, hold: String, action: String?): Boolean = try {
            ContextCompat.startForegroundService(context, Intent(context, AgentService::class.java).setAction(action))
            synchronized(holds) { holds[hold] = if (hold == HOLD_CHAT || hold == HOLD_SIGN_IN) 1 else (holds[hold] ?: 0) + 1 }
            true
        } catch (e: Exception) {
            Log.w(TAG, "could not start the service for $hold", e)
            false
        }

        /**
         * Lets go of [hold]; with none left the service ends. A turn can end before the service has called startForeground
         * (one that fails at once, offline), and stopping it then makes Android end the app; so the stop is delivered to the
         * service, which starts foreground first and then stops itself.
         */
        private fun release(context: Context, hold: String) {
            val empty = synchronized(holds) {
                val n = holds[hold] ?: return
                if (n > 1) holds[hold] = n - 1 else holds.remove(hold)
                holds.isEmpty()
            }
            if (!empty) return
            try {
                context.startService(Intent(context, AgentService::class.java).setAction(ACTION_STOP))
            } catch (e: Exception) {
                context.stopService(Intent(context, AgentService::class.java))
            }
        }

        /** A chat's turn starts: keep the app alive. If Android refuses, the chat still works but may be cut off in the background. */
        fun start(context: Context) {
            acquire(context, HOLD_CHAT, null)
        }

        fun stop(context: Context) = release(context, HOLD_CHAT)

        /**
         * The OpenRouter sign-in is open in the browser and the app listens for its answer on this phone. Some phones
         * (Samsung's Freecess) freeze an app as soon as it is behind the browser, and a frozen app can't answer, so the
         * browser says the page can't be reached. An app with a foreground service isn't frozen.
         */
        fun holdForSignIn(context: Context) {
            acquire(context.applicationContext, HOLD_SIGN_IN, null)
        }

        fun releaseSignIn(context: Context) = release(context.applicationContext, HOLD_SIGN_IN)

        /** Runs the due automations in the service; false when it can't be started now (the caller runs them another way). */
        fun runAutomations(context: Context): Boolean = acquire(context.applicationContext, HOLD_AUTOMATION, ACTION_RUN)
    }
}
