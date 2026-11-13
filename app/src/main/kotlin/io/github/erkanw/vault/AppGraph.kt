package io.github.erkanw.vault

import android.content.Context
import io.github.erkanw.vault.agent.AgentBackend
import io.github.erkanw.vault.agent.OfflineBackend
import io.github.erkanw.vault.ui.DemoMode

/** Builds the app's long-lived parts once (PLAN §4: no DI framework). */
object AppGraph {
    @Volatile
    private var backend: AgentBackend? = null

    /**
     * What the screens talk to: the demo backend while demo mode is on (debug builds only), else the on-device one
     * (offline until Phase 1 connects a provider). Chosen once per process: turning demo mode on or off needs a restart
     * of the app (`am start -S`).
     */
    fun backend(context: Context): AgentBackend = backend ?: synchronized(this) {
        backend ?: (DemoMode.backend(context.applicationContext)
            ?: OfflineBackend(context.getString(R.string.not_connected))).also { backend = it }
    }
}
