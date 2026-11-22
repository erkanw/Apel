package io.github.erkanw.vault.tools

import android.content.Context
import io.github.erkanw.vault.agent.AgentScope
import io.github.erkanw.vault.agent.Delegation
import io.github.erkanw.vault.agent.messageNote
import io.github.erkanw.vault.agent.subagentPrompt
import io.github.erkanw.vault.agent.systemPrompt
import io.github.erkanw.vault.store.AgentProfile
import io.github.erkanw.vault.store.Agents
import io.github.erkanw.vault.store.ModelStore
import org.json.JSONObject
import java.time.ZonedDateTime

/**
 * A tool that runs a turn of its own (a subagent, another agent's answer): it keeps its own limits, reports what it is
 * doing through the hint it is given, and returns that turn's final answer.
 */
interface DelegatingTool : Tool {
    suspend fun runWith(args: JSONObject, ctx: ToolContext, hint: suspend (String) -> Unit): String
    override suspend fun run(args: JSONObject, ctx: ToolContext): String = runWith(args, ctx) {}
    override val ownLimit: Boolean get() = true
}

/**
 * Agents working together (PLAN §6.16): an agent can message the user's other agents, and start subagents for big tasks.
 * Both ask first by default, and each agent has switches for them on its page (kept with its switched-off tools, like
 * any tool's): [MESSAGE], [SUBAGENT], and [SUBAGENT_MODEL] for choosing a subagent's model (off: the agent's own model).
 * A subagent or a messaged agent can't message agents or start subagents itself.
 */
object AgentTools {
    const val MESSAGE = "agent_message"
    const val SUBAGENT = "subagent_run"

    /** Not a tool: the switch for choosing a subagent's model, kept with the agent's switched-off tools. */
    const val SUBAGENT_MODEL = "subagent_model"

    val NAMES = setOf(MESSAGE, SUBAGENT)

    /** The most subagents that run at once. */
    const val MAX_TOGETHER = 3

    /** What a subagent can't use: memory (it doesn't see it), anything that lasts beyond its task, notifications. */
    val SUBAGENT_EXCLUDED = setOf(
        "memory_read", "memory_write", "skill_save", "automation_create", "automation_update", "automation_delete", "notify",
    ) + NAMES

    /** What another agent can't use while it answers a message: automations, skills, notifications. Its own memory it keeps. */
    val MESSAGE_EXCLUDED = setOf("skill_save", "automation_create", "automation_update", "automation_delete", "notify") + NAMES

    /** One line about an agent for the others: what it is for, from its prompt's first sentence. */
    fun blurb(prompt: String?): String {
        val first = prompt.orEmpty().lineSequence().map { it.trim().trimStart('#', '-', '*', ' ') }.firstOrNull { it.isNotEmpty() }.orEmpty()
        val sentence = first.split(Regex("(?<=[.!?])\\s")).firstOrNull().orEmpty()
        return if (sentence.length <= 140) sentence else sentence.take(139).trimEnd() + "…"
    }

    /** The step's text: the tools a delegated turn used, once each, in order. */
    fun usedLine(steps: List<String>): String {
        val tools = steps.mapNotNull { s -> s.substringBefore(' ').substringBefore('\n').takeIf { it.contains('_') || it == "now" || it == "notify" } }.distinct()
        return if (tools.isEmpty()) "" else "Used: " + tools.joinToString(", ")
    }

    // ---- messaging another agent ----

    object Message : DelegatingTool {
        override val name = MESSAGE
        override val title = "Message other agents"
        override val description = "Asks another of your agents something it knows, from its memory, files or databases. Asks you first."
        override val instructions = "Send a message to another of the user’s agents and get its answer."
        override val schema: JSONObject = params("agent" to str("The agent’s id."), "message" to str("What you ask or tell it."))
        override val defaultLevel = ToolLevel.Ask

        private fun others(agent: AgentScope?) = Agents.list.value.filter { it.id != (agent?.id ?: io.github.erkanw.vault.agent.AgentPresets.APEL) }

        override fun offered(context: Context, agent: AgentScope?) = others(agent).isNotEmpty()

