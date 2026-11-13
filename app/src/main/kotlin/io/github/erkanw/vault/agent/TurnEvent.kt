package io.github.erkanw.vault.agent

import io.github.erkanw.vault.chat.AgentStep

/**
 * What happens during one turn of the agent, in order, as [AgentBackend.run] reports it (PLAN §5). The chat turns these
 * into the streaming reply, the working line and the steps panel.
 */
sealed interface TurnEvent {
    /** The message was taken into [chatId] (a new session gets its id here). */
    data class MessageAccepted(val chatId: String) : TurnEvent

    /** More of the answer's text. */
    data class Delta(val text: String) : TurnEvent

    /** More of the model's reasoning, when it streams it. */
    data class ReasoningDelta(val text: String) : TurnEvent

    /** What the agent is doing right now, for the working line ("Reading example.com"). */
    data class ToolHint(val text: String) : TurnEvent

    /** A step kept with the answer, for the steps panel (a tool call and its short result, a note). */
    data class Progress(val step: AgentStep) : TurnEvent

    /** The answer's whole text so far, replacing what streamed (after a retry on a fallback model, say). */
    data class Message(val text: String) : TurnEvent

    /** The turn is over; [text] is the final answer. */
    data class TurnEnd(val text: String) : TurnEvent

    /** The turn failed; [message] says why and what to do, in a line. */
    data class Error(val message: String) : TurnEvent
}
