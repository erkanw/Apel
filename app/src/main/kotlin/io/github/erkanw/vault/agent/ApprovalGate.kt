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
    /** [AllowSimilar]: allow, and don’t ask again for calls like it ([ApprovalMemory]). */
    enum class Answer { Allow, AllowAlways, AllowSimilar, Deny, Timeout }

    /**
     * [canAlways]: whether "Always allow this tool" is offered (not when something other than the level asked).
     * [unattended]: asked by notification (an automation, or the app wasn't on screen), so Allow needs the fingerprint.
     * [similar]: what "Don’t ask again" would cover ("pages on example.com"); null when it isn't offered.
     */
    data class Request(
        val id: String, val sessionId: String, val tool: String, val summary: String, val detail: String, val canAlways: Boolean = true,
        val unattended: Boolean = false, val similar: String? = null,
    )

    private const val TIMEOUT_MS = 30 * 60_000L

    private val waiting = HashMap<String, CompletableDeferred<Answer>>()
    private val _pending = MutableStateFlow<List<Request>>(emptyList())

    /** Every request waiting now, oldest first. */
    val pending: StateFlow<List<Request>> = _pending.asStateFlow()

    /** Suspends until the user answers (or the time runs out, which is a no): at most [timeoutMs], 30 minutes by default. */
    suspend fun ask(
        sessionId: String, tool: String, summary: String, detail: String, canAlways: Boolean = true, id: String = UUID.randomUUID().toString(),
        unattended: Boolean = false, timeoutMs: Long = TIMEOUT_MS, similar: String? = null,
    ): Answer {
        val request = Request(id, sessionId, tool, summary, detail, canAlways, unattended, similar)
        val answer = CompletableDeferred<Answer>()
        synchronized(waiting) { waiting[request.id] = answer }
        _pending.update { it + request }
        try {
            return withTimeoutOrNull(timeoutMs.coerceAtMost(TIMEOUT_MS)) { answer.await() } ?: Answer.Timeout
        } finally {
            synchronized(waiting) { waiting.remove(request.id) }
            _pending.update { list -> list.filterNot { it.id == request.id } }
        }
    }

    /**
     * Whether answering [request] with [allow] must first pass the fingerprint check (PLAN §10): Allow or Always allow
     * for a request that came by notification, when nobody was necessarily watching. Deny never does.
     */
    fun needsBiometrics(request: Request, allow: Answer): Boolean =
        request.unattended && (allow == Answer.Allow || allow == Answer.AllowAlways || allow == Answer.AllowSimilar)

    fun answer(id: String, answer: Answer) {
        synchronized(waiting) { waiting[id] }?.complete(answer)
    }
}
