package io.github.erkanw.vault

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.erkanw.vault.agent.AgentBackend
import io.github.erkanw.vault.chat.AutomationJob
import io.github.erkanw.vault.chat.BuiltInTool
import io.github.erkanw.vault.chat.McpServer
import io.github.erkanw.vault.chat.SkillInfo
import io.github.erkanw.vault.chat.describeSchedule
import io.github.erkanw.vault.chat.mimeOf
import io.github.erkanw.vault.chat.parentPath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

private val PICTURE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp")
private val TEXT_EXTENSIONS = setOf(
    "txt", "md", "log", "json", "csv", "tsv", "html", "htm", "css", "js", "mjs", "xml", "yml", "yaml", "toml", "ini", "cfg", "py", "kt", "sh",
)
private const val MAX_PREVIEW_TEXT = 512 * 1024L
private const val MAX_PREVIEW_PICTURE = 25L * 1024 * 1024

private class PreviewTooBig : java.io.IOException("too big to preview")

/** Passes bytes on to [inner] until [max] is passed, then fails, so a huge file is never pulled into memory. */
private class LimitedOutput(private val inner: java.io.OutputStream, private val max: Long) : java.io.OutputStream() {
    private var written = 0L
    override fun write(b: Int) {
        if (++written > max) throw PreviewTooBig()
        inner.write(b)
    }
    override fun write(b: ByteArray, off: Int, len: Int) {
        written += len
        if (written > max) throw PreviewTooBig()
        inner.write(b, off, len)
    }
}

/** The pages area (PLAN §5): Files, Skills, Automations and Tools. Databases join it in Phase 6. */
class PagesViewModel(app: Application) : AndroidViewModel(app) {
    private val backend: AgentBackend = AppGraph.backend(app)

    /** What a page says while nothing is connected. */
    private val notConnected: String = app.getString(R.string.not_connected)

    // ---- Skills and Automations ----------------------------------------------------------------------

    private val _skills = MutableStateFlow(PageState<SkillInfo>())
    val skills: StateFlow<PageState<SkillInfo>> = _skills.asStateFlow()

    private val _automations = MutableStateFlow(PageState<AutomationJob>())
    val automations: StateFlow<PageState<AutomationJob>> = _automations.asStateFlow()

    fun loadSkills() = load(_skills) { backend.skills() }

    fun loadAutomations() = load(_automations) { backend.automations() }

    init {
        // A run started or ended, or the agent changed a job: the page shows it (quietly, keeping what is shown meanwhile).
        viewModelScope.launch {
            io.github.erkanw.vault.automation.Automations.version.collect {
                if (it > 0L && backend.connected) runCatching { backend.automations() }.onSuccess { list -> _automations.value = PageState(items = list) }
            }
        }
    }

    /** Makes an automation; [onDone] gets null, or why it failed. */
    fun createAutomation(values: JSONObject, onDone: (String?) -> Unit) {
        viewModelScope.launch {
            val error = try { backend.automationCreate(values); null } catch (e: CancellationException) { throw e } catch (e: Exception) { e.message ?: "Couldn’t save the automation." }
            onDone(error)
            loadAutomations()
        }
    }

    /** Every run of [job] kept on the phone, newest first, for the runs page. */
    suspend fun automationRuns(job: AutomationJob) = runCatching { backend.automationRuns(job.id) }.getOrDefault(emptyList())

    /** Turns a skill on or off. */
    fun setSkillEnabled(skill: SkillInfo, enabled: Boolean) = act(
        _skills,
        { list -> list.map { if (it.name == skill.name) it.copy(enabled = enabled) else it } },
        "Couldn’t change the skill.",
        ::loadSkills,
    ) { backend.setSkillEnabled(skill.name, enabled) }

