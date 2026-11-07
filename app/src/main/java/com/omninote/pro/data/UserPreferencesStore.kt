package com.omninote.pro.data

import android.content.Context
import com.omninote.pro.core.model.BrushConfig
import com.omninote.pro.core.model.BrushType
import com.omninote.pro.core.model.PencilGrade
import com.omninote.pro.core.model.TemplateConfig
import com.omninote.pro.engine.input.ButtonMapping
import com.omninote.pro.engine.input.PenAction
import com.omninote.pro.engine.input.PenButtonConfig
import com.omninote.pro.engine.input.PenButtonRole
import com.omninote.pro.engine.input.RedmiPenButtonManager
import com.omninote.pro.engine.io.VaultExtras
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

// =========================================================================================
// Model
// =========================================================================================

@Serializable
enum class DockEdge { TOP, BOTTOM, LEFT, RIGHT }

@Serializable
enum class AcademicPaletteId(val label: String) {
    MUJI_VINTAGE("Muji Vintage"),
    MILDLINER_PASTEL("Mildliner Pastel"),
    NEON_DARK("High-Contrast Neon"),
    CLASSIC_INK("Classic Ink")
}

class AcademicPalette(val id: AcademicPaletteId, val colors: List<Int>) {
    val label: String get() = id.label
}

/** The four curated palettes offered by the colour studio. Fixed data, not a user setting. */
object AcademicPalettes {
    val all: List<AcademicPalette> = listOf(
        AcademicPalette(
            AcademicPaletteId.MUJI_VINTAGE,
            listOf(0xFF3E3A36, 0xFF8C4A3A, 0xFFB5793B, 0xFFA89A4E, 0xFF5E7A5A, 0xFF4A6F7C, 0xFF7C6A5D, 0xFF9A8F84).map { it.toInt() }
        ),
        AcademicPalette(
            AcademicPaletteId.MILDLINER_PASTEL,
            listOf(0xFFF2A7A0, 0xFFF7C79A, 0xFFF5E49B, 0xFFB8DDA4, 0xFF9ED6C8, 0xFF9CC8EA, 0xFFC9B4E0, 0xFFD9D4CC).map { it.toInt() }
        ),
        AcademicPalette(
            AcademicPaletteId.NEON_DARK,
            listOf(0xFFFF5C7A, 0xFFFF9E40, 0xFFFFE74C, 0xFF5CFF8F, 0xFF3DF2E0, 0xFF55B8FF, 0xFFFFFFFF, 0xFFB8C0CC).map { it.toInt() }
        ),
        AcademicPalette(
            AcademicPaletteId.CLASSIC_INK,
            listOf(0xFF111111, 0xFF1A3A8F, 0xFFC62828, 0xFF1B8A5A, 0xFF8A5A1B, 0xFF555555, 0xFF0F6E8C, 0xFF6B2D5C).map { it.toInt() }
        )
    )

    fun byId(id: AcademicPaletteId): AcademicPalette = all.first { it.id == id }
}

@Serializable
data class PenButtonConfigDto(
    val primaryClick: String = PenAction.TOGGLE_ERASER.name,
    val primaryHold: String = PenAction.HOLD_TO_ERASE.name,
    val primaryDouble: String = PenAction.CYCLE_PRESET_PEN.name,
    val secondaryClick: String = PenAction.TOGGLE_LASSO.name,
    val secondaryHold: String = PenAction.HOLD_FOR_LASSO.name,
    val secondaryDouble: String = PenAction.NONE.name,
    val holdThresholdMs: Long = 350L,
    val doubleClickWindowMs: Long = 300L
) {
    fun toConfig(): PenButtonConfig {
        fun action(name: String) = PenAction.entries.firstOrNull { it.name == name } ?: PenAction.NONE
        return PenButtonConfig(
            primary = ButtonMapping(action(primaryClick), action(primaryHold), action(primaryDouble)),
            secondary = ButtonMapping(action(secondaryClick), action(secondaryHold), action(secondaryDouble)),
            holdThresholdMs = holdThresholdMs.coerceIn(150L, 1500L),
            doubleClickWindowMs = doubleClickWindowMs.coerceIn(120L, 800L)
        )
    }

    companion object {
        fun of(c: PenButtonConfig) = PenButtonConfigDto(
            c.primary.click.name, c.primary.hold.name, c.primary.doubleClick.name,
            c.secondary.click.name, c.secondary.hold.name, c.secondary.doubleClick.name,
            c.holdThresholdMs, c.doubleClickWindowMs
        )
    }
}

/** Redmi Smart Pen 1S button setup: key code to role, plus what each gesture does. */
@Serializable
data class PenBindingsDto(
    val keyBindings: Map<Int, String> = RedmiPenButtonManager.DEFAULT_BINDINGS.mapValues { it.value.name },
    val config: PenButtonConfigDto = PenButtonConfigDto()
) {
    fun toBindingMap(): Map<Int, PenButtonRole> =
        keyBindings.mapNotNull { (code, role) -> PenButtonRole.entries.firstOrNull { it.name == role }?.let { code to it } }.toMap()
}

