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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.omninote.pro.core.model.BrushType
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Non-brush glyphs of the precision icon pack (brushes are drawn through [BrushTypeIcon]). */
enum class OmniIconKind { LASSO, RULER, STICKERS, TEXT_BOX, PIN }

private val Steel = Color(0xFFC9D1DC)
private val SteelDark = Color(0xFF7D8797)
private val Ink = Color(0xFF2B3340)
private val EraserPink = Color(0xFFF28BA0)
private val EraserWhite = Color(0xFFF5F7FA)
private val WoodTan = Color(0xFFE2C79D)

/**
 * The app's hand-built vector icon pack. Everything is drawn on a unit square so it stays crisp at any size: tint is
 * the live accent / ink colour, [bodyColor] the neutral barrel colour. [angleDeg] tilts the glyph (the slim dock shows
 * the pen and highlighter at an angle, like a pen resting in the hand).
 */
@Composable
fun OmniVectorIcon(
    kind: OmniIconKind,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 28.dp,
    bodyColor: Color = Warm.Text,
    angleDeg: Float = 0f
) {
    Canvas(modifier.size(size)) {
        rotate(angleDeg, center) {
            when (kind) {
                OmniIconKind.LASSO -> drawLassoGlyph(tint, bodyColor)
                OmniIconKind.RULER -> drawRulerGlyph(tint, bodyColor)
                OmniIconKind.STICKERS -> drawStickersGlyph(tint, bodyColor)
                OmniIconKind.TEXT_BOX -> drawTextBoxGlyph(tint, bodyColor)
                OmniIconKind.PIN -> drawPinGlyph(tint, bodyColor)
            }
        }
    }
}

/** Draws the glyph for [type], upright with its tip down, into the current draw scope. */
internal fun DrawScope.drawBrushGlyph(type: BrushType, tint: Color, bodyColor: Color) {
    when (type) {
        BrushType.FOUNTAIN_PEN -> drawLamyFountain(tint, bodyColor)
        BrushType.BALLPOINT -> drawMujiBallpoint(tint, bodyColor)
        BrushType.FINELINER -> drawMicronFineliner(tint, bodyColor)
        BrushType.BRUSH_PEN -> drawTombowBrush(tint, bodyColor)
        BrushType.PENCIL -> drawMonoPencil(tint, bodyColor)
        BrushType.HIGHLIGHTER -> drawMildlinerChisel(tint, bodyColor)
        BrushType.PROCEDURAL_LINE -> drawDashedPen(tint, bodyColor)
        BrushType.STUDY_TAPE -> drawWashiRoll(tint)
        BrushType.LASER_POINTER -> drawLaserBeam(tint, bodyColor)
        BrushType.ERASER -> drawBlockEraser(bodyColor)
    }
}

private fun DrawScope.u(v: Float): Float = v * size.minDimension

private fun DrawScope.pt(x: Float, y: Float): Offset = Offset(u(x), u(y))

private fun DrawScope.rr(color: Color, x0: Float, y0: Float, x1: Float, y1: Float, radius: Float) {
    drawRoundRect(color, pt(x0, y0), Size(u(x1 - x0), u(y1 - y0)), CornerRadius(u(radius)))
}

private fun DrawScope.poly(color: Color, vararg xy: Float) {
    val path = Path()
    path.moveTo(u(xy[0]), u(xy[1]))
    var i = 2
    while (i < xy.size) {
        path.lineTo(u(xy[i]), u(xy[i + 1]))
        i += 2
    }
    path.close()
    drawPath(path, color)
}

// ------------------------------------------------------------------------------------------------
// Pens
// ------------------------------------------------------------------------------------------------

/** Lamy-style fountain pen: ink-coloured ferrule, tapered grip section, steel nib with breather hole and slit. */
private fun DrawScope.drawLamyFountain(tint: Color, body: Color) {
    val barrel = body.copy(alpha = 0.88f)
    rr(barrel, 0.39f, 0.03f, 0.61f, 0.50f, 0.07f)
    rr(body.copy(alpha = 0.55f), 0.60f, 0.07f, 0.645f, 0.36f, 0.02f) // clip
    rr(tint, 0.385f, 0.49f, 0.615f, 0.545f, 0.015f) // ferrule
    poly(barrel, 0.405f, 0.545f, 0.595f, 0.545f, 0.545f, 0.70f, 0.455f, 0.70f) // grip section
    val nib = Path().apply {
        moveTo(u(0.455f), u(0.69f))
        lineTo(u(0.545f), u(0.69f))
        cubicTo(u(0.58f), u(0.78f), u(0.545f), u(0.90f), u(0.50f), u(0.975f))
        cubicTo(u(0.455f), u(0.90f), u(0.42f), u(0.78f), u(0.455f), u(0.69f))
        close()
    }
    drawPath(nib, Steel)
    drawCircle(Ink, u(0.024f), pt(0.5f, 0.785f)) // breather hole
    drawLine(Ink, pt(0.5f, 0.81f), pt(0.5f, 0.965f), u(0.016f), StrokeCap.Round) // slit
    drawCircle(tint, u(0.022f), pt(0.5f, 0.968f)) // ink at the tip
}

