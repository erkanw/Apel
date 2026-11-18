package io.github.erkanw.vault.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import io.github.erkanw.vault.MainActivity
import io.github.erkanw.vault.R
import io.github.erkanw.vault.openrouter.OpenRouterHub
import io.github.erkanw.vault.openrouter.OrRank
import io.github.erkanw.vault.openrouter.OrState
import io.github.erkanw.vault.openrouter.OrWeek
import io.github.erkanw.vault.openrouter.RANK_OTHERS
import io.github.erkanw.vault.openrouter.compactCount
import io.github.erkanw.vault.openrouter.modelLabel
import io.github.erkanw.vault.openrouter.shortDay
import io.github.erkanw.vault.openrouter.shortModel
import io.github.erkanw.vault.openrouter.usd
import io.github.erkanw.vault.openrouter.weekOf
import io.github.erkanw.vault.provider.ProviderKind
import io.github.erkanw.vault.provider.ProviderStore
import io.github.erkanw.vault.usage.Budget
import io.github.erkanw.vault.usage.UsageLog
import io.github.erkanw.vault.usage.UsageStats
import io.github.erkanw.vault.usage.UsageSummary
import kotlinx.coroutines.runBlocking
import java.util.Date

/**
 * The Usage widget (the former OpenRouter widget, same look): what is left (of the month's budget, or of the credits) and
 * what was spent today; wider, the week, the month and the top model; large, this week by model. The spending comes
 * from the app's own log, so it is current after every answer; the credits are the last ones OpenRouter gave. Tapping
 * it opens Usage.
 */
object UsageWidget {
    private const val TAG = "UsageWidget"
    private const val WIDE_MIN_DP = 200
    private const val LARGE_MIN_WIDTH_DP = 250
    private const val LARGE_MIN_HEIGHT_DP = 250
    private const val LOW = 0xFFF5A524.toInt() // attention: running low is a warning, not an error
    private const val WHITE = 0xFFF5F5F7.toInt()
    private const val DANGER = 0xFFFF5A4E.toInt()
    private const val LOW_BALANCE = 2.0

    /** Everything one refresh shows, read once. */
    private class Data(val summary: UsageSummary, val week: OrWeek?, val credits: OrState, val budget: Budget.Settings, val connected: Boolean)

