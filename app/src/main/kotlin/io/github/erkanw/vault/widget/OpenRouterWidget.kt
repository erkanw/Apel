package io.github.erkanw.vault.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.RemoteViews
import io.github.erkanw.vault.MainActivity
import io.github.erkanw.vault.R
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import io.github.erkanw.vault.openrouter.OpenRouterHub
import io.github.erkanw.vault.openrouter.OrRank
import io.github.erkanw.vault.openrouter.OrSnapshot
import io.github.erkanw.vault.openrouter.OrState
import io.github.erkanw.vault.openrouter.OrWeek
import io.github.erkanw.vault.openrouter.RANK_OTHERS
import io.github.erkanw.vault.openrouter.compactCount
import io.github.erkanw.vault.openrouter.modelLabel
import io.github.erkanw.vault.openrouter.shortDay
import io.github.erkanw.vault.openrouter.shortModel
import io.github.erkanw.vault.openrouter.statsOf
import io.github.erkanw.vault.openrouter.usd
import io.github.erkanw.vault.openrouter.weekOf
import java.util.Date

/**
 * The OpenRouter widget: what is left to spend and what was spent today (and, wider, this week and month and the most
 * used model), from the last numbers the app fetched. The system asks for a refresh about every 30 minutes (and the
 * page refreshes it whenever it is open); tapping it opens the OpenRouter page.
 */
object OpenRouterWidget {
    private const val TAG = "OpenRouterWidget"
    private const val WIDE_MIN_DP = 200

    /** The large widget: about four columns by three rows of the home screen. */
    private const val LARGE_MIN_WIDTH_DP = 250
    private const val LARGE_MIN_HEIGHT_DP = 250
    private const val LOW = 0xFFF5A524.toInt() // attention: running low is a warning, not an error
    private const val WHITE = 0xFFF5F5F7.toInt()

    /** Below this many dollars left, the number turns red. */
    private const val LOW_BALANCE = 2.0

