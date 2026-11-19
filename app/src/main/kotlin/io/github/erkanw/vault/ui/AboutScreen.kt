package io.github.erkanw.vault.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.erkanw.vault.R
import io.github.erkanw.vault.mascot.Blob
import io.github.erkanw.vault.mascot.BlobMood
import io.github.erkanw.vault.store.Diagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One licence on the Licences page: who made it, and the asset files of its text (shown one after the other). */
private data class Licence(val id: String, val title: Int?, val by: Int, val files: List<String>)

private val APACHE = listOf("licenses/LICENSE")
private val LICENCES = listOf(
    Licence("apel", null, R.string.licence_apel_by, listOf("licenses/NOTICE", "licenses/LICENSE")),
    Licence("blob", R.string.licence_blob, R.string.licence_blob_by, listOf("licenses/BlobAtelier-MIT.txt")),
    Licence("inter", R.string.licence_inter, R.string.licence_inter_by, listOf("licenses/Inter-OFL.txt")),
    Licence("lucide", R.string.licence_lucide, R.string.licence_lucide_by, listOf("licenses/Lucide-ISC.txt")),
    Licence("androidx", R.string.licence_androidx, R.string.licence_androidx_by, APACHE),
    Licence("kotlin", R.string.licence_kotlin, R.string.licence_kotlin_by, APACHE),
    Licence("okhttp", R.string.licence_okhttp, R.string.licence_okhttp_by, APACHE),
)

/**
 * About (PLAN §6.12): the app, its version and what it is; the privacy statement; the licences with their full texts;
 * the source; the welcome again; diagnostics to copy. The privacy and licence pages open inside this page, with their
 * own Back.
 */
@Composable
fun AboutScreen(onBack: () -> Unit, onWelcome: () -> Unit) {
    var page by rememberSaveable { mutableStateOf("main") }
    BackHandler(enabled = page != "main") { page = if (page.startsWith("licence:")) "licences" else "main" }
    AnimatedContent(page, transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) }, label = "about") { p ->
        when {
            p == "privacy" -> PrivacyPage(onBack = { page = "main" })
            p == "licences" -> LicencesPage(onBack = { page = "main" }, onOpen = { page = "licence:$it" })
            p.startsWith("licence:") -> LICENCES.firstOrNull { it.id == p.removePrefix("licence:") }
                ?.let { LicenceTextPage(it, onBack = { page = "licences" }) }
            else -> AboutMain(onBack, onWelcome, onPrivacy = { page = "privacy" }, onLicences = { page = "licences" })
        }
    }
}

@Composable
private fun AboutMain(onBack: () -> Unit, onWelcome: () -> Unit, onPrivacy: () -> Unit, onLicences: () -> Unit) {
    val k = MaterialTheme.vault
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val appName = stringResource(R.string.app_name)
    val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty() }
    var note by remember { mutableStateOf<String?>(null) }
    val copied = stringResource(R.string.about_diagnostics_copied)
    val noBrowser = stringResource(R.string.about_no_browser)
    val sourceUrl = stringResource(R.string.about_source_url)

    Box(Modifier.fillMaxSize().background(k.background)) {
        Column(Modifier.fillMaxSize()) {
            PageHeader(title = stringResource(R.string.about_title), subtitle = "", onBack = onBack)
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                Column(Modifier.fillMaxWidth().padding(top = 8.dp, start = 16.dp, end = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Blob(BlobMood.Idle, size = 112.dp, interactive = false)
                    Spacer(Modifier.height(8.dp))
                    Text(appName, style = VaultType.Display, color = k.text, modifier = Modifier.semantics { heading() })
                    Text(stringResource(R.string.about_version, version), style = VaultType.Label.copy(fontWeight = null), color = k.muted)
                    Spacer(Modifier.height(10.dp))
                    Text(stringResource(R.string.about_tagline), style = VaultType.Body, color = k.text, textAlign = TextAlign.Center)
                    Text(stringResource(R.string.about_open_source), style = VaultType.Body, color = k.muted, textAlign = TextAlign.Center)
                }
                VaultGroup {
                    GroupActionRow(stringResource(R.string.about_privacy), stringResource(R.string.about_privacy_sub), onPrivacy, chevron = true)
                    GroupDivider()
                    GroupActionRow(stringResource(R.string.about_licences), stringResource(R.string.about_licences_sub, appName), onLicences, chevron = true)
                    GroupDivider()
                    GroupActionRow(stringResource(R.string.about_source), stringResource(R.string.about_source_sub), {
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(sourceUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        } catch (e: ActivityNotFoundException) {
                            note = noBrowser
                        }
                    })
                }
                Column {
                    VaultGroup {
                        GroupActionRow(stringResource(R.string.about_welcome), stringResource(R.string.about_welcome_sub), onWelcome)
                        GroupDivider()
                        GroupActionRow(stringResource(R.string.about_diagnostics), stringResource(R.string.about_diagnostics_sub), {
                            scope.launch {
                                val report = runCatching { Diagnostics.report(context) }.getOrElse { "Apel $version: diagnostics failed (${it.javaClass.simpleName})" }
                                clipboard.setText(AnnotatedString(report))
                                // Android 13 and later show their own preview of what was copied.
                                if (android.os.Build.VERSION.SDK_INT < 33) note = copied
                            }
                        })
                    }
                    Text(
                        stringResource(R.string.about_credits),
                        style = VaultType.Caption, color = k.muted, modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp),
                    )
                }
                Spacer(Modifier.height(12.dp))
            }
        }
        note?.let { text ->
            LaunchedEffect(text) { delay(4000); note = null }
            NotePill(text, Modifier.align(Alignment.BottomCenter))
        }
    }
}

