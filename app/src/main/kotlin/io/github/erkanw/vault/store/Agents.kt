package io.github.erkanw.vault.store

import android.content.Context
import androidx.core.content.edit
import io.github.erkanw.vault.agent.AgentPresets
import io.github.erkanw.vault.agent.AgentScope
import io.github.erkanw.vault.mascot.BlobLook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** An agent as the screens show it: its row, with Apel's name and look read from where they always were. */
data class AgentInfo(val row: AgentEntity, val name: String, val look: BlobLook) {
    val id: String get() = row.id
    val isApel: Boolean get() = row.id == AgentPresets.APEL
}

/**
 * The agents (PLAN §6.16): Room `agents`, with Apel as the row `apel` (made on first use; its name and look stay in
 * [AgentProfile] and [LookStore]). Which agent new chats start with, and which agent each channel uses, are settings.
 */
object Agents {
    private const val PREFS = "agents"
    private const val KEY_SELECTED = "selected"
    private const val CHANNEL_PREFS = "channel_agents"

    private val lock = Mutex()
    private val _list = MutableStateFlow<List<AgentInfo>>(emptyList())

    /** Every agent, Apel first; read again on every change. */
    val list: StateFlow<List<AgentInfo>> = _list.asStateFlow()

    private fun dao(context: Context) = AppDatabase.get(context).agents()
    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun channelPrefs(context: Context) = context.applicationContext.getSharedPreferences(CHANNEL_PREFS, Context.MODE_PRIVATE)

    /** Makes Apel's row when there is none; Instructions written before agents existed become part of its prompt. */
    suspend fun ensure(context: Context): List<AgentInfo> = withContext(Dispatchers.IO) {
        lock.withLock {
            val app = context.applicationContext
            if (dao(app).get(AgentPresets.APEL) == null) {
                val old = File(File(app.filesDir, "agent"), "AGENT.md")
                val instructions = old.takeIf { it.isFile }?.readText()?.trim().orEmpty()
                val prompt = if (instructions.isEmpty()) null else AgentPresets.defaultPrompt(AgentProfile.name(app)) + "\n\n" + instructions
                dao(app).insert(AgentEntity(id = AgentPresets.APEL, name = "", prompt = prompt, created = 0L))
                if (old.isFile) old.renameTo(File(old.parentFile, "AGENT.md.before-agents"))
            }
            reload(app)
        }
    }

    private suspend fun reload(app: Context): List<AgentInfo> {
        val rows = dao(app).all()
        val apel = rows.firstOrNull { it.id == AgentPresets.APEL }
        val infos = listOfNotNull(apel?.let { info(app, it) }) + rows.filter { it.id != AgentPresets.APEL }.map { info(app, it) }
        _list.value = infos
        return infos
    }

    fun info(context: Context, row: AgentEntity): AgentInfo =
        if (row.id == AgentPresets.APEL) AgentInfo(row, AgentProfile.name(context), LookStore.look(context))
        else AgentInfo(row, row.name, BlobLook.parse(row.look.ifBlank { null }))

    suspend fun get(context: Context, id: String?): AgentInfo? {
        if (_list.value.isEmpty()) ensure(context)
        val wanted = id ?: AgentPresets.APEL
        return _list.value.firstOrNull { it.id == wanted }
    }

    /** What a turn uses: the agent [id] (Apel when null or deleted). */
    suspend fun scope(context: Context, id: String?): AgentScope {
        val a = get(context, id) ?: get(context, null)!!
        return AgentScope(
            id = a.id, name = a.name,
            prompt = a.row.prompt?.takeIf { it.isNotBlank() } ?: AgentPresets.defaultPrompt(a.name),
            model = a.row.model,
            toolsOff = AgentPresets.setOf(a.row.toolsOff),
            skillsOff = AgentPresets.setOf(a.row.skillsOff),
            dbCaps = AgentPresets.capsOf(a.row.dbCaps),
        )
    }

    /** A new agent from [template] (or blank); its id. Everything is on. */
    suspend fun create(context: Context, name: String, templateId: String? = null): String = withContext(Dispatchers.IO) {
        lock.withLock {
            val app = context.applicationContext
            val t = templateId?.let(AgentPresets::template)
            val rows = dao(app).all()
            val clean = name.trim().take(AgentPresets.MAX_NAME).ifBlank { t?.name ?: "New agent" }
            val id = AgentPresets.idFor(clean, rows.map { it.id }.toSet())
            dao(app).insert(
                AgentEntity(
                    id = id, name = clean, look = t?.look ?: BlobLook.Default.toQuery(), prompt = t?.prompt,
                    template = t?.id.orEmpty(), position = (rows.maxOfOrNull { it.position } ?: 0) + 1, created = System.currentTimeMillis(),
                ),
            )
            reload(app)
            id
        }
    }

