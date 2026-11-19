package io.github.erkanw.vault.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.erkanw.vault.UsageViewModel
import io.github.erkanw.vault.openrouter.OrDayStat
import io.github.erkanw.vault.openrouter.OrModelStat
import io.github.erkanw.vault.openrouter.OrSnapshot
import io.github.erkanw.vault.openrouter.compactCount
import io.github.erkanw.vault.openrouter.modelLabel
import io.github.erkanw.vault.openrouter.shortDay
import io.github.erkanw.vault.openrouter.usd
import io.github.erkanw.vault.provider.ProviderKind
import io.github.erkanw.vault.usage.Budget
import io.github.erkanw.vault.usage.UsageStats
import io.github.erkanw.vault.usage.UsageSummary
import java.util.Date
import java.util.Locale

/**
 * Usage (PLAN §6.11, handbook §3): the former OpenRouter page, fed by the app's own log. The budget on top; what was spent
 * today, this week and this month; the last 30 days as bars; by model; by source (you, each automation); the
 * statistics, with the free-model counter; then what OpenRouter says about the key: credits, its limit, its own count.
 */
@Composable
fun UsageScreen(vm: UsageViewModel, onBack: () -> Unit) {
    val k = MaterialTheme.vault
    val context = LocalContext.current
    val demo = remember { DemoMode.active(context) }
    val summary by vm.summary.collectAsStateWithLifecycle()
    val live by vm.credits.collectAsStateWithLifecycle()
    val provider by vm.provider.collectAsStateWithLifecycle()
    val budget by vm.budget.collectAsStateWithLifecycle()
    val credits = if (demo) live.copy(hasKey = true, error = null, loading = false, snapshot = DemoMode.openRouter(System.currentTimeMillis())) else live
    val openRouter = demo || provider.kind == ProviderKind.OpenRouter
    var editingBudget by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.open() }

    Column(Modifier.fillMaxSize().background(k.background)) {
        PageHeader("Usage", if (openRouter && credits.loading) "Updating…" else "", onBack)
        val s = summary
        if (s == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { TypingDots(k.muted) }
            return@Column
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            BudgetCard(budget, s, onEdit = { editingBudget = true })
            if (s.isEmpty) {
                Text(
                    "Nothing spent yet. What each answer costs shows here, by model and by who asked.",
                    style = VaultType.Body, color = k.muted, modifier = Modifier.padding(horizontal = 8.dp),
                )
            } else {
                Dashboard(s, credits.snapshot, openRouter)
            }
            if (openRouter) OpenRouterCard(credits, s, onRefresh = { vm.refreshCredits() })
        }
    }
    if (editingBudget) {
        BudgetSheet(budget?.settings ?: Budget.Settings(0.0, Budget.Action.Pause), openRouter, onDismiss = { editingBudget = false }) { limit, action ->
            vm.setBudget(limit, action)
            editingBudget = false
        }
    }
}

// ---- the budget ------------------------------------------------------------------------------------------------

