@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package io.github.erkanw.vault.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import io.github.erkanw.vault.mascot.BlobState
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import io.github.erkanw.vault.chat.prettyModelName
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

import io.github.erkanw.vault.SettingsViewModel
import io.github.erkanw.vault.R
import androidx.compose.ui.res.stringResource

@Composable
fun SettingsScreen(
    vm: SettingsViewModel,
    onBack: () -> Unit,
    onOpenRouter: () -> Unit = {},
    onModels: () -> Unit = {},
    onProvider: () -> Unit = {},
    providerSummary: String = "",
    onWelcome: () -> Unit = {},
) {
    val k = MaterialTheme.vault
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val agent = LocalAgentName.current
    val appName = stringResource(R.string.app_name)
    val widgetAnimated by vm.widgetAnimated.collectAsStateWithLifecycle()
    val widgetBlended by vm.widgetBlended.collectAsStateWithLifecycle()
    val sleepEnabled by vm.sleepEnabled.collectAsStateWithLifecycle()
    val hapticsEnabled by vm.hapticsEnabled.collectAsStateWithLifecycle()
    val widgetNote by vm.widgetNote.collectAsStateWithLifecycle()
    val openRouter by vm.openRouter.collectAsStateWithLifecycle()
    val newMessageAlertsOn by vm.newMessageAlertsOn.collectAsStateWithLifecycle()
    val themeMode by vm.themeMode.collectAsStateWithLifecycle()
    val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty() }

    Column(Modifier.fillMaxSize().background(k.background).imePadding()) {
        PageHeader(
            title = "Settings",
            subtitle = if (version.isBlank()) appName else "$appName $version",
            onBack = onBack,
        )
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            Section("Provider") {
                ActionRow(
                    title = "Provider",
                    subtitle = providerSummary,
                    onClick = onProvider,
                    chevron = true,
                )
            }

            Section("Appearance") {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ThemeMode.entries.forEach { mode -> VaultChip(mode.label, { vm.setThemeMode(mode) }, selected = themeMode == mode, onLift = true) }
                    }
                    Text(
                        "System follows your phone’s light or dark setting.",
                        style = VaultType.Caption, color = k.muted,
                        modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 4.dp),
                    )
                }
            }

            Section("Notifications") {
                SwitchRow(
                    title = "Tell me when a reply is ready",
                    subtitle = "A notification when $agent finishes a reply while you are elsewhere. Unread chats show a dot in the drawer. What it says is hidden on the lock screen.",
                    checked = newMessageAlertsOn,
                    onChange = vm::setNewMessageAlerts,
                )
            }

            Section("Models and credits") {
                ActionRow(
                    title = "Choose and add models",
                    subtitle = "Which models the chat offers and in what order: add one from OpenRouter, set the main model and the fallbacks, change the limits.",
                    onClick = onModels,
                    chevron = true,
                )
                RowDivider()
                ActionRow(
                    title = "OpenRouter credits",
                    subtitle = openRouter.snapshot?.let { s ->
                        (s.balance?.let { "${io.github.erkanw.vault.openrouter.usd(it)} left · " } ?: "") +
                            "${io.github.erkanw.vault.openrouter.usd(s.today)} today"
                    } ?: if (openRouter.hasKey) "What is left on your account and what each model cost." else "Needs your OpenRouter key.",
                    onClick = onOpenRouter,
                    chevron = true,
                )
            }

            Section("Home-screen widget") {
                SwitchRow(
                    title = "Animate the widget",
                    subtitle = "$agent loops on your home screen: looking around, blinking, hopping when a reply is done. Off is a still, which costs no battery.",
                    checked = widgetAnimated,
                    onChange = vm::setWidgetAnimated,
                )
                RowDivider()
                SwitchRow(
                    title = "Blend the frames",
                    subtitle = "Each frame fades into the next, which looks smoother. Off gives crisp cuts.",
                    checked = widgetBlended,
                    onChange = vm::setWidgetBlended,
                    enabled = widgetAnimated,
                )
                RowDivider()
                SwitchRow(
                    title = "Sleep at night",
                    subtitle = "From 9 PM to 7 AM an idle $agent sleeps on the widget. Anything it is doing wins over sleep.",
                    checked = sleepEnabled,
                    onChange = vm::setSleepEnabled,
                )
                RowDivider()
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Widget status", style = VaultType.Body.copy(fontWeight = VaultType.Row.fontWeight), color = k.text)
                        Text(widgetNote, style = VaultType.Label.copy(fontWeight = null), color = k.muted)
                    }
                    QuietButton("Refresh", vm::refreshWidget)
                }
            }

            Section("Feedback") {
                SwitchRow(
                    title = "Haptic feedback",
                    subtitle = "One short pulse when you send or copy, two when $agent has answered, one long strong one on an error.",
                    checked = hapticsEnabled,
                    onChange = vm::setHapticsEnabled,
                )
                RowDivider()
                ActionRow(
                    title = "Try the vibrations",
                    subtitle = "Plays them in turn: single, double, heavy. Feel nothing? Check Settings > Sounds and vibration > Vibration intensity on your phone.",
                    enabled = hapticsEnabled,
                ) {
                    scope.launch {
                        Haptics.tap(view)
                        delay(800)
                        Haptics.confirm(view)
                        delay(800)
                        Haptics.reject(view)
                    }
                }
            }

            Section("About") {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(appName, style = VaultType.Body.copy(fontWeight = VaultType.Row.fontWeight), color = k.text)
                    Text("Your own AI agent, on this phone.", style = VaultType.Label.copy(fontWeight = null), color = k.muted)
                    Text(
                        "Mascot made with Blob Atelier by Victoria Wu (MIT). The typeface is Inter (SIL Open Font " +
                            "Licence). Icons follow Lucide (ISC licence).",
                        style = VaultType.Caption, color = k.muted,
                    )
                }
                RowDivider()
                ActionRow(title = "Show the welcome again", subtitle = "The first-run steps: provider, first model, name.", onClick = onWelcome)
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun Section(
    title: String,
    modifier: Modifier = Modifier,
    trailing: String? = null,
    on: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier) {
        GroupLabel(title, trailing = if (trailing != null) ({ GroupState(trailing, on) }) else null)
        VaultGroup(content = content)
    }
}

@Composable
private fun RowDivider() = GroupDivider()

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) =
    GroupSwitchRow(title, subtitle, checked, onChange, enabled)

@Composable
private fun ActionRow(
    title: String,
    subtitle: String,
    enabled: Boolean = true,
    chevron: Boolean = false,
    color: androidx.compose.ui.graphics.Color? = null,
    onClick: () -> Unit,
) = GroupActionRow(title, subtitle, onClick, enabled = enabled, chevron = chevron, color = color)
