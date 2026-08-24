package com.tacoboy

import android.content.Intent
import android.hardware.input.InputManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.Guideline
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import com.android.libretrodroid.R
import com.swordfish.libretrodroid.GLRetroView
import com.swordfish.libretrodroid.GLRetroViewData
import com.swordfish.libretrodroid.Variable
import com.swordfish.libretrodroid.VirtualFile
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// GLRetroView.frameSpeed multiplier while fast-forward is engaged -- matches RetroArch's
// own common default, not independently tuned; not user-configurable yet.
private const val FAST_FORWARD_SPEED = 2

/** Furthest down the boundary may be dragged while the on-screen pad is showing. Below roughly
 *  a quarter of the screen the controls are too small to hit reliably, and a pad you cannot
 *  press is worse than no pad -- so the handle stops here rather than letting it happen. */
private const val TOUCH_CONTROLS_MAX_BOUNDARY = 0.75f

// Set before recreate() -- by onResetClicked(), and by the library's ROM-switch path -- to
// load exactly this ROM on the way back up, bypassing the "Resume on Launch" preference gate
// in initRomFlow(). That preference is about whether a cold app launch should resume anything
// at all, an unrelated concern to "the user just asked for this game, now". initRomFlow()
// clears it as it reads it -- it survives recreate(), so a stale one hijacks later switches.
private const val EXTRA_FORCE_RELOAD_ROM_URI = "force_reload_rom_uri"

private const val TAG = "TacoBoy.Activity"

/** Two startup crashes in a row is the point at which restarting into the same state stops
 *  looking like bad luck. One is common enough (a core that dislikes one ROM) and already
 *  handled more precisely by the crashed-ROM breaker; this is the blunter backstop for
 *  everything else. See TacoBoyApplication. */
private const val SAFE_START_CRASH_STREAK = 2

/**
 * Entry point for TacoBoy. Renders the emulator only in the screen region
 * above wherever the GameSir Pocket Taco clamps on, leaving the rest solid
 * black instead of relying on Android split-screen. The boundary is
 * user-adjustable via boundary_handle since clamp height varies by phone.
 *
 * ROM source: a user-picked SAF folder tree (see RomLibrary), scanned
 * across every system in GameSystem — core selection follows the picked
 * ROM's extension.
 */
class TacoBoyActivity : AppCompatActivity() {

    private var retroView: GLRetroView? = null

    private lateinit var guideline: Guideline
    private lateinit var occlusionZone: View
    private lateinit var boundaryHandle: View
    private lateinit var libraryButton: View
    private lateinit var romPickerPrompt: View
    private lateinit var romPickerStatus: TextView
    private lateinit var romPickerButton: Button
    private lateinit var gameContainer: FrameLayout
    private lateinit var boundaryController: BoundaryController
    private lateinit var menuButton: View
    private lateinit var touchControls: TouchControls
    private lateinit var touchControlsButton: View
    private lateinit var editLayoutButton: TextView
    private lateinit var resetLayoutButton: View
    private lateinit var quickMenuPanel: View
    private lateinit var slotLabels: List<TextView>
    private lateinit var slotSaveButtons: List<View>
    private lateinit var slotLoadButtons: List<View>
    private lateinit var saveSlotsContainer: View
    private lateinit var hardcoreModeNote: View
    private lateinit var fpsOverlay: TextView
    private lateinit var turboIndicator: TextView
    private lateinit var fastForwardToggleButton: TextView
    private lateinit var achievementTrackingToggleButton: TextView

    private var currentRomIdentifier: String? = null
    private var currentGameSystem: GameSystem? = null
    // Resolved once per ROM load (see loadRom) from GameSystem.selectedCore, not
    // re-read live -- switching PS1's core mid-session in Settings shouldn't affect
    // the game already running, only the next one loaded.
    private var currentCore: CoreDefinition? = null
    private var currentRomUri: Uri? = null
    private var sessionStartTimeMs: Long = 0L

    // Reset implicitly on every ROM switch -- setupRetroView only ever runs once per
    // Activity instance (switching ROMs recreates the Activity), so this can't leak
    // an engaged state from a previous game into a new one.
    private var fastForwardEngaged = false

    private var achievementsSession: AchievementsSession? = null