/** The month against the limit, with a bar: live under 80 %, attention over, danger at the limit. Tapping changes it. */
@Composable
private fun BudgetCard(status: Budget.Status?, s: UsageSummary, onEdit: () -> Unit) {
    val k = MaterialTheme.vault
    val settings = status?.settings
    Column {
        GroupLabel("Budget")
        VaultGroup {
            if (settings == null || !settings.on) {
                GroupActionRow(
                    "Set a monthly budget", "Off. A limit for each month, with a notification at 80%.", onEdit, chevron = true,
                )
                return@VaultGroup
            }
            val spent = status.spent
            val fraction = (spent / settings.limit).coerceAtLeast(0.0)
            val tone = when {
                status.over -> k.danger
                status.near -> k.attention
                else -> k.live
            }
            val resets = remember { nextMonthLabel() }
            val atLimit = when (settings.action) {
                Budget.Action.Pause -> "pauses automations"
                Budget.Action.Ask -> "asks before each message"
                Budget.Action.Both -> "pauses automations and asks before each message"
            }
            val said = "${usd(spent)} of ${usd(settings.limit)} spent this month, ${(fraction * 100).toInt()}%." +
                (if (status.over) " Used up." else " At the limit it $atLimit.") + " Change the budget."
            Column(
                Modifier.fillMaxWidth().pressable(onClick = onEdit, pressedScale = 1f, pressed = k.pressed, role = Role.Button)
                    .semantics(mergeDescendants = true) { contentDescription = said }
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(usd(spent), style = VaultType.Display, color = k.text)
                    Text(" of ${usd(settings.limit)}", style = VaultType.Body, color = k.muted, modifier = Modifier.padding(bottom = 2.dp))
                    Spacer(Modifier.weight(1f))
                    Text("${(fraction * 100).toInt()}%", style = VaultType.Label, color = if (status.over || status.near) tone else k.muted)
                }
                Canvas(Modifier.fillMaxWidth().height(6.dp)) {
                    val r = CornerRadius(size.height / 2f)
                    drawRoundRect(k.line, cornerRadius = r)
                    val w = size.width * fraction.coerceIn(0.0, 1.0).toFloat()
                    if (w > 0f) drawRoundRect(tone, size = Size(w.coerceAtLeast(size.height), size.height), cornerRadius = r)
                }
                val nowSo = when (settings.action) {
                    Budget.Action.Pause -> "Automations are paused."
                    Budget.Action.Ask -> "Each message asks first."
                    Budget.Action.Both -> "Automations are paused and each message asks first."
                }
                Text(
                    (if (status.over) nowSo else "At the limit it $atLimit.") + " A new month starts $resets.",
                    style = VaultType.Caption, color = k.muted,
                )
            }
        }
    }
}

/** "on 1 Dec", the first day of next month (UTC). */
private fun nextMonthLabel(): String {
    val next = UsageStats.utcDay(System.currentTimeMillis()).withDayOfMonth(1).plusMonths(1)
    return "on ${next.dayOfMonth} ${next.month.getDisplayName(java.time.format.TextStyle.SHORT, Locale.getDefault())}"
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun BudgetSheet(current: Budget.Settings, openRouter: Boolean, onDismiss: () -> Unit, onSave: (Double, Budget.Action) -> Unit) {
    val k = MaterialTheme.vault
    val start = if (current.on) current.limit else Budget.SUGGESTED
    var text by remember { mutableStateOf(String.format(Locale.US, if (start % 1.0 == 0.0) "%.0f" else "%.2f", start)) }
    var action by remember { mutableStateOf(current.action) }
    val limit = text.replace(',', '.').trim().toDoubleOrNull()
    val valid = limit != null && limit > 0.0 && limit <= 10_000.0
    VaultSheet(onDismiss = onDismiss, title = "Monthly budget") {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            VaultField(
                text, { text = it.filter { c -> c.isDigit() || c == '.' || c == ',' }.take(8) }, "Each month, in US dollars",
                placeholder = "5", keyboardType = KeyboardType.Decimal,
                supporting = if (text.isNotBlank() && !valid) "A number above 0, like 5 or 12.50." else "A notification comes at 80%. Months are UTC" + if (openRouter) ", as OpenRouter counts them." else ".",
                isError = text.isNotBlank() && !valid,
            )
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("At the limit", style = VaultType.Label, color = k.muted)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Budget.Action.entries.forEach { a -> VaultChip(a.label, { action = a }, selected = action == a) }
                }
                Text(
                    when (action) {
                        Budget.Action.Pause -> "Automations wait until the month ends. Your own messages go on."
                        Budget.Action.Ask -> "Each message to a paid model asks you before it is sent. Automations go on."
                        Budget.Action.Both -> "Automations wait, and each message to a paid model asks you before it is sent."
                    },
                    style = VaultType.Caption, color = k.muted,
                )
            }
            PrimaryButton(if (current.on) "Save budget" else "Set budget", { if (valid) onSave(limit!!, action) }, Modifier.fillMaxWidth(), enabled = valid)
            if (current.on) QuietButton("Turn the budget off", { onSave(0.0, action) }, Modifier.fillMaxWidth())
            Spacer(Modifier.height(4.dp))
        }
    }
}

