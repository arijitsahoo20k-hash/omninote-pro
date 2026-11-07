package com.omninote.pro.engine.input

import android.view.KeyEvent

/**
 * Lets the Activity hand hardware key events (the Redmi Smart Pen's barrel buttons arrive as PAGE_UP / PAGE_DOWN) to
 * whichever pen-button manager is in charge right now: the focused pane while writing, or the app-level manager on
 * screens without a canvas. Handled before views see the event so the system never turns the keys into page scrolls.
 */
class PenKeyRouter {
    @Volatile
    var target: RedmiPenButtonManager? = null

    /** True when the event was consumed. */
    fun onKeyEvent(event: KeyEvent): Boolean = target?.onKeyEvent(event) == true
}
