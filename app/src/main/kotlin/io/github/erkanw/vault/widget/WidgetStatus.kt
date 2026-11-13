package io.github.erkanw.vault.widget

import android.content.Context
import androidx.core.content.edit
import io.github.erkanw.vault.mascot.BlobState

/**
 * What the widget shows, written by the chat and read by the widget. Widgets run
 * outside the app, so this lives in a small preferences file.
 */
object WidgetStatus {
    private const val PREFS = "widget"

    data class Snapshot(val state: BlobState, val snippet: String, val at: Long) {
        /** A request that never reported back (the app was killed) must not leave Blob "thinking" forever. */
        fun effective(now: Long): BlobState {
            val age = now - at
            return when (state) {
                BlobState.Thinking, BlobState.Working -> if (age > BUSY_MAX_MS) BlobState.Idle else state
                BlobState.Finished, BlobState.Error -> if (age > RESULT_MAX_MS) BlobState.Idle else state
                else -> state
            }
        }
    }

    fun write(context: Context, state: BlobState, snippet: String? = null) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString("state", state.name)
            putLong("at", System.currentTimeMillis())
            if (snippet != null) putString("snippet", snippet)
        }
    }

    fun read(context: Context): Snapshot {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val state = BlobState.entries.firstOrNull { it.name == p.getString("state", null) } ?: BlobState.Idle
        return Snapshot(state, p.getString("snippet", "").orEmpty(), p.getLong("at", 0L))
    }

    /** The outcome of the last widget refresh, so Settings can say whether the widget is being updated. */
    fun recordUpdate(context: Context, atMs: Long, note: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putLong("updateAt", atMs)
            putString("updateNote", note)
        }
    }

    /** "14:32 · OK · Thinking", or a message when there has been no widget update yet. */
    fun lastUpdate(context: Context): String {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val at = p.getLong("updateAt", 0L)
        if (at == 0L) return "No widget update yet. Add the widget to your home screen."
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(at))
        return "$time · ${p.getString("updateNote", "").orEmpty()}"
    }

    /** Whether each frame of the loop fades into the next (smoother, the default) or cuts to it. */
    fun isBlended(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("blended", true)

    fun setBlended(context: Context, blended: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean("blended", blended) }
    }

    /** Whether the widget runs Blob's loop (on by default) or shows a still, which costs the launcher nothing. */
    fun isAnimated(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("animated", true)

    fun setAnimated(context: Context, animated: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean("animated", animated) }
    }

    private const val BUSY_MAX_MS = 3 * 60_000L
    private const val RESULT_MAX_MS = 10 * 60_000L
}