// ---- the numbers -----------------------------------------------------------------------------------------------

/*
 * The numbers arrive in order rather than all at once: each card has a clock that starts when the card first shows
 * (at once for the cards on screen, as you scroll for the rest), and each figure, bar and row rises into place when its
 * turn comes on it, top to bottom and left to right (the thirty bars grow from the baseline one after another). Only the
 * draw phase reads the clock, so nothing is laid out again while it runs. With the system's animations off, the clock
 * finishes at once and everything is simply there.
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
private fun Dashboard(s: UsageSummary, credits: OrSnapshot?, openRouter: Boolean) {
    val k = MaterialTheme.vault
    val (clock, seenSpend) = rememberCardClock(0f)

    Card("Spent", seenSpend.rise(clock, 0f)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(Modifier.rise(clock, 60f)) {
                Text(usd(s.month), color = k.text, fontSize = 34.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "spent this month" + (credits?.credits?.let { " · ${usd(it.left)} of credits left" } ?: ""),
                    color = k.muted, style = VaultType.Label.copy(fontWeight = null),
                )
            }
            // The three figures rise left to right; with very large text they stack, so no amount is cut.
            if (androidx.compose.ui.platform.LocalDensity.current.fontScale < 1.5f) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Figure("Today", usd(s.today), Modifier.weight(1f).rise(clock, 140f))
                    Figure("This week", usd(s.week), Modifier.weight(1f).rise(clock, 200f))
                    Figure("Per answer", s.perMessage?.let { usd(it) } ?: "—", Modifier.weight(1f).rise(clock, 260f))
                }
            } else {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Figure("Today", usd(s.today), Modifier.fillMaxWidth().rise(clock, 140f))
                    Figure("This week", usd(s.week), Modifier.fillMaxWidth().rise(clock, 200f))
                    Figure("Per answer", s.perMessage?.let { usd(it) } ?: "—", Modifier.fillMaxWidth().rise(clock, 260f))
                }
            }
            Text(
                "Days, weeks (from Monday) and months are UTC" + (if (openRouter) ", as OpenRouter counts them." else ".") + " Per answer is the average of the last 30 days.",
                color = k.muted, style = VaultType.Caption,
                modifier = Modifier.rise(clock, 300f, 8.dp),
            )
        }
    }

    val (clock30, seen30) = rememberCardClock(180f)
    Card("Last 30 days", seen30.rise(clock30, 180f)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.rise(clock30, 240f), verticalAlignment = Alignment.Bottom) {
                Text(usd(s.total), color = k.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Text("${usd(s.total / UsageStats.DAYS)} a day", color = k.muted, style = VaultType.Label.copy(fontWeight = null))
            }
            DailyBars(s.days, clock30, startMs = 300f)
            Row(Modifier.fillMaxWidth().rise(clock30, 340f, 8.dp)) {
                Text(shortDay(s.days.first().date), color = k.muted, style = VaultType.Caption)
                Spacer(Modifier.weight(1f))
                s.busiest?.let { Text("Busiest ${shortDay(it.date)} · ${usd(it.usage)}", color = k.muted, style = VaultType.Caption) }
                Spacer(Modifier.weight(1f))
                Text("Today", color = k.muted, style = VaultType.Caption)
            }
        }
    }

    if (s.models.isNotEmpty()) {
        val (clockModels, seenModels) = rememberCardClock(360f)
        Card("By model", seenModels.rise(clockModels, 360f)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                s.models.take(8).forEachIndexed { i, m -> ModelRow(m, clockModels, delayMs = 420f + i * 60f) }
                if (s.models.size > 8) {
                    val rest = s.models.drop(8)
                    Text(
                        "and ${rest.size} more · ${usd(rest.sumOf { it.usage })}",
                        color = k.muted, style = VaultType.Caption,
                        modifier = Modifier.rise(clockModels, 420f + 8 * 60f, 8.dp),
                    )
                }
            }
        }
    }

    if (s.agents.size > 1) {
        val (clockAgents, seenAgents) = rememberCardClock(430f)
        Card("By agent", seenAgents.rise(clockAgents, 430f)) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                s.agents.forEachIndexed { i, a ->
                    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.padding(vertical = 9.dp).rise(clockAgents, 460f + i * 50f, 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(a.label, color = k.text, style = VaultType.Preview, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${a.messages} answer${if (a.messages == 1) "" else "s"}", color = k.muted, style = VaultType.Caption)
                        }
                        Text(usd(a.cost), color = k.text, style = VaultType.Preview, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }

    if (s.sources.isNotEmpty()) {
        val (clockSources, seenSources) = rememberCardClock(440f)
        Card("By who asked", seenSources.rise(clockSources, 440f)) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                s.sources.forEachIndexed { i, src ->
                    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.padding(vertical = 9.dp).rise(clockSources, 480f + i * 50f, 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(src.label, color = k.text, style = VaultType.Preview, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${src.messages} answer${if (src.messages == 1) "" else "s"}", color = k.muted, style = VaultType.Caption)
                        }
                        Text(usd(src.cost), color = k.text, style = VaultType.Preview, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }

    val (clockStats, seenStats) = rememberCardClock(520f)
    Card("Statistics", seenStats.rise(clockStats, 520f)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            var n = 0
            fun next() = 560f + 40f * n++
            StatRow("Answers", compactCount(s.messages.toLong()), Modifier.rise(clockStats, next(), 10.dp))
            StatRow("Requests to models", compactCount(s.requests.toLong()), Modifier.rise(clockStats, next(), 10.dp))
            StatRow("Tokens in / out", "${compactCount(s.tokensIn)} / ${compactCount(s.tokensOut)}", Modifier.rise(clockStats, next(), 10.dp))
            if (openRouter) StatRow("Free-model requests today", freeCounter(s.freeToday, credits), Modifier.rise(clockStats, next(), 10.dp))
            StatRow("Last 7 days", usd(s.last7), Modifier.rise(clockStats, next(), 10.dp))
            StatRow("The 7 days before", usd(s.before7), Modifier.rise(clockStats, next(), 10.dp))
            StatRow("At this pace", "${usd(s.last7 / 7.0 * 30.0)} a month", Modifier.rise(clockStats, next(), 10.dp))
            Text(
                "The last 30 days." + (if (openRouter) " Free-model requests are this phone’s; OpenRouter’s daily limit counts all your devices." else "") + if (s.unpriced > 0) " ${s.unpriced} answer${if (s.unpriced == 1) "" else "s"} came without a price from your server, counted as nothing." else "",
                color = k.muted, style = VaultType.Caption, modifier = Modifier.padding(vertical = 8.dp).rise(clockStats, next(), 8.dp),
            )
        }
    }
}

/** "12 of 50": OpenRouter's free models allow 50 requests a day, 1,000 once $10 of credits were bought. */
private fun freeCounter(used: Int, credits: OrSnapshot?): String {
    val bought = credits?.credits?.total ?: return used.toString()
    return "$used of ${if (bought >= 10.0) "1,000" else "50"}"
}

