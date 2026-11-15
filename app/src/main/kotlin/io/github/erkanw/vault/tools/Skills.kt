package io.github.erkanw.vault.tools

import android.content.Context
import androidx.core.content.edit
import org.json.JSONObject
import java.io.File

/** One skill: its slug, one line on when to use it, and its instructions. Built-in skills are read-only. */
data class Skill(val name: String, val description: String, val body: String, val builtIn: Boolean, val enabled: Boolean)

/**
 * Skills in the Agent Skills layout (PLAN §6.7): `workspace/skills/<slug>/SKILL.md` with `name` and `description` in its
 * front matter, plus the built-in starters. The agent sees an index of the enabled ones and loads one with `read_skill`.
 */
object SkillStore {
    private const val PREFS = "skills"
    private const val KEY_OFF = "off"
    val SLUG = Regex("^[a-z0-9][a-z0-9-]{0,47}$")

    private val starters = listOf(
        Skill(
            "web-research", "Researching a question on the web: search, read several sources, cite them.",
            """
            When the user asks something that needs current or checkable facts:
            1. Search the web; pick two or three good sources, not just the first result.
            2. Read the most useful pages with web_fetch, not only the search snippets.
            3. Answer briefly, with links to the sources you used (markdown links named by their site).
            4. Say what is uncertain or where sources disagree. Never invent a source.
            """.trimIndent(), builtIn = true, enabled = true,
        ),
        Skill(
            "notes", "Keeping notes for the user in the workspace.",
            """
            Keep notes in notes/, one Markdown file per topic (notes/physics.md, notes/trip-to-rome.md).
            - Before writing, list notes/ and read the file if it exists; add to it rather than starting over.
            - Start each file with a # title; keep entries short, newest at the bottom, with the date when it matters.
            - Say which file you wrote, with its path.
            Facts about the user themselves go in memory (memory_write), not in notes.
            """.trimIndent(), builtIn = true, enabled = true,
        ),
        Skill(
            "daily-brief", "A short morning brief: the day, the user's notes and anything worth knowing.",
            """
            A daily brief is short enough to read in a minute:
            - The date, and the weather where the user is if you know where that is (memory), from the web.
            - What their notes and memory say is coming up today or soon.
            - At most three items of news on topics they care about (memory), each one line with a link.
            End with nothing else: no offers, no questions.
            """.trimIndent(), builtIn = true, enabled = true,
        ),
        Skill(
            "scheduling", "Turning a request into a scheduled job with a clear instruction and schedule.",
            """
            When the user wants something done regularly or later:
            - Restate it as one clear instruction that will make sense on its own when it runs.
            - Pick the schedule in plain words first (every weekday at 08:00), then check it with the user.
            - Say where the result will appear (a channel or a new chat).
            Scheduled jobs arrive in a later version; until then, say so and offer to remind them another way.
            """.trimIndent(), builtIn = true, enabled = true,
        ),
    )

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun off(context: Context) = prefs(context).getStringSet(KEY_OFF, emptySet()).orEmpty()
    fun dir(context: Context) = File(Workspace.root(context), "skills").apply { mkdirs() }

    fun all(context: Context): List<Skill> {
        val disabled = off(context)
        val mine = dir(context).listFiles().orEmpty().filter { it.isDirectory && SLUG.matches(it.name) }.mapNotNull { d ->
            val f = File(d, "SKILL.md")
            if (!f.isFile) null else parse(f.readText(), d.name).copy(enabled = d.name !in disabled)
        }
        val names = mine.map { it.name }.toSet()
        return starters.filter { it.name !in names }.map { it.copy(enabled = it.name !in disabled) } + mine.sortedBy { it.name }
    }

    fun get(context: Context, name: String): Skill? = all(context).firstOrNull { it.name == name }

    fun setEnabled(context: Context, name: String, on: Boolean) {
        val set = off(context).toMutableSet()
        if (on) set.remove(name) else set.add(name)
        prefs(context).edit { putStringSet(KEY_OFF, set) }
    }

    /** Saves a skill of the user's (a starter's name makes the user's own copy, which then wins). */
    fun save(context: Context, name: String, description: String, body: String) {
        if (!SLUG.matches(name)) throw ToolException("A skill name is lowercase letters, digits and dashes, like trip-planner.")
        if (description.isBlank()) throw ToolException("A skill needs a one-line description.")
        val d = File(dir(context), name).apply { mkdirs() }
        File(d, "SKILL.md").writeText(render(name, description, body))
    }

