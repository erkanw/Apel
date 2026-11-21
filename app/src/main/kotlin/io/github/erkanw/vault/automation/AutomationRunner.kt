package io.github.erkanw.vault.automation

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import io.github.erkanw.vault.AppGraph
import io.github.erkanw.vault.agent.AutomationTurn
import io.github.erkanw.vault.agent.SessionEvents
import io.github.erkanw.vault.agent.TurnEvent
import io.github.erkanw.vault.agent.TurnInput
import io.github.erkanw.vault.chat.ChannelLooks
import io.github.erkanw.vault.chat.notificationText
import io.github.erkanw.vault.data.AppSettings
import io.github.erkanw.vault.provider.ProviderStore
import io.github.erkanw.vault.store.AppDatabase
import io.github.erkanw.vault.store.AutomationEntity
import io.github.erkanw.vault.store.AutomationRunEntity
import io.github.erkanw.vault.usage.Budget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Runs automations (PLAN §8): every job that is due, or that you asked to run now, one at a time (no overlaps). A run is a
 * turn of the agent in the job's channel (or a new chat), with its own model if it has one; its answer is posted as a
 * notification and recorded in the run log. Without a network a due job waits up to 30 minutes, then is skipped; with
 * the month's budget used up (Phase 7 sets one) it is skipped. Tidy memory is its own kind of run ([TidyMemory]).
 */
object AutomationRunner {
    private const val TAG = "AutomationRunner"

    /** Runs kept per automation. */
    private const val KEEP_RUNS = 100

    /** How long before a background job's end an approval stops waiting: time for the run to finish and be recorded. */
    private const val FINISH_MARGIN_MS = 90_000L

    /** What Android gives a background job (about ten minutes), less a little. */
    const val BACKGROUND_JOB_MS = 9 * 60_000L

    private val _running = MutableStateFlow<Set<String>>(emptySet())

    /** The jobs running right now (at most one). */
    val running: StateFlow<Set<String>> = _running.asStateFlow()

    /** Jobs you asked to run now. */
    private val requested = ConcurrentHashMap.newKeySet<String>()

    private val mutex = Mutex()
    @Volatile private var again = false

    /** Run [id] now, whatever its schedule. */
    fun requestRun(context: Context, id: String) {
        requested += id
        Scheduler.runDue(context.applicationContext)
    }

    /** When the drain running now must be done by (a WorkManager job, which Android stops after about ten minutes), or 0. */
    @Volatile private var deadline = 0L

    /**
     * How long a tool may wait for your OK in an automation now: up to [max], and in a background job short enough that
     * the run ends (as "no answer") before Android stops it, so it is recorded and planned like any other run.
     */
    fun approvalWait(max: Long): Long {
        val until = deadline
        if (until == 0L) return max
        return (until - System.currentTimeMillis() - FINISH_MARGIN_MS).coerceIn(0L, max)
    }

    /**
     * Runs every due or requested job until none is left, then sets the alarm for the next one. Calls while it is busy
     * just make it look again before it ends. [until]: when a background job running this must be done (0: no limit).
     */
    suspend fun drain(context: Context, until: Long = 0L) {
        val app = context.applicationContext
        again = true
        while (again) {
            // Busy: the drain running now looks again before it ends.
            if (!mutex.tryLock()) return
            deadline = until
            try {
                cleanOnce(app)
                val tried = HashSet<String>()
                while (again) {
                    again = false
                    if (pass(app, tried)) again = true
                }
            } finally {
                deadline = 0L
                mutex.unlock()
            }
        }
        runCatching { Scheduler.arm(app) }.onFailure { Log.w(TAG, "arm failed", it) }
    }