    /**
     * Fires only on a genuine connect transition (device attach), not on a static
     * "is it connected right now" check — the Pocket Taco is normally already
     * clamped on and connected before the app is even opened, and this Activity
     * gets recreated fairly often (switching ROMs from the library), so toasting
     * on every onCreate/onResume would spam far more than it'd inform.
     */
    private val inputDeviceListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) {
            val device = inputManager().getInputDevice(deviceId) ?: return
            if (PocketTacoDetector.isPocketTaco(device)) {
                if (TacoBoyPrefs.isPocketTacoAutoBindEnabled(this@TacoBoyActivity)) {
                    val defaultPresetName = TacoBoyPrefs.getDefaultControllerPresetName(this@TacoBoyActivity)
                    val defaultPreset = defaultPresetName?.let { name ->
                        ControllerPresets.list(this@TacoBoyActivity).firstOrNull { it.name == name }
                    }
                    if (defaultPreset != null) {
                        // Not scoped to one system, same as resetAllToDefaults below — a
                        // stale customization could exist for any of them.
                        GameSystem.entries.forEach { system ->
                            ControllerPresets.applyToSystem(this@TacoBoyActivity, system, defaultPreset)
                        }
                        Toast.makeText(
                            this@TacoBoyActivity,
                            getString(R.string.pocket_taco_detected_preset, defaultPreset.name),
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        // Safe to do unconditionally on every connect — DEFAULT_SOURCE is the
                        // confirmed-correct mapping for this exact hardware, so this only ever
                        // clears a stale customization (e.g. left over from a different
                        // controller), never something that was already correct.
                        ControllerBindings.resetAllToDefaults(this@TacoBoyActivity)
                        Toast.makeText(this@TacoBoyActivity, R.string.pocket_taco_detected_rebound, Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(this@TacoBoyActivity, R.string.pocket_taco_detected, Toast.LENGTH_SHORT).show()
                }
            }
        }
        override fun onInputDeviceRemoved(deviceId: Int) {}
        override fun onInputDeviceChanged(deviceId: Int) {}
    }

    private fun inputManager() = getSystemService(INPUT_SERVICE) as InputManager

    private val libraryLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val romUri = result.data
                ?.getStringExtra(RomLibraryActivity.EXTRA_RESULT_ROM_URI)
                ?.let(Uri::parse)
                ?: return@registerForActivityResult

            if (retroView != null) {
                // Backing out to the library and picking the same game that's already
                // running (e.g. just tapping its tile again) used to recreate() anyway,
                // which meant every trip to the library restarted the game even when
                // nothing was actually being switched. Nothing to do here — it's still
                // running underneath.
                if (romUri == currentRomUri) {
                    return@registerForActivityResult
                }
                // Validate before recreate() — recreate() is a one-way trip back to
                // a blank picker, so a stale pick (deleted file, revoked SAF grant)
                // shouldn't be allowed to silently kill an already-running game.
                if (!canOpenRom(romUri)) {
                    Toast.makeText(
                        this,
                        lastLoadFailureMessage ?: R.string.rom_picker_rom_unavailable,
                        Toast.LENGTH_LONG
                    ).show()
                    return@registerForActivityResult
                }
                TacoBoyPrefs.setLastRomUri(this, romUri.toString())
                // Signal the switch through the same extra Reset uses rather than leaning on
                // the lastRom pref alone: initRomFlow's lastRom branch is gated on "Resume on
                // Launch", which is about whether a *cold start* resumes anything -- with that
                // preference off, a mid-session switch would recreate() into a blank picker.
                setIntent(Intent(intent).putExtra(EXTRA_FORCE_RELOAD_ROM_URI, romUri.toString()))
                // Native LibretroDroid is a C++ singleton; hot-swapping
                // GLRetroView in place (remove old, add new) doesn't fully
                // tear down the old instance's native state first. Verified
                // via device backtrace: a stale resume() call on the old
                // instance raced the new instance's create() and hit a
                // null FPSSync, SIGSEGV. Recreating the Activity reuses the
                // already-correct cold-start path instead.
                recreate()
            } else if (loadRom(romUri)) {
                TacoBoyPrefs.setLastRomUri(this, romUri.toString())
                hideRomPickerPrompt()
            } else {
                val reason = lastLoadFailureMessage
                Toast.makeText(
                    this,
                    reason ?: R.string.rom_picker_rom_unavailable,
                    Toast.LENGTH_LONG
                ).show()
                showRomPickerPrompt(getString(reason ?: R.string.rom_picker_hint))
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_tacoboy)

        guideline = findViewById(R.id.boundary_guideline)
        occlusionZone = findViewById(R.id.occlusion_zone)
        boundaryHandle = findViewById(R.id.boundary_handle)
        libraryButton = findViewById(R.id.library_button)
        romPickerPrompt = findViewById(R.id.rom_picker_prompt)
        romPickerStatus = findViewById(R.id.rom_picker_status)
        romPickerButton = findViewById(R.id.rom_picker_button)
        gameContainer = findViewById(R.id.gamecontainer)
        menuButton = findViewById(R.id.menu_button)
        touchControls = findViewById(R.id.touch_controls)
        touchControlsButton = findViewById(R.id.touch_controls_button)
        editLayoutButton = findViewById(R.id.edit_layout_button)
        resetLayoutButton = findViewById(R.id.reset_layout_button)
        quickMenuPanel = findViewById(R.id.quick_menu_panel)

        val slotRowIds = listOf(R.id.slot_row_1, R.id.slot_row_2, R.id.slot_row_3, R.id.slot_row_4)
        slotLabels = slotRowIds.map { findViewById<View>(it).findViewById(R.id.slot_label) }
        slotSaveButtons = slotRowIds.map { findViewById<View>(it).findViewById(R.id.slot_save) }
        slotLoadButtons = slotRowIds.map { findViewById<View>(it).findViewById(R.id.slot_load) }
        slotSaveButtons.forEachIndexed { index, view -> view.setOnClickListener { onSaveSlot(index + 1) } }
        slotLoadButtons.forEachIndexed { index, view -> view.setOnClickListener { onLoadSlot(index + 1) } }
        saveSlotsContainer = findViewById(R.id.save_slots_container)
        hardcoreModeNote = findViewById(R.id.hardcore_mode_note)
        fpsOverlay = findViewById(R.id.fps_overlay)
        turboIndicator = findViewById(R.id.turbo_indicator)
        findViewById<View>(R.id.reset_button).setOnClickListener { onResetClicked() }
        findViewById<View>(R.id.exit_to_library_button).setOnClickListener { onExitToLibraryClicked() }
        fastForwardToggleButton = findViewById(R.id.fast_forward_toggle_button)
        fastForwardToggleButton.setOnClickListener { onFastForwardToggleClicked() }
        findViewById<View>(R.id.info_button).setOnClickListener { onInfoClicked() }
        achievementTrackingToggleButton = findViewById(R.id.achievement_tracking_toggle_button)
        achievementTrackingToggleButton.setOnClickListener { onAchievementTrackingToggleClicked() }

        boundaryController = BoundaryController(
            this, guideline, occlusionZone, boundaryHandle, findViewById(R.id.boundary_hint)
        )

        // Always available — RomLibraryActivity self-manages per-system folder state,
        // so there's no "nothing configured yet" case this needs to be hidden for.
        libraryButton.visibility = View.VISIBLE

        romPickerButton.setOnClickListener { openLibrary() }
        libraryButton.setOnClickListener { openLibrary() }
        menuButton.setOnClickListener { toggleQuickMenu() }
        touchControlsButton.setOnClickListener { onTouchControlsToggled() }
        editLayoutButton.setOnClickListener { onEditLayoutToggled() }
        resetLayoutButton.setOnClickListener { onResetLayoutClicked() }

        initRomFlow()
    }

    override fun onResume() {
        super.onResume()
        // Boundary may have been dragged on RomLibraryActivity since this
        // Activity was last shown (same prefs, no recreate happens on back).
        boundaryController.refresh()
        // Same story for the on-screen pad's size and haptics: Settings writes them straight
        // to prefs and coming back here doesn't recreate, so they are re-read on the way in
        // rather than only at game load. Both are no-ops when nothing changed.
        touchControls.globalScale = TacoBoyPrefs.getTouchControlScale(this)
        touchControls.hapticStrength = TacoBoyPrefs.getHapticStrength(this)
        inputManager().registerInputDeviceListener(inputDeviceListener, null)
    }

    override fun onPause() {
        super.onPause()
        inputManager().unregisterInputDeviceListener(inputDeviceListener)
        abandonTurbo()
        // The whole point is to survive whatever happens next — backgrounding,
        // switching ROMs (recreate()), or the process getting killed outright to
        // reclaim memory. onPause is the last callback guaranteed to run before any
        // of those, and this call blocks until the write completes (see
        // GLRetroView.serializeSRAM's runOnEmulationThread), so it's safe even right
        // before a kill.
        persistSram()
    }

    /** Cart battery-save (SRAM), not the save-state slots — see SramManager. Silently
     *  best-effort: backgrounding the app is not the moment to surface a Toast. */
    private fun persistSram() {
        if (!TacoBoyPrefs.isAutoSaveSramEnabled(this)) return
        val romId = currentRomIdentifier ?: return
        val view = retroView ?: return
        try {
            val data = view.serializeSRAM()
            SramManager.save(this, romId, data)
        } catch (e: Exception) {
            // Best-effort — nothing useful to do if this fails mid-teardown.
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
        // Losing focus without pausing -- a dialog, the notification shade -- means the
        // release for anything currently held goes somewhere else and never reaches
        // onKeyUp. Left alone that button stays in heldTargets forever, and the next time
        // turbo engages it rapid-fires a button nobody is touching. Cheaper to forget what
        // is held than to be wrong about it.
        if (!hasFocus) abandonTurbo()
    }

    private fun hideSystemBars() {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    // --- ROM folder / selection flow -------------------------------------

    private fun initRomFlow() {
        // Ahead of every branch that loads something by itself: after repeated crashes on
        // startup, the one thing not to do is confidently reload whatever was on screen when
        // it died. Coming up at the picker costs the user one tap and guarantees they can
        // reach the library, which is the whole promise of the restart in TacoBoyApplication.
        if (TacoBoyPrefs.getCrashStreak(this) >= SAFE_START_CRASH_STREAK) {
            TacoBoyLog.e(TAG, "Starting at the picker after repeated startup crashes")
            setIntent(Intent(intent).apply { removeExtra(EXTRA_FORCE_RELOAD_ROM_URI) })
            showRomPickerPrompt(getString(R.string.rom_picker_safe_start))
            return
        }

        val forcedRom = intent.getStringExtra(EXTRA_FORCE_RELOAD_ROM_URI)
        if (forcedRom != null) {
            // One-shot signal, consumed on read: it rides the Activity's intent across
            // recreate(), and switching ROMs recreate()s too -- so leaving it set meant one
            // Reset hijacked every later ROM switch back to the game that had been reset,
            // until the app was restarted (found play-testing 2026-08-24).
            setIntent(Intent(intent).apply { removeExtra(EXTRA_FORCE_RELOAD_ROM_URI) })
            if (loadRom(Uri.parse(forcedRom))) {
                hideRomPickerPrompt()
                return
            }
            showRomPickerPrompt(
                getString(lastLoadFailureMessage ?: R.string.rom_picker_last_rom_missing)
            )
            return
        }

        val lastRom = TacoBoyPrefs.getLastRomUri(this)
        val crashedRom = TacoBoyPrefs.getCrashedRomUri(this)

        if (lastRom != null && lastRom == crashedRom) {
            // Last launch began loading this exact ROM and never got to clear the
            // flag — almost certainly a native crash mid-load (see TacoBoyPrefs.
            // beginRomLoad). Don't retry it automatically or we'd crash-loop.
            //
            // This is also the only moment a native crash can be written down at all: it
            // killed the process with no handler running, so the evidence is exactly this
            // flag, and it is gone the moment the next line clears it.
            val crashedName = try {
                DocumentFile.fromSingleUri(this, Uri.parse(crashedRom))?.name
            } catch (e: Exception) {
                null
            }
            val crashedSystem = crashedName?.let { GameSystem.forFileName(it) }
            CrashLog.record(
                context = this,
                summary = "Core died loading ${crashedName ?: "a ROM"} (no first frame)",
                detail = buildString {
                    append("System: ").append(crashedSystem?.shortLabel ?: "unknown")
                    crashedSystem?.let {
                        append("\nCore: ").append(it.selectedCore(this@TacoBoyActivity).fileName)
                    }
                    append("\nUri: ").append(crashedRom)
                },
            )
            TacoBoyPrefs.setLastRomUri(this, null)
            TacoBoyPrefs.endRomLoad(this)
            showRomPickerPrompt(getString(R.string.rom_picker_crash_recovered))
            return
        }

        if (lastRom != null && TacoBoyPrefs.isResumeOnLaunchEnabled(this)) {
            if (loadRom(Uri.parse(lastRom))) {
                hideRomPickerPrompt()
                return
            }
            // lastRom was set but is no longer openable (file moved/deleted, or its
            // SAF grant was revoked e.g. by clearing the picker app's data) — say so
            // explicitly rather than showing the same hint a genuine first run gets.
            val reason = lastLoadFailureMessage
            // A missing BIOS is not a missing ROM: clearing lastRom would quietly forget a
            // game that is perfectly fine and will load as soon as a BIOS is imported.
            if (reason == null) TacoBoyPrefs.setLastRomUri(this, null)
            showRomPickerPrompt(getString(reason ?: R.string.rom_picker_last_rom_missing))
            return
        }
        showRomPickerPrompt(getString(R.string.rom_picker_hint))
    }

    private fun openLibrary() {
        val intent = Intent(this, RomLibraryActivity::class.java)
        if (retroView != null) {
            intent.putExtra(RomLibraryActivity.EXTRA_GAME_TITLE, currentRomIdentifier)
        }
        libraryLauncher.launch(intent)
    }

    private fun showRomPickerPrompt(message: String) {
        romPickerStatus.text = message
        romPickerPrompt.visibility = View.VISIBLE
    }

    private fun hideRomPickerPrompt() {
        romPickerPrompt.visibility = View.GONE
    }

    /** Set by [loadRom] when it fails for a reason worth naming; callers show it in place of
     *  their generic "couldn't open that" message. Cleared at the start of each attempt so a
     *  stale reason can never be reported against a later failure. */
    @StringRes
    private var lastLoadFailureMessage: Int? = null

    /** Returns false (and leaves any existing game running) if the URI can no longer be opened, its system isn't supported, or a required BIOS is missing. */
    private fun loadRom(uri: Uri): Boolean {
        lastLoadFailureMessage = null
        // A revoked SAF grant (folder deleted, picker app's data cleared, etc.) can
        // throw from the DocumentFile query rather than just returning null,
        // depending on the platform's DocumentsProvider — wrap it so that's just
        // another "can't load this ROM" case, not a crash (and not a crash-loop,
        // since this happens before beginRomLoad's crash-loop breaker takes over).
        val displayName = try {
            DocumentFile.fromSingleUri(this, uri)?.name
        } catch (e: Exception) {
            null
        } ?: return false
        val system = GameSystem.forFileName(displayName) ?: return false

        val pfd = try {
            contentResolver.openFileDescriptor(uri, "r")
        } catch (e: Exception) {
            null
        } ?: return false

        // Checked before anything is committed, because the alternative is worse than a
        // refusal: with no BIOS staged, a system that genuinely needs one hands the core an
        // empty directory and the user gets a black screen or a native crash, neither of
        // which says "import a BIOS". Systems whose BIOS is optional (Lynx, which falls back
        // to Handy's internal HLE boot ROM) are deliberately not gated.
        if (system.needsBios && !system.biosOptional && !BiosManager.hasUsableBios(this, system)) {
            TacoBoyLog.e(TAG, "Refusing to load ${system.shortLabel} ROM: no BIOS imported")
            lastLoadFailureMessage = R.string.rom_picker_bios_missing
            return false
        }

        currentRomIdentifier = displayName
        currentGameSystem = system
        currentCore = system.selectedCore(this)
        currentRomUri = uri
        TacoBoyPrefs.beginRomLoad(this, uri.toString())
        TacoBoyPrefs.recordRomPlayed(this, uri.toString())
        setupRetroView(system, VirtualFile(displayName, pfd))
        menuButton.visibility = View.VISIBLE
        touchControlsButton.visibility = View.VISIBLE
        setupTouchControls(system)
        updateSlotLabels()
        return true
    }

    /**
     * Side-effect-free accessibility probe for a ROM Uri — same checks as loadRom()
     * without touching Activity/native state, so a caller can confirm a pick is
     * still valid before committing to something irreversible (recreate()).
     */
    private fun canOpenRom(uri: Uri): Boolean {
        lastLoadFailureMessage = null
        return try {
            val displayName = DocumentFile.fromSingleUri(this, uri)?.name ?: return false
            val system = GameSystem.forFileName(displayName) ?: return false
            // Kept in step with loadRom's own checks on purpose: this runs before recreate(),
            // and anything loadRom would refuse afterwards would strand the user at a blank
            // picker having killed the game they were playing.
            if (system.needsBios && !system.biosOptional && !BiosManager.hasUsableBios(this, system)) {
                lastLoadFailureMessage = R.string.rom_picker_bios_missing
                return false
            }
            contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
        } catch (e: Exception) {
            false
        }
    }

    // --- Save states / quick menu -----------------------------------------

    private fun toggleQuickMenu() {
        if (quickMenuPanel.visibility == View.VISIBLE) {
            quickMenuPanel.visibility = View.GONE
        } else {
            // Hardcore Mode only hides save-states -- SramManager's auto-save and any
            // in-progress manual SRAM save are completely untouched by this check.
            if (TacoBoyPrefs.isHardcoreModeEnabled(this)) {
                saveSlotsContainer.visibility = View.GONE
                hardcoreModeNote.visibility = View.VISIBLE
            } else {
                saveSlotsContainer.visibility = View.VISIBLE
                hardcoreModeNote.visibility = View.GONE
                updateSlotLabels()
            }
            updateFastForwardToggleLabel()
            updateAchievementTrackingToggle()
            quickMenuPanel.visibility = View.VISIBLE
        }
    }

    /** Hidden entirely when live tracking isn't active this session -- nothing to pause.
     *  Checks AchievementsSession.isActive(), not mere non-null: a session object exists
     *  unconditionally on every load (see setupRetroView) before its async activation work
     *  (login check, game recognition, fetching achievements) has resolved one way or the
     *  other, so non-null alone doesn't mean there's genuinely anything to toggle yet. */
    private fun updateAchievementTrackingToggle() {
        val session = achievementsSession
        if (session == null || !session.isActive()) {
            achievementTrackingToggleButton.visibility = View.GONE
            return
        }
        achievementTrackingToggleButton.visibility = View.VISIBLE
        achievementTrackingToggleButton.text = getString(
            if (session.isTrackingEnabled()) R.string.quick_menu_achievement_tracking_on
            else R.string.quick_menu_achievement_tracking_off
        )
    }

    private fun onAchievementTrackingToggleClicked() {
        val session = achievementsSession ?: return
        val nowEnabled = !session.isTrackingEnabled()
        session.setTrackingEnabled(nowEnabled)
        Toast.makeText(
            this,
            if (nowEnabled) R.string.quick_menu_achievement_tracking_resumed_toast
            else R.string.quick_menu_achievement_tracking_paused_toast,
            Toast.LENGTH_SHORT
        ).show()
        quickMenuPanel.visibility = View.GONE
    }

    private fun updateFastForwardToggleLabel() {
        fastForwardToggleButton.text = getString(
            if (fastForwardEngaged) R.string.quick_menu_fast_forward_on else R.string.quick_menu_fast_forward_off
        )
    }

    private fun onFastForwardToggleClicked() {
        setFastForwardEngaged(!fastForwardEngaged)
        quickMenuPanel.visibility = View.GONE
    }

    private fun onInfoClicked() {
        quickMenuPanel.visibility = View.GONE
        val system = currentGameSystem ?: return
        val core = currentCore ?: system.selectedCore(this)
        val romName = currentRomIdentifier ?: getString(R.string.quick_menu_info_unknown_rom)
        val elapsedText = formatElapsedTime(System.currentTimeMillis() - sessionStartTimeMs)
        AlertDialog.Builder(this)
            .setTitle(R.string.quick_menu_info)
            .setMessage(getString(R.string.quick_menu_info_message, core.displayName, system.shortLabel, romName, elapsedText))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun formatElapsedTime(elapsedMs: Long): String {
        val totalSeconds = elapsedMs / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
        }
    }

    private fun updateSlotLabels() {
        val romId = currentRomIdentifier ?: return
        val dateFormat = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
        for (slot in 1..SaveStateManager.SLOT_COUNT) {
            val lastModified = SaveStateManager.lastModified(this, romId, slot)
            val label = slotLabels[slot - 1]
            val loadButton = slotLoadButtons[slot - 1]
            if (lastModified != null) {
                label.text = getString(R.string.quick_menu_slot_saved, slot, dateFormat.format(Date(lastModified)))
                loadButton.isEnabled = true
                loadButton.alpha = 1f
            } else {
                label.text = getString(R.string.quick_menu_slot_empty, slot)
                loadButton.isEnabled = false
                loadButton.alpha = 0.4f
            }
        }
    }

    private fun onSaveSlot(slot: Int) {
        val romId = currentRomIdentifier ?: return
        val view = retroView ?: return
        try {
            SaveStateManager.save(this, romId, slot, view.serializeState())
            updateSlotLabels()
        } catch (e: Exception) {
            Toast.makeText(this, R.string.quick_menu_save_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun onLoadSlot(slot: Int) {
        val romId = currentRomIdentifier ?: return
        val data = SaveStateManager.load(this, romId, slot) ?: return
        val ok = retroView?.unserializeState(data) ?: false
        if (!ok) {
            Toast.makeText(this, R.string.quick_menu_load_failed, Toast.LENGTH_SHORT).show()
        }
        quickMenuPanel.visibility = View.GONE
    }

    /** A plain retroView.reset() (retro_reset(), the console's own reset button) doesn't
     *  re-read core-option/renderer variables -- those are only applied once, in
     *  setupRetroView, when a game is freshly loaded. Since a PS1 reset already reboots
     *  through the BIOS anyway (no faster than a fresh load), there's no real cost to doing
     *  a full reload here instead -- and it's the only way "Reset" actually picks up
     *  Settings changes made mid-session, matching what switching ROMs away and back
     *  already did (see EXTRA_FORCE_RELOAD_ROM_URI/initRomFlow). Uses the same recreate()
     *  path as switching ROMs rather than tearing down/rebuilding retroView in place --
     *  see the libraryLauncher comment on why that's unsafe (native singleton, SIGSEGV).
     */
    private fun onResetClicked() {
        val uri = currentRomUri ?: return
        quickMenuPanel.visibility = View.GONE
        setIntent(Intent(intent).putExtra(EXTRA_FORCE_RELOAD_ROM_URI, uri.toString()))
        recreate()
    }

    private fun onExitToLibraryClicked() {
        quickMenuPanel.visibility = View.GONE
        openLibrary()
    }

    /** Only ever called with no existing retroView — switching an active game recreates the Activity instead (see libraryLauncher). */
    private fun setupRetroView(system: GameSystem, gameFile: VirtualFile) {
        sessionStartTimeMs = System.currentTimeMillis()
        val core = currentCore ?: system.selectedCore(this)
        // BIOS-needing systems get a dedicated staging directory containing only the one
        // user-selected BIOS file, instead of the whole multi-file library -- see
        // BiosManager's doc comment for why (a core scanning a directory with several valid
        // BIOS files has no way to know which one the user actually wants). Other systems
        // are unaffected, same filesDir as before.
        val systemDir = if (system.needsBios) {
            BiosManager.prepareActiveBios(this, system)
            BiosManager.activeBiosDirectory(this).absolutePath
        } else {
            filesDir.absolutePath
        }
        val data = GLRetroViewData(this).apply {
            coreFilePath = core.fileName
            gameVirtualFiles = listOf(gameFile)
            systemDirectory = systemDir
            savesDirectory = filesDir.absolutePath
            shader = TacoBoyPrefs.getShaderChoice(this@TacoBoyActivity, system).toConfig()
            integerScale = TacoBoyPrefs.isIntegerScaleEnabled(this@TacoBoyActivity)
            rumbleEventsEnabled = true
            preferLowLatencyAudio = TacoBoyPrefs.isLowLatencyAudioEnabled(this@TacoBoyActivity)
            variables = buildList {
                core.rendererOptionKey?.let { key ->
                    add(Variable(key, core.selectedRenderer(this@TacoBoyActivity, system)))
                }
                CoreOptions.forSelectedCore(this@TacoBoyActivity, system).forEach { option ->
                    add(Variable(option.key, CoreOptions.currentValue(this@TacoBoyActivity, system, option)))
                }
            }.toTypedArray()
            // Restores the in-game (cart battery) save, not a save-state slot — see
            // SramManager. GLRetroView applies this itself right after the game loads,
            // before the first frame renders (GLRetroView.initializeCore).
            if (TacoBoyPrefs.isAutoSaveSramEnabled(this@TacoBoyActivity)) {
                saveRAMState = currentRomIdentifier?.let { SramManager.load(this@TacoBoyActivity, it) }
            }
        }

        val newRetroView = GLRetroView(this, data)
        retroView = newRetroView
        lifecycle.addObserver(newRetroView)

        gameContainer.addView(newRetroView)
        newRetroView.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )

        // Only a real rendered frame proves the core survived loading — loadRom()
        // returning true just means setupRetroView() didn't throw synchronously,
        // which a load-time native crash (see beginRomLoad) happens moments after.
        lifecycleScope.launch {
            newRetroView.getGLRetroEvents().first { it is GLRetroView.GLRetroEvents.FrameRendered }
            TacoBoyPrefs.endRomLoad(this@TacoBoyActivity)
        }

        // Checked once per game load, same "takes effect next load" convention as
        // renderer/audio-latency options -- toggling this in Settings mid-session
        // doesn't retroactively start/stop the overlay for the game already running.
        if (TacoBoyPrefs.isShowFpsEnabled(this)) {
            fpsOverlay.visibility = View.VISIBLE
            // Intervals, not just a count. An average of 60 is equally consistent with a
            // steady 60 and with 70 fast frames plus a 200ms stall, and only the second one
            // is what anybody actually feels -- so the worst frame of each second is kept
            // alongside the count. Nanos from elapsedRealtime because it is monotonic;
            // System.currentTimeMillis can step sideways under a clock sync mid-measurement.
            var renderedFrames = 0
            var worstFrameNs = 0L
            var previousFrameNs = 0L
            lifecycleScope.launch {
                newRetroView.getGLRetroEvents().collect { event ->
                    if (event !is GLRetroView.GLRetroEvents.FrameRendered) return@collect
                    renderedFrames++
                    val now = SystemClock.elapsedRealtimeNanos()
                    if (previousFrameNs != 0L) {
                        val interval = now - previousFrameNs
                        if (interval > worstFrameNs) worstFrameNs = interval
                    }
                    previousFrameNs = now
                }
            }
            lifecycleScope.launch {
                while (true) {
                    delay(1000)
                    val worstMs = worstFrameNs / 1_000_000.0
                    fpsOverlay.text =
                        getString(R.string.fps_overlay_format, renderedFrames, worstMs)
                    // Logged as well as shown, so a profiling run can be driven and read
                    // from adb instead of by watching the corner of the screen. One line a
                    // second, only while Show FPS is on. See roadmap 3.3.
                    TacoBoyLog.d(
                        TAG,
                        "perf system=${currentGameSystem?.shortLabel}" +
                            " core=${currentCore?.fileName}" +
                            " shader=${currentGameSystem?.let { TacoBoyPrefs.getShaderChoice(this@TacoBoyActivity, it).name }}" +
                            " fps=$renderedFrames worstFrameMs=${"%.1f".format(worstMs)}"
                    )
                    renderedFrames = 0
                    worstFrameNs = 0
                }
            }
        }

        // Live RetroAchievements tracking -- always on when logged in, no separate toggle
        // (see AchievementsSession doc comment). A separate launch from the one above:
        // that one is a one-shot crash-loop-breaker signal, this needs its own independent
        // await-then-long-lived-subscription.
        lifecycleScope.launch {
            newRetroView.getGLRetroEvents().first { it is GLRetroView.GLRetroEvents.FrameRendered }
            val identifier = currentRomIdentifier ?: return@launch
            val uri = currentRomUri ?: return@launch
            val gameSystem = currentGameSystem ?: return@launch
            val session = AchievementsSession(this@TacoBoyActivity, this@TacoBoyActivity, newRetroView)
            achievementsSession = session
            session.start(RomLibrary.RomEntry(identifier, uri), gameSystem)
        }

        // Previously unwired entirely — a load failure (bad dump, unsupported
        // format, or for PS1 specifically a missing/invalid BIOS) failed
        // completely silently, no crash but no feedback either.
        lifecycleScope.launch {
            newRetroView.getGLRetroErrors().collect {
                val message = if (system.needsBios) {
                    R.string.game_load_failed_needs_bios
                } else {
                    R.string.game_load_failed_generic
                }
                Toast.makeText(this@TacoBoyActivity, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Deliberately does NOT give GLRetroView view focus (see setupRetroView) — that
     * would hand key dispatch to its own hardcoded GamepadsManager swap, which isn't
     * user-configurable. TacoBoyActivity owns key handling itself instead, applying
     * ControllerBindings' per-system, user-editable table (Settings > Controller).
     *
     * Also swallows one confirmed-spurious case: the Pocket Taco's B button fires
     * both KEYCODE_BUTTON_B *and* KEYCODE_BACK from the same physical press, every
     * time (confirmed via adb logcat against the real device — not a mislabeled
     * button, one press genuinely produces two keycodes). Left alone, the BACK half
     * hits Android's default back-navigation and minimizes the whole app on every B
     * press. The phone's own back gesture/button arrives from a different device, so
     * only the Taco's duplicate gets swallowed.
     */
    /**
     * Points the on-screen pad at this system and hands its output straight to the core.
     *
     * Both analog sticks are offered on PS1 only — it is the one supported system whose real
     * controller had them, and they are the reason the pad unlocks anything the Pocket Taco
     * physically cannot play: the left for movement, the right for the camera and aiming that
     * later PS1 games put there. Note that a game also has to be *in* analog mode for either
     * to do anything: that is the core's own "Analog Mode on Boot" option in Settings >
     * System, not something this turns on, since forcing it breaks games that only handle the
     * digital pad.
     */
    private fun setupTouchControls(system: GameSystem) {
        touchControls.configure(
            system,
            withSticks = system == GameSystem.PS1,
            savedLayout = TouchLayouts.load(this, system),
            globalScale = TacoBoyPrefs.getTouchControlScale(this),
        )
        touchControls.hapticStrength = TacoBoyPrefs.getHapticStrength(this)
        // Written on each drop rather than on leaving edit mode, so a layout can't be lost by
        // the app being killed with the editor still open.
        touchControls.onLayoutChanged = { positions ->
            TouchLayouts.save(this, system, positions)
        }
        touchControls.onKey = { action, keyCode -> retroView?.sendKeyEvent(action, keyCode) }
        touchControls.onAnalog = { stick, x, y ->
            val source = when (stick) {
                TouchControls.Stick.LEFT -> GLRetroView.MOTION_SOURCE_ANALOG_LEFT
                TouchControls.Stick.RIGHT -> GLRetroView.MOTION_SOURCE_ANALOG_RIGHT
            }
            retroView?.sendMotionEvent(source, x, y)
        }
        applyTouchControlsVisibility(TacoBoyPrefs.isTouchControlsEnabled(this))
    }

    private fun onTouchControlsToggled() {
        val enabled = !TacoBoyPrefs.isTouchControlsEnabled(this)
        TacoBoyPrefs.setTouchControlsEnabled(this, enabled)
        applyTouchControlsVisibility(enabled)
        Toast.makeText(
            this,
            getString(
                if (enabled) R.string.touch_controls_on else R.string.touch_controls_off
            ),
            Toast.LENGTH_SHORT
        ).show()
    }

    /**
     * Edit mode lives in the quick menu rather than behind a long-press on the pad itself:
     * a long-press is invisible until you already know about it, and the pad's whole surface
     * is otherwise busy playing the game.
     */
    private fun onEditLayoutToggled() {
        val editing = !touchControls.editMode
        touchControls.editMode = editing
        editLayoutButton.setText(
            if (editing) R.string.quick_menu_edit_layout_done
            else R.string.quick_menu_edit_layout
        )
        // The menu would cover the very controls being dragged.
        if (editing) quickMenuPanel.visibility = View.GONE
        if (!editing) {
            Toast.makeText(this, R.string.touch_controls_layout_saved, Toast.LENGTH_SHORT).show()
        }
    }

    private fun onResetLayoutClicked() {
        touchControls.resetLayout()
        Toast.makeText(this, R.string.touch_controls_layout_reset, Toast.LENGTH_SHORT).show()
    }

    private fun applyTouchControlsVisibility(enabled: Boolean) {
        // Anything still held has to be let go on the way out, or the core keeps seeing the
        // press for the rest of the session.
        if (!enabled) {
            touchControls.releaseEverything()
            // Editing a pad that isn't on screen would leave the menu row saying "Done".
            touchControls.editMode = false
            editLayoutButton.setText(R.string.quick_menu_edit_layout)
        }
        touchControls.visibility = if (enabled) View.VISIBLE else View.GONE
        editLayoutButton.visibility = if (enabled) View.VISIBLE else View.GONE
        resetLayoutButton.visibility = if (enabled) View.VISIBLE else View.GONE
        touchControlsButton.alpha = if (enabled) 1.0f else 0.5f
        // With the pad showing, the boundary can no longer be dragged down to a sliver --
        // the controls would end up unusably small rather than merely hidden.
        boundaryController.setMaxPercentOverride(
            if (enabled) TOUCH_CONTROLS_MAX_BOUNDARY else null
        )
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (PocketTacoDetector.isSpuriousBack(keyCode, event)) return true
        if (handleFastForwardKey(keyCode, event)) return true
        if (handleTurboKey(keyCode, event)) return true
        val mapped = currentGameSystem?.let { ControllerBindings.resolveTarget(this, it, keyCode) }
        if (mapped != null) {
            // Held before it is sent: the OS repeats a held key every ~30-40ms, and every one
            // of those repeats arrives here as another ACTION_DOWN. Recording it first means
            // turbo can pick up a button that was already down when it engaged.
            if (heldTargets.add(mapped) || !turboAppliesTo(mapped)) {
                retroView?.sendKeyEvent(event.action, mapped)
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (PocketTacoDetector.isSpuriousBack(keyCode, event)) return true
        if (handleFastForwardKey(keyCode, event)) return true
        if (handleTurboKey(keyCode, event)) return true
        val mapped = currentGameSystem?.let { ControllerBindings.resolveTarget(this, it, keyCode) }
        if (mapped != null) {
            heldTargets.remove(mapped)
            // Sent unconditionally, even mid-turbo-pulse: the release is what stops the core
            // seeing the button, and skipping it because the pulser happens to be in its
            // "up" half would strand the press on the next pulse.
            retroView?.sendKeyEvent(event.action, mapped)
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    // --- Turbo (rapid fire) ------------------------------------------------

    /** RetroPad targets currently held on a physical controller, so turbo knows what to
     *  repeat -- including buttons that were already down before it engaged. */
    private val heldTargets = mutableSetOf<Int>()

    private var turboEngaged = false
    private var turboPulseDown = false
    private val turboHandler = Handler(Looper.getMainLooper())

    /** Counts full press/release cycles so the rate actually delivered can be logged and
     *  compared against the configured one -- "turbo feels wrong" is otherwise a report with
     *  nothing behind it. Summarised once a second, not once a pulse. */
    private var turboCycles = 0
    private var turboRateLoggedAtMs = 0L

    /**
     * The set turbo is willing to repeat: face buttons and shoulders.
     *
     * Never the D-pad. A repeating direction is not rapid fire, it is a stutter -- the core
     * sees the stick centre between every pulse, so the character stops dead ten times a
     * second. Never Start/Select or L3/R3 either: nothing is improved by opening a menu ten
     * times a second, and the harm if it happens by accident is real.
     */
    private val turboTargets = setOf(
        ControllerBindings.Target.A.keyCode, ControllerBindings.Target.B.keyCode,
        ControllerBindings.Target.X.keyCode, ControllerBindings.Target.Y.keyCode,
        ControllerBindings.Target.L1.keyCode, ControllerBindings.Target.R1.keyCode,
        ControllerBindings.Target.L2.keyCode, ControllerBindings.Target.R2.keyCode,
    )

    private fun turboAppliesTo(targetKeyCode: Int) =
        turboEngaged && targetKeyCode in turboTargets

    /**
     * Returns true if this event was the bound turbo key — fully handled here and never
     * forwarded to the core, exactly like the fast-forward key, and with the same accepted
     * consequence: binding one physical button to both turbo and a RetroPad target means
     * turbo wins and the target becomes unreachable. Fast-forward is checked first, so a key
     * bound to both of those is fast-forward.
     *
     * Turbo is a physical-controller feature. The on-screen pad sends its presses straight
     * through TouchControls.onKey without going through here, and has no spare button to bind
     * a modifier to anyway.
     */
    private fun handleTurboKey(keyCode: Int, event: KeyEvent): Boolean {
        val mode = TacoBoyPrefs.getTurboMode(this)
        if (mode == TurboMode.OFF) return false
        val system = currentGameSystem ?: return false
        val boundKey = TacoBoyPrefs.getTurboKeyCode(this, system) ?: return false
        if (keyCode != boundKey) return false

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                // Same reason fast-forward ignores repeats: the OS auto-repeat of a held key
                // would flip Toggle on and off every few tens of milliseconds.
                if (event.repeatCount == 0) {
                    when (mode) {
                        TurboMode.HOLD -> setTurboEngaged(true)
                        TurboMode.TOGGLE -> setTurboEngaged(!turboEngaged)
                        TurboMode.OFF -> Unit
                    }
                }
            }
            KeyEvent.ACTION_UP -> if (mode == TurboMode.HOLD) setTurboEngaged(false)
        }
        return true
    }

    /**
     * Drops turbo state without handing anything back to the core -- for going into the
     * background, where [setTurboEngaged]'s re-press would be exactly wrong: the keys are not
     * really held any more (their release will be delivered to whatever has focus next, not
     * here), so re-asserting them would leave a button stuck down in the paused game.
     */
    private fun abandonTurbo() {
        turboHandler.removeCallbacksAndMessages(null)
        turboEngaged = false
        // Goes through the field rather than setTurboEngaged, so hide the badge by hand --
        // otherwise backgrounding mid-turbo leaves it on screen with nothing behind it.
        if (::turboIndicator.isInitialized) turboIndicator.visibility = View.GONE
        turboPulseDown = false
        heldTargets.clear()
    }

    private fun setTurboEngaged(engaged: Boolean) {
        if (turboEngaged == engaged) return
        turboEngaged = engaged
        // Held-mode turbo is self-evident while you are holding the key; Toggle mode is not,
        // and a pad silently left in rapid fire is the kind of thing you discover by losing.
        turboIndicator.visibility = if (engaged) View.VISIBLE else View.GONE
        // Debug level: turbo is invisible except in its effect on the game, so when someone
        // reports "turbo isn't working" the first question is whether it engaged at all.
        TacoBoyLog.d(TAG, "Turbo ${if (engaged) "engaged" else "released"}, held=$heldTargets")
        if (engaged) {
            turboPulseDown = true
            turboCycles = 0
            turboRateLoggedAtMs = 0L
            scheduleTurboPulse()
        } else {
            turboHandler.removeCallbacksAndMessages(null)
            // Whatever is still physically held has to be handed back to the core as a plain
            // press. Disengaging in the pulser's "up" half would otherwise leave a button the
            // user is still holding reading as released until they let go and press again.
            heldTargets.filter { it in turboTargets }.forEach {
                retroView?.sendKeyEvent(KeyEvent.ACTION_DOWN, it)
            }
        }
    }

    /**
     * One half-cycle per post: down for half the period, up for the other half. A press and
     * its release have to land on separate emulated frames to be seen at all, which is what
     * puts the ceiling on TacoBoyPrefs.TURBO_RATE_MAX.
     */
    private fun scheduleTurboPulse() {
        val halfPeriodMs = (1000L / TacoBoyPrefs.getTurboRate(this) / 2).coerceAtLeast(1L)
        turboHandler.postDelayed({
            if (!turboEngaged) return@postDelayed
            val action = if (turboPulseDown) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP
            val pulsed = heldTargets.filter { it in turboTargets }
            pulsed.forEach { retroView?.sendKeyEvent(action, it) }
            // One cycle is a press and its release, so count on the release half only.
            if (!turboPulseDown && pulsed.isNotEmpty()) turboCycles++
            val now = SystemClock.elapsedRealtime()
            if (turboRateLoggedAtMs == 0L) turboRateLoggedAtMs = now
            if (now - turboRateLoggedAtMs >= 1000L) {
                val rate = turboCycles * 1000.0 / (now - turboRateLoggedAtMs)
                TacoBoyLog.d(
                    TAG,
                    "Turbo firing ${"%.1f".format(rate)}/s (configured " +
                        "${TacoBoyPrefs.getTurboRate(this@TacoBoyActivity)}/s) targets=$pulsed"
                )
                turboCycles = 0
                turboRateLoggedAtMs = now
            }
            turboPulseDown = !turboPulseDown
            scheduleTurboPulse()
        }, halfPeriodMs)
    }

    /**
     * Returns true if this event was the bound fast-forward key -- fully handled here,
     * never forwarded to the core as a RetroPad button. A physical key can only do one
     * job: binding the same key to both fast-forward and a RetroPad target means
     * fast-forward silently wins here, before ControllerBindings.resolveTarget ever
     * runs -- an accepted edge case, not something this UI tries to prevent yet.
     *
     * Uses GLRetroView.frameSpeed -- an existing libretrodroid field (native
     * LibretroDroid::setFrameSpeed) that multiplies both core steps-per-frame and audio
     * playback speed, previously never called from this app at all.
     */
    private fun handleFastForwardKey(keyCode: Int, event: KeyEvent): Boolean {
        val mode = TacoBoyPrefs.getFastForwardMode(this)
        if (mode == FastForwardMode.OFF) return false
        val boundKey = TacoBoyPrefs.getFastForwardKeyCode(this) ?: return false
        if (keyCode != boundKey) return false

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                // OS auto-repeat while held would otherwise flip Toggle mode back and
                // forth every ~30-40ms and redundantly re-engage Hold mode.
                if (event.repeatCount == 0) {
                    when (mode) {
                        FastForwardMode.HOLD -> setFastForwardEngaged(true)
                        FastForwardMode.TOGGLE -> setFastForwardEngaged(!fastForwardEngaged)
                        FastForwardMode.OFF -> Unit
                    }
                }
            }
            KeyEvent.ACTION_UP -> {
                if (mode == FastForwardMode.HOLD) setFastForwardEngaged(false)
            }
        }
        return true
    }

    private fun setFastForwardEngaged(engaged: Boolean) {
        fastForwardEngaged = engaged
        retroView?.frameSpeed = if (engaged) FAST_FORWARD_SPEED else 1
        // Same reason turbo logs its state: fast-forward is invisible except in its effect,
        // so "did it actually engage?" is the first question when it appears not to work --
        // and the profiling harness asserts on this line before it measures anything (see
        // PROFILING.md, where not verifying the running config invalidated an entire run).
        TacoBoyLog.d(TAG, "Fast-forward ${if (engaged) "engaged" else "released"} speed=${retroView?.frameSpeed}")
    }

    /* D-pad hat axis and both analog sticks — not currently user-remappable (D-pad
       always means D-pad), unlike face/shoulder buttons which go through
       ControllerBindings above. GLRetroView has its own onGenericMotionEvent doing
       the same thing, but it never consumes the event (always returns false), so it
       would double-forward if GLRetroView ever held focus — it deliberately doesn't
       (see setupRetroView), so this is the only path. */
    override fun onGenericMotionEvent(event: MotionEvent?): Boolean {
        if (event != null) {
            sendMotionEvent(event, GLRetroView.MOTION_SOURCE_DPAD, MotionEvent.AXIS_HAT_X, MotionEvent.AXIS_HAT_Y)
            sendMotionEvent(event, GLRetroView.MOTION_SOURCE_ANALOG_LEFT, MotionEvent.AXIS_X, MotionEvent.AXIS_Y)
            sendMotionEvent(event, GLRetroView.MOTION_SOURCE_ANALOG_RIGHT, MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ)
        }
        return super.onGenericMotionEvent(event)
    }

    private fun sendMotionEvent(event: MotionEvent, source: Int, xAxis: Int, yAxis: Int) {
        retroView?.sendMotionEvent(source, event.getAxisValue(xAxis), event.getAxisValue(yAxis))
    }
}
