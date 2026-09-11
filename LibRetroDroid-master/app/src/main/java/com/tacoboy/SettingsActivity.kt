package com.tacoboy

import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.TooltipCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.android.libretrodroid.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Granularity of the on-screen control size slider -- 5% steps across the range
 *  TacoBoyPrefs allows, so the value lands on numbers a user can read back. */
private const val SCALE_SLIDER_STEP = 0.05f

/**
 * Public source for this build. GPL-3 (LibretroDroid, and therefore TacoBoy) entitles anyone
 * given a copy to the corresponding source, and the About tab is where that link lives. Blank
 * renders as "Not set yet" -- fill this in before handing a build to anyone.
 *
 * This is deliberately the only outbound link in the app. A donation link lives on the source
 * repository instead: Genesis Plus GX's terms forbid use "in a commercial product or
 * activity", and a link in a README can be reworded or removed at will, where one baked into
 * an APK is already in every copy that has been handed out.
 */
// GPL-3 obliges anyone handed a binary to be able to get its source, and this link is
// how TacoBoy discharges that. It must point at a repository the recipient can
// actually reach -- an empty string renders as "Not set yet", which is honest for a
// build passed to one person and not good enough for a public release.
private const val SOURCE_URL = "https://github.com/SirBerusX3/TacoBoy"

/**
 * Every third-party component shipped in the APK, with the licence it is actually under --
 * each one read from that project's own source rather than assumed, 2026-08-24.
 *
 * The two "non-commercial" entries are the load-bearing ones: Snes9x is "freeware for PERSONAL
 * USE only" and Genesis Plus GX's terms say redistributions "may not be sold, nor may they be
 * used in a commercial product or activity". They are why this app is free and must stay free.
 */
private val LICENCES = listOf(
    "LibretroDroid (app base)" to "GPL-3.0",
    "mGBA — Game Boy Advance" to "MPL-2.0",
    "Gambatte — Game Boy / Color" to "GPL-2.0",
    "Snes9x — SNES" to "Non-commercial",
    "Genesis Plus GX — Genesis / SMS / GG / SG-1000" to "Non-commercial",
    "Handy — Atari Lynx" to "zlib-style",
    "Beetle PSX HW — PlayStation" to "GPL-2.0",
    "SwanStation — PlayStation" to "GPL-3.0",
    "rcheevos (RetroAchievements)" to "MIT",
    "Oboe (audio)" to "Apache-2.0",
)


