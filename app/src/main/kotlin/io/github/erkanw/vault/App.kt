package io.github.erkanw.vault

import android.app.Application
import android.util.Log
import io.github.erkanw.vault.automation.Automations
import io.github.erkanw.vault.automation.Scheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The process starts (the app opened, an alarm, a widget, a job): the built-in Tidy memory is there, the next automation's
 * alarm is set and the 30-minute check is enqueued (PLAN §8).
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                io.github.erkanw.vault.store.Agents.ensure(this@App)
                Automations.ensureBuiltIns(this@App)
                Scheduler.arm(this@App)
                Scheduler.ensurePeriodic(this@App)
            } catch (e: Exception) {
                Log.w("App", "automations start-up failed", e)
            }
        }
    }
}
