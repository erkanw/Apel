package io.github.erkanw.vault.openrouter

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale

// What OpenRouter tells, and what the app makes of it. Pure helpers, testable on the JVM.
//
// A regular API key can read only `GET /api/v1/key`: what that one key has spent. A Management key can also read
// `/credits` (the account balance), `/keys` (every key's spend today, this week and this month) and `/activity`
// (the last 30 completed days, per model and day). The page and the widget show what the key in use allows.

/** `GET /api/v1/key`: the key this app uses. Money is in US dollars. */
data class OrKey(
    val label: String,
    val usage: Double,
    val daily: Double,
    val weekly: Double,
    val monthly: Double,
    val limit: Double?,
    val limitRemaining: Double?,
    val management: Boolean,
)

/** `GET /api/v1/credits` (Management key): credits bought and used, ever. */
data class OrCredits(val total: Double, val used: Double) {
    val left: Double get() = total - used
}

/** One entry of `GET /api/v1/keys` (Management key): what a key of the account has spent. */
data class OrKeyUse(
    val name: String,
    val label: String,
    val disabled: Boolean,
    val usage: Double,
    val daily: Double,
    val weekly: Double,
    val monthly: Double,
)

/** One row of `GET /api/v1/activity` (Management key): a model's spend on a UTC day. */
data class OrActivityRow(
    val date: String,
    val model: String,
    val provider: String,
    val usage: Double,
    val requests: Int,
    val promptTokens: Long,
    val completionTokens: Long,
    val reasoningTokens: Long,
    val cachedTokens: Long,
)

/**
 * Everything fetched at [at] (milliseconds). Empty lists and a null balance mean the key can't read them. [activityAt] is
 * when [rows] were fetched: they only change once a day, so they are not asked for as often as the rest.
 */
data class OrSnapshot(
    val at: Long,
    val key: OrKey,
    val credits: OrCredits?,
    val keys: List<OrKeyUse>,
    val rows: List<OrActivityRow>,
    val activityAt: Long = 0L,
) {
    /** Spent today (UTC): all the account's keys with a Management key, otherwise this key alone. */
    val today: Double get() = if (keys.isNotEmpty()) keys.sumOf { it.daily } else key.daily
    val thisWeek: Double get() = if (keys.isNotEmpty()) keys.sumOf { it.weekly } else key.weekly
    val thisMonth: Double get() = if (keys.isNotEmpty()) keys.sumOf { it.monthly } else key.monthly

    /** What is left to spend: the account balance, or this key's remaining limit, or null if neither is known. */
    val balance: Double? get() = credits?.left ?: key.limitRemaining

    /** Whether the per-model detail is there (a Management key). */
    val detailed: Boolean get() = credits != null || rows.isNotEmpty() || keys.isNotEmpty()
}

// ---- reading ---------------------------------------------------------------------------------------------------

private fun JSONObject.num(key: String): Double =
    if (!has(key) || isNull(key)) 0.0 else optDouble(key, 0.0).let { if (it.isNaN() || it.isInfinite()) 0.0 else it }

private fun JSONObject.numOrNull(key: String): Double? =
    if (!has(key) || isNull(key)) null else optDouble(key, Double.NaN).takeIf { !it.isNaN() && !it.isInfinite() }

private fun JSONObject.text(key: String): String = if (!has(key) || isNull(key)) "" else optString(key, "")

private fun keyFrom(o: JSONObject) = OrKey(
    label = o.text("label"),
    usage = o.num("usage"), daily = o.num("usage_daily"), weekly = o.num("usage_weekly"), monthly = o.num("usage_monthly"),
    limit = o.numOrNull("limit"), limitRemaining = o.numOrNull("limit_remaining"),
    management = o.optBoolean("is_management_key", false),
)

private fun creditsFrom(o: JSONObject) = OrCredits(o.num("total_credits"), o.num("total_usage"))

private fun keyUseFrom(o: JSONObject) = OrKeyUse(
    name = o.text("name").ifBlank { o.text("label") }, label = o.text("label"), disabled = o.optBoolean("disabled", false),
    usage = o.num("usage"), daily = o.num("usage_daily"), weekly = o.num("usage_weekly"), monthly = o.num("usage_monthly"),
)