    /** A copy of [id] with its settings (not its memory or chats). */
    suspend fun duplicate(context: Context, id: String): String? = withContext(Dispatchers.IO) {
        val source = get(context, id) ?: return@withContext null
        lock.withLock {
            val app = context.applicationContext
            val rows = dao(app).all()
            val name = "${source.name} copy".take(AgentPresets.MAX_NAME)
            val newId = AgentPresets.idFor(name, rows.map { it.id }.toSet())
            dao(app).insert(
                source.row.copy(
                    id = newId, name = name, look = source.look.toQuery(),
                    prompt = source.row.prompt ?: AgentPresets.defaultPrompt(source.name).takeIf { !source.isApel },
                    position = (rows.maxOfOrNull { it.position } ?: 0) + 1, created = System.currentTimeMillis(),
                ),
            )
            reload(app)
            newId
        }
    }

    /** Changes [id]'s row with [change]. Apel's name and look are changed where they live, not here. */
    suspend fun update(context: Context, id: String, change: (AgentEntity) -> AgentEntity) = withContext(Dispatchers.IO) {
        lock.withLock {
            val app = context.applicationContext
            val row = dao(app).get(id) ?: return@withLock
            dao(app).update(change(row))
            reload(app)
        }
    }

    /** Name and look of [id]: Apel's go to its profile (the app's identity), the others to their row. */
    suspend fun rename(context: Context, id: String, name: String) {
        if (id == AgentPresets.APEL) {
            AgentProfile.setName(context, name)
            withContext(Dispatchers.IO) { lock.withLock { reload(context.applicationContext) } }
        } else if (name.isNotBlank()) update(context, id) { it.copy(name = name.trim().take(AgentPresets.MAX_NAME)) }
    }

    suspend fun setLook(context: Context, id: String, look: BlobLook) {
        if (id == AgentPresets.APEL) {
            LookStore.set(context, look)
            withContext(Dispatchers.IO) { lock.withLock { reload(context.applicationContext) } }
        } else update(context, id) { it.copy(look = look.toQuery()) }
    }

    /** Deletes [id] (never Apel): its chats and jobs move to Apel; its memory goes to the Trash folder. */
    suspend fun delete(context: Context, id: String) = withContext(Dispatchers.IO) {
        if (id == AgentPresets.APEL) return@withContext
        lock.withLock {
            val app = context.applicationContext
            dao(app).moveSessionsToApel(id)
            dao(app).moveAutomationsToApel(id)
            dao(app).delete(id)
            // Its memory goes to the Trash (restorable for 30 days), like any deleted file.
            val memory = File(io.github.erkanw.vault.tools.Workspace.root(app), "memory/$id")
            if (memory.isDirectory) {
                val dest = File(io.github.erkanw.vault.tools.Workspace.trash(app), "${System.currentTimeMillis()}-memory-$id")
                if (memory.renameTo(dest)) File(dest.path + ".from").writeText("memory/$id")
            }
            if (selected(app) == id) select(app, AgentPresets.APEL)
            channelPrefs(app).all.filterValues { it == id }.keys.forEach { k -> channelPrefs(app).edit { remove(k) } }
            reload(app)
        }
    }

    // ---- which agent ----

    private val _selected = MutableStateFlow<String?>(null)

    /** The agent new chats from Home start with. */
    fun selected(context: Context): String = _selected.value ?: (prefs(context).getString(KEY_SELECTED, null) ?: AgentPresets.APEL).also { _selected.value = it }

    fun selectedFlow(context: Context): StateFlow<String?> {
        selected(context)
        return _selected.asStateFlow()
    }

    fun select(context: Context, id: String) {
        prefs(context).edit { putString(KEY_SELECTED, id) }
        _selected.value = id
    }

    /** The agent a channel uses; null: Apel. */
    fun ofChannel(context: Context, channelId: String): String? = channelPrefs(context).getString(channelId, null)

    fun setChannel(context: Context, channelId: String, agentId: String) =
        channelPrefs(context).edit { if (agentId == AgentPresets.APEL) remove(channelId) else putString(channelId, agentId) }
}
