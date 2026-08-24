package com.tacoboy

import android.content.Context

/**
 * Persists the black-zone boundary as a fraction of screen height.
 * Stored per-device (not per-game) since it reflects where the physical
 * GameSir Pocket Taco clamp sits on this specific phone.
 */
object TacoBoyPrefs {
    const val MIN_PERCENT = 0.3f
    const val MAX_PERCENT = 0.95f

    /**
     * Bounds for the global on-screen control size. The floor is set by what stays hittable at
     * all; the ceiling by what still fits side by side.
     *
     * Set so that *every* layout still fits at the top of the range, rather than the roomiest
     * one -- a slider that can break the pad it is sizing is not worth the extra few percent.
     * Two different pads bind it: Game Boy / GBA / Lynx are the widest (a big D-pad opposite a
     * two-button cluster needs about 0.73 of the zone's width, so they run out of room past
     * ~1.3x), and PS1 is the tallest (four stacked rows, whose diamond meets the stick below
     * it at ~1.2x). Measured on device, not guessed. Anyone wanting one control bigger than
     * this allows can scale that control on its own in edit mode, where the consequence is
     * visible while they do it. See TouchControls.layoutControls.
     */
    const val TOUCH_CONTROL_SCALE_MIN = 0.6f
    const val TOUCH_CONTROL_SCALE_MAX = 1.2f

    private const val PREFS_NAME = "tacoboy_prefs"
    private const val KEY_BOUNDARY_PERCENT = "boundary_percent"
    private const val KEY_ROM_FOLDER_URI_PREFIX = "rom_folder_uri_"
    private const val KEY_LAST_ROM_URI = "last_rom_uri"
    private const val KEY_LIBRARY_VIEW_MODE = "library_view_mode"
    private const val KEY_LAST_SYSTEM = "last_system"
    private const val KEY_HIDDEN_ROMS = "hidden_roms"
    private const val KEY_CUSTOM_TITLE_PREFIX = "custom_title:"
    private const val KEY_LOAD_IN_PROGRESS = "load_in_progress"
    private const val KEY_CRASH_STREAK = "crash_streak"
    private const val KEY_RENDERER_CHOICE_PREFIX = "renderer_choice_"
    private const val KEY_CORE_CHOICE_PREFIX = "core_choice_"
    private const val KEY_BOUNDARY_HINT_SHOWN = "boundary_hint_shown"
    private const val KEY_LAST_PLAYED_PREFIX = "last_played:"
    private const val KEY_PLAY_COUNT_PREFIX = "play_count:"
    private const val KEY_LIBRARY_SORT_MODE = "library_sort_mode"
    private const val KEY_BUTTON_BINDING_PREFIX = "button_binding_"
    private const val KEY_AUTO_SAVE_SRAM = "auto_save_sram"
    private const val KEY_POCKET_TACO_AUTO_BIND = "pocket_taco_auto_bind"
    private const val KEY_INTEGER_SCALE = "integer_scale"
    private const val KEY_TOUCH_CONTROLS = "touch_controls"
    private const val KEY_TOUCH_HAPTIC_STRENGTH = "touch_haptic_strength"
    private const val KEY_TOUCH_CONTROL_SCALE = "touch_control_scale"
    private const val KEY_DEFAULT_CONTROLLER_PRESET = "default_controller_preset"
    private const val KEY_LOW_LATENCY_AUDIO = "low_latency_audio"
    private const val KEY_CORE_OPTION_PREFIX = "core_option_"
    private const val KEY_HARDCORE_MODE = "hardcore_mode"
    private const val KEY_FAST_FORWARD_MODE = "fast_forward_mode"
    private const val KEY_FAST_FORWARD_KEYCODE = "fast_forward_keycode"
    private const val KEY_TURBO_MODE = "turbo_mode"
    private const val KEY_TURBO_RATE = "turbo_rate"
    private const val KEY_TURBO_KEYCODE_PREFIX = "turbo_keycode_"
    private const val KEY_SHOW_FPS = "show_fps"
    private const val KEY_RESUME_ON_LAUNCH = "resume_on_launch"
    private const val KEY_RA_USERNAME = "ra_username"
    private const val KEY_RA_API_KEY = "ra_api_key"
    private const val KEY_RA_SESSION_USERNAME = "ra_session_username"
    private const val KEY_RA_SESSION_TOKEN = "ra_session_token"
    private const val KEY_LOG_LEVEL = "log_level"
    private const val KEY_ACTIVE_BIOS_PREFIX = "active_bios_"
    private const val KEY_SHADER_CHOICE_PREFIX = "shader_choice_"
    private const val DEFAULT_BOUNDARY_PERCENT = 0.65f
    private const val NO_BINDING = Int.MIN_VALUE

