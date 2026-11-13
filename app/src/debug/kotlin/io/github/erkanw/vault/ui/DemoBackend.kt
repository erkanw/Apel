package io.github.erkanw.vault.ui

import android.content.Context
import io.github.erkanw.vault.agent.AgentBackend
import io.github.erkanw.vault.agent.TurnEvent
import io.github.erkanw.vault.agent.TurnInput
import io.github.erkanw.vault.chat.AgentStep
import io.github.erkanw.vault.chat.AutomationJob
import io.github.erkanw.vault.chat.BuiltInTool
import io.github.erkanw.vault.chat.ChatException
import io.github.erkanw.vault.chat.ChatMessage
import io.github.erkanw.vault.chat.FileEntry
import io.github.erkanw.vault.chat.FileListing
import io.github.erkanw.vault.chat.IngressLimits
import io.github.erkanw.vault.chat.LOCAL_COMMANDS
import io.github.erkanw.vault.chat.McpServer
import io.github.erkanw.vault.chat.MessageAttachment
import io.github.erkanw.vault.chat.ModelChoices
import io.github.erkanw.vault.chat.ModelPreset
import io.github.erkanw.vault.chat.NEW_SESSION_TITLE
import io.github.erkanw.vault.chat.Role
import io.github.erkanw.vault.chat.SessionStats
import io.github.erkanw.vault.chat.SessionSummary
import io.github.erkanw.vault.chat.SkillInfo
import io.github.erkanw.vault.chat.SlashCommand
import io.github.erkanw.vault.chat.StepKind
import io.github.erkanw.vault.chat.ThreadPage
import io.github.erkanw.vault.chat.humanCron
import io.github.erkanw.vault.chat.humanDuration
import io.github.erkanw.vault.chat.userIndexBefore
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.json.JSONObject
import java.io.OutputStream

/**
 * The demo backend (debug builds only): every [AgentBackend] call answered from sample data kept in memory, and a turn
 * that streams a made-up answer with a couple of tool steps, so the chat's streaming, steps and Stop can be seen without
 * a provider. Changes (rename, pin, a new chat) last until the app is closed. [mode] is the demo file's text.
 */
class DemoBackend(private val context: Context, private val mode: String) : AgentBackend {
    override val connected: Boolean = true

    private val empty = mode == "empty"
    private val start = System.currentTimeMillis()
    private fun ago(minutes: Long) = start - minutes * 60_000L

    private class Session(var summary: SessionSummary, val messages: MutableList<ChatMessage>, val forkBoundary: Int? = null)

    private val sessions = LinkedHashMap<String, Session>().apply { if (!empty) sampleSessions().forEach { put(it.summary.chatId, it) } }

    private fun s(id: String, title: String, preview: String, minutes: Long, running: Boolean = false, pinned: Boolean = false, archived: Boolean = false, prompt: String = title) =
        Session(
            SessionSummary(chatId = id, key = id, title = title, preview = preview, updatedMs = ago(minutes), running = running, pinned = pinned, archived = archived, createdMs = ago(minutes + 2)),
            mutableListOf(
                ChatMessage(1, Role.User, prompt, createdAt = ago(minutes + 1)),
                ChatMessage(2, Role.Assistant, preview, createdAt = ago(minutes)),
            ),
        )

    private fun sampleSessions(): List<Session> = listOf(
        s("demo-pinned", "Trip packing list", "Here’s a list for four days, sorted by bag. The charger and the passport are at the top.", 190, pinned = true, prompt = "Make me a packing list for four days away."),
        Session(
            SessionSummary("demo-1", "demo-1", "Compare three study plans", "I’d go with spaced and keep one practice exam on Thursday.", ago(2), running = false, pinned = false, createdMs = ago(1500)),
            studyChat(),
        ),
        s("demo-2", "Train times to the airport", "The 07:42 is the quickest: 38 minutes, direct.", 55),
        s("demo-3", "Rename the invoices folder", "Done. 14 files moved, nothing was overwritten.", 310),
        s("demo-4", "Why is my laptop slow?", "Two apps start at login and use most of the memory. Turning them off should help.", 1700),
        s("demo-5", "Weekly review", "Three open items: dentist, library book, tax form.", 3000),
        s("demo-6", "Plant watering reminder", "I set it for Sundays at 18:00.", 8000),
        s("demo-7", "Notes from the meeting", "Summary saved to notes/meeting.md.", 21000),
        s("demo-a1", "Old recipe ideas", "Lentil soup with lemon and chili oil.", 60000, archived = true),
        s("demo-a2", "Birthday present search", "Three options under $50.", 90000, archived = true),
    )