/**
 * Settings hub, clamp-aware like RomLibraryActivity so it never needs the
 * Pocket Taco removed to reach anything. Deliberately a shell for now: most
 * sections are placeholders (Graphics renderer choices only exist for cores
 * that expose a hardware/software toggle — currently just PS1; Controller is
 * reserved for per-system button bindings and saveable presets — see
 * PocketTacoDetector for the auto-detect half of that plan, already wired up
 * in TacoBoyActivity ahead of there being any preset to auto-assign).
 * Achievements now covers real RetroAchievements login and hash-based ROM
 * identification (see RetroAchievementsClient/RomHasher) — achievement lists
 * and unlock tracking are the still-unbuilt part of that integration.
 * Sections are plain sibling views toggled by visibility rather than
 * fragments, matching this app's existing style (no fragment usage
 * elsewhere in the codebase).
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var sections: List<Pair<TextView, View>>
    private lateinit var boundaryController: BoundaryController

    private lateinit var generalSectionContainer: LinearLayout
    private lateinit var graphicsSectionContainer: LinearLayout
    // Set while waiting for the next physical key press to assign it as the fast-forward
    // key; see dispatchKeyEvent. A separate flag from listeningTarget below since it's a
    // different kind of binding (a frontend action, not a ControllerBindings.Target).
    private var listeningForFastForwardKey = false
    private var listeningForTurboKey = false
    private lateinit var controllerSystemTabs: Map<GameSystem, TextView>
    private lateinit var controllerBindingsList: LinearLayout
    private lateinit var presetsRow: LinearLayout
    private lateinit var systemCoreOptionTabs: Map<GameSystem, TextView>
    private lateinit var systemCoreOptionsList: LinearLayout
    private var systemCoreOptionsSystem: GameSystem = GameSystem.GBA
    private lateinit var achievementsStatusContainer: LinearLayout
    private lateinit var testConnectionContainer: LinearLayout
    private lateinit var liveTrackingStatusContainer: LinearLayout
    private var controllerBindingSystem: GameSystem = GameSystem.GBA
    // Set while waiting for the next physical key press to assign it to this target;
    // null the rest of the time. See dispatchKeyEvent.
    private var listeningTarget: ControllerBindings.Target? = null
    /** Which explanatory notes are currently expanded, keyed by the note's string resource
     *  id. Kept here rather than on the views because several sections rebuild themselves
     *  wholesale (renderGeneralSection, renderGraphicsSection) -- without it, opening a note
     *  and then touching any control on the same tab would silently collapse it again. */
    private val expandedNotes = mutableSetOf<Int>()

    // Registered as a property, not inside a click handler, since ActivityResultLauncher
    // must be registered before STARTED -- same pattern RomLibraryActivity's pickers use.
    // A null uri means the user backed out of the save-location picker.
    private val exportLogsLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            if (uri == null) return@registerForActivityResult
            try {
                contentResolver.openOutputStream(uri)?.use { it.write(exportBody().toByteArray()) }
                Toast.makeText(this, R.string.settings_export_logs_success_toast, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                TacoBoyLog.e("SettingsActivity", "Failed to export logs", e)
                Toast.makeText(this, R.string.settings_export_logs_failed_toast, Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_settings)

        boundaryController = BoundaryController(
            this,
            findViewById(R.id.boundary_guideline),
            findViewById(R.id.occlusion_zone),
            findViewById(R.id.boundary_handle),
            findViewById(R.id.boundary_hint)
        )

        findViewById<View>(R.id.close_button).setOnClickListener { finish() }

        sections = listOf(
            findViewById<TextView>(R.id.tab_general) to findViewById(R.id.section_general),
            findViewById<TextView>(R.id.tab_system) to findViewById(R.id.section_system),
            findViewById<TextView>(R.id.tab_graphics) to findViewById(R.id.section_graphics),
            findViewById<TextView>(R.id.tab_controller) to findViewById(R.id.section_controller),
            findViewById<TextView>(R.id.tab_audio) to findViewById(R.id.section_audio),
            findViewById<TextView>(R.id.tab_achievements) to findViewById(R.id.section_achievements),
            findViewById<TextView>(R.id.tab_advanced) to findViewById(R.id.section_advanced),
            findViewById<TextView>(R.id.tab_about) to findViewById(R.id.section_about),
        )
        sections.forEach { (tab, content) -> tab.setOnClickListener { selectSection(content) } }

        populateGeneralSection(findViewById(R.id.section_general))
        populateSystemSection(findViewById(R.id.section_system))
        populateGraphicsSection(findViewById(R.id.section_graphics))
        populateControllerSection(findViewById(R.id.section_controller))
        populateAudioSection(findViewById(R.id.section_audio))
        populateAchievementsSection(findViewById(R.id.section_achievements))
        populateAdvancedSection(findViewById(R.id.section_advanced))
        populateAboutSection(findViewById(R.id.section_about))

        selectSection(sections.first().second)
    }

    override fun onResume() {
        super.onResume()
        // Boundary may have been dragged on another clamp-aware screen since this Activity was created.
        boundaryController.refresh()
    }

    /**
     * Intercepts every key before it can reach normal view/back handling — needed so
     * a "listening for a binding" row can capture the very next physical key press,
     * and so the Pocket Taco's confirmed-spurious duplicate BACK (see
     * PocketTacoDetector.isSpuriousBack) can't close Settings out from under a
     * successful bind (that duplicate arrives as a second, separate key event
     * immediately after the real one — see CHANGELOG.md's "real fix for wrong/broken
     * button input" entry for how that was confirmed).
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (PocketTacoDetector.isSpuriousBack(event.keyCode, event)) return true

        if (listeningForFastForwardKey && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            if (event.keyCode != KeyEvent.KEYCODE_BACK) {
                TacoBoyPrefs.setFastForwardKeyCode(this, event.keyCode)
            }
            listeningForFastForwardKey = false
            renderGeneralSection()
            return true
        }

        if (listeningForTurboKey && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            if (event.keyCode != KeyEvent.KEYCODE_BACK) {
                TacoBoyPrefs.setTurboKeyCode(this, controllerBindingSystem, event.keyCode)
            }
            listeningForTurboKey = false
            renderControllerBindingsList()
            return true
        }

        val target = listeningTarget
        if (target != null && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                // Not something we'd ever forward to a core anyway — treat it as
                // "cancel listening" rather than a bindable target.
                listeningTarget = null
            } else {
                ControllerBindings.setSource(this, controllerBindingSystem, target, event.keyCode)
                listeningTarget = null
            }
            renderControllerBindingsList()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun selectSection(content: View) {
        sections.forEach { (tab, sectionContent) ->
            val selected = sectionContent === content
            tab.setBackgroundResource(if (selected) R.drawable.tab_selected_bg else 0)
            tab.setTextColor(if (selected) 0xFFFFFFFF.toInt() else 0x88FFFFFF.toInt())
            sectionContent.visibility = if (selected) View.VISIBLE else View.GONE
        }
    }

    /** Fast-forward mode/key, Show FPS, Auto-save SRAM (moved here from System — it's
     *  global behavior, not per-system), and Resume last game on launch. See
     *  TacoBoyActivity.handleFastForwardKey/setupRetroView/initRomFlow for where each
     *  actually takes effect. */
    private fun populateGeneralSection(container: LinearLayout) {
        generalSectionContainer = container
        renderGeneralSection()
    }

    private fun renderGeneralSection() {
        generalSectionContainer.removeAllViews()
        generalSectionContainer.addView(
            withNote(fastForwardModeRow(), R.string.settings_fast_forward_mode_note))
        generalSectionContainer.addView(fastForwardKeyRow())

        generalSectionContainer.addView(withNote(toggleRow(
            getString(R.string.settings_show_fps_label),
            { TacoBoyPrefs.isShowFpsEnabled(this) },
            { TacoBoyPrefs.setShowFpsEnabled(this, it) },
        ), R.string.settings_show_fps_note))

        // The memory-card note is a second paragraph of the same explanation, not its own
        // setting -- one info button opens both, same as when they were stacked inline.
        generalSectionContainer.addView(withNote(toggleRow(
            getString(R.string.settings_auto_save_label),
            { TacoBoyPrefs.isAutoSaveSramEnabled(this) },
            { TacoBoyPrefs.setAutoSaveSramEnabled(this, it) },
        ), R.string.settings_auto_save_note, R.string.settings_memory_card_note))

        generalSectionContainer.addView(withNote(toggleRow(
            getString(R.string.settings_resume_on_launch_label),
            { TacoBoyPrefs.isResumeOnLaunchEnabled(this) },
            { TacoBoyPrefs.setResumeOnLaunchEnabled(this, it) },
        ), R.string.settings_resume_on_launch_note))
    }

    private fun fastForwardModeRow(): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 16, 0, 16)
        }

        val label = TextView(this).apply {
            text = getString(R.string.settings_fast_forward_mode_label)
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val valueButton = TextView(this).apply {
            text = fastForwardModeLabel(TacoBoyPrefs.getFastForwardMode(this@SettingsActivity))
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 15f
            setPadding(24, 12, 24, 12)
            setBackgroundResource(R.drawable.tab_selected_bg)
            isClickable = true
            isFocusable = true
        }
        valueButton.setOnClickListener {
            val modes = FastForwardMode.entries
            val current = TacoBoyPrefs.getFastForwardMode(this)
            val next = modes[(modes.indexOf(current) + 1) % modes.size]
            TacoBoyPrefs.setFastForwardMode(this, next)
            valueButton.text = fastForwardModeLabel(next)
        }

        row.addView(label)
        row.addView(valueButton)
        return row
    }

    private fun fastForwardModeLabel(mode: FastForwardMode): String = when (mode) {
        FastForwardMode.OFF -> getString(R.string.settings_toggle_off)
        FastForwardMode.HOLD -> getString(R.string.settings_fast_forward_mode_hold)
        FastForwardMode.TOGGLE -> getString(R.string.settings_fast_forward_mode_toggle)
    }

    private fun fastForwardKeyRow(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 16, 0, 16)
        }

        val label = TextView(this).apply {
            text = getString(R.string.settings_fast_forward_key_label)
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val boundKey = TacoBoyPrefs.getFastForwardKeyCode(this)
        val valueButton = TextView(this).apply {
            text = if (listeningForFastForwardKey) {
                getString(R.string.controller_binding_listening)
            } else {
                boundKey?.let { ControllerBindings.describeSource(it) } ?: getString(R.string.settings_fast_forward_key_unset)
            }
            setTextColor(if (listeningForFastForwardKey) 0xFFFFD54F.toInt() else 0xFFFFFFFF.toInt())
            textSize = 15f
            setPadding(24, 12, 24, 12)
            setBackgroundResource(R.drawable.tab_selected_bg)
            isClickable = true
            isFocusable = true
        }
        valueButton.setOnClickListener {
            listeningForFastForwardKey = !listeningForFastForwardKey
            renderGeneralSection()
        }

        val resetButton = TextView(this).apply {
            text = getString(R.string.settings_icon_reset)
            setTextColor(0x88FFFFFF.toInt())
            textSize = 18f
            setPadding(20, 12, 4, 12)
            setBackgroundResource(selectableItemBackgroundBorderlessResId())
            isClickable = true
            isFocusable = true
            visibility = if (boundKey != null) View.VISIBLE else View.GONE
        }
        TooltipCompat.setTooltipText(resetButton, getString(R.string.settings_tooltip_reset_binding))
        resetButton.setOnClickListener {
            TacoBoyPrefs.clearFastForwardKeyCode(this)
            renderGeneralSection()
        }

        row.addView(label)
        row.addView(valueButton)
        row.addView(resetButton)
        return row
    }

    /**
     * Per-system core options, relocated here from Advanced 2026-08-16 per
     * settingslayoutideas.md's reorg ("System (Per-system emulation options...)") — pure
     * relocation of already hardware-verified code, nothing about the mechanism changed.
     * System-tab row plus a per-system CoreOptions.Option list, same tabs-then-rows
     * pattern as populateControllerSection. Unlike the Graphics tab's single renderer
     * choice (CoreDefinition.rendererOptionKey), this covers a curated multi-option
     * subset per core — see CoreOptions' doc comment for why it's a separate model
     * rather than folding into CoreDefinition's renderer fields.
     */
    private fun populateSystemSection(container: LinearLayout) {
        container.addView(standaloneNote(
            R.string.settings_note_core_options_title, R.string.settings_system_core_options_note))

        val tabsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 16, 0, 16)
        }
        systemCoreOptionTabs = GameSystem.entries.associateWith { system ->
            TextView(this).apply {
                text = system.shortLabel
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(12, 20, 12, 20)
                maxLines = 1
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = 8 }
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    systemCoreOptionsSystem = system
                    updateSystemCoreOptionTabHighlight()
                    renderSystemCoreOptionsList()
                }
            }
        }
        systemCoreOptionTabs.values.forEach { tabsRow.addView(it) }
        container.addView(wrapInScrollingRow(tabsRow))

        systemCoreOptionsList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        container.addView(systemCoreOptionsList)

        updateSystemCoreOptionTabHighlight()
        renderSystemCoreOptionsList()
    }

    private fun updateSystemCoreOptionTabHighlight() {
        systemCoreOptionTabs.forEach { (system, tab) ->
            val selected = system == systemCoreOptionsSystem
            tab.setBackgroundResource(if (selected) R.drawable.tab_selected_bg else 0)
            tab.setTextColor(if (selected) 0xFFFFFFFF.toInt() else 0x88FFFFFF.toInt())
        }
    }

    private fun renderSystemCoreOptionsList() {
        systemCoreOptionsList.removeAllViews()
        val options = CoreOptions.forSelectedCore(this, systemCoreOptionsSystem)
        if (options.isEmpty()) {
            systemCoreOptionsList.addView(placeholderText(getString(R.string.settings_no_core_options)))
            return
        }
        options.forEach { option ->
            val row = coreOptionRow(systemCoreOptionsSystem, option)
            val description = CoreOptions.descriptionRes(option)
            // Options this app hasn't written an explanation for yet get a plain row rather than an
            // info button that opens nothing.
            systemCoreOptionsList.addView(
                if (description != 0) withNote(row, description) else row)
        }

        // Sits after the rows rather than above them: it's a footnote about what isn't there,
        // not an introduction to what is.
        val omitted = CoreOptions.omittedNoteRes(this, systemCoreOptionsSystem)
        if (omitted != 0) {
            systemCoreOptionsList.addView(standaloneNote(R.string.core_omitted_title, omitted))
        }
    }

    /** GLRetroViewData.preferLowLatencyAudio was hardcoded true in TacoBoyActivity.setupRetroView
     *  before this section existed — this is the first place it's actually a user choice, for the
     *  rare case where the smaller low-latency Oboe buffer underruns audibly on a given
     *  device/core pairing (see TacoBoyPrefs.isLowLatencyAudioEnabled). No resampler choice here:
     *  libretrodroid's audio.cpp has exactly one resampler (LinearResampler), not a selectable set,
     *  so unlike roadmap.md's "Audio options (latency, resampler)" wishlist, only latency is a real
     *  axis to expose. */
    private fun populateAudioSection(container: LinearLayout) {
        container.addView(withNote(toggleRow(
            getString(R.string.settings_low_latency_audio_label),
            { TacoBoyPrefs.isLowLatencyAudioEnabled(this) },
            { TacoBoyPrefs.setLowLatencyAudioEnabled(this, it) },
        ), R.string.settings_low_latency_audio_note))
    }

    /** Core options relocated to System 2026-08-16 (see populateSystemSection); this tab
     *  now covers settingslayoutideas.md's dev/power-user tools instead. */
    private fun populateAdvancedSection(container: LinearLayout) {
        container.addView(withNote(logLevelRow(), R.string.settings_log_level_note))
        container.addView(standaloneNote(
            R.string.settings_note_alignment_title, R.string.settings_16kb_alignment_note))
        container.addView(actionButtonRow(getString(R.string.settings_export_logs_button)) { exportLogs() })
        container.addView(
            actionButtonRow(getString(R.string.settings_clear_crash_log_button)) { clearCrashLog() }
        )
        container.addView(actionButtonRow(getString(R.string.settings_reset_all_settings_button)) { confirmResetAllSettings() })
        // A full-width action button has no label column to sit an info button in, so its
        // note gets its own header line directly beneath it instead.
        container.addView(standaloneNote(
            R.string.settings_note_reset_title, R.string.settings_reset_all_settings_note))
    }

    /** Same cycle-through-on-tap shape as fastForwardModeRow -- advances LogLevel.entries
     *  and writes through TacoBoyPrefs.setLogLevel, which also updates TacoBoyLog's
     *  in-memory filter immediately. */
    /** Cycle-through, same shape as logLevelRow. Each tap also fires the level it just
     *  selected, so the difference between Light, Medium and Strong can be felt while
     *  choosing rather than guessed at and then tested in a game. */
    private fun hapticStrengthRow(): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 16, 0, 16)
        }

        val label = TextView(this).apply {
            text = getString(R.string.controller_touch_haptics_label)
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val valueButton = TextView(this).apply {
            text = hapticStrengthLabel(TacoBoyPrefs.getHapticStrength(this@SettingsActivity))
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 15f
            setPadding(24, 12, 24, 12)
            setBackgroundResource(R.drawable.tab_selected_bg)
            isClickable = true
            isFocusable = true
        }
        valueButton.setOnClickListener {
            val levels = HapticStrength.entries
            val current = TacoBoyPrefs.getHapticStrength(this)
            val next = levels[(levels.indexOf(current) + 1) % levels.size]
            TacoBoyPrefs.setHapticStrength(this, next)
            valueButton.text = hapticStrengthLabel(next)
            previewHaptic(next)
        }

        row.addView(label)
        row.addView(valueButton)
        return row
    }

    /**
     * A slider rather than this screen's usual cycle-through-on-tap button: size is a
     * continuous quantity people converge on by feel, and stepping to it one tap at a time
     * through twenty values would be worse at the one job it has.
     *
     * The value is written on every change, not just when the finger lifts, so that dragging
     * it and then leaving through the back gesture can't lose the choice.
     */
    private fun touchControlScaleRow(): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 16, 0, 16)
        }

        val label = TextView(this).apply {
            text = getString(R.string.controller_touch_size_label)
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val value = TextView(this).apply {
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 15f
            gravity = Gravity.END
            minWidth = 120
            setPadding(24, 12, 0, 12)
        }

        // Integer steps of 5%, so the slider lands on round numbers a user can describe rather
        // than on whatever fraction the pixel under their thumb works out to.
        val steps = ((TacoBoyPrefs.TOUCH_CONTROL_SCALE_MAX - TacoBoyPrefs.TOUCH_CONTROL_SCALE_MIN)
            / SCALE_SLIDER_STEP).toInt()
        fun scaleFor(progress: Int) =
            TacoBoyPrefs.TOUCH_CONTROL_SCALE_MIN + progress * SCALE_SLIDER_STEP
        fun labelFor(scale: Float) =
            getString(R.string.controller_touch_size_value, Math.round(scale * 100))

        val current = TacoBoyPrefs.getTouchControlScale(this)
        value.text = labelFor(current)

        val slider = SeekBar(this).apply {
            max = steps
            progress = Math.round(
                (current - TacoBoyPrefs.TOUCH_CONTROL_SCALE_MIN) / SCALE_SLIDER_STEP
            ).coerceIn(0, steps)
            // Under 1f so the label, the longest on this tab, keeps enough room for one line.
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.85f)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    val scale = scaleFor(progress)
                    value.text = labelFor(scale)
                    if (fromUser) TacoBoyPrefs.setTouchControlScale(this@SettingsActivity, scale)
                }

                override fun onStartTrackingTouch(bar: SeekBar) = Unit
                override fun onStopTrackingTouch(bar: SeekBar) = Unit
            })
        }

        row.addView(label)
        row.addView(slider)
        row.addView(value)
        return row
    }

    /** Cycle-through, the same shape as fastForwardModeRow -- it is the same question about
     *  the same kind of key, so it gets the same control. */
    private fun turboModeRow(): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 16, 0, 16)
        }

        val label = TextView(this).apply {
            text = getString(R.string.controller_turbo_mode_label)
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val valueButton = TextView(this).apply {
            text = turboModeLabel(TacoBoyPrefs.getTurboMode(this@SettingsActivity))
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 15f
            setPadding(24, 12, 24, 12)
            setBackgroundResource(R.drawable.tab_selected_bg)
            isClickable = true
            isFocusable = true
        }
        valueButton.setOnClickListener {
            val modes = TurboMode.entries
            val current = TacoBoyPrefs.getTurboMode(this)
            val next = modes[(modes.indexOf(current) + 1) % modes.size]
            TacoBoyPrefs.setTurboMode(this, next)
            valueButton.text = turboModeLabel(next)
        }

        row.addView(label)
        row.addView(valueButton)
        return row
    }

    private fun turboModeLabel(mode: TurboMode): String = when (mode) {
        TurboMode.OFF -> getString(R.string.controller_turbo_off)
        TurboMode.HOLD -> getString(R.string.controller_turbo_hold)
        TurboMode.TOGGLE -> getString(R.string.controller_turbo_toggle)
    }

    /** A slider for the same reason the control-size one is: a rate is a feel you converge on,
     *  and stepping to it one tap at a time through a dozen values would be worse at it. */
    private fun turboRateRow(): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 16, 0, 16)
        }

        val label = TextView(this).apply {
            text = getString(R.string.controller_turbo_rate_label)
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val value = TextView(this).apply {
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 15f
            gravity = Gravity.END
            minWidth = 120
            setPadding(24, 12, 0, 12)
        }

        val current = TacoBoyPrefs.getTurboRate(this)
        value.text = getString(R.string.controller_turbo_rate_value, current)

        val slider = SeekBar(this).apply {
            max = TacoBoyPrefs.TURBO_RATE_MAX - TacoBoyPrefs.TURBO_RATE_MIN
            progress = current - TacoBoyPrefs.TURBO_RATE_MIN
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.85f)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    val rate = TacoBoyPrefs.TURBO_RATE_MIN + progress
                    value.text =
                        getString(R.string.controller_turbo_rate_value, rate)
                    if (fromUser) TacoBoyPrefs.setTurboRate(this@SettingsActivity, rate)
                }

                override fun onStartTrackingTouch(bar: SeekBar) = Unit
                override fun onStopTrackingTouch(bar: SeekBar) = Unit
            })
        }

        row.addView(label)
        row.addView(slider)
        row.addView(value)
        return row
    }

    private fun hapticStrengthLabel(strength: HapticStrength): String = when (strength) {
        HapticStrength.OFF -> getString(R.string.controller_haptics_off)
        HapticStrength.LIGHT -> getString(R.string.controller_haptics_light)
        HapticStrength.MEDIUM -> getString(R.string.controller_haptics_medium)
        HapticStrength.STRONG -> getString(R.string.controller_haptics_strong)
    }

    private fun previewHaptic(strength: HapticStrength) {
        if (strength == HapticStrength.OFF) return
        val vibrator = try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                (getSystemService(android.content.Context.VIBRATOR_MANAGER_SERVICE)
                    as? android.os.VibratorManager)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(android.content.Context.VIBRATOR_SERVICE) as? android.os.Vibrator
            }
        } catch (e: Exception) {
            TacoBoyLog.e("TacoBoy.Settings", "No vibrator available for haptic preview", e)
            null
        } ?: return
        if (!vibrator.hasVibrator()) return

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val amplitude = if (vibrator.hasAmplitudeControl()) {
                strength.amplitude
            } else {
                android.os.VibrationEffect.DEFAULT_AMPLITUDE
            }
            vibrator.vibrate(
                android.os.VibrationEffect.createOneShot(strength.durationMs, amplitude)
            )
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(strength.durationMs)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /**
     * Puts a row of system tabs inside a horizontal scroller with the same chevron affordance
     * the library's tab row uses.
     *
     * These rows were weighted, one share per system, which was fine at five and started
     * wrapping "SNES" onto two lines at ten. Shrinking the text further was the wrong answer
     * in the library and is the wrong answer here; the row scrolls instead, and the tabs go
     * back to their natural width.
     */
    private fun wrapInScrollingRow(tabsRow: LinearLayout): View {
        fun chevron(text: String) = TextView(this).apply {
            this.text = text
            setTextColor(0x80FFFFFF.toInt())
            textSize = 20f
            setPadding(dp(6), 0, dp(6), 0)
            visibility = View.INVISIBLE
            isClickable = true
            isFocusable = false
        }

        val left = chevron(getString(R.string.system_tabs_scroll_left))
        val right = chevron(getString(R.string.system_tabs_scroll_right))

        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isHorizontalFadingEdgeEnabled = true
            setFadingEdgeLength(dp(20))
            isFocusable = false
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
            addView(tabsRow)
        }

        fun updateHints() {
            left.visibility =
                if (scroll.canScrollHorizontally(-1)) View.VISIBLE else View.INVISIBLE
            right.visibility =
                if (scroll.canScrollHorizontally(1)) View.VISIBLE else View.INVISIBLE
        }

        left.setOnClickListener { scroll.smoothScrollBy(-(scroll.width * 3 / 4), 0) }
        right.setOnClickListener { scroll.smoothScrollBy(scroll.width * 3 / 4, 0) }
        scroll.viewTreeObserver.addOnScrollChangedListener { updateHints() }
        // A single post() runs before the row has been measured, so canScrollHorizontally is
        // still false and the chevron never appears. Recheck on every layout instead.
        scroll.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateHints() }

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(left)
            addView(scroll)
            addView(right)
        }
    }

    private fun logLevelRow(): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 16, 0, 16)
        }

        val label = TextView(this).apply {
            text = getString(R.string.settings_log_level_label)
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val valueButton = TextView(this).apply {
            text = logLevelLabel(TacoBoyPrefs.getLogLevel(this@SettingsActivity))
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 15f
            setPadding(24, 12, 24, 12)
            setBackgroundResource(R.drawable.tab_selected_bg)
            isClickable = true
            isFocusable = true
        }
        valueButton.setOnClickListener {
            val levels = LogLevel.entries
            val current = TacoBoyPrefs.getLogLevel(this)
            val next = levels[(levels.indexOf(current) + 1) % levels.size]
            TacoBoyPrefs.setLogLevel(this, next)
            valueButton.text = logLevelLabel(next)
        }

        row.addView(label)
        row.addView(valueButton)
        return row
    }

    private fun logLevelLabel(level: LogLevel): String = when (level) {
        LogLevel.ERROR -> getString(R.string.settings_log_level_error)
        LogLevel.WARN -> getString(R.string.settings_log_level_warn)
        LogLevel.INFO -> getString(R.string.settings_log_level_info)
        LogLevel.DEBUG -> getString(R.string.settings_log_level_debug)
    }

    /**
     * Crash history first, then this session's log buffer.
     *
     * The two answer different questions and only one of them survives a crash: the buffer is
     * whatever this process has said since it started, while CrashLog is what killed the
     * *previous* ones. Exporting only the buffer meant the file never contained the crash --
     * by the time anyone thought to export, the process that crashed was long gone.
     */
    private fun exportBody(): String {
        val crashes = CrashLog.readAll(this)
        val session = TacoBoyLog.exportText()
        return buildString {
            if (crashes.isNotEmpty()) {
                append("===== crash history =====\n").append(crashes).append("\n\n")
            }
            append("===== this session =====\n").append(session)
        }
    }

    private fun exportLogs() {
        if (TacoBoyLog.exportText().isEmpty() && !CrashLog.hasRecords(this)) {
            Toast.makeText(this, R.string.settings_export_logs_empty_toast, Toast.LENGTH_SHORT).show()
            return
        }
        val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd_HHmmss", java.util.Locale.US).format(java.util.Date())
        exportLogsLauncher.launch("tacoboy_log_$timestamp.txt")
    }

    /** Separate from Reset All Settings: the crash history is evidence, not configuration,
     *  and someone clearing it has decided they are done with it -- which is not the same
     *  decision as putting every preference back. */
    private fun clearCrashLog() {
        if (!CrashLog.hasRecords(this)) {
            Toast.makeText(this, R.string.settings_clear_crash_log_empty_toast, Toast.LENGTH_SHORT).show()
            return
        }
        CrashLog.clear(this)
        Toast.makeText(this, R.string.settings_clear_crash_log_done_toast, Toast.LENGTH_SHORT).show()
    }

    private fun confirmResetAllSettings() {
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.settings_reset_all_settings_confirm_title)
            .setMessage(R.string.settings_reset_all_settings_confirm_message)
            .setPositiveButton(R.string.settings_reset_all_settings_confirm_button) { _, _ ->
                TacoBoyPrefs.resetAllSettings(this)
                Toast.makeText(this, R.string.settings_reset_all_settings_done_toast, Toast.LENGTH_SHORT).show()
                recreate()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        dialog.window?.setGravity(Gravity.TOP)
    }

    private fun coreOptionRow(system: GameSystem, option: CoreOptions.Option): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 16, 0, 16)
        }

        val label = TextView(this).apply {
            text = option.label
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val valueButton = TextView(this).apply {
            text = CoreOptions.currentValue(this@SettingsActivity, system, option)
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 15f
            setPadding(24, 12, 24, 12)
            setBackgroundResource(R.drawable.tab_selected_bg)
            isClickable = true
            isFocusable = true
        }

        val resetButton = TextView(this).apply {
            text = getString(R.string.settings_icon_reset)
            setTextColor(0x88FFFFFF.toInt())
            textSize = 18f
            setPadding(20, 12, 4, 12)
            setBackgroundResource(selectableItemBackgroundBorderlessResId())
            isClickable = true
            isFocusable = true
            visibility = if (CoreOptions.isCustomized(this@SettingsActivity, system, option)) View.VISIBLE else View.GONE
        }
        TooltipCompat.setTooltipText(resetButton, getString(R.string.settings_tooltip_reset_binding))

        valueButton.setOnClickListener {
            val current = valueButton.text.toString()
            val currentIndex = option.choices.indexOf(current).coerceAtLeast(0)
            val next = option.choices[(currentIndex + 1) % option.choices.size]
            TacoBoyPrefs.setCoreOptionValue(this, system, option.key, next)
            valueButton.text = next
            resetButton.visibility = View.VISIBLE
        }
        resetButton.setOnClickListener {
            TacoBoyPrefs.clearCoreOptionValue(this, system, option.key)
            valueButton.text = CoreOptions.currentValue(this, system, option)
            resetButton.visibility = View.GONE
        }

        row.addView(label)
        row.addView(valueButton)
        row.addView(resetButton)
        return row
    }

    /** Shared by every on/off row in Settings (auto-save, Pocket Taco auto-bind, …) —
     *  `isEnabled`/`onToggle` read and write whichever TacoBoyPrefs flag this row controls. */
    private fun toggleRow(label: String, isEnabled: () -> Boolean, onToggle: (Boolean) -> Unit): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 16, 0, 16)
        }

        val labelView = TextView(this).apply {
            text = label
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val valueButton = TextView(this).apply {
            text = toggleValueLabel(isEnabled())
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 15f
            setPadding(24, 12, 24, 12)
            setBackgroundResource(R.drawable.tab_selected_bg)
            isClickable = true
            isFocusable = true
        }
        valueButton.setOnClickListener {
            val next = !isEnabled()
            onToggle(next)
            valueButton.text = toggleValueLabel(next)
        }

        row.addView(labelView)
        row.addView(valueButton)
        return row
    }

    private fun toggleValueLabel(enabled: Boolean): String {
        return getString(if (enabled) R.string.settings_toggle_on else R.string.settings_toggle_off)
    }

    /**
     * One core row (only for systems with more than one, currently just PS1) plus one
     * renderer row per GameSystem whose *selected* core has a renderer to choose between
     * — nothing here names a specific system, so a future system just needs a second
     * CoreDefinition or non-empty rendererChoices to automatically get rows. Rebuilt from
     * scratch on core change (renderGraphicsSection), same pattern as
     * renderAchievementsStatus/renderPresetsRow, since switching core can change which
     * renderer choices even apply (SwanStation's single-item Software list vs Beetle PSX HW's
     * software/hardware_gl/hardware_vk are completely different lists).
     */
    private fun populateGraphicsSection(container: LinearLayout) {
        graphicsSectionContainer = container
        renderGraphicsSection()
    }

    private fun renderGraphicsSection() {
        graphicsSectionContainer.removeAllViews()

        // Global (not per-system) and first, like the Controller tab's auto-bind row: it
        // applies to every system's picture, so hanging it off one system's block would be
        // misleading.
        graphicsSectionContainer.addView(withNote(toggleRow(
            getString(R.string.settings_integer_scale_label),
            { TacoBoyPrefs.isIntegerScaleEnabled(this) },
            { TacoBoyPrefs.setIntegerScaleEnabled(this, it) },
        ), R.string.settings_integer_scale_note))

        val configurable = GameSystem.entries.filter {
            it.cores.size > 1 || it.defaultCore.rendererChoices.isNotEmpty()
        }

        if (configurable.isEmpty()) {
            graphicsSectionContainer.addView(placeholderText(getString(R.string.settings_no_renderer_options)))
            // Falls through to the shader rows below rather than returning: those exist for
            // every system regardless of whether any core has a renderer to choose between.
        }

        configurable.forEach { system ->
            if (system.cores.size > 1) {
                graphicsSectionContainer.addView(withNote(coreRow(system), R.string.settings_core_note))
            }
            val core = system.selectedCore(this)
            if (core.rendererChoices.isNotEmpty()) {
                // Was one shared note at the bottom of the tab; now it hangs off each renderer
                // row it actually describes, which also means it disappears with them when no
                // system has a renderer to choose between.
                graphicsSectionContainer.addView(
                    withNote(rendererRow(system, core), R.string.settings_renderer_note))
            }
        }

        // Every system gets one, unlike the rows above -- a display shader doesn't depend on
        // the core having anything to choose between.
        GameSystem.entries.forEach { system ->
            graphicsSectionContainer.addView(
                withNote(shaderRow(system), R.string.settings_shader_note))
        }
    }

    /** Cycle-through-on-tap, same shape as fastForwardModeRow/logLevelRow. Switching core
     *  rebuilds the whole Graphics section rather than just this row, since the renderer
     *  row underneath depends on which core is now selected. */
    private fun coreRow(system: GameSystem): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 16, 0, 16)
        }

        val label = TextView(this).apply {
            text = getString(R.string.settings_core_label, system.shortLabel)
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val valueButton = TextView(this).apply {
            text = system.selectedCore(this@SettingsActivity).displayName
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 15f
            setPadding(24, 12, 24, 12)
            setBackgroundResource(R.drawable.tab_selected_bg)
            isClickable = true
            isFocusable = true
        }
        valueButton.setOnClickListener {
            val cores = system.cores
            val current = system.selectedCore(this)
            val next = cores[(cores.indexOf(current) + 1) % cores.size]
            TacoBoyPrefs.setSelectedCoreFileName(this, system, next.fileName)
            renderGraphicsSection()
        }

        row.addView(label)
        row.addView(valueButton)
        return row
    }

    private fun rendererRow(system: GameSystem, core: CoreDefinition): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 16, 0, 16)
        }

        val label = TextView(this).apply {
            text = getString(R.string.settings_renderer_label, system.shortLabel)
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val valueButton = TextView(this).apply {
            text = core.selectedRenderer(this@SettingsActivity, system)
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 15f
            setPadding(24, 12, 24, 12)
            setBackgroundResource(R.drawable.tab_selected_bg)
            isClickable = true
            isFocusable = true
        }

        // Only shown once a choice has actually been saved — nothing to reset back
        // from when the row is already just displaying CoreDefinition.defaultRenderer.
        val resetButton = TextView(this).apply {
            text = getString(R.string.settings_icon_reset)
            setTextColor(0x88FFFFFF.toInt())
            textSize = 18f
            setPadding(20, 12, 4, 12)
            setBackgroundResource(selectableItemBackgroundBorderlessResId())
            isClickable = true
            isFocusable = true
        }
        TooltipCompat.setTooltipText(resetButton, getString(R.string.settings_tooltip_reset_renderer))

        fun refreshResetVisibility() {
            resetButton.visibility =
                if (TacoBoyPrefs.getRendererChoice(this@SettingsActivity, system, core.fileName) != null) View.VISIBLE else View.GONE
        }
        refreshResetVisibility()

        valueButton.setOnClickListener {
            val current = valueButton.text.toString()
            val currentIndex = core.rendererChoices.indexOf(current).coerceAtLeast(0)
            val next = core.rendererChoices[(currentIndex + 1) % core.rendererChoices.size]
            valueButton.text = next
            TacoBoyPrefs.setRendererChoice(this, system, core.fileName, next)
            refreshResetVisibility()
        }
        resetButton.setOnClickListener {
            TacoBoyPrefs.clearRendererChoice(this, system, core.fileName)
            valueButton.text = core.defaultRenderer
            refreshResetVisibility()
        }

        row.addView(label)
        row.addView(valueButton)
        row.addView(resetButton)
        return row
    }

    /**
     * System-tab row (which GameSystem's bindings are being edited) plus a bindings
     * list rebuilt from scratch on every change — simpler than tracking individual
     * row state, and cheap at ~16 rows. Bindings are stored per-system (ControllerBindings),
     * so switching tabs here just changes which table renderControllerBindingsList reads.
     */
    /** Cycle-through-on-tap like the core and renderer rows above it, with the same reset
     *  affordance -- shown only once a choice has actually been saved, since there's nothing to
     *  reset back from while the row is still displaying ShaderChoice.DEFAULT. */
    private fun shaderRow(system: GameSystem): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 16, 0, 16)
        }

        val label = TextView(this).apply {
            text = getString(R.string.settings_shader_label, system.shortLabel)
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val valueButton = TextView(this).apply {
            text = shaderLabel(TacoBoyPrefs.getShaderChoice(this@SettingsActivity, system))
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 15f
            setPadding(24, 12, 24, 12)
            setBackgroundResource(R.drawable.tab_selected_bg)
            isClickable = true
            isFocusable = true
        }

        val resetButton = TextView(this).apply {
            text = getString(R.string.settings_icon_reset)
            setTextColor(0x88FFFFFF.toInt())
            textSize = 18f
            setPadding(20, 12, 4, 12)
            setBackgroundResource(selectableItemBackgroundBorderlessResId())
            isClickable = true
            isFocusable = true
        }
        TooltipCompat.setTooltipText(resetButton, getString(R.string.settings_tooltip_reset_shader))

        fun refresh(choice: ShaderChoice) {
            valueButton.text = shaderLabel(choice)
            resetButton.visibility =
                if (choice != ShaderChoice.DEFAULT) View.VISIBLE else View.GONE
        }
        refresh(TacoBoyPrefs.getShaderChoice(this, system))

        valueButton.setOnClickListener {
            val choices = ShaderChoice.entries
            val current = TacoBoyPrefs.getShaderChoice(this, system)
            val next = choices[(choices.indexOf(current) + 1) % choices.size]
            TacoBoyPrefs.setShaderChoice(this, system, next)
            refresh(next)
        }
        resetButton.setOnClickListener {
            TacoBoyPrefs.setShaderChoice(this, system, ShaderChoice.DEFAULT)
            refresh(ShaderChoice.DEFAULT)
        }

        row.addView(label)
        row.addView(valueButton)
        row.addView(resetButton)
        return row
    }

    /**
     * Version, attribution, and the source link.
     *
     * The source link is not decoration: LibretroDroid is GPL-3 and TacoBoy is a derivative of
     * it, so anyone handed a build is entitled to the corresponding source. Putting the link
     * on a screen a user can actually find is how that obligation gets discharged in practice.
     *
     * The licence list names every bundled core because each is a separate project by
     * different authors under different terms -- and because two of them (Snes9x, Genesis Plus
     * GX) forbid commercial use, which is a real constraint on what this app may become rather
     * than a formality. See CHANGELOG.md for the verbatim clauses.
     */
    private fun populateAboutSection(container: LinearLayout) {
        container.addView(readOnlyRow(getString(R.string.about_version_label), versionSummary()))

        container.addView(withNote(
            linkRow(getString(R.string.about_source_label), SOURCE_URL),
            R.string.about_source_note))

        container.addView(standaloneNote(
            R.string.about_content_note_title, R.string.about_content_note))

        container.addView(standaloneNote(
            R.string.about_licences_label, R.string.about_licences_note))
        LICENCES.forEach { (component, licence) ->
            container.addView(readOnlyRow(component, licence))
        }

        container.addView(standaloneNote(
            R.string.about_diagnostics_label, R.string.about_diagnostics_note))
        val diagnostics = TextView(this).apply {
            text = diagnosticsText()
            setTextColor(0xB3FFFFFF.toInt())
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(0, 8, 0, 8)
            setTextIsSelectable(true)
        }
        container.addView(diagnostics)
        container.addView(actionButtonRow(getString(R.string.about_copy_button)) {
            val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                as? android.content.ClipboardManager
            clipboard?.setPrimaryClip(
                android.content.ClipData.newPlainText("TacoBoy diagnostics", diagnosticsText())
            )
            Toast.makeText(this, R.string.about_copied_toast, Toast.LENGTH_SHORT).show()
        })
    }

    /** A label and a value with nothing to tap -- the About tab is mostly facts, not settings. */
    private fun readOnlyRow(label: String, value: String): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 12, 0, 12)
        }
        row.addView(TextView(this).apply {
            text = label
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(TextView(this).apply {
            text = value
            setTextColor(0xB3FFFFFF.toInt())
            textSize = 14f
            gravity = Gravity.END
        })
        return row
    }

    /** Opens [url] in a browser. A blank url renders as "Not set yet" rather than being hidden,
     *  so an unfinished build says so instead of silently omitting something it owes. */
    private fun linkRow(label: String, url: String): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 12, 0, 12)
        }
        row.addView(TextView(this).apply {
            text = label
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        val value = TextView(this).apply {
            text = if (url.isBlank()) getString(R.string.about_link_unset) else url
            setTextColor(if (url.isBlank()) 0x66FFFFFF.toInt() else 0xFF7FB3D5.toInt())
            textSize = 13f
            gravity = Gravity.END
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            isClickable = true
            isFocusable = true
            setPadding(16, 8, 0, 8)
        }
        value.setOnClickListener {
            if (url.isBlank()) {
                Toast.makeText(this, R.string.about_link_unset_toast, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            try {
                startActivity(
                    android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse(url)
                    )
                )
            } catch (e: Exception) {
                TacoBoyLog.e("SettingsActivity", "Could not open link", e)
                Toast.makeText(this, R.string.about_link_unset_toast, Toast.LENGTH_SHORT).show()
            }
        }
        row.addView(value)
        return row
    }

    private fun versionSummary(): String {
        return try {
            val info = packageManager.getPackageInfo(packageName, 0)
            val code = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION") info.versionCode.toLong()
            }
            "${info.versionName} ($code)"
        } catch (e: Exception) {
            TacoBoyLog.e("SettingsActivity", "Could not read package version", e)
            "unknown"
        }
    }

    /** Deliberately holds nothing personal: build, OS, device, and which cores are selected.
     *  Everything here is what the first reply to a bug report would have to ask for anyway. */
    private fun diagnosticsText(): String {
        return buildString {
            append("TacoBoy ").append(versionSummary()).append('\n')
            append("Android ").append(android.os.Build.VERSION.RELEASE)
                .append(" (SDK ").append(android.os.Build.VERSION.SDK_INT).append(")\n")
            append(android.os.Build.MANUFACTURER).append(' ').append(android.os.Build.MODEL)
                .append(" (").append(abiLabel()).append(")\n")
            append("Page size: ").append(pageSizeLabel()).append('\n')
            append("PS1 core: ").append(GameSystem.PS1.selectedCore(this@SettingsActivity).displayName)
                .append('\n')
            append("On-screen pad: ")
                .append(if (TacoBoyPrefs.isTouchControlsEnabled(this@SettingsActivity)) "on" else "off")
                .append(" at ")
                .append(Math.round(TacoBoyPrefs.getTouchControlScale(this@SettingsActivity) * 100))
                .append('%')
        }
    }

    /** The device's ABI, plus the ABI TacoBoy's native code actually runs as when the two differ.
     *
     *  Build.SUPPORTED_ABIS[0] is the device's own ABI, not the app's. TacoBoy ships arm64-v8a only,
     *  so on an x86_64 emulator or Chromebook it runs through an ARM-to-x86 translation layer
     *  (libndk_translation) while the device line would still have said plainly "x86_64" -- a
     *  report from such a machine would read like native hardware. That distinction mattered: an
     *  x86_64 16 KB emulator's dialog was partly confused by translation. So a mismatch is spelled
     *  out, and on a real arm64 phone nothing changes.
     *
     *  The app's ABI comes from nativeLibraryDir, whose last component is the instruction-set
     *  directory the package manager installed the libraries for ("arm64" -> arm64-v8a). The
     *  field that states it directly, ApplicationInfo.primaryCpuAbi, is hidden API. */
    private fun abiLabel(): String = diagnosticsAbiLabel(
        device = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "?",
        app = applicationInfo.nativeLibraryDir?.let { nativeAbiForLibDir(java.io.File(it).name) },
    )

    /** The memory page size this process runs with. It is the one number that makes a report
     *  from a 16 KB device checkable: 16 KB support cannot be confirmed from a description of
     *  how the app behaved, and most people have no ADB to run `getconf PAGESIZE` with. Copy
     *  Diagnostics now carries it, so "works on my phone" arrives with the fact that decides
     *  whether it proves anything.
     *
     *  From 0.2.1 every bundled library is 16 KB aligned, so Android never runs TacoBoy in
     *  page-size compat mode and this is the device's real page size. Whether compat mode
     *  would alter the answer for an older build is not established here -- the version line
     *  says which case a report is. */
    private fun pageSizeLabel(): String {
        val bytes = try {
            android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE)
        } catch (e: Exception) {
            TacoBoyLog.e("SettingsActivity", "Could not read page size", e)
            -1L
        }
        return diagnosticsPageSizeLabel(bytes)
    }

    private fun shaderLabel(choice: ShaderChoice): String = when (choice) {
        ShaderChoice.DEFAULT -> getString(R.string.settings_shader_default)
        ShaderChoice.SHARP -> getString(R.string.settings_shader_sharp)
        ShaderChoice.CRT -> getString(R.string.settings_shader_crt)
        ShaderChoice.LCD -> getString(R.string.settings_shader_lcd)
        ShaderChoice.UPSCALE1 -> getString(R.string.settings_shader_upscale1)
        ShaderChoice.UPSCALE2 -> getString(R.string.settings_shader_upscale2)
        ShaderChoice.UPSCALE3 -> getString(R.string.settings_shader_upscale3)
    }

    private fun populateControllerSection(container: LinearLayout) {
        // Global (not per-system) — placed above the system tabs so it's visible
        // regardless of which one is selected. See TacoBoyActivity.inputDeviceListener
        // for where this actually fires (Pocket Taco connect).
        container.addView(withNote(toggleRow(
            getString(R.string.controller_auto_bind_label),
            { TacoBoyPrefs.isPocketTacoAutoBindEnabled(this) },
            { TacoBoyPrefs.setPocketTacoAutoBindEnabled(this, it) },
        ), R.string.controller_auto_bind_note))

        container.addView(withNote(touchControlScaleRow(), R.string.controller_touch_size_note))

        container.addView(withNote(hapticStrengthRow(), R.string.controller_touch_haptics_note))

        container.addView(withNote(turboModeRow(), R.string.controller_turbo_mode_note))
        container.addView(turboRateRow())

        val tabsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 16)
        }
        controllerSystemTabs = GameSystem.entries.associateWith { system ->
            TextView(this).apply {
                text = system.shortLabel
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(12, 20, 12, 20)
                maxLines = 1
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = 8 }
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    controllerBindingSystem = system
                    listeningTarget = null
                    updateControllerTabHighlight()
                    renderPresetsRow()
                    renderControllerBindingsList()
                }
            }
        }
        controllerSystemTabs.values.forEach { tabsRow.addView(it) }
        container.addView(wrapInScrollingRow(tabsRow))

        // Instructions rather than a note, but the same three lines of prose between the
        // system tabs and the bindings they apply to -- collapsed the same way, with a title
        // that still says what's behind it.
        container.addView(standaloneNote(
            R.string.settings_note_bindings_title, R.string.settings_controller_placeholder))

        presetsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val presetsScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, 8, 0, 16)
            addView(presetsRow)
        }
        container.addView(presetsScroll)

        controllerBindingsList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        container.addView(controllerBindingsList)

        updateControllerTabHighlight()
        renderPresetsRow()
        renderControllerBindingsList()
    }

    private fun updateControllerTabHighlight() {
        controllerSystemTabs.forEach { (system, tab) ->
            val selected = system == controllerBindingSystem
            tab.setBackgroundResource(if (selected) R.drawable.tab_selected_bg else 0)
            tab.setTextColor(if (selected) 0xFFFFFFFF.toInt() else 0x88FFFFFF.toInt())
        }
    }

    /** Only shows targets relevant to the selected system's real hardware (see
     *  GameSystem.relevantControllerTargets) — e.g. no X/Y row for GB/GBC, which
     *  never had those buttons, so nothing here looks like an unexplained gap. */
    private fun renderControllerBindingsList() {
        controllerBindingsList.removeAllViews()
        ControllerBindings.Target.entries
            .filter { it in controllerBindingSystem.relevantControllerTargets }
            .forEach { target -> controllerBindingsList.addView(controllerBindingRow(target)) }
        // Last, and inside the per-system list rather than above it with the global rows:
        // turbo competes with A and B for the same physical buttons, and which button is
        // spare differs by system. Kept visible even with Turbo Mode off, so binding a key
        // and turning the mode on can be done in either order.
        controllerBindingsList.addView(withNote(turboKeyRow(), R.string.controller_turbo_key_note))
    }

    /** Same shape as fastForwardKeyRow, but per system -- see TacoBoyPrefs.getTurboKeyCode. */
    private fun turboKeyRow(): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 12, 0, 12)
        }

        val label = TextView(this).apply {
            text = getString(R.string.controller_turbo_key_label)
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val system = controllerBindingSystem
        val boundKey = TacoBoyPrefs.getTurboKeyCode(this, system)
        val valueButton = TextView(this).apply {
            text = when {
                listeningForTurboKey -> getString(R.string.controller_binding_listening)
                boundKey != null -> ControllerBindings.describeSource(boundKey)
                else -> getString(R.string.settings_fast_forward_key_unset)
            }
            setTextColor(if (listeningForTurboKey) 0xFFFFD54F.toInt() else 0xFFFFFFFF.toInt())
            textSize = 15f
            setPadding(24, 12, 24, 12)
            setBackgroundResource(R.drawable.tab_selected_bg)
            isClickable = true
            isFocusable = true
        }
        valueButton.setOnClickListener {
            listeningForTurboKey = !listeningForTurboKey
            renderControllerBindingsList()
        }

        val resetButton = TextView(this).apply {
            text = getString(R.string.settings_icon_reset)
            setTextColor(0x88FFFFFF.toInt())
            textSize = 18f
            setPadding(20, 12, 4, 12)
            setBackgroundResource(selectableItemBackgroundBorderlessResId())
            isClickable = true
            isFocusable = true
            visibility = if (boundKey != null) View.VISIBLE else View.GONE
        }
        TooltipCompat.setTooltipText(resetButton, getString(R.string.settings_tooltip_reset_binding))
        resetButton.setOnClickListener {
            TacoBoyPrefs.clearTurboKeyCode(this, system)
            renderControllerBindingsList()
        }

        row.addView(label)
        row.addView(valueButton)
        row.addView(resetButton)
        return row
    }

    private fun controllerBindingRow(target: ControllerBindings.Target): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 12, 0, 12)
        }

        val label = TextView(this).apply {
            text = target.label
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val system = controllerBindingSystem
        val isListening = listeningTarget == target

        val valueButton = TextView(this).apply {
            text = if (isListening) {
                getString(R.string.controller_binding_listening)
            } else {
                ControllerBindings.describeSource(ControllerBindings.getSource(this@SettingsActivity, system, target))
            }
            setTextColor(if (isListening) 0xFFFFD54F.toInt() else 0xFFFFFFFF.toInt())
            textSize = 15f
            setPadding(24, 12, 24, 12)
            setBackgroundResource(R.drawable.tab_selected_bg)
            isClickable = true
            isFocusable = true
        }
        valueButton.setOnClickListener {
            listeningTarget = if (isListening) null else target
            renderControllerBindingsList()
        }

        val resetButton = TextView(this).apply {
            text = getString(R.string.settings_icon_reset)
            setTextColor(0x88FFFFFF.toInt())
            textSize = 18f
            setPadding(20, 12, 4, 12)
            setBackgroundResource(selectableItemBackgroundBorderlessResId())
            isClickable = true
            isFocusable = true
            visibility = if (ControllerBindings.isCustomized(this@SettingsActivity, system, target)) View.VISIBLE else View.GONE
        }
        TooltipCompat.setTooltipText(resetButton, getString(R.string.settings_tooltip_reset_binding))
        resetButton.setOnClickListener {
            ControllerBindings.clearSource(this, system, target)
            renderControllerBindingsList()
        }

        row.addView(label)
        row.addView(valueButton)
        row.addView(resetButton)
        return row
    }

    /**
     * "Save Preset…" chip first, then one chip per saved ControllerPresets entry.
     * Presets aren't tied to a system — the same list shows regardless of which
     * system tab is selected — but tapping one applies it to whichever system is
     * currently active (see ControllerPresets.applyToSystem).
     */
    private fun renderPresetsRow() {
        presetsRow.removeAllViews()

        val saveButton = TextView(this).apply {
            text = getString(R.string.controller_save_preset)
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 13f
            setPadding(28, 14, 28, 14)
            setBackgroundResource(R.drawable.tab_selected_bg)
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = 12 }
        }
        saveButton.setOnClickListener { promptSavePreset() }
        presetsRow.addView(saveButton)

        val defaultPresetName = TacoBoyPrefs.getDefaultControllerPresetName(this)
        ControllerPresets.list(this).forEach { preset ->
            val isDefault = preset.name == defaultPresetName

            val presetContainer = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.quick_menu_panel_bg)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = 12 }
            }

            val chip = TextView(this).apply {
                text = preset.name
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 13f
                setPadding(28, 14, 8, 14)
                isClickable = true
                isFocusable = true
            }
            TooltipCompat.setTooltipText(chip, getString(R.string.controller_preset_tooltip, controllerBindingSystem.shortLabel))
            chip.setOnClickListener {
                ControllerPresets.applyToSystem(this, controllerBindingSystem, preset)
                renderControllerBindingsList()
                Toast.makeText(this, getString(R.string.controller_preset_applied, preset.name), Toast.LENGTH_SHORT).show()
            }
            chip.setOnLongClickListener {
                confirmDeletePreset(preset)
                true
            }

            // Marks this preset "apply to every system when a Pocket Taco connects" (see
            // TacoBoyActivity.inputDeviceListener) — not the same as the tap-to-apply chip
            // above, which only touches the currently-selected system tab.
            val star = TextView(this).apply {
                text = getString(if (isDefault) R.string.settings_icon_star_filled else R.string.settings_icon_star_outline)
                setTextColor(if (isDefault) 0xFFFFD54F.toInt() else 0x88FFFFFF.toInt())
                textSize = 16f
                setPadding(8, 14, 20, 14)
                setBackgroundResource(selectableItemBackgroundBorderlessResId())
                isClickable = true
                isFocusable = true
            }
            TooltipCompat.setTooltipText(star, getString(R.string.controller_preset_default_tooltip))
            star.setOnClickListener {
                if (isDefault) {
                    TacoBoyPrefs.setDefaultControllerPresetName(this, null)
                    Toast.makeText(this, R.string.controller_preset_default_cleared, Toast.LENGTH_SHORT).show()
                } else {
                    TacoBoyPrefs.setDefaultControllerPresetName(this, preset.name)
                    Toast.makeText(this, getString(R.string.controller_preset_default_set, preset.name), Toast.LENGTH_SHORT).show()
                }
                renderPresetsRow()
            }

            presetContainer.addView(chip)
            presetContainer.addView(star)
            presetsRow.addView(presetContainer)
        }
    }

    private fun promptSavePreset() {
        val input = EditText(this).apply {
            hint = getString(R.string.controller_preset_name_hint)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.controller_save_preset)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    val bindings = ControllerBindings.Target.entries.associateWith {
                        ControllerBindings.getSource(this, controllerBindingSystem, it)
                    }
                    ControllerPresets.save(this, name, bindings)
                    renderPresetsRow()
                    Toast.makeText(this, getString(R.string.controller_preset_saved, name), Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        dialog.window?.setGravity(Gravity.TOP)
    }

    private fun confirmDeletePreset(preset: ControllerPresets.Preset) {
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.controller_preset_delete_title)
            .setMessage(getString(R.string.controller_preset_delete_message, preset.name))
            .setPositiveButton(R.string.controller_preset_delete_confirm) { _, _ ->
                ControllerPresets.delete(this, preset.name)
                renderPresetsRow()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        dialog.window?.setGravity(Gravity.TOP)
    }

    /**
     * Login status/action row plus an explanatory note. Rebuilt from scratch on
     * login/logout (renderAchievementsStatus), same pattern as renderPresetsRow —
     * cheap at one row, and avoids tracking button/text state separately.
     */
    private fun populateAchievementsSection(container: LinearLayout) {
        achievementsStatusContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        container.addView(achievementsStatusContainer)
        container.addView(standaloneNote(
            R.string.settings_note_achievements_title, R.string.settings_achievements_note))
        renderAchievementsStatus()

        testConnectionContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        container.addView(testConnectionContainer)
        renderTestConnectionRow()

        liveTrackingStatusContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        container.addView(liveTrackingStatusContainer)
        container.addView(standaloneNote(
            R.string.settings_note_live_tracking_title, R.string.settings_live_tracking_note))
        renderLiveTrackingStatus()

        container.addView(withNote(toggleRow(
            getString(R.string.settings_hardcore_mode_label),
            { TacoBoyPrefs.isHardcoreModeEnabled(this) },
            { TacoBoyPrefs.setHardcoreModeEnabled(this, it) },
        ), R.string.settings_hardcore_mode_note))

        // Always available, even logged out -- exercises the same Toast.makeText call
        // AchievementsSession.onAchievementTriggered uses for a real unlock, with
        // fabricated data, purely to confirm what it looks like without needing an
        // actual in-game unlock to test against.
        container.addView(actionButtonRow(getString(R.string.settings_test_achievement_button)) {
            Toast.makeText(
                this,
                getString(R.string.achievement_unlocked_toast, getString(R.string.settings_test_achievement_title), 5),
                Toast.LENGTH_LONG
            ).show()
        })
    }

    /** Reusable single tappable action row, distinct from toggleRow (on/off) and the
     *  cycle-choice rows elsewhere -- just a full-width button that fires a callback. */
    private fun actionButtonRow(label: String, onClick: () -> Unit): View {
        return TextView(this).apply {
            text = label
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(24, 16, 24, 16)
            setBackgroundResource(R.drawable.tab_selected_bg)
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 8; bottomMargin = 8 }
            setOnClickListener { onClick() }
        }
    }

    /** Only shown once Web API key credentials are actually saved -- nothing to test
     *  against otherwise. Re-rendered from renderAchievementsStatus so login/logout keeps
     *  it in sync without a separate call site to remember. */
    private fun renderTestConnectionRow() {
        testConnectionContainer.removeAllViews()
        val username = TacoBoyPrefs.getRetroAchievementsUsername(this)
        val apiKey = TacoBoyPrefs.getRetroAchievementsApiKey(this)
        if (username == null || apiKey == null) return

        testConnectionContainer.addView(actionButtonRow(getString(R.string.settings_test_connection_button)) {
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) { RetroAchievementsClient.verifyCredentials(username, apiKey) }
                val message = if (result.valid) {
                    getString(R.string.settings_test_connection_success, result.confirmedUsername ?: username)
                } else {
                    getString(R.string.settings_test_connection_failed)
                }
                Toast.makeText(this@SettingsActivity, message, Toast.LENGTH_LONG).show()
            }
        })
    }

    private fun renderAchievementsStatus() {
        achievementsStatusContainer.removeAllViews()
        val username = TacoBoyPrefs.getRetroAchievementsUsername(this)
        val apiKey = TacoBoyPrefs.getRetroAchievementsApiKey(this)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 16, 0, 16)
        }
        val status = TextView(this).apply {
            text = if (username != null) {
                getString(R.string.settings_achievements_status_logged_in, username)
            } else {
                getString(R.string.settings_achievements_status_logged_out)
            }
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val actionButton = TextView(this).apply {
            text = getString(
                if (username != null) R.string.settings_achievements_logout_button
                else R.string.settings_achievements_login_button
            )
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 15f
            setPadding(24, 12, 24, 12)
            setBackgroundResource(R.drawable.tab_selected_bg)
            isClickable = true
            isFocusable = true
        }
        actionButton.setOnClickListener {
            if (username != null) logOutOfRetroAchievements() else promptRetroAchievementsLogin()
        }

        row.addView(status)
        row.addView(actionButton)
        achievementsStatusContainer.addView(row)
        if (apiKey != null) {
            achievementsStatusContainer.addView(
                placeholderText(getString(R.string.settings_achievements_api_key_masked, maskApiKey(apiKey)))
            )
        }
        if (::testConnectionContainer.isInitialized) renderTestConnectionRow()
    }

    /** Shows just enough of the saved Web API key to visually confirm which key is set
     *  (e.g. to tell two RA accounts apart) without displaying the whole credential --
     *  the key itself is never shown again after it's entered in promptRetroAchievementsLogin. */
    private fun maskApiKey(apiKey: String): String {
        val visibleCount = 4
        val hiddenCount = (apiKey.length - visibleCount).coerceAtLeast(0)
        return "•".repeat(hiddenCount) + apiKey.takeLast(visibleCount)
    }

    private fun logOutOfRetroAchievements() {
        TacoBoyPrefs.clearRetroAchievementsCredentials(this)
        renderAchievementsStatus()
        Toast.makeText(this, R.string.settings_achievements_logged_out_toast, Toast.LENGTH_SHORT).show()
    }

    /**
     * Username + Web API key — confirmed live against a real account this session that
     * RetroAchievements has no password-based login step at all (see
     * RetroAchievementsClient's doc comment for how that was verified): the key itself is
     * the permanent credential, so this dialog only ever asks for it, never a password.
     * "Login" here is really just RetroAchievementsClient.verifyCredentials confirming the
     * key works before TacoBoyPrefs saves it. Positive button gets its click listener
     * attached after `.show()` rather than via setPositiveButton, the standard Android
     * pattern for keeping the dialog open on invalid input / while the network call is in
     * flight, instead of it dismissing immediately the way setPositiveButton's callback would.
     */
    private fun promptRetroAchievementsLogin() {
        val usernameInput = EditText(this).apply {
            hint = getString(R.string.settings_achievements_username_hint)
        }
        val apiKeyInput = EditText(this).apply {
            hint = getString(R.string.settings_achievements_api_key_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val fields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(usernameInput)
            addView(apiKeyInput)
            addView(placeholderText(getString(R.string.settings_achievements_api_key_note)))
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.settings_achievements_login_title)
            .setView(fields)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        dialog.window?.setGravity(Gravity.TOP)

        val positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        positiveButton.setOnClickListener {
            val username = usernameInput.text.toString().trim()
            val apiKey = apiKeyInput.text.toString().trim()
            if (username.isEmpty() || apiKey.isEmpty()) return@setOnClickListener

            positiveButton.isEnabled = false
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) { RetroAchievementsClient.verifyCredentials(username, apiKey) }
                if (result.valid) {
                    val loggedInAs = result.confirmedUsername ?: username
                    TacoBoyPrefs.setRetroAchievementsCredentials(this@SettingsActivity, loggedInAs, apiKey)
                    renderAchievementsStatus()
                    Toast.makeText(
                        this@SettingsActivity,
                        getString(R.string.settings_achievements_login_success, loggedInAs),
                        Toast.LENGTH_SHORT
                    ).show()
                    dialog.dismiss()
                } else {
                    positiveButton.isEnabled = true
                    Toast.makeText(
                        this@SettingsActivity,
                        R.string.settings_achievements_login_failed,
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    /** Same structure as renderAchievementsStatus/promptRetroAchievementsLogin above, for
     *  the separate session-token login live tracking needs — see AchievementsSession's
     *  and RetroAchievementsClient's doc comments for why this can't just reuse the
     *  Web API key already set up above. */
    private fun renderLiveTrackingStatus() {
        liveTrackingStatusContainer.removeAllViews()
        val username = TacoBoyPrefs.getRetroAchievementsSessionUsername(this)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 16, 0, 16)
        }
        val status = TextView(this).apply {
            text = if (username != null) {
                getString(R.string.settings_live_tracking_status_logged_in, username)
            } else {
                getString(R.string.settings_live_tracking_status_logged_out)
            }
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val actionButton = TextView(this).apply {
            text = getString(
                if (username != null) R.string.settings_live_tracking_logout_button
                else R.string.settings_live_tracking_login_button
            )
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 15f
            setPadding(24, 12, 24, 12)
            setBackgroundResource(R.drawable.tab_selected_bg)
            isClickable = true
            isFocusable = true
        }
        actionButton.setOnClickListener {
            if (username != null) logOutOfLiveTracking() else promptLiveTrackingLogin()
        }

        row.addView(status)
        row.addView(actionButton)
        liveTrackingStatusContainer.addView(row)
    }

    private fun logOutOfLiveTracking() {
        TacoBoyPrefs.clearRetroAchievementsSession(this)
        renderLiveTrackingStatus()
        Toast.makeText(this, R.string.settings_live_tracking_logged_out_toast, Toast.LENGTH_SHORT).show()
    }

    private fun promptLiveTrackingLogin() {
        val usernameInput = EditText(this).apply {
            hint = getString(R.string.settings_achievements_username_hint)
            setText(TacoBoyPrefs.getRetroAchievementsUsername(this@SettingsActivity) ?: "")
        }
        val passwordInput = EditText(this).apply {
            hint = getString(R.string.settings_live_tracking_password_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val fields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(usernameInput)
            addView(passwordInput)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.settings_live_tracking_login_title)
            .setView(fields)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        dialog.window?.setGravity(Gravity.TOP)

        val positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        positiveButton.setOnClickListener {
            val username = usernameInput.text.toString().trim()
            val password = passwordInput.text.toString()
            if (username.isEmpty() || password.isEmpty()) return@setOnClickListener

            positiveButton.isEnabled = false
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) { RetroAchievementsClient.login(username, password) }
                if (result.success && result.token != null) {
                    val loggedInAs = result.confirmedUsername ?: username
                    TacoBoyPrefs.setRetroAchievementsSession(this@SettingsActivity, loggedInAs, result.token)
                    renderLiveTrackingStatus()
                    Toast.makeText(
                        this@SettingsActivity,
                        getString(R.string.settings_live_tracking_login_success, loggedInAs),
                        Toast.LENGTH_SHORT
                    ).show()
                    dialog.dismiss()
                } else {
                    positiveButton.isEnabled = true
                    Toast.makeText(
                        this@SettingsActivity,
                        R.string.settings_live_tracking_login_failed,
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    /** android:background="?attr/selectableItemBackgroundBorderless" — same ripple
     *  every other icon-only button in this app gets via XML, resolved here since
     *  this row is built in code rather than inflated. */
    /**
     * Pairs a control row with its explanatory note(s), collapsed behind an info button slipped
     * in between the row's label and its value. Every note used to render as always-on body text
     * under its row, which on the General tab alone buried four settings under roughly thirty
     * lines of grey prose -- on a screen the Pocket Taco clamp has already shortened, the rows
     * themselves were what got pushed off. Nothing was cut: tapping the button reveals the exact
     * same text, in place, and [expandedNotes] remembers it across a section rebuild.
     *
     * Takes string resource ids rather than resolved strings precisely so the first one can
     * double as that stable key. Pass more than one for a note that runs to several paragraphs;
     * they open and close together, as they read.
     */
    private fun withNote(row: LinearLayout, vararg noteRes: Int): View {
        val notes = noteRes.map { placeholderText(getString(it)) }
        // Index 1 puts it after the label (which carries the layout weight, so it has already
        // claimed the free space) and immediately before the value control.
        row.addView(infoToggle(noteRes.first(), notes), 1)

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(row)
            notes.forEach { addView(it) }
        }
    }

    /**
     * For a note with no row of its own to hang off -- a section intro, or one describing a whole
     * block of controls rather than a single setting. Renders as one dim "info + title" line that
     * expands the same way [withNote]'s does; the whole line is the target, not just the glyph.
     */
    private fun standaloneNote(titleRes: Int, vararg noteRes: Int): View {
        val notes = noteRes.map { placeholderText(getString(it)) }
        val toggle = infoToggle(noteRes.first(), notes)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 8, 0, 8)
            isClickable = true
            isFocusable = true
            setBackgroundResource(selectableItemBackgroundResId())
            addView(toggle)
            addView(TextView(this@SettingsActivity).apply {
                text = getString(titleRes)
                setTextColor(0x88FFFFFF.toInt())
                textSize = 13f
            })
            setOnClickListener { toggle.performClick() }
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(header)
            notes.forEach { addView(it) }
        }
    }

    /** The shared button behind both note styles: sets the notes' initial visibility from
     *  [expandedNotes] and flips both them and its own brightness on tap. Brightening rather
     *  than swapping the glyph keeps the row's width from shifting as notes open and close. */
    private fun infoToggle(key: Int, notes: List<TextView>): TextView {
        fun apply(expanded: Boolean, button: TextView) {
            notes.forEach { it.visibility = if (expanded) View.VISIBLE else View.GONE }
            button.setTextColor(if (expanded) 0xFFFFFFFF.toInt() else 0x66FFFFFF.toInt())
        }

        return TextView(this).apply {
            text = getString(R.string.settings_icon_info)
            textSize = 16f
            setPadding(16, 12, 16, 12)
            setBackgroundResource(selectableItemBackgroundBorderlessResId())
            isClickable = true
            isFocusable = true
            TooltipCompat.setTooltipText(this, getString(R.string.settings_tooltip_info))
            apply(key in expandedNotes, this)
            setOnClickListener {
                val expanded = key !in expandedNotes
                if (expanded) expandedNotes.add(key) else expandedNotes.remove(key)
                apply(expanded, this)
            }
        }
    }

    private fun selectableItemBackgroundResId(): Int {
        val outValue = android.util.TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
        return outValue.resourceId
    }

    private fun selectableItemBackgroundBorderlessResId(): Int {
        val outValue = android.util.TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outValue, true)
        return outValue.resourceId
    }

    private fun placeholderText(message: String): TextView {
        return TextView(this).apply {
            text = message
            setTextColor(0x88FFFFFF.toInt())
            textSize = 13f
            setPadding(0, 16, 0, 0)
        }
    }
}

// The decisions behind two diagnostics lines, kept free of Context so they can be tested
// directly -- on a 4 KB arm64 phone both lines look right even when the logic is wrong, since a
// failed ABI lookup falls back to the device ABI and only 4 KB is ever measured there.

internal fun diagnosticsPageSizeLabel(bytes: Long): String = when {
    bytes <= 0 -> "unknown"
    bytes % 1024 == 0L -> "${bytes / 1024} KB"
    else -> "$bytes bytes"
}

/** The instruction-set directory name the package manager installs native libraries under,
 *  as found at the end of ApplicationInfo.nativeLibraryDir, mapped back to an ABI name. */
internal fun nativeAbiForLibDir(dirName: String): String? = when (dirName) {
    "arm64" -> "arm64-v8a"
    "arm" -> "armeabi-v7a"
    "x86_64" -> "x86_64"
    "x86" -> "x86"
    else -> null
}

internal fun diagnosticsAbiLabel(device: String, app: String?): String =
    if (app == null || app == device) device else "$device, running $app translated"
