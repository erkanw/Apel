package io.github.erkanw.vault.chat

/*
 * Writing a new skill. The agent’s server has no call that creates one (its WebUI can only install skills from a catalogue),
 * but a skill is a folder in the agent’s workspace with a SKILL.md in it, which the agent can write itself. So the app builds the
 * file and asks the agent, in a new chat, to save it exactly as given. You read the request and send it.
 */

/** A skill's folder name: lower case letters, digits and single hyphens ("study-planner"), at most 48 characters. */
fun skillSlug(name: String): String =
    name.trim().lowercase()
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
        .take(48)
        .trim('-')

/** The SKILL.md for a skill: front matter with its name and description (what the agent reads to pick it), then the instructions. */
fun skillMarkdown(slug: String, description: String, instructions: String): String = buildString {
    appendLine("---")
    appendLine("name: $slug")
    // One line, quoted, so a colon in it doesn't break the front matter.
    appendLine("description: \"" + description.trim().replace(Regex("\\s+"), " ").replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
    appendLine("---")
    appendLine()
    append(instructions.trim())
    appendLine()
}

/** Why the form can't make a skill yet, or null when it can. */
fun skillProblem(name: String, description: String, instructions: String, taken: Set<String>): String? {
    val slug = skillSlug(name)
    return when {
        slug.isEmpty() -> "Give it a name."
        slug in taken -> "There is already a skill called $slug."
        description.isBlank() -> "Say in one line what it does: this is read to know when to use it."
        instructions.isBlank() -> "Write the instructions to follow."
        else -> null
    }
}

/** The message that asks the agent to save the skill's file as it is. */
fun createSkillRequest(slug: String, description: String, instructions: String): String = buildString {
    appendLine("Please create a new skill in your workspace: save the file below as skills/$slug/SKILL.md, exactly as written between the markers (do not reword or improve it), without changing any other skill. If that folder already exists, stop and tell me instead of overwriting it.")
    appendLine()
    appendLine("<<<")
    append(skillMarkdown(slug, description, instructions))
    appendLine(">>>")
    appendLine()
    append("When it is saved, tell me whether it shows up in your skills list.")
}