    /** The sample conversation: a markdown answer with a table and code, steps, and two files. */
    private fun studyChat(): MutableList<ChatMessage> {
        val day = 86_400_000L
        val plan = "Here’s how the three plans compare.\n\n**Cram week** puts everything into the last seven days. It is quick to set up, but retention drops fast.\n\n**Spaced** spreads 20-minute sessions over three weeks and uses the quizzes from your own notes.\n\n| Plan | Hours | Retention |\n| --- | --- | --- |\n| Cram week | 14 | Low |\n| Spaced | 10 | High |\n| Mixed | 12 | Medium |\n\n```python\ndef next_review(days, ease=2.5):\n    return round(days * ease)\n```\n\nI’d go with **spaced** and keep one practice exam on Thursday."
        val list = mutableListOf(
            ChatMessage(1, Role.User, "Compare three study plans for the physics exam: a cram week, spaced sessions, and a mix.", createdAt = start - day - 3_600_000L),
            ChatMessage(
                2, Role.Assistant, plan, createdAt = start - day - 3_500_000L,
                steps = listOf(
                    AgentStep(StepKind.Tool, "read_skill study-planner"),
                    AgentStep(StepKind.Note, "The exam is on Friday, three chapters still open."),
                    AgentStep(StepKind.Tool, "file_read notes/physics.md"),
                ),
            ),
            ChatMessage(3, Role.User, "Go with spaced. Put the sessions in my calendar.", createdAt = start - 600_000L),
            ChatMessage(
                4, Role.Assistant, "I can’t reach your calendar from here, so I saved the plan as a file you can import.", createdAt = start - 540_000L,
                attachments = listOf(
                    MessageAttachment(name = "study-plan.md", image = false, localPath = sample("study-plan.md", PLAN_MD)),
                    MessageAttachment(name = "spaced-plan.ics", image = false, localPath = sample("spaced-plan.ics", PLAN_ICS)),
                ),
            ),
        )
        if (mode == "error") {
            list += ChatMessage(5, Role.User, "Add the quiz dates too.", createdAt = start - 30_000L)
            list += ChatMessage(6, Role.Assistant, "Your OpenRouter credits have run out. Add credits at openrouter.ai, then try again.", isError = true, createdAt = start - 20_000L)
        }
        if (mode == "busy") {
            list += ChatMessage(5, Role.User, "Also add the quiz dates and remind me the evening before.", createdAt = start - 20_000L)
            list += ChatMessage(
                6, Role.Assistant, "", createdAt = start - 10_000L,
                steps = listOf(AgentStep(StepKind.Tool, "automation_create name=\"Physics quiz\""), AgentStep(StepKind.Thinking, "The evening before is 18:00 on the 12th.")),
            )
        }
        return list
    }

    /** A sample file kept in the cache, so the viewer has something to open. */
    private fun sample(name: String, text: String): String {
        val file = context.cacheDir.resolve("demo-files").apply { mkdirs() }.resolve(name)
        if (!file.exists() || file.readText() != text) file.writeText(text)
        return file.absolutePath
    }

    // ---- sessions ----

    override suspend fun sessions(): List<SessionSummary> =
        sessions.values.map { it.summary }.sortedWith(compareByDescending<SessionSummary> { it.pinned }.thenByDescending { it.updatedMs })

    override suspend fun thread(chatId: String): ThreadPage? = sessions[chatId]?.let { ThreadPage(it.messages.toList(), 0, it.forkBoundary) }

    override suspend fun sessionStats(chatId: String): SessionStats? = null

    private fun change(chatId: String, update: (SessionSummary) -> SessionSummary) {
        val s = sessions[chatId] ?: throw ChatException("That chat is gone.")
        s.summary = update(s.summary)
    }

