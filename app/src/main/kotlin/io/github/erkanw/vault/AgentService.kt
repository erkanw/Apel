package io.github.erkanw.vault

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import io.github.erkanw.vault.automation.AutomationRunner
import io.github.erkanw.vault.automation.BackgroundMode
import io.github.erkanw.vault.store.AgentProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Keeps the app alive while the agent works and, when "Keep running in the background" is on ([BackgroundMode]), all the
 * time. A foreground service keeps Android from suspending or ending the app when the screen locks, you switch apps or
 * clear the recent apps, which would cut answers off and hold automations back. While the agent works it shows the quiet
 * "Working" notification and holds a wake lock; while it only waits in the background it shows "Running in the
 * background" and holds no wake lock. A chat's turn runs in [ChatViewModel], kept alive by this service; automations run
 * their turns here ([AutomationRunner]). It stays while anything needs it (a "hold" each) and stops when nothing does.
 */
class AgentService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    /** The type it is in the foreground with now; 0 before the first [goForeground]. */
    private var foregroundType = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        synchronized(holds) { live = this }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        synchronized(holds) {
            live = this
            // Restarted by Android after it ended the app (only happens while running in the background): wait again.
            if (intent == null && BackgroundMode.isOn(this)) holds[HOLD_BACKGROUND] = 1
        }
        // Android insists on startForeground first, even for a stop that arrived right after the start.
        if (!goForeground(start = true)) {
            synchronized(holds) { if (live === this) live = null }
            stopSelf()
            return START_NOT_STICKY
        }
        val action = intent?.action
        if (action == ACTION_RUN) launchRun(applicationContext)
        // A stop, or a restart by Android with nothing left to hold it (a chat's start has no action, but an intent).
        if (action == ACTION_STOP || intent == null) {
            val stop = synchronized(holds) { holds.isEmpty().also { if (it && live === this) live = null } }
            if (stop) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                foregroundType = 0
                stopSelf()
            }
        }
        return if (BackgroundMode.isOn(this)) START_STICKY else START_NOT_STICKY
    }

    /**
     * Shows the notice for what holds the service now, in the foreground type that fits: special use while it runs in the
     * background (it may run for days), data sync while it only works. The wake lock is held only while working.
     */
    private fun goForeground(start: Boolean = false): Boolean {
        val (working, background) = synchronized(holds) { holds.keys.any { it != HOLD_BACKGROUND } to (HOLD_BACKGROUND in holds) }
        val notice = if (background && !working) backgroundNotification(this) else workingNotification(this)
        val type = if (background && Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        return try {
            // Every start needs startForeground, and so does a change of type; a new text alone is a plain update.
            if (start || type != foregroundType) {
                startForeground(NOTIFICATION_ID, notice, type)
                foregroundType = type
            } else {
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notice)
            }
            if (working) holdWakeLock() else dropWakeLock()
            true
        } catch (e: Exception) {
            Log.w(TAG, "could not go foreground", e)
            false
        }
    }

    /** Renewed whenever work starts, and let go when only the background hold is left. */
    private fun holdWakeLock() {
        dropWakeLock()
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "apel:reply")
            .apply { acquire(WAKE_LOCK_MS) }
    }

    private fun dropWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    override fun onDestroy() {
        dropWakeLock()
        synchronized(holds) { if (live === this) live = null }
        super.onDestroy()
    }

    /** "Turn off" on the background notification. */
    class TurnOffReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_TURN_OFF) BackgroundMode.set(context.applicationContext, false)
        }
    }

    companion object {
        private const val TAG = "AgentService"
        private const val CHANNEL = "reply"
        private const val CHANNEL_BACKGROUND = "background"
        private const val NOTIFICATION_ID = 1
        /** A run may wait up to 30 minutes for your OK, on top of its 10 minutes of work. */
        private const val WAKE_LOCK_MS = 45 * 60_000L
        private const val ACTION_STOP = "io.github.erkanw.vault.STOP_REPLY"
        private const val ACTION_RUN = "io.github.erkanw.vault.RUN_AUTOMATIONS"
        private const val ACTION_TURN_OFF = "io.github.erkanw.vault.BACKGROUND_OFF"
        private const val HOLD_CHAT = "chat"
        private const val HOLD_AUTOMATION = "automation"
        private const val HOLD_SIGN_IN = "sign-in"
        private const val HOLD_BACKGROUND = "background"

        /** What keeps the service running now, and how many times: a chat’s turn (once at most), automation starts. */
        private val holds = HashMap<String, Int>()

        /** The running service, when there is one: work is then added to it here, with no new start (Android may refuse one in the background). */
        private var live: AgentService? = null

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        private fun openApp(context: Context) = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        private fun workingNotification(context: Context): Notification {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.channel_working), NotificationManager.IMPORTANCE_LOW).apply {
                    description = context.getString(R.string.channel_working_description, AgentProfile.name(context))
                    setShowBadge(false)
                },
            )
            return Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.working_title, AgentProfile.name(context)))
                .setContentText("Tap to open the app")
                .setOngoing(true)
                .setContentIntent(openApp(context))
                .apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                    }
                }
                .build()
        }

        /** While it only waits: the next automation, and a way to turn it off. */
        private fun backgroundNotification(context: Context): Notification {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_BACKGROUND, context.getString(R.string.channel_background), NotificationManager.IMPORTANCE_LOW).apply {
                    description = context.getString(R.string.channel_background_description)
                    setShowBadge(false)
                },
            )
            val off = PendingIntent.getBroadcast(
                context, 0, Intent(context, TurnOffReceiver::class.java).setAction(ACTION_TURN_OFF),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            return Notification.Builder(context, CHANNEL_BACKGROUND)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.background_title))
                .setContentText(BackgroundMode.nextLine(context))
                .setOngoing(true)
                .setShowWhen(false)
                .setContentIntent(openApp(context))
                .addAction(Notification.Action.Builder(null, context.getString(R.string.background_turn_off), off).build())
                .build()
        }

        /** The same notice, for automations that run in a WorkManager job instead. */
        fun foregroundInfo(context: Context) =
            androidx.work.ForegroundInfo(NOTIFICATION_ID + 1, workingNotification(context), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

        private fun addHold(hold: String) {
            holds[hold] = if (hold == HOLD_CHAT || hold == HOLD_SIGN_IN || hold == HOLD_BACKGROUND) 1 else (holds[hold] ?: 0) + 1
        }

        private fun dropHold(hold: String): Boolean {
            val n = holds[hold] ?: return false
            if (n > 1) holds[hold] = n - 1 else holds.remove(hold)
            return true
        }

        private fun launchRun(app: Context) {
            scope.launch {
                try {
                    AutomationRunner.drain(app)
                } finally {
                    release(app, HOLD_AUTOMATION)
                }
            }
        }

        /**
         * Keeps the service for [hold]: added to the running service when there is one, else started. False when Android
         * doesn't allow a start now (in the background, without an exact alarm or another exemption).
         */
        private fun acquire(context: Context, hold: String, action: String?): Boolean {
            val app = context.applicationContext
            val running = synchronized(holds) { live?.also { addHold(hold) } }
            if (running != null) {
                if (action == ACTION_RUN) launchRun(app)
                running.refresh()
                return true
            }
            synchronized(holds) { addHold(hold) }
            return try {
                ContextCompat.startForegroundService(app, Intent(app, AgentService::class.java).setAction(action))
                true
            } catch (e: Exception) {
                synchronized(holds) { dropHold(hold) }
                Log.w(TAG, "could not start the service for $hold", e)
                false
            }
        }

        /** The notice again, on the main thread, after what holds the service changed. */
        private fun AgentService.refresh() {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                // Not after it stopped: the notice would come back on its own.
                if (synchronized(holds) { live === this }) goForeground()
            }
        }

        /**
         * Lets go of [hold]; with none left the service ends. A turn can end before the service has called startForeground
         * (one that fails at once, offline), and stopping it then makes Android end the app; so the stop is delivered to the
         * service, which starts foreground first and then stops itself.
         */
        private fun release(context: Context, hold: String) {
            val (empty, running) = synchronized(holds) {
                if (!dropHold(hold)) return
                holds.isEmpty() to live
            }
            if (!empty) {
                running?.refresh()
                return
            }
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

        /** Whether the service runs in the background now ([BackgroundMode]). */
        fun keepsRunning(): Boolean = synchronized(holds) { live != null && HOLD_BACKGROUND in holds }

        /** Runs in the background from now on (once; again is a no-op). False when Android doesn't allow the start now. */
        fun keepRunning(context: Context): Boolean = keepsRunning() || acquire(context.applicationContext, HOLD_BACKGROUND, null)

        fun stopKeeping(context: Context) = release(context.applicationContext, HOLD_BACKGROUND)

        /** The background notice shows the next automation: called after the alarm is set again. */
        fun nextChanged() {
            val running = synchronized(holds) { live?.takeIf { HOLD_BACKGROUND in holds } } ?: return
            running.refresh()
        }
    }
}