        override fun instructionsFor(context: Context, agent: AgentScope?): String = buildString {
            append("Send a message to another of the user’s agents in Apel and get its answer. Use it when another agent knows ")
            append("something you need, from its memory, notes, files or databases, or its job (for example what the training ")
            append("coach planned this week), or to pass on something it should know. Not for what you can do yourself. ")
            append("The user approves each message. Write it so it stands on its own: the other agent doesn’t see this chat.\n")
            append("The user’s other agents:")
            others(agent).forEach { a ->
                append("\n- ").append(a.name).append(" (id: ").append(a.id).append(")")
                blurb(a.row.prompt ?: io.github.erkanw.vault.agent.AgentPresets.defaultPrompt(a.name)).takeIf { it.isNotBlank() }?.let { append(": ").append(it) }
            }
        }

        override fun schemaFor(context: Context, agent: AgentScope?): JSONObject = params(
            "agent" to enumOf("The id of the agent to message.", *others(agent).map { it.id }.toTypedArray()),
            "message" to str("What you ask or tell it, complete: it doesn’t see this chat."),
        )

        private fun nameOf(id: String) = Agents.list.value.firstOrNull { it.id == id }?.name ?: id

        override fun hint(args: JSONObject) = "Asking ${nameOf(args.optString("agent"))}"
        override fun step(args: JSONObject) = "agent_message ${nameOf(args.optString("agent"))}\n“${args.optString("message").trim().take(1_000)}”"
        override fun ask(args: JSONObject) = io.github.erkanw.vault.tools.ApprovalAsk("Message ${nameOf(args.optString("agent"))}", args.optString("message").trim())
        override fun similar(args: JSONObject, ctx: ToolContext): Similar? =
            args.optString("agent").takeIf { it.isNotBlank() }?.let { Similar("$MESSAGE|$it", "messages to ${nameOf(it)}") }

        override fun precheck(args: JSONObject, ctx: ToolContext): String? {
            val id = args.optString("agent")
            if (id == ctx.agent.id) return "That is you. Message another agent."
            if (Agents.list.value.none { it.id == id }) return "There is no agent “$id”. These are the others: ${others(ctx.agent).joinToString { it.id }}."
            return null
        }

        override suspend fun runWith(args: JSONObject, ctx: ToolContext, hint: suspend (String) -> Unit): String {
            val id = args.need("agent")
            val message = args.need("message")
            val target = Agents.scope(ctx.app, id)
            if (target.id != id) throw ToolException("There is no agent “$id”.")
            val system = systemPrompt(
                target.name, ZonedDateTime.now(), null, MemoryTools.forPrompt(ctx.app, target), true, target.prompt,
                AgentProfile.aboutYou(ctx.app), SkillStore.index(ctx.app, target), memoryPath = target.memoryPath,
            ) + "\n\n" + messageNote(ctx.agent.name)
            val outcome = Delegation.run(
                ctx.app, target, Delegation.chain(ctx.app, target.model), system, message, ctx,
                delegate = "${target.name}, for ${ctx.agent.name}", excluded = MESSAGE_EXCLUDED, usageAgent = target.id,
                onHint = { hint("${target.name}: $it") },
            )
            // What another agent read on the web is as untrusted as a page.
            if (outcome.readWeb) ctx.readWeb = true
            val used = usedLine(outcome.steps).let { if (it.isEmpty()) "" else "\n($it)" }
            return if (outcome.ok) "${target.name} answered:\n${outcome.text}$used" else "${target.name} couldn’t answer: ${outcome.text}"
        }
    }

    // ---- subagents ----

    object Subagent : DelegatingTool {
        override val name = SUBAGENT
        override val title = "Start subagents"
        override val description = "For a big task, hands the work to a helper with a fresh chat, so the agent’s own chat stays short. Asks you first."
        override val instructions = "Hand one big, self-contained task to a subagent and get its final answer."
        override val schema: JSONObject = params("title" to str("A few words for the task."), "task" to str("The whole task."))
        override val defaultLevel = ToolLevel.Ask

        /** Whether [agent] may choose its subagents' model (its switch on its page). */
        fun choosesModel(agent: AgentScope?) = agent?.toolOn(SUBAGENT_MODEL) ?: true

        private fun presets(context: Context) = ModelStore(context).load().presets

