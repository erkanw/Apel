package io.github.erkanw.vault.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.erkanw.vault.ChatViewModel
import io.github.erkanw.vault.PagesViewModel
import io.github.erkanw.vault.ProviderViewModel
import io.github.erkanw.vault.SettingsViewModel
import io.github.erkanw.vault.chat.Channel
import io.github.erkanw.vault.chat.ChannelEntry
import io.github.erkanw.vault.chat.lookOf
import io.github.erkanw.vault.store.AgentProfile

/** The agent's name (Apel until renamed), for every agent-facing string on screen (handbook §6). */
val LocalAgentName = compositionLocalOf { "" }

/** The app: the screens, and the drawer that leads to them and to the sessions. */
@Composable
fun VaultApp(
    vm: ChatViewModel,
    pages: PagesViewModel,
    settings: SettingsViewModel,
    provider: ProviderViewModel,
    focusTick: Int,
    openRouterTick: Int = 0,
    openChatTick: Int = 0,
) {
    val k = MaterialTheme.vault
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val sessionsLoading by vm.sessionsLoading.collectAsStateWithLifecycle()
    val sessionsError by vm.sessionsError.collectAsStateWithLifecycle()
    val chatId by vm.currentChatId.collectAsStateWithLifecycle()
    val channelChats by vm.channelChats.collectAsStateWithLifecycle()
    val activeChannel by vm.activeChannel.collectAsStateWithLifecycle()
    val customChannels by vm.customChannels.collectAsStateWithLifecycle()
    val unreadChats by vm.unreadChats.collectAsStateWithLifecycle()
    val channelLooks by vm.channelLooks.collectAsStateWithLifecycle()
    val automations by pages.automations.collectAsStateWithLifecycle()

    val hostContext = LocalContext.current
    val agentName = AgentProfile.nameFlow(hostContext).collectAsStateWithLifecycle().value ?: AgentProfile.name(hostContext)
    val blobLook = io.github.erkanw.vault.store.LookStore.flow(hostContext).collectAsStateWithLifecycle().value ?: io.github.erkanw.vault.mascot.BlobLook.Default
    // Debug builds can show sample data (see DemoMode); a release build never does.
    val demo = remember { DemoMode.active(hostContext) }
    val providerState by provider.state.collectAsStateWithLifecycle()
    val connected = demo || providerState.connected
    // The first run opens on the welcome (PLAN §6.1); after that, Home.
    var screenName by rememberSaveable { mutableStateOf(if (demo || AgentProfile.welcomeDone(hostContext)) Screen.Home.name else Screen.Onboarding.name) }
    val screen = Screen.valueOf(screenName)
    var openCount by remember { mutableIntStateOf(0) }
    // The search text on the home screen; kept here because the home screen is left (and rebuilt) when a chat opens.
    var homeQuery by rememberSaveable { mutableStateOf("") }
    // Models and the usage page are reached from Home's "more" sheet or from Settings: Back goes to where you came from.
    var openedFrom by rememberSaveable { mutableStateOf(Screen.Settings.name) }
    // The provider page is opened from Settings or from Home's "Not connected" line: Back goes there.
    var providerFrom by rememberSaveable { mutableStateOf(Screen.Settings.name) }
    // The automation whose runs page is open.
    var runsFor by rememberSaveable { mutableStateOf<String?>(null) }

    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    // Each time the home screen comes into view: the keyboard goes away, its rows arrive again, and the list is brought
    // up to date.
    LaunchedEffect(screen) {
        if (screen == Screen.Home) {
            focusManager.clearFocus()
            keyboard?.hide()
            openCount++
            vm.refreshSessions()
            if (connected) pages.loadAutomations()
        }
    }
    // Tapping the widget goes to the chat.
    LaunchedEffect(focusTick) {
        if (focusTick > 0) screenName = Screen.Chat.name
    }
    // A "Replies" notification was tapped: show the chat (it is opened by the activity).
    LaunchedEffect(openChatTick) {
        if (openChatTick > 0) screenName = Screen.Chat.name
    }
    // The usage widget was tapped: show its page.
    LaunchedEffect(openRouterTick) {
        if (openRouterTick > 0) screenName = Screen.OpenRouter.name
    }
    // Back goes one step up: Files up a folder first, the usage page to where it was opened from, everything else Home.
    BackHandler(enabled = screen != Screen.Home && screen != Screen.Onboarding) {
        if (screen == Screen.Files && pages.folderUp()) return@BackHandler
        screenName = when (screen) {
            Screen.OpenRouter, Screen.Models, Screen.Look -> openedFrom
            Screen.Agent -> Screen.Settings.name
            Screen.AutomationRuns -> Screen.Automation.name
            Screen.Provider -> providerFrom
            else -> Screen.Home.name
        }
    }

    fun go(target: Screen) {
        if (target == Screen.Models || target == Screen.OpenRouter || target == Screen.Look) openedFrom = screen.name
        screenName = target.name
    }

    CompositionLocalProvider(LocalAgentName provides agentName, io.github.erkanw.vault.mascot.LocalBlobLook provides blobLook) {
        // The home screen is at the bottom of the stack; every other screen opens over it (sliding in from the right)
        // and goes back to it (sliding out to the right).
        AnimatedContent(
            targetState = screen,
            transitionSpec = {
                val forward = depthOf(targetState) > depthOf(initialState)
                if (forward) {
                    (slideInHorizontally(tween(Motion.Long, easing = Motion.Decelerate)) { it } + fadeIn(tween(Motion.Medium))) togetherWith
                        (slideOutHorizontally(tween(Motion.Long, easing = Motion.Decelerate)) { -it / 4 } + fadeOut(tween(Motion.Long)))
                } else {
                    (slideInHorizontally(tween(Motion.Long, easing = Motion.Decelerate)) { -it / 4 } + fadeIn(tween(Motion.Medium))) togetherWith
                        (slideOutHorizontally(tween(Motion.Long, easing = Motion.Decelerate)) { it } + fadeOut(tween(Motion.Medium)))
                }.using(SizeTransform(clip = false)).apply { targetContentZIndex = if (forward) 1f else 0f }
            },
            label = "screen",
        ) { shown ->
            val back: () -> Unit = { screenName = Screen.Home.name }
            // A screen opened over another one hides what is underneath it from touches.
            Box(Modifier.fillMaxSize().background(k.background)) {
                when (shown) {
                    Screen.Home -> {
                        val shownSessions = remember(sessions, channelChats) { sessions.filter { it.chatId !in channelChats.values } }
                        val channelEntries = (Channel.builtIn + customChannels).map { channel ->
                            val bound = channelChats[channel.id]
                            ChannelEntry(
                                channel = channel,
                                look = lookOf(channelLooks, channel),
                                chatId = bound,
                                selected = activeChannel == channel,
                                running = (demo && DemoMode.channelRunning(channel.id)) || (bound != null && sessions.any { it.chatId == bound && it.running }),
                                unread = (demo && DemoMode.channelUnread(channel.id)) || (bound != null && bound in unreadChats),
                            )
                        }
                        val failed = (demo && DemoMode.unreachable(hostContext)) || (sessionsError != null && sessions.isEmpty())
                        val runningName = channelEntries.firstOrNull { it.running }?.look?.title ?: shownSessions.firstOrNull { it.running }?.title
                        val presence = when {
                            failed -> Presence.Problem
                            // Not connected needs the user: amber, like "can't reach" (DESIGN.md, Signal).
                            !connected -> Presence.Problem
                            runningName != null -> Presence.Working
                            sessionsLoading && sessions.isEmpty() -> Presence.Unknown
                            else -> Presence.Ready
                        }
                        val nextRun = remember(automations) {
                            val now = System.currentTimeMillis()
                            automations.items.filter { it.enabled && it.nextRunMs > now }.minByOrNull { it.nextRunMs }?.let { NextRun(it.name, it.nextRunMs) }
                        }
                        HomeScreen(
                            presence = presence,
                            status = when {
                                failed -> "Couldn’t load your chats. Tap to try again."
                                !connected -> "Not connected. Tap to connect."
                                runningName != null -> "Working in $runningName"
                                else -> null
                            },
                            sessions = shownSessions,
                            loading = sessionsLoading,
                            error = sessionsError,
                            currentChatId = chatId,
                            openCount = openCount,
                            onNewSession = {
                                vm.newSession()
                                go(Screen.Chat)
                            },
                            onAsk = { text ->
                                vm.newSession()
                                vm.send(text)
                                go(Screen.Chat)
                            },
                            onOpenSession = { id ->
                                vm.openSession(id)
                                go(Screen.Chat)
                            },
                            onSetArchived = vm::setArchived,
                            onRename = vm::renameSession,
                            onTogglePinned = vm::togglePinned,
                            onDelete = vm::deleteSession,
                            onNavigate = ::go,
                            onRetry = { if (connected) vm.refreshSessions() else { providerFrom = Screen.Home.name; screenName = Screen.Provider.name } },
                            channels = channelEntries,
                            onOpenChannel = { channel ->
                                vm.openChannel(channel)
                                go(Screen.Chat)
                            },
                            onSaveChannelLook = vm::saveChannelLook,
                            onResetChannelLook = vm::resetChannelLook,
                            onCreateChannel = { look ->
                                val made = vm.createChannel(look.title, look.blurb, look.color)
                                vm.openChannel(made)
                                go(Screen.Chat)
                            },
                            onDeleteChannel = vm::deleteChannel,
                            query = homeQuery,
                            onQuery = { homeQuery = it },
                            unreadChats = unreadChats,
                            nextRun = nextRun,
                            onAttach = { uris, text ->
                                vm.newSession()
                                vm.attach(uris)
                                if (text.isNotBlank()) vm.prefillDraft(text)
                                go(Screen.Chat)
                            },
                        )
                    }
                    Screen.Chat -> ChatScreen(vm, focusTick, onBack = back, onSettings = { screenName = Screen.Settings.name }, onModels = { go(Screen.Models) })
                    Screen.Skills -> SkillsScreen(pages, onBack = back, onRequestInNewChat = { request ->
                        vm.newSession()
                        vm.prefillDraft(request)
                        screenName = Screen.Chat.name
                    })
                    Screen.Automation -> AutomationScreen(
                        pages,
                        vm,
                        onBack = back,
                        // A run's row opens the chat it posted in (its channel, when it is one).
                        onOpenChat = { id ->
                            vm.openChatById(id)
                            screenName = Screen.Chat.name
                        },
                        onShowRuns = { job ->
                            runsFor = job.id
                            screenName = Screen.AutomationRuns.name
                        },
                    )
                    Screen.AutomationRuns -> AutomationRunsScreen(pages, runsFor, onBack = { screenName = Screen.Automation.name }, onOpenChat = { id ->
                        vm.openChatById(id)
                        screenName = Screen.Chat.name
                    })
                    Screen.Settings -> SettingsScreen(
                        settings,
                        onBack = back,
                        onOpenRouter = { go(Screen.OpenRouter) },
                        onModels = { go(Screen.Models) },
                        onLook = { screenName = Screen.Agent.name },
                        onProvider = { providerFrom = Screen.Settings.name; screenName = Screen.Provider.name },
                        providerSummary = when (providerState.kind) {
                            io.github.erkanw.vault.provider.ProviderKind.OpenRouter -> "OpenRouter"
                            io.github.erkanw.vault.provider.ProviderKind.Custom -> providerState.customUrl.removePrefix("https://").removePrefix("http://")
                            io.github.erkanw.vault.provider.ProviderKind.None -> "Not connected"
                        },
                        onWelcome = {
                            AgentProfile.setWelcomeDone(hostContext, false)
                            screenName = Screen.Onboarding.name
                        },
                    )
                    Screen.OpenRouter -> OpenRouterScreen(settings, onBack = { screenName = openedFrom })
                    Screen.Files -> FilesScreen(pages, onBack = back)
                    Screen.Tools -> ToolsScreen(pages, onBack = back)
                    Screen.Databases -> DatabasesScreen(androidx.lifecycle.viewmodel.compose.viewModel<io.github.erkanw.vault.DatabasesViewModel>(), onBack = back)
                    Screen.Models -> ModelsScreen(settings, onBack = { screenName = openedFrom }, onSettings = { screenName = Screen.Provider.name })
                    Screen.Provider -> ProviderScreen(provider, onBack = { screenName = providerFrom }, onModels = { go(Screen.Models) }, onUsage = { go(Screen.OpenRouter) })
                    Screen.Look -> LookScreen(onBack = { screenName = openedFrom })
                    Screen.Agent -> AgentScreen(onBack = { screenName = Screen.Settings.name }, onLook = { go(Screen.Look) })
                    Screen.Onboarding -> OnboardingScreen(provider, onDone = {
                        vm.refreshComposerExtras()
                        screenName = Screen.Home.name
                    })
                }
            }
        }
    }
}

/** How deep a screen is in the stack: the home screen at the bottom, the usage page (reached from Settings) on top. */
private fun depthOf(screen: Screen): Int = when (screen) {
    Screen.Home -> 0
    Screen.OpenRouter, Screen.Models, Screen.Provider, Screen.Agent, Screen.AutomationRuns -> 2
    Screen.Look -> 3
    else -> 1
}
