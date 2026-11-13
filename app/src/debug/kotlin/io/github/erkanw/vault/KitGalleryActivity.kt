package io.github.erkanw.vault

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.erkanw.vault.mascot.Blob
import io.github.erkanw.vault.mascot.BlobFlat
import io.github.erkanw.vault.mascot.BlobMood
import io.github.erkanw.vault.ui.BarButton
import io.github.erkanw.vault.ui.ChannelColors
import io.github.erkanw.vault.ui.Composer
import io.github.erkanw.vault.ui.VaultTheme
import io.github.erkanw.vault.ui.VaultBubble
import io.github.erkanw.vault.ui.VaultChip
import io.github.erkanw.vault.ui.VaultIcons
import io.github.erkanw.vault.ui.VaultRow
import io.github.erkanw.vault.ui.VaultTopBar
import io.github.erkanw.vault.ui.VaultType
import io.github.erkanw.vault.ui.Presence
import io.github.erkanw.vault.ui.PrimaryButton
import io.github.erkanw.vault.ui.PushDrawer
import io.github.erkanw.vault.ui.QuietButton
import io.github.erkanw.vault.ui.SecondaryButton
import io.github.erkanw.vault.ui.SectionLabel
import io.github.erkanw.vault.ui.StateDot
import io.github.erkanw.vault.ui.StatusLine
import io.github.erkanw.vault.ui.SuggestionTile
import io.github.erkanw.vault.ui.TitleWithDot
import io.github.erkanw.vault.ui.UserBubble
import io.github.erkanw.vault.ui.vault
import io.github.erkanw.vault.ui.rememberPushDrawerState
import kotlinx.coroutines.launch

/**
 * Debug only: the app's components on sample content, for design review. Extras: page = home (default) | parts;
 * drawer = open.
 */
class KitGalleryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val page = intent.getStringExtra("page") ?: "home"
        val drawerOpen = intent.getStringExtra("drawer") == "open"
        setContent {
            VaultTheme {
                Box(Modifier.fillMaxSize().background(MaterialTheme.vault.background)) {
                    if (page == "parts") Parts() else HomeSample(drawerOpen)
                }
            }
        }
    }
}

@Composable
private fun HomeSample(startOpen: Boolean) {
    val k = MaterialTheme.vault
    val drawer = rememberPushDrawerState()
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { if (startOpen) drawer.open() }
    PushDrawer(drawer, drawer = { DrawerSample() }) {
        Column(Modifier.fillMaxSize().background(k.background).statusBarsPadding().navigationBarsPadding().imePadding()) {
            VaultTopBar(
                left = { BarButton(VaultIcons.Menu, "Chats and channels", { scope.launch { drawer.open() } }) },
                right = { BarButton(VaultIcons.SquarePen, "New chat", {}, iconSize = 21.dp) },
                title = { TitleWithDot("Apel", Presence.Ready) },
            )
            Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Blob(BlobMood.Idle, size = 210.dp)
                Text("What should Apel do?", style = VaultType.Display, color = k.text, textAlign = TextAlign.Center)
            }
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SuggestionTile("Daily brief", "New message", {}, Modifier.weight(1f), leading = { BlobFlat(ChannelColors.at(0), size = 22.dp) })
                    SuggestionTile("Notes", "Working…", {}, Modifier.weight(1f), leading = { BlobFlat(ChannelColors.at(1), size = 22.dp) })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SuggestionTile("Weekend plans", "Booked the table for 8", {}, Modifier.weight(1f))
                    SuggestionTile("Next: 07:30", "Morning brief", {}, Modifier.weight(1f))
                }
                Composer(text, { text = it }, onSend = {}, onAttach = {}, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
            }
        }
    }
}

@Composable
private fun DrawerSample() {
    val k = MaterialTheme.vault
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 8.dp)) {
        Spacer(Modifier.height(8.dp))
        VaultRow("Search", {}, leading = { Icon(VaultIcons.Search, null, tint = k.muted, modifier = Modifier.size(20.dp)) })
        SectionLabel("Channels")
        VaultRow("Daily brief", {}, leading = { BlobFlat(ChannelColors.at(0), size = 26.dp) }, trailing = { StateDot(working = false) }, emphasized = true)
        VaultRow("Notes", {}, leading = { BlobFlat(ChannelColors.at(1), size = 26.dp) }, trailing = { StateDot(working = true) })
        SectionLabel("Today")
        VaultRow("Weekend plans", {}, selected = true)
        VaultRow("Fix the router script", {})
        VaultRow("Book recommendations", {})
        SectionLabel("Yesterday")
        VaultRow("Tax letter summary", {})
        VaultRow("Trip to Lisbon", {})
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            BarButton(VaultIcons.Folder, "Files", {}, tint = k.muted)
            BarButton(VaultIcons.Sparkles, "Skills", {}, tint = k.muted)
            BarButton(VaultIcons.Clock, "Automations", {}, tint = k.muted)
            BarButton(VaultIcons.Sliders, "Settings", {}, tint = k.muted)
        }
    }
}

@Composable
private fun Parts() {
    val k = MaterialTheme.vault
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Settings", style = VaultType.LargeTitle, color = k.text)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            UserBubble { Text("What's on my calendar tomorrow?", style = VaultType.Reading, color = k.onBubbleMine) }
        }
        VaultBubble {
            Text("Two things: the dentist at 09:30 and Sam's birthday dinner at 19:00. Want a reminder an hour before each?", style = VaultType.Reading, color = k.onBubbleTheirs)
        }
        StatusLine("Created routine · Morning brief", leading = { Icon(VaultIcons.Clock, null, tint = k.muted, modifier = Modifier.size(13.dp)) })
        StatusLine("Apel is working", leading = { Blob(BlobMood.Working, size = 28.dp, interactive = false) })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            VaultChip("All", {}, selected = true)
            VaultChip("Pinned", {})
            VaultChip("Archived", {})
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            PrimaryButton("Connect", {})
            SecondaryButton("Test", {})
            QuietButton("Cancel", {}, color = k.muted)
        }
        io.github.erkanw.vault.ui.ApprovalCard(
            io.github.erkanw.vault.ui.ApprovalRequest("a1", "db_execute", "Add 3 rows to the expenses table", "INSERT INTO expenses (day, what, amount) VALUES ('11-12', 'Lunch', 12.50), …"),
            sending = false, onAllow = {}, onDeny = {},
        )
        Composer("Summarise the tax letter and tell me what I owe by when", {}, onSend = {}, onAttach = {}, busy = true)
        Composer("", {}, onSend = {}, onAttach = {})
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            ChannelColors.all.forEach { BlobFlat(it, size = 30.dp) }
        }
        Text("Body text in Inter at 15 sp, the default for inputs and lists. 0123456789", style = VaultType.Body, color = k.text)
        Text("Preview line, muted, 14 sp", style = VaultType.Preview, color = k.muted)
        Text("Caption 12 sp · 08:06", style = VaultType.Caption, color = k.muted)
    }
}
