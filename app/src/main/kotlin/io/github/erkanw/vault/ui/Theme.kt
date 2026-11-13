package io.github.erkanw.vault.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import io.github.erkanw.vault.R

/** Which look the app wears: the phone's (the default), or always light, or always dark. */
enum class ThemeMode(val id: String, val label: String) {
    System("system", "System"),
    Light("light", "Light"),
    Dark("dark", "Dark");

    companion object {
        fun fromId(id: String?): ThemeMode = entries.firstOrNull { it.id == id } ?: System
    }
}

/**
 * The agent’s palette, the Grok app played straight: a flat charcoal ground, one tonal lift for everything you touch or
 * read in a block (tiles, the input, the agent’s answers, sheets), no borders and no shadows. Text is near-white and grey.
 * White is the only accent: the send button and your own messages. Green, red and amber only ever mean a state.
 * Read it with `MaterialTheme.vault`.
 */
@Immutable
class VaultColors(
    /** The screen. */
    val background: Color,
    /** The drawer, sheets, dialogs, menus: one step up from the screen. */
    val surface: Color,
    /** Tiles, the input, the agent’s answers, search: the lift you touch. */
    val surfaceHigh: Color,
    /** A pressed tile or row. */
    val pressed: Color,
    /** A hairline, used only where two surfaces of the same tone meet. */
    val line: Color,
    val text: Color,
    /** Secondary text: previews, times, labels, placeholders (4.5:1 on [surfaceHigh]). */
    val muted: Color,
    /** Disabled text and icons. */
    val faint: Color,
    /** Your messages: the inverse pill. */
    val bubbleMine: Color,
    val onBubbleMine: Color,
    /** The agent’s answers when they sit in a bubble. */
    val bubbleTheirs: Color,
    val onBubbleTheirs: Color,
    val card: Color,
    val onCard: Color,
    /** The one accent (the send disc, a chosen item): white on dark, near-black on light. */
    val accent: Color,
    /** The accent as text colour. */
    val accentText: Color,
    val onAccent: Color,
    /** Connected, done, working: something alive and well. */
    val live: Color,
    val danger: Color,
    /** "This needs you": can't reach the agent, a request waiting. */
    val attention: Color,
    /** What dims the screen behind the open drawer, and behind a sheet. Lighter on a light page, where grey reads as dirt. */
    val drawerDim: Color,
    val sheetDim: Color,
    /** True for the light palette, for the few things that are drawn differently on it (a disabled primary button). */
    val light: Boolean,
)

private val DarkVault = VaultColors(
    background = Color(0xFF111113),
    surface = Color(0xFF19191C),
    surfaceHigh = Color(0xFF232326),
    pressed = Color(0xFF2E2E32),
    line = Color(0x14FFFFFF),
    text = Color(0xFFF5F5F7),
    muted = Color(0xFF8E8E93),
    faint = Color(0xFF5C5C62),
    bubbleMine = Color(0xFFF5F5F7),
    onBubbleMine = Color(0xFF111113),
    bubbleTheirs = Color(0xFF232326),
    onBubbleTheirs = Color(0xFFF5F5F7),
    card = Color(0xFF232326),
    onCard = Color(0xFFF5F5F7),
    accent = Color(0xFFF5F5F7),
    accentText = Color(0xFFF5F5F7),
    onAccent = Color(0xFF111113),
    live = Color(0xFF30C77B),
    danger = Color(0xFFFF5A4E),
    attention = Color(0xFFF5A524),
    drawerDim = Color(0x80000000),
    sheetDim = Color(0x52000000),
    light = false,
)

