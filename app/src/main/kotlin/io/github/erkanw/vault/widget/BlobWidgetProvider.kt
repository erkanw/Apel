package io.github.erkanw.vault.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.os.Bundle

/** The home-screen widget. All the work is in [BlobWidgetUpdater]. */
class BlobWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        // The frames are drawn and the chats read off the main thread; the broadcast stays alive until then.
        val pending = goAsync()
        Thread {
            try {
                BlobWidgetUpdater.update(context.applicationContext, appWidgetManager, appWidgetIds)
            } finally {
                pending.finish()
            }
        }.start()
    }

    /** The widget was resized: it may now be the small layout or the wide one. */
    override fun onAppWidgetOptionsChanged(
        context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle,
    ) {
        val pending = goAsync()
        Thread {
            try {
                BlobWidgetUpdater.update(context.applicationContext, appWidgetManager, intArrayOf(appWidgetId))
            } finally {
                pending.finish()
            }
        }.start()
    }

    override fun onDisabled(context: Context) {
        BlobWidgetUpdater.cancelAlarm(context)
    }
}
