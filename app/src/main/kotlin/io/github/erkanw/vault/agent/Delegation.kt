package io.github.erkanw.vault.agent

import android.content.Context
import io.github.erkanw.vault.chat.ModelPreset
import io.github.erkanw.vault.chat.ThinkFilter
import io.github.erkanw.vault.provider.ChatRequest
import io.github.erkanw.vault.provider.ProviderClient
import io.github.erkanw.vault.provider.ProviderException
import io.github.erkanw.vault.provider.ProviderKind
import io.github.erkanw.vault.provider.ProviderStore
import io.github.erkanw.vault.provider.StreamPart
import io.github.erkanw.vault.store.ModelStore
import io.github.erkanw.vault.store.UsageEntity
import io.github.erkanw.vault.tools.ToolContext
import io.github.erkanw.vault.tools.ToolRegistry
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * A turn without a chat (PLAN §6.16): a subagent doing one task, or another agent answering a message. It runs the same
 * loop as a chat's turn (the model answers or calls tools, which ask first where they must) with its own context, under
 * its own limits, and returns only its final answer. Nothing is saved as a chat; what it cost goes to Usage. Its tool
 * approvals appear in the chat that asked, saying whose they are.
 */
object Delegation {
    const val MAX_ROUNDS = 30
    const val MAX_MS = 10 * 60_000L

    /** How it went: the final answer, the model that gave it, its steps (for the asking chat's step), and whether it read the web. */
    class Outcome(val text: String, val ok: Boolean, val model: String, val cost: Double?, val readWeb: Boolean, val steps: List<String>)

    /** A tool call as it streams in. */
    private class Call(var id: String = "", var name: String = "", val args: StringBuilder = StringBuilder())

    /**
     * The models to try for a delegated turn: [first] (a preset's name), then [then], then the main model and its
     * fallbacks, each once.
     */
    fun chain(context: Context, first: String?, then: String? = null): List<ModelPreset> {
        val models = ModelStore(context)
        return buildList {
            listOf(first, then).forEach { n -> models.preset(n)?.let { p -> if (none { it.name == p.name }) add(p) } }
            models.callChain().forEach { p -> if (none { it.name == p.name }) add(p) }
        }
    }

