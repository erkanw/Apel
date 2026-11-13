package io.github.erkanw.vault.ui

import androidx.compose.foundation.Canvas
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.State
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.erkanw.vault.SettingsViewModel
import io.github.erkanw.vault.openrouter.OrDayStat
import io.github.erkanw.vault.openrouter.OrModelStat
import io.github.erkanw.vault.openrouter.OrSnapshot
import io.github.erkanw.vault.openrouter.compactCount
import io.github.erkanw.vault.openrouter.shortDay
import io.github.erkanw.vault.openrouter.shortModel
import io.github.erkanw.vault.openrouter.statsOf
import io.github.erkanw.vault.openrouter.usd
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Date

/**
 * OpenRouter credits and spending, reached from Settings. What it can show depends on the key: any key shows what that
 * key spent (today, this week, this month, its limit); a Management key adds the account's balance, every key's spend
 * and the last 30 days model by model, with the statistics drawn from them.
 */
@Composable
fun OpenRouterScreen(vm: SettingsViewModel, onBack: () -> Unit) {
    val k = MaterialTheme.vault
    val context = LocalContext.current
    val demo = androidx.compose.runtime.remember { DemoMode.active(context) }
    val live by vm.openRouter.collectAsStateWithLifecycle()
    val state = if (demo) live.copy(hasKey = true, error = null, snapshot = DemoMode.openRouter(System.currentTimeMillis())) else live
    val snapshot = state.snapshot
    var changingKey by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { if (!demo) vm.openOpenRouter() }
    // Saved: the key form closes once there is a key and the numbers are coming.
    LaunchedEffect(state.hasKey) { if (state.hasKey) changingKey = false }

    val subtitle = when {
        !state.hasKey -> "Add your OpenRouter key to begin"
        state.loading && snapshot == null -> "Loading…"
        state.loading -> "Updating…"
        snapshot != null -> "Updated ${android.text.format.DateFormat.getTimeFormat(context).format(Date(snapshot.at))}"
        else -> "Not loaded yet"
    }

    Column(Modifier.fillMaxSize().background(k.background)) {
        PageHeader("OpenRouter", subtitle, onBack, icon = VaultIcons.ArrowLeft, iconDescription = "Back")
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            state.error?.let { Note(it, danger = true) }
            if (!state.hasKey || changingKey) {
                KeyForm(
                    firstTime = !state.hasKey,
                    onSave = { vm.saveOpenRouterKey(it) },
                    onCancel = { changingKey = false },
                )
            }
            if (state.hasKey && snapshot == null && state.error == null) {
                Note(if (state.loading) "Asking OpenRouter…" else "Nothing loaded yet. Pull the numbers with Refresh.")
            }
            if (snapshot != null) Dashboard(snapshot)
            if (state.hasKey) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    PrimaryButton(if (state.loading) "Refreshing…" else "Refresh", vm::refreshOpenRouter, Modifier.weight(1f), enabled = !state.loading)
                    SecondaryButton(if (changingKey) "Close" else "Change key", { changingKey = !changingKey }, Modifier.weight(1f))
                }
                QuietButton("Remove the key from this phone", vm::removeOpenRouterKey, color = k.danger)
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

// ---- the key ---------------------------------------------------------------------------------------------------

@Composable
private fun KeyForm(firstTime: Boolean, onSave: (String) -> String?, onCancel: () -> Unit) {
    val k = MaterialTheme.vault
    var input by remember { mutableStateOf("") }
    var complaint by remember { mutableStateOf<String?>(null) }
    Card("OpenRouter key") {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "Paste an OpenRouter API key. It is kept encrypted on this phone and only ever sent to openrouter.ai.",
                color = k.muted, style = VaultType.Label.copy(fontWeight = null), lineHeight = 18.sp,
            )
            VaultField(
                value = input,
                onValue = { input = it; complaint = null },
                label = "API key",
                placeholder = "sk-or-v1-…",
                supporting = complaint,
                keyboardType = KeyboardType.Password,
                transformation = PasswordVisualTransformation(),
            )
            Text(
                "A regular key shows what that one key spent. A Management key (created at openrouter.ai/settings/management-keys) " +
                    "also shows the account balance, every key’s spending, and the last 30 days model by model. " +
                    "A Management key can’t make requests, so it can’t be used to spend anything.",
                color = k.muted, style = VaultType.Caption, lineHeight = 17.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                PrimaryButton("Save and load", { complaint = onSave(input) }, Modifier.weight(1f), enabled = input.isNotBlank())
                if (!firstTime) SecondaryButton("Cancel", onCancel, Modifier.weight(1f))
            }
        }
    }
}

