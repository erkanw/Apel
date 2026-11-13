package io.github.erkanw.vault.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle

/**
 * The size a widget has on the home screen right now, in dp. The launcher reports a range: the narrowest width and the
 * tallest height are the portrait cell, the widest width and the shortest height the landscape one. Reading the minimum
 * height in portrait (as both widgets once did) takes a 4x3 widget for a 4x2 one.
 */
internal fun widgetSizeDp(context: Context, options: Bundle): Pair<Int, Int> {
    val landscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val width = options.getInt(if (landscape) AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH else AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
    val height = options.getInt(if (landscape) AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT else AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0)
    return width to height
}
