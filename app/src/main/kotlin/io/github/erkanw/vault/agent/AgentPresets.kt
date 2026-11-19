package io.github.erkanw.vault.agent

import io.github.erkanw.vault.userdb.Access
import org.json.JSONArray
import org.json.JSONObject

/**
 * One agent as a turn sees it (PLAN §6.16): who it is, its prompt and model, and what it may use. Everything is on
 * unless switched off; a database cap can only narrow what the database itself allows. Pure: tested on the JVM.
 */
data class AgentScope(
    val id: String,
    val name: String,
    /** The system prompt as the agent reads it (the default filled in with its name when not set). */
    val prompt: String,
    /** A model preset's name; null: the main model and its fallbacks. */
    val model: String? = null,
    val toolsOff: Set<String> = emptySet(),
    val skillsOff: Set<String> = emptySet(),
    /** Database name → the most this agent may do there. */
    val dbCaps: Map<String, Access> = emptyMap(),
) {
    val isApel: Boolean get() = id == AgentPresets.APEL

    fun toolOn(name: String) = name !in toolsOff

    fun skillOn(name: String) = name !in skillsOff

    /** What this agent may do in a database the database itself opens at [own]. */
    fun access(db: String, own: Access): Access {
        val cap = dbCaps[db] ?: return own
        return if (cap.ordinal < own.ordinal) cap else own
    }

    /** Where this agent's memory is, inside the workspace. Apel keeps the memory it always had. */
    val memoryPath: String get() = if (isApel) "memory/MEMORY.md" else "memory/$id/MEMORY.md"
}

/** A ready-made agent offered by New agent: a name, a look and a prompt to start from. */
data class AgentTemplate(val id: String, val name: String, val blurb: String, val look: String, val prompt: String)

object AgentPresets {
    const val APEL = "apel"
    const val MAX_NAME = 24
    const val MAX_PROMPT = 8_000

    /**
     * The default system prompt, the same for every model: who the agent is and how it answers. The app adds the
     * time, About you, memory, skills and how to treat tool results around it.
     */
    fun defaultPrompt(name: String): String =
        "You are $name, the user’s own AI agent. You run in the Apel app on their Android phone.\n" +
            "Answer clearly and briefly: they read you on a phone. Use Markdown when it helps (short lists, small tables, code blocks).\n" +
            "Say so when you are unsure, and don’t invent facts, links or quotes."

    val templates = listOf(
        AgentTemplate(
            "study", "Study companion", "Explains, quizzes and plans revision",
            "shape=bean&color=sky&material=matte&eyes=dot&cheeks=1",
            "You are a patient study companion for a student.\n" +
                "Explain one step at a time, in plain words, and check understanding with a short question before moving on.\n" +
                "When asked to quiz, ask one question at a time and wait for the answer. Help plan revision in small, realistic sessions.\n" +
                "Never just hand over answers to graded work: guide the student to find them.",
        ),
        AgentTemplate(
            "research", "Researcher", "Searches, reads and cites sources",
            "shape=pebble&color=mint&material=glass&eyes=dot&cheeks=0",
            "You are a careful researcher.\n" +
                "Search the web and read the sources before answering anything current or specific. Prefer primary sources.\n" +
                "Give a short answer first, then the evidence, and cite every claim with a link. Say where sources disagree or are thin.\n" +
                "Never invent sources, figures or quotes.",
        ),
        AgentTemplate(
            "coach", "Training coach", "Plans workouts and tracks progress",
            "shape=pill&color=tangerine&material=soft&eyes=capsule&cheeks=1",
            "You are a supportive training coach.\n" +
                "Ask about goals, level, time and injuries before planning. Build progressive plans with clear sets, reps or durations and rest.\n" +
                "Keep a training log in a database when one is open to you, and adjust the plan to how sessions went.\n" +
                "You are not a doctor: for pain, injury or health conditions, advise seeing a professional.",
        ),
        AgentTemplate(
            "review", "Code reviewer", "Reviews code for bugs and clarity",
            "shape=squircle&color=lilac&material=matte&eyes=dot&cheeks=0",
            "You are a thorough code reviewer.\n" +
                "Look for correctness bugs first, then security, then clarity and simplicity. Point to the exact line and explain why it matters.\n" +
                "Suggest the smallest fix, with a code block. Separate must-fix issues from optional suggestions.\n" +
                "Don’t rewrite working code for style alone.",
        ),
    )

    fun template(id: String): AgentTemplate? = templates.firstOrNull { it.id == id }

    // ---- the stored lists ----

    fun setOf(json: String?): Set<String> = try {
        val a = JSONArray(json ?: "[]")
        (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }.toSet()
    } catch (e: Exception) {
        emptySet()
    }

    fun setJson(set: Set<String>): String = JSONArray(set.sorted()).toString()

    fun capsOf(json: String?): Map<String, Access> = try {
        val o = JSONObject(json ?: "{}")
        o.keys().asSequence().mapNotNull { k -> Access.entries.firstOrNull { it.id == o.optString(k) }?.let { k to it } }.toMap()
    } catch (e: Exception) {
        emptyMap()
    }

    fun capsJson(caps: Map<String, Access>): String = JSONObject().apply { caps.toSortedMap().forEach { (k, v) -> put(k, v.id) } }.toString()

    /** A new agent's id from its name: "Study companion" → "study-companion", "-2"… when taken; never "apel". */
    fun idFor(name: String, taken: Set<String>): String {
        val base = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(24).ifBlank { "agent" }
        var id = base
        var n = 2
        while (id in taken || id == APEL) id = "$base-${n++}"
        return id
    }
}
