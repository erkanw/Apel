package io.github.erkanw.vault.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.util.Log
import io.github.erkanw.vault.store.AgentProfile
import android.view.View
import android.widget.RemoteViews
import io.github.erkanw.vault.MainActivity
import io.github.erkanw.vault.R
import io.github.erkanw.vault.data.BlobPrefs
import io.github.erkanw.vault.mascot.BlobState
import io.github.erkanw.vault.mascot.SleepSchedule
import io.github.erkanw.vault.ui.blobMoodOf

/**
 * Builds Blob's widget and pushes it to the launcher, plainly: one RemoteViews per widget,
 * made fresh from the current state at every call. (An earlier version used Glance, which
 * keeps a widget "session" alive and can ignore later updates; this can't.)
 *
 * A widget is the small layout (Blob over a label) or the wide one (Blob beside the label
 * and the start of the last answer), by the size it has on the home screen. Blob himself
 * is a flipbook of drawn frames the launcher loops by itself, see [BlobFlipbook], or a still
 * when the loop is off in Settings.
 *
 * Whoever changes what the widget shows (a chat state, a setting) calls [updateAll]. The
 * system calls the provider every 30 minutes, and an alarm calls it when Blob falls asleep
 * or wakes up.
 */
object BlobWidgetUpdater {
    private const val TAG = "BlobWidget"
    private const val WIDE_MIN_DP = 200

    /** Three rows of the home screen (in portrait a 4x2 is about 210 dp tall, a 4x3 about 320): room for the list of sessions. */
    private const val TALL_MIN_DP = 250
    // The app's tones (DESIGN.md): a state shows as a coloured word or dot, everything else stays grey.
    private const val MUTED = 0xFF8E8E93.toInt()
    private const val LIVE = 0xFF30C77B.toInt()
    private const val ATTENTION = 0xFFF5A524.toInt()
    private const val DANGER = 0xFFFF5A4E.toInt()
    private const val VARIANT_MS = 10 * 60_000L
    private const val ALARM_REQUEST = 7