private fun rowFrom(o: JSONObject) = OrActivityRow(
    date = o.text("date").take(10), model = o.text("model"), provider = o.text("provider_name"),
    usage = o.num("usage"), requests = o.optInt("requests", 0),
    promptTokens = o.optLong("prompt_tokens", 0L), completionTokens = o.optLong("completion_tokens", 0L),
    reasoningTokens = o.optLong("reasoning_tokens", 0L), cachedTokens = o.optLong("cached_tokens", 0L),
)

private fun objectOf(body: String): JSONObject? = try { JSONObject(body) } catch (e: Exception) { null }

private fun <T> list(array: JSONArray?, read: (JSONObject) -> T): List<T> =
    if (array == null) emptyList() else List(array.length()) { i -> array.optJSONObject(i)?.let(read) }.filterNotNull()

/** The body of `GET /key`, or null if it isn't one. */
fun parseKey(body: String): OrKey? = objectOf(body)?.optJSONObject("data")?.let(::keyFrom)

/** The body of `GET /credits`, or null. */
fun parseCredits(body: String): OrCredits? = objectOf(body)?.optJSONObject("data")?.takeIf { it.has("total_credits") }?.let(::creditsFrom)

/** The body of `GET /keys`: one page of the account's keys. */
fun parseKeys(body: String): List<OrKeyUse> = list(objectOf(body)?.optJSONArray("data"), ::keyUseFrom)

/** The body of `GET /activity`. Rows without a date or a model are dropped. */
fun parseActivity(body: String): List<OrActivityRow> =
    list(objectOf(body)?.optJSONArray("data"), ::rowFrom).filter { it.date.isNotBlank() && it.model.isNotBlank() }

// ---- keeping the last answer, so the page and the widget have something to show offline ----------------------

fun OrSnapshot.toJson(): String = JSONObject().apply {
    put("v", 1)
    put("at", at)
    put("activityAt", activityAt)
    put("key", JSONObject().apply {
        put("label", key.label); put("usage", key.usage); put("usage_daily", key.daily)
        put("usage_weekly", key.weekly); put("usage_monthly", key.monthly)
        key.limit?.let { put("limit", it) }; key.limitRemaining?.let { put("limit_remaining", it) }
        put("is_management_key", key.management)
    })
    credits?.let { put("credits", JSONObject().put("total_credits", it.total).put("total_usage", it.used)) }
    put("keys", JSONArray().apply {
        for (k in keys) put(
            JSONObject().put("name", k.name).put("label", k.label).put("disabled", k.disabled).put("usage", k.usage)
                .put("usage_daily", k.daily).put("usage_weekly", k.weekly).put("usage_monthly", k.monthly),
        )
    })
    put("rows", JSONArray().apply {
        for (r in rows) put(
            JSONObject().put("date", r.date).put("model", r.model).put("provider_name", r.provider).put("usage", r.usage)
                .put("requests", r.requests).put("prompt_tokens", r.promptTokens).put("completion_tokens", r.completionTokens)
                .put("reasoning_tokens", r.reasoningTokens).put("cached_tokens", r.cachedTokens),
        )
    })
}.toString()

fun parseSnapshot(json: String): OrSnapshot? {
    val o = objectOf(json) ?: return null
    val key = o.optJSONObject("key")?.let(::keyFrom) ?: return null
    return OrSnapshot(
        at = o.optLong("at", 0L),
        key = key,
        credits = o.optJSONObject("credits")?.let(::creditsFrom),
        keys = list(o.optJSONArray("keys"), ::keyUseFrom),
        rows = list(o.optJSONArray("rows"), ::rowFrom),
        activityAt = o.optLong("activityAt", 0L),
    )
}

// ---- statistics ------------------------------------------------------------------------------------------------

data class OrDayStat(val date: String, val usage: Double)

data class OrModelStat(val model: String, val usage: Double, val requests: Int, val tokens: Long, val share: Float)

data class OrStats(
    /** Oldest first, every day of the period (days with nothing are 0). */
    val days: List<OrDayStat>,
    /** Most expensive first. */
    val models: List<OrModelStat>,
    val total: Double,
    val requests: Int,
    val promptTokens: Long,
    val completionTokens: Long,
    val reasoningTokens: Long,
    val cachedTokens: Long,
    val perDay: Double,
    val perRequest: Double?,
    val busiest: OrDayStat?,
    /** The last seven days, and the seven before them. */
    val last7: Double,
    val before7: Double,
) {
    /** What a month would cost at the pace of the last seven days. */
    val monthAtThisPace: Double get() = last7 / 7.0 * 30.0

    /** The share of prompt tokens served from the provider's cache, 0..1; null if there were no prompt tokens. */
    val cachedShare: Float? get() = if (promptTokens > 0) (cachedTokens.toDouble() / promptTokens).coerceIn(0.0, 1.0).toFloat() else null
}