    override suspend fun rename(chatId: String, title: String) = change(chatId) { it.copy(title = title.trim().ifBlank { it.title }) }
    override suspend fun setPinned(chatId: String, pinned: Boolean) = change(chatId) { it.copy(pinned = pinned) }
    override suspend fun setArchived(chatId: String, archived: Boolean) = change(chatId) { it.copy(archived = archived, pinned = it.pinned && !archived) }

    override suspend fun deleteSession(chatId: String) {
        sessions.remove(chatId)
    }

    override suspend fun forkChat(sourceChatId: String, beforeUserIndex: Int, title: String): String {
        val source = sessions[sourceChatId] ?: throw ChatException("That chat is gone.")
        val cut = source.messages.indices.firstOrNull { userIndexBefore(source.messages, it, 0) == beforeUserIndex && source.messages[it].role == Role.User }
            ?: source.messages.size
        val id = "demo-" + System.nanoTime().toString(16)
        val kept = source.messages.take(cut).toMutableList()
        sessions[id] = Session(SessionSummary(id, id, title, kept.lastOrNull()?.text.orEmpty().take(80), System.currentTimeMillis(), false, false, createdMs = System.currentTimeMillis()), kept, kept.size)
        return id
    }

    private var activePreset = "main"

    override suspend fun setSessionModel(chatId: String?, preset: String) {
        if (chatId != null && sessions.containsKey(chatId)) change(chatId) { it.copy(modelPreset = preset) } else activePreset = preset
    }

    // ---- turns ----

    override fun run(chatId: String?, input: TurnInput): Flow<TurnEvent> = flow {
        val id = chatId?.takeIf { sessions.containsKey(it) } ?: ("demo-" + System.nanoTime().toString(16)).also { newId ->
            val title = input.text.lineSequence().firstOrNull().orEmpty().take(40).ifBlank { NEW_SESSION_TITLE }
            sessions[newId] = Session(SessionSummary(newId, newId, title, "", System.currentTimeMillis(), false, false, createdMs = System.currentTimeMillis()), mutableListOf())
        }
        val session = sessions.getValue(id)
        val nextId = { (session.messages.maxOfOrNull { it.id } ?: 0L) + 1 }
        session.messages += ChatMessage(nextId(), Role.User, input.text)
        emit(TurnEvent.MessageAccepted(id))
        if (mode == "error") {
            delay(700)
            emit(TurnEvent.Error("Your OpenRouter credits have run out. Add credits at openrouter.ai, then try again."))
            return@flow
        }
        val answer: String
        val steps = ArrayList<AgentStep>()
        if (input.text.startsWith("/")) {
            delay(400)
            answer = when (input.text.trim().substringBefore(' ').lowercase()) {
                "/help" -> LOCAL_COMMANDS.joinToString("\n") { "- **${it.name}** ${it.description.lowercase()}" }
                "/compact" -> "Done: the older part of this chat is summarised, so there is room for more."
                else -> "That command isn’t in demo mode."
            }
        } else {
            delay(600)
            emit(TurnEvent.ToolHint("Searching the web"))
            AgentStep(StepKind.Tool, "web_search \"${input.text.take(40)}\"").also { steps += it; emit(TurnEvent.Progress(it)) }
            delay(900)
            emit(TurnEvent.ToolHint("Reading example.com"))
            AgentStep(StepKind.Tool, "web_fetch https://example.com").also { steps += it; emit(TurnEvent.Progress(it)) }
            delay(900)
            answer = "This is **demo mode**: no AI provider is connected, so this answer is made up on the phone.\n\n" +
                "You asked: “${input.text.take(120)}”.\n\nOnce a provider is connected, the agent searches, reads pages and " +
                "writes its answer here, word by word, just like this."
        }
        for (word in answer.split(Regex("(?<= )"))) {
            emit(TurnEvent.Delta(word))
            delay(35)
        }
        emit(TurnEvent.TurnEnd(answer))
        session.messages += ChatMessage(nextId(), Role.Assistant, answer, steps = steps)
        session.summary = session.summary.copy(preview = answer.take(120), updatedMs = System.currentTimeMillis())
    }

    // ---- the composer ----

    override suspend fun limits(): IngressLimits = IngressLimits()
    override suspend fun commands(): List<SlashCommand> = LOCAL_COMMANDS

    // ---- models ----

