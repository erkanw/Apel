package io.github.erkanw.vault.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.erkanw.vault.chat.AutomationJob
import io.github.erkanw.vault.chat.Channel
import io.github.erkanw.vault.chat.ChannelLook
import io.github.erkanw.vault.chat.EditResult
import io.github.erkanw.vault.chat.EveryUnit
import io.github.erkanw.vault.chat.ScheduleResult
import io.github.erkanw.vault.chat.automationEdit
import io.github.erkanw.vault.chat.describeSchedule
import io.github.erkanw.vault.chat.scheduleDraftOf
import io.github.erkanw.vault.chat.toSchedule
import io.github.erkanw.vault.mascot.BlobFlat

/**
 * Edits a scheduled job: its name, what the agent should do, when, and the channel it runs in. Name, instruction and schedule
 * are saved on the server at once (`automation.update`). The server can't move a job to another chat, so picking another
 * channel opens that channel with a request for the agent to make the job there and remove this one, to read and send.
 *
 * With [NEW_JOB] it makes a job instead: the server has no call for that either, so it opens the chosen channel (or a
 * new chat) with a request for the agent to add the job there.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AutomationEditSheet(
    job: AutomationJob,
    /** The channel the job runs in now, or null when it runs in an ordinary chat (or somewhere else). */
    current: Channel?,
    channels: List<Pair<Channel, ChannelLook>>,
    onSave: (values: org.json.JSONObject, schedule: org.json.JSONObject?) -> Unit,
    onMove: (channel: Channel, name: String, message: String, schedule: org.json.JSONObject?) -> Unit,
    /** Makes a channel; only called when the job is saved into one made here, so a cancelled sheet leaves none behind. */
    onNewChannel: (ChannelLook) -> Channel,
    onDismiss: () -> Unit,
    /** New-job mode: the chat it goes into (null: a new chat), and the job as filled in. */
    onCreate: (channel: Channel?, name: String, message: String, schedule: org.json.JSONObject) -> Unit = { _, _, _, _ -> },
) {
    val isNew = job.id.isEmpty()
    val k = MaterialTheme.vault
    var name by remember { mutableStateOf(job.name) }
    var message by remember { mutableStateOf(job.message) }
    var draft by remember { mutableStateOf(if (isNew) io.github.erkanw.vault.chat.newScheduleDraft() else scheduleDraftOf(job)) }
    // Any change to the schedule's fields marks it touched; until then the job keeps the schedule it has.
    fun edit(d: io.github.erkanw.vault.chat.ScheduleDraft) { draft = d.copy(touched = true) }
    var target by remember { mutableStateOf(current) }
    var made by remember { mutableStateOf<List<Pair<Channel, ChannelLook>>>(emptyList()) }
    var creating by remember { mutableStateOf(false) }
    var tried by remember { mutableStateOf(false) }

    val edit = automationEdit(job, name, message, draft)
    val preview = if (!draft.touched) job.schedule else (draft.toSchedule() as? ScheduleResult.Ok)?.let { describeSchedule(it.json) }
    val moving = !isNew && target != null && target?.id != current?.id
    val targetTitle = shownTitle(target, channels, made)
    val shownChannels = channels + made.filter { m -> channels.none { it.first.id == m.first.id } }

    VaultSheet(onDismiss = onDismiss, title = if (isNew) "New automation" else "Edit automation") {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            VaultField(name, { name = it.take(80) }, "Name", isError = tried && name.isBlank())
            VaultField(
                message, { message = it }, "What ${LocalAgentName.current} should do", singleLine = false, minLines = 3,
                supporting = "${LocalAgentName.current} reads this each time the job runs.", isError = tried && message.isBlank(),
            )

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("When", style = VaultType.Label, color = k.muted)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    VaultChip("Every", { edit(draft.copy(kind = "every")) }, selected = draft.kind == "every")
                    VaultChip("Cron", { edit(draft.copy(kind = "cron")) }, selected = draft.kind == "cron")
                    VaultChip("Once", { edit(draft.copy(kind = "at")) }, selected = draft.kind == "at")
                }
                when (draft.kind) {
                    "every" -> Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        VaultField(
                            draft.everyValue, { v -> edit(draft.copy(everyValue = v.filter { it.isDigit() }.take(5))) }, "Every",
                            keyboardType = KeyboardType.Number, modifier = Modifier.width(96.dp),
                        )
                        FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            EveryUnit.entries.forEach { u ->
                                VaultChip(u.label, { edit(draft.copy(everyUnit = u)) }, selected = draft.everyUnit == u)
                            }
                        }
                    }
                    "cron" -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        VaultField(draft.cronExpr, { edit(draft.copy(cronExpr = it)) }, "Cron expression", placeholder = "0 9 * * *")
                        VaultField(draft.tz, { edit(draft.copy(tz = it)) }, "Time zone (optional)", placeholder = "Europe/Stockholm")
                    }
                    else -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        VaultField(draft.date, { edit(draft.copy(date = it.take(10))) }, "Date", placeholder = "2026-11-14", modifier = Modifier.weight(1.3f))
                        VaultField(draft.time, { edit(draft.copy(time = it.take(5))) }, "Time", placeholder = "08:30", keyboardType = KeyboardType.Number, modifier = Modifier.weight(1f))
                    }
                }
                val reason = if (!draft.touched) null else (draft.toSchedule() as? ScheduleResult.Invalid)?.reason
                Text(
                    reason ?: preview.orEmpty(),
                    style = VaultType.Caption,
                    color = if (reason != null) k.attention else k.muted,
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Runs in", style = VaultType.Label, color = k.muted)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (current == null) {
                        VaultChip(if (isNew) "A new chat" else "Its own chat", { target = null }, selected = target == null)
                    }
                    shownChannels.forEach { (channel, look) ->
                        VaultChip(
                            look.title, { target = channel }, selected = target?.id == channel.id,
                            leading = { BlobFlat(ChannelColors.at(look.color), size = 16.dp) },
                        )
                    }
                    VaultChip("New channel", { creating = true }, leading = { Icon(VaultIcons.Plus, null, tint = k.text, modifier = Modifier.size(16.dp)) })
                }
                if (isNew) {
                    Text(
                        "${LocalAgentName.current} adds new jobs itself, from the chat they report into. This opens " +
                            (targetTitle?.let { "“$it”" } ?: "a new chat") + " with a request for it. You read it and send it.",
                        style = VaultType.Caption, color = k.muted,
                    )
                }
                AnimatedVisibility(moving) {
                    Text(
                        "${LocalAgentName.current} can’t move a job between chats by itself. Saving opens " +
                            "“${shownChannels.firstOrNull { it.first.id == target?.id }?.second?.title.orEmpty()}” with a request for ${LocalAgentName.current} to " +
                            "add this job there and remove this one. You read it and send it.",
                        style = VaultType.Caption, color = k.muted,
                    )
                }
            }

            if (tried && edit is EditResult.Invalid) Text(edit.reason, style = VaultType.Caption, color = k.attention)
            PrimaryButton(
                when {
                    isNew -> "Open " + (targetTitle?.let { "“$it”" } ?: "a new chat")
                    moving -> "Save and open the channel"
                    else -> "Save"
                },
                onClick = {
                    tried = true
                    val ok = edit as? EditResult.Ok ?: return@PrimaryButton
                    val to = target
                    if (isNew) {
                        val real = when {
                            to == null -> null
                            to.id.startsWith(DRAFT) -> onNewChannel(made.first { it.first.id == to.id }.second)
                            else -> to
                        }
                        onCreate(real, name.trim(), message.trim(), ok.schedule ?: return@PrimaryButton)
                    } else if (moving && to != null) {
                        // A channel made in this sheet is only a draft until now.
                        val real = if (to.id.startsWith(DRAFT)) onNewChannel(made.first { it.first.id == to.id }.second) else to
                        onMove(real, name.trim(), message.trim(), ok.schedule)
                    } else {
                        onSave(ok.values, ok.schedule)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(4.dp))
        }
    }
    if (creating) {
        NewChannelDialog(
            taken = shownChannels.map { it.second },
            onCreate = { look ->
                val draftChannel = Channel(DRAFT + made.size, look.title, look.blurb, Channel.CUSTOM_INTRO, look.color, custom = true)
                made = made + (draftChannel to look)
                target = draftChannel
                creating = false
            },
            onDismiss = { creating = false },
        )
    }
}

/** The id prefix of a channel made in the sheet and not yet kept. */
private const val DRAFT = "draft-"

/** The name of the channel [target] (one of [channels] or a draft in [made]), or null for a chat of its own. */
private fun shownTitle(target: Channel?, channels: List<Pair<Channel, ChannelLook>>, made: List<Pair<Channel, ChannelLook>>): String? =
    target?.let { t -> (channels + made).firstOrNull { it.first.id == t.id }?.second?.title }
