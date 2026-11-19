package io.github.erkanw.vault.store

import android.app.AlarmManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import io.github.erkanw.vault.provider.ProviderKind
import io.github.erkanw.vault.provider.ProviderStore
import io.github.erkanw.vault.tools.ToolLevel
import io.github.erkanw.vault.tools.ToolRegistry
import io.github.erkanw.vault.usage.Budget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * About → "Copy diagnostics" (PLAN §10): a short report for a bug report. Versions, the phone, settings that change how
 * the app behaves, and counts. Never a chat, a name, a file, an address, a key or a token.
 */
object Diagnostics {
    suspend fun report(context: Context): String = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val info = app.packageManager.getPackageInfo(app.packageName, 0)
        val debug = app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        val provider = ProviderStore.state.value
        val db = AppDatabase.get(app).openHelper.readableDatabase
        fun count(sql: String) = runCatching { db.query(sql).use { if (it.moveToFirst()) it.getInt(0) else 0 } }.getOrDefault(-1)
        val levels = (ToolRegistry.tools.map { it.name } + ToolRegistry.WEB_SEARCH).groupingBy { ToolRegistry.level(app, it) }.eachCount()
        val budget = Budget.settings(app)
        val alarms = Build.VERSION.SDK_INT < 31 || app.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
        val battery = app.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(app.packageName)
        val models = ModelStore(app).callChain().map { it.model }
        buildString {
            appendLine("Apel ${info.versionName} (${androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(info)}), ${if (debug) "debug" else "release"} build")
            appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Font scale ${app.resources.configuration.fontScale}, ${app.resources.configuration.densityDpi} dpi")
            appendLine(
                "Provider: " + when (provider.kind) {
                    ProviderKind.OpenRouter -> "OpenRouter"
                    ProviderKind.Custom -> "own server (" + (if (provider.customUrl.startsWith("https://")) "https" else "http") + ", key ${if (provider.customHasKey) "set" else "none"})"
                    else -> "not connected"
                } + ", private providers only ${onOff(provider.privateOnly)}",
            )
            appendLine("Models in order: ${models.joinToString(", ").ifBlank { "none" }}")
            appendLine("Chats ${count("SELECT COUNT(*) FROM sessions")}, messages ${count("SELECT COUNT(*) FROM messages")}, usage rows ${count("SELECT COUNT(*) FROM usage")}")
            appendLine("Automations ${count("SELECT COUNT(*) FROM automations")} (${count("SELECT COUNT(*) FROM automations WHERE enabled = 1")} on), databases ${count("SELECT COUNT(*) FROM user_dbs")}, tool servers ${count("SELECT COUNT(*) FROM mcp_servers")}")
            appendLine("Built-in tools: ${ToolLevel.entries.joinToString(", ") { "${levels[it] ?: 0} ${it.name.lowercase()}" }}")
            appendLine("Budget: " + if (budget.on) "on, ${budget.action.name.lowercase()}" else "off")
            appendLine("Notifications ${onOff(NotificationManagerCompat.from(app).areNotificationsEnabled())}, exact alarms ${onOff(alarms)}, battery optimisation ${if (battery) "off for Apel" else "on"}")
            append("Database version ${Backup.DB_VERSION}")
        }
    }

    private fun onOff(on: Boolean) = if (on) "on" else "off"
}
