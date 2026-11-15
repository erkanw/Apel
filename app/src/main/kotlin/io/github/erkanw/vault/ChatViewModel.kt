package io.github.erkanw.vault

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.erkanw.vault.agent.AgentBackend
import io.github.erkanw.vault.agent.TurnEvent
import io.github.erkanw.vault.agent.TurnInput
import io.github.erkanw.vault.chat.AgentStep
import io.github.erkanw.vault.chat.AttachmentKind
import io.github.erkanw.vault.chat.AttachmentReader
import io.github.erkanw.vault.chat.Channel
import io.github.erkanw.vault.chat.ChannelLook
import io.github.erkanw.vault.chat.ChannelLooks
import io.github.erkanw.vault.chat.ChatAlerts
import io.github.erkanw.vault.chat.ChatException
import io.github.erkanw.vault.chat.ChatMessage
import io.github.erkanw.vault.chat.ChatStore
import io.github.erkanw.vault.chat.Dictation
import io.github.erkanw.vault.chat.DictationListener
import io.github.erkanw.vault.chat.IngressLimits
import io.github.erkanw.vault.chat.LOCAL_COMMANDS
import io.github.erkanw.vault.chat.MediaLoader
import io.github.erkanw.vault.chat.MediaPart
import io.github.erkanw.vault.chat.MessageAttachment
import io.github.erkanw.vault.chat.ModelChoices
import io.github.erkanw.vault.chat.ModelPreset
import io.github.erkanw.vault.chat.ModelPrefs
import io.github.erkanw.vault.chat.ModelPrefsStore
import io.github.erkanw.vault.chat.ModelsHub
import io.github.erkanw.vault.chat.NEW_SESSION_TITLE
import io.github.erkanw.vault.chat.Rejection
import io.github.erkanw.vault.chat.Role
import io.github.erkanw.vault.chat.SeenStore
import io.github.erkanw.vault.chat.SessionStats
import io.github.erkanw.vault.chat.SessionSummary
import io.github.erkanw.vault.chat.SlashCommand
import io.github.erkanw.vault.chat.StepKind
import io.github.erkanw.vault.chat.agentStep
import io.github.erkanw.vault.chat.applyModelPrefs
import io.github.erkanw.vault.chat.channelOf
import io.github.erkanw.vault.chat.checkAdding
import io.github.erkanw.vault.chat.classifyAttachment
import io.github.erkanw.vault.chat.cleanLook
import io.github.erkanw.vault.chat.defaultLook
import io.github.erkanw.vault.chat.encodedSize
import io.github.erkanw.vault.chat.hasNews
import io.github.erkanw.vault.chat.mimeOf
import io.github.erkanw.vault.chat.promptOf
import io.github.erkanw.vault.chat.rejectionMessage
import io.github.erkanw.vault.chat.statsOf
import io.github.erkanw.vault.chat.userIndexBefore
import io.github.erkanw.vault.chat.withStep
import io.github.erkanw.vault.data.AppSettings
import io.github.erkanw.vault.mascot.BlobState
import io.github.erkanw.vault.store.AgentProfile
import io.github.erkanw.vault.widget.BlobWidgetUpdater
import io.github.erkanw.vault.widget.WidgetStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The chat area (PLAN §5): the session on screen and its messages, the session list, channels, the composer (attachments,
 * commands, models, dictation) and the mascot's mood. Home and the drawer read it too. Everything goes through
 * [AgentBackend].
 */