/**
 * The numbers behind the page. [rows] are the activity of the last days; the period is the [dayCount] days ending on
 * [endDate] (default: the latest day in the rows), so quiet days show as empty bars. Model names lose nothing: the
 * page shortens them for display.
 */
fun statsOf(rows: List<OrActivityRow>, endDate: String? = null, dayCount: Int = 30): OrStats {
    val end = (endDate ?: rows.maxOfOrNull { it.date })?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        ?: LocalDate.now(ZoneOffset.UTC)
    val start = end.minusDays((dayCount - 1).toLong())
    val inside = rows.filter { r ->
        val d = runCatching { LocalDate.parse(r.date) }.getOrNull()
        d != null && !d.isBefore(start) && !d.isAfter(end)
    }
    val perDate = inside.groupBy { it.date }.mapValues { (_, v) -> v.sumOf { it.usage } }
    val days = List(dayCount) { i ->
        val d = start.plusDays(i.toLong()).toString()
        OrDayStat(d, perDate[d] ?: 0.0)
    }
    val total = days.sumOf { it.usage }
    val models = inside.groupBy { it.model }.map { (model, v) ->
        OrModelStat(
            model = model,
            usage = v.sumOf { it.usage },
            requests = v.sumOf { it.requests },
            tokens = v.sumOf { it.promptTokens + it.completionTokens + it.reasoningTokens },
            share = 0f,
        )
    }.sortedByDescending { it.usage }.map { it.copy(share = if (total > 0) (it.usage / total).toFloat() else 0f) }
    val requests = inside.sumOf { it.requests }
    return OrStats(
        days = days,
        models = models,
        total = total,
        requests = requests,
        promptTokens = inside.sumOf { it.promptTokens },
        completionTokens = inside.sumOf { it.completionTokens },
        reasoningTokens = inside.sumOf { it.reasoningTokens },
        cachedTokens = inside.sumOf { it.cachedTokens },
        perDay = total / dayCount,
        perRequest = if (requests > 0) total / requests else null,
        busiest = days.maxByOrNull { it.usage }?.takeIf { it.usage > 0 },
        last7 = days.takeLast(7).sumOf { it.usage },
        before7 = days.dropLast(7).takeLast(7).sumOf { it.usage },
    )
}

// ---- the week, as OpenRouter's dashboard shows it ---------------------------------------------------------------

/** One line of a ranking: a model (or [RANK_OTHERS]), its value, its share of the total, and its colour slot (-1: others). */
data class OrRank(val model: String, val value: Double, val share: Float, val color: Int)

const val RANK_OTHERS = "Others"

/** How many colours a model can take: the same model has the same colour in all three rankings. */
const val RANK_COLORS = 6

/**
 * This week's spend, requests and tokens, each with the models that account for most of it (the three biggest, then the
 * rest as "Others"), like the dashboard's "This Week's Usage". [through] is the last day counted: the activity only holds
 * days that are over, so today is not in it. [label] says which days: "This week" (Monday on, UTC) or, on a Monday when
 * no day of the week is over yet, "Last 7 days".
 */
data class OrWeek(
    val label: String,
    val through: String,
    val days: Int,
    val spend: Double,
    val requests: Long,
    val tokens: Long,
    val bySpend: List<OrRank>,
    val byRequests: List<OrRank>,
    val byTokens: List<OrRank>,
)