/** Muji-style ballpoint: clear barrel with a visible ink refill, clicker, steel cone and ball. */
private fun DrawScope.drawMujiBallpoint(tint: Color, body: Color) {
    rr(body.copy(alpha = 0.35f), 0.43f, 0.05f, 0.57f, 0.56f, 0.05f) // translucent barrel
    rr(body.copy(alpha = 0.8f), 0.44f, 0.015f, 0.56f, 0.10f, 0.03f) // clicker
    rr(tint.copy(alpha = 0.95f), 0.485f, 0.14f, 0.515f, 0.54f, 0.012f) // refill
    poly(SteelDark, 0.44f, 0.56f, 0.56f, 0.56f, 0.528f, 0.84f, 0.472f, 0.84f)
    poly(Steel, 0.472f, 0.84f, 0.528f, 0.84f, 0.5f, 0.925f)
    drawCircle(tint, u(0.024f), pt(0.5f, 0.945f))
}

/** Pigma Micron-style fineliner: colour-banded barrel, three-step metal collar and a needle tip. */
private fun DrawScope.drawMicronFineliner(tint: Color, body: Color) {
    rr(body.copy(alpha = 0.88f), 0.42f, 0.04f, 0.58f, 0.52f, 0.05f)
    rr(tint, 0.42f, 0.20f, 0.58f, 0.31f, 0.0f) // colour band
    rr(body.copy(alpha = 0.7f), 0.445f, 0.52f, 0.555f, 0.62f, 0.01f) // step 1
    rr(Steel, 0.465f, 0.62f, 0.535f, 0.72f, 0.01f) // step 2
    rr(SteelDark, 0.485f, 0.72f, 0.515f, 0.82f, 0.008f) // step 3
    drawLine(tint, pt(0.5f, 0.82f), pt(0.5f, 0.955f), u(0.024f), StrokeCap.Round)
}

/** Tombow-style brush pen: long barrel, steel ferrule, tapering ink-coloured bristles with a highlight. */
private fun DrawScope.drawTombowBrush(tint: Color, body: Color) {
    val barrel = Path().apply {
        moveTo(u(0.42f), u(0.03f))
        lineTo(u(0.58f), u(0.03f))
        lineTo(u(0.595f), u(0.40f))
        lineTo(u(0.555f), u(0.52f))
        lineTo(u(0.445f), u(0.52f))
        lineTo(u(0.405f), u(0.40f))
        close()
    }
    drawPath(barrel, body.copy(alpha = 0.88f))
    rr(Steel, 0.445f, 0.515f, 0.555f, 0.575f, 0.01f)
    val bristle = Path().apply {
        moveTo(u(0.46f), u(0.575f))
        cubicTo(u(0.425f), u(0.70f), u(0.47f), u(0.86f), u(0.50f), u(0.985f))
        cubicTo(u(0.53f), u(0.86f), u(0.575f), u(0.70f), u(0.54f), u(0.575f))
        close()
    }
    drawPath(bristle, tint)
    drawLine(Color.White.copy(alpha = 0.35f), pt(0.485f, 0.62f), pt(0.495f, 0.85f), u(0.012f), StrokeCap.Round)
}

/** Hexagonal Mono-style pencil seen from the side: three facets, pink eraser, steel ferrule, sharpened wood. */
private fun DrawScope.drawMonoPencil(tint: Color, body: Color) {
    val facetL = tint.copy(alpha = 0.70f)
    val facetC = tint.copy(alpha = 0.95f)
    val facetR = tint.copy(alpha = 0.50f)
    rr(EraserPink, 0.37f, 0.015f, 0.63f, 0.09f, 0.02f)
    rr(Steel, 0.37f, 0.09f, 0.63f, 0.14f, 0.0f)
    drawRect(facetL, pt(0.37f, 0.14f), Size(u(0.065f), u(0.46f)))
    drawRect(facetC, pt(0.435f, 0.14f), Size(u(0.13f), u(0.46f)))
    drawRect(facetR, pt(0.565f, 0.14f), Size(u(0.065f), u(0.46f)))
    drawLine(body.copy(alpha = 0.55f), pt(0.5f, 0.24f), pt(0.5f, 0.46f), u(0.014f), StrokeCap.Round)
    poly(WoodTan, 0.37f, 0.60f, 0.63f, 0.60f, 0.5f, 0.90f)
    poly(WoodTan.copy(alpha = 0.6f), 0.37f, 0.60f, 0.435f, 0.60f, 0.5f, 0.90f)
    poly(tint, 0.465f, 0.82f, 0.535f, 0.82f, 0.5f, 0.935f)
}

