package io.github.erkanw.vault.automation

import android.content.Context
import io.github.erkanw.vault.chat.AutomationJob
import io.github.erkanw.vault.chat.AutomationRun
import io.github.erkanw.vault.chat.ChatException
import io.github.erkanw.vault.chat.describeSchedule
import io.github.erkanw.vault.store.AppDatabase
import io.github.erkanw.vault.store.AutomationEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.util.UUID

/**
 * The automations on the phone (PLAN §5 `AutomationRepository`, §6.8): made, edited, turned on and off and deleted here,
 * from the Automations page or the agent's automation tools. Every change plans the next run and re-arms the alarm.
 */
object Automations {
    const val TIDY_MEMORY = "tidy-memory"

    /** Ticks on every change, so pages showing automations read them again. */
    private val _version = MutableStateFlow(0L)
    val version: StateFlow<Long> = _version.asStateFlow()

    fun changed() = _version.update { it + 1 }

    private fun dao(context: Context) = AppDatabase.get(context).automations()

    /** Adds the built-in Tidy memory (03:30 nightly) if it isn't there yet. */
    suspend fun ensureBuiltIns(context: Context) {
        val dao = dao(context)
        if (dao.get(TIDY_MEMORY) != null) return
        val now = System.currentTimeMillis()
        val schedule = Schedule(Schedule.CRON, expr = "30 3 * * *")
        dao.insert(
            AutomationEntity(
                id = TIDY_MEMORY, name = "Tidy memory",
                message = "Reads the day’s chats and keeps what’s worth remembering in Memory.",
                kind = schedule.kind, expr = schedule.expr, enabled = true, builtin = TIDY_MEMORY, created = now,
                nextRun = schedule.next(now) ?: 0L,
            ),
        )
    }

    suspend fun all(context: Context): List<AutomationEntity> = dao(context).all()
    suspend fun get(context: Context, id: String): AutomationEntity? = dao(context).get(id)

    /** Everything the Automations page shows: each job with its last [runs] runs (one more than a card shows, so it knows there are more). */
    suspend fun jobs(context: Context, runs: Int = 6): List<AutomationJob> {
        val dao = dao(context)
        val exact = Scheduler.canExact(context)
        val running = AutomationRunner.running.value
        return dao.all().map { a -> toJob(a, dao.runs(a.id, runs).map { r ->
            // A run left "running" by a process that ended (the runner records it as stopped on its next start).
            val stale = r.status == "running" && a.id !in running
            AutomationRun(r.id, r.started, if (stale) "stopped" else r.status, if (stale) "Stopped: the app was closed during the run." else r.summary, r.sessionId, r.messageId)
        }, exact, a.id in running) }
    }

    fun toJob(a: AutomationEntity, runs: List<AutomationRun> = emptyList(), exact: Boolean = true, running: Boolean = false): AutomationJob {
        val s = a.schedule()
        return AutomationJob(
            id = a.id, name = a.name, enabled = a.enabled, protected = a.builtin != null, schedule = describeSchedule(s.toJson()),
            nextRunMs = if (a.enabled) a.nextRun else 0L, lastRunMs = a.lastRun, lastStatus = a.lastStatus, lastError = a.lastError,
            pending = running, message = a.message, channel = a.channelId.orEmpty(),
            scheduleKind = s.kind, cronExpr = s.expr, tz = s.tz, everyMs = s.everyMs, atMs = s.atMs,
            model = a.model.orEmpty(), builtIn = a.builtin != null, runs = runs, exact = exact,
        )
    }

