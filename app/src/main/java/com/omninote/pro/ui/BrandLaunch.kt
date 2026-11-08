package com.omninote.pro.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.launch
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Navy = Color(0xFF11192B)
private val Blue = Color(0xFF3B82F6)

/**
 * 650 ms brand moment: a fountain-pen nib draws a glowing bezier, the "OmniNote Pro" wordmark fades in under it, then
 * the whole thing crossfades away and [onFinished] is called so the host can show the library.
 */
@Composable
fun BrandLaunch(onFinished: () -> Unit, modifier: Modifier = Modifier) {
    val draw = remember { Animatable(0f) }
    val fade = remember { Animatable(1f) }
    val word = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        // 0..400 ms: draw the stroke; the wordmark rises from 150 ms; 400..650 ms: crossfade out.
        val drawJob = launch { draw.animateTo(1f, tween(400, easing = FastOutSlowInEasing)) }
        val wordJob = launch { kotlinx.coroutines.delay(150); word.animateTo(1f, tween(250)) }
        drawJob.join(); wordJob.join()
        fade.animateTo(0f, tween(250))
        onFinished()
    }
    Box(
        modifier
            .fillMaxSize()
            .graphicsLayer { alpha = fade.value }
            .background(Brush.verticalGradient(listOf(Color(0xFF18233C), Navy))),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width * 0.46f
            val h = size.height * 0.16f
            val x0 = (size.width - w) / 2f
            val y0 = size.height * 0.42f
            val path = Path().apply {
                moveTo(x0, y0 + h * 0.7f)
                cubicTo(x0 + w * 0.25f, y0 - h * 0.9f, x0 + w * 0.45f, y0 + h * 1.6f, x0 + w * 0.7f, y0 + h * 0.3f)
                cubicTo(x0 + w * 0.82f, y0 - h * 0.3f, x0 + w * 0.92f, y0 + h * 0.2f, x0 + w, y0 - h * 0.1f)
            }
            val measure = PathMeasure().apply { setPath(path, false) }
            val len = measure.length
            val seg = Path()
            measure.getSegment(0f, len * draw.value, seg, true)
            // glow, then crisp line
            drawPath(seg, Blue.copy(alpha = 0.22f), style = Stroke(width = 22.dp.toPx(), cap = StrokeCap.Round))
            drawPath(seg, Blue.copy(alpha = 0.45f), style = Stroke(width = 11.dp.toPx(), cap = StrokeCap.Round))
            drawPath(seg, Color.White, style = Stroke(width = 3.5.dp.toPx(), cap = StrokeCap.Round))

            // nib at the head of the stroke, pointing along the travel direction
            val tip = measure.getPosition(len * draw.value)
            val tan = measure.getTangent(len * draw.value)
            val angle = Math.toDegrees(kotlin.math.atan2(tan.y.toDouble(), tan.x.toDouble())).toFloat()
            val s = 26.dp.toPx()
            rotate(angle + 135f, pivot = tip) {
                val nib = Path().apply {
                    moveTo(tip.x, tip.y)
                    lineTo(tip.x - s * 0.32f, tip.y - s * 0.9f)
                    lineTo(tip.x + s * 0.32f, tip.y - s * 0.9f)
                    close()
                }
                drawPath(nib, Color(0xFFE8EEFB))
                drawLine(Navy, Offset(tip.x, tip.y - s * 0.12f), Offset(tip.x, tip.y - s * 0.62f), strokeWidth = 1.6.dp.toPx())
                drawCircle(Navy, 2.2.dp.toPx(), Offset(tip.x, tip.y - s * 0.66f))
                drawRect(Blue, Offset(tip.x - s * 0.3f, tip.y - s * 1.25f), androidx.compose.ui.geometry.Size(s * 0.6f, s * 0.35f))
            }
        }
        Text(
            "OmniNote Pro",
            color = Color.White,
            fontSize = 30.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp,
            modifier = Modifier
                .align(Alignment.Center)
                .graphicsLayer { alpha = word.value; translationY = (1f - word.value) * 24.dp.toPx() + 54.dp.toPx() }
        )
    }
}