// ---- the numbers -----------------------------------------------------------------------------------------------

/*
 * The numbers arrive in order rather than all at once: each card has a clock that starts when the card first shows
 * (at once for the cards on screen, as you scroll for the rest), and each figure, bar and row rises into place when its
 * turn comes on it, top to bottom and left to right (the thirty bars grow from the
 * baseline one after another). Only the draw phase reads the clock, so nothing is laid out again while it runs. With the
 * system's animations off, the clock finishes at once and everything is simply there.
 */

/** How long one piece takes to rise, and the whole arrival at most. */
private const val RISE_MS = 520f
private const val RISE_TOTAL_MS = 2400f

/**
 * A card's own arrival clock and the modifier that starts it: a card on screen when the page opens runs from 0 (so its
 * delays keep the page's order), one further down starts when it first scrolls into view, from just before its own
 * delay, so it rises as you reach it rather than unseen.
 */
@Composable
private fun rememberCardClock(baseDelayMs: Float): Pair<State<Float>, Modifier> {
    val clock = remember { Animatable(0f) }
    val view = androidx.compose.ui.platform.LocalView.current
    var start by remember { mutableStateOf<Float?>(null) }
    var first by remember { mutableStateOf(true) }
    LaunchedEffect(start) {
        val from = start ?: return@LaunchedEffect
        clock.snapTo(from)
        clock.animateTo(RISE_TOTAL_MS, tween((RISE_TOTAL_MS - from).toInt().coerceAtLeast(1), easing = LinearEasing))
    }
    val watch = Modifier.onGloballyPositioned { c ->
        if (start != null) return@onGloballyPositioned
        if (c.positionInWindow().y < view.height) start = if (first) 0f else (baseDelayMs - 40f).coerceAtLeast(0f)
        first = false
    }
    return clock.asState() to watch
}

/** How far a piece that starts at [delayMs] has risen, 0 to 1, eased like an arrival. */
private fun riseOf(clockMs: Float, delayMs: Float, durationMs: Float = RISE_MS): Float =
    Motion.Decelerate.transform(((clockMs - delayMs) / durationMs).coerceIn(0f, 1f))

/** Rises [distance] up into place while fading in, starting at [delayMs] on [clock]. */
private fun Modifier.rise(clock: State<Float>, delayMs: Float, distance: Dp = 16.dp): Modifier = graphicsLayer {
    val p = riseOf(clock.value, delayMs)
    alpha = p
    translationY = (1f - p) * distance.toPx()
}

