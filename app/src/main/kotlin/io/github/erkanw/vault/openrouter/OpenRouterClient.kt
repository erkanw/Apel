package io.github.erkanw.vault.openrouter

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Asks OpenRouter what the key spent. The key goes to `https://openrouter.ai` and nowhere else: the address is fixed
 * here, never typed, and a redirect is not followed. Blocking: call it off the main thread.
 */
object OpenRouterClient {
    const val BASE = "https://openrouter.ai/api/v1"

    sealed interface Result {
        class Ok(val snapshot: OrSnapshot) : Result

        /** [rejected]: OpenRouter doesn't accept the key itself (as against a network problem). */
        class Failed(val message: String, val rejected: Boolean = false) : Result
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private class Reply(val code: Int, val body: String)

    private fun get(path: String, key: String): Reply {
        val request = Request.Builder()
            .url("$BASE/$path")
            .header("Authorization", "Bearer $key")
            .header("Accept", "application/json")
            .build()
        http.newCall(request).execute().use { response -> return Reply(response.code, response.body?.string().orEmpty()) }
    }

    /** A part that only a Management key can read: its body, or null when it can't be had (the page copes without). */
    private fun optional(path: String, key: String): String? = try {
        get(path, key).takeIf { it.code in 200..299 }?.body
    } catch (e: IOException) {
        null
    }

    /**
     * Everything this [key] may read. A regular key gives its own spend; a Management key adds the balance, every key's
     * spend and (with [full]) the last 30 days per model, where a part that fails keeps what [previous] had. The widget
     * passes `full = false`: it only needs the numbers that change, and has a few seconds to get them.
     */
    fun fetch(key: String, previous: OrSnapshot?, full: Boolean, now: Long = System.currentTimeMillis()): Result {
        val me = try {
            get("key", key)
        } catch (e: IOException) {
            return Result.Failed("Couldn't reach OpenRouter (${e.message ?: "no connection"}).")
        }
        when {
            me.code == 401 || me.code == 403 ->
                return Result.Failed("OpenRouter doesn't accept this key. Check that it was copied whole.", rejected = true)
            me.code !in 200..299 -> return Result.Failed("OpenRouter answered HTTP ${me.code}.")
        }
        val info = parseKey(me.body) ?: return Result.Failed("OpenRouter answered, but not in a way this app understands.")
        // A regular key (the one sign-in makes) may or may not read the account's credits: asked, and kept if it can.
        if (!info.management) {
            val credits = optional("credits", key)?.let(::parseCredits)
            return Result.Ok(OrSnapshot(now, info, credits, emptyList(), emptyList()))
        }

        val credits = optional("credits", key)?.let(::parseCredits) ?: previous?.credits
        val keys = allKeys(key) ?: previous?.keys.orEmpty()
        // The per-model activity only changes when a day ends, so the widget doesn't ask for it every time: it is asked for
        // when the page wants everything, when there is none yet, or when what there is has gone stale.
        val needActivity = full || previous == null || previous.rows.isEmpty() || now - previous.activityAt > ACTIVITY_MAX_AGE_MS
        val fresh = if (needActivity) optional("activity", key)?.let(::parseActivity) else null
        val rows = fresh ?: previous?.rows.orEmpty()
        val activityAt = if (fresh != null) now else previous?.activityAt ?: 0L
        return Result.Ok(OrSnapshot(now, info, credits, keys, rows, activityAt))
    }

    /** How old the per-model activity may get before the widget asks for it again. */
    private const val ACTIVITY_MAX_AGE_MS = 6 * 60 * 60_000L

    /** The account's keys, page after page (the page size isn't documented), or null if the first page can't be had. */
    private fun allKeys(key: String): List<OrKeyUse>? {
        val all = ArrayList<OrKeyUse>()
        var offset = 0
        for (page in 0 until MAX_KEY_PAGES) {
            val body = optional("keys?include_disabled=true&offset=$offset", key) ?: return all.takeIf { page > 0 }
            val items = parseKeys(body).filter { fresh -> all.none { it.name == fresh.name && it.label == fresh.label } }
            if (items.isEmpty()) break
            all.addAll(items)
            offset += items.size
        }
        return all
    }

    private const val MAX_KEY_PAGES = 10
}
