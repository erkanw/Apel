package io.github.erkanw.vault.chat

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class Role(val wire: String) { User("user"), Assistant("assistant") }

data class ChatMessage(
    val id: Long,
    val role: Role,
    val text: String,
    val isError: Boolean = false,
    /** When it was sent or arrived (milliseconds); 0 if unknown. */
    val createdAt: Long = System.currentTimeMillis(),
    /** Files that went with it (pictures and documents), by name. */
    val attachments: List<MessageAttachment> = emptyList(),
    /** For a reply: the tools the agent called and the notes it sent on the way (see AgentSteps.kt). */
    val steps: List<AgentStep> = emptyList(),
    /** For a reply: the agent that wrote it, named under it (PLAN §6.16). */
    val agentId: String? = null,
)

/** The conversation, kept in the app's private storage so it is there after a restart. */
class ChatStore(context: Context) {
    private val file = File(context.filesDir, "chat.json")

    fun load(): List<ChatMessage> = try {
        val array = JSONArray(file.readText())
        List(array.length()) { i ->
            val o = array.getJSONObject(i)
            val files = o.optJSONArray("files")?.let { a ->
                List(a.length()) { j ->
                    a.getJSONObject(j).let {
                        MessageAttachment(
                            name = it.getString("n"),
                            image = it.optBoolean("i"),
                            video = it.optBoolean("v"),
                            url = if (it.isNull("u")) null else it.optString("u").ifEmpty { null },
                            localPath = if (it.isNull("p")) null else it.optString("p").ifEmpty { null },
                        )
                    }
                }
            }.orEmpty()
            ChatMessage(
                o.getLong("id"), Role.valueOf(o.getString("role")), o.getString("text"), o.optBoolean("error"), o.optLong("t", 0L), files,
                steps = stepsFromJson(o.optJSONArray("steps")),
            )
        }
    } catch (e: Exception) {
        emptyList()
    }

    fun save(messages: List<ChatMessage>) {
        val array = JSONArray()
        for (m in messages.takeLast(MAX_KEPT)) {
            array.put(
                JSONObject()
                    .put("id", m.id)
                    .put("role", m.role.name)
                    .put("text", m.text)
                    .put("error", m.isError)
                    .put("t", m.createdAt)
                    .apply {
                        if (m.steps.isNotEmpty()) put("steps", stepsToJson(m.steps))
                        if (m.attachments.isNotEmpty()) {
                            put(
                                "files",
                                JSONArray().apply {
                                    m.attachments.forEach {
                                        put(
                                            JSONObject().put("n", it.name).put("i", it.image).put("v", it.video)
                                                .put("u", it.url ?: JSONObject.NULL).put("p", it.localPath ?: JSONObject.NULL),
                                        )
                                    }
                                },
                            )
                        }
                    },
            )
        }
        try {
            file.writeText(array.toString())
        } catch (e: Exception) {
            // Out of space or similar: the chat still works, it just won't survive a restart.
        }
    }

    fun clear() {
        file.delete()
    }

    private companion object {
        const val MAX_KEPT = 200
    }
}
