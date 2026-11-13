package io.github.erkanw.vault.mascot

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A channel's avatar: Blob's cloud as a flat silhouette in the channel's own [color], with capsule eyes, still.
 * Unlike [Blob], the cloud fills the box edge to edge (the box is the body's bounds), so it lines up with text and icons.
 */
@Composable
fun BlobFlat(color: Color, modifier: Modifier = Modifier, size: Dp = 32.dp) {
    val path = remember { BlobShapes.newPath().asComposePath() }
    val eyes = remember {
        val e = BlobEngine()
        e.step(0f, still = true)
        BlobEngine.Frame().also { e.frame(it) }
    }
    val eye = if (color.luminance() > 0.45f) Color(0xFF1E1C24) else Color.White
    Canvas(modifier.size(size)) {
        val left = -BlobShapes.hw
        val top = BlobShapes.top
        val w = BlobShapes.hw * 2
        val h = 90f - top
        val s = minOf(this.size.width / w, this.size.height / h)
        translate((this.size.width - w * s) / 2, (this.size.height - h * s) / 2) {
            scale(s, s, pivot = Offset.Zero) {
                translate(-left, -top) {
                    drawPath(path, color)
                    // Eyes a little larger than on the glass Blob, so they still read at avatar size (as Grok Bot's).
                    val eyeScale = 1.35f
                    for (i in 0..1) {
                        val ew = 15f * eyes.eyeFs[i] * eyeScale
                        val eh = 30f * eyeScale
                        drawRoundRect(
                            eye,
                            topLeft = Offset(eyes.eyeX[i] - ew / 2, eyes.eyeY[i] - eh / 2),
                            size = Size(ew, eh),
                            cornerRadius = CornerRadius(ew / 2, ew / 2),
                        )
                    }
                }
            }
        }
    }
}