/** Mildliner-style chisel highlighter: translucent coloured cap band over a pale barrel, wedge tip. */
private fun DrawScope.drawMildlinerChisel(tint: Color, body: Color) {
    rr(body.copy(alpha = 0.85f), 0.37f, 0.03f, 0.63f, 0.50f, 0.05f)
    rr(tint.copy(alpha = 0.55f), 0.37f, 0.03f, 0.63f, 0.20f, 0.05f) // translucent cap band
    rr(body.copy(alpha = 0.55f), 0.40f, 0.49f, 0.60f, 0.56f, 0.01f) // neck
    poly(tint.copy(alpha = 0.92f), 0.40f, 0.56f, 0.60f, 0.56f, 0.60f, 0.74f, 0.51f, 0.95f, 0.40f, 0.95f)
}

/** Dashed pen: a short pen tip over a dashed stroke. */
private fun DrawScope.drawDashedPen(tint: Color, body: Color) {
    rr(body.copy(alpha = 0.88f), 0.44f, 0.03f, 0.56f, 0.30f, 0.04f)
    poly(body.copy(alpha = 0.7f), 0.455f, 0.30f, 0.545f, 0.30f, 0.51f, 0.40f, 0.49f, 0.40f)
    var y = 0.46f
    while (y < 0.94f) {
        drawLine(tint, pt(0.5f, y), pt(0.5f, minOf(y + 0.10f, 0.95f)), u(0.06f), StrokeCap.Round)
        y += 0.17f
    }
}

/** Washi tape roll: ink-coloured roll with a hub, and a striped strip unrolling to the right. */
private fun DrawScope.drawWashiRoll(tint: Color) {
    poly(tint.copy(alpha = 0.85f), 0.40f, 0.70f, 0.94f, 0.70f, 0.94f, 0.84f, 0.40f, 0.84f)
    var x = 0.50f
    while (x < 0.92f) {
        drawLine(Color.White.copy(alpha = 0.40f), pt(x, 0.84f), pt(x + 0.07f, 0.70f), u(0.03f))
        x += 0.10f
    }
    drawCircle(tint, u(0.33f), pt(0.40f, 0.44f))
    drawCircle(Color.White.copy(alpha = 0.22f), u(0.33f), pt(0.40f, 0.44f), style = Stroke(u(0.025f)))
    drawCircle(Color.White.copy(alpha = 0.30f), u(0.20f), pt(0.40f, 0.44f), style = Stroke(u(0.02f)))
    drawCircle(Ink.copy(alpha = 0.75f), u(0.11f), pt(0.40f, 0.44f))
}

/** Laser pointer: a small pen-shaped emitter and a glowing dot with radiating rays. */
private fun DrawScope.drawLaserBeam(tint: Color, body: Color) {
    rotate(-45f, pt(0.24f, 0.24f)) {
        rr(body.copy(alpha = 0.88f), 0.17f, 0.06f, 0.31f, 0.36f, 0.05f)
        rr(tint, 0.17f, 0.30f, 0.31f, 0.36f, 0.02f)
    }
    drawLine(tint.copy(alpha = 0.55f), pt(0.30f, 0.34f), pt(0.64f, 0.68f), u(0.025f), StrokeCap.Round)
    val c = pt(0.70f, 0.74f)
    drawCircle(tint.copy(alpha = 0.18f), u(0.24f), c)
    drawCircle(tint.copy(alpha = 0.40f), u(0.15f), c)
    drawCircle(tint, u(0.075f), c)
    for (i in 0 until 8) {
        val a = i * PI.toFloat() / 4f
        drawLine(
            tint.copy(alpha = 0.75f),
            Offset(c.x + cos(a) * u(0.17f), c.y + sin(a) * u(0.17f)),
            Offset(c.x + cos(a) * u(0.25f), c.y + sin(a) * u(0.25f)),
            u(0.03f), StrokeCap.Round
        )
    }
}

/** Block eraser with a paper sleeve: pink block, white sleeve with a ink-neutral band. */
private fun DrawScope.drawBlockEraser(body: Color) {
    rotate(-30f, center) {
        rr(EraserPink, 0.16f, 0.34f, 0.84f, 0.66f, 0.06f)
        rr(EraserWhite, 0.42f, 0.34f, 0.84f, 0.66f, 0.06f) // sleeve
        drawRect(EraserWhite, pt(0.42f, 0.34f), Size(u(0.10f), u(0.32f)))
        drawLine(SteelDark.copy(alpha = 0.55f), pt(0.42f, 0.34f), pt(0.42f, 0.66f), u(0.014f))
        drawLine(body.copy(alpha = 0.45f), pt(0.50f, 0.42f), pt(0.78f, 0.42f), u(0.016f), StrokeCap.Round)
        drawLine(body.copy(alpha = 0.45f), pt(0.50f, 0.58f), pt(0.70f, 0.58f), u(0.016f), StrokeCap.Round)
    }
}

