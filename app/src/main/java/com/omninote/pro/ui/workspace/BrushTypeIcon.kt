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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.omninote.pro.core.model.BrushType

/**
 * Vector glyph for each instrument, drawn from the shape of its tip so slots stay recognisable at a glance.
 * [tint] colours the ink end (the live colour); the body uses [bodyColor].
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
        when (type) {
            BrushType.FOUNTAIN_PEN -> penBody(s, bodyColor) { tipX, tipY ->
                val p = Path().apply {
                    moveTo(tipX - s * 0.09f, tipY - s * 0.20f)
                    lineTo(tipX + s * 0.09f, tipY - s * 0.20f)
                    lineTo(tipX, tipY + s * 0.04f)
                    close()
                }
                drawPath(p, tint)
                drawLine(bodyColor, Offset(tipX, tipY - s * 0.16f), Offset(tipX, tipY - s * 0.02f), s * 0.025f)
            }
            BrushType.BALLPOINT -> penBody(s, bodyColor) { tipX, tipY ->
                drawLine(bodyColor, Offset(tipX, tipY - s * 0.18f), Offset(tipX, tipY - s * 0.04f), s * 0.07f, StrokeCap.Round)
                drawCircle(tint, s * 0.055f, Offset(tipX, tipY))
            }
            BrushType.FINELINER -> penBody(s, bodyColor) { tipX, tipY ->
                drawLine(tint, Offset(tipX, tipY - s * 0.20f), Offset(tipX, tipY + s * 0.02f), s * 0.035f, StrokeCap.Round)
            }
            BrushType.BRUSH_PEN -> penBody(s, bodyColor) { tipX, tipY ->
                val p = Path().apply {
                    moveTo(tipX - s * 0.08f, tipY - s * 0.22f)
                    cubicTo(tipX - s * 0.12f, tipY - s * 0.08f, tipX - s * 0.03f, tipY, tipX, tipY + s * 0.05f)
                    cubicTo(tipX + s * 0.03f, tipY, tipX + s * 0.12f, tipY - s * 0.08f, tipX + s * 0.08f, tipY - s * 0.22f)
                    close()
                }
                drawPath(p, tint)
            }
            BrushType.PENCIL -> penBody(s, bodyColor) { tipX, tipY ->
                val wood = Path().apply {
                    moveTo(tipX - s * 0.09f, tipY - s * 0.18f)
                    lineTo(tipX + s * 0.09f, tipY - s * 0.18f)
                    lineTo(tipX, tipY + s * 0.04f)
                    close()
                }
                drawPath(wood, Color(0xFFE2C79D))
                val lead = Path().apply {
                    moveTo(tipX - s * 0.035f, tipY - s * 0.05f)
                    lineTo(tipX + s * 0.035f, tipY - s * 0.05f)
                    lineTo(tipX, tipY + s * 0.04f)
                    close()
                }
                drawPath(lead, tint)
            }
            BrushType.HIGHLIGHTER -> penBody(s, bodyColor) { tipX, tipY ->
                val chisel = Path().apply {
                    moveTo(tipX - s * 0.10f, tipY - s * 0.18f)
                    lineTo(tipX + s * 0.10f, tipY - s * 0.18f)
                    lineTo(tipX + s * 0.10f, tipY - s * 0.04f)
                    lineTo(tipX - s * 0.02f, tipY + s * 0.04f)
                    close()
                }
                drawPath(chisel, tint.copy(alpha = 0.85f))
            }
            BrushType.PROCEDURAL_LINE -> {
                val y = s * 0.5f
                val w = s * 0.07f
                drawLine(tint, Offset(s * 0.1f, y), Offset(s * 0.28f, y), w, StrokeCap.Round)
                drawLine(tint, Offset(s * 0.42f, y), Offset(s * 0.6f, y), w, StrokeCap.Round)
                drawCircle(tint, w * 0.7f, Offset(s * 0.76f, y))
                drawCircle(tint, w * 0.7f, Offset(s * 0.9f, y))
            }
            BrushType.STUDY_TAPE -> {
                drawRoundRect(tint, Offset(s * 0.1f, s * 0.32f), Size(s * 0.8f, s * 0.36f), CornerRadius(s * 0.05f))
                var x = s * 0.18f
                while (x < s * 0.86f) {
                    drawLine(Color(0x55000000), Offset(x, s * 0.34f), Offset(x - s * 0.1f, s * 0.66f), s * 0.03f)
                    x += s * 0.14f
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

/** Pen drawn at 45 degrees: a rounded barrel with the tip at the lower left. [tip] draws the nib at (x, y). */
private fun DrawScope.penBody(s: Float, bodyColor: Color, tip: DrawScope.(Float, Float) -> Unit) {
    rotate(45f, Offset(s / 2f, s / 2f)) {
        val bx = s * 0.5f
        drawRoundRect(
            bodyColor,
            Offset(bx - s * 0.1f, s * 0.04f),
            Size(s * 0.2f, s * 0.56f),
            CornerRadius(s * 0.05f),
            style = Stroke(width = s * 0.05f)
        )
        tip(bx, s * 0.78f)
    }
}