    /** One look at what is due; true when something ran (so it looks again). */
    private suspend fun pass(app: Context, tried: MutableSet<String>): Boolean {
        // A process started by the alarm has loaded nothing yet.
        ProviderStore.ensureLoaded(app)
        ChannelLooks.ensureLoaded(app)
        Automations.ensureBuiltIns(app)
        val now = System.currentTimeMillis()
        val due = Automations.all(app).filter { a ->
            a.id !in tried && (a.id in requested || (a.enabled && a.nextRun in 1..(now + 30_000L)))
        }.sortedBy { if (it.id in requested) 0L else it.nextRun }
        if (due.isEmpty()) return false
        for (a in due) {
            tried += a.id
            val scheduled = a.enabled && a.nextRun in 1..(System.currentTimeMillis() + 30_000L)
            val manual = requested.remove(a.id) && !scheduled
            val fresh = Automations.get(app, a.id) ?: continue
            try {
                runOne(app, fresh, manual)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "run of ${a.id} failed", e)
            }
        }
        return true
    }

    /** What a run came to. */
    data class Result(val status: String, val summary: String, val error: String = "", val sessionId: String? = null, val messageId: Long? = null, val text: String = "")

    private suspend fun runOne(app: Context, a: AutomationEntity, manual: Boolean) {
        val dao = AppDatabase.get(app).automations()
        val started = System.currentTimeMillis()
        // Skipped before it starts: the budget (Phase 7 sets one), or no network for 30 minutes.
        val skip: String? = when {
            !manual && Budget.automationsPaused(app) != null -> Budget.automationsPaused(app)
            !online(app) -> if (!manual && started - a.nextRun < Scheduler.NETWORK_WAIT_MS) {
                Scheduler.waitForNetwork(app)
                return
            } else "Skipped: no network."
            ProviderStore.target(app) == null -> "Skipped: not connected to a provider."
            else -> null
        }
        if (skip != null) {
            Log.i(TAG, "${a.id} skipped: $skip")
            dao.insertRun(AutomationRunEntity(automationId = a.id, started = started, finished = started, status = "skipped", summary = skip))
            advance(app, a, started, "skipped", skip, manual)
            return
        }
        Log.i(TAG, "${a.id} starts" + if (manual) " (run now)" else "")
        _running.update { it + a.id }
        Automations.changed()
        val runId = dao.insertRun(AutomationRunEntity(automationId = a.id, started = started))
        var result = Result("stopped", "Stopped: the app was closed during the run.")
        try {
            result = if (a.builtin == Automations.TIDY_MEMORY) TidyMemory.run(app, a) else turn(app, a)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            result = Result("error", e.message ?: "Something went wrong.", error = e.message.orEmpty())
        } finally {
            withContext(NonCancellable) {
                val end = System.currentTimeMillis()
                Log.i(TAG, "${a.id} ended: ${result.status} in ${end - started} ms")
                dao.finishRun(runId, end, result.status, result.sessionId, result.messageId, result.summary.take(300))
                dao.trimRuns(a.id, KEEP_RUNS)
                advance(app, a, started, result.status, result.error, manual)
                _running.update { it - a.id }
                Automations.changed()
            }
        }
        // Tidy memory works quietly: its run log says what it did.
        if (a.builtin == null && result.sessionId != null) {
            val title = a.channelId?.let { id -> ChannelLooks.custom.value.firstOrNull { it.id == id } }?.let { ChannelLooks.look(it).title }
            AutomationAlerts.show(
                app, a.id, if (title != null && title != a.name) "${a.name} · $title" else a.name, result.sessionId,
                if (result.status == "ok") result.text else "${a.name} didn’t finish: ${result.summary}",
            )
        }
    }

    /** Records how it went and plans the next run; a one-time job turns off once it has run. */
    private suspend fun advance(app: Context, a: AutomationEntity, at: Long, status: String, error: String, manual: Boolean) {
        val dao = AppDatabase.get(app).automations()
        val current = dao.get(a.id) ?: return
        if (manual && !(current.enabled && current.nextRun in 1..System.currentTimeMillis())) {
            // Run now: the schedule stays as it was.
            dao.setResult(a.id, at, status, if (status == "ok") "" else error, current.nextRun, current.enabled)
            return
        }
        val next = if (current.enabled) current.schedule().next(System.currentTimeMillis(), anchor = current.nextRun) ?: 0L else 0L
        val enabled = current.enabled && (next > 0L || current.kind != Schedule.AT)
        dao.setResult(a.id, at, status, if (status == "ok") "" else error, next, enabled)
    }

    /** One turn of the agent for [a], in its channel or a new chat. */
    private suspend fun turn(app: Context, a: AutomationEntity): Result {
        val backend = AppGraph.local(app)
        ChannelLooks.ensureLoaded(app)
        val settings = AppSettings(app)
        val channel = a.channelId?.let { id -> ChannelLooks.custom.value.firstOrNull { it.id == id } }
        var chatId = channel?.let { settings.channelChats()[it.id] }?.takeIf { backend.hasSession(it) }
        // One turn per chat: if you are talking in the channel, the job waits for that answer (up to 10 minutes).
        chatId?.let { id ->
            var waited = 0
            while (backend.isRunning(id) && waited < 300) { delay(2_000); waited++ }
        }
        val look = channel?.let { ChannelLooks.look(it) }
        val input = TurnInput(
            text = a.message,
            channelId = channel?.id,
            channelNote = look?.let { if (it.blurb.isBlank()) it.title else "${it.title} (${it.blurb})" },
            automation = AutomationTurn(a.id, a.name, a.model, title = look?.title ?: a.name, agentId = a.agentId),
            agentId = a.agentId,
        )
        var sessionId: String? = chatId
        var answer = ""
        var error: String? = null
        backend.run(chatId, input).collect { e ->
            when (e) {
                is TurnEvent.MessageAccepted -> {
                    sessionId = e.chatId
                    if (channel != null && chatId == null) {
                        settings.setChannelChat(channel.id, e.chatId)
                        chatId = e.chatId
                    }
                    SessionEvents.changed(e.chatId)
                }
                is TurnEvent.Message -> answer = e.text
                is TurnEvent.TurnEnd -> if (e.text.isNotEmpty()) answer = e.text
                is TurnEvent.Error -> error = e.message
                else -> Unit
            }
        }
        val sid = sessionId
        sid?.let { SessionEvents.changed(it) }
        val messageId = sid?.let { backend.lastAnswerId(it) }
        val err = error
        return if (err != null) Result("error", err, error = err, sessionId = sid, messageId = messageId)
        else Result("ok", firstLine(answer), sessionId = sid, messageId = messageId, text = answer)
    }

    /** After a time zone or clock change: every job's next run worked out again. */
    suspend fun replanAll(app: Context) {
        val dao = AppDatabase.get(app).automations()
        val now = System.currentTimeMillis()
        for (a in dao.all()) {
            if (!a.enabled) continue
            val s = a.schedule()
            if (s.kind == Schedule.CRON) dao.setNext(a.id, s.next(now) ?: 0L)
        }
        Automations.changed()
    }

    /** Runs a process that ended mid-run left "running" are marked stopped: once per process, under the lock, before any run. */
    @Volatile private var cleaned = false

    private suspend fun cleanOnce(app: Context) {
        if (cleaned) return
        AppDatabase.get(app).automations().stopInterrupted(System.currentTimeMillis(), "Stopped: the app was closed during the run.")
        cleaned = true
    }

    private fun online(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** An answer's first line as the run log shows it. */
    fun firstLine(text: String): String =
        notificationText(text, 300).lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty().take(160)
}
