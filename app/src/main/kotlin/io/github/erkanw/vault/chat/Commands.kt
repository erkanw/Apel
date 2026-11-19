package io.github.erkanw.vault.chat

// Slash commands and model presets, as the composer and the Models page show them.

/** One slash command: [name] includes the slash ("/compact"); [argHint] is empty for a command without arguments. */
data class SlashCommand(
    val name: String,
    val title: String,
    val description: String,
    val argHint: String,
    val acceptsArgs: Boolean,
    /** Answers on the side, without a turn of its own. */
    val sideChannel: Boolean = false,
)

/** The commands the app offers (PLAN §3): done on the phone, not sent to the model as text. */
val LOCAL_COMMANDS = listOf(
    SlashCommand("/new", "New chat", "Start a new chat", "", false),
    SlashCommand("/stop", "Stop", "Stop the answer being written", "", false),
    SlashCommand("/model", "Model", "Pick the model for this chat", "", false),
    SlashCommand("/compact", "Compact", "Summarise the older part of this chat to save room", "", false, sideChannel = true),
    SlashCommand("/skill", "Skill", "Use one of your skills", "<name>", true),
    SlashCommand("/help", "Help", "What the commands do", "", false, sideChannel = true),
)

/**
 * The commands to offer for what is being typed: while the text is a slash and the start of a word
 * (no space yet), the commands that begin with it; otherwise none.
 */
fun matchingCommands(commands: List<SlashCommand>, typed: String): List<SlashCommand> {
    if (!typed.startsWith("/") || typed.any { it.isWhitespace() }) return emptyList()
    val prefix = typed.lowercase()
    return commands.filter { it.name.lowercase().startsWith(prefix) }
}

/** What the draft should become when a command is picked: the command and a space, ready for its argument. */
fun commandDraft(command: SlashCommand): String = command.name + " "

/** Commands the app does itself, rather than passing them on as a message. */
enum class LocalCommand { NewSession, Stop, PickModel }

/** `/new`, `/stop` and a bare `/model` are done by the screen; everything else goes to the backend as it was typed. */
fun localCommand(text: String): LocalCommand? = when (text.trim().lowercase()) {
    "/new" -> LocalCommand.NewSession
    "/stop" -> LocalCommand.Stop
    "/model" -> LocalCommand.PickModel
    else -> null
}

/** One model preset: [name] identifies it, [model] is the model behind it. */
data class ModelPreset(
    val name: String,
    val label: String,
    val model: String,
    val active: Boolean,
    val isDefault: Boolean = false,
    /** How many tokens the model can hold in mind at once; 0 if unknown. */
    val contextTokens: Long = 0L,
    /** The most it may write in one answer; 0 if unknown. */
    val maxTokens: Long = 0L,
    /** Left out of the chat's model list on this phone (see [ModelPrefs]). */
    val hidden: Boolean = false,
)

/**
 * The presets to choose from, and the one in use now. [callOrder] is the order of use: the main model first, then the
 * ones it falls back on; [callOrderEditable] says whether it can be changed.
 */
data class ModelChoices(
    val presets: List<ModelPreset>,
    val active: String?,
    val callOrder: List<String> = emptyList(),
    val callOrderEditable: Boolean = false,
) {
    companion object {
        val None = ModelChoices(emptyList(), null)
    }
}

/**
 * The preset the chat runs with: the one the session chose, else the one in use. A session that only carries the
 * "default" preset (a placeholder for "nothing chosen") counts as not having chosen.
 */
fun shownPreset(choices: ModelChoices, sessionPreset: String?, agentPreset: String? = null): ModelPreset? {
    val own = choices.presets.firstOrNull { it.name == sessionPreset && !it.isDefault }
    return own ?: choices.presets.firstOrNull { it.name == agentPreset } ?: choices.presets.firstOrNull { it.name == choices.active }
}

/**
 * A model's id written as a person would: "apodex/apodex-1.1-mini:free" is "Apodex 1.1 Mini",
 * "qwen/qwen3.7-flash" is "Qwen3.7 Flash", "qwen/qwen3.8-27b" is "Qwen3.8 27B",
 * "anthropic/claude-sonnet-5.5" is "Claude Sonnet 5.5". Blank in, blank out.
 */
fun prettyModelName(model: String): String {
    val name = model.substringAfterLast('/').substringBefore(':').trim()
    if (name.isEmpty()) return ""
    return name.split('-', '_').filter { it.isNotEmpty() }.joinToString(" ") { part ->
        when {
            // 27b, 70b: a size.
            part.matches(Regex("""\d+(\.\d+)?[bB]""")) -> part.uppercase()
            part.equals("gpt", ignoreCase = true) -> "GPT"
            part.first().isLetter() -> part.replaceFirstChar { it.uppercase() }
            else -> part
        }
    }
}