@Composable
private fun Dashboard(snapshot: OrSnapshot) {
    val k = MaterialTheme.vault
    // OpenRouter's activity ends with the last completed UTC day (yesterday), so that is where the chart ends.
    val stats = remember(snapshot.rows) { statsOf(snapshot.rows, endDate = LocalDate.now(ZoneOffset.UTC).minusDays(1).toString()) }
    val (clock, seenBalance) = rememberCardClock(0f)

    Card("Balance", seenBalance.rise(clock, 0f)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            val balance = snapshot.balance
            Column(Modifier.rise(clock, 60f)) {
                Text(
                    balance?.let { usd(it) } ?: usd(snapshot.thisMonth),
                    color = if (balance != null && balance < 2.0) k.attention else k.text,
                    fontSize = 34.sp, fontWeight = FontWeight.SemiBold,
                )
                Text(
                    when {
                        snapshot.credits != null -> "credits left of ${usd(snapshot.credits.total)} bought"
                        balance != null -> "left on this key’s limit of ${usd(snapshot.key.limit ?: 0.0)}"
                        else -> "spent by this key this month (it has no spending limit)"
                    },
                    color = k.muted, style = VaultType.Label.copy(fontWeight = null),
                )
            }
            // The three figures rise left to right.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Figure("Today", usd(snapshot.today), Modifier.weight(1f).rise(clock, 140f))
                Figure("This week", usd(snapshot.thisWeek), Modifier.weight(1f).rise(clock, 200f))
                Figure("This month", usd(snapshot.thisMonth), Modifier.weight(1f).rise(clock, 260f))
            }
            Text(
                "Days, weeks and months are UTC, as OpenRouter counts them." +
                    if (snapshot.keys.isNotEmpty()) " Added up over ${snapshot.keys.size} key${if (snapshot.keys.size == 1) "" else "s"}." else " Only what this key spent.",
                color = k.muted, style = VaultType.Caption.copy(fontSize = 11.sp), lineHeight = 15.sp,
                modifier = Modifier.rise(clock, 300f, 8.dp),
            )
        }
    }

    if (!snapshot.detailed) {
        Note(
            "This is a regular key, so it can only see what it spent itself. For the account balance and a model-by-model " +
                "breakdown, create a Management key at openrouter.ai/settings/management-keys and use it here (Change key).",
            modifier = Modifier.rise(clock, 200f),
        )
        return
    }

    val (clock30, seen30) = rememberCardClock(180f)
    Card("Last 30 days", seen30.rise(clock30, 180f)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.rise(clock30, 240f), verticalAlignment = Alignment.Bottom) {
                Text(usd(stats.total), color = k.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Text("${usd(stats.perDay)} a day", color = k.muted, style = VaultType.Label.copy(fontWeight = null))
            }
            DailyBars(stats.days, clock30, startMs = 300f)
            Row(Modifier.fillMaxWidth().rise(clock30, 340f, 8.dp)) {
                Text(shortDay(stats.days.first().date), color = k.muted, style = VaultType.Caption.copy(fontSize = 11.sp))
                Spacer(Modifier.weight(1f))
                stats.busiest?.let { Text("Busiest ${shortDay(it.date)} · ${usd(it.usage)}", color = k.muted, style = VaultType.Caption.copy(fontSize = 11.sp)) }
                Spacer(Modifier.weight(1f))
                Text(shortDay(stats.days.last().date), color = k.muted, style = VaultType.Caption.copy(fontSize = 11.sp))
            }
        }
    }

    if (stats.models.isNotEmpty()) {
        val (clockModels, seenModels) = rememberCardClock(360f)
        Card("By model", seenModels.rise(clockModels, 360f)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                stats.models.take(8).forEachIndexed { i, m -> ModelRow(m, clockModels, delayMs = 420f + i * 60f) }
                if (stats.models.size > 8) {
                    val rest = stats.models.drop(8)
                    Text(
                        "and ${rest.size} more · ${usd(rest.sumOf { it.usage })}",
                        color = k.muted, style = VaultType.Caption,
                        modifier = Modifier.rise(clockModels, 420f + 8 * 60f, 8.dp),
                    )
                }
            }
        }
    }

    val (clockStats, seenStats) = rememberCardClock(520f)
    Card("Statistics", seenStats.rise(clockStats, 520f)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            var n = 0
            fun next() = 560f + 40f * n++
            StatRow("Requests", compactCount(stats.requests.toLong()), Modifier.rise(clockStats, next(), 10.dp))
            StatRow("Average per request", stats.perRequest?.let { usd(it) } ?: "—", Modifier.rise(clockStats, next(), 10.dp))
            StatRow("Tokens in / out", "${compactCount(stats.promptTokens)} / ${compactCount(stats.completionTokens)}", Modifier.rise(clockStats, next(), 10.dp))
            if (stats.reasoningTokens > 0) StatRow("Reasoning tokens", compactCount(stats.reasoningTokens), Modifier.rise(clockStats, next(), 10.dp))
            stats.cachedShare?.let { StatRow("Served from cache", "${(it * 100).toInt()}% of input", Modifier.rise(clockStats, next(), 10.dp)) }
            StatRow("Last 7 days", usd(stats.last7), Modifier.rise(clockStats, next(), 10.dp))
            StatRow("The 7 days before", usd(stats.before7), Modifier.rise(clockStats, next(), 10.dp))
            StatRow("At this pace", "${usd(stats.monthAtThisPace)} a month", Modifier.rise(clockStats, next(), 10.dp))
        }
    }

    if (snapshot.keys.size > 1 || snapshot.keys.any { it.disabled }) {
        val (clockKeys, seenKeys) = rememberCardClock(640f)
        Card("By key", seenKeys.rise(clockKeys, 640f)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                snapshot.keys.sortedByDescending { it.monthly }.forEachIndexed { i, key ->
                    Row(Modifier.rise(clockKeys, 680f + i * 50f, 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                key.name.ifBlank { "Unnamed key" } + if (key.disabled) " (off)" else "",
                                color = k.text, style = VaultType.Preview, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            Text("Today ${usd(key.daily)} · week ${usd(key.weekly)}", color = k.muted, style = VaultType.Caption)
                        }
                        Text(usd(key.monthly), color = k.text, style = VaultType.Preview, fontWeight = FontWeight.Medium)
                    }
                }
                Text("The amounts on the right are this month.", color = k.muted, style = VaultType.Caption.copy(fontSize = 11.sp))
            }
        }
    }
}

