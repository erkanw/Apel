package io.github.erkanw.vault.tools

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import io.github.erkanw.vault.MainActivity
import io.github.erkanw.vault.R
import io.github.erkanw.vault.store.AgentProfile
import org.json.JSONObject
import java.io.File
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The agent's memory (PLAN §7.6): one Markdown file, `workspace/memory/MEMORY.md`, read into every turn's system prompt
 * and kept up to date by the agent itself.
 */
object MemoryTools {
    /** About 4,000 tokens: what the system prompt carries. */
    const val PROMPT_CHARS = 16_000
    private const val MAX_CHARS = 64_000

    /** Apel's memory (the one it always had). */
    fun file(context: Context) = File(Workspace.root(context), "memory/MEMORY.md")

    /** [agent]'s memory: each agent keeps its own (PLAN §6.16). */
    fun file(context: Context, agent: io.github.erkanw.vault.agent.AgentScope) = File(Workspace.root(context), agent.memoryPath)

    /** An agent's memory by id, for the agent page. */
    fun file(context: Context, agentId: String) = File(
        Workspace.root(context),
        io.github.erkanw.vault.agent.AgentScope(agentId, "", "").memoryPath,
    )

    /** The memory as the system prompt carries it, or "" when there is none yet. */
    fun forPrompt(context: Context, agent: io.github.erkanw.vault.agent.AgentScope? = null): String {
        val f = if (agent == null) file(context) else file(context, agent)
        if (!f.isFile) return ""
        val text = f.readText().trim()
        return if (text.length <= PROMPT_CHARS) text else text.take(PROMPT_CHARS) + "\n…(memory continues; use memory_read for the rest)"
    }

    object Read : Tool {
        override val name = "memory_read"
        override val title = "Read memory"
        override val description = "Reads what the agent keeps in memory."
        override val instructions = "Read your whole memory file. Its start is already in your instructions."
        override val schema = params()
        override val defaultLevel = ToolLevel.Allowed
        override fun hint(args: JSONObject) = "Remembering"
        override fun step(args: JSONObject) = "memory_read"
        override suspend fun run(args: JSONObject, ctx: ToolContext): String =
            file(ctx.app, ctx.agent).takeIf { it.isFile }?.readText()?.ifBlank { null } ?: "(memory is empty)"
    }

    object Write : Tool {
        override val name = "memory_write"
        override val title = "Remember"
        override val description = "Keeps facts and preferences for later chats."
        override val instructions = "Keep something for future chats: facts about the user, their preferences, ongoing projects. " +
            "mode \"append\" adds a line under a section (made if missing); \"replace\" swaps a section's whole text. " +
            "Keep entries short; don't store secrets or passwords."
        override val schema = params(
            "mode" to enumOf("append or replace", "append", "replace"),
            "section" to str("A short heading, like \"About the user\" or \"Projects\"."),
            "text" to str("What to keep (append) or the section's new text (replace)."),
        )
        override val defaultLevel = ToolLevel.Allowed
        override fun hint(args: JSONObject) = "Remembering this"
        override fun step(args: JSONObject) = "memory_write ${args.optString("section")}: ${args.optString("text").take(120)}"
        // After reading the web, a page could be steering it: what it keeps for every later chat is asked first (PLAN §10).
        override fun mustAsk(args: JSONObject, ctx: ToolContext): ApprovalAsk? =
            if (ctx.readWeb) ApprovalAsk("Remember this, after reading the web", "${args.optString("section")}: ${args.optString("text")}") else null
        override suspend fun run(args: JSONObject, ctx: ToolContext): String {
            val section = args.need("section").replace("\n", " ").trim().trimStart('#', ' ')
            val text = args.optString("text").trim()
            val replace = args.optString("mode") == "replace"
            val f = file(ctx.app, ctx.agent)
            f.parentFile?.mkdirs()
            val updated = withSection(if (f.isFile) f.readText() else "# Memory\n", section, text, replace)
            if (updated.length > MAX_CHARS) throw ToolException("Memory is full (64,000 characters). Replace a section with a shorter summary first.")
            f.writeText(updated)
            return if (replace) "Section “$section” replaced." else "Kept under “$section”."
        }
    }