    /**
     * Makes a job from [values]: `name`, `message`, `schedule` (JSON, see [Schedule]), `channel` (a channel id, or empty
     * for a new chat each time) and `model` (a preset, or empty for the main model). Returns its id.
     */
    suspend fun create(context: Context, values: JSONObject): String {
        val name = values.optString("name").trim().take(80)
        val message = values.optString("message").trim()
        if (name.isEmpty()) throw ChatException("Give it a name.")
        if (message.isEmpty()) throw ChatException("Write what it should do.")
        val schedule = Schedule.fromJson(values.optJSONObject("schedule")) ?: throw ChatException("Choose when it runs.")
        val now = System.currentTimeMillis()
        schedule.problem(now)?.let { throw ChatException(it) }
        val id = UUID.randomUUID().toString().substring(0, 8)
        dao(context).insert(
            AutomationEntity(
                id = id, name = name, message = message, kind = schedule.kind, expr = schedule.expr, tz = schedule.tz,
                everyMs = schedule.everyMs, atMs = schedule.atMs, enabled = true,
                channelId = values.optString("channel").trim().ifEmpty { null }, model = values.optString("model").trim().ifEmpty { null },
                nextRun = schedule.next(now, anchor = 0L) ?: 0L, created = now,
            ),
        )
        afterChange(context)
        return id
    }

    /** Changes a job: any of the keys of [create], each only when present. A built-in job keeps its name and instruction. */
    suspend fun update(context: Context, id: String, values: JSONObject) {
        val dao = dao(context)
        val a = dao.get(id) ?: throw ChatException("That automation is gone.")
        val now = System.currentTimeMillis()
        var next = a
        if (a.builtin == null) {
            if (values.has("name")) next = next.copy(name = values.optString("name").trim().take(80).ifEmpty { throw ChatException("Give it a name.") })
            if (values.has("message")) next = next.copy(message = values.optString("message").trim().ifEmpty { throw ChatException("Write what it should do.") })
            if (values.has("channel")) next = next.copy(channelId = values.optString("channel").trim().ifEmpty { null })
        }
        if (values.has("model")) next = next.copy(model = values.optString("model").trim().ifEmpty { null })
        values.optJSONObject("schedule")?.let { o ->
            val s = Schedule.fromJson(o) ?: throw ChatException("Choose when it runs.")
            if (s != a.schedule()) {
                s.problem(now)?.let { throw ChatException(it) }
                next = next.copy(kind = s.kind, expr = s.expr, tz = s.tz, everyMs = s.everyMs, atMs = s.atMs, nextRun = if (next.enabled) s.next(now) ?: 0L else 0L)
                // A one-time job that had run and was off comes back on with its new time.
                if (s.kind == Schedule.AT && !a.enabled) next = next.copy(enabled = true, nextRun = s.atMs)
            }
        }
        if (values.has("enabled")) next = withEnabled(next, values.optBoolean("enabled"), now)
        if (next != a) dao.update(next)
        afterChange(context)
    }

    suspend fun setEnabled(context: Context, id: String, enabled: Boolean) {
        val dao = dao(context)
        val a = dao.get(id) ?: throw ChatException("That automation is gone.")
        dao.update(withEnabled(a, enabled, System.currentTimeMillis()))
        afterChange(context)
    }

    private fun withEnabled(a: AutomationEntity, enabled: Boolean, now: Long): AutomationEntity {
        if (!enabled) return a.copy(enabled = false, nextRun = 0L)
        if (a.enabled && a.nextRun > 0L) return a
        val s = a.schedule()
        val next = s.next(now) ?: throw ChatException(if (s.kind == Schedule.AT) "Its time has passed. Edit it to choose a new one." else "That schedule never runs.")
        return a.copy(enabled = true, nextRun = next)
    }

    suspend fun delete(context: Context, id: String) {
        val dao = dao(context)
        val a = dao.get(id) ?: return
        if (a.builtin != null) throw ChatException("${a.name} is built in: turn it off instead.")
        dao.delete(id)
        afterChange(context)
    }

    /** A channel was deleted: its jobs go on in a new chat each time. */
    suspend fun forgetChannel(context: Context, channelId: String) {
        dao(context).forgetChannel(channelId)
        changed()
    }

    private suspend fun afterChange(context: Context) {
        changed()
        Scheduler.arm(context)
    }
}

fun AutomationEntity.schedule() = Schedule(kind, expr, tz, everyMs, atMs)
