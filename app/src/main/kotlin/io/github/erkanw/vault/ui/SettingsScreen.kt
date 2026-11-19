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
    onUsage: () -> Unit = {},
    onModels: () -> Unit = {},
    onLook: () -> Unit = {},
    onProvider: () -> Unit = {},
    providerSummary: String = "",
    onAbout: () -> Unit = {},
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
    val budget by vm.budget.collectAsStateWithLifecycle()
    val newMessageAlertsOn by vm.newMessageAlertsOn.collectAsStateWithLifecycle()
    val themeMode by vm.themeMode.collectAsStateWithLifecycle()
    val backup by vm.backup.collectAsStateWithLifecycle()
    val pickBackup = rememberBackupPicker(vm)
    LaunchedEffect(Unit) { vm.forgetBackupNotes() }
    val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty() }

    Box(Modifier.fillMaxSize()) {
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

            Section("Agent") {
                ActionRow(
                    title = agent,
                    subtitle = "Name, look, instructions, about you and memory",
                    onClick = onLook,
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

            Section("Models and usage") {
                ActionRow(
                    title = "Choose and add models",
                    subtitle = "Which models the chat offers and in what order: add one from OpenRouter, set the main model and the fallbacks, change the limits.",
                    onClick = onModels,
                    chevron = true,
                )
                RowDivider()
                ActionRow(
                    title = "Usage and budget",
                    subtitle = budget?.let { b ->
                        val spent = io.github.erkanw.vault.openrouter.usd(b.spent)
                        if (b.settings.on) "$spent of ${io.github.erkanw.vault.openrouter.usd(b.settings.limit)} this month" + (if (b.over) ", used up" else "") else "$spent this month · no budget set"
                    } ?: "What each answer cost, and a monthly budget.",
                    onClick = onUsage,
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
            }

            Section(stringResource(R.string.backup_section)) {
                val busy = backup.working != null
                val makeError = backup.error?.takeIf { !backup.restoreError }
                val restoreError = backup.error?.takeIf { backup.restoreError }
                ActionRow(
                    title = stringResource(R.string.backup_make),
                    subtitle = if (backup.working == io.github.erkanw.vault.BackupWork.Making) stringResource(R.string.backup_making)
                        else makeError?.let { backupProblemText(it, appName) } ?: stringResource(R.string.backup_make_sub),
                    subtitleColor = if (makeError != null) k.attention else null,
                    status = backup.working == io.github.erkanw.vault.BackupWork.Making || makeError != null,
                    enabled = !busy,
                    onClick = vm::makeBackup,
                )
                RowDivider()
                ActionRow(
                    title = stringResource(R.string.backup_restore),
                    subtitle = when (backup.working) {
                        io.github.erkanw.vault.BackupWork.Checking -> stringResource(R.string.backup_checking)
                        io.github.erkanw.vault.BackupWork.Restoring -> stringResource(R.string.backup_restoring)
                        else -> restoreError?.let { backupProblemText(it, appName) } ?: stringResource(R.string.backup_restore_sub, appName)
                    },
                    subtitleColor = if (restoreError != null) k.attention else null,
                    status = backup.working == io.github.erkanw.vault.BackupWork.Checking || backup.working == io.github.erkanw.vault.BackupWork.Restoring || restoreError != null,
                    enabled = !busy,
                    onClick = pickBackup,
                )
            }

            Section(stringResource(R.string.about_title)) {
                ActionRow(
                    title = stringResource(R.string.about_row, appName),
                    subtitle = stringResource(R.string.about_row_sub, version),
                    onClick = onAbout,
                    chevron = true,
                )
            }
            Spacer(Modifier.height(12.dp))
        }
    }

    BackupConfirmDialog(vm)

    backup.made?.let { made ->
        val text = stringResource(R.string.backup_made, made.file.name, fileSize(context, made.bytes))
        val large = androidx.compose.ui.platform.LocalDensity.current.fontScale >= 1.4f
        LaunchedEffect(made) { delay(if (large) 8000 else 5000); vm.clearBackupNote() }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) { NotePill(text) }
    }
    }
}

/** "30 kB", with a space that never breaks the number from its unit. */
internal fun fileSize(context: android.content.Context, bytes: Long): String =
    android.text.format.Formatter.formatShortFileSize(context, bytes).replace(' ', '\u00A0')

/** Opens the phone's file picker for a backup zip; the chosen one is read and checked, then [BackupConfirmDialog] asks. */
@Composable
internal fun rememberBackupPicker(vm: SettingsViewModel): () -> Unit {
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.checkBackup(uri)
    }
    return { picker.launch(arrayOf("application/zip", "application/octet-stream", "application/x-zip-compressed")) }
}

/** "Restore this backup?" with what the backup holds, once one has been read and checked. */
@Composable
internal fun BackupConfirmDialog(vm: SettingsViewModel) {
    val backup by vm.backup.collectAsStateWithLifecycle()
    val checked = backup.confirm ?: return
    val context = LocalContext.current
    val appName = stringResource(R.string.app_name)
    val m = checked.manifest
    val res = context.resources
    val made = remember(m.created) {
        java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(m.created))
    }
    VaultDialog(
        title = stringResource(R.string.backup_confirm_title),
        text = stringResource(
            R.string.backup_confirm_text, made, appName, m.versionName,
            res.getQuantityString(R.plurals.backup_chats, m.chats, m.chats),
            res.getQuantityString(R.plurals.backup_files, m.files, m.files),
            fileSize(context, checked.bytes),
        ),
        confirm = stringResource(R.string.backup_confirm),
        onConfirm = vm::restoreBackup,
        onDismiss = vm::cancelRestore,
        destructive = true,
    )
}

@Composable
internal fun backupProblemText(problem: io.github.erkanw.vault.store.BackupProblem, appName: String): String = when (problem) {
    io.github.erkanw.vault.store.BackupProblem.NotABackup -> stringResource(R.string.backup_error_not_backup, appName)
    io.github.erkanw.vault.store.BackupProblem.OtherApp -> stringResource(R.string.backup_error_other_app)
    io.github.erkanw.vault.store.BackupProblem.Newer -> stringResource(R.string.backup_error_newer, appName)
    io.github.erkanw.vault.store.BackupProblem.Damaged -> stringResource(R.string.backup_error_damaged)
    io.github.erkanw.vault.store.BackupProblem.TooBig -> stringResource(R.string.backup_error_too_big)
    io.github.erkanw.vault.store.BackupProblem.NoSpace -> stringResource(R.string.backup_error_no_space)
    io.github.erkanw.vault.store.BackupProblem.Unreadable -> stringResource(R.string.backup_error_unreadable)
    io.github.erkanw.vault.store.BackupProblem.Failed -> stringResource(R.string.backup_error_failed)
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
    subtitleColor: androidx.compose.ui.graphics.Color? = null,
    status: Boolean = false,
    onClick: () -> Unit,
) = GroupActionRow(title, subtitle, onClick, enabled = enabled, chevron = chevron, color = color, subtitleColor = subtitleColor, status = status)