    /** Turbo speed bounds, in presses per second. The floor is where it stops being faster
     *  than tapping by hand; the ceiling is set by the emulated hardware rather than by us --
     *  past about 15/s a 60Hz core starts missing presses entirely, because a press and its
     *  release have to land on separate frames to be seen at all. 10 is the usual default in
     *  hardware turbo pads and is comfortably inside that. */
    const val TURBO_RATE_MIN = 4
    const val TURBO_RATE_MAX = 15
    private const val DEFAULT_TURBO_RATE = 10

    fun getBoundaryPercent(context: Context): Float {
        return prefs(context).getFloat(KEY_BOUNDARY_PERCENT, DEFAULT_BOUNDARY_PERCENT)
    }

    fun setBoundaryPercent(context: Context, percent: Float) {
        prefs(context).edit()
            .putFloat(KEY_BOUNDARY_PERCENT, percent.coerceIn(MIN_PERCENT, MAX_PERCENT))
            .apply()
    }

    /** Each GameSystem remembers its own folder, so switching systems in the library is instant. */
    fun getRomFolderUri(context: Context, system: GameSystem): String? {
        return prefs(context).getString(KEY_ROM_FOLDER_URI_PREFIX + system.name, null)
    }

    fun setRomFolderUri(context: Context, system: GameSystem, uri: String?) {
        prefs(context).edit().putString(KEY_ROM_FOLDER_URI_PREFIX + system.name, uri).apply()
    }