/** What OpenRouter itself says: the credits, the key's limit, and its own count for this key (to check the log against). */
@Composable
private fun OpenRouterCard(state: io.github.erkanw.vault.openrouter.OrState, s: UsageSummary, onRefresh: () -> Unit) {
    val k = MaterialTheme.vault
    val context = LocalContext.current
    val snap = state.snapshot
    Column {
        GroupLabel("OpenRouter")
        VaultGroup {
            state.error?.let {
                Text(
                    "$it Tap to try again.", style = VaultType.Label.copy(fontWeight = null), color = k.attention,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).pressable(onClick = onRefresh, pressedScale = 1f, pressed = k.pressed, onClickLabel = "Try again")
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                )
                if (snap != null) GroupDivider()
            }
            if (snap == null) {
                if (state.error == null) Text(
                    if (state.loading) "Asking OpenRouter…" else "Not loaded yet.", style = VaultType.Label.copy(fontWeight = null), color = k.muted,
                    modifier = Modifier.padding(16.dp),
                )
                return@VaultGroup
            }
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                snap.credits?.let { StatRow("Credits left", "${usd(it.left)} of ${usd(it.total)}") }
                StatRow(
                    "This key’s limit",
                    snap.key.limit?.let { limit -> "${usd(snap.key.limitRemaining ?: 0.0)} left of ${usd(limit)}" } ?: "None",
                )
                StatRow("OpenRouter’s count today", usd(snap.key.daily))
                StatRow("OpenRouter’s count this month", usd(snap.key.monthly))
                val gap = kotlin.math.abs(snap.key.monthly - s.month)
                Text(
                    "OpenRouter’s count is for the key this app signed in with, as of " +
                        android.text.format.DateFormat.getTimeFormat(context).format(Date(snap.at)) + "." +
                        if (gap >= 0.01) " This month it differs from the log by ${usd(gap)}: a stopped answer, or a call made since." else " This month it matches this phone’s log.",
                    color = k.muted, style = VaultType.Caption, modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            GroupDivider()
            GroupActionRow(if (state.loading) "Refreshing…" else "Refresh", null, onRefresh, enabled = !state.loading)
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
    val busiest = days.maxByOrNull { it.usage }?.takeIf { it.usage > 0 }
    val said = "Daily spend over the last 30 days, ${usd(days.sumOf { it.usage })} in all" +
        (busiest?.let { "; the busiest day was ${shortDay(it.date)}, ${usd(it.usage)}." } ?: ".")
    Canvas(Modifier.fillMaxWidth().height(96.dp).semantics { contentDescription = said }) {
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

/** A model's name and cost, rising at [delayMs]; its share bar then grows to its length. A free model says so. */
@Composable
private fun ModelRow(stat: OrModelStat, clock: State<Float>, delayMs: Float) {
    val k = MaterialTheme.vault
    val track = k.line
    val fill = k.accent
    // A free model that still cost something (web search is paid on any model) is shown like the others.
    val free = UsageStats.isFree(stat.model) && stat.usage == 0.0
    Column(Modifier.semantics(mergeDescendants = true) {}.rise(clock, delayMs, 12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                modelLabel(stat.model).removeSuffix(" (free)"), color = k.text, style = VaultType.Body, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            Text(if (free) "Free" else usd(stat.usage), color = if (free) k.muted else k.text, style = VaultType.Body, fontWeight = FontWeight.Medium)
        }
        if (!free) Canvas(Modifier.fillMaxWidth().height(5.dp)) {
            val r = CornerRadius(size.height / 2f)
            drawRoundRect(track, cornerRadius = r)
            val p = riseOf(clock.value, delayMs + 120f, 640f)
            val w = size.width * stat.share.coerceIn(0.02f, 1f) * p
            if (w > 0f) drawRoundRect(fill, size = Size(w, size.height), cornerRadius = r)
        }
        Text(
            (if (free) "" else "${(stat.share * 100).toInt()}% · ") + "${compactCount(stat.requests.toLong())} requests · ${compactCount(stat.tokens)} tokens",
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
    Column(modifier.semantics(mergeDescendants = true) {}.clip(RoundedCornerShape(10.dp)).background(k.surfaceHigh).padding(horizontal = 10.dp, vertical = 9.dp)) {
        Text(label, color = k.muted, style = VaultType.Caption, maxLines = 1)
        Text(value, color = k.text, style = VaultType.Body.copy(fontSize = 16.sp), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun StatRow(label: String, value: String, modifier: Modifier = Modifier) {
    val k = MaterialTheme.vault
    Row(modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = k.muted, style = VaultType.Preview, modifier = Modifier.weight(1f))
        Text(value, color = k.text, style = VaultType.Preview, fontWeight = FontWeight.Medium)
    }
}
