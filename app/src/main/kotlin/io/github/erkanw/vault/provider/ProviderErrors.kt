package io.github.erkanw.vault.provider

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** A provider error: the line to show, and whether trying the next model could help (PLAN §7.3). */
class ProviderException(message: String, val fallback: Boolean = false, val status: Int = 0, /** What the provider said, as it said it. */ val detail: String = "") : IOException(message)

/**
 * The words for a provider error (handbook §6): what happened and the one thing to do. 402 and 429 never fall back to
 * another model: they end the turn. [openRouter] picks the OpenRouter wording; [model] is the model asked.
 */
fun providerError(status: Int, detail: String, openRouter: Boolean, model: String): ProviderException {
    val free = model.endsWith(":free")
    val text = when (status) {
        400 -> if (detail.contains("context", ignoreCase = true) || detail.contains("too long", ignoreCase = true)) {
            "This chat is too long for the model. Start a new chat, or pick a model that holds more."
        } else {
            "The model refused the request: ${detail.ifBlank { "no reason given" }}"
        }
        401, 403 -> if (openRouter) "Your OpenRouter key no longer works. Sign in again in Settings, Provider."
        else "The server refused the key ($status). Check it in Settings, Provider."
        402 -> if (openRouter) "Your OpenRouter credits have run out. Add credits at openrouter.ai, then try again."
        else "The server says payment is needed (402). Check your account with that provider."
        404 -> "The model “$model” isn’t available any more. Pick another in the chat’s model list."
        408 -> "The model took too long to answer. Try again."
        429 -> when {
            openRouter && free -> "Free models allow 50 messages a day. Try again tomorrow or add \$10 of credits."
            else -> "Too many requests right now. Wait a minute and try again."
        }
        in 500..599 -> "The provider had a problem ($status). Try again in a moment."
        else -> "The provider answered $status${if (detail.isBlank()) "." else ": $detail"}"
    }
    val fallback = status == 404 || status == 408 || status in 500..599
    return ProviderException(text, fallback = fallback, status = status, detail = detail)
}

/** A network failure in a line, with what usually helps. Worth trying the next model only on a timeout. */
fun networkError(e: IOException, host: String): ProviderException = when (e) {
    is ProviderException -> e
    is UnknownHostException -> ProviderException("No connection to $host. Check the phone’s internet and try again.")
    is SocketTimeoutException -> ProviderException("$host didn’t answer in time. Try again.", fallback = true)
    is ConnectException -> ProviderException("Couldn’t connect to $host. Check the address, and that the server is running.")
    else -> ProviderException("The connection to $host broke off. Try again.")
}

/** The error message inside a provider's JSON error body, or the body's start. */
fun errorDetailOf(body: String): String {
    val trimmed = body.trim()
    if (trimmed.isEmpty()) return ""
    return try {
        val o = org.json.JSONObject(trimmed)
        val error = o.optJSONObject("error")
        (error?.optString("message") ?: o.optString("message")).orEmpty().ifBlank { trimmed.take(200) }
    } catch (e: Exception) {
        trimmed.take(200)
    }
}