class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val settings = AppSettings(app)
    private val store = ChatStore(app)
    private val backend: AgentBackend = AppGraph.backend(app)
    private val seenStore = SeenStore(app)
    private val dictation = Dictation(app)

    /** False while no provider is connected (and demo mode is off). */
    val connected: Boolean get() = backend.connected

    /** False when the phone has no speech recognizer: the mic is hidden. */
    val dictationAvailable: Boolean = dictation.available()

    /** The session of each channel by channel id; a channel without one isn't started yet. */
    private val _channelChats = MutableStateFlow(settings.channelChats())
    val channelChats: StateFlow<Map<String, String>> = _channelChats.asStateFlow()

    /** A channel that was opened before it had a session: the next session made is its. */
    private val _pendingChannel = MutableStateFlow<Channel?>(null)

    /** The session being shown, or null for a new one that has no id until its first message. */
    private val _currentChatId = MutableStateFlow(settings.currentChatId)
    val currentChatId: StateFlow<String?> = _currentChatId.asStateFlow()

    /** The channel on screen: the one whose session is open, or one just opened that has none yet. Null: an ordinary session. */
    val activeChannel: StateFlow<Channel?> =
        combine(_currentChatId, _channelChats, _pendingChannel, ChannelLooks.custom) { chat, bound, pending, custom ->
            if (chat == null) pending else channelOf(bound, chat, Channel.builtIn + custom)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _sessions = MutableStateFlow<List<SessionSummary>>(emptyList())
    val sessions: StateFlow<List<SessionSummary>> = _sessions.asStateFlow()

    private val _sessionsLoading = MutableStateFlow(false)
    val sessionsLoading: StateFlow<Boolean> = _sessionsLoading.asStateFlow()

    private val _sessionsError = MutableStateFlow<String?>(null)
    val sessionsError: StateFlow<String?> = _sessionsError.asStateFlow()

    /** True while an opened session's messages are being read. */
    private val _threadLoading = MutableStateFlow(false)
    val threadLoading: StateFlow<Boolean> = _threadLoading.asStateFlow()

    /**
     * Bumped whenever the session on screen is replaced. A reply still arriving for the session we left must not write
     * into the new one, so it checks this first.
     */
    private var epoch = 0

    private val _messages = MutableStateFlow(store.load())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    /** The mascot's mood, from what the agent is doing. */
    private val _mood = MutableStateFlow(BlobState.Idle)
    val mood: StateFlow<BlobState> = _mood.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** What the agent is doing right now ("Reading example.com"), while a reply is on its way. */
    private val _step = MutableStateFlow("")
    val step: StateFlow<String> = _step.asStateFlow()

    /** The slash commands, for the menu that opens when you type "/". */
    private val _commands = MutableStateFlow(LOCAL_COMMANDS)
    val commands: StateFlow<List<SlashCommand>> = _commands.asStateFlow()

    /** The model presets to pick from (with this phone's labels, order and hidden ones), and the one in use. */
    val models: StateFlow<ModelChoices> = combine(ModelsHub.choices, ModelPrefsStore.prefs) { choices, prefs -> applyModelPrefs(choices, prefs) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ModelChoices.None)

    val modelPrefs: StateFlow<ModelPrefs> = ModelPrefsStore.prefs
    val catalog: StateFlow<CatalogState> = ModelsHub.catalog

    fun loadCatalog(force: Boolean = false) {
        viewModelScope.launch { ModelsHub.loadCatalog(getApplication(), force) }
    }

    /** The files chosen for the next message. */
    private val _pending = MutableStateFlow<List<PendingAttachment>>(emptyList())
    val pending: StateFlow<List<PendingAttachment>> = _pending.asStateFlow()

    /** Something to tell about the files or the chat (one was refused), shown briefly. */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private var limits = IngressLimits()
    private var nextAttachmentId = 1L

    private val _voice = MutableStateFlow<VoiceState>(VoiceState.Idle)
    val voice: StateFlow<VoiceState> = _voice.asStateFlow()

    /** Dictated text, for the message box to take. */
    private val _transcripts = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val transcripts: SharedFlow<String> = _transcripts.asSharedFlow()

    /** The counts for the information page of the session on screen. */
    private val _stats = MutableStateFlow<SessionStats?>(null)
    val sessionStats: StateFlow<SessionStats?> = _stats.asStateFlow()

    private var statsFor: String? = null
    private var voiceTicker: Job? = null

    private var job: Job? = null
    private var stopRequested = false
    private var nextId = (_messages.value.maxOfOrNull { it.id } ?: 0L) + 1

    init {
        ChannelLooks.ensureLoaded(app)
        ModelPrefsStore.ensureLoaded(app)
        onVisible()
    }

    /** Called when the app comes to the front. */
    fun onVisible() {
        refreshSessions()
        refreshComposerExtras()
        if (!_busy.value) syncCurrentThread()
    }

    /** Reads the commands, the model presets and the attachment limits. A failure leaves what was there. */
    fun refreshComposerExtras() {
        viewModelScope.launch {
            try {
                _commands.value = backend.commands()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The menu keeps the app's own commands.
            }
            ModelsHub.refresh(backend)
            try {
                limits = backend.limits()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Keep the usual limits.
            }
        }
    }

    // ---- attachments ------------------------------------------------------------------------------------

    /** Adds the picked files to the next message: each is checked against the limits, then prepared in the background. */
    fun attach(uris: List<Uri>) {
        val resolver = getApplication<Application>().contentResolver
        viewModelScope.launch {
            for (uri in uris) {
                val file = withContext(Dispatchers.IO) { AttachmentReader.describe(resolver, uri) }
                val kind = classifyAttachment(file.name, file.mime)
                val now = _pending.value
                val refused = checkAdding(
                    kind = kind,
                    size = if (file.size < 0) 1L else file.size,
                    count = now.size,
                    usedBytes = now.sumOf { it.bytes },
                    usedEncoded = now.sumOf { encodedSize(it.bytes) },
                    limits = limits,
                )
                if (refused != null) {
                    _notice.value = rejectionMessage(refused, file.name, limits)
                    continue
                }
                val id = nextAttachmentId++
                val isImage = kind == AttachmentKind.Image
                _pending.update { it + PendingAttachment(id, file.name, isImage, ready = false, bytes = maxOf(file.size, 1L)) }
                launch {
                    var kept: String? = null
                    val encoded = withContext(Dispatchers.IO) {
                        val result = if (isImage) AttachmentReader.encodeImage(resolver, file, limits.maxFileBytes)
                        else AttachmentReader.encodeDocument(resolver, file, limits.maxFileBytes)
                        // A copy stays on the phone, to be opened from the chat later.
                        if (result?.data != null && result.bytes <= limits.maxFileBytes) {
                            kept = MediaLoader.keepCopy(getApplication<Application>(), result.data, result.name)
                        }
                        result
                    }
                    when {
                        encoded == null -> {
                            _pending.update { list -> list.filterNot { it.id == id } }
                            _notice.value = rejectionMessage(
                                if (file.size > limits.maxFileBytes) Rejection.TooLarge else Rejection.Unreadable,
                                file.name,
                                limits,
                            )
                        }
                        encoded.bytes > limits.maxFileBytes -> {
                            _pending.update { list -> list.filterNot { it.id == id } }
                            _notice.value = rejectionMessage(Rejection.TooLarge, file.name, limits)
                        }
                        else -> _pending.update { list ->
                            list.map {
                                if (it.id == id) {
                                    it.copy(name = encoded.name, ready = true, bytes = encoded.bytes, dataUrl = encoded.dataUrl, thumbnail = encoded.thumbnail, localPath = kept)
                                } else {
                                    it
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    fun removeAttachment(id: Long) {
        _pending.value.firstOrNull { it.id == id }?.localPath?.let { path -> viewModelScope.launch(Dispatchers.IO) { MediaLoader.deleteCopy(path) } }
        _pending.update { list -> list.filterNot { it.id == id } }
    }

    fun clearNotice() {
        _notice.value = null
    }

    fun showNotice(text: String) {
        _notice.value = text
    }

    // ---- session information ----------------------------------------------------------------------------

    /** Counts the session on screen for its information page: the backend's numbers, else the messages on the phone. */
    fun loadSessionStats() {
        val chat = _currentChatId.value
        if (chat == null) {
            _stats.value = statsOf(_messages.value)
            return
        }
        if (statsFor != chat) {
            // Another session than the one counted last: don't show its numbers meanwhile.
            statsFor = chat
            _stats.value = null
        }
        val myEpoch = epoch
        viewModelScope.launch {
            try {
                val stats = backend.sessionStats(chat)
                if (epoch == myEpoch) _stats.value = stats ?: statsOf(_messages.value)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (epoch == myEpoch && _stats.value == null) _stats.value = statsOf(_messages.value, complete = false)
            }
        }
    }

    // ---- dictation --------------------------------------------------------------------------------------

    /**
     * Starts dictation (the microphone permission is already granted). [finishVoice] ends it and the words go into the
     * message box; [cancelVoice] throws them away.
     */
    fun startVoice() {
        if (_voice.value != VoiceState.Idle || _busy.value) return
        if (!dictationAvailable) {
            _notice.value = "This phone has no speech recognizer for dictation."
            return
        }
        val started = System.currentTimeMillis()
        _voice.value = VoiceState.Recording(0L, 0f)
        voiceTicker = viewModelScope.launch {
            while (isActive) {
                delay(250)
                _voice.update { if (it is VoiceState.Recording) it.copy(elapsedMs = System.currentTimeMillis() - started) else it }
            }
        }
        dictation.start(object : DictationListener {
            override fun onLevel(level: Float) =
                _voice.update { if (it is VoiceState.Recording) it.copy(level = level) else it }

            override fun onPartial(text: String) {}

            override fun onDone(text: String) {
                endVoice()
                if (text.isBlank()) _notice.value = "Nothing was heard. Tap the microphone and speak."
                else _transcripts.tryEmit(text)
            }

            override fun onFailed(message: String) {
                endVoice()
                _notice.value = message
            }
        })
    }

    private fun endVoice() {
        voiceTicker?.cancel()
        voiceTicker = null
        _voice.value = VoiceState.Idle
        dictation.release()
    }

    /** Ends dictation; the words heard so far go into the box. */
    fun finishVoice() {
        if (_voice.value is VoiceState.Recording) {
            _voice.value = VoiceState.Transcribing
            dictation.finish()
        }
    }

    /** Throws the dictation away. */
    fun cancelVoice() {
        if (_voice.value == VoiceState.Idle) return
        dictation.cancel()
        endVoice()
    }

    override fun onCleared() {
        dictation.release()
        super.onCleared()
    }

    // ---- models -------------------------------------------------------------------------------------------

    /** Switches the session on screen (or the next new one) to a model preset. */
    fun chooseModel(preset: ModelPreset) {
        if (_busy.value) return
        val chat = _currentChatId.value
        if (chat != null && _sessions.value.any { it.chatId == chat }) {
            _sessions.update { list -> list.map { if (it.chatId == chat) it.copy(modelPreset = preset.name) else it } }
        } else {
            ModelsHub.markActive(preset.name)
        }
        viewModelScope.launch {
            try {
                backend.setSessionModel(chat, preset.name)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _notice.value = e.message ?: "Couldn’t change the model."
                refreshSessions()
            }
        }
    }

    // ---- sessions ---------------------------------------------------------------------------------------

    fun refreshSessions() {
        viewModelScope.launch {
            _sessionsLoading.value = true
            try {
                _sessions.value = backend.sessions()
                _sessionsError.value = null
                // A message that arrives in the session on screen is read as it comes: it is not unread.
                _currentChatId.value?.let { open ->
                    _sessions.value.firstOrNull { it.chatId == open }?.let { markChatSeen(open, it.updatedMs) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _sessionsError.value = e.message ?: "Couldn’t load the sessions."
            } finally {
                _sessionsLoading.value = false
            }
        }
    }

    /** Changes a session: the drawer shows it at once; if the backend refuses, the drawer goes back and says why. */
    private fun sessionChange(
        optimistic: (List<SessionSummary>) -> List<SessionSummary>,
        failure: String,
        call: suspend () -> Unit,
    ) {
        val before = _sessions.value
        _sessions.value = optimistic(before)
        viewModelScope.launch {
            try {
                call()
                _sessionsError.value = null
                refreshSessions()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _sessions.value = before
                _sessionsError.value = e.message ?: failure
            }
        }
    }

    /** Archives a session (finished, or kept for later) or moves it back to Active. */
    fun setArchived(session: SessionSummary, archived: Boolean) = sessionChange(
        { list -> list.map { if (it.key == session.key) it.copy(archived = archived, pinned = it.pinned && !archived) else it } },
        "Couldn’t ${if (archived) "archive" else "restore"} the session.",
    ) { backend.setArchived(session.chatId, archived) }

    /** Gives a session a name of the user's own; a blank [title] goes back to the automatic one. */
    fun renameSession(session: SessionSummary, title: String) = sessionChange(
        { list -> if (title.isBlank()) list else list.map { if (it.key == session.key) it.copy(title = title.trim()) else it } },
        "Couldn’t rename the session.",
    ) { backend.rename(session.chatId, title) }

    fun togglePinned(session: SessionSummary) = sessionChange(
        { list -> list.map { if (it.key == session.key) it.copy(pinned = !it.pinned) else it } },
        "Couldn’t ${if (session.pinned) "unpin" else "pin"} the session.",
    ) { backend.setPinned(session.chatId, !session.pinned) }

    /** Deletes a session. If it is the one on screen, a new session takes its place. */
    fun deleteSession(session: SessionSummary) {
        val wasCurrent = session.chatId == _currentChatId.value
        sessionChange({ list -> list.filterNot { it.key == session.key } }, "Couldn’t delete the session.") {
            backend.deleteSession(session.chatId)
        }
        if (wasCurrent) newSession()
    }

    /** Replaces what is on the phone for the session on screen by what the backend has. */
    private fun syncCurrentThread() {
        val chat = _currentChatId.value ?: return
        val myEpoch = epoch
        viewModelScope.launch {
            try {
                val page = backend.thread(chat)
                if (epoch != myEpoch || _currentChatId.value != chat || _busy.value) return@launch
                if (page != null && page.messages.isNotEmpty()) showMessages(page.messages, page.userOffset, page.forkBoundary)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Keep what is already on the phone.
            }
        }
    }

    /**
     * Puts [list] on screen. [offset] is how many of your messages come before it in the whole session, and [boundary]
     * where a forked session's own messages begin.
     */
    private fun showMessages(list: List<ChatMessage>, offset: Int = 0, boundary: Int? = null) {
        userOffset = offset
        _forkBoundary.value = boundary
        _messages.value = list
        nextId = (list.maxOfOrNull { it.id } ?: 0L) + 1
        store.save(list)
    }

    /** Your messages before the first one on screen, for counting which of yours a fork starts before. */
    private var userOffset = 0

    /** In a forked session: how many of the messages on screen came from the session it was forked from. */
    private val _forkBoundary = MutableStateFlow<Int?>(null)
    val forkBoundary: StateFlow<Int?> = _forkBoundary.asStateFlow()

    private fun setCurrent(chatId: String?) {
        settings.currentChatId = chatId
        _currentChatId.value = chatId
    }

    // ---- edit and retry ---------------------------------------------------------------------------------

    /** Sends [newText] in place of [message] (one of yours): in a fork that starts just before it. */
    fun editMessage(message: ChatMessage, newText: String) = branch(message, newText.trim())

    /** Asks again: the prompt that [reply] answers is sent again, in a fork that starts just before it. */
    fun retry(reply: ChatMessage) {
        val list = _messages.value
        val at = list.indexOfFirst { it.id == reply.id }
        val prompt = if (at >= 0) promptOf(list, at) else null
        if (prompt != null) branch(prompt, prompt.text)
    }

    /**
     * Forks the session just before [prompt] and sends [text] there, so the original stays as it is. With your first
     * message there is nothing to keep, so it is simply a new session.
     */
    private fun branch(prompt: ChatMessage, text: String) {
        if (text.isEmpty() || _busy.value) return
        val list = _messages.value
        val at = list.indexOfFirst { it.id == prompt.id }
        if (at < 0) return
        val prefix = list.subList(0, at).toList()
        val source = _currentChatId.value
        val userIndex = userIndexBefore(list, at, userOffset)
        if (source == null || userIndex == 0) {
            newSession()
            send(text, withFiles = false)
            return
        }
        val myEpoch = epoch
        val title = "Fork: " + (_sessions.value.firstOrNull { it.chatId == source }?.title ?: NEW_SESSION_TITLE)
        _busy.value = true
        _step.value = "Branching the conversation…"
        publish(BlobState.Thinking)
        viewModelScope.launch {
            try {
                val forkId = backend.forkChat(source, userIndex, title)
                if (epoch != myEpoch) return@launch
                epoch++
                setCurrent(forkId)
                // What comes before the edited message is the same in the fork: keep it on screen.
                showMessages(prefix, userOffset, prefix.size.takeIf { it > 0 })
                _busy.value = false
                _step.value = ""
                send(text, withFiles = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (epoch == myEpoch) {
                    _busy.value = false
                    _step.value = ""
                    val message = e.message ?: "Couldn’t branch the conversation."
                    _messages.update { it + ChatMessage(nextId++, Role.Assistant, message, isError = true) }
                    publish(BlobState.Error, snippet = message.take(SNIPPET_CHARS))
                }
            }
        }
    }

    /** Opens a session from the drawer: its messages load, and the next message continues it. */
    fun openSession(chatId: String) {
        markChatSeen(chatId, maxOf(System.currentTimeMillis(), _sessions.value.firstOrNull { it.chatId == chatId }?.updatedMs ?: 0L))
        if (chatId == _currentChatId.value) return
        _pendingChannel.value = null
        leaveChat()
        // The old messages go first, so nothing shows the previous session's messages under the new one's id.
        showMessages(emptyList())
        setCurrent(chatId)
        _threadLoading.value = true
        val myEpoch = epoch
        viewModelScope.launch {
            try {
                val page = backend.thread(chatId)
                if (epoch == myEpoch) showMessages(page?.messages ?: emptyList(), page?.userOffset ?: 0, page?.forkBoundary)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (epoch == myEpoch) {
                    showMessages(listOf(ChatMessage(1L, Role.Assistant, "Couldn’t load this session: ${e.message}", isError = true)))
                }
            } finally {
                if (epoch == myEpoch) _threadLoading.value = false
            }
        }
    }

    /** Starts an empty session. It exists from its first message on. */
    fun newSession() {
        _pendingChannel.value = null
        leaveChat()
        showMessages(emptyList())
        _threadLoading.value = false
        setCurrent(null)
    }

    // ---- channels -----------------------------------------------------------------------------------------

    /** What you named and styled the channels; the screens read it from here. */
    val channelLooks: StateFlow<Map<String, ChannelLook>> = ChannelLooks.looks

    /** Keeps a channel's new name, description and icon; a new name is given to its session too. */
    fun saveChannelLook(channel: Channel, look: ChannelLook) {
        val before = ChannelLooks.look(channel)
        ChannelLooks.save(getApplication<Application>(), channel, look)
        val chatId = _channelChats.value[channel.id]
        if (chatId != null && look.title != before.title) {
            viewModelScope.launch {
                try {
                    backend.rename(chatId, look.title)
                    refreshSessions()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // The app shows the new name either way.
                }
            }
        }
    }

    fun resetChannelLook(channel: Channel) = saveChannelLook(channel, channel.defaultLook)

    /** The channels you made (the starters are always there until deleted). */
    val customChannels: StateFlow<List<Channel>> = ChannelLooks.custom

    /** Makes a channel on the phone; it gets its session with its first message. Returns it, to open it or to pick it. */
    fun createChannel(title: String, blurb: String, color: Int): Channel {
        val draft = Channel("new", title.ifBlank { "New channel" }, blurb, Channel.CUSTOM_INTRO, color, custom = true)
        return ChannelLooks.create(getApplication(), cleanLook(draft, title, blurb, color))
    }

    /** Forgets a channel you made. Its session is not deleted: it goes back to the ordinary sessions, under the channel's name. */
    fun deleteChannel(channel: Channel) {
        if (!channel.custom) return
        ChannelLooks.delete(getApplication(), channel)
        settings.setChannelChat(channel.id, null)
        _channelChats.value = settings.channelChats()
        if (_pendingChannel.value?.id == channel.id) _pendingChannel.value = null
    }

    /** Opens a channel: its session, or the first time an empty one that the channel will own. */
    fun openChannel(channel: Channel) {
        val bound = _channelChats.value[channel.id]
        if (bound != null) {
            openSession(bound)
            return
        }
        newSession()
        _pendingChannel.value = channel
    }

    private fun bindPendingChannel(chatId: String) {
        val channel = _pendingChannel.value ?: return
        if (_channelChats.value.containsValue(chatId)) return
        _pendingChannel.value = null
        settings.setChannelChat(channel.id, chatId)
        _channelChats.value = settings.channelChats()
        viewModelScope.launch {
            try {
                backend.rename(chatId, ChannelLooks.look(channel).title)
                refreshSessions()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Not worth a message: the channel works without it.
            }
        }
    }

    // ---- unread -------------------------------------------------------------------------------------------

    /** When you last had each session open (milliseconds), by id: what is newer than that is unread. */
    private val _chatSeen = MutableStateFlow(seenStore.seen())

    /** When the app first looked: a message from before that is not news. */
    private val seenBaseline: Long = seenStore.baseline()

    /** The sessions with a message you haven't opened: the drawer marks them. The one on screen never is. */
    val unreadChats: StateFlow<Set<String>> =
        combine(_sessions, _chatSeen, _currentChatId) { sessions, seen, current ->
            sessions.filter { it.chatId != current && hasNews(it, seen, emptyMap(), seenBaseline) }.map { it.chatId }.toSet()
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    /** [chatId] is being read (or was just written in): nothing in it is unread, and its notification goes away. */
    fun markChatSeen(chatId: String, ms: Long = System.currentTimeMillis()) {
        seenStore.markSeen(chatId, ms)
        _chatSeen.value = seenStore.seen()
        ChatAlerts.cancel(getApplication<Application>(), chatId)
    }

    /** A notification was tapped: the channel if the session is one, else the session. */
    fun openChatById(chatId: String?) {
        if (chatId == null) return
        val channel = channelOf(_channelChats.value, chatId, Channel.builtIn + ChannelLooks.custom.value)
        if (channel != null) openChannel(channel) else openSession(chatId)
    }

    /** Text to put in the message box when the chat screen is next shown. */
    private val _pendingDraft = MutableStateFlow<String?>(null)
    val pendingDraft: StateFlow<String?> = _pendingDraft.asStateFlow()

    fun prefillDraft(text: String) {
        _pendingDraft.value = text
    }

    fun clearPendingDraft() {
        _pendingDraft.value = null
    }

    // ---- turns ----------------------------------------------------------------------------------------------

    /** Stops showing whatever answer is on its way for the session on screen, and forgets it. */
    private fun leaveChat() {
        epoch++
        if (_busy.value) {
            // The running turn no longer reports back here (it sees the epoch changed), so this resets what it would have.
            stopRequested = true
            job?.cancel()
            _busy.value = false
            _step.value = ""
            AgentService.stop(getApplication<Application>())
        }
        publish(BlobState.Idle, snippet = "")
    }

    /**
     * Sends [text], with the files chosen for it ([withFiles]; a slash command never carries any). Does nothing while a
     * file is still being prepared.
     */
    fun send(text: String, withFiles: Boolean = true) {
        val prompt = text.trim()
        val files = if (withFiles && !prompt.startsWith("/")) _pending.value else emptyList()
        if ((prompt.isEmpty() && files.isEmpty()) || _busy.value || files.any { !it.ready }) return
        if (files.isNotEmpty()) _pending.value = emptyList()
        val media = files.mapNotNull { f -> f.dataUrl?.let { MediaPart(f.name, it) } }
        val sentMessage = ChatMessage(nextId++, Role.User, prompt, attachments = files.map { MessageAttachment(it.name, it.image, localPath = it.localPath) })
        val history = _messages.value.filter { !it.isError && (it.text.isNotEmpty() || it.attachments.isNotEmpty()) } + sentMessage
        val reply = ChatMessage(nextId++, Role.Assistant, "")
        _messages.value = history + reply
        store.save(_messages.value)
        _busy.value = true
        _step.value = ""
        stopRequested = false
        val myEpoch = epoch
        publish(BlobState.Thinking)
        // Keeps the app alive if the screen locks or you switch apps while the agent is answering.
        AgentService.start(getApplication<Application>())

        job = viewModelScope.launch {
            // False once the session on screen is another one: this reply then has nothing left to show.
            val here = { epoch == myEpoch }
            var answer = ""
            var reasoning = ""
            var started = false
            fun startedWriting() {
                if (!started) {
                    started = true
                    _step.value = ""
                    publish(BlobState.Working)
                }
            }
            try {
                backend.run(_currentChatId.value, TurnInput(prompt, media, sentMessage.attachments, activeChannel.value?.id, activeChannel.value?.let { ChannelLooks.look(it).let { l -> if (l.blurb.isBlank()) l.title else "${l.title} (${l.blurb})" } })).collect { event ->
                    if (!here()) return@collect
                    when (event) {
                        is TurnEvent.MessageAccepted -> if (_currentChatId.value == null) {
                            setCurrent(event.chatId)
                            bindPendingChannel(event.chatId)
                        }
                        is TurnEvent.Delta -> {
                            answer += event.text
                            if (answer.isNotEmpty()) startedWriting()
                            setReply(reply.id, answer)
                        }
                        is TurnEvent.Message -> {
                            answer = event.text
                            setReply(reply.id, answer)
                        }
                        is TurnEvent.ReasoningDelta -> {
                            reasoning += event.text
                            agentStep(StepKind.Thinking, reasoning)?.let { step -> setThinking(reply.id, step) }
                        }
                        is TurnEvent.ToolHint -> {
                            _step.value = event.text
                            if (_mood.value == BlobState.Thinking) publish(BlobState.Working)
                        }
                        is TurnEvent.Progress -> addStep(reply.id, event.step)
                        is TurnEvent.TurnEnd -> {
                            if (event.text.isNotEmpty()) answer = event.text
                            setReply(reply.id, answer)
                        }
                        is TurnEvent.Error -> throw ChatException(event.message)
                    }
                }
                if (here()) {
                    publish(BlobState.Finished, snippet = answer.take(SNIPPET_CHARS))
                    offerNotifications()
                    // The app is in the background: say the reply is here.
                    val chat = _currentChatId.value
                    if (!prompt.startsWith("/") && !ChatAlerts.appVisible && seenStore.enabled && chat != null) {
                        val title = activeChannel.value?.let { ChannelLooks.look(it).title }
                            ?: _sessions.value.firstOrNull { it.chatId == chat }?.title
                            ?: AgentProfile.name(getApplication())
                        ChatAlerts.show(getApplication<Application>(), title, chat, answer)
                    }
                }
            } catch (e: CancellationException) {
                if (here() && stopRequested) {
                    // You cut it off: keep what arrived; with nothing yet, the reply says "Stopped." (as it is saved).
                    _messages.update { list -> list.map { if (it.id == reply.id && it.text.isEmpty()) it.copy(text = "Stopped.") else it } }
                    publish(BlobState.Idle)
                }
                throw e
            } catch (e: Exception) {
                if (here()) {
                    val message = e.message ?: "Something went wrong."
                    val errorId = nextId++
                    _messages.update { list ->
                        val partial = list.firstOrNull { it.id == reply.id }?.text.orEmpty()
                        if (partial.isEmpty()) {
                            list.map { if (it.id == reply.id) it.copy(text = message, isError = true) else it }
                        } else {
                            // Part of the answer arrived: keep it, and say what went wrong after it.
                            list + ChatMessage(errorId, Role.Assistant, message, isError = true)
                        }
                    }
                    publish(BlobState.Error, snippet = message.take(SNIPPET_CHARS))
                }
            } finally {
                if (here()) {
                    _busy.value = false
                    _step.value = ""
                    store.save(_messages.value)
                    AgentService.stop(getApplication<Application>())
                    // The backend may have named the session now.
                    refreshSessions()
                }
            }
            if (here() && _mood.value == BlobState.Finished) {
                delay(FINISHED_MS)
                if (here() && !_busy.value && _mood.value == BlobState.Finished) publish(BlobState.Idle)
            }
        }
    }

    /** True after the first reply, while notifications were never asked for (PLAN §6.13): the chat asks then. */
    private val _askNotifications = MutableStateFlow(false)
    val askNotifications: StateFlow<Boolean> = _askNotifications.asStateFlow()

    private fun offerNotifications() {
        val app = getApplication<Application>()
        if (android.os.Build.VERSION.SDK_INT < 33 || io.github.erkanw.vault.data.BlobPrefs.notificationsAsked(app)) return
        if (app.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED) return
        _askNotifications.value = true
    }

    /** The question was answered (either way): it isn't asked again. */
    fun notificationsAnswered() {
        io.github.erkanw.vault.data.BlobPrefs.setNotificationsAsked(getApplication())
        _askNotifications.value = false
    }

    fun stop() {
        if (!_busy.value) return
        stopRequested = true
        job?.cancel()
    }

    private fun setReply(id: Long, text: String) {
        _messages.update { list -> list.map { if (it.id == id) it.copy(text = text) else it } }
    }

    private fun addStep(id: Long, step: AgentStep) {
        _messages.update { list -> list.map { if (it.id == id) it.copy(steps = withStep(it.steps, step)) else it } }
    }

    /** The reasoning streamed so far: one Thinking step that grows, after the steps before it. */
    private fun setThinking(id: Long, step: AgentStep) {
        _messages.update { list ->
            list.map {
                if (it.id != id) it
                // One thinking step, first, updated in place as reasoning streams across the turn's tool rounds (as saved).
                else {
                    val at = it.steps.indexOfFirst { s -> s.kind == StepKind.Thinking }
                    it.copy(steps = if (at >= 0) it.steps.toMutableList().also { l -> l[at] = step } else listOf(step) + it.steps)
                }
            }
        }
    }

    private fun publish(state: BlobState, snippet: String? = null) {
        _mood.value = state
        val app = getApplication<Application>()
        WidgetStatus.write(app, state, snippet)
        viewModelScope.launch(Dispatchers.Default) { BlobWidgetUpdater.updateAll(app) }
    }

    // ---- files of messages --------------------------------------------------------------------------------

    private val _openSaved = MutableSharedFlow<SavedFile>(extraBufferCapacity = 1)

    /** A file from the chat that has just been saved and should now be opened. */
    val openSaved: SharedFlow<SavedFile> = _openSaved.asSharedFlow()

    /**
     * A file of a message: one the in-app viewer can show as text opens there (pictures and videos are opened by the
     * chat itself); any other is saved in Downloads/Apel and handed to whatever app on the phone opens that kind of file.
     */
    fun openAttachment(file: MessageAttachment) {
        if (io.github.erkanw.vault.chat.textKindOf(file.name) != null) {
            _viewingFile.value = file
            return
        }
        _notice.value = "Saving ${file.name}…"
        viewModelScope.launch {
            try {
                val saved = saveToDownloads(file)
                _notice.value = "Saved ${saved.name} in Downloads/Apel"
                _openSaved.emit(saved)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _notice.value = "Couldn’t save ${file.name}: ${e.message ?: "the copy failed"}."
            }
        }
    }

    /** The file open in the in-app text viewer, or null. */
    private val _viewingFile = MutableStateFlow<MessageAttachment?>(null)
    val viewingFile: StateFlow<MessageAttachment?> = _viewingFile.asStateFlow()

    fun closeFile() { _viewingFile.value = null }

    /** The text of [file] for the viewer (at most VIEWER_MAX_BYTES, and whether it was cut); null when it isn't text. */
    suspend fun readFileText(file: MessageAttachment): Pair<String, Boolean>? = withContext(Dispatchers.IO) {
        val (bytes, total) = MediaLoader.readHead(null, file, io.github.erkanw.vault.chat.VIEWER_MAX_BYTES + 4)
            ?: throw java.io.IOException("The file isn’t on this phone any more")
        io.github.erkanw.vault.chat.decodeForViewer(bytes, total)
    }

    /** Saves [file] in Downloads/Apel from the copy on the phone and returns it; throws if it can't. */
    suspend fun saveToDownloads(file: MessageAttachment): SavedFile = withContext(Dispatchers.IO) {
        val local = file.localPath?.takeIf { java.io.File(it).exists() }
            ?: throw java.io.IOException("the file isn’t on this phone any more")
        val fileName = file.name.substringAfterLast('/').ifBlank { "file" }
        val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(fileName.substringAfterLast('.', "").lowercase()) ?: mimeOf(fileName)
        saveInDownloads(getApplication(), fileName, mime) { out -> java.io.File(local).inputStream().use { it.copyTo(out) } }
    }

    private companion object {
        const val SNIPPET_CHARS = 160
        const val FINISHED_MS = 4_000L
    }
}

/**
 * Writes a file into Downloads/Apel through the MediaStore with [write], and returns it. A half-written file is not kept.
 */
fun saveInDownloads(app: Application, fileName: String, mime: String, write: (java.io.OutputStream) -> Unit): SavedFile {
    val resolver = app.contentResolver
    var target: Uri? = null
    try {
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(android.provider.MediaStore.Downloads.MIME_TYPE, mime)
            put(android.provider.MediaStore.Downloads.RELATIVE_PATH, DOWNLOADS_FOLDER)
            put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw java.io.IOException("The phone wouldn’t make the file in Downloads.")
        target = uri
        val out = resolver.openOutputStream(uri) ?: throw java.io.IOException("The phone wouldn’t open the file in Downloads.")
        out.use(write)
        resolver.update(uri, android.content.ContentValues().apply { put(android.provider.MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        return SavedFile(fileName, uri, mime)
    } catch (e: Exception) {
        target?.let { runCatching { resolver.delete(it, null, null) } }
        throw e
    }
}
