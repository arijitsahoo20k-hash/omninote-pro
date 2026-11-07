package com.omninote.pro.engine.input

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** Physical barrel buttons. Whatever the firmware calls them, every source maps onto one of these two roles. */
enum class PenButtonRole { PRIMARY, SECONDARY }

enum class PenToolKind { PEN, ERASER, LASSO, STUDY_TAPE }

/**
 * Tool selection owned by the button manager.
 * [dockedIndex] indexes the app's docked preset pens (top 3) and survives switching to other tools,
 * so returning to PEN restores the same preset.
 */
data class PenToolState(
    val kind: PenToolKind = PenToolKind.PEN,
    val dockedIndex: Int = 0
)

enum class PenAction(val toolKind: PenToolKind?) {
    NONE(null),

    /** Click flips eraser on/off. In the hold slot it acts as hold-to-erase. */
    TOGGLE_ERASER(PenToolKind.ERASER),

    /** Hold-to-erase: eraser while held, previous pen on release. */
    HOLD_TO_ERASE(PenToolKind.ERASER),
    TOGGLE_LASSO(PenToolKind.LASSO),
    HOLD_FOR_LASSO(PenToolKind.LASSO),
    TOGGLE_STUDY_TAPE(PenToolKind.STUDY_TAPE),
    HOLD_FOR_STUDY_TAPE(PenToolKind.STUDY_TAPE),

    /** Advance to the next of the docked preset pens (wraps). */
    CYCLE_PRESET_PEN(null)
}

data class ButtonMapping(
    val click: PenAction = PenAction.NONE,
    val hold: PenAction = PenAction.NONE,
    val doubleClick: PenAction = PenAction.NONE
)

data class PenButtonConfig(
    val primary: ButtonMapping = ButtonMapping(
        click = PenAction.TOGGLE_ERASER,
        hold = PenAction.HOLD_TO_ERASE,
        doubleClick = PenAction.CYCLE_PRESET_PEN
    ),
    val secondary: ButtonMapping = ButtonMapping(
        click = PenAction.TOGGLE_LASSO,
        hold = PenAction.HOLD_FOR_LASSO
    ),
    val holdThresholdMs: Long = 350L,
    val doubleClickWindowMs: Long = 300L
) {
    /** Secondary button drives Lasso or Study Tape (click toggles, hold is momentary). */
    fun withSecondaryTool(kind: PenToolKind): PenButtonConfig = copy(
        secondary = when (kind) {
            PenToolKind.LASSO -> ButtonMapping(PenAction.TOGGLE_LASSO, PenAction.HOLD_FOR_LASSO)
            PenToolKind.STUDY_TAPE -> ButtonMapping(PenAction.TOGGLE_STUDY_TAPE, PenAction.HOLD_FOR_STUDY_TAPE)
            PenToolKind.ERASER -> ButtonMapping(PenAction.TOGGLE_ERASER, PenAction.HOLD_TO_ERASE)
            PenToolKind.PEN -> ButtonMapping()
        }
    )
}

enum class PenGesture { CLICK, HOLD_START, HOLD_END, DOUBLE_CLICK }

/** For UI feedback (toasts, toolbar pulse). */
data class PenButtonEvent(val role: PenButtonRole, val gesture: PenGesture, val action: PenAction)

sealed interface KeycodeLearnState {
    data object Idle : KeycodeLearnState

    /** Waiting for the user to press the barrel button that should become [role]. */
    data class Listening(val role: PenButtonRole) : KeycodeLearnState

    data class Captured(val role: PenButtonRole, val keyCode: Int) : KeycodeLearnState {
        val keyName: String get() = KeyEvent.keyCodeToString(keyCode)
    }
}

