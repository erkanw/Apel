package io.github.erkanw.vault.ui

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import io.github.erkanw.vault.chat.ModelPreset
import io.github.erkanw.vault.chat.ScheduleResult
import io.github.erkanw.vault.chat.automationEdit
import io.github.erkanw.vault.chat.describeSchedule
import io.github.erkanw.vault.chat.scheduleDraftOf
import io.github.erkanw.vault.chat.toSchedule
import io.github.erkanw.vault.mascot.BlobFlat

/**
 * Makes or edits a scheduled job (PLAN §6.8): its name, what the agent should do, when, where it posts (a channel, a new
 * channel, or a new chat each time) and the model it runs with. Saving keeps it at once. The schedule is only checked and
 * sent once one of its fields changed, so a rename never rewrites it. A channel made here is only kept when the job is
 * saved into it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AutomationEditSheet(
    job: AutomationJob,
    /** The channel the job posts in now, or null for a new chat each time (or a new job). */
    current: Channel?,
    channels: List<Pair<Channel, ChannelLook>>,
    models: List<ModelPreset>,
    /** The values to keep: `name`, `message`, `schedule` (when it changed), `channel` and `model`. */
    onSave: (values: org.json.JSONObject, schedule: org.json.JSONObject?) -> Unit,
    /** Makes a channel; only called when the job is saved into one made here, so a cancelled sheet leaves none behind. */
    onNewChannel: (ChannelLook) -> Channel,
    onDismiss: () -> Unit,
    /** Why the last save failed, shown above the button. */
    error: String? = null,
) {
    val isNew = job.id.isEmpty()
    val k = MaterialTheme.vault
    val agent = LocalAgentName.current
    var name by remember { mutableStateOf(job.name) }
    var message by remember { mutableStateOf(job.message) }
    var draft by remember { mutableStateOf(if (isNew) io.github.erkanw.vault.chat.newScheduleDraft() else scheduleDraftOf(job)) }
    // Any change to the schedule's fields marks it touched; until then the job keeps the schedule it has.
    fun edit(d: io.github.erkanw.vault.chat.ScheduleDraft) { draft = d.copy(touched = true) }
    var target by remember { mutableStateOf(current) }
    var model by remember { mutableStateOf(job.model.ifEmpty { null }) }
    var runsAs by remember { mutableStateOf(job.agent.ifEmpty { null }) }
    val agentsVm = androidx.lifecycle.viewmodel.compose.viewModel<io.github.erkanw.vault.AgentsViewModel>()
    val agentList by agentsVm.agents.collectAsStateWithLifecycle()
    var made by remember { mutableStateOf<List<Pair<Channel, ChannelLook>>>(emptyList()) }
    var creating by remember { mutableStateOf(false) }
    var tried by remember { mutableStateOf(false) }

    val edit = automationEdit(job, name, message, draft)
    val preview = if (!draft.touched) job.schedule else (draft.toSchedule() as? ScheduleResult.Ok)?.let { describeSchedule(it.json) }
    val shownChannels = channels + made.filter { m -> channels.none { it.first.id == m.first.id } }
    // Presets hidden from the chat’s model list stay out here too, unless this job uses one.
    val shownModels = models.filterNot { it.hidden && it.name != model }

    VaultSheet(onDismiss = onDismiss, title = if (isNew) "New automation" else "Edit automation") {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            VaultField(name, { name = it.take(80) }, "Name", isError = tried && name.isBlank())
            VaultField(
                message, { message = it }, "What $agent should do", singleLine = false, minLines = 3,
                supporting = "$agent reads this each time the job runs, without the chat around it.", isError = tried && message.isBlank(),
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
                    VaultChip("A new chat each time", { target = null }, selected = target == null)
                    shownChannels.forEach { (channel, look) ->
                        VaultChip(
                            look.title, { target = channel }, selected = target?.id == channel.id,
                            leading = { BlobFlat(ChannelColors.at(look.color), size = 16.dp) },
                        )
                    }
                    VaultChip("New channel", { creating = true }, leading = { Icon(VaultIcons.Plus, null, tint = k.text, modifier = Modifier.size(16.dp)) })
                }
                Text(
                    if (target == null) "Each run starts a chat named after the job." else "Each run posts in the channel, after what is already there.",
                    style = VaultType.Caption, color = k.muted,
                )
            }

            if (agentList.size > 1) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(androidx.compose.ui.res.stringResource(io.github.erkanw.vault.R.string.agent_runs_as), style = VaultType.Label, color = k.muted)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        agentList.forEach { a ->
                            VaultChip(a.name, { runsAs = a.id.takeIf { !a.isApel } }, selected = (runsAs ?: io.github.erkanw.vault.agent.AgentPresets.APEL) == a.id, leading = { AgentMark(a.look, size = 18.dp) })
                        }
                    }
                }
            }

            if (shownModels.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Model", style = VaultType.Label, color = k.muted)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        VaultChip("Main model", { model = null }, selected = model == null)
                        shownModels.forEach { p -> VaultChip(p.label.ifBlank { p.name }, { model = p.name }, selected = model == p.name) }
                    }
                }
            }

            when {
                tried && edit is EditResult.Invalid -> Text(edit.reason, style = VaultType.Caption, color = k.attention)
                error != null -> Text(error, style = VaultType.Caption, color = k.attention)
            }
            PrimaryButton(
                if (isNew) "Make automation" else "Save",
                onClick = {
                    tried = true
                    val ok = edit as? EditResult.Ok ?: return@PrimaryButton
                    val to = target
                    // A channel made in this sheet is only a draft until now.
                    val real = if (to != null && to.id.startsWith(DRAFT)) {
                        onNewChannel(made.first { it.first.id == to.id }.second).also { kept ->
                            // Kept now: a second try after a failed save uses it instead of making another.
                            made = made.map { if (it.first.id == to.id) kept to it.second else it }
                            target = kept
                        }
                    } else to
                    val values = ok.values.put("channel", real?.id.orEmpty()).put("model", model.orEmpty()).put("agent", runsAs.orEmpty())
                    if (isNew) values.put("schedule", ok.schedule ?: return@PrimaryButton)
                    onSave(values, ok.schedule)
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