/** The thirty days as bars that grow from the baseline one after another, left to right, starting at [startMs]. */
@Composable
private fun DailyBars(days: List<OrDayStat>, clock: State<Float>, startMs: Float) {
    val k = MaterialTheme.vault
    val accent = k.accent
    val idle = k.line
    val peak = (days.maxOfOrNull { it.usage } ?: 0.0).coerceAtLeast(1e-9)
    Canvas(Modifier.fillMaxWidth().height(96.dp)) {
        val gap = 3.dp.toPx()
        val barWidth = ((size.width - gap * (days.size - 1)) / days.size).coerceAtLeast(1f)
        val now = clock.value
        days.forEachIndexed { i, day ->
            val p = riseOf(now, startMs + i * 16f)
            if (p <= 0f) return@forEachIndexed
            // A quiet day is a short stub, so the row still reads as thirty days.
            val full = if (day.usage <= 0.0) 3.dp.toPx() else (size.height * (day.usage / peak)).toFloat().coerceAtLeast(5.dp.toPx())
            val h = full * p
            drawRoundRect(
                color = if (day.usage > 0.0) accent else idle,
                topLeft = Offset(i * (barWidth + gap), size.height - h),
                size = Size(barWidth, h),
                cornerRadius = CornerRadius((barWidth / 3f).coerceAtMost(h / 2f)),
                alpha = (p * 1.6f).coerceAtMost(1f),
            )
        }
    }
}

/** A model's name and cost, rising at [delayMs]; its share bar then grows to its length. */
@Composable
private fun ModelRow(stat: OrModelStat, clock: State<Float>, delayMs: Float) {
    val k = MaterialTheme.vault
    val track = k.line
    val fill = k.accent
    Column(Modifier.rise(clock, delayMs, 12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                shortModel(stat.model), color = k.text, style = VaultType.Body, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            Text(usd(stat.usage), color = k.text, style = VaultType.Body, fontWeight = FontWeight.Medium)
        }
        Canvas(Modifier.fillMaxWidth().height(5.dp)) {
            val r = CornerRadius(size.height / 2f)
            drawRoundRect(track, cornerRadius = r)
            val p = riseOf(clock.value, delayMs + 120f, 640f)
            val w = size.width * stat.share.coerceIn(0.02f, 1f) * p
            if (w > 0f) drawRoundRect(fill, size = Size(w, size.height), cornerRadius = r)
        }
        Text(
            "${(stat.share * 100).toInt()}% · ${compactCount(stat.requests.toLong())} requests · ${compactCount(stat.tokens)} tokens",
            color = k.muted, style = VaultType.Caption,
        )
    }
}

// ---- small pieces ----------------------------------------------------------------------------------------------

@Composable
private fun Card(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val k = MaterialTheme.vault
    Column(modifier) {
        GroupLabel(title)
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(k.surfaceHigh), content = content)
    }
}

@Composable
private fun Figure(label: String, value: String, modifier: Modifier = Modifier) {
    val k = MaterialTheme.vault
    Column(modifier.clip(RoundedCornerShape(10.dp)).background(k.surfaceHigh).padding(horizontal = 10.dp, vertical = 9.dp)) {
        Text(label, color = k.muted, style = VaultType.Caption.copy(fontSize = 11.sp), maxLines = 1)
        Text(value, color = k.text, style = VaultType.Body.copy(fontSize = 16.sp), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun StatRow(label: String, value: String, modifier: Modifier = Modifier) {
    val k = MaterialTheme.vault
    Row(modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = k.muted, style = VaultType.Preview, modifier = Modifier.weight(1f))
        Text(value, color = k.text, style = VaultType.Preview, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun Note(text: String, danger: Boolean = false, modifier: Modifier = Modifier) {
    val k = MaterialTheme.vault
    Text(
        text,
        color = if (danger) k.danger else k.muted,
        style = VaultType.Label.copy(fontWeight = null), lineHeight = 18.sp,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (danger) k.danger.copy(alpha = 0.12f) else k.surface)
            .padding(14.dp),
    )
}
