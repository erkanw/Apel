package io.github.erkanw.vault.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The agent’s icons: one family, drawn as lines of the same weight with round ends on a 24 unit grid, so no icon is
 * heavier or lighter than its neighbour. They are tinted by whoever draws them (`Icon(..., tint = ...)`).
 * The shapes follow the Lucide icon set (ISC licence, https://lucide.dev), written out here so the app needs no
 * icon library.
 */
object VaultIcons {
    val Search by lazy { icon("Search") { circle(11f, 11f, 8f); path("m21 21-4.3-4.3") } }
    val More by lazy { icon("More") { circle(5f, 12f, 1f); circle(12f, 12f, 1f); circle(19f, 12f, 1f) } }
    val Plus by lazy { icon("Plus") { path("M5 12h14"); path("M12 5v14") } }
    val Close by lazy { icon("Close") { path("M18 6 6 18"); path("m6 6 12 12") } }
    val ArrowUp by lazy { icon("ArrowUp") { path("m5 12 7-7 7 7"); path("M12 19V5") } }
    val Info by lazy { icon("Info") { circle(12f, 12f, 10f); path("M12 16v-4"); path("M12 8h.01") } }
    val ArrowLeft by lazy { icon("ArrowLeft") { path("m12 19-7-7 7-7"); path("M19 12H5") } }
    val ChevronRight by lazy { icon("ChevronRight") { path("m9 18 6-6-6-6") } }
    val ChevronDown by lazy { icon("ChevronDown") { path("m6 9 6 6 6-6") } }

    /** Files. */
    val Folder by lazy {
        icon("Folder") {
            path("M20 20a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-7.9a2 2 0 0 1-1.69-.9L9.6 3.9A2 2 0 0 0 7.93 3H4a2 2 0 0 0-2 2v13a2 2 0 0 0 2 2Z")
        }
    }

    /** Skills. */
    val Sparkles by lazy {
        icon("Sparkles") {
            path("m12 3-1.912 5.813a2 2 0 0 1-1.275 1.275L3 12l5.813 1.912a2 2 0 0 1 1.275 1.275L12 21l1.912-5.813a2 2 0 0 1 1.275-1.275L21 12l-5.813-1.912a2 2 0 0 1-1.275-1.275L12 3Z")
        }
    }

    /** Automation. */
    val Clock by lazy { icon("Clock") { circle(12f, 12f, 10f); path("M12 6v6l4 2") } }

    /** Databases (Lucide database). */
    /** Lucide bot: the Agents page. */
    val Bot by lazy { icon("Bot") { path("M12 8V4H8"); path("M6 8h12a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2v-8a2 2 0 0 1 2-2z"); path("M2 14h2"); path("M20 14h2"); path("M15 13v2"); path("M9 13v2") } }
    val Database by lazy { icon("Database") { path("M3 5a9 3 0 1 0 18 0a9 3 0 1 0 -18 0"); path("M3 5v14a9 3 0 0 0 18 0V5"); path("M3 12a9 3 0 0 0 18 0") } }

    /** Settings. */
    val Sliders by lazy {
        icon("Sliders") {
            path("M21 4h-7"); path("M10 4H3"); path("M21 12h-9"); path("M8 12H3"); path("M21 20h-5"); path("M12 20H3")
            path("M14 2v4"); path("M8 10v4"); path("M16 18v4")
        }
    }

    /** Agent settings (Lucide "settings"). */
    val Cog by lazy {
        icon("Cog") {
            path("M12.22 2h-.44a2 2 0 0 0-2 2v.18a2 2 0 0 1-1 1.73l-.43.25a2 2 0 0 1-2 0l-.15-.08a2 2 0 0 0-2.73.73l-.22.38a2 2 0 0 0 .73 2.73l.15.1a2 2 0 0 1 1 1.72v.51a2 2 0 0 1-1 1.74l-.15.09a2 2 0 0 0-.73 2.73l.22.38a2 2 0 0 0 2.73.73l.15-.08a2 2 0 0 1 2 0l.43.25a2 2 0 0 1 1 1.73V20a2 2 0 0 0 2 2h.44a2 2 0 0 0 2-2v-.18a2 2 0 0 1 1-1.73l.43-.25a2 2 0 0 1 2 0l.15.08a2 2 0 0 0 2.73-.73l.22-.39a2 2 0 0 0-.73-2.73l-.15-.08a2 2 0 0 1-1-1.74v-.5a2 2 0 0 1 1-1.74l.15-.09a2 2 0 0 0 .73-2.73l-.22-.38a2 2 0 0 0-2.73-.73l-.15.08a2 2 0 0 1-2 0l-.43-.25a2 2 0 0 1-1-1.73V4a2 2 0 0 0-2-2z")
            path("M9 12a3 3 0 1 0 6 0a3 3 0 1 0 -6 0")
        }
    }

    /** Tools. */
    val Wrench by lazy {
        icon("Wrench") {
            path("M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.77-3.77a6 6 0 0 1-7.94 7.94l-6.91 6.91a2.12 2.12 0 0 1-3-3l6.91-6.91a6 6 0 0 1 7.94-7.94l-3.76 3.76z")
        }
    }

    /** Models. */
    val Cube by lazy {
        icon("Cube") {
            path("M21 8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16Z")
            path("m3.3 7 8.7 5 8.7-5"); path("M12 22V12")
        }
    }

    /** OpenRouter (credits). */
    val Coin by lazy { icon("Coin") { circle(12f, 12f, 10f); path("M16 8h-6a2 2 0 1 0 0 4h4a2 2 0 1 1 0 4H8"); path("M12 18V6") } }

    /** Commands (the composer's + menu). */
    val Terminal by lazy { icon("Terminal") { path("m4 17 6-6-6-6"); path("M12 19h8") } }

    /** Play a video. */
    /** Save to the phone. */
    val Download by lazy { icon("Download") { path("M12 15V3"); path("M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"); path("m7 10 5 5 5-5") } }

    val Play by lazy { icon("Play") { path("M6 4.5v15a1 1 0 0 0 1.5.86l12.5-7.5a1 1 0 0 0 0-1.72L7.5 3.64A1 1 0 0 0 6 4.5z") } }

    /** A chat. */
    val Chat by lazy { icon("Chat") { path("M7.9 20A9 9 0 1 0 4 16.1L2 22Z") } }

    /** Opens the drawer: two lines, the lower one shorter (as Grok's). */
    val Menu by lazy { icon("Menu") { path("M4 8h16"); path("M4 16h10") } }

    /** New chat. */
    val SquarePen by lazy {
        icon("SquarePen") {
            path("M12 3H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7")
            path("M18.375 2.625a1 1 0 0 1 3 3l-9.013 9.014a2 2 0 0 1-.853.505l-2.873.84a.5.5 0 0 1-.62-.62l.84-2.873a2 2 0 0 1 .506-.852z")
        }
    }
    val Pencil by lazy {
        icon("Pencil") {
            path("M21.174 6.812a1 1 0 0 0-3.986-3.987L3.842 16.174a2 2 0 0 0-.5.83l-1.321 4.352a.5.5 0 0 0 .623.622l4.353-1.32a2 2 0 0 0 .83-.497z")
            path("m15 5 4 4")
        }
    }
    val Paperclip by lazy {
        icon("Paperclip") { path("m16 6-8.414 8.586a2 2 0 0 0 2.829 2.829l8.414-8.586a4 4 0 1 0-5.657-5.657l-8.379 8.551a6 6 0 1 0 8.485 8.485l8.379-8.551") }
    }
    val Copy by lazy {
        icon("Copy") {
            path("M10 8h10a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H10a2 2 0 0 1-2-2V10a2 2 0 0 1 2-2z")
            path("M4 16c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2h10c1.1 0 2 .9 2 2")
        }
    }
    /** Lucide dices: Randomize on the Look page. */
    val Dices by lazy {
        icon("Dices") {
            path("M4 10h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2v-8a2 2 0 0 1 2-2z")
            path("m17.92 14 3.5-3.5a2.24 2.24 0 0 0 0-3l-5-4.92a2.24 2.24 0 0 0-3 0L10 6")
            path("M6 18h.01"); path("M10 14h.01"); path("M15 6h.01"); path("M18 9h.01")
        }
    }
    val Check by lazy { icon("Check") { path("M20 6 9 17l-5-5") } }

    /** Stop a running answer (drawn as a filled-looking rounded square outline). */
    val Stop by lazy { icon("Stop") { path("M8 6h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2z") } }
    val Pin by lazy {
        icon("Pin") {
            path("M12 17v5")
            path("M9 10.76a2 2 0 0 1-1.11 1.79l-1.78.9A2 2 0 0 0 5 15.24V16a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1v-.76a2 2 0 0 0-1.11-1.79l-1.78-.9A2 2 0 0 1 15 10.76V7a1 1 0 0 1 1-1 2 2 0 0 0 0-4H8a2 2 0 0 0 0 4 1 1 0 0 1 1 1z")
        }
    }
    val Archive by lazy {
        icon("Archive") {
            path("M3 3h18a1 1 0 0 1 1 1v2a1 1 0 0 1-1 1H3a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1z")
            path("M4 8v11a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8"); path("M10 12h4")
        }
    }
    val Trash by lazy {
        icon("Trash") { path("M3 6h18"); path("M19 6v14c0 1-1 2-2 2H7c-1 0-2-1-2-2V6"); path("M8 6V4c0-1 1-2 2-2h4c1 0 2 1 2 2v2") }
    }
    val Refresh by lazy {
        icon("Refresh") {
            path("M3 12a9 9 0 0 1 9-9 9.75 9.75 0 0 1 6.74 2.74L21 8"); path("M21 3v5h-5")
            path("M21 12a9 9 0 0 1-9 9 9.75 9.75 0 0 1-6.74-2.74L3 16"); path("M8 16H3v5")
        }
    }
    val ArrowDown by lazy { icon("ArrowDown") { path("M12 5v14"); path("m19 12-7 7-7-7") } }
    val FileText by lazy {
        icon("FileText") {
            path("M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7Z"); path("M14 2v4a2 2 0 0 0 2 2h4")
            path("M10 9H8"); path("M16 13H8"); path("M16 17H8")
        }
    }
    val Image by lazy {
        icon("Image") {
            path("M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z")
            circle(9f, 9f, 2f); path("m21 15-3.086-3.086a2 2 0 0 0-2.828 0L6 21")
        }
    }
}

private class IconBuilder(private val b: ImageVector.Builder) {
    fun path(d: String) {
        b.addPath(
            pathData = addPathNodes(d),
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.7f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }

    /** A circle, as two arcs. */
    fun circle(cx: Float, cy: Float, r: Float) {
        path("M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0")
    }
}

private fun icon(name: String, build: IconBuilder.() -> Unit): ImageVector {
    val b = ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
    IconBuilder(b).build()
    return b.build()
}