// ------------------------------------------------------------------------------------------------
// Tools
// ------------------------------------------------------------------------------------------------

/** Lasso: a dotted loop with a short tail and a knot. */
private fun DrawScope.drawLassoGlyph(tint: Color, body: Color) {
    val dash = PathEffect.dashPathEffect(floatArrayOf(u(0.07f), u(0.07f)), 0f)
    drawOval(
        tint,
        topLeft = pt(0.14f, 0.16f),
        size = Size(u(0.72f), u(0.50f)),
        style = Stroke(width = u(0.065f), cap = StrokeCap.Round, pathEffect = dash)
    )
    val tail = Path().apply {
        moveTo(u(0.34f), u(0.64f))
        cubicTo(u(0.36f), u(0.78f), u(0.30f), u(0.84f), u(0.40f), u(0.92f))
    }
    drawPath(tail, body.copy(alpha = 0.9f), style = Stroke(width = u(0.05f), cap = StrokeCap.Round))
    drawCircle(body.copy(alpha = 0.9f), u(0.05f), pt(0.41f, 0.93f))
}

/** Ruler: tilted bar with major and minor ticks. */
private fun DrawScope.drawRulerGlyph(tint: Color, body: Color) {
    rotate(-35f, center) {
        rr(tint.copy(alpha = 0.9f), 0.06f, 0.36f, 0.94f, 0.64f, 0.05f)
        var i = 0
        var x = 0.12f
        while (x <= 0.90f) {
            val major = i % 2 == 0
            drawLine(
                Color(0xFF0B1220).copy(alpha = 0.75f),
                pt(x, 0.36f), pt(x, if (major) 0.52f else 0.45f),
                u(if (major) 0.022f else 0.015f)
            )
            x += 0.08f
            i++
        }
    }
    @Suppress("UNUSED_VARIABLE") val unused = body
}

/** Stickers / shapes: a 2x2 grid with a circle, a square, a triangle and a star. */
private fun DrawScope.drawStickersGlyph(tint: Color, body: Color) {
    val c = body.copy(alpha = 0.92f)
    drawCircle(c, u(0.17f), pt(0.28f, 0.28f))
    rr(tint, 0.55f, 0.11f, 0.89f, 0.45f, 0.05f)
    poly(tint, 0.28f, 0.55f, 0.45f, 0.89f, 0.11f, 0.89f)
    val star = Path()
    val cx = u(0.72f)
    val cy = u(0.73f)
    for (k in 0 until 10) {
        val r = if (k % 2 == 0) u(0.19f) else u(0.08f)
        val a = (-PI / 2.0 + k * PI / 5.0).toFloat()
        val x = cx + cos(a) * r
        val y = cy + sin(a) * r
        if (k == 0) star.moveTo(x, y) else star.lineTo(x, y)
    }
    star.close()
    drawPath(star, c)
}

/** Text box: dashed frame with a bold capital T. */
private fun DrawScope.drawTextBoxGlyph(tint: Color, body: Color) {
    val dash = PathEffect.dashPathEffect(floatArrayOf(u(0.06f), u(0.05f)), 0f)
    drawRoundRect(
        tint.copy(alpha = 0.8f),
        topLeft = pt(0.10f, 0.14f),
        size = Size(u(0.80f), u(0.72f)),
        cornerRadius = CornerRadius(u(0.06f)),
        style = Stroke(width = u(0.04f), pathEffect = dash)
    )
    val c = body.copy(alpha = 0.95f)
    drawLine(c, pt(0.30f, 0.32f), pt(0.70f, 0.32f), u(0.075f), StrokeCap.Round)
    drawLine(c, pt(0.50f, 0.32f), pt(0.50f, 0.72f), u(0.075f), StrokeCap.Round)
}

/** Audio pin: a map-pin teardrop with a small speaker wave. */
private fun DrawScope.drawPinGlyph(tint: Color, body: Color) {
    val drop = Path().apply {
        moveTo(u(0.5f), u(0.95f))
        cubicTo(u(0.12f), u(0.55f), u(0.18f), u(0.08f), u(0.5f), u(0.08f))
        cubicTo(u(0.82f), u(0.08f), u(0.88f), u(0.55f), u(0.5f), u(0.95f))
        close()
    }
    drawPath(drop, tint)
    drawCircle(body.copy(alpha = 0.95f), u(0.15f), pt(0.5f, 0.42f))
}
