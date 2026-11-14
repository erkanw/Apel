package io.github.erkanw.vault.widget

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Redraws the widget's flipbook after a look change, once things are quiet: tapping through the swatches asks many
 * times, and only the last look is drawn.
 */
object WidgetRedraw {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    @Synchronized
    fun request(context: Context) {
        val app = context.applicationContext
        job?.cancel()
        job = scope.launch {
            delay(400)
            BlobWidgetUpdater.updateAll(app)
        }
    }
}
