package io.github.erkanw.vault.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.erkanw.vault.mascot.Blob
import io.github.erkanw.vault.mascot.BlobColor
import io.github.erkanw.vault.mascot.BlobEyes
import io.github.erkanw.vault.mascot.BlobFinish
import io.github.erkanw.vault.mascot.BlobLook
import io.github.erkanw.vault.mascot.BlobMood
import io.github.erkanw.vault.mascot.BlobShape
import io.github.erkanw.vault.mascot.BlobTaste
import io.github.erkanw.vault.mascot.LocalBlobLook
import io.github.erkanw.vault.store.LookStore
import io.github.erkanw.vault.widget.WidgetRedraw

/**
 * The in-app studio (handbook §4.6): the live mascot, a preview of each mood, Randomize, and the look's five options.
 * Every change is saved at once and shows everywhere (Home, chat, channels, the widget).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LookScreen(
    onBack: () -> Unit,
    /** Another agent's look (PLAN §6.16): shown and changed through these; null is Apel's (the app's own). */
    agentLook: BlobLook? = null,
    agentName: String? = null,
    onChoose: ((BlobLook) -> Unit)? = null,
) {
    val k = MaterialTheme.vault
    val context = LocalContext.current
    val view = androidx.compose.ui.platform.LocalView.current
    val look = agentLook ?: LocalBlobLook.current
    val agent = agentName ?: LocalAgentName.current
    var moodName by rememberSaveable { mutableStateOf(BlobMood.Idle.name) }
    val mood = BlobMood.valueOf(moodName)
    var pokes by remember { mutableIntStateOf(0) }
    var menuOpen by remember { mutableStateOf(false) }

    fun choose(next: BlobLook) {
        if (next == look) return
        Haptics.tap(view)
        if (onChoose != null) {
            onChoose(next)
            return
        }
        LookStore.set(context, next)
        // The widget's flipbook is drawn again in the background.
        WidgetRedraw.request(context)
    }

    androidx.compose.runtime.CompositionLocalProvider(LocalBlobLook provides look) {
    Column(Modifier.fillMaxSize().background(k.background)) {
        PageHeader(
            title = "Look",
            subtitle = agent,
            onBack = onBack,
            action = {
                Box {
                    BarButton(VaultIcons.More, "More options", { menuOpen = true }, tint = k.muted)
                    DropdownMenu(
                        expanded = menuOpen, onDismissRequest = { menuOpen = false },
                        shape = RoundedCornerShape(16.dp), containerColor = k.pressed, tonalElevation = 0.dp, shadowElevation = 0.dp,
                    ) {
                        MenuItem(VaultIcons.Refresh, "Reset to default") { menuOpen = false; choose(BlobLook.Default) }
                    }
                }
            },
        )
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Blob(mood, size = 168.dp, description = agent, pokeTick = pokes)
            // Preview the moods, as the Studio's state row.
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BlobMood.entries.forEach { m ->
                    val label = m.name
                    VaultChip(
                        label, { moodName = m.name }, selected = mood == m,
                        modifier = Modifier.semantics { contentDescription = "Preview: $label"; selected = mood == m },
                    )
                }
            }
            SecondaryButton(
                "Randomize",
                onClick = {
                    choose(BlobTaste.randomize(look))
                    pokes++
                },
                modifier = Modifier.padding(top = 8.dp),
                leading = { Icon(VaultIcons.Dices, null, tint = k.text, modifier = Modifier.size(18.dp)) },
            )

            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                Column {
                    GroupLabel("Shape")
                    TileGrid(BlobShape.entries, columns = 4) { s ->
                        LookTile("Shape", s.label, look.shape == s, { choose(look.copy(shape = s)) }) {
                            Thumb(look.copy(shape = s))
                        }
                    }
                }
                Column {
                    GroupLabel("Finish")
                    TileGrid(BlobFinish.entries, columns = 3) { f ->
                        LookTile("Finish", f.label, look.finish == f, { choose(look.copy(finish = f)) }) {
                            // Larger than the shape thumbnails, so the material itself can be seen.
                            Thumb(look.copy(finish = f), body = 64.dp)
                        }
                    }
                }
                Column {
                    GroupLabel("Colour")
                    VaultGroup {
                        FlowRow(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                            BlobColor.entries.forEach { c -> Swatch(c, look.color == c) { choose(look.copy(color = c)) } }
                        }
                    }
                }
                Column {
                    GroupLabel("Face")
                    VaultGroup {
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Eyes", style = VaultType.Body.copy(fontWeight = VaultType.Row.fontWeight), color = k.text, modifier = Modifier.weight(1f))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                BlobEyes.entries.forEach { e ->
                                    VaultChip(
                                        e.label, { choose(look.copy(eyes = e)) }, selected = look.eyes == e, onLift = true,
                                        modifier = Modifier.semantics { contentDescription = "Eyes: ${e.label}"; selected = look.eyes == e },
                                    )
                                }
                            }
                        }
                        GroupDivider()
                        GroupSwitchRow("Blush", null, look.blush, { choose(look.copy(blush = it)) })
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
    }
}