    private val presets = mutableListOf(
        ModelPreset("main", "Gemini 3 Flash", "google/gemini-3-flash", active = false, contextTokens = 1_048_576, maxTokens = 8_192),
        ModelPreset("qwen", "Qwen3.8 27B", "qwen/qwen3.8-27b", active = false, contextTokens = 131_072, maxTokens = 8_192),
        ModelPreset("free", "GPT-OSS 120B", "openai/gpt-oss-120b:free", active = false, contextTokens = 131_072, maxTokens = 4_096),
    )
    private var callOrder = listOf("main", "qwen")

    override suspend fun models(): ModelChoices =
        ModelChoices(presets.map { it.copy(active = it.name == activePreset) }, activePreset, callOrder, callOrderEditable = true)

    override suspend fun createModel(name: String, model: String, maxTokens: Long, contextTokens: Long) {
        if (presets.any { it.name == name }) throw ChatException("There is already a model called $name.")
        presets += ModelPreset(name, name, model, false, contextTokens = contextTokens, maxTokens = maxTokens)
    }

    override suspend fun updateModel(name: String, maxTokens: Long, contextTokens: Long) {
        val i = presets.indexOfFirst { it.name == name }
        if (i >= 0) presets[i] = presets[i].copy(maxTokens = maxTokens, contextTokens = contextTokens)
    }

    override suspend fun deleteModel(name: String) {
        presets.removeAll { it.name == name }
        callOrder = callOrder - name
    }

    override suspend fun setModelOrder(order: List<String>) {
        callOrder = order
    }

    // ---- files ----

    private val folders = mapOf(
        "" to listOf(FileEntry("projects", true, 0L, ago(120)), FileEntry("notes", true, 0L, ago(180)), FileEntry("downloads", true, 0L, ago(300))),
        "projects" to listOf(FileEntry("trip", true, 0L, ago(190)), FileEntry("router-diagram.png", false, 0L, ago(3000))),
        "projects/trip" to listOf(FileEntry("packing-list.md", false, 0L, ago(190))),
        "notes" to listOf(FileEntry("study-plan.md", false, 0L, ago(9)), FileEntry("meeting.md", false, 0L, ago(21000))),
        "downloads" to listOf(FileEntry("spaced-plan.ics", false, 0L, ago(9))),
    )

    private fun fileBytes(path: String): ByteArray? = when (path.substringAfterLast('/')) {
        "study-plan.md" -> PLAN_MD.toByteArray()
        "spaced-plan.ics" -> PLAN_ICS.toByteArray()
        "packing-list.md" -> "# Packing list\n\n- Passport\n- Charger\n- Rain jacket\n- Two books\n".toByteArray()
        "meeting.md" -> "# Meeting notes\n\n- Launch moved to the 20th\n- Ana owns the release notes\n".toByteArray()
        "router-diagram.png" -> picture()
        else -> null
    }

