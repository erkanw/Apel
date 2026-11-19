package io.github.erkanw.vault.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.erkanw.vault.R
import kotlinx.coroutines.launch

/** One card of the walkthrough: the page's icon, its name, what it is for, and where it is. */
private data class TourCard(val icon: ImageVector, val title: Int, val body: Int, val where: Int)

private val cards = listOf(
    TourCard(VaultIcons.Bot, R.string.tour_agents_title, R.string.tour_agents_body, R.string.tour_agents_where),
    TourCard(VaultIcons.Folder, R.string.tour_files_title, R.string.tour_files_body, R.string.tour_files_where),
    TourCard(VaultIcons.Sparkles, R.string.tour_skills_title, R.string.tour_skills_body, R.string.tour_skills_where),
    TourCard(VaultIcons.Clock, R.string.tour_automations_title, R.string.tour_automations_body, R.string.tour_automations_where),
    TourCard(VaultIcons.Database, R.string.tour_databases_title, R.string.tour_databases_body, R.string.tour_databases_where),
    TourCard(VaultIcons.Wrench, R.string.tour_tools_title, R.string.tour_tools_body, R.string.tour_tools_where),
    TourCard(VaultIcons.Chat, R.string.tour_channels_title, R.string.tour_channels_body, R.string.tour_channels_where),
)

/**
 * The walkthrough (PLAN §13, Phase 11): after the welcome, one short card per page on what it is for and where it is.
 * Swipe or Next; Skip or Back ends it. Shown once; About shows it again.
 */
@Composable
fun TourScreen(onShown: () -> Unit, onDone: () -> Unit) {
    // Seen once means once: an app closed halfway through doesn't show it again.
    androidx.compose.runtime.LaunchedEffect(Unit) { onShown() }
    val k = MaterialTheme.vault
    val agent = LocalAgentName.current
    val pager = rememberPagerState { cards.size }
    val scope = rememberCoroutineScope()
    val last = pager.currentPage == cards.lastIndex
    BackHandler {
        if (pager.currentPage > 0) scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } else onDone()
    }
    Column(Modifier.fillMaxSize().background(k.background).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            if (!last) QuietButton(stringResource(R.string.tour_skip), onDone, color = k.muted)
        }
        HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth()) { page ->
            val card = cards[page]
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.size(88.dp).clip(CircleShape).background(k.surfaceHigh), contentAlignment = Alignment.Center) {
                    Icon(card.icon, null, tint = k.text, modifier = Modifier.size(36.dp))
                }
                Spacer(Modifier.height(28.dp))
                Text(
                    stringResource(card.title), style = VaultType.Display, color = k.text, textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    stringResource(card.body, agent), style = VaultType.Body.copy(lineBreak = LineBreak.Paragraph), color = k.muted,
                    textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp),
                )
                Text(
                    stringResource(card.where), style = VaultType.Caption, color = k.text, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 20.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp)).background(k.surfaceHigh).padding(horizontal = 14.dp, vertical = 7.dp),
                )
                Spacer(Modifier.height(24.dp))
            }
        }
        val position = stringResource(R.string.tour_position, pager.currentPage + 1, cards.size)
        Row(
            Modifier.fillMaxWidth().padding(vertical = 12.dp).semantics(mergeDescendants = true) { contentDescription = position },
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        ) {
            repeat(cards.size) { i ->
                val on = i == pager.currentPage
                val width by animateDpAsState(if (on) 18.dp else 6.dp, Motion.settle(), label = "dot")
                val color by animateColorAsState(if (on) k.text else k.faint, label = "dotColor")
                Box(Modifier.height(6.dp).width(width).clip(CircleShape).background(color))
            }
        }
        Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 16.dp, top = 4.dp)) {
            PrimaryButton(
                stringResource(if (last) R.string.tour_done else R.string.tour_next),
                { if (last) onDone() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } },
                Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Drawer → Agent settings: the pages that shape how the agents work (PLAN §13, Phase 11). */
@Composable
fun AgentSettingsScreen(onBack: () -> Unit, onAutomations: () -> Unit, onSkills: () -> Unit, onTools: () -> Unit) {
    val k = MaterialTheme.vault
    val agent = LocalAgentName.current
    Column(Modifier.fillMaxSize().background(k.background)) {
        PageHeader(stringResource(R.string.agent_settings_title), stringResource(R.string.agent_settings_sub), onBack)
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            VaultGroup {
                GroupActionRow(stringResource(R.string.agent_settings_automations), stringResource(R.string.agent_settings_automations_sub, agent), onAutomations, chevron = true,
                    leading = { Icon(VaultIcons.Clock, null, tint = k.text, modifier = Modifier.size(20.dp)) })
                GroupDivider()
                GroupActionRow(stringResource(R.string.agent_settings_skills), stringResource(R.string.agent_settings_skills_sub, agent), onSkills, chevron = true,
                    leading = { Icon(VaultIcons.Sparkles, null, tint = k.text, modifier = Modifier.size(20.dp)) })
                GroupDivider()
                GroupActionRow(stringResource(R.string.agent_settings_tools), stringResource(R.string.agent_settings_tools_sub, agent), onTools, chevron = true,
                    leading = { Icon(VaultIcons.Wrench, null, tint = k.text, modifier = Modifier.size(20.dp)) })
            }
            Text(
                stringResource(R.string.agent_settings_note), style = VaultType.Caption, color = k.muted,
                modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp),
            )
        }
    }
}