    fun getLastSystem(context: Context): GameSystem? {
        val stored = prefs(context).getString(KEY_LAST_SYSTEM, null) ?: return null
        return try {
            GameSystem.valueOf(stored)
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    fun setLastSystem(context: Context, system: GameSystem) {
        prefs(context).edit().putString(KEY_LAST_SYSTEM, system.name).apply()
    }

    fun getLastRomUri(context: Context): String? {
        return prefs(context).getString(KEY_LAST_ROM_URI, null)
    }

    fun setLastRomUri(context: Context, uri: String?) {
        prefs(context).edit().putString(KEY_LAST_ROM_URI, uri).apply()
    }

    fun getLibraryViewMode(context: Context): RomLibraryActivity.ViewMode {
        val stored = prefs(context).getString(KEY_LIBRARY_VIEW_MODE, null)
        return if (stored == RomLibraryActivity.ViewMode.GRID.name) {
            RomLibraryActivity.ViewMode.GRID
        } else {
            RomLibraryActivity.ViewMode.LIST
        }
    }

    fun setLibraryViewMode(context: Context, mode: RomLibraryActivity.ViewMode) {
        prefs(context).edit().putString(KEY_LIBRARY_VIEW_MODE, mode.name).apply()
    }

    /** Soft-hide only — no delete, no "unhide" UI yet either (reversible only by clearing app data for now). */
    fun getHiddenRoms(context: Context): Set<String> {
        return prefs(context).getStringSet(KEY_HIDDEN_ROMS, emptySet()) ?: emptySet()
    }

    fun setHidden(context: Context, romUri: String, hidden: Boolean) {
        val updated = getHiddenRoms(context).toMutableSet()
        if (hidden) updated.add(romUri) else updated.remove(romUri)
        // SharedPreferences forbids mutating a returned Set in place; always write a fresh copy.
        prefs(context).edit().putStringSet(KEY_HIDDEN_ROMS, HashSet(updated)).apply()
    }

    /** Display-only override — never touches the identifier used for box art/save-state/core lookup. */
    fun getCustomTitle(context: Context, romUri: String): String? {
        return prefs(context).getString(KEY_CUSTOM_TITLE_PREFIX + romUri, null)
    }

    fun setCustomTitle(context: Context, romUri: String, title: String?) {
        prefs(context).edit().putString(KEY_CUSTOM_TITLE_PREFIX + romUri, title?.ifBlank { null }).apply()
    }

    /**
     * Crash-loop breaker: two native crashes were found this session
     * (fdsan double-close, FPSSync null deref), both during ROM load and
     * both process-killing — unrecoverable in-process, and Java's
     * uncaught-exception handler can't see them at all. Without this, a
     * ROM that crashes on load would crash the app again on every
     * subsequent launch too, since initRomFlow() auto-resumes
     * lastRomUri unconditionally — no way out short of clearing app data.
     *
     * beginRomLoad uses commit() (synchronous), not apply(), because a
     * process-killing crash can happen within milliseconds of calling
     * loadRom() — an async apply() write might never reach disk before
     * the process dies, which would defeat the whole point.
     */
    fun beginRomLoad(context: Context, romUri: String) {
        prefs(context).edit().putString(KEY_LOAD_IN_PROGRESS, romUri).commit()
    }

    fun endRomLoad(context: Context) {
        prefs(context).edit().remove(KEY_LOAD_IN_PROGRESS).apply()
    }

    fun getCrashedRomUri(context: Context): String? {
        return prefs(context).getString(KEY_LOAD_IN_PROGRESS, null)
    }

    /**
     * How many times in a row the app has died within seconds of starting.
     *
     * The crash-loop breaker above catches the specific case of one ROM killing the core
     * during load. This catches the general one: anything that throws early enough that
     * restarting straight back into the same state just does it again -- which the restart
     * in TacoBoyApplication would otherwise do forever, since it deliberately relaunches
     * rather than showing Android's "app has stopped" dialog.
     *
     * Written with commit() rather than apply(): the only caller is an uncaught-exception
     * handler that kills the process on the next line, and an async write would lose the
     * race it is trying to record.
     */
    fun getCrashStreak(context: Context): Int {
        return prefs(context).getInt(KEY_CRASH_STREAK, 0)
    }

    fun recordStartupCrash(context: Context) {
        prefs(context).edit()
            .putInt(KEY_CRASH_STREAK, getCrashStreak(context) + 1)
            .commit()
    }

    /** Called once the app has stayed up long enough to count as healthy -- see
     *  TacoBoyApplication.STARTUP_WINDOW_MS. */
    fun clearCrashStreak(context: Context) {
        prefs(context).edit().remove(KEY_CRASH_STREAK).apply()
    }

    /** Scoped by (system, coreFileName) rather than just system -- a real bug found
     *  2026-08-16 testing Beetle PSX HW as PS1's second core: a renderer choice saved
     *  under SwanStation ("Software") silently leaked into Beetle's completely different
     *  option-value namespace (lowercase software/hardware_gl/hardware_vk), and Beetle
     *  silently fell back to software since "Software" matched none of its own choices.
     *  Scoping by core avoids that, and as a side benefit each core now remembers its
     *  own renderer choice independently across switches. Null means "use
     *  CoreDefinition.defaultRenderer" — most cores never get a saved value at all. */
    fun getRendererChoice(context: Context, system: GameSystem, coreFileName: String): String? {
        return prefs(context).getString(KEY_RENDERER_CHOICE_PREFIX + system.name + "_" + coreFileName, null)
    }

    fun setRendererChoice(context: Context, system: GameSystem, coreFileName: String, value: String) {
        prefs(context).edit().putString(KEY_RENDERER_CHOICE_PREFIX + system.name + "_" + coreFileName, value).apply()
    }

    /** Removes the saved override entirely (not just resets it to the default value),
     *  so getRendererChoice goes back to returning null / tracking CoreDefinition.defaultRenderer
     *  if that default ever changes later. */
    fun clearRendererChoice(context: Context, system: GameSystem, coreFileName: String) {
        prefs(context).edit().remove(KEY_RENDERER_CHOICE_PREFIX + system.name + "_" + coreFileName).apply()
    }

    /** Null means "use GameSystem.defaultCore" — most systems only have one core and
     *  never get a saved value at all (see PS1 for the one exception). */
    fun getSelectedCoreFileName(context: Context, system: GameSystem): String? {
        return prefs(context).getString(KEY_CORE_CHOICE_PREFIX + system.name, null)
    }

    fun setSelectedCoreFileName(context: Context, system: GameSystem, fileName: String) {
        prefs(context).edit().putString(KEY_CORE_CHOICE_PREFIX + system.name, fileName).apply()
    }

    /** Called once a ROM has actually been handed to the emulator core (see TacoBoyActivity.loadRom) —
     *  drives the library's "Recently Played"/"Most Played" sort modes. */
    fun recordRomPlayed(context: Context, romUri: String) {
        val count = getPlayCount(context, romUri)
        prefs(context).edit()
            .putLong(KEY_LAST_PLAYED_PREFIX + romUri, System.currentTimeMillis())
            .putInt(KEY_PLAY_COUNT_PREFIX + romUri, count + 1)
            .apply()
    }

    /** 0 means "never played" — sorts to the end of a descending "Recently Played" ordering. */
    fun getLastPlayed(context: Context, romUri: String): Long {
        return prefs(context).getLong(KEY_LAST_PLAYED_PREFIX + romUri, 0L)
    }

    fun getPlayCount(context: Context, romUri: String): Int {
        return prefs(context).getInt(KEY_PLAY_COUNT_PREFIX + romUri, 0)
    }

    fun getLibrarySortMode(context: Context): RomLibraryActivity.SortMode {
        val stored = prefs(context).getString(KEY_LIBRARY_SORT_MODE, null)
        return RomLibraryActivity.SortMode.entries.firstOrNull { it.name == stored } ?: RomLibraryActivity.SortMode.NAME
    }

    fun setLibrarySortMode(context: Context, mode: RomLibraryActivity.SortMode) {
        prefs(context).edit().putString(KEY_LIBRARY_SORT_MODE, mode.name).apply()
    }

    /** Null means "use ControllerBindings.defaultSource(target)" — most targets never get a saved override. */
    fun getButtonBindingSource(context: Context, system: GameSystem, target: ControllerBindings.Target): Int? {
        val stored = prefs(context).getInt(bindingKey(system, target), NO_BINDING)
        return if (stored == NO_BINDING) null else stored
    }

    fun setButtonBindingSource(context: Context, system: GameSystem, target: ControllerBindings.Target, sourceKeyCode: Int) {
        prefs(context).edit().putInt(bindingKey(system, target), sourceKeyCode).apply()
    }

    fun clearButtonBindingSource(context: Context, system: GameSystem, target: ControllerBindings.Target) {
        prefs(context).edit().remove(bindingKey(system, target)).apply()
    }

    private fun bindingKey(system: GameSystem, target: ControllerBindings.Target) =
        KEY_BUTTON_BINDING_PREFIX + system.name + "_" + target.name

    /** On by default — off is an escape hatch (Settings > System) for the rare core where
     *  restoring SRAM on load misbehaves, not something most players need to touch. */
    fun isAutoSaveSramEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_AUTO_SAVE_SRAM, true)
    }

