package io.github.erkanw.vault.chat

import java.util.Locale

/**
 * What a session is made of, for its information page: how many prompts and responses, the files that went with
 * them, when it began, and a rough idea of how much of the model's memory it takes up.
 */
data class SessionStats(
    val prompts: Int,
    val responses: Int,
    val attachments: List<MessageAttachment>,
    /** First and last message times (milliseconds), 0 if unknown. */
    val firstMs: Long,
    val lastMs: Long,
    /** A guess at the tokens in the messages: about a quarter of the characters. */
    val estimatedTokens: Int,
    /** False when only part of a very long session could be read. */
    val complete: Boolean = true,
    /** True when the context was compressed at some point: then the estimate counts only what came after. */
    val compacted: Boolean = false,
) {
    /** Every prompt and every response. */
    val messages: Int get() = prompts + responses
}

/** The numbers for [messages]: yours are prompts, the agent’s replies are responses (failed and empty ones are neither). */
fun statsOf(messages: List<ChatMessage>, complete: Boolean = true, compactedAt: Int = 0, summaryChars: Int = 0): SessionStats {
    var prompts = 0
    var responses = 0
    var chars = 0L
    val files = ArrayList<MessageAttachment>()
    val seen = HashSet<String>()
    var first = 0L
    var last = 0L
    for ((index, m) in messages.withIndex()) {
        if (m.isError) continue
        val real = m.text.isNotEmpty() || m.attachments.isNotEmpty()
        if (!real) continue
        if (m.role == Role.User) prompts++ else responses++
        // Only what came after the last compression is still in the model's memory (the summary stands in for the rest).
        if (index >= compactedAt) chars += m.text.length
        for (a in m.attachments) if (seen.add(a.url ?: (a.localPath ?: a.name + "#" + files.size))) files.add(a)
        if (m.createdAt > 0L) {
            if (first == 0L || m.createdAt < first) first = m.createdAt
            if (m.createdAt > last) last = m.createdAt
        }
    }
    return SessionStats(prompts, responses, files, first, last, estimateTokens(chars + summaryChars), complete, compacted = compactedAt > 0 || summaryChars > 0)
}

/** About four characters to a token. */
fun estimateTokens(characters: Long): Int = ((characters + 3L) / 4L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

/** 950 as "950", 12,400 as "12.4k", 128,000 as "128k", 1,500,000 as "1.5M". */
fun formatTokens(n: Long): String = when {
    n < 1_000L -> n.toString()
    n < 100_000L -> trimmed(n / 1_000.0) + "k"
    n < 1_000_000L -> (n / 1_000L).toString() + "k"
    else -> trimmed(n / 1_000_000.0) + "M"
}

private fun trimmed(value: Double): String = String.format(Locale.US, "%.1f", value).removeSuffix(".0")