    private fun picture(): ByteArray {
        val bitmap = android.graphics.Bitmap.createBitmap(320, 200, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawColor(0xFF232326.toInt())
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF8E8E93.toInt(); strokeWidth = 4f; style = android.graphics.Paint.Style.STROKE }
        canvas.drawRoundRect(40f, 70f, 120f, 130f, 12f, 12f, paint)
        canvas.drawRoundRect(200f, 70f, 280f, 130f, 12f, 12f, paint)
        canvas.drawLine(120f, 100f, 200f, 100f, paint)
        return java.io.ByteArrayOutputStream().also { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    override suspend fun files(path: String): FileListing {
        val entries = (if (empty) emptyList() else folders[path]) ?: throw ChatException("That folder isn’t there.")
        return FileListing(path, entries.map { if (it.dir) it else it.copy(size = fileBytes(if (path.isEmpty()) it.name else "$path/${it.name}")?.size?.toLong() ?: 0L) })
    }

    override suspend fun download(path: String, zip: Boolean, out: OutputStream): Long {
        if (!zip) {
            val bytes = fileBytes(path) ?: throw ChatException("That file isn’t there.")
            out.write(bytes)
            return bytes.size.toLong()
        }
        val counting = java.util.zip.ZipOutputStream(out)
        var total = 0L
        fun add(folder: String) {
            for (e in folders[folder].orEmpty()) {
                val p = if (folder.isEmpty()) e.name else "$folder/${e.name}"
                if (e.dir) add(p) else fileBytes(p)?.let { bytes ->
                    counting.putNextEntry(java.util.zip.ZipEntry(p.removePrefix("$path/")))
                    counting.write(bytes)
                    counting.closeEntry()
                    total += bytes.size
                }
            }
        }
        add(path)
        counting.finish()
        return total
    }

    // ---- skills ----

    private val skills = mutableListOf(
        SkillInfo("web-research", "Search, read several sources, cite the links, and say what’s uncertain.", true, true, false, "built-in", ""),
        SkillInfo("daily-brief", "A short morning brief from your notes, automations and the web.", true, true, false, "built-in", ""),
        SkillInfo("notes", "Where and how to keep notes: one file per topic in notes/.", true, true, false, "built-in", ""),
        SkillInfo("databases", "How to use your databases safely: schema first, small queries, confirm writes.", true, true, false, "built-in", ""),
        SkillInfo("scheduling", "Turns a request into an automation with a clear instruction and schedule.", true, true, false, "built-in", ""),
        SkillInfo("study-planner", "Turns a list of chapters into a revision plan with spaced reviews.", true, true, true, "workspace", ""),
        SkillInfo("packing-list", "Builds a packing list from the trip’s length and the weather.", false, true, true, "workspace", ""),
    )

    override suspend fun skills(): List<SkillInfo> = if (empty) emptyList() else skills.toList()

    override suspend fun setSkillEnabled(name: String, enabled: Boolean) {
        val i = skills.indexOfFirst { it.name == name }
        if (i >= 0) skills[i] = skills[i].copy(enabled = enabled)
    }

    override suspend fun deleteSkill(name: String) {
        skills.removeAll { it.name == name && it.deletable }
    }

    // ---- tools ----

    private val tools = mutableListOf(
        BuiltInTool("web_search", "Web search", "Search the web", true),
        BuiltInTool("web_fetch", "Web pages", "Read a page as text", true),
        BuiltInTool("files", "Files", "Read and write in its workspace", true),
        BuiltInTool("databases", "Databases", "Query and change your databases", true),
        BuiltInTool("memory", "Memory", "Remember things across chats", true),
        BuiltInTool("automations", "Automations", "Make and change its own schedule", true),
        BuiltInTool("skills", "Skills", "Read and save skills", true),
        BuiltInTool("notify", "Notifications", "Send you a notification", true),
        BuiltInTool("time", "Date and time", "Know the date, time and zone", true),
    )
    private val servers = mutableListOf(
        McpServer("weather", "Weather", listOf("get_forecast", "get_alerts"), listOf("*"), true, "connected"),
        McpServer("library", "Library catalogue", listOf("search_books", "renew_loan", "list_loans"), listOf("search_books", "list_loans"), false, "unreachable"),
    )

    override suspend fun builtInTools(): List<BuiltInTool> = tools.toList()
    override suspend fun mcpServers(): List<McpServer> = if (empty) emptyList() else servers.toList()

    override suspend fun setBuiltInTool(path: String, on: Boolean) {
        val i = tools.indexOfFirst { it.path == path }
        if (i >= 0) tools[i] = tools[i].copy(enabled = on)
    }

    override suspend fun setMcpTools(server: String, enabledTools: List<String>) {
        val i = servers.indexOfFirst { it.name == server }
        if (i >= 0) servers[i] = servers[i].copy(enabledTools = enabledTools)
    }

    // ---- automations ----

    private fun nextAt(hour: Int, minute: Int, day: java.time.DayOfWeek? = null): Long {
        val zone = java.time.ZoneId.systemDefault()
        val now = java.time.ZonedDateTime.now(zone)
        var at = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
        if (day != null) at = at.with(java.time.temporal.TemporalAdjusters.nextOrSame(day))
        if (!at.isAfter(now)) at = if (day != null) at.plusWeeks(1) else at.plusDays(1)
        return at.toInstant().toEpochMilli()
    }

    private val automations by lazy {
        val h = 3_600_000L
        mutableListOf(
            job("j1", "Morning brief", "30 7 * * *", nextAt(7, 30), start - 2 * h, message = "Write my morning brief: the weather, today’s plans from my notes, and three headlines.", channel = "daily-brief"),
            job("j2", "Weekly review", "30 18 * * 5", nextAt(18, 30, java.time.DayOfWeek.FRIDAY), start - 5 * 24 * h, message = "Go through my open items and list what is still open."),
            job("j3", "Price check", "", start + 2 * h, start - 4 * h, status = "error", error = "The shop’s page didn’t answer in 20 s.", message = "Check the price of the headphones on my wish list.", everyMs = 6 * h),
            job("j4", "Plant watering", "0 18 * * 0", 0L, 0L, enabled = false, message = "Remind me to water the plants."),
            job("tidy", "Tidy memory", "30 3 * * *", nextAt(3, 30), start - 20 * h, message = "Read today’s chats and keep what is worth remembering.", protected = true),
        )
    }

    private fun job(
        id: String, name: String, cron: String, next: Long, last: Long, status: String = "ok", enabled: Boolean = true, error: String = "",
        message: String = "", channel: String = "", everyMs: Long = 0L, protected: Boolean = false,
    ) = AutomationJob(
        id, name, enabled, protected,
        schedule = if (everyMs > 0) "Every " + humanDuration(everyMs) else humanCron(cron),
        nextRunMs = next, lastRunMs = last, lastStatus = status, lastError = error, pending = false, message = message, channel = channel,
        scheduleKind = if (everyMs > 0) "every" else "cron", cronExpr = cron, tz = if (everyMs > 0) "" else java.time.ZoneId.systemDefault().id, everyMs = everyMs,
    )

    override suspend fun automations(): List<AutomationJob> = if (empty) emptyList() else automations.toList()

    override suspend fun automationAction(id: String, action: String) {
        val i = automations.indexOfFirst { it.id == id }
        if (i < 0) return
        when (action) {
            "enable" -> automations[i] = automations[i].copy(enabled = true)
            "disable" -> automations[i] = automations[i].copy(enabled = false)
            "run" -> automations[i] = automations[i].copy(lastRunMs = System.currentTimeMillis(), lastStatus = "ok", lastError = "")
            "delete" -> if (!automations[i].protected) automations.removeAt(i) else throw ChatException("Tidy memory is built in: turn it off instead.")
        }
    }

    override suspend fun automationUpdate(id: String, values: JSONObject) {
        val i = automations.indexOfFirst { it.id == id }
        if (i < 0) return
        val j = automations[i]
        val schedule = values.optJSONObject("schedule")
        automations[i] = j.copy(
            name = values.optString("name", j.name),
            message = values.optString("message", j.message),
            schedule = if (schedule != null) io.github.erkanw.vault.chat.describeSchedule(schedule) else j.schedule,
            scheduleKind = schedule?.optString("kind") ?: j.scheduleKind,
            cronExpr = schedule?.optString("expr") ?: j.cronExpr,
            tz = schedule?.optString("tz") ?: j.tz,
            everyMs = schedule?.optLong("every_ms", 0L) ?: j.everyMs,
            atMs = schedule?.optLong("at_ms", 0L) ?: j.atMs,
        )
    }

    private companion object {
        val PLAN_MD = """
            # Physics: spaced plan

            Three weeks, **20 minutes** a session, quizzes from your own notes.

            ## Week 1
            - [x] Mechanics: forces and motion
            - [ ] Energy and work
            - [ ] Quiz: chapters 1 and 2

            ## Week 2
            1. Waves
            2. Electricity
            3. Practice exam on Thursday

            > Keep one evening free before the exam.

            | Day | Topic | Minutes |
            | --- | --- | --- |
            | Mon | Forces | 20 |
            | Wed | Energy | 20 |
            | Fri | Quiz | 30 |
        """.trimIndent()

        val PLAN_ICS = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Apel//Study plan//EN
            BEGIN:VEVENT
            UID:physics-week1-mon@apel
            DTSTART:20261116T180000
            DTEND:20261116T182000
            SUMMARY:Physics: forces and motion
            END:VEVENT
            BEGIN:VEVENT
            UID:physics-week1-wed@apel
            DTSTART:20261118T180000
            DTEND:20261118T182000
            SUMMARY:Physics: energy and work
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()
    }
}