    fun updateAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, OpenRouterWidgetProvider::class.java))
        update(context, manager, ids)
    }

    fun update(context: Context, manager: AppWidgetManager, ids: IntArray) {
        if (ids.isEmpty()) return
        try {
            OpenRouterHub.ensureLoaded(context)
            val state = OpenRouterHub.state.value
            val week = state.snapshot?.let { weekOf(it.rows) }
            for (id in ids) {
                val options = manager.getAppWidgetOptions(id)
                val (width, height) = widgetSizeDp(context, options)
                // The big one needs the room, and the week's numbers (a Management key, with some days to show).
                val views = if (week != null && state.snapshot != null && width >= LARGE_MIN_WIDTH_DP && height >= LARGE_MIN_HEIGHT_DP) {
                    buildLarge(context, state.snapshot, week)
                } else {
                    build(context, width >= WIDE_MIN_DP, state)
                }
                manager.updateAppWidget(id, views)
            }
        } catch (e: Exception) {
            Log.w(TAG, "widget update failed", e)
        }
    }

    private fun build(context: Context, wide: Boolean, state: OrState): RemoteViews {
        val views = RemoteViews(context.packageName, if (wide) R.layout.widget_or_wide else R.layout.widget_or_small)
        val snapshot = state.snapshot
        var mainColor = WHITE
        // An empty line would still take its height (and its margin) and push the rest off centre, so it is hidden instead.
        fun line(id: Int, text: String) {
            views.setTextViewText(id, text)
            views.setViewVisibility(id, if (text.isBlank()) android.view.View.GONE else android.view.View.VISIBLE)
        }
        when {
            !state.hasKey -> {
                views.setTextViewText(R.id.or_main, "No key yet")
                views.setTextViewText(R.id.or_label, "Add it in Settings")
                line(R.id.or_sub, "")
                line(R.id.or_extra, "")
            }
            snapshot == null -> {
                views.setTextViewText(R.id.or_main, "…")
                views.setTextViewText(R.id.or_label, if (state.error != null) "Couldn’t load" else "Loading")
                line(R.id.or_sub, "")
                line(R.id.or_extra, state.error.orEmpty())
            }
            else -> {
                val balance = snapshot.balance
                views.setTextViewText(R.id.or_main, usd(balance ?: snapshot.thisMonth))
                views.setTextViewText(R.id.or_label, if (balance != null) "left to spend" else "spent this month")
                if (balance != null && balance < LOW_BALANCE) mainColor = LOW
                line(R.id.or_sub, "Today ${usd(snapshot.today)}")
                line(R.id.or_extra, extraLine(snapshot, state))
            }
        }
        views.setTextColor(R.id.or_main, mainColor)
        val time = snapshot?.let { android.text.format.DateFormat.getTimeFormat(context).format(Date(it.at)) }.orEmpty()
        views.setTextViewText(R.id.or_time, time)
        views.setViewVisibility(R.id.or_time, if (time.isBlank()) android.view.View.GONE else android.view.View.VISIBLE)
        val open = Intent(context, MainActivity::class.java).apply { action = MainActivity.ACTION_OPENROUTER }
        views.setOnClickPendingIntent(
            R.id.root,
            PendingIntent.getActivity(context, 20, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE),
        )
        return views
    }

    /**
     * The large widget, after the dashboard's "This Week's Usage": spend, requests and tokens, each with its biggest models
     * (a dot in the model's colour, its name, its share of that measure) and "Others" for the rest.
     */
    private fun buildLarge(context: Context, snapshot: OrSnapshot, week: OrWeek): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_or_large)
        views.setTextViewText(R.id.w_period, "${week.label} · to ${shortDay(week.through)}")
        views.setTextViewText(R.id.w_spend, usd(week.spend))
        views.setTextViewText(R.id.w_req, compactCount(week.requests))
        views.setTextViewText(R.id.w_tok, compactCount(week.tokens))
        fillRows(views, week.bySpend, SPEND_ROWS) { usd(it) }
        fillRows(views, week.byRequests, REQUEST_ROWS) { compactCount(it.toLong()) }
        fillRows(views, week.byTokens, TOKEN_ROWS) { compactCount(it.toLong()) }
        val time = android.text.format.DateFormat.getTimeFormat(context).format(Date(snapshot.at))
        views.setTextViewText(
            R.id.w_foot,
            listOfNotNull(
                snapshot.balance?.let { "Balance ${usd(it)}" },
                "Today ${usd(snapshot.today)}",
                "updated $time",
            ).joinToString(" · "),
        )
        val open = Intent(context, MainActivity::class.java).apply { action = MainActivity.ACTION_OPENROUTER }
        views.setOnClickPendingIntent(
            R.id.root,
            PendingIntent.getActivity(context, 20, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE),
        )
        return views
    }

    /** One column's ranking: up to four rows (container, name, value), the rest hidden. */
    private fun fillRows(views: RemoteViews, ranking: List<OrRank>, rows: IntArray, format: (Double) -> String) {
        // rows holds, for each of the four lines, the container's id, then the name's and the value's.
        for (line in 0 until 4) {
            val container = rows[line * 3]
            val rank = ranking.getOrNull(line)
            views.setViewVisibility(container, if (rank == null) android.view.View.GONE else android.view.View.VISIBLE)
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

    /** The colours a model can take, as in the dashboard: the biggest is orange. Others are grey. */
    // The channel hues (ChannelColors), in an order where neighbours stay apart.
    private val PALETTE = intArrayOf(0xFF3D8BF2.toInt(), 0xFF2FBF8F.toInt(), 0xFFF5B83D.toInt(), 0xFFA77BF0.toInt(), 0xFFF2604F.toInt(), 0xFF2EB5C9.toInt())
    private const val OTHERS_GREY = 0xFF8E8E93.toInt()
    private val SPEND_ROWS = intArrayOf(R.id.s1, R.id.s1n, R.id.s1v, R.id.s2, R.id.s2n, R.id.s2v, R.id.s3, R.id.s3n, R.id.s3v, R.id.s4, R.id.s4n, R.id.s4v)
    private val REQUEST_ROWS = intArrayOf(R.id.q1, R.id.q1n, R.id.q1v, R.id.q2, R.id.q2n, R.id.q2v, R.id.q3, R.id.q3n, R.id.q3v, R.id.q4, R.id.q4n, R.id.q4v)
    private val TOKEN_ROWS = intArrayOf(R.id.t1, R.id.t1n, R.id.t1v, R.id.t2, R.id.t2n, R.id.t2v, R.id.t3, R.id.t3n, R.id.t3v, R.id.t4, R.id.t4n, R.id.t4v)

    /** The wide widget's second line: the week and the month, and the model that cost the most. */
    private fun extraLine(snapshot: OrSnapshot, state: OrState): String {
        val parts = mutableListOf("Week ${usd(snapshot.thisWeek)}", "Month ${usd(snapshot.thisMonth)}")
        statsOf(snapshot.rows).models.firstOrNull()?.let { parts.add("Top ${shortModel(it.model)}") }
        val line = parts.joinToString(" · ")
        return if (state.error != null) "$line · offline" else line
    }
}

/** The system's side of the OpenRouter widget: draws what is kept at once, then asks OpenRouter in the background. */
class OpenRouterWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        OpenRouterWidget.update(context, appWidgetManager, appWidgetIds)
        OpenRouterHub.ensureLoaded(context)
        if (!OpenRouterHub.state.value.hasKey) return
        // A broadcast may only run a few seconds, and the network is off the main thread: ask in a thread, and say
        // when it is done. Only the numbers that change (no per-model detail), to stay inside the time.
        val pending = goAsync()
        Thread {
            try {
                OpenRouterHub.refresh(context, full = false)
            } catch (e: Exception) {
                Log.w("OpenRouterWidget", "refresh failed", e)
            } finally {
                pending.finish()
            }
        }.start()
    }

    override fun onAppWidgetOptionsChanged(
        context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle,
    ) {
        OpenRouterWidget.update(context, appWidgetManager, intArrayOf(appWidgetId))
    }
}
