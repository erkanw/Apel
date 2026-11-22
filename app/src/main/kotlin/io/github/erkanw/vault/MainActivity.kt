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
    private val provider: ProviderViewModel by viewModels()

    /** Bumped each time the widget opens the app, so the chat puts the keyboard up. */
    private var focusTick by mutableIntStateOf(0)
    private var usageTick by mutableIntStateOf(0)
    private var openChatTick by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The look chosen in Settings, told to Android so the next launch screen already wears it (a no-op once it has).
        applyNightMode(this, ThemeMode.fromId(BlobPrefs.themeId(this)))
        enableEdgeToEdge()
        // Chats, memory and files don't show in the recent-apps switcher (Android 13+). Your own screenshots still work.
        if (android.os.Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false)
        Haptics.attach(this)
        // Once per intent: not again on rotation, but also when Android restores the screen for a notification tap after
        // the app was closed (the saved state is there then, and the tap's intent is new).
        if (!intent.getBooleanExtra(EXTRA_HANDLED, false)) {
            handle(intent)
            intent.putExtra(EXTRA_HANDLED, true)
        }
        setContent {
            val mode by settings.themeMode.collectAsStateWithLifecycle()
            val dark = mode.isDark(isSystemInDarkTheme())
            // The bars' icons follow the look the app wears, which may not be the phone's.
            SideEffect {
                val bars = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { dark }
                enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
            }
            VaultTheme(mode) { VaultApp(chat, pages, settings, provider, focusTick, usageTick, openChatTick) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    // Back in front: finish a sign-in whose code arrived while the browser was in front.
    override fun onResume() {
        super.onResume()
        provider.onForeground()
        // Stopped from Android’s active apps, or ended: running in the background comes back when the app opens.
        io.github.erkanw.vault.automation.BackgroundMode.ensure(this)
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

    private fun handle(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_ASK, false) == true) focusTick++
        // Back from the OpenRouter sign-in: the "Back to the app" link only brings the app to the front (the code
        // already came in through the loopback address); onResume finishes the sign-in.
        when (intent?.action) {
            // The Usage widget or the budget notification was tapped.
            ACTION_USAGE -> usageTick++
            // A "Replies" notification was tapped: open that chat.
            ChatAlerts.ACTION_OPEN -> {
                chat.openChatById(intent.getStringExtra(ChatAlerts.EXTRA_CHAT))
                openChatTick++
            }
        }
    }

    companion object {
        const val ACTION_ASK = "io.github.erkanw.vault.ASK"
        const val ACTION_USAGE = "io.github.erkanw.vault.USAGE"
        const val EXTRA_ASK = "ask"
        private const val EXTRA_HANDLED = "io.github.erkanw.vault.HANDLED"
    }
}