/**
 * Turns barrel-button input into tool changes. Two input sources feed one state machine per button role:
 *
 *  1. Standard Android stylus buttons: BUTTON_STYLUS_PRIMARY / SECONDARY in MotionEvent.buttonState during hover and touch.
 *  2. Bluetooth HID key events from Xiaomi HyperOS (PAGE_UP, PAGE_DOWN, F1..F12, ESCAPE, or any keycode learned
 *     through [startLearning]).
 *
 * Gestures per role: click, hold (momentary, reverts on release), double-click. Click actions fire immediately
 * with no wait for a possible second click; a double-click first rewinds the first click's effect, then runs
 * its own action, so nothing lags.
 *
 * Host wiring (UI thread only):
 *  - every stylus MotionEvent (touch, hover, generic motion) -> [onMotionEvent]
 *  - Activity.dispatchKeyEvent -> [onKeyEvent]; return true when it returns true
 *  - lifecycle stop / view detach -> [releaseAll]
 *
 * Default HID bindings are PAGE_DOWN -> PRIMARY and PAGE_UP -> SECONDARY. That is a best guess at common pen HID
 * profiles, not a verified Redmi Smart Pen 1S mapping; [startLearning] replaces it with whatever the pen really sends.
 */
class RedmiPenButtonManager(
    config: PenButtonConfig = PenButtonConfig(),
    dockedPresetCount: Int = 3
) {
    @Volatile
    var config: PenButtonConfig = config

    private var dockedCount = dockedPresetCount.coerceAtLeast(0)

    private val _state = MutableStateFlow(PenToolState())
    val state: StateFlow<PenToolState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<PenButtonEvent>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<PenButtonEvent> = _events.asSharedFlow()

    private val _bindings = MutableStateFlow(DEFAULT_BINDINGS)

    /** keyCode -> role. Persist this and restore it with [setBindings]. */
    val bindings: StateFlow<Map<Int, PenButtonRole>> = _bindings.asStateFlow()

    private val _learnState = MutableStateFlow<KeycodeLearnState>(KeycodeLearnState.Idle)
    val learnState: StateFlow<KeycodeLearnState> = _learnState.asStateFlow()

    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private val roles = Array(PenButtonRole.entries.size) { RoleState(PenButtonRole.entries[it]) }
    private var toggleReturn = PenToolState()

    private val learnTimeoutRunnable = Runnable {
        if (_learnState.value is KeycodeLearnState.Listening) _learnState.value = KeycodeLearnState.Idle
    }

    private inner class RoleState(val role: PenButtonRole) {
        var pressed = false
        var motionPressed = false
        var holdFired = false
        var holdRestore: PenToolState? = null
        var lastClickReleaseMs = NO_TIME
        var preClickState: PenToolState? = null
        val holdRunnable = Runnable { onHoldTimeout(this) }
    }

    // =====================================================================================
    // Tool state API (keeps the toolbar and the barrel buttons in sync)
    // =====================================================================================

    fun setToolState(newState: PenToolState) {
        _state.value = newState.copy(dockedIndex = clampIndex(newState.dockedIndex))
    }

    fun setDockedPresetCount(count: Int) {
        dockedCount = count.coerceAtLeast(0)
        _state.value = _state.value.copy(dockedIndex = clampIndex(_state.value.dockedIndex))
    }

    // =====================================================================================
    // Input sources
    // =====================================================================================

    /** Feed every stylus MotionEvent. Non-stylus events are ignored. */
    fun onMotionEvent(event: MotionEvent) {
        var hasStylus = false
        for (i in 0 until event.pointerCount) {
            if (event.getToolType(i) == MotionEvent.TOOL_TYPE_STYLUS) {
                hasStylus = true
                break
            }
        }
        if (!hasStylus) return

        val action = event.actionMasked
        val leaving = action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_HOVER_EXIT
        val bits = if (leaving) 0 else event.buttonState
        syncMotionButton(
            roles[PenButtonRole.PRIMARY.ordinal],
            bits and MotionEvent.BUTTON_STYLUS_PRIMARY != 0,
            event.eventTime
        )
        syncMotionButton(
            roles[PenButtonRole.SECONDARY.ordinal],
            bits and MotionEvent.BUTTON_STYLUS_SECONDARY != 0,
            event.eventTime
        )
    }

    /** @return true if the event was consumed (bound pen key, or captured in learn mode). */
    fun onKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        val learn = _learnState.value
        if (learn is KeycodeLearnState.Listening) {
            if (code in LEARN_BLOCKED_KEYCODES) return false
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) bindLearned(learn.role, code)
            return true
        }

        val role = _bindings.value[code] ?: return false
        val rs = roles[role.ordinal]
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) onButtonDown(rs, event.eventTime)
            KeyEvent.ACTION_UP -> onButtonUp(rs, event.eventTime)
        }
        return true
    }

    /** Release any held button (hold-to-erase reverts). Call on focus loss, stylus loss or detach. */
    fun releaseAll() {
        val now = SystemClock.uptimeMillis()
        for (rs in roles) {
            rs.motionPressed = false
            onButtonUp(rs, now)
        }
    }

    // =====================================================================================
    // Keycode learn mode
    // =====================================================================================

    /** Next non-system key pressed is bound to [role], replacing that role's previous keys. Auto-cancels after 15s. */
    fun startLearning(role: PenButtonRole) {
        releaseAll()
        handler.removeCallbacks(learnTimeoutRunnable)
        _learnState.value = KeycodeLearnState.Listening(role)
        handler.postDelayed(learnTimeoutRunnable, LEARN_TIMEOUT_MS)
    }

    fun cancelLearning() {
        handler.removeCallbacks(learnTimeoutRunnable)
        _learnState.value = KeycodeLearnState.Idle
    }

    /** Call after the UI has shown the captured key. */
    fun acknowledgeLearned() {
        if (_learnState.value is KeycodeLearnState.Captured) _learnState.value = KeycodeLearnState.Idle
    }

    fun setBindings(map: Map<Int, PenButtonRole>) {
        _bindings.value = map.filterKeys { it !in LEARN_BLOCKED_KEYCODES }
    }

    fun clearBinding(role: PenButtonRole) {
        _bindings.value = _bindings.value.filterValues { it != role }
    }

    fun resetBindingsToDefault() {
        _bindings.value = DEFAULT_BINDINGS
    }

    private fun bindLearned(role: PenButtonRole, keyCode: Int) {
        handler.removeCallbacks(learnTimeoutRunnable)
        _bindings.value = _bindings.value.filterValues { it != role } + (keyCode to role)
        _learnState.value = KeycodeLearnState.Captured(role, keyCode)
    }

    // =====================================================================================
    // Per-role gesture state machine
    // =====================================================================================

    private fun syncMotionButton(rs: RoleState, pressedNow: Boolean, time: Long) {
        if (pressedNow == rs.motionPressed) return
        rs.motionPressed = pressedNow
        if (pressedNow) onButtonDown(rs, time) else onButtonUp(rs, time)
    }

    private fun mappingFor(role: PenButtonRole): ButtonMapping =
        if (role == PenButtonRole.PRIMARY) config.primary else config.secondary

    private fun onButtonDown(rs: RoleState, @Suppress("UNUSED_PARAMETER") time: Long) {
        if (rs.pressed) return
        rs.pressed = true
        rs.holdFired = false
        if (mappingFor(rs.role).hold != PenAction.NONE) {
            handler.postDelayed(rs.holdRunnable, config.holdThresholdMs)
        }
    }

    private fun onHoldTimeout(rs: RoleState) {
        if (!rs.pressed || rs.holdFired) return
        val action = mappingFor(rs.role).hold
        if (action == PenAction.NONE) return
        rs.holdFired = true

        val kind = action.toolKind
        if (kind != null) {
            val current = _state.value
            rs.holdRestore = if (current.kind != kind) current else null
            _state.value = current.copy(kind = kind)
        } else {
            rs.holdRestore = null
            perform(action)
        }
        _events.tryEmit(PenButtonEvent(rs.role, PenGesture.HOLD_START, action))
    }

    private fun onButtonUp(rs: RoleState, time: Long) {
        if (!rs.pressed) return
        rs.pressed = false
        handler.removeCallbacks(rs.holdRunnable)

        if (rs.holdFired) {
            rs.holdFired = false
            val restore = rs.holdRestore
            rs.holdRestore = null
            if (restore != null) _state.value = restore
            _events.tryEmit(PenButtonEvent(rs.role, PenGesture.HOLD_END, mappingFor(rs.role).hold))
            return
        }
        handleClick(rs, time)
    }

    private fun handleClick(rs: RoleState, now: Long) {
        val mapping = mappingFor(rs.role)
        val doubleAction = mapping.doubleClick
        val previous = rs.lastClickReleaseMs
        val isDouble = doubleAction != PenAction.NONE &&
            previous != NO_TIME &&
            now - previous <= config.doubleClickWindowMs

        if (isDouble) {
            rs.lastClickReleaseMs = NO_TIME
            val before = rs.preClickState
            rs.preClickState = null
            if (before != null) _state.value = before
            perform(doubleAction)
            _events.tryEmit(PenButtonEvent(rs.role, PenGesture.DOUBLE_CLICK, doubleAction))
        } else {
            rs.preClickState = _state.value
            rs.lastClickReleaseMs = if (doubleAction != PenAction.NONE) now else NO_TIME
            perform(mapping.click)
            _events.tryEmit(PenButtonEvent(rs.role, PenGesture.CLICK, mapping.click))
        }
    }

    private fun perform(action: PenAction) {
        val kind = action.toolKind
        when {
            kind != null -> toggle(kind)
            action == PenAction.CYCLE_PRESET_PEN -> cyclePreset()
        }
    }

    private fun toggle(kind: PenToolKind) {
        val current = _state.value
        if (current.kind == kind) {
            val back = if (toggleReturn.kind == kind) PenToolKind.PEN else toggleReturn.kind
            _state.value = PenToolState(back, clampIndex(current.dockedIndex))
        } else {
            toggleReturn = current
            _state.value = current.copy(kind = kind)
        }
    }

    private fun cyclePreset() {
        if (dockedCount <= 0) return
        val current = _state.value
        _state.value = PenToolState(PenToolKind.PEN, (current.dockedIndex + 1) % dockedCount)
    }

    private fun clampIndex(index: Int): Int =
        if (dockedCount <= 0) 0 else index.coerceIn(0, dockedCount - 1)

    companion object {
        /** HID keycodes pens and clickers are known to emit; handy for UI hints and tests. */
        val KNOWN_PEN_KEYCODES: Set<Int> = buildSet {
            add(KeyEvent.KEYCODE_PAGE_UP)
            add(KeyEvent.KEYCODE_PAGE_DOWN)
            add(KeyEvent.KEYCODE_ESCAPE)
            for (code in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12) add(code)
        }

        val DEFAULT_BINDINGS: Map<Int, PenButtonRole> = mapOf(
            KeyEvent.KEYCODE_PAGE_DOWN to PenButtonRole.PRIMARY,
            KeyEvent.KEYCODE_PAGE_UP to PenButtonRole.SECONDARY
        )

        /** Keys the system must keep: never captured or bound. */
        private val LEARN_BLOCKED_KEYCODES: Set<Int> = setOf(
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_HOME,
            KeyEvent.KEYCODE_APP_SWITCH,
            KeyEvent.KEYCODE_POWER,
            KeyEvent.KEYCODE_SLEEP,
            KeyEvent.KEYCODE_WAKEUP,
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_MUTE,
            KeyEvent.KEYCODE_CALL,
            KeyEvent.KEYCODE_ENDCALL
        )

        private const val NO_TIME = Long.MIN_VALUE
        private const val LEARN_TIMEOUT_MS = 15_000L
    }
}
