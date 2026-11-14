package io.github.erkanw.vault.mascot

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

// The mascot's look (handbook §4.3): Blob Atelier's Blobs series, with its ids, so a look reads and writes as a Studio
// link (`shape=mochi&color=milk&material=matte&eyes=dot&cheeks=1`).

enum class BlobShape(val id: String, val label: String) {
    Mochi("mochi", "Dumpling"), Bean("bean", "Bean"), Pebble("pebble", "Pebble"), Squircle("squircle", "Squircle"),
    Pill("pill", "Pill"), Drop("drop", "Drop"), Kitty("kitty", "Kitty"), Cloud("cloud", "Cloud"),
}

enum class BlobFinish(val id: String, val label: String) {
    Matte("matte", "Matte"), Soft("soft", "Soft"), Jelly("jelly", "Jelly"), Glass("glass", "Glass"), Clay("clay", "Clay"), Velvet("velvet", "Velvet"),
}

enum class BlobColor(val id: String, val argb: Int) {
    Milk("milk", 0xFFF2ECE4.toInt()), Peach("peach", 0xFFFFB49C.toInt()), Butter("butter", 0xFFFFD873.toInt()),
    Matcha("matcha", 0xFFB3D69A.toInt()), Mint("mint", 0xFF97E2CF.toInt()), Sky("sky", 0xFF9CCBFF.toInt()),
    Lilac("lilac", 0xFFC6B4FF.toInt()), Bubblegum("bubblegum", 0xFFFFA3C8.toInt()), Tangerine("tangerine", 0xFFFF9159.toInt()),
    Cocoa("cocoa", 0xFF9A6A52.toInt()), Ink("ink", 0xFF232228.toInt());

    /** The label: the id in sentence case. */
    val label: String get() = id.replaceFirstChar { it.uppercase() }
}

enum class BlobEyes(val id: String, val label: String) { Dot("dot", "Dot"), Capsule("capsule", "Capsule") }

