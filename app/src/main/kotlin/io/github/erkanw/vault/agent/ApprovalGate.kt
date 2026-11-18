package io.github.erkanw.vault.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * Tools waiting for the user's OK (PLAN §7.5). The runtime asks and suspends; the chat shows the waiting request on the
 * approval card and answers it. Unanswered after 30 minutes it gives up ([Answer.Timeout]); a stopped turn takes it away.
 */
object ApprovalGate {
    enum class Answer { Allow, AllowAlways, Deny, Timeout }

    /** [canAlways]: whether "Always allow this tool" is offered (not when something other than the level asked). */
    data class Request(val id: String, val sessionId: String, val tool: String, val summary: String, val detail: String, val canAlways: Boolean = true)

    private const val TIMEOUT_MS = 30 * 60_000L

    private val waiting = HashMap<String, CompletableDeferred<Answer>>()
    private val _pending = MutableStateFlow<List<Request>>(emptyList())

    /** Every request waiting now, oldest first. */
    val pending: StateFlow<List<Request>> = _pending.asStateFlow()

    /** Suspends until the user answers (or the time runs out, which is a no). */
    suspend fun ask(sessionId: String, tool: String, summary: String, detail: String, canAlways: Boolean = true, id: String = UUID.randomUUID().toString()): Answer {
        val request = Request(id, sessionId, tool, summary, detail, canAlways)
        val answer = CompletableDeferred<Answer>()
        synchronized(waiting) { waiting[request.id] = answer }
        _pending.update { it + request }
        try {
            return withTimeoutOrNull(TIMEOUT_MS) { answer.await() } ?: Answer.Timeout
        } finally {
            synchronized(waiting) { waiting.remove(request.id) }
            _pending.update { list -> list.filterNot { it.id == request.id } }
        }
    }

    fun answer(id: String, answer: Answer) {
        synchronized(waiting) { waiting[id] }?.complete(answer)
    }
}
