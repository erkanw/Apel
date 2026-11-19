package io.github.erkanw.vault.tools

import android.content.Context
import io.github.erkanw.vault.automation.AutomationRunner
import io.github.erkanw.vault.automation.Automations
import io.github.erkanw.vault.automation.Schedule
import io.github.erkanw.vault.automation.schedule
import io.github.erkanw.vault.chat.Channel
import io.github.erkanw.vault.chat.ChannelLooks
import io.github.erkanw.vault.chat.ChatException
import io.github.erkanw.vault.chat.cleanLook
import io.github.erkanw.vault.chat.describeSchedule
import io.github.erkanw.vault.chat.nextChannelColor
import io.github.erkanw.vault.store.AutomationEntity
import io.github.erkanw.vault.store.ModelStore
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The agent's own schedule (PLAN §7.4): automation_list, automation_create, automation_update, automation_delete. A job
 * runs in a channel (by name; one that doesn't exist yet is made) or in a new chat each time; made from inside a
 * channel, it posts there unless told otherwise. Creating, changing and deleting ask first (§7.5).
 */
object AutomationTools {
    private val WHEN = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Locale.ENGLISH)

    private val scheduleProps = arrayOf(
        "cron" to str("A 5-field cron expression in the user's time zone, e.g. \"0 8 * * 1-5\" for weekdays at 08:00. Use one of cron, every_minutes or at."),
        "every_minutes" to JSONObject().put("type", "integer").put("description", "Run every N minutes (at least 15). 1440 is daily."),
        "at" to str("Run once, at this local date and time: \"YYYY-MM-DD HH:MM\"."),
        "timezone" to str("Optional IANA time zone for cron, e.g. \"Europe/Stockholm\". Default: the phone's."),
        "runs_in" to str("Where results are posted: the name of a channel (made if it doesn't exist), or \"new chat\" for a new chat each time. Default: this channel if this chat is one, else a new chat each time."),
    )

    object ListTool : Tool {
        override val name = "automation_list"
        override val title = "See automations"
        override val description = "Lists its scheduled jobs."
        override val instructions = "List the scheduled automations: id, name, on or off, schedule, where they post, next and last run."
        override val schema = params()
        override val defaultLevel = ToolLevel.Allowed
        override fun hint(args: JSONObject) = "Checking the schedule"
        override fun step(args: JSONObject) = "automation_list"
        override suspend fun run(args: JSONObject, ctx: ToolContext): String {
            val all = Automations.all(ctx.app)
            if (all.isEmpty()) return "No automations yet."
            return all.joinToString("\n\n") { describe(ctx.app, it) }
        }
    }

    object CreateTool : Tool {
        override val name = "automation_create"
        override val title = "Make automations"
        override val description = "Schedules a new job. Asks you first."
        override val instructions = "Schedule a job: at the set times the agent gets `instruction` as a message and its answer is posted " +
            "(and notified). Write the instruction so it stands alone (it is read without this chat). Give exactly one of cron, every_minutes or at."
        override val schema = params(
            "name" to str("A short name, e.g. \"Morning brief\"."),
            "instruction" to str("What to do each time, as a message to yourself."),
            *scheduleProps,
            required = listOf("name", "instruction"),
        )
        override val defaultLevel = ToolLevel.Ask
        override fun hint(args: JSONObject) = "Scheduling “${args.optString("name")}”"
        override fun step(args: JSONObject) = "automation_create \"${args.optString("name")}\""
        override fun ask(args: JSONObject): ApprovalAsk = ApprovalAsk(
            "Schedule “${args.optString("name").take(60)}”",
            buildString {
                append(runCatching { describeSchedule(scheduleOf(args)!!.toJson()) }.getOrDefault("(no valid schedule)"))
                append("\nPosts in: ").append(args.optString("runs_in").ifBlank { "this chat’s channel, or a new chat each time" })
                append("\n\n").append(args.optString("instruction").take(1_500))
            },
        )

        override suspend fun run(args: JSONObject, ctx: ToolContext): String {
            val name = args.need("name")
            val instruction = args.need("instruction")
            val schedule = scheduleOf(args) ?: throw ToolException("Give one of cron, every_minutes or at.")
            val channel = target(ctx, args.optString("runs_in"))
            val values = JSONObject().put("name", name).put("message", instruction).put("schedule", schedule.toJson()).put("channel", channel?.id.orEmpty())
                // A job an agent makes runs as that agent (PLAN §6.16).
                .put("agent", ctx.agent.id)
            val id = try { Automations.create(ctx.app, values) } catch (e: ChatException) { throw ToolException(e.message ?: "That didn’t work.") }
            val a = Automations.get(ctx.app, id) ?: throw ToolException("It was made but can’t be read back.")
            return "Made it.\n" + describe(ctx.app, a)
        }
    }

    object UpdateTool : Tool {
        override val name = "automation_update"
        override val title = "Change automations"
        override val description = "Changes a job’s instruction, schedule or channel, or turns it on or off. Asks you first."
        override val instructions = "Change a scheduled job (by id or name): only the fields given change. `enabled` turns it on or off; `run_now` runs it once now."
        override val schema = params(
            "id" to str("The automation's id or exact name."),
            "name" to str("A new name."),
            "instruction" to str("A new instruction."),
            *scheduleProps,
            "enabled" to JSONObject().put("type", "boolean").put("description", "Turn it on (true) or off (false)."),
            "run_now" to JSONObject().put("type", "boolean").put("description", "Run it once now."),
            required = listOf("id"),
        )
        override val defaultLevel = ToolLevel.Ask
        override fun hint(args: JSONObject) = "Changing an automation"
        override fun step(args: JSONObject) = "automation_update ${args.optString("id")}"
        override fun ask(args: JSONObject): ApprovalAsk = ApprovalAsk(
            "Change the automation “${args.optString("id").take(60)}”",
            args.keys().asSequence().filter { it != "id" }.joinToString("\n") { "$it: ${args.opt(it).toString().take(600)}" }.ifBlank { "(nothing to change)" },
        )

        override suspend fun run(args: JSONObject, ctx: ToolContext): String {
            val a = find(ctx.app, args.need("id"))
            val values = JSONObject()
            args.optString("name").takeIf { it.isNotBlank() }?.let { values.put("name", it) }
            args.optString("instruction").takeIf { it.isNotBlank() }?.let { values.put("message", it) }
            if (listOf("cron", "every_minutes", "at").any { args.has(it) && !args.isNull(it) }) {
                values.put("schedule", (scheduleOf(args) ?: throw ToolException("Give one of cron, every_minutes or at.")).toJson())
            }
            if (args.has("runs_in") && args.optString("runs_in").isNotBlank()) values.put("channel", target(ctx, args.optString("runs_in"))?.id.orEmpty())
            if (args.has("enabled") && !args.isNull("enabled")) values.put("enabled", args.optBoolean("enabled"))
            try { Automations.update(ctx.app, a.id, values) } catch (e: ChatException) { throw ToolException(e.message ?: "That didn’t work.") }
            if (args.optBoolean("run_now")) {
                if (a.id == ctx.automation) throw ToolException("A job can’t run itself again while it runs.")
                AutomationRunner.requestRun(ctx.app, a.id)
            }
            val now = Automations.get(ctx.app, a.id) ?: throw ToolException("It is gone.")
            return "Changed.\n" + describe(ctx.app, now) + if (args.optBoolean("run_now")) "\nRunning it now; its answer is posted when it is done." else ""
        }
    }

    object DeleteTool : Tool {
        override val name = "automation_delete"
        override val title = "Delete automations"
        override val description = "Deletes a scheduled job. Asks you first."
        override val instructions = "Delete a scheduled job, by id or exact name. To pause it instead, use automation_update with enabled false."
        override val schema = params("id" to str("The automation's id or exact name."))
        override val defaultLevel = ToolLevel.Ask
        override fun hint(args: JSONObject) = "Deleting an automation"
        override fun step(args: JSONObject) = "automation_delete ${args.optString("id")}"
        override fun ask(args: JSONObject) = ApprovalAsk("Delete the automation “${args.optString("id").take(60)}”", args.optString("id"))
        override suspend fun run(args: JSONObject, ctx: ToolContext): String {
            val a = find(ctx.app, args.need("id"))
            try { Automations.delete(ctx.app, a.id) } catch (e: ChatException) { throw ToolException(e.message ?: "That didn’t work.") }
            return "Deleted “${a.name}”."
        }
    }

    /** The schedule the arguments give, or null when they give none; throws when what they give is wrong. */
    fun scheduleOf(args: JSONObject, zone: ZoneId = ZoneId.systemDefault(), now: Long = System.currentTimeMillis()): Schedule? {
        val given = listOf("cron", "every_minutes", "at").filter { args.has(it) && !args.isNull(it) && args.optString(it).isNotBlank() }
        if (given.isEmpty()) return null
        if (given.size > 1) throw ToolException("Give only one of cron, every_minutes or at.")
        val s = when (given.single()) {
            "cron" -> Schedule(Schedule.CRON, expr = args.optString("cron").trim(), tz = args.optString("timezone").trim())
            "every_minutes" -> Schedule(Schedule.EVERY, everyMs = args.optLong("every_minutes") * 60_000L)
            else -> {
                val text = args.optString("at").trim().replace('T', ' ')
                val local = runCatching { LocalDateTime.parse(text.take(16), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) }.getOrNull()
                    ?: throw ToolException("Write `at` as \"YYYY-MM-DD HH:MM\".")
                Schedule(Schedule.AT, atMs = local.atZone(zone).toInstant().toEpochMilli())
            }
        }
        s.problem(now)?.let { throw ToolException(it) }
        return s
    }

    /** Where a job posts: a channel by name (made when missing), none for a new chat each time, or this chat's channel by default. */
    private fun target(ctx: ToolContext, runsIn: String): Channel? {
        ChannelLooks.ensureLoaded(ctx.app)
        val text = runsIn.trim()
        val channels = ChannelLooks.custom.value
        if (text.isEmpty()) return ctx.channelId?.let { id -> channels.firstOrNull { it.id == id } }
        if (text.lowercase().replace(Regex("[^a-z]"), "") in setOf("newchat", "anewchat", "newchateachtime", "anewchateachtime", "none")) return null
        if (text.lowercase() in setOf("this channel", "here", "this chat")) {
            return ctx.channelId?.let { id -> channels.firstOrNull { it.id == id } } ?: throw ToolException("This chat isn’t a channel. Name one, or use \"new chat\".")
        }
        channels.firstOrNull { ChannelLooks.look(it).title.equals(text, ignoreCase = true) }?.let { return it }
        val draft = Channel("new", text, "", Channel.CUSTOM_INTRO, 0, custom = true)
        val look = cleanLook(draft, text, "", nextChannelColor(channels.map { ChannelLooks.look(it) }))
        return ChannelLooks.create(ctx.app, look)
    }

    private suspend fun find(context: Context, idOrName: String): AutomationEntity {
        val all = Automations.all(context)
        return all.firstOrNull { it.id == idOrName.trim() } ?: all.firstOrNull { it.name.equals(idOrName.trim(), ignoreCase = true) }
            ?: throw ToolException("No automation “$idOrName”. Call automation_list to see them.")
    }

    private fun describe(context: Context, a: AutomationEntity): String {
        ChannelLooks.ensureLoaded(context)
        val where = a.channelId?.let { id -> ChannelLooks.custom.value.firstOrNull { it.id == id } }?.let { "channel “${ChannelLooks.look(it).title}”" } ?: "a new chat each time"
        val zone = ZoneId.systemDefault()
        fun at(ms: Long) = java.time.Instant.ofEpochMilli(ms).atZone(zone).format(WHEN)
        return buildString {
            append("id ").append(a.id).append(": “").append(a.name).append("”")
            if (a.builtin != null) append(" (built in)")
            append(if (a.enabled) ", on" else ", off").append("\n")
            append(describeSchedule(a.schedule().toJson())).append("; posts in ").append(where)
            a.model?.let { m -> append("; model ").append(ModelStore(context).preset(m)?.label ?: m) }
            if (a.enabled && a.nextRun > 0L) append("\nNext run: ").append(at(a.nextRun))
            if (a.lastRun > 0L) append("\nLast run: ").append(at(a.lastRun)).append(" (").append(a.lastStatus.ifBlank { "done" }).append(")")
            if (a.builtin == null) append("\nInstruction: ").append(a.message.take(400))
        }
    }
}