// The same roles inverted (Grok Bot's own light look). Signal colours are a step deeper so they hold as text on white
// and on the lift where most small text sits (amber 4.9:1 on the lift, red 4.6:1).
private val LightVault = VaultColors(
    background = Color(0xFFFFFFFF),
    surface = Color(0xFFF7F7F8),
    surfaceHigh = Color(0xFFF0F0F2),
    pressed = Color(0xFFE5E5E8),
    line = Color(0x14000000),
    text = Color(0xFF111113),
    muted = Color(0xFF68686D),
    faint = Color(0xFFAEAEB2),
    bubbleMine = Color(0xFF111113),
    onBubbleMine = Color(0xFFFFFFFF),
    bubbleTheirs = Color(0xFFF0F0F2),
    onBubbleTheirs = Color(0xFF111113),
    card = Color(0xFFF0F0F2),
    onCard = Color(0xFF111113),
    accent = Color(0xFF111113),
    accentText = Color(0xFF111113),
    onAccent = Color(0xFFFFFFFF),
    live = Color(0xFF1E9E5E),
    danger = Color(0xFFC9342A),
    attention = Color(0xFF9A5700),
    drawerDim = Color(0x24000000),
    sheetDim = Color(0x29000000),
    light = true,
)

val LocalVault = staticCompositionLocalOf { DarkVault }

/** Each channel's own colour, for its flat Blob cloud (the order is fixed: a channel keeps its colour). */
object ChannelColors {
    val all = listOf(
        Color(0xFFF5B83D), // amber
        Color(0xFFF2604F), // coral
        Color(0xFF3D8BF2), // blue
        Color(0xFF2FBF8F), // green
        Color(0xFFA77BF0), // violet
        Color(0xFFF27BB0), // pink
        Color(0xFF2EB5C9), // teal
        Color(0xFFB08968), // cocoa
    )

    fun at(index: Int): Color = all[Math.floorMod(index, all.size)]
}

@OptIn(ExperimentalTextApi::class)
private fun inter(weight: Int, opticalSize: Float) = Font(
    R.font.inter_variable,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight), FontVariation.Setting("opsz", opticalSize)),
)

/** Inter at text size (optical size 14): body, rows, labels. */
val Inter = FontFamily(inter(400, 14f), inter(500, 14f), inter(600, 14f), inter(700, 14f))

/** Inter cut for display sizes (optical size 28): titles of 20 sp and up, tighter and finer. */
val InterDisplay = FontFamily(inter(400, 28f), inter(500, 28f), inter(600, 28f), inter(700, 28f))

private fun style(size: Int, line: Int, weight: FontWeight = FontWeight.Normal, tracking: TextUnit = 0.sp, display: Boolean = false) =
    TextStyle(
        fontFamily = if (display) InterDisplay else Inter,
        fontSize = size.sp,
        lineHeight = line.sp,
        fontWeight = weight,
        letterSpacing = tracking,
    )

/**
 * The type scale: Inter, regular and semibold doing most of the work, tracking tightening as size grows. Sizes are in
 * sp, so they follow the phone's font size. Colours are set where the text is drawn.
 */
object VaultType {
    /** A page's big title (Settings, Files...). */
    val LargeTitle = style(30, 36, FontWeight.SemiBold, (-0.6).sp, display = true)

    /** Home's question under Blob. */
    val Display = style(22, 28, FontWeight.SemiBold, (-0.35).sp, display = true)

    /** Top bar titles, sheet titles. */
    val Title = style(17, 22, FontWeight.SemiBold, (-0.2).sp)

    /** A list row's or tile's main line. */
    val Row = style(16, 21, FontWeight.Medium, (-0.1).sp)

    /** Chat messages and other reading text. */
    val Reading = style(16, 24)

    /** Inputs and general body text. */
    val Body = style(15, 21)

    /** Second lines: previews, descriptions. */
    val Preview = style(14, 19)

    /** Group headings, chips, buttons, status lines. */
    val Label = style(13, 18, FontWeight.Medium)

    /** Times, counts, fine print. */
    val Caption = style(12, 16, tracking = 0.1.sp)
}

