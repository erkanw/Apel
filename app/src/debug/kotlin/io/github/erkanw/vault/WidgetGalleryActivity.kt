package io.github.erkanw.vault

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Log
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RemoteViews
import android.widget.ScrollView
import io.github.erkanw.vault.mascot.BlobState
import io.github.erkanw.vault.ui.blobMoodOf
import io.github.erkanw.vault.widget.BlobFlipbook
import io.github.erkanw.vault.widget.BlobWidgetUpdater
import io.github.erkanw.vault.widget.BlobWidgetUpdater.Content
import io.github.erkanw.vault.widget.BlobWidgetUpdater.Size

/**
 * Debug only: the home-screen widget in its sizes and states, inflated here from the same RemoteViews the launcher gets,
 * on a wallpaper-like ground (`--es set a|b`, `--es wall dark|light`).
 */
class WidgetGalleryActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // `--ez notify true`: a sample notification, to see the status bar icon.
        if (intent.getBooleanExtra("notify", false)) {
            val manager = getSystemService(android.app.NotificationManager::class.java)
            manager.createNotificationChannel(android.app.NotificationChannel("debug", "Debug samples", android.app.NotificationManager.IMPORTANCE_HIGH))
            manager.notify(
                99,
                android.app.Notification.Builder(this, "debug")
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle("Daily brief")
                    .setContentText("Sunny, 14°. Two things on your list today.")
                    .build(),
            )
            finish()
            return
        }
        val set = intent.getStringExtra("set") ?: "a"
        val light = intent.getStringExtra("wall") == "light"
        val dp = resources.displayMetrics.density
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * dp).toInt(), (56 * dp).toInt(), (16 * dp).toInt(), (32 * dp).toInt())
        }
        val wall = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            if (light) intArrayOf(0xFFE9D8C4.toInt(), 0xFFB9C9D6.toInt()) else intArrayOf(0xFF2B3A55.toInt(), 0xFF0E1420.toInt(), 0xFF3A2A3F.toInt()),
        )
        setContentView(ScrollView(this).apply { background = wall; addView(column) })

        val px = BlobFlipbook.framePx(this)
        fun add(size: Size, state: BlobState, content: Content, widthDp: Int, heightDp: Int) {
            val start = System.nanoTime()
            val frames = BlobFlipbook.frames(blobMoodOf(state), px, variant = 3)
            Log.i("WidgetGallery", "$state: ${LOOP} frames of $px px in ${(System.nanoTime() - start) / 1_000_000} ms")
            val views = BlobWidgetUpdater.build(this, size, content, frames, null, blend = true)
            val slot = FrameLayout(this)
            slot.addView(views.apply(this, slot))
            column.addView(slot, LinearLayout.LayoutParams((widthDp * dp).toInt(), (heightDp * dp).toInt()).apply { bottomMargin = (20 * dp).toInt() })
        }
        fun content(state: BlobState, label: String, snippet: String, source: String, request: String = "", asking: Boolean = false, sessions: List<Pair<String, Int>> = emptyList()) =
            Content(label, snippet, source, BlobWidgetUpdater.toneOf(state), "21:42", "$source · 21:42", sessions, request, asking)

        val agent = io.github.erkanw.vault.store.AgentProfile.name(this)
        if (set == "a") {
            add(Size.Small, BlobState.Idle, Content("Ask $agent", "", agent, BlobWidgetUpdater.toneOf(BlobState.Idle), "", "Wed 11 Nov", emptyList(), "", false), 168, 176)
            add(Size.Wide, BlobState.Finished, content(BlobState.Finished, "Replied", "The 07:42 is the quickest: 38 minutes, direct. The next one leaves at 08:12.", agent), 344, 176)
            add(Size.Tall, BlobState.Working, content(BlobState.Working, "Working", "Reading three pages about train times to the airport.", agent), 344, 264)
        } else {
            add(Size.Wide, BlobState.Approval, content(BlobState.Approval, "Wants your OK", "Add 3 rows to the expenses table", agent, request = "r1"), 344, 176)
            add(Size.Small, BlobState.Sleeping, Content("Sleeping", "", agent, BlobWidgetUpdater.toneOf(BlobState.Sleeping), "", "Wed 11 Nov", emptyList(), "", false), 168, 176)
            add(Size.Small, BlobState.Error, Content("Couldn’t reply", "", agent, BlobWidgetUpdater.toneOf(BlobState.Error), "", "$agent · 21:40", emptyList(), "", false), 168, 176)
        }
    }

    private companion object {
        const val LOOP = BlobFlipbook.LOOP_FRAMES
    }
}
