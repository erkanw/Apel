package io.github.erkanw.vault.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.os.Bundle

/** The home-screen widget. All the work is in [BlobWidgetUpdater]. */
class BlobWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        BlobWidgetUpdater.update(context, appWidgetManager, appWidgetIds)
    }

    /** The widget was resized: it may now be the small layout or the wide one. */
    override fun onAppWidgetOptionsChanged(
        context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle,
    ) {
        BlobWidgetUpdater.update(context, appWidgetManager, intArrayOf(appWidgetId))
    }

    override fun onDisabled(context: Context) {
        BlobWidgetUpdater.cancelAlarm(context)
    }
}
