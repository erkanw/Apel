package io.github.erkanw.vault.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.erkanw.vault.CustomServer
import io.github.erkanw.vault.ModelOption
import io.github.erkanw.vault.ProviderViewModel
import io.github.erkanw.vault.SignIn
import io.github.erkanw.vault.openrouter.usd
import io.github.erkanw.vault.provider.OpenRouterAuth
import io.github.erkanw.vault.provider.ProviderKind

/** The privacy line of onboarding and the provider page (handbook §6). */
@Composable
internal fun privacyLine(): String =
    "Your chats, files and databases stay on this phone. What you send to ${LocalAgentName.current} goes to the AI provider you chose."

/** Opens [url] in a Custom Tab, or any browser when the phone has none that supports them. */
internal fun openInBrowser(context: Context, url: String) {
    val uri = Uri.parse(url)
    try {
        CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, uri)
    } catch (e: ActivityNotFoundException) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e2: ActivityNotFoundException) {
            // Nothing opens web pages: the sign-in panel offers the code instead.
        }
    }
}

/**
 * Settings → Provider (handbook §3): connect OpenRouter, or your own OpenAI-compatible server; once connected, the
 * credits, the private-providers switch and Sign out.
 */
@Composable
fun ProviderScreen(vm: ProviderViewModel, onBack: () -> Unit, onModels: () -> Unit, onUsage: () -> Unit) {
    val k = MaterialTheme.vault
    val state by vm.state.collectAsStateWithLifecycle()
    val openRouter by vm.openRouter.collectAsStateWithLifecycle()
    var confirmSignOut by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.kind) {
        if (state.kind == ProviderKind.Custom) vm.loadCustomModels()
        if (state.kind == ProviderKind.OpenRouter) vm.refreshUsage()
    }

    Column(Modifier.fillMaxSize().background(k.background).imePadding()) {
        PageHeader(
            "Provider",
            when (state.kind) {
                ProviderKind.OpenRouter -> "OpenRouter"
                ProviderKind.Custom -> "Your own server"
                ProviderKind.None -> "Not connected"
            },
            onBack,
        )
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            when (state.kind) {
                ProviderKind.OpenRouter -> Column {
                    GroupLabel("OpenRouter")
                    VaultGroup {
                        val s = openRouter.snapshot
                        GroupActionRow(
                            "Connected",
                            s?.let { snap ->
                                listOfNotNull(
                                    snap.credits?.let { "${usd(it.left)} of credits left" },
                                    snap.key.limit?.let { "key limit ${usd(it)}" },
                                ).joinToString(" · ").ifBlank { null }?.replaceFirstChar { it.uppercase() }
                            } ?: "Set a limit for this key at openrouter.ai",
                            onClick = onUsage,
                            chevron = true,
                        )
                        GroupDivider()
                        GroupSwitchRow(
                            "Private providers only",
                            "Requests go only to providers that keep no data. A free model you chose with the warning is the one exception.",
                            state.privateOnly,
                            vm::setPrivateOnly,
                        )
                        GroupDivider()
                        GroupActionRow("Sign out", "The key is forgotten on this phone. Delete it at openrouter.ai to end it.", { confirmSignOut = true }, color = k.danger)
                    }
                }
                ProviderKind.Custom -> Column {
                    GroupLabel("Your own server")
                    VaultGroup {
                        GroupActionRow(state.customUrl, if (state.customHasKey) "With a key, kept encrypted on this phone" else "No key", onClick = onModels, chevron = true)
                        GroupDivider()
                        GroupActionRow("Sign out", "The address and the key are forgotten on this phone.", { confirmSignOut = true }, color = k.danger)
                    }
                }
                ProviderKind.None -> Column {
                    GroupLabel("OpenRouter")
                    VaultGroup {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                "One account for hundreds of models. You pay OpenRouter directly, only for what you use; free models are there too.",
                                style = VaultType.Body, color = k.text,
                            )
                            Spacer(Modifier.size(14.dp))
                            SignInPanel(vm)
                        }
                    }
                }
            }
            if (state.kind != ProviderKind.OpenRouter) Column {
                GroupLabel(if (state.kind == ProviderKind.Custom) "Server" else "Advanced: your own server")
                VaultGroup { CustomServerForm(vm, Modifier.padding(16.dp), initialUrl = state.customUrl, onChosen = null) }
            }
            Text(privacyLine(), style = VaultType.Caption, color = k.muted, modifier = Modifier.padding(horizontal = 8.dp))
        }
    }
    if (confirmSignOut) {
        VaultDialog(
            title = "Sign out?",
            text = "${LocalAgentName.current} stops answering until a provider is connected again. Your chats stay on this phone.",
            confirm = "Sign out",
            destructive = true,
            onConfirm = {
                confirmSignOut = false
                vm.signOut()
            },
            onDismiss = { confirmSignOut = false },
        )
    }
}

/**
 * Connect OpenRouter: the primary button opens the sign-in in a Custom Tab; "Sign in with a code instead" is the
 * headless way, for when the browser can't come back to the app. While waiting it says so; with the code way it asks
 * for the code.
 */