    /** [doc] with [text] appended to the `## section` (made at the end if missing), or that section's text replaced. */
    fun withSection(doc: String, section: String, text: String, replace: Boolean): String {
        val lines = doc.trimEnd().lines().toMutableList()
        val head = lines.indexOfFirst { it.trim().equals("## $section", ignoreCase = true) }
        val entry = if (replace) text else text.lines().joinToString("\n") { if (it.isBlank() || it.trimStart().startsWith("-")) it else "- $it" }
        if (head < 0) return (lines.joinToString("\n") + "\n\n## $section\n" + entry).trim() + "\n"
        var end = head + 1
        while (end < lines.size && !lines[end].startsWith("## ")) end++
        val body = lines.subList(head + 1, end).joinToString("\n").trim()
        val newBody = if (replace || body.isEmpty()) entry else body + "\n" + entry
        val out = lines.subList(0, head + 1) + newBody.lines() + (if (end < lines.size) listOf("") + lines.subList(end, lines.size) else emptyList())
        return out.joinToString("\n").trim() + "\n"
    }
}

object TimeTool : Tool {
    override val name = "now"
    override val title = "Date and time"
    override val description = "Knows the date, time and time zone."
    override val instructions = "The current date, time and time zone on the phone."
    override val schema = params()
    override val defaultLevel = ToolLevel.Allowed
    override fun hint(args: JSONObject) = "Checking the time"
    override fun step(args: JSONObject) = "now"
    override suspend fun run(args: JSONObject, ctx: ToolContext): String {
        val now = ZonedDateTime.now()
        return now.format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH:mm:ss", Locale.ENGLISH)) + " (" + now.zone.id + ", UTC" + now.offset.id.replace("Z", "+00:00") + ")"
    }
}

/** A notification now (PLAN §7.4), at most ten an hour, in the Replies channel. */
object NotifyTool : Tool {
    private const val CHANNEL = "agent_notes"
    private const val PER_HOUR = 10
    private val sent = ArrayDeque<Long>()

    override val name = "notify"
    override val title = "Notify"
    override val description = "Sends you a notification, at most ten an hour."
    override val instructions = "Send the user a phone notification now: a short title and one or two sentences. Use it only when they asked to be told."
    override val schema = params("title" to str("A few words."), "text" to str("One or two sentences."))
    override val defaultLevel = ToolLevel.Allowed
    override fun hint(args: JSONObject) = "Sending a notification"
    override fun step(args: JSONObject) = "notify \"${args.optString("title")}\""
    override fun similar(args: JSONObject, ctx: ToolContext) = Similar(name, "notifications")
    override suspend fun run(args: JSONObject, ctx: ToolContext): String {
        val app = ctx.app
        if (android.os.Build.VERSION.SDK_INT >= 33 && app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            throw ToolException("Notifications are off for the app; tell the user instead.")
        }
        val now = System.currentTimeMillis()
        synchronized(sent) {
            while (sent.isNotEmpty() && now - sent.first() > 3_600_000) sent.removeFirst()
            if (sent.size >= PER_HOUR) throw ToolException("Ten notifications this hour already; tell the user in the chat instead.")
            sent.addLast(now)
        }
        val manager = app.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, app.getString(R.string.channel_agent_notes), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = app.getString(R.string.channel_agent_notes_description, AgentProfile.name(app))
            },
        )
        val open = PendingIntent.getActivity(
            app, 0, Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(args.need("title").take(80))
            .setContentText(args.optString("text").take(300))
            .setStyle(NotificationCompat.BigTextStyle().bigText(args.optString("text").take(300)))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        manager.notify((now % 100_000).toInt() + 5000, n)
        return "Notification sent."
    }
}
