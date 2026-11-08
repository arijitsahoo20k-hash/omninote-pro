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

/**
 * Midnight Navy & Slate workspace palette. The object keeps its historical name so every screen that reads `Warm.*`
 * picks the new colours up without edits. No purple or indigo: the accent is a clear blue.
 */
object Warm {
    // Surfaces (named in the design spec)
    val Sidebar = Color(0xFF11192B)
    val Backdrop = Color(0xFF152036)
    val TopBar = Color(0xFF18233C)
    val AccentBlue = Color(0xFF3B82F6)

    val Accent = AccentBlue
    val AccentSoft = Color(0x333B82F6)
    val Glass = Color(0xE6182440)
    val GlassRaised = Color(0xF01F2D4D)
    val GlassEdge = Color(0x33CBD5E1)
    val Text = Color(0xFFE6ECF5)
    val TextMuted = Color(0xFF94A3B8)
    val Danger = Color(0xFFEF6B6B)
    val Ok = Color(0xFF6BCB9B)
    val Warn = Color(0xFFE5B85C)
    val Scrim = Color(0x99080D18)
    val Divider = Color(0x26CBD5E1)
}

@Composable
fun WorkspaceTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Warm.Accent,
            onPrimary = Color(0xFFFFFFFF),
            secondary = Warm.Warn,
            background = Warm.Backdrop,
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
                if (raised) listOf(Warm.GlassRaised, Warm.Glass) else listOf(Warm.Glass, Color(0xE6131C33))
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
            .background(if (selected) Warm.Accent else Color(0x1FCBD5E1), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (selected) Color(0xFFFFFFFF) else Warm.Text,
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
                inactiveTrackColor = Color(0x33CBD5E1),
                disabledThumbColor = Warm.TextMuted,
                disabledActiveTrackColor = Color(0x55CBD5E1)
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
                checkedThumbColor = Color(0xFFFFFFFF),
                checkedTrackColor = Warm.Accent,
                uncheckedThumbColor = Warm.TextMuted,
                uncheckedTrackColor = Color(0x33CBD5E1),
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
