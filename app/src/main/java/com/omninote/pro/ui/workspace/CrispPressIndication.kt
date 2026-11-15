package com.omninote.pro.ui.workspace

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import kotlinx.coroutines.launch

/**
 * The pen dock's press feedback: a flat 10% white wash while a press is active, and nothing otherwise. Unlike the
 * default Material ripple there is no fade tail, no hover (stylus hover included), focus or drag state layer, so
 * nothing is left glowing after a tap. Installed with `LocalIndication provides CrispPressIndication` inside the dock
 * only.
 */
object CrispPressIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = CrispPressNode(interactionSource)

    // A stable singleton: the same factory must compare equal across recompositions so modifiers are not rebuilt.
    override fun equals(other: Any?): Boolean = other === CrispPressIndication
    override fun hashCode(): Int = -1
}

private class CrispPressNode(private val interactionSource: InteractionSource) : Modifier.Node(), DrawModifierNode {
    private val presses = HashSet<PressInteraction.Press>()
    private var pressed = false

    override fun onAttach() {
        coroutineScope.launch {
            interactionSource.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> presses.add(interaction)
                    is PressInteraction.Release -> presses.remove(interaction.press)
                    is PressInteraction.Cancel -> presses.remove(interaction.press)
                    else -> Unit
                }
                val now = presses.isNotEmpty()
                if (now != pressed) {
                    pressed = now
                    invalidateDraw()
                }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        if (pressed) drawRect(Color.White.copy(alpha = 0.10f))
    }
}
