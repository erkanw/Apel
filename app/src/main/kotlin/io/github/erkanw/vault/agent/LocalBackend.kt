package io.github.erkanw.vault.agent

import android.content.Context
import io.github.erkanw.vault.R
import io.github.erkanw.vault.chat.AgentStep
import io.github.erkanw.vault.chat.AutomationJob
import io.github.erkanw.vault.chat.BuiltInTool
import io.github.erkanw.vault.chat.ChatException
import io.github.erkanw.vault.chat.ChatMessage
import io.github.erkanw.vault.chat.FileListing
import io.github.erkanw.vault.chat.IngressLimits
import io.github.erkanw.vault.chat.LOCAL_COMMANDS
import io.github.erkanw.vault.chat.McpServer
import io.github.erkanw.vault.chat.MessageAttachment
import io.github.erkanw.vault.chat.ModelChoices
import io.github.erkanw.vault.chat.ModelPreset
import io.github.erkanw.vault.chat.ModelsHub
import io.github.erkanw.vault.chat.NEW_SESSION_TITLE
import io.github.erkanw.vault.chat.Role
import io.github.erkanw.vault.chat.SessionStats
import io.github.erkanw.vault.chat.SessionSummary
import io.github.erkanw.vault.chat.SkillInfo
import io.github.erkanw.vault.chat.SlashCommand
import io.github.erkanw.vault.chat.StepKind
import io.github.erkanw.vault.chat.ThinkFilter
import io.github.erkanw.vault.chat.ThreadPage
import io.github.erkanw.vault.chat.statsOf
import io.github.erkanw.vault.provider.ChatRequest
import io.github.erkanw.vault.provider.ProviderClient
import io.github.erkanw.vault.provider.ProviderException
import io.github.erkanw.vault.provider.ProviderKind
import io.github.erkanw.vault.provider.ProviderStore
import io.github.erkanw.vault.provider.StreamPart
import io.github.erkanw.vault.store.AgentProfile
import io.github.erkanw.vault.store.AppDatabase
import io.github.erkanw.vault.store.AttachmentEntity
import io.github.erkanw.vault.store.MessageEntity
import io.github.erkanw.vault.store.MessageWithParts
import io.github.erkanw.vault.store.ModelStore
import io.github.erkanw.vault.store.SessionEntity
import io.github.erkanw.vault.store.StepEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.time.ZonedDateTime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The on-device agent (PLAN §7), Phase 1: plain chat. Sessions and messages are in Room; a turn streams one answer from
 * the chosen provider, retrying once on the next model when the first fails in a way another model could fix. Tools,
 * skills, files and automations arrive in later phases; until then their pages are empty.
 */
class LocalBackend(context: Context) : AgentBackend {
    private val app = context.applicationContext
    private val db = AppDatabase.get(app).chats()
    private val models = ModelStore(app)

    /** Sessions with a turn running now. */
    private val running = ConcurrentHashMap.newKeySet<String>()

    /** A model chosen in a chat that has no session yet: the next session starts with it. */
    @Volatile private var pendingPreset: String? = null

    init {
        ProviderStore.ensureLoaded(app)
    }

    override val connected: Boolean get() = ProviderStore.state.value.connected

    private fun notConnected(): Nothing = throw ChatException(app.getString(R.string.not_connected))
    private fun later(): Nothing = throw ChatException("This comes in a later version.")

    // ---- sessions ----

    override suspend fun sessions(): List<SessionSummary> = db.sessions().map { row ->
        val s = row.session
        SessionSummary(
            chatId = s.id, key = s.id, title = s.title.ifBlank { NEW_SESSION_TITLE }, preview = row.preview.orEmpty().take(200),
            updatedMs = s.updated, running = s.id in running, pinned = s.pinned, archived = s.archived,
            modelPreset = s.modelOverride, createdMs = s.created,
        )
    }.sortedWith(compareByDescending<SessionSummary> { it.pinned }.thenByDescending { it.updatedMs })

    override suspend fun thread(chatId: String): ThreadPage? {
        val session = db.session(chatId) ?: return null
        return ThreadPage(db.messages(chatId).map(::toChatMessage), 0, session.forkBoundary)
    }

    override suspend fun sessionStats(chatId: String): SessionStats? {
        val parts = db.messages(chatId)
        if (parts.isEmpty()) return null
        val stats = statsOf(parts.map(::toChatMessage))
        // What the model actually held on the last turn: its prompt plus its answer, as the provider counted them.
        val last = parts.lastOrNull { it.message.role == "assistant" && it.message.tokensIn > 0 }?.message
        return if (last == null) stats else stats.copy(estimatedTokens = (last.tokensIn + last.tokensOut).toInt())
    }

