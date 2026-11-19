package io.github.erkanw.vault.usage

import io.github.erkanw.vault.openrouter.OrActivityRow
import io.github.erkanw.vault.openrouter.OrDayStat
import io.github.erkanw.vault.openrouter.OrModelStat
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.TemporalAdjusters

// The Usage page's numbers (PLAN §6.11), made from the app's own log: one row per answer, with what OpenRouter said it
// cost. Pure, so it is tested on the JVM. Days, weeks (Monday on) and months are UTC, as OpenRouter counts them.

/** One logged answer (a `usage` row), as the statistics need it. */
data class UsageCall(
    val time: Long,
    val model: String,
    /** "user" or "automation". */
    val source: String,
    val automationId: String?,
    /** Calls to the model this answer took (one per tool round). */
    val requests: Int,
    val tokensIn: Long,
    val tokensOut: Long,
    /** Null when the provider doesn't say (your own server). */
    val cost: Double?,
    /** The agent that answered (PLAN §6.16); null: Apel. */
    val agentId: String? = null,
)

/** Who spent it: you, or one automation. [id] is "you" or the automation's id. */
data class SourceStat(val id: String, val label: String, val cost: Double, val messages: Int)

data class UsageSummary(
    val today: Double,
    val week: Double,
    val month: Double,
    /** The last 30 days, oldest first, today last; quiet days are 0. */
    val days: List<OrDayStat>,
    /** Most expensive first (then most used), over the 30 days. */
    val models: List<OrModelStat>,
    val sources: List<SourceStat>,
    /** Spend per agent; shown only when more than one agent answered. */
    val agents: List<SourceStat> = emptyList(),
    val messages: Int,
    val requests: Int,
    val tokensIn: Long,
    val tokensOut: Long,
    /** Requests to free models today (OpenRouter allows 50 a day, 1,000 once $10 of credits were bought). */
    val freeToday: Int,
    /** Answers whose cost the provider didn't give (counted as nothing). */
    val unpriced: Int,
) {
    val total: Double get() = days.sumOf { it.usage }
    /** What an answer cost on average over the 30 days, or null with none. */
    val perMessage: Double? get() = if (messages > 0) total / messages else null
    val last7: Double get() = days.takeLast(7).sumOf { it.usage }
    val before7: Double get() = days.dropLast(7).takeLast(7).sumOf { it.usage }
    val busiest: OrDayStat? get() = days.maxByOrNull { it.usage }?.takeIf { it.usage > 0 }
    val isEmpty: Boolean get() = messages == 0 && month == 0.0
}

object UsageStats {
    private const val APEL = "apel"
    const val DAYS = 30
    const val YOU = "you"

    fun utcDay(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()

    /** The first millisecond of this UTC month. */
    fun monthStart(now: Long): Long = utcDay(now).withDayOfMonth(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    /** The first millisecond of the oldest day the page reads: 30 days, or the month if it began earlier. */
    fun since(now: Long): Long = minOf(monthStart(now), utcDay(now).minusDays((DAYS - 1).toLong()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())

    fun isFree(model: String): Boolean = model.endsWith(":free")

    /**
     * The summary at [now] from [calls] (at least [since] back). [names] gives each automation's name; one no longer
     * there is "A deleted automation".
     */
    fun summarize(calls: List<UsageCall>, now: Long, names: Map<String, String> = emptyMap(), agentNames: Map<String, String> = emptyMap()): UsageSummary {
        val today = utcDay(now)
        val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val first = today.withDayOfMonth(1)
        val start = today.minusDays((DAYS - 1).toLong())
        fun cost(c: UsageCall) = c.cost?.takeIf { !it.isNaN() && !it.isInfinite() } ?: 0.0
        val dated = calls.map { utcDay(it.time) to it }.filter { (d, _) -> !d.isAfter(today) }
        val inside = dated.filter { (d, _) -> !d.isBefore(start) }.map { it.second }
        val perDay = dated.filter { (d, _) -> !d.isBefore(start) }.groupBy({ it.first }, { cost(it.second) })
        val days = List(DAYS) { i ->
            val d = start.plusDays(i.toLong())
            OrDayStat(d.toString(), perDay[d]?.sum() ?: 0.0)
        }
        val total = days.sumOf { it.usage }
        val models = inside.groupBy { it.model }.map { (model, v) ->
            OrModelStat(model, v.sumOf(::cost), v.sumOf { it.requests }, v.sumOf { it.tokensIn + it.tokensOut }, 0f)
        }.sortedWith(compareByDescending<OrModelStat> { it.usage }.thenByDescending { it.requests }.thenBy { it.model })
            .map { it.copy(share = if (total > 0) (it.usage / total).toFloat() else 0f) }
        val sources = inside.groupBy { if (it.source == "automation") it.automationId ?: "" else YOU }.map { (id, v) ->
            val label = when (id) {
                YOU -> "You"
                else -> names[id] ?: "A deleted automation"
            }
            SourceStat(id, label, v.sumOf(::cost), v.size)
        }.sortedWith(compareByDescending<SourceStat> { it.cost }.thenByDescending { it.messages }.thenBy { it.label })
        val agents = inside.groupBy { it.agentId ?: APEL }.map { (id, v) ->
            SourceStat(id, agentNames[id] ?: if (id == APEL) "Apel" else "A deleted agent", v.sumOf(::cost), v.size)
        }.sortedWith(compareByDescending<SourceStat> { it.cost }.thenByDescending { it.messages }.thenBy { it.label })
        return UsageSummary(
            agents = agents,
            today = dated.filter { (d, _) -> d == today }.sumOf { cost(it.second) },
            week = dated.filter { (d, _) -> !d.isBefore(monday) }.sumOf { cost(it.second) },
            month = dated.filter { (d, _) -> !d.isBefore(first) }.sumOf { cost(it.second) },
            days = days,
            models = models,
            sources = sources,
            messages = inside.size,
            requests = inside.sumOf { it.requests },
            tokensIn = inside.sumOf { it.tokensIn },
            tokensOut = inside.sumOf { it.tokensOut },
            freeToday = dated.filter { (d, c) -> d == today && isFree(c.model) }.sumOf { it.second.requests },
            unpriced = inside.count { it.cost == null },
        )
    }

    /** The log as OpenRouter's activity rows (a model's spend on a UTC day), for the widget's week by model. */
    fun activityRows(calls: List<UsageCall>): List<OrActivityRow> =
        calls.groupBy { utcDay(it.time).toString() to it.model }.map { (k, v) ->
            OrActivityRow(
                date = k.first, model = k.second, provider = "",
                usage = v.sumOf { it.cost ?: 0.0 }, requests = v.sumOf { it.requests },
                promptTokens = v.sumOf { it.tokensIn }, completionTokens = v.sumOf { it.tokensOut },
                reasoningTokens = 0L, cachedTokens = 0L,
            )
        }
}