@Composable
internal fun SignInPanel(vm: ProviderViewModel, modifier: Modifier = Modifier, primaryAtFoot: Boolean = false) {
    val k = MaterialTheme.vault
    val context = LocalContext.current
    val signIn by vm.signIn.collectAsStateWithLifecycle()
    var code by rememberSaveable { mutableStateOf("") }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when (val s = signIn) {
            is SignIn.Waiting -> if (s.method == OpenRouterAuth.Method.Code) {
                Text("Approve on OpenRouter, then copy the code it shows and paste it here.", style = VaultType.Label.copy(fontWeight = null), color = k.muted)
                VaultField(code, { code = it }, "Code from OpenRouter", placeholder = "Paste the code", keyboardType = KeyboardType.Ascii)
                PrimaryButton("Continue", { vm.finishWithCode(code) }, Modifier.fillMaxWidth(), enabled = code.isNotBlank())
                QuietButton("Cancel", vm::cancelSignIn, Modifier.fillMaxWidth(), color = k.muted)
            } else {
                Text("Waiting for OpenRouter… Approve there, then come back here.", style = VaultType.Label.copy(fontWeight = null), color = k.muted)
                QuietButton("Open OpenRouter again", { openInBrowser(context, vm.startSignIn()) }, Modifier.fillMaxWidth())
                QuietButton("Cancel", vm::cancelSignIn, Modifier.fillMaxWidth(), color = k.muted)
            }
            SignIn.Exchanging -> Text("Finishing the sign-in…", style = VaultType.Label.copy(fontWeight = null), color = k.muted)
            else -> {
                if (s is SignIn.Failed) Text(s.message, style = VaultType.Label.copy(fontWeight = null), color = k.attention)
                // In onboarding the screen puts Connect OpenRouter at its foot (one primary per step, at the foot).
                if (!primaryAtFoot) PrimaryButton("Connect OpenRouter", { openInBrowser(context, vm.startSignIn()) }, Modifier.fillMaxWidth())
                QuietButton("Sign in with a code instead", { openInBrowser(context, vm.startSignIn(code = true)) }, Modifier.fillMaxWidth(), color = k.muted)
            }
        }
    }
}

/** Your own server: address and optional key, Connect; then its models to pick the first one ([onChosen], onboarding). */
@Composable
internal fun CustomServerForm(vm: ProviderViewModel, modifier: Modifier = Modifier, initialUrl: String = "", onChosen: ((String) -> Unit)?) {
    val k = MaterialTheme.vault
    val custom by vm.custom.collectAsStateWithLifecycle()
    var address by rememberSaveable { mutableStateOf(initialUrl) }
    var key by rememberSaveable { mutableStateOf("") }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            "Any server that speaks the OpenAI chat API: a provider such as Groq or Mistral, or Ollama or LM Studio on your computer.",
            style = VaultType.Label.copy(fontWeight = null), color = k.muted,
        )
        VaultField(address, { address = it }, "Address", placeholder = "https://api.example.com/v1", keyboardType = KeyboardType.Uri,
            supporting = custom.warning, isError = custom.warning != null)
        VaultField(key, { key = it }, "Key (optional)", placeholder = "Not needed for a server at home", keyboardType = KeyboardType.Password,
            transformation = PasswordVisualTransformation(), supporting = "Kept encrypted on this phone, and only sent to this address.")
        custom.error?.let { Text(it, style = VaultType.Label.copy(fontWeight = null), color = k.attention) }
        // In onboarding it is the step's one action: the primary. On the Provider page it is a secondary inside the group.
        if (onChosen != null) PrimaryButton(if (custom.working) "Connecting…" else "Connect", { vm.connectCustom(address, key) }, Modifier.fillMaxWidth(), enabled = !custom.working)
        else SecondaryButton(if (custom.working) "Connecting…" else "Connect", { vm.connectCustom(address, key) }, Modifier.fillMaxWidth(), onLift = true)
        if (custom.models.isNotEmpty()) {
            Text("${custom.models.size} models on this server" + if (onChosen != null) ". Pick the first one to use:" else ". Add them on the Models page.",
                style = VaultType.Label.copy(fontWeight = null), color = k.muted)
            if (onChosen != null) {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (LocalOnGround.current) k.surfaceHigh else k.background)) {
                    custom.models.take(40).forEachIndexed { i, id ->
                        if (i > 0) GroupDivider()
                        GroupActionRow(id, null, { onChosen(id) })
                    }
                }
            }
        }
    }
}

/** One model to choose: name, price per message, a Free chip, and a check when chosen (chosen by tone, never by outline). */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun ModelOptionRow(option: ModelOption, selected: Boolean, onClick: () -> Unit) {
    val k = MaterialTheme.vault
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .pressable(onClick = onClick, shape = RoundedCornerShape(14.dp), pressedScale = 0.985f, rest = if (selected) k.pressed else k.surfaceHigh, pressed = k.pressed, role = Role.RadioButton)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            // The role chip wraps under the name at large font sizes rather than breaking inside its word.
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(option.model.label, style = VaultType.Body.copy(fontWeight = VaultType.Row.fontWeight), color = k.text)
                Text(
                    option.model.role, style = VaultType.Caption, color = k.muted, maxLines = 1, softWrap = false,
                    modifier = Modifier.clip(CircleShape).background(if (selected) k.surfaceHigh else k.pressed).padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
            Text(option.price, style = VaultType.Label.copy(fontWeight = null), color = k.muted)
        }
        Box(
            Modifier.size(22.dp).clip(CircleShape).background(if (selected) k.accent else k.pressed),
            contentAlignment = Alignment.Center,
        ) { if (selected) Icon(VaultIcons.Check, null, tint = k.onAccent, modifier = Modifier.size(14.dp)) }
    }
}