/** The week from [rows] as of [today] (UTC), or null when there is nothing in it. */
fun weekOf(rows: List<OrActivityRow>, today: LocalDate = LocalDate.now(ZoneOffset.UTC), top: Int = 3): OrWeek? {
    fun dayOf(r: OrActivityRow) = runCatching { LocalDate.parse(r.date) }.getOrNull()
    val over = rows.filter { r -> dayOf(r)?.let { it.isBefore(today) } == true }
    val monday = today.minusDays((today.dayOfWeek.value - 1).toLong())
    var label = "This week"
    var inside = over.filter { r -> dayOf(r)?.let { !it.isBefore(monday) } == true }
    if (inside.isEmpty()) {
        label = "Last 7 days"
        inside = over.filter { r -> dayOf(r)?.let { !it.isBefore(today.minusDays(7)) } == true }
    }
    if (inside.isEmpty()) return null

    class Total(var spend: Double = 0.0, var requests: Long = 0L, var tokens: Long = 0L)
    val byModel = HashMap<String, Total>()
    for (r in inside) {
        val t = byModel.getOrPut(r.model) { Total() }
        t.spend += r.usage
        t.requests += r.requests
        t.tokens += r.promptTokens + r.completionTokens + r.reasoningTokens
    }
    // A model keeps one colour whichever ranking it is in: by how much it cost, then how much it was used.
    val order = byModel.entries.sortedWith(compareByDescending<Map.Entry<String, Total>> { it.value.spend }
        .thenByDescending { it.value.tokens }.thenBy { it.key }).map { it.key }
    val colorOf = order.withIndex().associate { (i, model) -> model to i % RANK_COLORS }

    fun ranking(value: (Total) -> Double): List<OrRank> {
        val total = byModel.values.sumOf(value)
        val share = { v: Double -> if (total > 0.0) (v / total).toFloat() else 0f }
        val sorted = byModel.entries.sortedWith(compareByDescending<Map.Entry<String, Total>> { value(it.value) }.thenBy { it.key })
        val shown = sorted.filter { value(it.value) > 0.0 }.take(top)
        val shownNames = shown.map { it.key }.toSet()
        val rest = sorted.filter { it.key !in shownNames }
        val out = shown.map { OrRank(it.key, value(it.value), share(value(it.value)), colorOf.getValue(it.key)) }.toMutableList()
        if (rest.isNotEmpty()) {
            val v = rest.sumOf { value(it.value) }
            out.add(OrRank(RANK_OTHERS, v, share(v), -1))
        }
        return out
    }
    return OrWeek(
        label = label,
        through = inside.maxOf { it.date },
        days = inside.map { it.date }.toSet().size,
        spend = byModel.values.sumOf { it.spend },
        requests = byModel.values.sumOf { it.requests },
        tokens = byModel.values.sumOf { it.tokens },
        bySpend = ranking { it.spend },
        byRequests = ranking { it.requests.toDouble() },
        byTokens = ranking { it.tokens.toDouble() },
    )
}

/** A model as the dashboard names it: "qwen/qwen3.7-flash" → "Qwen3.7 Flash", "apodex/apodex-1.1-mini:free" → "Apodex 1.1 Mini (free)". */
fun modelLabel(slug: String): String {
    val name = io.github.erkanw.vault.chat.prettyModelName(slug).ifBlank { slug }
    return if (slug.endsWith(":free")) "$name (free)" else name
}

/** The UTC date of [nowMs] as "2026-10-11": OpenRouter counts its days in UTC. */
fun utcDate(nowMs: Long): String = Instant.ofEpochMilli(nowMs).atZone(ZoneOffset.UTC).toLocalDate().toString()

// ---- words and numbers for the page ----------------------------------------------------------------------------

/** "$12.34"; small amounts keep their digits ("$0.042", "$0.0031") so a cheap day doesn't read as nothing. */
fun usd(value: Double): String {
    val v = if (value.isNaN() || value.isInfinite()) 0.0 else value
    val a = kotlin.math.abs(v)
    val text = when {
        a == 0.0 -> "0.00"
        a >= 1.0 -> String.format(Locale.US, "%,.2f", a)
        a >= 0.01 -> String.format(Locale.US, "%.3f", a)
        else -> String.format(Locale.US, "%.4f", a)
    }
    return (if (v < 0) "-$" else "$") + text
}

/** "1.2M", "340k", "812". */
fun compactCount(n: Long): String = when {
    n >= 1_000_000_000L -> String.format(Locale.US, "%.1fB", n / 1e9)
    n >= 1_000_000L -> String.format(Locale.US, "%.1fM", n / 1e6)
    n >= 10_000L -> "${n / 1000}k"
    n >= 1_000L -> String.format(Locale.US, "%.1fk", n / 1e3)
    else -> n.toString()
}

/** "openai/gpt-4.1" → "gpt-4.1"; the provider goes. A model without a slash is returned as it is. */
fun shortModel(model: String): String = model.substringAfterLast('/').ifBlank { model }

/** "2026-10-09" → "9 Oct". */
fun shortDay(date: String): String = runCatching {
    val d = LocalDate.parse(date)
    "${d.dayOfMonth} ${d.month.getDisplayName(java.time.format.TextStyle.SHORT, Locale.getDefault())}"
}.getOrDefault(date)