    /** Saves a new or edited skill, then reloads the list; [onDone] gets null, or the reason it failed. */
    fun saveSkill(name: String, description: String, instructions: String, onDone: (String?) -> Unit) {
        viewModelScope.launch {
            val error = try { backend.saveSkill(name, description, instructions); null } catch (e: Exception) { e.message ?: "Couldn’t save the skill." }
            onDone(error)
            if (error == null) loadSkills()
        }
    }

    suspend fun skillText(name: String) = runCatching { backend.skillText(name) }.getOrNull()

    /** A skill's one-line result ("Imported trip-planner."), or why it failed, for the Skills page. */
    private val _skillNote = MutableStateFlow<String?>(null)
    val skillNote: StateFlow<String?> = _skillNote.asStateFlow()
    fun clearSkillNote() { _skillNote.value = null }

    private fun skillOp(work: (android.content.Context) -> String) {
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            _skillNote.value = try { work(app) } catch (e: CancellationException) { throw e } catch (e: Exception) { e.message ?: "That didn’t work." }
            launch(Dispatchers.Main) { loadSkills() }
        }
    }

    fun importSkill(uri: android.net.Uri) = skillOp { "Imported ${io.github.erkanw.vault.tools.SkillStore.import(it, uri)}." }
    fun duplicateSkill(name: String) = skillOp { "Made ${io.github.erkanw.vault.tools.SkillStore.duplicate(it, name)}, your own copy to edit." }

    /** Saves a skill of yours to Downloads/Apel: its SKILL.md, or the whole folder as a zip when it holds more. */
    fun exportSkill(name: String) {
        val dir = java.io.File(io.github.erkanw.vault.tools.SkillStore.dir(getApplication()), name)
        val alone = dir.listFiles().orEmpty().all { it.name == "SKILL.md" }
        if (alone) download("skills/$name/SKILL.md", "$name.md", folder = false) else download("skills/$name", name, folder = true)
        _skillNote.value = "Saving $name in Downloads/Apel."
    }

    fun deleteSkill(skill: SkillInfo) = act(
        _skills,
        { list -> list.filterNot { it.name == skill.name } },
        "Couldn’t delete the skill.",
        ::loadSkills,
    ) { backend.deleteSkill(skill.name) }

    /** [action]: `enable`, `disable`, `run` (now) or `delete`. */
    fun automationAction(job: AutomationJob, action: String) = act(
        _automations,
        { list ->
            when (action) {
                "enable" -> list.map { if (it.id == job.id) it.copy(enabled = true) else it }
                "disable" -> list.map { if (it.id == job.id) it.copy(enabled = false) else it }
                "run" -> list.map { if (it.id == job.id) it.copy(pending = true) else it }
                "delete" -> list.filterNot { it.id == job.id }
                else -> list
            }
        },
        "Couldn’t $action the automation.",
        ::loadAutomations,
    ) { backend.automationAction(job.id, action) }

    /** Saves an edited automation ([values]; [schedule] is what it will run on). The card shows the change at once. */
    fun updateAutomation(job: AutomationJob, values: JSONObject, schedule: JSONObject?) = act(
        _automations,
        { list ->
            list.map {
                if (it.id != job.id) it else if (schedule == null) it.copy(
                    name = values.optString("name", it.name),
                    message = values.optString("message", it.message),
                    channel = values.optString("channel", it.channel),
                    model = values.optString("model", it.model),
                ) else it.copy(
                    name = values.optString("name", it.name),
                    message = values.optString("message", it.message),
                    channel = values.optString("channel", it.channel),
                    model = values.optString("model", it.model),
                    schedule = describeSchedule(schedule),
                    scheduleKind = schedule.optString("kind"),
                    cronExpr = schedule.optString("expr"),
                    tz = schedule.optString("tz"),
                    everyMs = schedule.optLong("every_ms", 0L),
                    atMs = schedule.optLong("at_ms", 0L),
                )
            }
        },
        "Couldn’t save the automation.",
        ::loadAutomations,
    ) { backend.automationUpdate(job.id, values) }

    // ---- Tools ---------------------------------------------------------------------------------------

    private val _builtInTools = MutableStateFlow(PageState<BuiltInTool>())
    val builtInTools: StateFlow<PageState<BuiltInTool>> = _builtInTools.asStateFlow()
    private val _mcpServers = MutableStateFlow(PageState<McpServer>())
    val mcpServers: StateFlow<PageState<McpServer>> = _mcpServers.asStateFlow()

    /** Each server's tool selection before it was switched off, kept so switching it on brings it back. */
    private val toolPrefs = app.getSharedPreferences("tools", android.content.Context.MODE_PRIVATE)

    fun loadTools() {
        load(_builtInTools) { backend.builtInTools() }
        load(_mcpServers) { backend.mcpServers() }
    }

    fun setBuiltInTool(tool: BuiltInTool, on: Boolean) = act(
        _builtInTools,
        { list -> list.map { if (it.path == tool.path) it.copy(enabled = on) else it } },
        "Couldn’t turn ${tool.title.lowercase()} ${if (on) "on" else "off"}.",
        ::loadTools,
    ) { backend.setBuiltInTool(tool.path, on) }

    fun setToolLevel(tool: BuiltInTool, level: io.github.erkanw.vault.tools.ToolLevel) = act(
        _builtInTools,
        { list -> list.map { if (it.path == tool.path) it.copy(level = level, enabled = level != io.github.erkanw.vault.tools.ToolLevel.Off) else it } },
        "Couldn’t change ${tool.title.lowercase()}.",
        ::loadTools,
    ) { backend.setToolLevel(tool.path, level) }

    /** Turns a whole tool server off (none of its tools) or back on (the tools it had on before, or all of them). */
    fun setServerOn(server: McpServer, on: Boolean) {
        val next = if (on) {
            toolPrefs.getString("last_${server.name}", null)?.split('\n')?.filter { it.isNotBlank() }?.ifEmpty { null } ?: listOf("*")
        } else {
            toolPrefs.edit().putString("last_${server.name}", server.enabledTools.joinToString("\n")).apply()
            emptyList()
        }
        setServerTools(server, next, if (on) "Couldn’t turn ${server.title} on." else "Couldn’t turn ${server.title} off.")
    }

    fun setServerTool(server: McpServer, tool: String, on: Boolean) =
        setServerTools(server, server.withTool(tool, on), "Couldn’t change $tool.")

    private fun setServerTools(server: McpServer, enabled: List<String>, failure: String) = act(
        _mcpServers,
        { list -> list.map { if (it.name == server.name) it.copy(enabledTools = enabled) else it } },
        failure,
        ::loadTools,
    ) { backend.setMcpTools(server.name, enabled) }

    /** Reaches a tool server without keeping it: what it offers, or why not. */
    fun testServer(address: String, token: String, onResult: (Result<io.github.erkanw.vault.chat.McpTest>) -> Unit) {
        viewModelScope.launch {
            val r = try {
                Result.success(backend.testMcpServer(address, token))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            onResult(r)
        }
    }

    /** Adds a tool server; [onDone] gets null, or why it couldn't be added. */
    fun addServer(address: String, token: String, name: String, onDone: (String?) -> Unit) {
        viewModelScope.launch {
            val error = try {
                backend.addMcpServer(address, token, name)
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.message ?: "Couldn’t add the server."
            }
            onDone(error)
            if (error == null) loadTools()
        }
    }

    /** Reads a server's tools again (it may have new ones, or be back). */
    fun refreshServer(server: McpServer, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            runCatching { backend.refreshMcpServer(server.name) }
            loadTools()
            onDone()
        }
    }

    fun removeServer(server: McpServer) = act(
        _mcpServers, { list -> list.filter { it.name != server.name } }, "Couldn’t remove ${server.title}.", ::loadTools,
    ) { backend.removeMcpServer(server.name) }

    /** Closes the experimental browser's page and deletes its cookies and stored data. */
    fun clearBrowser(onDone: () -> Unit) {
        viewModelScope.launch {
            runCatching { io.github.erkanw.vault.tools.BrowserSession.clear() }
            onDone()
        }
    }

    // ---- Files: the agent's workspace -----------------------------------------------------------------

    private val _files = MutableStateFlow(FilesState())
    val files: StateFlow<FilesState> = _files.asStateFlow()
    private var filesRequest = 0

    /** Shows the folder [path] ("" is the top) of the workspace. */
    fun openFolder(path: String) {
        if (!backend.connected) {
            _files.value = FilesState(error = notConnected)
            return
        }
        val mine = ++filesRequest
        _files.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val listing = backend.files(path)
                if (mine == filesRequest) _files.update { it.copy(path = listing.path, entries = listing.entries, loading = false, error = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The folder that was shown stays (a failed step in leaves you where you were).
                if (mine == filesRequest) _files.update { it.copy(loading = false, error = e.message ?: "Couldn’t load the folder.") }
            }
        }
    }

    // ---- editing the workspace (PLAN §6.6) ----

    private val _trash = MutableStateFlow<List<io.github.erkanw.vault.tools.TrashItem>>(emptyList())
    val trash: StateFlow<List<io.github.erkanw.vault.tools.TrashItem>> = _trash.asStateFlow()

    /** Runs a change to the workspace off the main thread, then shows the folder again; a refusal shows as the page's line. */
    private fun fileOp(after: (String?) -> Unit = {}, work: (android.content.Context) -> String?) {
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            val result = try {
                work(app)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _files.update { it.copy(error = e.message ?: "That didn’t work.") }
                return@launch
            }
            launch(Dispatchers.Main) {
                openFolder(_files.value.path)
                after(result)
            }
        }
    }

    fun newFolder(name: String) = fileOp { io.github.erkanw.vault.tools.WorkspaceOps.newFolder(it, _files.value.path, name.trim()); null }

    /** Makes an empty file and opens it, ready to edit. */
    fun newFile(name: String) = fileOp(after = { path -> if (path != null) viewFile(path, path.substringAfterLast('/')) }) {
        io.github.erkanw.vault.tools.WorkspaceOps.newFile(it, _files.value.path, name.trim())
    }

    fun moveEntry(path: String, to: String) = fileOp { io.github.erkanw.vault.tools.WorkspaceOps.move(it, path, to) }
    fun trashEntry(path: String) = fileOp { io.github.erkanw.vault.tools.WorkspaceOps.trash(it, path); null }
    fun importFile(uri: android.net.Uri) = fileOp { io.github.erkanw.vault.tools.WorkspaceOps.import(it, _files.value.path, uri) }

    /** Saves an edited text file and shows the new text in the viewer. */
    fun saveText(path: String, text: String) = fileOp(after = { _viewed.update { v -> v?.copy(text = text) } }) {
        io.github.erkanw.vault.tools.WorkspaceOps.writeText(it, path, text); null
    }

    fun loadTrash() {
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) { _trash.value = io.github.erkanw.vault.tools.WorkspaceOps.trashList(app) }
    }

    fun restore(id: String) = fileOp(after = { loadTrash() }) { io.github.erkanw.vault.tools.WorkspaceOps.restore(it, id) }
    fun deleteForever(id: String) = fileOp(after = { loadTrash() }) { io.github.erkanw.vault.tools.WorkspaceOps.deleteForever(it, id); null }

    /** Back one folder; false when already at the top (the page then closes). */
    fun folderUp(): Boolean {
        val here = _files.value.path
        if (here.isEmpty()) return false
        openFolder(parentPath(here))
        return true
    }

    /** Saves a file, or a folder as a zip, in Downloads/Apel on the phone. */
    fun download(path: String, name: String, folder: Boolean) {
        if (_files.value.busy != null) return
        _files.update { it.copy(busy = name, saved = null, error = null) }
        viewModelScope.launch {
            val fileName = if (folder) "$name.zip" else name
            val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(fileName.substringAfterLast('.', "").lowercase()) ?: mimeOf(fileName)
            try {
                // The copy is written as it is read; MediaStore calls are blocking.
                val saved = withContext(Dispatchers.IO) {
                    val buffer = java.io.ByteArrayOutputStream()
                    backend.download(path, folder, buffer)
                    saveInDownloads(getApplication(), fileName, mime) { out -> buffer.writeTo(out) }
                }
                _files.update { it.copy(busy = null, saved = saved) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _files.update { it.copy(busy = null, error = e.message ?: "The download failed.") }
            }
        }
    }

    fun dismissSaved() = _files.update { it.copy(saved = null) }

    private val _viewed = MutableStateFlow<ViewedFile?>(null)

    /** The file of the Files page that is open to look at, if any. */
    val viewed: StateFlow<ViewedFile?> = _viewed.asStateFlow()

    fun closeViewed() {
        _viewed.value = null
    }

    /** Opens a workspace file to look at in the app (text and pictures); nothing is saved to Downloads. */
    fun viewFile(path: String, name: String) {
        if (_files.value.busy != null || _files.value.viewing != null) return
        val ext = name.substringAfterLast('.', "").lowercase()
        val picture = ext in PICTURE_EXTENSIONS
        if (!picture && ext !in TEXT_EXTENSIONS) {
            _files.update { it.copy(error = "There is no preview for ${if (ext.isEmpty()) "this kind of file" else ".$ext files"}: use the download icon to save it.") }
            return
        }
        _files.update { it.copy(viewing = name, saved = null, error = null) }
        viewModelScope.launch(Dispatchers.IO) {
            var copy: java.io.File? = null
            try {
                if (picture) {
                    val dir = java.io.File(getApplication<Application>().cacheDir, "viewed").also { it.mkdirs() }
                    dir.listFiles()?.forEach { it.delete() } // only the picture being looked at is kept
                    val file = java.io.File(dir, java.util.UUID.randomUUID().toString() + "." + ext)
                    copy = file
                    file.outputStream().use { backend.download(path, false, LimitedOutput(it, MAX_PREVIEW_PICTURE)) }
                    _viewed.value = ViewedFile(name, path, picture = file.absolutePath)
                } else {
                    val buffer = java.io.ByteArrayOutputStream()
                    backend.download(path, false, LimitedOutput(buffer, MAX_PREVIEW_TEXT))
                    _viewed.value = ViewedFile(name, path, text = buffer.toString("UTF-8"))
                }
                _files.update { it.copy(viewing = null) }
            } catch (e: Exception) {
                copy?.delete()
                if (e is CancellationException) throw e
                val why = if (e is PreviewTooBig) "That file is too big to preview: use the download icon to save it." else e.message ?: "Couldn’t open the file."
                _files.update { it.copy(viewing = null, error = why) }
            }
        }
    }

    // ---- shared ----------------------------------------------------------------------------------------

    private fun <T> load(flow: MutableStateFlow<PageState<T>>, fetch: suspend () -> List<T>) {
        if (!backend.connected) {
            flow.value = PageState(error = notConnected)
            return
        }
        flow.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                flow.value = PageState(items = fetch())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                flow.update { it.copy(loading = false, error = e.message ?: "Couldn’t load.") }
            }
        }
    }

    /** A change on a list page: shown at once, saved, then the list is read again; undone if refused. */
    private fun <T> act(
        flow: MutableStateFlow<PageState<T>>,
        optimistic: (List<T>) -> List<T>,
        failure: String,
        reload: () -> Unit,
        call: suspend () -> Unit,
    ) {
        val before = flow.value
        flow.value = before.copy(items = optimistic(before.items), error = null)
        viewModelScope.launch {
            try {
                call()
                reload()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                flow.value = before.copy(error = e.message ?: failure)
            }
        }
    }
}