    fun updateAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        update(context, manager, widgetIds(context, manager))
    }

    fun update(context: Context, manager: AppWidgetManager, ids: IntArray) {
        if (ids.isEmpty()) return
        val now = System.currentTimeMillis()
        try {
            val snapshot = WidgetStatus.read(context)
            val shown = SleepSchedule.display(snapshot.effective(now), now, BlobPrefs.sleepEnabled(context))
            // A new idle loop every ten minutes, whenever the widget is next refreshed.
            val variant = ((now / VARIANT_MS) % 1_000_000L).toInt()
            val animated = WidgetStatus.isAnimated(context)
            val blend = WidgetStatus.isBlended(context)
            val px = BlobFlipbook.framePx(context)
            val frames = if (animated) BlobFlipbook.frames(blobMoodOf(shown), px, variant) else null
            val still = if (animated) null else BlobFlipbook.still(blobMoodOf(shown), px)
            val label = labelFor(shown, context)
            val content = contentFor(context, shown, snapshot, label, snapshot.snippet, now)
            for (id in ids) {
                val options = manager.getAppWidgetOptions(id)
                val (width, height) = widgetSizeDp(context, options)
                Log.d(TAG, "widget $id: $width x $height dp (min ${options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)} x ${options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)}, max ${options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH)} x ${options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)})")
                val size = when {
                    width >= WIDE_MIN_DP && height >= TALL_MIN_DP -> Size.Tall
                    width >= WIDE_MIN_DP -> Size.Wide
                    else -> Size.Small
                }
                manager.updateAppWidget(id, build(context, size, content, frames, still, blend))
            }
            WidgetStatus.recordUpdate(context, now, "OK · ${shown.name}${if (!animated) " · still" else if (blend) " · blended" else " · cuts"}")
        } catch (e: Exception) {
            // Say so where Settings can show it: a widget that silently stops updating is worse.
            Log.w(TAG, "widget update failed", e)
            WidgetStatus.recordUpdate(context, now, "FAILED · ${e.javaClass.simpleName}: ${e.message}")
        }
        scheduleNextChange(context, now)
    }

    internal enum class Size { Small, Wide, Tall }

    /** Everything the three layouts can show, worked out once per refresh. */
    internal class Content(
        val label: String,
        val snippet: String,
        /** Who it is (the agent's name) and its state colour, for the header. */
        val source: String,
        val sourceColor: Int,
        val time: String,
        /** The muted line under the label of the small widget. */
        val meta: String,
        /** The tall widget's rows: each row's text and its state dot (a drawable, or 0 for none). Empty for now (Phase 7). */
        val sessions: List<Pair<String, Int>>,
        /** A tool request waiting for your OK: its id (blank: none), and whether it is a question. */
        val request: String,
        val asking: Boolean,
    )

    private fun contentFor(
        context: Context, shown: BlobState, snapshot: WidgetStatus.Snapshot,
        label: String, snippet: String, now: Long,
    ): Content {
        val source = AgentProfile.name(context)
        val at = snapshot.at
        val time = if (at > 0L) android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(at)) else ""
        val meta = time
            .ifBlank { java.text.SimpleDateFormat("EEE d MMM", java.util.Locale.getDefault()).format(java.util.Date(now)) }
        val sourceColor = toneOf(shown)
        return Content(label, snippet, source, sourceColor, time, meta, emptyList(), "", false)
    }

    internal fun build(
        context: Context, size: Size, content: Content,
        frames: List<Bitmap>?, still: Bitmap?, blend: Boolean,
    ): RemoteViews {
        val pkg = context.packageName
        val views = RemoteViews(
            pkg,
            when (size) {
                Size.Small -> R.layout.widget_blob_small
                Size.Wide -> R.layout.widget_blob_wide
                Size.Tall -> R.layout.widget_blob_tall
            },
        )
        views.setTextViewText(R.id.label, content.label)
        if (size == Size.Small) views.setTextViewText(R.id.meta, content.meta)
        if (size != Size.Small) {
            views.setTextViewText(R.id.snippet, content.snippet)
            views.setViewVisibility(R.id.snippet, if (content.snippet.isBlank()) View.GONE else View.VISIBLE)
            views.setTextViewText(R.id.source, content.source)
            views.setTextViewText(R.id.bar, context.getString(R.string.widget_message_named, content.source))
            views.setTextColor(R.id.source, content.sourceColor)
            views.setTextViewText(R.id.time, content.time)
            val buttons = content.request.isNotBlank()
            views.setViewVisibility(R.id.actions, if (buttons) View.VISIBLE else View.GONE)
            // A request waiting for your OK takes the message bar's place (wired in Phase 3).
            views.setViewVisibility(R.id.bar, if (buttons) View.GONE else View.VISIBLE)
        }
        if (size == Size.Tall) {
            val rows = intArrayOf(R.id.s1, R.id.s2, R.id.s3)
            for ((i, row) in rows.withIndex()) {
                val session = content.sessions.getOrNull(i)
                views.setViewVisibility(row, if (session == null) View.GONE else View.VISIBLE)
                if (session != null) {
                    views.setTextViewText(row, session.first)
                    // A drawn dot, not a "●" in the text: the launcher's font draws that one small and low.
                    views.setTextViewCompoundDrawablesRelative(row, session.second, 0, 0, 0)
                }
            }
        }
        views.removeAllViews(R.id.slot)
        if (frames != null) {
            val flipper = RemoteViews(pkg, if (blend) R.layout.widget_blob_flipper else R.layout.widget_blob_flipper_cut)
            BlobFlipbook.apply(context, flipper, frames)
            views.addView(R.id.slot, flipper)
        } else if (still != null) {
            val image = RemoteViews(pkg, R.layout.widget_blob_still)
            image.setImageViewBitmap(R.id.still, still)
            views.addView(R.id.slot, image)
        }
        val open = Intent(context, MainActivity::class.java).apply {
            action = MainActivity.ACTION_ASK
            putExtra(MainActivity.EXTRA_ASK, true)
        }
        val ask = PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        views.setOnClickPendingIntent(R.id.root, ask)
        // The bar looks like a message box: tapping it opens the chat with the keyboard up, as the rest of the widget does.
        if (size != Size.Small) views.setOnClickPendingIntent(R.id.bar, ask)
        return views
    }

    /** The colour of a state's word or dot: green while working, amber when waiting on you, red on an error, grey otherwise. */
    internal fun toneOf(state: BlobState): Int = when (state) {
        BlobState.Working, BlobState.Thinking, BlobState.Searching -> LIVE
        BlobState.Approval, BlobState.Question, BlobState.RateLimit -> ATTENTION
        BlobState.Error, BlobState.Dizzy -> DANGER
        else -> MUTED
    }

    /** A session row's dot, in the same tones as [toneOf]. */
    internal fun dotOf(state: BlobState): Int = when (toneOf(state)) {
        LIVE -> R.drawable.widget_dot_live
        ATTENTION -> R.drawable.widget_dot_attention
        DANGER -> R.drawable.widget_dot_danger
        else -> R.drawable.widget_dot_idle
    }

    internal fun labelFor(state: BlobState, context: Context): String = when (state) {
        BlobState.Thinking -> "Thinking…"
        BlobState.Working -> "Replying…"
        BlobState.Finished -> "Replied"
        BlobState.Error -> "Couldn’t reply"
        BlobState.Sleeping -> "Sleeping"
        else -> "Ask " + AgentProfile.name(context)
    }

    private fun widgetIds(context: Context, manager: AppWidgetManager): IntArray =
        manager.getAppWidgetIds(ComponentName(context, BlobWidgetProvider::class.java))

    // ---- the night ----------------------------------------------------------------------

    private fun alarmIntent(context: Context, ids: IntArray): PendingIntent {
        val intent = Intent(context, BlobWidgetProvider::class.java).apply {
            action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
        }
        return PendingIntent.getBroadcast(
            context, ALARM_REQUEST, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * Wakes the widget when Blob next falls asleep or wakes up. Inexact on purpose: it needs no special permission, and a few minutes of lateness doesn't matter.
     */
    fun scheduleNextChange(context: Context, now: Long = System.currentTimeMillis()) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val ids = widgetIds(context, AppWidgetManager.getInstance(context))
        if (ids.isEmpty() || !BlobPrefs.sleepEnabled(context)) {
            cancelAlarm(context)
            return
        }
        val next = SleepSchedule.nextChange(now)
        try {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC, next + 1_000L, alarmIntent(context, ids))
        } catch (e: SecurityException) {
            Log.w(TAG, "could not schedule the night refresh", e)
        }
    }

    fun cancelAlarm(context: Context) {
        context.getSystemService(AlarmManager::class.java)?.cancel(alarmIntent(context, IntArray(0)))
    }
}
