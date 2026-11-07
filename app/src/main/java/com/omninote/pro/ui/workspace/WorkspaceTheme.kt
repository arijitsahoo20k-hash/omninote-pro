package com.omninote.pro.ui.workspace

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Warm, muted workspace palette. Deliberately no purple or indigo anywhere in the chrome. */
object Warm {
    val Accent = Color(0xFFD9845B)
    val AccentSoft = Color(0x33D9845B)
    val Glass = Color(0xE6262120)
    val GlassRaised = Color(0xF03A332F)
    val GlassEdge = Color(0x33FFF1DC)
    val Text = Color(0xFFF4EBDD)
    val TextMuted = Color(0xFFB9AC9A)
    val Danger = Color(0xFFD9695B)
    val Ok = Color(0xFF8FB58A)
    val Warn = Color(0xFFE0B35C)
    val Scrim = Color(0x99120F0D)
    val Divider = Color(0x26FFF1DC)
}

@Composable
fun WorkspaceTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Warm.Accent,
            onPrimary = Color(0xFF2A1A10),
            secondary = Warm.Warn,
            background = Color(0xFF1B1716),
            surface = Warm.Glass,
            onSurface = Warm.Text,
            onBackground = Warm.Text,
            error = Warm.Danger
        ),
        content = content
    )
}

/** Translucent warm panel with a hairline edge: the app's glassmorphism surface. */
fun Modifier.glass(shape: Shape = RoundedCornerShape(20.dp), raised: Boolean = false, elevation: Dp = 10.dp): Modifier =
    this
        .shadow(elevation, shape, clip = false, ambientColor = Color.Black, spotColor = Color.Black)
        .clip(shape)
        .background(
            Brush.verticalGradient(
                if (raised) listOf(Warm.GlassRaised, Warm.Glass) else listOf(Warm.Glass, Color(0xE61F1B1A))
            ),
            shape
        )
        .border(BorderStroke(1.dp, Warm.GlassEdge), shape)

@Composable
fun ToolButton(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    enabled: Boolean = true,
    content: @Composable () -> Unit
) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier
            .size(size)
            .clip(shape)
            .background(if (selected) Warm.AccentSoft else Color.Transparent, shape)
            .border(BorderStroke(1.dp, if (selected) Warm.Accent else Color.Transparent), shape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) { content() }
}

@Composable
fun ChipButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier
            .heightIn(min = 36.dp)
            .clip(shape)
            .background(if (selected) Warm.Accent else Color(0x1FFFF1DC), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (selected) Color(0xFF2A1A10) else Warm.Text,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

@Composable
fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    valueText: String,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onFinished: (() -> Unit)? = null,
    enabled: Boolean = true
) {
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = Warm.TextMuted, fontSize = 12.sp)
            Text(valueText, color = Warm.Text, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            onValueChangeFinished = onFinished,
            valueRange = range,
            enabled = enabled,
            colors = SliderDefaults.colors(
                thumbColor = Warm.Accent,
                activeTrackColor = Warm.Accent,
                inactiveTrackColor = Color(0x33FFF1DC),
                disabledThumbColor = Warm.TextMuted,
                disabledActiveTrackColor = Color(0x55FFF1DC)
            )
        )
    }
}

@Composable
fun LabeledSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 40.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = Warm.Text, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color(0xFF2A1A10),
                checkedTrackColor = Warm.Accent,
                uncheckedThumbColor = Warm.TextMuted,
                uncheckedTrackColor = Color(0x33FFF1DC),
                uncheckedBorderColor = Color.Transparent
            )
        )
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        color = Warm.TextMuted,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.sp,
        modifier = modifier.padding(top = 10.dp, bottom = 4.dp)
    )
}

@Composable
fun ColorDot(argb: Int, size: Dp, modifier: Modifier = Modifier, ring: Boolean = false) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(Color(argb), CircleShape)
            .border(BorderStroke(if (ring) 2.dp else 1.dp, if (ring) Warm.Text else Warm.GlassEdge), CircleShape)
    )
}