    override suspend fun rename(chatId: String, title: String) {
        if (title.isBlank()) {
            val first = db.messages(chatId).firstOrNull { it.message.role == "user" }?.message?.text.orEmpty()
            db.setTitle(chatId, titleFrom(first).ifBlank { NEW_SESSION_TITLE }, userSet = false)
        } else {
            db.setTitle(chatId, title.trim().take(80), userSet = true)
        }
    }

    override suspend fun setPinned(chatId: String, pinned: Boolean) = db.setPinned(chatId, pinned)
    override suspend fun setArchived(chatId: String, archived: Boolean) = db.setArchived(chatId, archived)
    override suspend fun deleteSession(chatId: String) = db.deleteSession(chatId)

    override suspend fun forkChat(sourceChatId: String, beforeUserIndex: Int, title: String): String {
        val source = db.session(sourceChatId) ?: throw ChatException("That chat is gone.")
        val messages = db.messages(sourceChatId)
        var users = 0
        val cut = messages.indexOfFirst { it.message.role == "user" && users++ == beforeUserIndex }.let { if (it < 0) messages.size else it }
        val kept = messages.take(cut)
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        db.insertSession(SessionEntity(id, title, now, now, titleSet = true, channelId = null, modelOverride = source.modelOverride, forkBoundary = kept.size))
        for (m in kept) copyMessage(m, id)
        return id
    }

    private suspend fun copyMessage(m: MessageWithParts, sessionId: String) {
        val newId = db.insertMessage(m.message.copy(id = 0, sessionId = sessionId, forkedFrom = m.message.id))
        if (m.attachments.isNotEmpty()) db.insertAttachments(m.attachments.map { it.copy(id = 0, messageId = newId) })
        if (m.steps.isNotEmpty()) db.insertSteps(m.steps.map { it.copy(messageId = newId) })
    }

    override suspend fun setSessionModel(chatId: String?, preset: String) {
        if (chatId != null && db.session(chatId) != null) db.setModel(chatId, preset) else pendingPreset = preset
    }

    // ---- turns ----

