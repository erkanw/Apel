package io.github.erkanw.vault.ui

import android.content.Context
import io.github.erkanw.vault.agent.AgentBackend

/** The release build has no demo data: this is the empty twin of the debug build's DemoMode. */
object DemoMode {
    fun active(context: Context): Boolean = false
    fun backend(context: Context): AgentBackend? = null
    fun busy(context: Context): Boolean = false
    fun unreachable(context: Context): Boolean = false
    fun channelRunning(id: String): Boolean = false
    fun channelUnread(id: String): Boolean = false
    fun openRouter(now: Long): io.github.erkanw.vault.openrouter.OrSnapshot? = null
    fun usage(now: Long): List<io.github.erkanw.vault.usage.UsageCall>? = null
    val automationNames: Map<String, String> = emptyMap()
}
