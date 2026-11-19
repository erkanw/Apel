package io.github.erkanw.vault.chat

import io.github.erkanw.vault.openrouter.presetNameFor

/** Model ids that don't chat: embeddings, rerankers, speech in or out, and safety classifiers. */
private val NOT_CHAT_IDS = listOf("embed", "bge-", "all-minilm", "clip", "rerank", "whisper", "tts", "orpheus", "guard", "moderation")

/** Whether a server's model id is one you can chat with. */
fun isChatModel(id: String): Boolean = id.isNotBlank() && NOT_CHAT_IDS.none { id.lowercase().contains(it) }

/**
 * The presets for your own server (an OpenAI-compatible provider such as Groq): one for every chat model it lists in
 * [ids], kept in step with it. Presets of models the server no longer lists go; new ones are added with a readable
 * label. The main model and its fallbacks stay as you set them (the ones still offered); with none left, the first
 * model becomes the main one. New models are offered in the chat but don't become fallbacks by themselves.
 */
fun syncServerPresets(presets: List<ModelPreset>, order: List<String>, ids: List<String>): Pair<List<ModelPreset>, List<String>> {
    val offered = ids.filter(::isChatModel).distinct()
    val kept = presets.filter { it.model in offered }
    val have = kept.map { it.model }.toSet()
    val taken = kept.map { it.name }.toMutableSet()
    val added = offered.filter { it !in have }.map { id ->
        val name = presetNameFor(id, taken).also { taken += it }
        ModelPreset(name, prettyModelName(id).ifBlank { id }, id, false)
    }
    val all = kept + added
    val keptOrder = order.filter { n -> all.any { it.name == n } }
    return all to keptOrder.ifEmpty { listOfNotNull(all.firstOrNull()?.name) }
}
