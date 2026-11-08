package com.omninote.pro.ui.workspace

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.omninote.pro.core.model.BrushType

/**
 * Vertical pen-tip silhouettes: each instrument is drawn upright with its tip pointing down, so a row of slots reads
 * like a row of pens. The tip and ferrule take the live ink colour ([tint]); the barrel uses [bodyColor].
 */
@Composable
fun BrushTypeIcon(
    type: BrushType,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 28.dp,
    bodyColor: Color = Warm.Text
) {
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension
        val cx = s * 0.5f
        val barrel = bodyColor.copy(alpha = 0.85f)
        when (type) {
            BrushType.FOUNTAIN_PEN -> {
                barrelRect(cx, s * 0.06f, s * 0.46f, s * 0.2f, s, barrel)
                // Nib: a long kite with a breather hole and a centre slit.
                val nib = Path().apply {
                    moveTo(cx - s * 0.12f, s * 0.52f)
                    lineTo(cx + s * 0.12f, s * 0.52f)
                    cubicTo(cx + s * 0.13f, s * 0.7f, cx + s * 0.05f, s * 0.84f, cx, s * 0.95f)
                    cubicTo(cx - s * 0.05f, s * 0.84f, cx - s * 0.13f, s * 0.7f, cx - s * 0.12f, s * 0.52f)
                    close()
                }
                drawPath(nib, tint)
                drawCircle(bodyColor.copy(alpha = 0.9f), s * 0.025f, Offset(cx, s * 0.68f))
                drawLine(bodyColor.copy(alpha = 0.9f), Offset(cx, s * 0.7f), Offset(cx, s * 0.92f), s * 0.018f)
            }
            BrushType.BALLPOINT -> {
                barrelRect(cx, s * 0.06f, s * 0.5f, s * 0.17f, s, barrel)
                val cone = Path().apply {
                    moveTo(cx - s * 0.085f, s * 0.56f)
                    lineTo(cx + s * 0.085f, s * 0.56f)
                    lineTo(cx + s * 0.025f, s * 0.88f)
                    lineTo(cx - s * 0.025f, s * 0.88f)
                    close()
                }
                drawPath(cone, barrel)
                drawCircle(tint, s * 0.04f, Offset(cx, s * 0.92f))
            }
            BrushType.FINELINER -> {
                barrelRect(cx, s * 0.06f, s * 0.5f, s * 0.15f, s, barrel)
                drawRect(tint, Offset(cx - s * 0.075f, s * 0.56f), Size(s * 0.15f, s * 0.07f))
                drawLine(tint, Offset(cx, s * 0.63f), Offset(cx, s * 0.95f), s * 0.035f, StrokeCap.Round)
            }
            BrushType.BRUSH_PEN -> {
                barrelRect(cx, s * 0.06f, s * 0.42f, s * 0.15f, s, barrel)
                drawRect(bodyColor.copy(alpha = 0.6f), Offset(cx - s * 0.08f, s * 0.48f), Size(s * 0.16f, s * 0.07f))
                val bristle = Path().apply {
                    moveTo(cx - s * 0.1f, s * 0.55f)
                    cubicTo(cx - s * 0.16f, s * 0.7f, cx - s * 0.05f, s * 0.84f, cx, s * 0.97f)
                    cubicTo(cx + s * 0.05f, s * 0.84f, cx + s * 0.16f, s * 0.7f, cx + s * 0.1f, s * 0.55f)
                    close()
                }
                drawPath(bristle, tint)
            }
            BrushType.PENCIL -> {
                val body = Path().apply {
                    moveTo(cx - s * 0.12f, s * 0.08f)
                    lineTo(cx + s * 0.12f, s * 0.08f)
                    lineTo(cx + s * 0.12f, s * 0.58f)
                    lineTo(cx - s * 0.12f, s * 0.58f)
                    close()
                }
                drawPath(body, tint.copy(alpha = 0.9f))
                drawRect(barrel, Offset(cx - s * 0.12f, s * 0.08f), Size(s * 0.24f, s * 0.07f))
                val wood = Path().apply {
                    moveTo(cx - s * 0.12f, s * 0.58f)
                    lineTo(cx + s * 0.12f, s * 0.58f)
                    lineTo(cx, s * 0.95f)
                    close()
                }
                drawPath(wood, Color(0xFFE2C79D))
                val lead = Path().apply {
                    moveTo(cx - s * 0.04f, s * 0.84f)
                    lineTo(cx + s * 0.04f, s * 0.84f)
                    lineTo(cx, s * 0.95f)
                    close()
                }
                drawPath(lead, tint)
            }
            BrushType.HIGHLIGHTER -> {
                barrelRect(cx, s * 0.06f, s * 0.42f, s * 0.24f, s, barrel)
                val chisel = Path().apply {
                    moveTo(cx - s * 0.12f, s * 0.48f)
                    lineTo(cx + s * 0.12f, s * 0.48f)
                    lineTo(cx + s * 0.12f, s * 0.72f)
                    lineTo(cx - s * 0.04f, s * 0.95f)
                    lineTo(cx - s * 0.12f, s * 0.95f)
                    close()
                }
                drawPath(chisel, tint.copy(alpha = 0.9f))
            }
            BrushType.PROCEDURAL_LINE -> {
                // Dashed stroke running down the pen axis.
                var y = s * 0.1f
                while (y < s * 0.9f) {
                    drawLine(tint, Offset(cx, y), Offset(cx, minOf(y + s * 0.13f, s * 0.92f)), s * 0.07f, StrokeCap.Round)
                    y += s * 0.22f
                }
            }
            BrushType.STUDY_TAPE -> {
                drawRoundRect(tint, Offset(cx - s * 0.2f, s * 0.1f), Size(s * 0.4f, s * 0.8f), CornerRadius(s * 0.05f))
                var y = s * 0.2f
                while (y < s * 0.86f) {
                    drawLine(Color(0x55000000), Offset(cx - s * 0.2f, y + s * 0.1f), Offset(cx + s * 0.2f, y - s * 0.02f), s * 0.03f)
                    y += s * 0.15f
                }
            }
            BrushType.LASER_POINTER -> {
                drawCircle(tint, s * 0.12f, center)
                for (i in 0 until 8) {
                    rotate(i * 45f, center) {
                        drawLine(tint.copy(alpha = 0.7f), Offset(s * 0.5f, s * 0.14f), Offset(s * 0.5f, s * 0.26f), s * 0.04f, StrokeCap.Round)
                    }
                }
            }
            BrushType.ERASER -> rotate(-30f, center) {
                drawRoundRect(bodyColor.copy(alpha = 0.9f), Offset(s * 0.2f, s * 0.34f), Size(s * 0.6f, s * 0.32f), CornerRadius(s * 0.06f))
                drawRect(tint.copy(alpha = 0.9f), Offset(s * 0.2f, s * 0.34f), Size(s * 0.24f, s * 0.32f))
            }
        }
    }
}

/** Upright rounded barrel from [top] down to [bottom] centred on [cx]. */
private fun DrawScope.barrelRect(cx: Float, top: Float, bottom: Float, width: Float, s: Float, color: Color) {
    drawRoundRect(color, Offset(cx - width / 2f, top), Size(width, bottom - top), CornerRadius(s * 0.06f))
}