@Composable
private fun <T> TileGrid(items: List<T>, columns: Int, tile: @Composable (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { Box(Modifier.weight(1f)) { tile(it) } }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** A choice with a still picture: the pressed tone and a small check disc when chosen, the lift otherwise. */
@Composable
private fun LookTile(group: String, label: String, selected: Boolean, onClick: () -> Unit, picture: @Composable () -> Unit) {
    val k = MaterialTheme.vault
    val shape = RoundedCornerShape(18.dp)
    Box(
        Modifier.fillMaxWidth()
            .clip(shape)
            .pressable(onClick = onClick, shape = shape, rest = if (selected) k.pressed else k.surfaceHigh, pressed = k.pressed, role = Role.RadioButton)
            .clearAndSetSemantics {
                contentDescription = "$group: $label" + if (selected) ", selected" else ""
                role = Role.RadioButton
                this.selected = selected
            },
    ) {
        Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.fillMaxWidth().aspectRatio(1.25f), contentAlignment = Alignment.Center) { picture() }
            Text(
                label, style = VaultType.Caption, color = if (selected) k.text else k.muted,
                textAlign = TextAlign.Center, maxLines = 1, softWrap = false, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
        if (selected) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(6.dp).size(18.dp).background(k.accent, CircleShape),
                contentAlignment = Alignment.Center,
            ) { Icon(VaultIcons.Check, null, tint = k.onAccent, modifier = Modifier.size(12.dp)) }
        }
    }
}

/** A still blob in [look], its body about 44dp wide: the drawing box is larger than the body, so it overflows the slot. */
@Composable
private fun Thumb(look: BlobLook, body: Dp = 44.dp) {
    Box(Modifier.size(body), contentAlignment = Alignment.Center) {
        Blob(BlobMood.Idle, Modifier.wrapContentSize(unbounded = true).requiredSize(body * 1.8f), size = body * 1.8f, interactive = false, animated = false, look = look)
    }
}

/** A colour: a 36dp disc in a 48dp target; the chosen one ringed in the text colour with a gap of ground (the one ring in the app). */
@Composable
private fun Swatch(color: BlobColor, selected: Boolean, onClick: () -> Unit) {
    val k = MaterialTheme.vault
    Box(
        Modifier.size(48.dp)
            .pressable(onClick = onClick, shape = CircleShape, pressedScale = 0.9f, role = Role.RadioButton)
            .clearAndSetSemantics {
                contentDescription = "Colour: ${color.label}" + if (selected) ", selected" else ""
                role = Role.RadioButton
                this.selected = selected
            },
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Box(Modifier.size(46.dp).border(2.dp, k.text, CircleShape))
        // The hairline keeps ink (and milk on a light page) apart from the lift, two like tones meeting.
        // A disc close to the lift's own tone (ink on dark, milk on light) gets a stronger rim, so it never vanishes.
        val disc = Color(color.argb)
        val near = kotlin.math.abs(disc.luminance() - k.surfaceHigh.luminance()) < 0.05f
        Box(Modifier.size(36.dp).background(disc, CircleShape).border(1.dp, if (near) k.faint else k.line, CircleShape))
    }
}