        override fun instructionsFor(context: Context, agent: AgentScope?): String = buildString {
            append("Hand one big, self-contained task to a subagent: a helper that starts with a fresh chat, works on its own ")
            append("with your tools (not your memory) and returns only its final answer, so your own chat stays short. Use it ")
            append("only when a task would fill your chat with material you won’t need afterwards: reading many pages to find ")
            append("and compare prices, going deep through search results, working through a long document. Never for what ")
            append("you can do in a few steps yourself, and not for every task: each one costs and the user must approve it. ")
            append("Put everything it needs in the task (what to find, where, in what form to answer): it doesn’t see this chat, ")
            append("your memory or About you. Several calls in one reply run at the same time (at most $MAX_TOGETHER), ")
            append("for separate parts of a job.")
            if (choosesModel(agent)) {
                val list = presets(context)
                if (list.isNotEmpty()) {
                    append("\nChoose its model from the user’s models: a cheaper, faster one for searching and reading, a ")
                    append("stronger one for hard reasoning. Leave it out to use your own.")
                    val catalog = io.github.erkanw.vault.chat.ModelsHub.catalog.value.models
                    list.forEach { p ->
                        append("\n- ").append(p.name).append(": ").append(p.label.ifBlank { p.model })
                        val m = catalog.firstOrNull { it.id == p.model }
                        if (m != null) append(" (").append(io.github.erkanw.vault.provider.priceLine(m.free, m.bestIn, m.bestOut)).append(")")
                        else if (p.model.endsWith(":free")) append(" (free)")
                    }
                }
            } else {
                append(" It uses the same model as you.")
            }
        }

        override fun schemaFor(context: Context, agent: AgentScope?): JSONObject {
            val props = mutableListOf(
                "title" to str("A few words for what it does, shown to the user, like “Prices of the XM5 at 4 shops”."),
                "task" to str("The whole task, complete: what to find or do, where, and what to answer with."),
            )
            val names = presets(context).map { it.name }
            if (choosesModel(agent) && names.isNotEmpty()) props += "model" to enumOf("The model it uses; leave out for your own.", *names.toTypedArray())
            return params(*props.toTypedArray(), required = listOf("title", "task"))
        }

        /** The preset a call runs on: its own choice when the agent may choose, else the turn's model. */
        fun modelFor(args: JSONObject, ctx: ToolContext): String? {
            val chosen = args.optString("model").takeIf { it.isNotBlank() && choosesModel(ctx.agent) }
            val presets = presets(ctx.app)
            return chosen?.takeIf { n -> presets.any { it.name == n } } ?: ctx.model ?: ctx.agent.model
        }

        private fun label(ctx: ToolContext, preset: String?): String {
            val p = ModelStore(ctx.app).preset(preset) ?: Delegation.chain(ctx.app, preset).firstOrNull()
            return p?.let { it.label.ifBlank { it.model } } ?: "your main model"
        }

        private fun titleOf(args: JSONObject) = args.optString("title").trim().ifBlank { "a task" }.take(80)

        override fun hint(args: JSONObject) = "Subagent: ${titleOf(args)}"
        override fun step(args: JSONObject) = "subagent_run “${titleOf(args)}”"
        override fun ask(args: JSONObject) = ApprovalAsk("Start a subagent: ${titleOf(args)}", args.optString("task").trim())
        override fun askFor(args: JSONObject, ctx: ToolContext) =
            ApprovalAsk("Start a subagent: ${titleOf(args)}", "Model: ${label(ctx, modelFor(args, ctx))}\n\n" + args.optString("task").trim())
        override fun similar(args: JSONObject, ctx: ToolContext): Similar {
            val preset = modelFor(args, ctx)
            return Similar("$SUBAGENT|${preset.orEmpty()}", "subagents on ${label(ctx, preset)}")
        }

        override suspend fun runWith(args: JSONObject, ctx: ToolContext, hint: suspend (String) -> Unit): String {
            val title = titleOf(args)
            val task = args.need("task")
            val preset = modelFor(args, ctx)
            val chain = Delegation.chain(ctx.app, preset, ctx.agent.model)
            val system = subagentPrompt(ctx.agent.name, title, ZonedDateTime.now(), SkillStore.index(ctx.app, ctx.agent))
            val outcome = Delegation.run(
                ctx.app, ctx.agent, chain, system, task, ctx,
                delegate = "Subagent “$title”", excluded = SUBAGENT_EXCLUDED, usageAgent = ctx.agent.id,
                onHint = { hint("$title: $it") },
            )
            // What it read on the web is as untrusted as a page, and so is its answer.
            if (outcome.readWeb) ctx.readWeb = true
            val used = usedLine(outcome.steps).let { if (it.isEmpty()) "" else "; ${it.replaceFirstChar { c -> c.lowercase() }}" }
            val about = "(${outcome.model}$used)"
            return if (outcome.ok) "Subagent “$title” answered $about:\n${outcome.text}" else "Subagent “$title” failed $about: ${outcome.text}"
        }
    }
}