    fun setAutoSaveSramEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_SAVE_SRAM, enabled).apply()
    }

    /** On by default — ControllerBindings.DEFAULT_SOURCE is already the confirmed-correct
     *  mapping for this exact hardware (see its doc comment), so resetting to it on every
     *  Pocket Taco connect is safe. Turn off to keep a customized layout or applied preset
     *  across reconnects instead of it being reset back to defaults each time. */
    fun isPocketTacoAutoBindEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_POCKET_TACO_AUTO_BIND, true)
    }

    fun setPocketTacoAutoBindEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_POCKET_TACO_AUTO_BIND, enabled).apply()
    }

    /** Global rather than per-system, unlike the shader/renderer choices: it isn't a matter of
     *  which look suits a console, it's whether this screen should show whole source pixels at
     *  all. Off by default -- it trades a visibly smaller picture for the uniform grid, and
     *  that's only worth it to someone who wants it. See VideoLayout::updateIntegerScale. */
    fun isIntegerScaleEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_INTEGER_SCALE, false)
    }

    fun setIntegerScaleEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_INTEGER_SCALE, enabled).apply()
    }

    /** Whether the on-screen pad is showing. Persisted so it survives leaving a game, but off
     *  by default: this app's whole point is the physical controller, and someone who has one
     *  attached should never see a touch pad they didn't ask for. */
    fun isTouchControlsEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_TOUCH_CONTROLS, false)
    }

    fun setTouchControlsEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_TOUCH_CONTROLS, enabled).apply()
    }

    /** How hard the on-screen pad buzzes on a press -- see HapticStrength. Defaults to Medium
     *  rather than Off: a glass button with no feedback at all is the main thing that makes a
     *  touch pad feel worse than a physical one. OFF is the way to silence it; because this
     *  drives the vibrator directly (the only way to control intensity) the system's own
     *  touch-feedback toggle no longer gates it. */
    fun getHapticStrength(context: Context): HapticStrength {
        return HapticStrength.fromPrefValue(
            prefs(context).getString(KEY_TOUCH_HAPTIC_STRENGTH, null)
        )
    }

    fun setHapticStrength(context: Context, strength: HapticStrength) {
        prefs(context).edit().putString(KEY_TOUCH_HAPTIC_STRENGTH, strength.prefValue).apply()
    }

    /** Base size of every on-screen control, as a multiplier on the computed default layout.
     *  Global rather than per-system: it exists for a physical fact about the user (finger
     *  size, screen size) that doesn't change when they switch console. Per-control tweaks
     *  ride on top of this and are stored per system in TouchLayouts. */
    fun getTouchControlScale(context: Context): Float {
        return prefs(context).getFloat(KEY_TOUCH_CONTROL_SCALE, 1f)
            .coerceIn(TOUCH_CONTROL_SCALE_MIN, TOUCH_CONTROL_SCALE_MAX)
    }

    fun setTouchControlScale(context: Context, scale: Float) {
        prefs(context).edit()
            .putFloat(
                KEY_TOUCH_CONTROL_SCALE,
                scale.coerceIn(TOUCH_CONTROL_SCALE_MIN, TOUCH_CONTROL_SCALE_MAX)
            )
            .apply()
    }

    /** Null means "no default set" — auto-bind falls back to ControllerBindings.resetAllToDefaults.
     *  When set, it names a ControllerPresets entry to apply to every system instead, on each
     *  Pocket Taco connect (see TacoBoyActivity.inputDeviceListener). */
    fun getDefaultControllerPresetName(context: Context): String? {
        return prefs(context).getString(KEY_DEFAULT_CONTROLLER_PRESET, null)
    }

    fun setDefaultControllerPresetName(context: Context, name: String?) {
        prefs(context).edit().putString(KEY_DEFAULT_CONTROLLER_PRESET, name).apply()
    }

    /** On by default — matches this app's original hardcoded behavior (GLRetroViewData.
     *  preferLowLatencyAudio also itself defaults to true) before this became a setting.
     *  Trades a smaller Oboe buffer (see libretrodroid's audio.cpp LOW_LATENCY_SETTINGS,
     *  4 video frames vs the default 8) for lower input-to-sound delay -- an escape hatch
     *  for the rare device/core combination where that smaller buffer underruns audibly. */
    fun isLowLatencyAudioEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_LOW_LATENCY_AUDIO, true)
    }

    fun setLowLatencyAudioEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_LOW_LATENCY_AUDIO, enabled).apply()
    }

    /** Null means "use CoreOptions.Option.default" -- most options never get a saved override.
     *  Stores the raw core-facing value (e.g. "disabled", "Recompiler"), not a display label. */
    fun getCoreOptionValue(context: Context, system: GameSystem, key: String): String? {
        return prefs(context).getString(coreOptionKey(system, key), null)
    }

    fun setCoreOptionValue(context: Context, system: GameSystem, key: String, value: String) {
        prefs(context).edit().putString(coreOptionKey(system, key), value).apply()
    }

    fun clearCoreOptionValue(context: Context, system: GameSystem, key: String) {
        prefs(context).edit().remove(coreOptionKey(system, key)).apply()
    }

    private fun coreOptionKey(system: GameSystem, key: String) = KEY_CORE_OPTION_PREFIX + system.name + "_" + key

    /** Which imported BIOS file (by filename, matching BiosManager.importBios's naming) should
     *  be staged for this system's core to see -- see BiosManager.prepareActiveBios. Null means
     *  "no explicit choice made yet", not "no BIOS available" -- callers fall back to
     *  BiosManager's own default-picking logic. Deliberately NOT cleared by resetAllSettings,
     *  same category as ROM folder grants below -- this is "which file resource is active,"
     *  not a core-behavior setting a user would expect a settings reset to touch. */
    fun getActiveBiosFileName(context: Context, system: GameSystem): String? {
        return prefs(context).getString(KEY_ACTIVE_BIOS_PREFIX + system.name, null)
    }

    fun setActiveBiosFileName(context: Context, system: GameSystem, fileName: String) {
        prefs(context).edit().putString(KEY_ACTIVE_BIOS_PREFIX + system.name, fileName).apply()
    }

    /** Drops this filename as any system's saved choice -- called when the file is deleted
     *  from the library (see BiosManager.deleteBios). Not strictly required for correctness
     *  (resolveActiveFileName already ignores a saved name that's no longer on disk), but it
     *  stops a stale name from silently re-activating if a file with the same name is
     *  imported again later, which would look like the delete didn't take. */
    fun clearActiveBiosFileName(context: Context, fileName: String) {
        val editor = prefs(context).edit()
        GameSystem.entries.forEach { system ->
            if (getActiveBiosFileName(context, system) == fileName) {
                editor.remove(KEY_ACTIVE_BIOS_PREFIX + system.name)
            }
        }
        editor.apply()
    }

    /** RetroAchievements' Hardcore Mode rules forbid save-states (an emulator-only
     *  convenience) but not the SRAM/cart-battery saves every real cartridge/memory
     *  card already has — this only gates the quick menu's save-state slots (see
     *  TacoBoyActivity's save_slots_container), never SramManager/auto-save. Off by
     *  default since it's a restriction, not a default-safe behavior. */
    fun isHardcoreModeEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_HARDCORE_MODE, false)
    }

    fun setHardcoreModeEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_HARDCORE_MODE, enabled).apply()
    }

    fun getFastForwardMode(context: Context): FastForwardMode {
        val stored = prefs(context).getString(KEY_FAST_FORWARD_MODE, null)
        return FastForwardMode.entries.firstOrNull { it.name == stored } ?: FastForwardMode.OFF
    }

    fun setFastForwardMode(context: Context, mode: FastForwardMode) {
        prefs(context).edit().putString(KEY_FAST_FORWARD_MODE, mode.name).apply()
    }

    /** Null means "no key assigned yet" -- deliberately not defaulted to a guessed
     *  physical key (e.g. a Pocket Taco R2) since, unlike ControllerBindings.DEFAULT_SOURCE,
     *  no such default was ever confirmed against real hardware. */
    fun getFastForwardKeyCode(context: Context): Int? {
        val stored = prefs(context).getInt(KEY_FAST_FORWARD_KEYCODE, NO_BINDING)
        return if (stored == NO_BINDING) null else stored
    }

    fun setFastForwardKeyCode(context: Context, keyCode: Int) {
        prefs(context).edit().putInt(KEY_FAST_FORWARD_KEYCODE, keyCode).apply()
    }

    fun clearFastForwardKeyCode(context: Context) {
        prefs(context).edit().remove(KEY_FAST_FORWARD_KEYCODE).apply()
    }

    /** Off by default: turbo changes what a button does, and a button that fires ten times
     *  when pressed once is the sort of surprise nobody should get without asking. */
    fun getTurboMode(context: Context): TurboMode {
        val stored = prefs(context).getString(KEY_TURBO_MODE, null) ?: return TurboMode.OFF
        return TurboMode.entries.firstOrNull { it.name == stored } ?: TurboMode.OFF
    }

    fun setTurboMode(context: Context, mode: TurboMode) {
        prefs(context).edit().putString(KEY_TURBO_MODE, mode.name).apply()
    }

    /** Presses per second while turbo is engaged. Global rather than per system: it is a
     *  property of how fast the user wants to tap, not of the console. */
    fun getTurboRate(context: Context): Int {
        return prefs(context).getInt(KEY_TURBO_RATE, DEFAULT_TURBO_RATE)
            .coerceIn(TURBO_RATE_MIN, TURBO_RATE_MAX)
    }

    fun setTurboRate(context: Context, rate: Int) {
        prefs(context).edit()
            .putInt(KEY_TURBO_RATE, rate.coerceIn(TURBO_RATE_MIN, TURBO_RATE_MAX))
            .apply()
    }

    /**
     * The turbo key is per system, unlike the fast-forward key, because it is a *binding* --
     * it lives in the same per-system list as A and B and competes with them for physical
     * buttons, and which spare button exists differs by system (a Game Boy layout leaves R2
     * free; a PS1 one does not). Null means nothing is bound and turbo cannot engage.
     */
    fun getTurboKeyCode(context: Context, system: GameSystem): Int? {
        val stored = prefs(context).getInt(KEY_TURBO_KEYCODE_PREFIX + system.name, NO_BINDING)
        return if (stored == NO_BINDING) null else stored
    }

    fun setTurboKeyCode(context: Context, system: GameSystem, keyCode: Int) {
        prefs(context).edit().putInt(KEY_TURBO_KEYCODE_PREFIX + system.name, keyCode).apply()
    }

    fun clearTurboKeyCode(context: Context, system: GameSystem) {
        prefs(context).edit().remove(KEY_TURBO_KEYCODE_PREFIX + system.name).apply()
    }

    /** Off by default -- a dev/perf-check tool (see settingslayoutideas.md), not
     *  something most players want cluttering the screen during normal play. */
    fun isShowFpsEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_SHOW_FPS, false)
    }

    fun setShowFpsEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SHOW_FPS, enabled).apply()
    }

    /** On by default -- preserves this app's original hardcoded behavior (initRomFlow
     *  auto-resuming lastRomUri unconditionally) before this became a choice. Off is an
     *  escape hatch for users who'd rather land on the ROM prompt/library every launch. */
    fun isResumeOnLaunchEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_RESUME_ON_LAUNCH, true)
    }

    fun setResumeOnLaunchEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_RESUME_ON_LAUNCH, enabled).apply()
    }

    fun getRetroAchievementsUsername(context: Context): String? {
        return prefs(context).getString(KEY_RA_USERNAME, null)
    }

    /** RA's Web API key, not a session token — there's no login/session concept in this
     *  API (see RetroAchievementsClient); the key itself is the permanent credential every
     *  identification call authenticates with, so it's what gets stored, same as the
     *  username it's paired with. */
    fun getRetroAchievementsApiKey(context: Context): String? {
        return prefs(context).getString(KEY_RA_API_KEY, null)
    }

    fun setRetroAchievementsCredentials(context: Context, username: String, apiKey: String) {
        prefs(context).edit()
            .putString(KEY_RA_USERNAME, username)
            .putString(KEY_RA_API_KEY, apiKey)
            .apply()
    }

    fun clearRetroAchievementsCredentials(context: Context) {
        prefs(context).edit().remove(KEY_RA_USERNAME).remove(KEY_RA_API_KEY).apply()
    }

    /** Separate from the identification username/API key pair above -- live tracking needs
     *  a real login session token (see RetroAchievementsClient's class doc comment for why
     *  the permanent API key isn't enough for this), obtained via a distinct password
     *  login the user does once in Settings' "Live Tracking" section. The password itself
     *  is never stored here or anywhere -- only the token RA's login2 hands back. */
    fun getRetroAchievementsSessionUsername(context: Context): String? {
        return prefs(context).getString(KEY_RA_SESSION_USERNAME, null)
    }

    fun getRetroAchievementsSessionToken(context: Context): String? {
        return prefs(context).getString(KEY_RA_SESSION_TOKEN, null)
    }

    fun setRetroAchievementsSession(context: Context, username: String, token: String) {
        prefs(context).edit()
            .putString(KEY_RA_SESSION_USERNAME, username)
            .putString(KEY_RA_SESSION_TOKEN, token)
            .apply()
    }

    fun clearRetroAchievementsSession(context: Context) {
        prefs(context).edit().remove(KEY_RA_SESSION_USERNAME).remove(KEY_RA_SESSION_TOKEN).apply()
    }

    /** Shared across TacoBoyActivity and RomLibraryActivity — dismissing the hint on either screen retires it everywhere. */
    fun hasSeenBoundaryHint(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_BOUNDARY_HINT_SHOWN, false)
    }

    fun setBoundaryHintShown(context: Context) {
        prefs(context).edit().putBoolean(KEY_BOUNDARY_HINT_SHOWN, true).apply()
    }

    /** ERROR by default -- least noisy. See TacoBoyLog for what actually reads this. */
    fun getLogLevel(context: Context): LogLevel {
        val stored = prefs(context).getString(KEY_LOG_LEVEL, null)
        return LogLevel.entries.firstOrNull { it.name == stored } ?: LogLevel.ERROR
    }

    /** Also pushes the new level into TacoBoyLog's in-memory cache immediately, since log
     *  call sites read that cache rather than SharedPreferences on every call. */
    fun setLogLevel(context: Context, level: LogLevel) {
        prefs(context).edit().putString(KEY_LOG_LEVEL, level.name).apply()
        TacoBoyLog.setLevel(level)
    }

    /** Settings > Advanced's "Reset all settings" -- deliberately curated, not a wholesale
     *  SharedPreferences.clear(). Clears everything exposed as a Settings-screen row
     *  (including per-system/prefixed ones: renderer choice, core option overrides,
     *  button bindings) but leaves RetroAchievements login/session, ROM folder grants,
     *  saved controller presets, and library state (view mode, sort, hidden ROMs, custom
     *  titles, play stats, crash-loop breaker) untouched -- those aren't "settings" a user
     *  would expect a settings reset to silently undo. */
    /** Which display shader this system's picture is drawn through -- see ShaderChoice.
     *  Per-system, same shape as the renderer choice, and cleared by resetAllSettings since
     *  unlike the core/renderer/BIOS choices this is a pure appearance setting. */
    fun getShaderChoice(context: Context, system: GameSystem): ShaderChoice {
        return ShaderChoice.fromPrefValue(
            prefs(context).getString(KEY_SHADER_CHOICE_PREFIX + system.name, null)
        )
    }

    fun setShaderChoice(context: Context, system: GameSystem, choice: ShaderChoice) {
        prefs(context).edit()
            .putString(KEY_SHADER_CHOICE_PREFIX + system.name, choice.prefValue)
            .apply()
    }

    fun resetAllSettings(context: Context) {
        val editor = prefs(context).edit()
        editor.remove(KEY_AUTO_SAVE_SRAM)
        editor.remove(KEY_POCKET_TACO_AUTO_BIND)
        editor.remove(KEY_INTEGER_SCALE)
        editor.remove(KEY_TOUCH_CONTROLS)
        editor.remove(KEY_TOUCH_HAPTIC_STRENGTH)
        // The boolean this replaced, in case a build with it ever ran on this device.
        editor.remove("touch_haptics")
        editor.remove(KEY_DEFAULT_CONTROLLER_PRESET)
        editor.remove(KEY_LOW_LATENCY_AUDIO)
        editor.remove(KEY_HARDCORE_MODE)
        editor.remove(KEY_FAST_FORWARD_MODE)
        editor.remove(KEY_FAST_FORWARD_KEYCODE)
        editor.remove(KEY_SHOW_FPS)
        editor.remove(KEY_RESUME_ON_LAUNCH)
        editor.remove(KEY_LOG_LEVEL)
        for (key in prefs(context).all.keys) {
            if (key.startsWith(KEY_RENDERER_CHOICE_PREFIX) ||
                key.startsWith(KEY_CORE_OPTION_PREFIX) ||
                key.startsWith(KEY_BUTTON_BINDING_PREFIX) ||
                key.startsWith(KEY_CORE_CHOICE_PREFIX) ||
                key.startsWith(KEY_SHADER_CHOICE_PREFIX)
            ) {
                editor.remove(key)
            }
        }
        editor.apply()
        TacoBoyLog.setLevel(LogLevel.ERROR)
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
