package io.github.erkanw.vault

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.erkanw.vault.chat.ChatAlerts
import io.github.erkanw.vault.data.BlobPrefs
import io.github.erkanw.vault.ui.Haptics
import io.github.erkanw.vault.ui.ThemeMode
import io.github.erkanw.vault.ui.VaultApp
import io.github.erkanw.vault.ui.VaultTheme
import io.github.erkanw.vault.ui.applyNightMode
import io.github.erkanw.vault.ui.isDark

class MainActivity : ComponentActivity() {
    private val chat: ChatViewModel by viewModels()
    private val pages: PagesViewModel by viewModels()
    private val settings: SettingsViewModel by viewModels()

    /** Bumped each time the widget opens the app, so the chat puts the keyboard up. */
    private var focusTick by mutableIntStateOf(0)
    private var openRouterTick by mutableIntStateOf(0)
    private var openChatTick by mutableIntStateOf(0)

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* the service works either way */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The look chosen in Settings, told to Android so the next launch screen already wears it (a no-op once it has).
        applyNightMode(this, ThemeMode.fromId(BlobPrefs.themeId(this)))
        enableEdgeToEdge()
        askForNotificationsOnce()
        Haptics.attach(this)
        if (savedInstanceState == null) handle(intent)
        setContent {
            val mode by settings.themeMode.collectAsStateWithLifecycle()
            val dark = mode.isDark(isSystemInDarkTheme())
            // The bars' icons follow the look the app wears, which may not be the phone's.
            SideEffect {
                val bars = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { dark }
                enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
            }
            VaultTheme(mode) { VaultApp(chat, pages, settings, focusTick, openRouterTick, openChatTick) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    // While the app is on screen a reply is read as it arrives: no notification then.
    override fun onStart() {
        super.onStart()
        ChatAlerts.appVisible = true
    }

    override fun onStop() {
        ChatAlerts.appVisible = false
        super.onStop()
    }

    /** Back from another app or the lock screen: pick up what happened meanwhile. */
    override fun onRestart() {
        super.onRestart()
        chat.onVisible()
    }

    /**
     * Android 13+ asks before an app may show notifications. Asked once for now; PLAN §6.13 moves the ask to after the
     * first reply (Phase 1).
     */
    private fun askForNotificationsOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (BlobPrefs.notificationsAsked(this)) return
        BlobPrefs.setNotificationsAsked(this)
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun handle(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_ASK, false) == true) focusTick++
        when (intent?.action) {
            // The usage widget was tapped.
            ACTION_OPENROUTER -> openRouterTick++
            // A "Replies" notification was tapped: open that chat.
            ChatAlerts.ACTION_OPEN -> {
                chat.openChatById(intent.getStringExtra(ChatAlerts.EXTRA_CHAT))
                openChatTick++
            }
        }
    }

    companion object {
        const val ACTION_ASK = "io.github.erkanw.vault.ASK"
        const val ACTION_OPENROUTER = "io.github.erkanw.vault.OPENROUTER"
        const val EXTRA_ASK = "ask"
    }
}