/** The short note that rises at the foot of a page for 4 s; read out politely. */
@Composable
internal fun NotePill(text: String, modifier: Modifier = Modifier) {
    val k = MaterialTheme.vault
    Box(modifier.navigationBarsPadding().padding(bottom = 24.dp, start = 16.dp, end = 16.dp)) {
        Text(
            text, style = VaultType.Label.copy(fontWeight = null), color = k.text, textAlign = TextAlign.Center,
            modifier = Modifier.clip(RoundedCornerShape(24.dp)).background(k.pressed).padding(horizontal = 16.dp, vertical = 10.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

@Composable
private fun PrivacyPage(onBack: () -> Unit) {
    val k = MaterialTheme.vault
    val appName = stringResource(R.string.app_name)
    Column(Modifier.fillMaxSize().background(k.background)) {
        PageHeader(title = stringResource(R.string.about_privacy), subtitle = appName, onBack = onBack)
        SelectionContainer(Modifier.weight(1f)) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 24.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.privacy_intro, appName), style = VaultType.Reading, color = k.text)
                PrivacySection(stringResource(R.string.privacy_stays_title), stringResource(R.string.privacy_stays, appName))
                PrivacySection(
                    stringResource(R.string.privacy_leaves_title),
                    stringResource(R.string.privacy_leaves_provider),
                    stringResource(R.string.privacy_leaves_web),
                    stringResource(R.string.privacy_leaves_servers),
                    stringResource(R.string.privacy_leaves_voice),
                )
                PrivacySection(stringResource(R.string.privacy_backups_title), stringResource(R.string.privacy_backups))
                PrivacySection(stringResource(R.string.privacy_permissions_title), stringResource(R.string.privacy_permissions, appName))
                PrivacySection(stringResource(R.string.privacy_questions_title), stringResource(R.string.privacy_questions, appName))
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun PrivacySection(title: String, vararg paragraphs: String) {
    val k = MaterialTheme.vault
    Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = VaultType.Title, color = k.text, modifier = Modifier.semantics { heading() })
        paragraphs.forEach { Text(it, style = VaultType.Reading, color = k.text) }
    }
}

@Composable
private fun LicencesPage(onBack: () -> Unit, onOpen: (String) -> Unit) {
    val k = MaterialTheme.vault
    val appName = stringResource(R.string.app_name)
    Column(Modifier.fillMaxSize().background(k.background)) {
        PageHeader(title = stringResource(R.string.about_licences), subtitle = appName, onBack = onBack)
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            VaultGroup {
                LICENCES.forEachIndexed { i, l ->
                    if (i > 0) GroupDivider()
                    GroupActionRow(l.title?.let { stringResource(it) } ?: appName, stringResource(l.by), { onOpen(l.id) }, chevron = true)
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun LicenceTextPage(licence: Licence, onBack: () -> Unit) {
    val k = MaterialTheme.vault
    val context = LocalContext.current
    val appName = stringResource(R.string.app_name)
    val unreadable = stringResource(R.string.licence_unreadable)
    val paragraphs by produceState<List<String>?>(null, licence.id) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                licence.files.flatMap { f -> reflowLicence(context.assets.open(f).use { it.readBytes().toString(Charsets.UTF_8) }) }
            }.getOrElse { listOf(unreadable) }
        }
    }
    Column(Modifier.fillMaxSize().background(k.background)) {
        PageHeader(title = licence.title?.let { stringResource(it) } ?: appName, subtitle = stringResource(licence.by).substringAfter(" · ").substringBefore(" · "), onBack = onBack)
        SelectionContainer(Modifier.weight(1f)) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 24.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (paragraphs == null) TypingDots(k.muted)
                paragraphs?.forEach { Text(it, style = VaultType.Body, color = k.text) }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

/**
 * A licence text made to read on a phone: its paragraphs (split at blank lines) joined into running text, so they wrap
 * to the screen instead of at 72 columns. A block of short lines (a title, a name and a copyright) keeps its breaks; a
 * list item ("- …") starts its own paragraph. A paragraph that is a rule of dashes is dropped.
 */
internal fun reflowLicence(text: String): List<String> =
    text.replace("\r\n", "\n").split(Regex("\n[ \t]*\n")).flatMap { p ->
        val lines = p.lines().map { it.trim() }.filter { it.isNotEmpty() }
        when {
            lines.isEmpty() -> emptyList()
            lines.size > 1 && lines.all { it.length < 50 } -> listOf(lines.joinToString("\n"))
            else -> {
                val out = ArrayList<StringBuilder>()
                for (l in lines) if (out.isEmpty() || l.startsWith("- ") || l.startsWith("* ")) out += StringBuilder(l) else out.last().append(' ').append(l)
                out.map { it.toString().replace(Regex(" {2,}"), " ") }
            }
        }
    }.filter { !it.all { c -> c == '-' || c == '=' || c == ' ' || c == '\n' } }
