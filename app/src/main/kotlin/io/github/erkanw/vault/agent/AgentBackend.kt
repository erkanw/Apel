package io.github.erkanw.vault.agent

import io.github.erkanw.vault.chat.AutomationJob
import io.github.erkanw.vault.chat.BuiltInTool
import io.github.erkanw.vault.chat.FileListing
import io.github.erkanw.vault.chat.IngressLimits
import io.github.erkanw.vault.chat.McpServer
import io.github.erkanw.vault.chat.MediaPart
import io.github.erkanw.vault.chat.ModelChoices
import io.github.erkanw.vault.chat.SessionStats
import io.github.erkanw.vault.chat.SessionSummary
import io.github.erkanw.vault.chat.SkillInfo
import io.github.erkanw.vault.chat.SlashCommand
import io.github.erkanw.vault.chat.ThreadPage
import kotlinx.coroutines.flow.Flow
import org.json.JSONObject
import java.io.OutputStream

/** One message to the agent: its text and the files that go with it, and the channel it was written in. */
data class TurnInput(
    val text: String,
    val media: List<MediaPart> = emptyList(),
    /** The same files as kept on the phone (their copies), to show with the message later. */
    val attachments: List<io.github.erkanw.vault.chat.MessageAttachment> = emptyList(),
    val channelId: String? = null,
    /** The channel's description, for the system prompt. */
    val channelNote: String? = null,
)

/**
 * Everything the screens ask of the agent (PLAN §5, "the migration trick"). Phase 0 has two: a demo one in debug builds
 * and [OfflineBackend]; the on-device runtime implements it from Phase 1. Every call may throw with a message that can be
 * shown as it is.
 */
interface AgentBackend {
    /** False while no provider is connected: pages say so instead of loading. */
    val connected: Boolean

    // ---- sessions ----
    suspend fun sessions(): List<SessionSummary>
    suspend fun thread(chatId: String): ThreadPage?
    suspend fun sessionStats(chatId: String): SessionStats?
    suspend fun rename(chatId: String, title: String)
    suspend fun setPinned(chatId: String, pinned: Boolean)
    suspend fun setArchived(chatId: String, archived: Boolean)
    suspend fun deleteSession(chatId: String)

    /** Copies [sourceChatId] up to just before your message number [beforeUserIndex] (from 0) into a new session; its id. */
    suspend fun forkChat(sourceChatId: String, beforeUserIndex: Int, title: String): String

    /** The model preset [chatId] runs with from now on (null: the next new session). */
    suspend fun setSessionModel(chatId: String?, preset: String)

    // ---- turns ----

    /** Runs one turn in [chatId] (null: a new session). Cancelling the flow stops the turn. */
    fun run(chatId: String?, input: TurnInput): Flow<TurnEvent>

    // ---- the composer ----
    suspend fun limits(): IngressLimits
    suspend fun commands(): List<SlashCommand>

    // ---- models ----
    suspend fun models(): ModelChoices
    suspend fun createModel(name: String, model: String, maxTokens: Long, contextTokens: Long)
    suspend fun updateModel(name: String, maxTokens: Long, contextTokens: Long)
    suspend fun deleteModel(name: String)
    suspend fun setModelOrder(order: List<String>)

    // ---- files (the workspace) ----
    suspend fun files(path: String): FileListing

    /** Writes the file at [path] (or the folder, zipped, when [zip]) to [out]; the number of bytes. */
    suspend fun download(path: String, zip: Boolean, out: OutputStream): Long

    // ---- skills ----
    suspend fun skills(): List<SkillInfo>
    suspend fun setSkillEnabled(name: String, enabled: Boolean)
    suspend fun deleteSkill(name: String)

    // ---- tools ----
    suspend fun builtInTools(): List<BuiltInTool>
    suspend fun mcpServers(): List<McpServer>
    suspend fun setBuiltInTool(path: String, on: Boolean)

    /** Sets a built-in tool’s level; a backend without levels just switches it on or off. */
    suspend fun setToolLevel(path: String, level: io.github.erkanw.vault.tools.ToolLevel) = setBuiltInTool(path, level != io.github.erkanw.vault.tools.ToolLevel.Off)
    suspend fun setMcpTools(server: String, enabledTools: List<String>)

    // ---- automations ----
    suspend fun automations(): List<AutomationJob>

    /** [action]: `enable`, `disable`, `run` (now) or `delete`. */
    suspend fun automationAction(id: String, action: String)

    /** Saves an edited automation: `name`, `message` and, when it changes, `schedule`. */
    suspend fun automationUpdate(id: String, values: JSONObject)
}