@Serializable
data class UserPreferences(
    /** Exactly [SLOT_COUNT] quick-preset pens. */
    val penSlots: List<BrushConfig> = DefaultPenSlots.build(),
    val activePenSlot: Int = 0,
    val activePalette: AcademicPaletteId = AcademicPaletteId.CLASSIC_INK,
    val customSwatches: List<Int> = emptyList(),
    val penBindings: PenBindingsDto = PenBindingsDto(),
    val darkModeInkInversion: Boolean = true,
    /** Strict palm rejection by default: a single finger never pans unless this is on (or the Hand tool is active). */
    val singleFingerPanEnabled: Boolean = false,
    val holdToSnapEnabled: Boolean = true,
    val autoBackupFolderUri: String? = null,
    val defaultTemplate: TemplateConfig = TemplateConfig(),
    val dockEdge: DockEdge = DockEdge.BOTTOM,
    val dockCollapsed: Boolean = false
) {
    companion object {
        const val SLOT_COUNT = 8
        const val MAX_CUSTOM_SWATCHES = 24
    }
}

object DefaultPenSlots {
    /** Eight curated academic defaults, in dock order. */
    fun build(): List<BrushConfig> = listOf(
        BrushConfig.ballpoint(0xFF111111.toInt(), 0.35f),
        BrushConfig.fountain(0xFF0047AB.toInt(), 0.45f),
        BrushConfig.fineliner(0xFFC62828.toInt(), 0.3f),
        BrushConfig.fineliner(0xFF1B8A5A.toInt(), 0.5f),
        BrushConfig.brushPen(0xFFE69500.toInt(), 0.8f),
        BrushConfig.pencil(PencilGrade.B2, 0.7f),
        BrushConfig.highlighter(0xFFFFE066.toInt(), 6f).copy(nibAngleDeg = 45f, nibFlatness = 0.35f),
        BrushConfig.studyTape(0xFF8A5CB8.toInt(), 8f)
    )
}

// =========================================================================================
// Store
// =========================================================================================

/**
 * User settings as one JSON file (`filesDir/settings/user_prefs.json`) mirrored by an in-memory [state].
 *
 * Reads happen once at construction (the file is a few KB). Every [update] changes [state] immediately and queues a
 * write of the newest value; writes run on one IO thread, go to a temp file first and replace the real file by
 * rename, so a crash mid-write leaves the previous settings intact. A file that cannot be parsed is kept as
 * `user_prefs.json.corrupt` and defaults are used.
 */
class UserPreferencesStore(context: Context, private val scope: CoroutineScope) {

    private val dir = File(context.filesDir, "settings")
    private val file = File(dir, FILE_NAME)
    private val writeDispatcher = Dispatchers.IO.limitedParallelism(1)

    private val _state = MutableStateFlow(load())
    val state: StateFlow<UserPreferences> = _state.asStateFlow()

    val value: UserPreferences get() = _state.value

    private var writeJob: Job? = null

    fun update(transform: (UserPreferences) -> UserPreferences) {
        _state.update { normalise(transform(it)) }
        scheduleWrite()
    }

    // ---- pen slots ----

    fun setPenSlot(index: Int, brush: BrushConfig) {
        require(index in 0 until UserPreferences.SLOT_COUNT) { "Slot index out of range" }
        require(brush.type.isInk) { "A pen slot must hold an ink instrument" }
        update { p -> p.copy(penSlots = p.penSlots.toMutableList().also { it[index] = brush }) }
    }

    fun setActivePenSlot(index: Int) {
        update { it.copy(activePenSlot = index.coerceIn(0, UserPreferences.SLOT_COUNT - 1)) }
    }

    fun resetPenSlots() {
        update { it.copy(penSlots = DefaultPenSlots.build(), activePenSlot = 0) }
    }

    // ---- palette ----

    fun setActivePalette(id: AcademicPaletteId) = update { it.copy(activePalette = id) }

    fun addCustomSwatch(argb: Int) = update { p ->
        val opaque = argb or (0xFF shl 24)
        val next = (listOf(opaque) + p.customSwatches.filter { it != opaque }).take(UserPreferences.MAX_CUSTOM_SWATCHES)
        p.copy(customSwatches = next)
    }

    fun removeCustomSwatch(argb: Int) = update { p -> p.copy(customSwatches = p.customSwatches.filter { it != argb }) }

    // ---- toggles ----

    fun setDarkModeInkInversion(on: Boolean) = update { it.copy(darkModeInkInversion = on) }

    fun setSingleFingerPan(on: Boolean) = update { it.copy(singleFingerPanEnabled = on) }

    fun setHoldToSnap(on: Boolean) = update { it.copy(holdToSnapEnabled = on) }

    fun setAutoBackupFolder(uri: String?) = update { it.copy(autoBackupFolderUri = uri) }

    fun setDefaultTemplate(config: TemplateConfig) = update { it.copy(defaultTemplate = config) }

    fun setDock(edge: DockEdge, collapsed: Boolean) = update { it.copy(dockEdge = edge, dockCollapsed = collapsed) }

