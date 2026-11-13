package io.github.erkanw.vault.agent

import io.github.erkanw.vault.chat.AutomationJob
import io.github.erkanw.vault.chat.BuiltInTool
import io.github.erkanw.vault.chat.ChatException
import io.github.erkanw.vault.chat.FileListing
import io.github.erkanw.vault.chat.IngressLimits
import io.github.erkanw.vault.chat.LOCAL_COMMANDS
import io.github.erkanw.vault.chat.McpServer
import io.github.erkanw.vault.chat.ModelChoices
import io.github.erkanw.vault.chat.SessionStats
import io.github.erkanw.vault.chat.SessionSummary
import io.github.erkanw.vault.chat.SkillInfo
import io.github.erkanw.vault.chat.SlashCommand
import io.github.erkanw.vault.chat.ThreadPage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.json.JSONObject
import java.io.OutputStream

/**
 * The backend while nothing is connected: empty lists, and a turn that ends at once with the one thing to do. It stands
 * in for the on-device runtime until Phase 1 builds it.
 */
class OfflineBackend(private val notConnected: String) : AgentBackend {
    override val connected: Boolean = false

    private fun refuse(): Nothing = throw ChatException(notConnected)

    override suspend fun sessions(): List<SessionSummary> = emptyList()
    override suspend fun thread(chatId: String): ThreadPage? = null
    override suspend fun sessionStats(chatId: String): SessionStats? = null
    override suspend fun rename(chatId: String, title: String) = refuse()
    override suspend fun setPinned(chatId: String, pinned: Boolean) = refuse()
    override suspend fun setArchived(chatId: String, archived: Boolean) = refuse()
    override suspend fun deleteSession(chatId: String) = refuse()
    override suspend fun forkChat(sourceChatId: String, beforeUserIndex: Int, title: String): String = refuse()
    override suspend fun setSessionModel(chatId: String?, preset: String) = refuse()

    override fun run(chatId: String?, input: TurnInput): Flow<TurnEvent> = flowOf(TurnEvent.Error(notConnected))

    override suspend fun limits(): IngressLimits = IngressLimits()
    override suspend fun commands(): List<SlashCommand> = LOCAL_COMMANDS

    override suspend fun models(): ModelChoices = ModelChoices.None
    override suspend fun createModel(name: String, model: String, maxTokens: Long, contextTokens: Long) = refuse()
    override suspend fun updateModel(name: String, maxTokens: Long, contextTokens: Long) = refuse()
    override suspend fun deleteModel(name: String) = refuse()
    override suspend fun setModelOrder(order: List<String>) = refuse()

    override suspend fun files(path: String): FileListing = FileListing(path, emptyList())
    override suspend fun download(path: String, zip: Boolean, out: OutputStream): Long = refuse()

    override suspend fun skills(): List<SkillInfo> = emptyList()
    override suspend fun setSkillEnabled(name: String, enabled: Boolean) = refuse()
    override suspend fun deleteSkill(name: String) = refuse()

    override suspend fun builtInTools(): List<BuiltInTool> = emptyList()
    override suspend fun mcpServers(): List<McpServer> = emptyList()
    override suspend fun setBuiltInTool(path: String, on: Boolean) = refuse()
    override suspend fun setMcpTools(server: String, enabledTools: List<String>) = refuse()

    override suspend fun automations(): List<AutomationJob> = emptyList()
    override suspend fun automationAction(id: String, action: String) = refuse()
    override suspend fun automationUpdate(id: String, values: JSONObject) = refuse()
}
