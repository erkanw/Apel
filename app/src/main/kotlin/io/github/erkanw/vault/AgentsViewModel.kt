package io.github.erkanw.vault

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.erkanw.vault.agent.AgentPresets
import io.github.erkanw.vault.chat.ModelPreset
import io.github.erkanw.vault.mascot.BlobLook
import io.github.erkanw.vault.store.AgentInfo
import io.github.erkanw.vault.store.Agents
import io.github.erkanw.vault.store.ModelStore
import io.github.erkanw.vault.tools.McpStore
import io.github.erkanw.vault.tools.MemoryTools
import io.github.erkanw.vault.tools.SkillStore
import io.github.erkanw.vault.tools.ToolRegistry
import io.github.erkanw.vault.userdb.Access
import io.github.erkanw.vault.userdb.UserDbs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A switch on an agent's page: a tool or a skill, on or off for this agent. */
data class AgentSwitch(val id: String, val title: String, val subtitle: String, val on: Boolean)

/** A database on an agent's page: what the database itself allows, and what this agent may do there. */
data class AgentDb(val name: String, val own: Access, val effective: Access)

/** What an agent's page shows besides the agent itself. */
data class AgentDetail(
    val tools: List<AgentSwitch> = emptyList(),
    val skills: List<AgentSwitch> = emptyList(),
    val databases: List<AgentDb> = emptyList(),
    val jobs: List<String> = emptyList(),
    val memoryNotes: Int = 0,
    val memoryText: String = "",
)

/** The agents area (PLAN §6.16): the list, New agent, and one agent's page. */
class AgentsViewModel(app: Application) : AndroidViewModel(app) {
    val agents: StateFlow<List<AgentInfo>> = Agents.list
    val selected: StateFlow<String?> = Agents.selectedFlow(app)

    private val _detail = MutableStateFlow(AgentDetail())
    val detail: StateFlow<AgentDetail> = _detail.asStateFlow()

    init {
        viewModelScope.launch { Agents.ensure(app) }
    }

    /** The model presets an agent can be set to (hidden ones too: an agent may want one off the chat's list). */
    fun presets(): List<ModelPreset> = ModelStore(getApplication()).load().presets

    fun select(id: String) = Agents.select(getApplication(), id)

    fun create(name: String, template: String?, onDone: (String) -> Unit) {
        viewModelScope.launch { onDone(Agents.create(getApplication(), name, template)) }
    }

    fun duplicate(id: String, onDone: (String) -> Unit) {
        viewModelScope.launch { Agents.duplicate(getApplication(), id)?.let(onDone) }
    }

    fun delete(id: String) {
        viewModelScope.launch { Agents.delete(getApplication(), id) }
    }

    fun rename(id: String, name: String) {
        viewModelScope.launch { Agents.rename(getApplication(), id, name) }
    }

    fun setLook(id: String, look: BlobLook) {
        viewModelScope.launch {
            Agents.setLook(getApplication(), id, look)
            if (id == AgentPresets.APEL) io.github.erkanw.vault.widget.WidgetRedraw.request(getApplication())
        }
    }

    fun setModel(id: String, preset: String?) {
        viewModelScope.launch { Agents.update(getApplication(), id) { it.copy(model = preset) } }
    }

    /** The prompt as written; the default (or blank) is kept as null, so the default follows the agent's name. */
    fun setPrompt(id: String, name: String, text: String) {
        val clean = text.trim().take(AgentPresets.MAX_PROMPT)
        val stored = clean.takeIf { it.isNotEmpty() && it != AgentPresets.defaultPrompt(name) }
        viewModelScope.launch { Agents.update(getApplication(), id) { it.copy(prompt = stored) } }
    }

    fun setTool(id: String, tool: String, on: Boolean) = change(id) { row ->
        val off = AgentPresets.setOf(row.toolsOff).let { if (on) it - tool else it + tool }
        row.copy(toolsOff = AgentPresets.setJson(off))
    }

    fun setSkill(id: String, skill: String, on: Boolean) = change(id) { row ->
        val off = AgentPresets.setOf(row.skillsOff).let { if (on) it - skill else it + skill }
        row.copy(skillsOff = AgentPresets.setJson(off))
    }

    /** [access] at most what the database allows; the database's own access means "no cap". */
    fun setDb(id: String, db: AgentDb, access: Access) = change(id) { row ->
        val caps = AgentPresets.capsOf(row.dbCaps).let { if (access.ordinal >= db.own.ordinal) it - db.name else it + (db.name to access) }
        row.copy(dbCaps = AgentPresets.capsJson(caps))
    }

    private fun change(id: String, block: (io.github.erkanw.vault.store.AgentEntity) -> io.github.erkanw.vault.store.AgentEntity) {
        viewModelScope.launch {
            Agents.update(getApplication(), id, block)
            load(id)
        }
    }

    fun clearMemory(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            MemoryTools.file(getApplication(), id).delete()
            load(id)
        }
    }

    fun saveMemory(id: String, text: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val f = MemoryTools.file(getApplication(), id)
            f.parentFile?.mkdirs()
            f.writeText(text.trim().let { if (it.isEmpty()) it else it + "\n" })
            load(id)
        }
    }

    /** Reads what [id]'s page shows: its tools, skills, databases, jobs and memory. */
    fun load(id: String) {
        viewModelScope.launch {
            _detail.value = withContext(Dispatchers.IO) {
                val app = getApplication<Application>()
                val scope = Agents.scope(app, id)
                val tools = (ToolRegistry.tools.map { Triple(it.name, it.title, it.description) } +
                    McpStore.agentTools(app).map { Triple(it.name, io.github.erkanw.vault.tools.readableToolName(it.title), it.description) })
                    .filter { (name, _, _) -> ToolRegistry.level(app, name) != io.github.erkanw.vault.tools.ToolLevel.Off }
                    .map { (name, title, sub) -> AgentSwitch(name, title, sub, scope.toolOn(name)) }
                val skills = SkillStore.all(app).filter { it.enabled }.map { AgentSwitch(it.name, it.name, it.description, scope.skillOn(it.name)) }
                val dbs = runCatching { UserDbs.core(app).agentVisible() }.getOrDefault(emptyList())
                    .map { AgentDb(it.name, it.access, scope.access(it.name, it.access)) }
                val jobs = io.github.erkanw.vault.automation.Automations.all(app)
                    .filter { (it.agentId ?: AgentPresets.APEL) == id && it.builtin == null }.map { it.name }
                val memory = MemoryTools.file(app, id).takeIf { it.isFile }?.readText().orEmpty()
                AgentDetail(tools, skills, dbs, jobs, memory.lines().count { it.trimStart().startsWith("- ") }, memory)
            }
        }
    }
}