    /** Redraws every Usage widget. Blocking (it reads the log): call it off the main thread. */
    fun updateAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        update(context, manager, manager.getAppWidgetIds(ComponentName(context, UsageWidgetProvider::class.java)))
    }

    fun update(context: Context, manager: AppWidgetManager, ids: IntArray) {
        if (ids.isEmpty()) return
        try {
            val app = context.applicationContext
            OpenRouterHub.ensureLoaded(app)
            ProviderStore.ensureLoaded(app)
            val now = System.currentTimeMillis()
            val data = runBlocking {
                val calls = UsageLog.calls(app, UsageStats.since(now))
                val names = io.github.erkanw.vault.store.AppDatabase.get(app).automations().all().associate { it.id to it.name }
                Data(
                    UsageStats.summarize(calls, now, names),
                    weekOf(UsageStats.activityRows(calls), UsageStats.utcDay(now), includeToday = true),
                    OpenRouterHub.state.value,
                    Budget.settings(app),
                    ProviderStore.state.value.connected,
                )
            }
            for (id in ids) {
                val (width, height) = widgetSizeDp(context, manager.getAppWidgetOptions(id))
                val views = if (data.week != null && width >= LARGE_MIN_WIDTH_DP && height >= LARGE_MIN_HEIGHT_DP) {
                    buildLarge(context, data, data.week)
                } else {
                    build(context, width >= WIDE_MIN_DP, data)
                }
                manager.updateAppWidget(id, views)
            }
        } catch (e: Exception) {
            Log.w(TAG, "widget update failed", e)
        }
    }

    private fun openIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 20, Intent(context, MainActivity::class.java).apply { action = MainActivity.ACTION_USAGE },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun build(context: Context, wide: Boolean, data: Data): RemoteViews {
        val views = RemoteViews(context.packageName, if (wide) R.layout.widget_usage_wide else R.layout.widget_usage_small)
        val s = data.summary
        val snapshot = data.credits.snapshot
        var mainColor = WHITE
        // An empty line would still take its height (and its margin) and push the rest off centre, so it is hidden instead.
        fun line(id: Int, text: String) {
            views.setTextViewText(id, text)
            views.setViewVisibility(id, if (text.isBlank()) View.GONE else View.VISIBLE)
        }
        var time = ""
        when {
            data.budget.on -> {
                val left = (data.budget.limit - s.month).coerceAtLeast(0.0)
                views.setTextViewText(R.id.or_main, if (left <= 0.0) "Used up" else usd(left))
                views.setTextViewText(R.id.or_label, if (left <= 0.0) "budget of ${usd(data.budget.limit)}" else "left of the month’s budget")
                mainColor = when {
                    left <= 0.0 -> DANGER
                    s.month >= data.budget.limit * Budget.WARN_AT -> LOW
                    else -> WHITE
                }
            }
            snapshot?.balance != null -> {
                val balance = snapshot.balance!!
                views.setTextViewText(R.id.or_main, usd(balance))
                views.setTextViewText(R.id.or_label, if (snapshot.credits != null) "credits left" else "left on the key’s limit")
                if (balance < LOW_BALANCE) mainColor = LOW
                time = android.text.format.DateFormat.getTimeFormat(context).format(Date(snapshot.at))
            }
            else -> {
                views.setTextViewText(R.id.or_main, usd(s.month))
                views.setTextViewText(R.id.or_label, if (data.connected || !s.isEmpty) "spent this month" else "Not connected yet")
            }
        }
        line(R.id.or_sub, "Today ${usd(s.today)}")
        line(R.id.or_extra, if (wide) extraLine(s) else "")
        views.setTextColor(R.id.or_main, mainColor)
        line(R.id.or_time, time)
        views.setOnClickPendingIntent(R.id.root, openIntent(context))
        return views
    }

    /** The large widget, after the dashboard's "This Week's Usage": spend, requests and tokens, each by model. */
    private fun buildLarge(context: Context, data: Data, week: OrWeek): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_usage_large)
        views.setTextViewText(R.id.w_period, "${week.label} · to ${shortDay(week.through)}")
        views.setTextViewText(R.id.w_spend, usd(week.spend))
        views.setTextViewText(R.id.w_req, compactCount(week.requests))
        views.setTextViewText(R.id.w_tok, compactCount(week.tokens))
        fillRows(views, week.bySpend, SPEND_ROWS) { usd(it) }
        fillRows(views, week.byRequests, REQUEST_ROWS) { compactCount(it.toLong()) }
        fillRows(views, week.byTokens, TOKEN_ROWS) { compactCount(it.toLong()) }
        val budget = data.budget.takeIf { it.on }?.let { "Budget ${usd((it.limit - data.summary.month).coerceAtLeast(0.0))} left" }
        views.setTextViewText(
            R.id.w_foot,
            listOfNotNull(
                budget ?: data.credits.snapshot?.balance?.let { "Credits ${usd(it)}" },
                "Today ${usd(data.summary.today)}",
                "Month ${usd(data.summary.month)}",
            ).joinToString(" · "),
        )
        views.setOnClickPendingIntent(R.id.root, openIntent(context))
        return views
    }

    /** One column's ranking: up to four rows (container, name, value), the rest hidden. */
    private fun fillRows(views: RemoteViews, ranking: List<OrRank>, rows: IntArray, format: (Double) -> String) {
        for (line in 0 until 4) {
            val container = rows[line * 3]
            val rank = ranking.getOrNull(line)
            views.setViewVisibility(container, if (rank == null) View.GONE else View.VISIBLE)
            if (rank == null) continue
            val color = if (rank.color < 0) OTHERS_GREY else PALETTE[rank.color % PALETTE.size]
            val name = if (rank.model == RANK_OTHERS) RANK_OTHERS else modelLabel(rank.model)
            views.setTextViewText(
                rows[line * 3 + 1],
                SpannableStringBuilder("● $name").apply { setSpan(ForegroundColorSpan(color), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) },
            )
            views.setTextViewText(rows[line * 3 + 2], format(rank.value))
        }
    }

    // The channel hues (ChannelColors), in an order where neighbours stay apart. Others are grey.
    private val PALETTE = intArrayOf(0xFF3D8BF2.toInt(), 0xFF2FBF8F.toInt(), 0xFFF5B83D.toInt(), 0xFFA77BF0.toInt(), 0xFFF2604F.toInt(), 0xFF2EB5C9.toInt())
    private const val OTHERS_GREY = 0xFF8E8E93.toInt()
    private val SPEND_ROWS = intArrayOf(R.id.s1, R.id.s1n, R.id.s1v, R.id.s2, R.id.s2n, R.id.s2v, R.id.s3, R.id.s3n, R.id.s3v, R.id.s4, R.id.s4n, R.id.s4v)
    private val REQUEST_ROWS = intArrayOf(R.id.q1, R.id.q1n, R.id.q1v, R.id.q2, R.id.q2n, R.id.q2v, R.id.q3, R.id.q3n, R.id.q3v, R.id.q4, R.id.q4n, R.id.q4v)
    private val TOKEN_ROWS = intArrayOf(R.id.t1, R.id.t1n, R.id.t1v, R.id.t2, R.id.t2n, R.id.t2v, R.id.t3, R.id.t3n, R.id.t3v, R.id.t4, R.id.t4n, R.id.t4v)

    /** The wide widget's second line: the week and the month, and the model that cost the most. */
    private fun extraLine(s: UsageSummary): String {
        val parts = mutableListOf("Week ${usd(s.week)}", "Month ${usd(s.month)}")
        s.models.firstOrNull()?.let { parts.add("Top ${shortModel(it.model)}") }
        return parts.joinToString(" · ")
    }
}

/** The system's side of the Usage widget: draws from the log, then asks OpenRouter for the credits if they are old. */
class UsageWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        // A broadcast may only run a few seconds and the log is read off the main thread: work in a thread, say when done.
        val pending = goAsync()
        val app = context.applicationContext
        Thread {
            try {
                UsageWidget.update(app, appWidgetManager, appWidgetIds)
                ProviderStore.ensureLoaded(app)
                OpenRouterHub.ensureLoaded(app)
                if (ProviderStore.state.value.kind == ProviderKind.OpenRouter && OpenRouterHub.isStale(30 * 60_000L)) {
                    OpenRouterHub.refresh(app, full = false)
                }
            } catch (e: Exception) {
                Log.w("UsageWidget", "refresh failed", e)
            } finally {
                pending.finish()
            }
        }.start()
    }

    override fun onAppWidgetOptionsChanged(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle) {
        val pending = goAsync()
        Thread {
            try {
                UsageWidget.update(context.applicationContext, appWidgetManager, intArrayOf(appWidgetId))
            } finally {
                pending.finish()
            }
        }.start()
    }
}