    override fun run(chatId: String?, input: TurnInput): Flow<TurnEvent> = flow {
        val target = ProviderStore.target(app)
        if (target == null) {
            emit(TurnEvent.Error(app.getString(R.string.not_connected)))
            return@flow
        }
        val now = System.currentTimeMillis()
        val session = chatId?.let { db.session(it) } ?: SessionEntity(
            id = UUID.randomUUID().toString(), title = titleFrom(input.text).ifBlank { NEW_SESSION_TITLE }, created = now, updated = now,
            channelId = input.channelId, modelOverride = pendingPreset,
        ).also {
            db.insertSession(it)
            pendingPreset = null
        }
        val history = db.messages(session.id)
        val userId = db.insertMessage(MessageEntity(sessionId = session.id, role = "user", text = input.text, created = now))
        if (input.attachments.isNotEmpty()) {
            db.insertAttachments(input.attachments.map { a ->
                val path = a.localPath
                AttachmentEntity(
                    messageId = userId, kind = if (a.image) "image" else if (a.video) "video" else "file", name = a.name,
                    mime = io.github.erkanw.vault.chat.mimeOf(a.name), path = path, size = path?.let { File(it).length() } ?: 0L,
                )
            })
        }
        db.touch(session.id, now)
        emit(TurnEvent.MessageAccepted(session.id))

        // A slash command the app answers itself: no model call.
        if (input.text.startsWith("/")) {
            val word = input.text.trim().substringBefore(' ').lowercase()
            val answer = if (word == "/help") {
                LOCAL_COMMANDS_NOW.joinToString("\n") { "- **${it.name}** ${it.description.replaceFirstChar { c -> c.lowercase() }}" }
            } else {
                "That command isn’t available. Type / to see the commands."
            }
            db.insertMessage(MessageEntity(sessionId = session.id, role = "assistant", text = answer, created = System.currentTimeMillis()))
            emit(TurnEvent.TurnEnd(answer))
            return@flow
        }

        val chain = buildList {
            models.preset(session.modelOverride)?.let { add(it) }
            models.callChain().forEach { p -> if (none { it.name == p.name }) add(p) }
        }
        if (chain.isEmpty()) {
            val message = "No model chosen yet. Add one in Settings, Models."
            saveAssistant(session.id, message, null, 0, 0, null, "error", emptyList())
            emit(TurnEvent.Error(message))
            return@flow
        }

        // A picture for a model that reads only text: say so before anything is sent.
        val first = chain.first()
        if (input.media.any { it.dataUrl.startsWith("data:image/") } && !reads(first.model, "image")) {
            val message = "${first.label} can’t read pictures. Pick another model in the chat’s model list."
            saveAssistant(session.id, message, first.model, 0, 0, null, "error", emptyList())
            emit(TurnEvent.Error(message))
            return@flow
        }

        val state = ProviderStore.state.value
        val openRouter = target.kind == ProviderKind.OpenRouter
        val content = userContent(input.text, input.media, pdfAsFile = openRouter) { name, bytes -> saveDownload(name, bytes) }
        val steps = ArrayList<AgentStep>()
        var raw = ""
        var reasoning = ""
        var usage: StreamPart.Usage? = null
        var used: ModelPreset = first
        running += session.id
        try {
            for ((i, preset) in chain.take(2).withIndex()) {
                used = preset
                val messages = JSONArray()
                    .put(JSONObject().put("role", "system").put("content", systemPrompt(AgentProfile.name(app), ZonedDateTime.now(), input.channelNote)))
                history.filter { keep(it) }.forEach { h -> messages.put(JSONObject().put("role", h.message.role).put("content", historyText(h))) }
                messages.put(JSONObject().put("role", "user").put("content", content))
                val request = ChatRequest(
                    model = preset.model, messages = messages, maxTokens = preset.maxTokens,
                    privateOnly = openRouter && state.privateOnly && preset.model !in state.privateExempt,
                )
                try {
                    ProviderClient.stream(target, request).collect { part ->
                        when (part) {
                            is StreamPart.Text -> {
                                raw += part.text
                                emit(TurnEvent.Message(ThinkFilter.visible(raw)))
                            }
                            is StreamPart.Reasoning -> {
                                reasoning += part.text
                                emit(TurnEvent.ReasoningDelta(part.text))
                            }
                            is StreamPart.Usage -> usage = part
                        }
                    }
                    break
                } catch (e: ProviderException) {
                    val next = chain.getOrNull(i + 1)
                    if (!e.fallback || raw.isNotEmpty() || next == null || i > 0) throw e
                    val note = AgentStep(StepKind.Note, "${preset.label} didn’t answer (${e.message}), so ${next.label} is answering.")
                    steps += note
                    emit(TurnEvent.Progress(note))
                }
            }
            if (reasoning.isNotBlank()) steps.add(0, AgentStep(StepKind.Thinking, reasoning.trim().take(io.github.erkanw.vault.chat.MAX_STEP_CHARS)))
            val answer = ThinkFilter.visible(raw).trim()
            val u = usage
            if (answer.isEmpty()) {
                val message = "${used.label} sent an empty answer. Try again, or pick another model."
                saveAssistant(session.id, message, used.model, u?.promptTokens ?: 0, u?.completionTokens ?: 0, u?.cost, "error", steps)
                emit(TurnEvent.Error(message))
            } else {
                saveAssistant(session.id, answer, used.model, u?.promptTokens ?: 0, u?.completionTokens ?: 0, u?.cost, "ok", steps)
                emit(TurnEvent.TurnEnd(answer))
            }
        } catch (e: CancellationException) {
            // Stopped: what arrived is kept, marked as stopped.
            val partial = ThinkFilter.visible(raw).trim()
            withContext(NonCancellable) {
                if (partial.isNotEmpty()) saveAssistant(session.id, partial, used.model, 0, 0, null, "stopped", steps)
            }
            throw e
        } catch (e: ProviderException) {
            val message = e.message ?: "The provider failed."
            saveAssistant(session.id, message, used.model, 0, 0, null, "error", steps)
            emit(TurnEvent.Error(message))
        } finally {
            running -= session.id
        }
    }

    /** Whether [model] reads [input] ("image", "file"), as far as the catalogue knows; unknown models get the benefit of the doubt. */
    private fun reads(model: String, input: String): Boolean {
        val known = ModelsHub.catalog.value.models.firstOrNull { it.id == model } ?: return true
        return known.inputs.isEmpty() || input in known.inputs
    }

    private fun keep(m: MessageWithParts): Boolean = m.message.status != "error" && (m.message.text.isNotBlank() || m.attachments.isNotEmpty())

    /** A past message as the model sees it again: its text, and a line for each file (files aren't sent twice). */
    private fun historyText(m: MessageWithParts): String {
        if (m.attachments.isEmpty()) return m.message.text
        val files = m.attachments.joinToString(", ") { "“${it.name}”" }
        return (m.message.text + "\n\n(Attached earlier: $files.)").trim()
    }

