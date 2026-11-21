package io.github.erkanw.vault.automation

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.erkanw.vault.AgentService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * Wakes the phone for automations (PLAN §8). One alarm is set, for the earliest planned run: exact
 * (`setExactAndAllowWhileIdle`) when the user allowed exact alarms, otherwise inexact (`setAndAllowWhileIdle`, "about
 * 09:00"). A job already due (missed while the phone was off) gets an alarm a few seconds ahead, so it runs once. A
 * WorkManager check every 30 minutes re-arms the alarm, and [BootReceiver] does after a restart.
 */
object Scheduler {
    private const val TAG = "Scheduler"
    const val ACTION_ALARM = "io.github.erkanw.vault.AUTOMATION_ALARM"
    private const val CHECK = "automations-check"
    private const val RUN = "automations-run"
    private const val NETWORK = "automations-network"

    /** A due job waits this long for a network before it is recorded as skipped. */
    const val NETWORK_WAIT_MS = 30 * 60_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun canExact(context: Context): Boolean =
        Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    private fun alarmIntent(context: Context) = PendingIntent.getBroadcast(
        context, 0, Intent(context, AlarmReceiver::class.java).setAction(ACTION_ALARM),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Sets the alarm for the earliest planned run (or cancels it when nothing is planned). */
    suspend fun arm(context: Context) {
        val app = context.applicationContext
        val now = System.currentTimeMillis()
        val next = Automations.all(app).filter { it.enabled && it.nextRun > 0L }.minOfOrNull { it.nextRun }
        val alarms = app.getSystemService(AlarmManager::class.java)
        val pi = alarmIntent(app)
        if (next == null) {
            alarms.cancel(pi)
            return
        }
        // A job already due runs a few seconds from now: once, never as a backlog.
        val at = maxOf(next, now + 5_000L)
        try {
            if (canExact(app)) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (e: SecurityException) {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    fun armSoon(context: Context) {
        val app = context.applicationContext
        scope.launch { runCatching { arm(app) }.onFailure { Log.w(TAG, "arm failed", it) } }
    }

    /** The 30-minute check: catches runs a lost alarm missed and keeps the alarm set. Safe to call on every start. */
    fun ensurePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<CheckWorker>(30, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(CHECK, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /**
     * Runs what is due now: through [AgentService] (a foreground service, allowed from an exact alarm or with the app on
     * screen), else in an expedited job that runs the turns itself.
     */
    fun runDue(context: Context) {
        if (AgentService.runAutomations(context)) return
        val request = OneTimeWorkRequestBuilder<RunWorker>().setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST).build()
        WorkManager.getInstance(context).enqueueUniqueWork(RUN, ExistingWorkPolicy.KEEP, request)
    }

    /** A due job found no network: run again once there is one (PLAN §8). */
    fun waitForNetwork(context: Context) {
        val request = OneTimeWorkRequestBuilder<RunWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(NETWORK, ExistingWorkPolicy.KEEP, request)
    }

    /** Every 30 minutes: re-arm, and run anything overdue (an alarm that never came). */
    class CheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            Automations.ensureBuiltIns(applicationContext)
            val now = System.currentTimeMillis()
            val overdue = Automations.all(applicationContext).any { it.enabled && it.nextRun in 1..(now - 60_000L) }
            // A plain background job: Android stops it after about ten minutes.
            if (overdue) AutomationRunner.drain(applicationContext, until = System.currentTimeMillis() + AutomationRunner.BACKGROUND_JOB_MS) else arm(applicationContext)
            return Result.success()
        }
    }

    /** Runs due jobs where the service couldn't be started (an inexact alarm, or the network came back). */
    class RunWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            // Promoted to a foreground service where Android allows it; otherwise it runs as a job.
            val foreground = runCatching { setForeground(getForegroundInfo()) }.isSuccess
            // As a plain job (not promoted), Android stops it after about ten minutes: approvals give up before then.
            val until = if (foreground) 0L else System.currentTimeMillis() + AutomationRunner.BACKGROUND_JOB_MS
            if (!AgentService.runAutomations(applicationContext)) AutomationRunner.drain(applicationContext, until)
            return Result.success()
        }

        override suspend fun getForegroundInfo(): ForegroundInfo = AgentService.foregroundInfo(applicationContext)
    }
}

/** The automations alarm went off: run what is due. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Scheduler.ACTION_ALARM) Scheduler.runDue(context.applicationContext)
    }
}

/** After a restart, an app update, a clock or time zone change, or a change to the exact-alarm permission: set the alarm again. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Automations.ensureBuiltIns(app)
                if (intent.action == Intent.ACTION_TIMEZONE_CHANGED || intent.action == Intent.ACTION_TIME_CHANGED) AutomationRunner.replanAll(app)
                Scheduler.arm(app)
                Scheduler.ensurePeriodic(app)
            } catch (e: Exception) {
                Log.w("BootReceiver", "re-arm failed", e)
            } finally {
                pending.finish()
            }
        }
    }
}