    /** Moves a skill of the user's to the Trash; built-in starters can only be switched off. */
    fun delete(context: Context, name: String) {
        val d = File(dir(context), name)
        if (!d.isDirectory) throw ToolException("Built-in skills can’t be deleted; switch them off instead.")
        val dest = File(Workspace.trash(context), "${System.currentTimeMillis()}-$name")
        if (!d.renameTo(dest)) throw ToolException("It couldn’t be moved to the Trash.")
        File(dest.path + ".from").writeText("skills/$name")
    }

    fun render(name: String, description: String, body: String) =
        "---\nname: $name\ndescription: ${description.replace("\n", " ").trim()}\n---\n\n${body.trim()}\n"

    /** A SKILL.md's front matter and body; [fallback] names it when the front matter doesn't. */
    fun parse(text: String, fallback: String): Skill {
        val t = text.replace("\r", "")
        var name = fallback
        var description = ""
        var body = t
        if (t.startsWith("---\n")) {
            val end = t.indexOf("\n---", 4)
            if (end > 0) {
                t.substring(4, end).lines().forEach { line ->
                    val k = line.substringBefore(':').trim()
                    val v = line.substringAfter(':', "").trim().trim('"', '\'')
                    if (k == "name" && v.isNotBlank()) name = v
                    if (k == "description") description = v
                }
                body = t.substring(end + 4).trimStart('-').trim()
            }
        }
        return Skill(name, description, body.trim(), builtIn = false, enabled = true)
    }

    /** The system prompt's skills index: the enabled skills, one line each. */
    fun index(context: Context): String = all(context).filter { it.enabled }.joinToString("\n") { "- ${it.name}: ${it.description}" }

    object ListTool : Tool {
        override val name = "skill_list"
        override val title = "List skills"
        override val description = "Sees which skills there are."
        override val instructions = "List every skill with its description and whether it is on."
        override val schema = params()
        override val defaultLevel = ToolLevel.Allowed
        override fun hint(args: JSONObject) = "Looking at the skills"
        override fun step(args: JSONObject) = "skill_list"
        override suspend fun run(args: JSONObject, ctx: ToolContext) =
            all(ctx.app).joinToString("\n") { "${it.name}${if (it.builtIn) " (built in)" else ""}${if (it.enabled) "" else " (off)"}: ${it.description}" }
    }

    object ReadTool : Tool {
        override val name = "read_skill"
        override val title = "Use skills"
        override val description = "Loads a skill’s instructions when it fits the task."
        override val instructions = "Load a skill's full instructions by name (from the skills index), then follow them."
        override val schema = params("name" to str("The skill's name, like web-research."))
        override val defaultLevel = ToolLevel.Allowed
        override fun hint(args: JSONObject) = "Reading the ${args.optString("name")} skill"
        override fun step(args: JSONObject) = "read_skill " + args.optString("name")
        override suspend fun run(args: JSONObject, ctx: ToolContext): String {
            val s = get(ctx.app, args.need("name")) ?: throw ToolException("No skill called “${args.optString("name")}”. Use skill_list.")
            if (!s.enabled) throw ToolException("That skill is switched off.")
            return "# Skill: ${s.name}\n${s.description}\n\n${s.body}"
        }
    }

    object SaveTool : Tool {
        override val name = "skill_save"
        override val title = "Make skills"
        override val description = "Saves a new or changed skill. Asks first."
        override val instructions = "Save a skill: instructions you or the user want followed whenever a kind of task comes up. " +
            "name is lowercase-with-dashes; description says in one line when to use it; instructions are the steps."
        override val schema = params(
            "name" to str("lowercase-with-dashes, like trip-planner"),
            "description" to str("One line: when to use it."),
            "instructions" to str("The steps to follow, in Markdown."),
        )
        override val defaultLevel = ToolLevel.Ask
        override fun hint(args: JSONObject) = "Saving the ${args.optString("name")} skill"
        override fun step(args: JSONObject) = "skill_save " + args.optString("name")
        override fun ask(args: JSONObject) = ApprovalAsk("Save the skill “${args.optString("name")}”", args.optString("description") + "\n\n" + args.optString("instructions").take(600))
        override suspend fun run(args: JSONObject, ctx: ToolContext): String {
            save(ctx.app, args.need("name"), args.need("description"), args.need("instructions"))
            return "Saved the skill ${args.optString("name")}."
        }
    }
}