    // ---- Redmi Smart Pen 1S ----

    fun setPenBindings(bindings: Map<Int, PenButtonRole>, config: PenButtonConfig) {
        update { it.copy(penBindings = PenBindingsDto(bindings.mapValues { e -> e.value.name }, PenButtonConfigDto.of(config))) }
    }

    /**
     * Applies the saved bindings to [manager] and keeps both sides in sync: learning a key on the manager is saved,
     * and a restore from backup is pushed to the manager. Returns the collector job; cancel it with the screen.
     */
    fun attachPenManager(manager: RedmiPenButtonManager, collectScope: CoroutineScope = scope): Job {
        val saved = _state.value.penBindings
        manager.setBindings(saved.toBindingMap())
        manager.config = saved.config.toConfig()
        manager.setDockedPresetCount(UserPreferences.SLOT_COUNT)
        return collectScope.launch {
            launch {
                manager.bindings.collect { map ->
                    if (map != _state.value.penBindings.toBindingMap()) {
                        setPenBindings(map, _state.value.penBindings.config.toConfig())
                    }
                }
            }
            launch {
                _state.map { it.penBindings }.distinctUntilChanged().collect { dto ->
                    val map = dto.toBindingMap()
                    if (map != manager.bindings.value) manager.setBindings(map)
                    manager.config = dto.config.toConfig()
                }
            }
        }
    }

    // ---- vault backup ----

    /** Pen slots and button bindings as the JSON strings stored in `.omnibackup` and handed to [com.omninote.pro.engine.io.OmniArchiveManager]. */
    fun exportVaultExtras(): VaultExtras {
        val p = _state.value
        return VaultExtras(
            penPresetsJson = OmniJson.encodeToString(PRESETS_SERIALIZER, p.penSlots),
            penButtonBindingsJson = OmniJson.encodeToString(PenBindingsDto.serializer(), p.penBindings)
        )
    }

    /** Restores what [exportVaultExtras] saved. Malformed or incomplete parts are ignored, never half-applied. */
    fun applyVaultExtras(extras: VaultExtras) {
        val slots = extras.penPresetsJson?.let {
            runCatching { OmniJson.decodeFromString(PRESETS_SERIALIZER, it) }.getOrNull()
        }?.takeIf { it.size == UserPreferences.SLOT_COUNT && it.all { b -> b.type.isInk } }
        val bindings = extras.penButtonBindingsJson?.let {
            runCatching { OmniJson.decodeFromString(PenBindingsDto.serializer(), it) }.getOrNull()
        }
        if (slots == null && bindings == null) return
        update { p ->
            p.copy(
                penSlots = slots ?: p.penSlots,
                penBindings = bindings ?: p.penBindings
            )
        }
    }

    // =====================================================================================

    private fun load(): UserPreferences {
        if (!file.isFile) return UserPreferences()
        return try {
            normalise(OmniJson.decodeFromString(UserPreferences.serializer(), file.readText(Charsets.UTF_8)))
        } catch (e: Exception) {
            runCatching { file.renameTo(File(dir, "$FILE_NAME.corrupt")) }
            UserPreferences()
        }
    }

    /** Keeps the invariants the UI relies on: exactly eight ink slots and an in-range active slot. */
    private fun normalise(p: UserPreferences): UserPreferences {
        val defaults = DefaultPenSlots.build()
        val slots = List(UserPreferences.SLOT_COUNT) { i ->
            p.penSlots.getOrNull(i)?.takeIf { it.type.isInk && it.type != BrushType.ERASER } ?: defaults[i]
        }
        return p.copy(
            penSlots = slots,
            activePenSlot = p.activePenSlot.coerceIn(0, UserPreferences.SLOT_COUNT - 1),
            customSwatches = p.customSwatches.take(UserPreferences.MAX_CUSTOM_SWATCHES)
        )
    }

    private fun scheduleWrite() {
        writeJob?.cancel()
        writeJob = scope.launch(writeDispatcher) {
            kotlinx.coroutines.delay(WRITE_DEBOUNCE_MS)
            writeNow(_state.value)
        }
    }

    /** Writes immediately; call when the app is going to the background so nothing is lost to the debounce. */
    suspend fun flush() {
        writeJob?.cancel()
        kotlinx.coroutines.withContext(writeDispatcher + kotlinx.coroutines.NonCancellable) { writeNow(_state.value) }
    }

    private fun writeNow(prefs: UserPreferences) {
        dir.mkdirs()
        val temp = File(dir, "$FILE_NAME.tmp")
        temp.writeText(OmniJson.encodeToString(UserPreferences.serializer(), prefs), Charsets.UTF_8)
        try {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (e: IOException) {
            temp.delete()
            throw e
        }
    }

    private companion object {
        const val FILE_NAME = "user_prefs.json"
        const val WRITE_DEBOUNCE_MS = 350L
        val PRESETS_SERIALIZER = kotlinx.serialization.builtins.ListSerializer(BrushConfig.serializer())
    }
}