/** One look of the mascot. */
data class BlobLook(
    val shape: BlobShape = BlobShape.Mochi,
    val color: BlobColor = BlobColor.Milk,
    val finish: BlobFinish = BlobFinish.Matte,
    val eyes: BlobEyes = BlobEyes.Dot,
    val blush: Boolean = true,
) {
    /** As a Studio link's hash. */
    fun toQuery(): String = "series=blobs&shape=${shape.id}&color=${color.id}&material=${finish.id}&eyes=${eyes.id}&cheeks=${if (blush) 1 else 0}"

    companion object {
        /** The default look (handbook §4.2): Dumpling, milk, matte, dot eyes, blush. */
        val Default = BlobLook()

        /** Reads [toQuery] (or a Studio link's hash); anything unknown keeps the default. */
        fun parse(query: String?): BlobLook {
            if (query.isNullOrBlank()) return Default
            val q = query.substringAfter('#').split('&').mapNotNull { p -> p.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
            return BlobLook(
                shape = BlobShape.entries.firstOrNull { it.id == q["shape"] } ?: Default.shape,
                color = BlobColor.entries.firstOrNull { it.id == q["color"] } ?: Default.color,
                finish = BlobFinish.entries.firstOrNull { it.id == q["material"] } ?: Default.finish,
                eyes = BlobEyes.entries.firstOrNull { it.id == q["eyes"] } ?: Default.eyes,
                blush = q["cheeks"]?.let { it == "1" || it == "true" } ?: Default.blush,
            )
        }
    }
}

/** The Studio's "Randomize with taste" (index.html, `TASTE`, `CURATED`, `randomize()`), exactly. */
object BlobTaste {
    private val all = BlobColor.entries.map { it.id }
    private val taste = mapOf(
        "matte" to all, "soft" to all,
        "jelly" to listOf("peach", "butter", "matcha", "mint", "sky", "lilac", "bubblegum", "tangerine"),
        "glass" to listOf("sky", "lilac", "mint", "bubblegum", "peach", "butter"),
        "clay" to listOf("milk", "peach", "butter", "matcha", "sky", "lilac", "cocoa", "tangerine"),
        "velvet" to listOf("peach", "bubblegum", "lilac", "matcha", "cocoa", "tangerine", "butter", "sky", "ink"),
    )
    private val curated = listOf(
        listOf("mochi", "peach", "jelly", "dot"), listOf("bean", "lilac", "velvet", "capsule"), listOf("pebble", "milk", "clay", "dot"),
        listOf("squircle", "ink", "matte", "capsule"), listOf("pill", "sky", "glass", "capsule"), listOf("drop", "mint", "soft", "dot"),
        listOf("kitty", "butter", "clay", "dot"), listOf("cloud", "bubblegum", "jelly", "dot"), listOf("mochi", "matcha", "velvet", "dot"),
        listOf("cloud", "sky", "glass", "dot"),
    )
    private val finishPick = listOf("jelly", "jelly", "soft", "clay", "velvet", "glass", "matte", "soft")

    fun randomize(current: BlobLook, rnd: Random = Random.Default): BlobLook {
        fun <T> pick(a: List<T>) = a[rnd.nextInt(a.size)]
        var c = current
        repeat(20) {
            var shape: String
            var color: String
            var finish: String
            var eyes: String
            if (rnd.nextDouble() < 0.3) {
                val q = pick(curated)
                shape = q[0]; color = q[1]; finish = q[2]; eyes = q[3]
            } else {
                finish = pick(finishPick)
                color = pick(taste.getValue(finish))
                shape = pick(BlobShape.entries).id
                eyes = if ((if (shape in setOf("pill", "bean", "drop")) rnd.nextDouble() < 0.7 else rnd.nextDouble() < 0.25)) "capsule" else "dot"
            }
            val blush = if (color !in setOf("ink", "cocoa")) rnd.nextDouble() < 0.7 else false
            c = BlobLook.parse("shape=$shape&color=$color&material=$finish&eyes=$eyes&cheeks=${if (blush) 1 else 0}")
            if (c.shape != current.shape || c.color != current.color || c.finish != current.finish) return c
        }
        return c
    }
}

// ---- colour helpers, as agent-mascot.js (lines 22 to 34) ----

internal class Rgb(val r: Double, val g: Double, val b: Double) {
    fun argb(alpha: Double = 1.0): Int {
        fun c(v: Double) = v.coerceIn(0.0, 255.0).let { Math.round(it).toInt() }
        return (c(alpha * 255) shl 24) or (c(r) shl 16) or (c(g) shl 8) or c(b)
    }

    companion object {
        fun of(argb: Int) = Rgb(((argb shr 16) and 255).toDouble(), ((argb shr 8) and 255).toDouble(), (argb and 255).toDouble())
        val WHITE = Rgb(255.0, 255.0, 255.0)
        val INK = Rgb(38.0, 26.0, 66.0)
    }
}

internal fun mixc(a: Rgb, b: Rgb, t: Double) = Rgb(a.r + (b.r - a.r) * t, a.g + (b.g - a.g) * t, a.b + (b.b - a.b) * t)

internal fun lum(c: Rgb): Double {
    fun f(v: Double): Double { val x = v / 255; return if (x <= 0.03928) x / 12.92 else ((x + 0.055) / 1.055).pow(2.4) }
    return 0.2126 * f(c.r) + 0.7152 * f(c.g) + 0.0722 * f(c.b)
}

internal fun rgb2hsl(c: Rgb): DoubleArray {
    val r = c.r / 255; val g = c.g / 255; val b = c.b / 255
    val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
    var h = 0.0; var s = 0.0; val l = (mx + mn) / 2
    if (mx != mn) {
        val d = mx - mn
        s = if (l > 0.5) d / (2 - mx - mn) else d / (mx + mn)
        h = when (mx) { r -> (g - b) / d + (if (g < b) 6 else 0); g -> (b - r) / d + 2; else -> (r - g) / d + 4 }
        h /= 6
    }
    return doubleArrayOf(h, s, l)
}

internal fun hsl2rgb(h: Double, s: Double, l: Double): Rgb {
    fun f(p: Double, q: Double, t0: Double): Double {
        var t = t0
        if (t < 0) t += 1.0
        if (t > 1) t -= 1.0
        return when {
            t < 1.0 / 6 -> p + (q - p) * 6 * t
            t < 0.5 -> q
            t < 2.0 / 3 -> p + (q - p) * (2.0 / 3 - t) * 6
            else -> p
        }
    }
    if (s == 0.0) return Rgb(l * 255, l * 255, l * 255)
    val q = if (l < 0.5) l * (1 + s) else l + s - l * s
    val p = 2 * l - q
    return Rgb(f(p, q, h + 1.0 / 3) * 255, f(p, q, h) * 255, f(p, q, h - 1.0 / 3) * 255)
}

internal fun hueShift(c: Rgb, deg: Double, ds: Double = 0.0, dl: Double = 0.0): Rgb {
    val h = rgb2hsl(c)
    return hsl2rgb((h[0] + deg / 360 + 1) % 1, (h[1] + ds).coerceIn(0.0, 1.0), (h[2] + dl).coerceIn(0.0, 1.0))
}