    private suspend fun saveAssistant(sessionId: String, text: String, model: String?, tokensIn: Long, tokensOut: Long, cost: Double?, status: String, steps: List<AgentStep>) {
        val id = db.insertMessage(
            MessageEntity(sessionId = sessionId, role = "assistant", text = text, created = System.currentTimeMillis(), model = model,
                tokensIn = tokensIn, tokensOut = tokensOut, cost = cost, status = status),
        )
        if (steps.isNotEmpty()) db.insertSteps(steps.mapIndexed { i, s -> StepEntity(id, i, stepKindName(s.kind), s.text) })
        db.touch(sessionId, System.currentTimeMillis())
    }

    /** A file the model can't take as it is: saved in the workspace's downloads folder; returns its workspace path. */
    private fun saveDownload(name: String, bytes: ByteArray): String? = try {
        val dir = File(app.filesDir, "workspace/downloads").apply { mkdirs() }
        val safe = name.substringAfterLast('/').replace(Regex("""[^\w.\- ]"""), "_").ifBlank { "file" }
        var file = File(dir, safe)
        var n = 2
        while (file.exists()) file = File(dir, safe.substringBeforeLast('.') + " ($n)" + (if (safe.contains('.')) "." + safe.substringAfterLast('.') else "")).also { n++ }
        file.writeBytes(bytes)
        "downloads/${file.name}"
    } catch (e: Exception) {
        null
    }

    private fun toChatMessage(m: MessageWithParts): ChatMessage {
        val e = m.message
        return ChatMessage(
            id = e.id, role = if (e.role == "user") Role.User else Role.Assistant, text = e.text, isError = e.status == "error", createdAt = e.created,
            attachments = m.attachments.map { a -> MessageAttachment(a.name, image = a.kind == "image", video = a.kind == "video", localPath = a.path) },
            steps = m.steps.sortedBy { it.index }.map { s -> AgentStep(stepKindOf(s.kind), s.title) },
        )
    }

    // ---- the composer ----

    override suspend fun limits(): IngressLimits = IngressLimits()
    override suspend fun commands(): List<SlashCommand> = LOCAL_COMMANDS_NOW

    // ---- models ----

    override suspend fun models(): ModelChoices = models.choices()

    override suspend fun createModel(name: String, model: String, maxTokens: Long, contextTokens: Long) {
        if (!connected) notConnected()
        val label = ModelsHub.catalog.value.models.firstOrNull { it.id == model }?.name?.substringAfter(": ") ?: io.github.erkanw.vault.chat.prettyModelName(model)
        try {
            models.add(ModelPreset(name, label, model, false, contextTokens = contextTokens, maxTokens = maxTokens))
        } catch (e: IllegalArgumentException) {
            throw ChatException(e.message ?: "That name is taken.")
        }
    }

    override suspend fun updateModel(name: String, maxTokens: Long, contextTokens: Long) = models.update(name, maxTokens, contextTokens)
    override suspend fun deleteModel(name: String) = models.remove(name)
    override suspend fun setModelOrder(order: List<String>) = models.setOrder(order)

    // ---- later phases ----

    override suspend fun files(path: String): FileListing = FileListing(path, emptyList())
    override suspend fun download(path: String, zip: Boolean, out: OutputStream): Long = later()
    override suspend fun skills(): List<SkillInfo> = emptyList()
    override suspend fun setSkillEnabled(name: String, enabled: Boolean) = later()
    override suspend fun deleteSkill(name: String) = later()
    override suspend fun builtInTools(): List<BuiltInTool> = emptyList()
    override suspend fun mcpServers(): List<McpServer> = emptyList()
    override suspend fun setBuiltInTool(path: String, on: Boolean) = later()
    override suspend fun setMcpTools(server: String, enabledTools: List<String>) = later()
    override suspend fun automations(): List<AutomationJob> = emptyList()
    override suspend fun automationAction(id: String, action: String) = later()
    override suspend fun automationUpdate(id: String, values: JSONObject) = later()

    private companion object {
        /** The commands that work in this version. */
        val LOCAL_COMMANDS_NOW = LOCAL_COMMANDS.filter { it.name in setOf("/new", "/stop", "/model", "/help") }

        fun stepKindName(kind: StepKind) = when (kind) {
            StepKind.Tool -> "tool"
            StepKind.Note -> "progress"
            StepKind.Thinking -> "reasoning"
        }

        fun stepKindOf(name: String) = when (name) {
            "tool" -> StepKind.Tool
            "reasoning" -> StepKind.Thinking
            else -> StepKind.Note
        }
    }
}