    /**
     * Runs [task] for [scope] with [system] as its prompt. Its tool context comes from [parent] (the same chat, for
     * approvals; the same automation, for notifications), marked as [delegate] and kept from [excluded]. [usageAgent] is
     * who its cost is counted for. [onHint] hears what it is doing.
     */
    suspend fun run(
        app: Context, scope: AgentScope, chain: List<ModelPreset>, system: String, task: String, parent: ToolContext,
        delegate: String, excluded: Set<String>, usageAgent: String, onHint: suspend (String) -> Unit,
    ): Outcome {
        val target = ProviderStore.target(app) ?: return Outcome("No AI provider is connected.", false, "", null, false, emptyList())
        if (chain.isEmpty()) return Outcome("No model chosen yet.", false, "", null, false, emptyList())
        val ctx = ToolContext(app, parent.sessionId, automation = parent.automation, channelId = parent.channelId, agent = scope)
        ctx.delegate = delegate
        ctx.excluded = excluded
        ctx.see(task)
        val openRouter = target.kind == ProviderKind.OpenRouter
        val state = ProviderStore.state.value
        var tools = ToolRegistry.specs(app, target.kind, scope, exclude = excluded + io.github.erkanw.vault.tools.AgentTools.NAMES)
        var used = chain.first()
        var modelAt = 0
        val steps = ArrayList<String>()
        val rounds = ArrayList<JSONObject>()
        var tokensIn = 0L
        var tokensOut = 0L
        var cost: Double? = null
        var requests = 0
        var answer = ""
        var ok = true
        val begun = System.currentTimeMillis()
        try {
            var round = 0
            while (true) {
                if (round >= MAX_ROUNDS || System.currentTimeMillis() - begun - ctx.waitedMs > MAX_MS) {
                    answer = (answer + "\n\n(Stopped here: this was taking too long. What is above is what was found so far.)").trim()
                    break
                }
                round++
                ctx.model = used.name
                var raw = ""
                val calls = sortedMapOf<Int, Call>()
                val messages = JSONArray()
                    .put(JSONObject().put("role", "system").put("content", system))
                    .put(JSONObject().put("role", "user").put("content", task))
                rounds.forEach { messages.put(it) }
                val request = ChatRequest(
                    model = used.model, messages = messages, maxTokens = used.maxTokens,
                    privateOnly = openRouter && state.privateOnly && used.model !in state.privateExempt,
                    tools = tools,
                )
                try {
                    ProviderClient.stream(target, request).collect { part ->
                        when (part) {
                            is StreamPart.Text -> raw += part.text
                            is StreamPart.Reasoning -> Unit
                            is StreamPart.Usage -> {
                                tokensIn += part.promptTokens
                                tokensOut += part.completionTokens
                                requests++
                                part.cost?.let { cost = (cost ?: 0.0) + it }
                            }
                            is StreamPart.Citation -> {
                                ctx.see(part.url)
                                if (!ctx.readWeb) onHint("Searching the web")
                                ctx.readWeb = true
                            }
                            is StreamPart.ToolCall -> {
                                val c = calls.getOrPut(part.index) { Call() }
                                part.id?.let { c.id = it }
                                part.name?.let { c.name = it }
                                c.args.append(part.args)
                            }
                        }
                    }
                } catch (e: ProviderException) {
                    if (raw.isNotEmpty() || calls.isNotEmpty()) throw e
                    val toolTrouble = tools.length() > 0 && e.status in setOf(400, 404, 422) && e.detail.contains("tool", ignoreCase = true)
                    val next = chain.getOrNull(modelAt + 1)
                    when {
                        toolTrouble && openRouter && e.detail.contains("web_search", ignoreCase = true) -> tools = withoutSearch(tools)
                        toolTrouble -> tools = JSONArray()
                        e.fallback && round == 1 && next != null -> {
                            steps += "${used.label} didn’t answer (${e.message}), so ${next.label} took over."
                            modelAt++
                            used = next
                        }
                        else -> throw e
                    }
                    round--
                    continue
                }
                val text = ThinkFilter.visible(raw).trim()
                if (calls.isEmpty()) {
                    answer = text
                    break
                }
                if (text.isNotEmpty()) steps += text.take(300)
                val callArray = JSONArray()
                calls.values.forEachIndexed { i, c ->
                    if (c.id.isBlank()) c.id = "call_${round}_$i"
                    callArray.put(JSONObject().put("id", c.id).put("type", "function").put("function", JSONObject().put("name", c.name).put("arguments", c.args.toString().ifBlank { "{}" })))
                }
                rounds += JSONObject().put("role", "assistant").put("content", if (raw.isBlank()) JSONObject.NULL else raw).put("tool_calls", callArray)
                for (c in calls.values) {
                    val result = ToolCalls.run(app, c.name, c.args.toString(), ctx, onStep = { steps += it.lineSequence().first() }, onHint = onHint)
                    rounds += JSONObject().put("role", "tool").put("tool_call_id", c.id).put("content", result)
                }
            }
            if (answer.isBlank()) {
                ok = false
                answer = "${used.label} sent an empty answer."
            }
        } catch (e: ProviderException) {
            ok = false
            answer = "It failed: ${e.message ?: "the provider failed."}"
        } finally {
            if (tokensIn > 0 || requests > 0) withContext(NonCancellable) {
                io.github.erkanw.vault.usage.UsageLog.record(
                    app,
                    UsageEntity(
                        time = System.currentTimeMillis(), model = used.model, source = if (parent.automation != null) "automation" else "user",
                        sessionId = parent.sessionId, automationId = parent.automation, tokensIn = tokensIn, tokensOut = tokensOut, cost = cost,
                        ok = ok, requests = requests.coerceAtLeast(1), agentId = usageAgent.takeIf { it != AgentPresets.APEL },
                    ),
                )
            }
        }
        return Outcome(answer, ok, used.label.ifBlank { used.model }, cost, ctx.readWeb, steps)
    }

    private fun withoutSearch(tools: JSONArray) = JSONArray().apply {
        for (i in 0 until tools.length()) tools.optJSONObject(i)?.takeIf { it.optString("type") != "openrouter:web_search" }?.let { put(it) }
    }
}