private val VaultTypography = Typography().let { t ->
    Typography(
        displayLarge = t.displayLarge.copy(fontFamily = InterDisplay),
        displayMedium = t.displayMedium.copy(fontFamily = InterDisplay),
        displaySmall = t.displaySmall.copy(fontFamily = InterDisplay),
        headlineLarge = t.headlineLarge.copy(fontFamily = InterDisplay),
        headlineMedium = t.headlineMedium.copy(fontFamily = InterDisplay),
        headlineSmall = VaultType.Display,
        titleLarge = VaultType.Title.copy(fontSize = 20.sp, lineHeight = 26.sp, fontFamily = InterDisplay),
        titleMedium = VaultType.Title,
        titleSmall = VaultType.Label.copy(fontSize = 14.sp, lineHeight = 20.sp),
        bodyLarge = VaultType.Reading,
        bodyMedium = VaultType.Body,
        bodySmall = VaultType.Caption,
        labelLarge = VaultType.Label.copy(fontSize = 14.sp, lineHeight = 20.sp),
        labelMedium = VaultType.Label,
        labelSmall = VaultType.Caption.copy(fontWeight = FontWeight.Medium),
    )
}

val MaterialTheme.vault: VaultColors
    @Composable
    @ReadOnlyComposable
    get() = LocalVault.current

/**
 * Tells Android 12+ which look the app wears, so the launch screen and the window behind Compose (which come from
 * `values-night` and `drawable-night-nodpi`) match it at the next cold start, not only the phone's own setting. System
 * hands the choice back to the phone. Android 10 and 11 have no such call and keep following the phone.
 */
fun applyNightMode(context: android.content.Context, mode: ThemeMode) {
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) return
    val manager = context.getSystemService(android.app.UiModeManager::class.java) ?: return
    val wanted = when (mode) {
        ThemeMode.System -> android.app.UiModeManager.MODE_NIGHT_AUTO
        ThemeMode.Light -> android.app.UiModeManager.MODE_NIGHT_NO
        ThemeMode.Dark -> android.app.UiModeManager.MODE_NIGHT_YES
    }
    manager.setApplicationNightMode(wanted)
}

/** Whether [mode] means a dark screen right now ([systemDark]: what the phone itself is set to). */
fun ThemeMode.isDark(systemDark: Boolean): Boolean = when (this) {
    ThemeMode.System -> systemDark
    ThemeMode.Light -> false
    ThemeMode.Dark -> true
}

@Composable
fun VaultTheme(mode: ThemeMode = ThemeMode.System, content: @Composable () -> Unit) {
    val dark = mode.isDark(isSystemInDarkTheme())
    val k = if (dark) DarkVault else LightVault
    val scheme = if (dark) {
        darkColorScheme(
            primary = k.accent, onPrimary = k.onAccent,
            secondaryContainer = k.surfaceHigh, onSecondaryContainer = k.text,
            background = k.background, onBackground = k.text,
            surface = k.background, onSurface = k.text,
            surfaceVariant = k.surfaceHigh, onSurfaceVariant = k.muted,
            surfaceContainerLowest = k.background, surfaceContainerLow = k.surface, surfaceContainer = k.surface,
            surfaceContainerHigh = k.surface, surfaceContainerHighest = k.surfaceHigh,
            outline = Color(0x33FFFFFF), outlineVariant = k.line,
            error = k.danger, errorContainer = Color(0xFF3D1A17), onErrorContainer = Color(0xFFFFDAD5),
            scrim = Color(0xCC000000),
        )
    } else {
        lightColorScheme(
            primary = k.accent, onPrimary = k.onAccent,
            secondaryContainer = k.surfaceHigh, onSecondaryContainer = k.text,
            background = k.background, onBackground = k.text,
            surface = k.background, onSurface = k.text,
            surfaceVariant = k.surfaceHigh, onSurfaceVariant = k.muted,
            surfaceContainerLowest = k.background, surfaceContainerLow = k.surface, surfaceContainer = k.surface,
            surfaceContainerHigh = k.surface, surfaceContainerHighest = k.surfaceHigh,
            outline = Color(0x33000000), outlineVariant = k.line,
            error = k.danger, errorContainer = Color(0xFFFFE1DD), onErrorContainer = Color(0xFF5C1510),
            scrim = Color(0x66000000),
        )
    }
    CompositionLocalProvider(LocalVault provides k) {
        MaterialTheme(colorScheme = scheme, typography = VaultTypography, content = content)
    }
}
