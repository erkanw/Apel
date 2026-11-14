package io.github.erkanw.vault.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.erkanw.vault.ProviderViewModel
import io.github.erkanw.vault.mascot.Blob
import io.github.erkanw.vault.mascot.BlobMood
import io.github.erkanw.vault.provider.ProviderKind

private enum class Step { Welcome, Connect, OwnServer, Model, Yours }

/**
 * The first run (PLAN §6.1, handbook §3): welcome, connect (OpenRouter, your own server, or look around first), the
 * first model, and making it yours (the name; the look stays the default until the Look page exists). [onDone] opens Home.
 */
@Composable
fun OnboardingScreen(vm: ProviderViewModel, onDone: () -> Unit) {
    val k = MaterialTheme.vault
    val state by vm.state.collectAsStateWithLifecycle()
    val catalog by vm.catalog.collectAsStateWithLifecycle()
    val signIn by vm.signIn.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    var stepName by rememberSaveable { mutableStateOf(Step.Welcome.name) }
    val step = Step.valueOf(stepName)
    val agent = LocalAgentName.current
    var name by rememberSaveable { mutableStateOf(agent) }
    var chosen by rememberSaveable { mutableStateOf<String?>(null) }
    var warnFor by rememberSaveable { mutableStateOf<String?>(null) }
    var lookOpen by rememberSaveable { mutableStateOf(false) }

    // Once OpenRouter is connected, the next step is the first model.
    LaunchedEffect(state.kind) {
        if (step == Step.Connect && state.kind == ProviderKind.OpenRouter) stepName = Step.Model.name
    }
    LaunchedEffect(step) { if (step == Step.Model) vm.loadCatalog() }
    BackHandler(enabled = step != Step.Welcome) {
        stepName = when (step) {
            Step.Connect -> Step.Welcome
            Step.OwnServer -> Step.Connect
            Step.Model -> Step.Connect
            else -> if (state.connected) Step.Model else Step.Connect
        }.name
    }

    // The Look page in place of the welcome (only one of them on screen); the step and the typed name stay as they were.
    if (lookOpen) {
        BackHandler { lookOpen = false }
        LookScreen(onBack = { lookOpen = false })
        return
    }

    androidx.compose.runtime.CompositionLocalProvider(LocalOnGround provides true) {
    Column(Modifier.fillMaxSize().background(k.background).statusBarsPadding().navigationBarsPadding().imePadding()) {
        // One mascot for the whole welcome: it keeps living across the steps and grows or shrinks on the settle spring.
        val big = step == Step.Welcome || step == Step.Yours
        val blobSize by androidx.compose.animation.core.animateDpAsState(if (big) 210.dp else 120.dp, Motion.settle(), label = "blobSize")
        val blobTop by androidx.compose.animation.core.animateDpAsState(if (big) 72.dp else 28.dp, Motion.settle(), label = "blobTop")
        Column(Modifier.fillMaxWidth().padding(top = blobTop, bottom = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Blob(BlobMood.Idle, size = blobSize, description = agent)
        }
        AnimatedContent(step, Modifier.weight(1f), transitionSpec = { fadeIn(tween(Motion.Medium)) togetherWith fadeOut(tween(Motion.Short)) }, label = "step") { shown ->
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                when (shown) {
                    Step.Welcome -> {
                        Heading("Hi, I’m $agent")
                        Line("$agent is your own AI agent. It lives on this phone; you pay the AI provider directly, only for what you use.")
                        Spacer(Modifier.height(12.dp))
                        Text(privacyLine(), style = VaultType.Caption.copy(lineBreak = androidx.compose.ui.text.style.LineBreak.Heading), color = k.muted, textAlign = TextAlign.Center)
                    }
                    Step.Connect -> {
                        Heading("Connect a provider")
                        Line(
                            when {
                                // Shown again from Settings while connected: nothing to sign in to.
                                state.kind == ProviderKind.OpenRouter -> "You’re signed in to OpenRouter. Continue to pick a model, or use your own server instead."
                                state.connected -> "You’re connected to your own server. Continue to pick a model."
                                else -> "OpenRouter gives you hundreds of models with one account and prepaid credits. Sign in, and come back here."
                            },
                        )
                        Spacer(Modifier.height(20.dp))
                        if (!state.connected) SignInPanel(vm, primaryAtFoot = true)
                        QuietButton("Use my own server", { stepName = Step.OwnServer.name }, Modifier.fillMaxWidth(), color = k.muted)
                        QuietButton("Look around first", {
                            vm.finishWelcome(name)
                            onDone()
                        }, Modifier.fillMaxWidth(), color = k.muted)
                    }
                    Step.OwnServer -> {
                        Heading("Your own server")
                        Spacer(Modifier.height(16.dp))
                        CustomServerForm(vm, onChosen = { id ->
                            vm.chooseModel(id, io.github.erkanw.vault.chat.prettyModelName(id).ifBlank { id }, null, allowKeepsData = false)
                            stepName = Step.Yours.name
                        })
                    }
                    Step.Model -> {
                        Heading("Pick a first model")
                        Line("You can add more and switch at any time.")
                        Spacer(Modifier.height(16.dp))
                        val options = vm.options(catalog.models, state.privateOnly)
                        if (chosen == null) chosen = vm.currentMainModel()?.takeIf { id -> options.any { it.model.id == id } } ?: options.first().model.id
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            options.forEach { o -> ModelOptionRow(o, chosen == o.model.id) { chosen = o.model.id } }
                        }
                        if (catalog.loading) Text("Reading prices…", style = VaultType.Caption, color = k.muted, modifier = Modifier.padding(top = 8.dp))
                        catalog.error?.let { Text("Prices couldn’t be read. You can still choose.", style = VaultType.Caption, color = k.muted, modifier = Modifier.padding(top = 8.dp)) }
                    }
                    Step.Yours -> {
                        Heading("Make it yours")
                        Line("Give your agent a name. You can change it, and its look, later in Settings.")
                        Spacer(Modifier.height(16.dp))
                        VaultField(name, { name = it.take(24) }, "Name", placeholder = agent)
                        QuietButton("Change look", { lookOpen = true }, Modifier.fillMaxWidth().padding(top = 8.dp), color = k.muted)
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
        // One primary action per step, at the foot.
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp)) {
            when (step) {
                Step.Welcome -> PrimaryButton("Get started", { stepName = Step.Connect.name }, Modifier.fillMaxWidth())
                Step.Connect -> when {
                    state.connected -> PrimaryButton("Continue", { stepName = Step.Model.name }, Modifier.fillMaxWidth())
                    // While the browser is out or the code is being asked for, the panel holds the actions.
                    signIn is io.github.erkanw.vault.SignIn.Idle || signIn is io.github.erkanw.vault.SignIn.Failed ->
                        PrimaryButton("Connect OpenRouter", { openInBrowser(context, vm.startSignIn()) }, Modifier.fillMaxWidth())
                    else -> Unit
                }
                Step.Model -> {
                    val options = vm.options(catalog.models, state.privateOnly)
                    val pick = options.firstOrNull { it.model.id == chosen } ?: options.first()
                    PrimaryButton("Use ${pick.model.label}", {
                        if (pick.keepsData && state.privateOnly) {
                            warnFor = pick.model.id
                        } else {
                            vm.chooseModel(pick.model.id, pick.model.label, pick.catalog, allowKeepsData = false)
                            stepName = Step.Yours.name
                        }
                    }, Modifier.fillMaxWidth())
                }
                Step.Yours -> PrimaryButton("Keep this look", {
                    vm.finishWelcome(name)
                    onDone()
                }, Modifier.fillMaxWidth())
                else -> Unit
            }
        }
    }

    }

    warnFor?.let { id ->
        val pick = vm.options(catalog.models, state.privateOnly).first { it.model.id == id }
        VaultDialog(
            title = "Use a model that may keep data?",
            text = "This free model’s provider may keep your messages. Private providers stay on for every other model.",
            confirm = "Use it",
            onConfirm = {
                warnFor = null
                vm.chooseModel(pick.model.id, pick.model.label, pick.catalog, allowKeepsData = true)
                stepName = Step.Yours.name
            },
            onDismiss = { warnFor = null },
        )
    }
}

@Composable
private fun Heading(text: String) {
    Text(text, style = VaultType.Display, color = MaterialTheme.vault.text, textAlign = TextAlign.Center)
}

@Composable
private fun Line(text: String) {
    // Heading line breaks: no single word left alone on the last line of centred text.
    Text(text, style = VaultType.Body.copy(lineBreak = androidx.compose.ui.text.style.LineBreak.Heading), color = MaterialTheme.vault.muted, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
}
