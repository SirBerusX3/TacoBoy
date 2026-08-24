package com.tacoboy

import android.content.Context

/**
 * Extension -> libretro core mapping. Only systems whose core .so is
 * already bundled in jniLibs are supported here — NES/Genesis would need
 * a new core binary this project doesn't include, so they're deliberately
 * left out rather than half-wired.
 *
 * GB and GBC share the gambatte core but are kept as distinct entries
 * because libretro's thumbnail server keeps separate folders for them
 * (see BoxArtCache) — collapsing them would break box art matching.
 */
enum class GameSystem(
    // Every system has at least one core; only PS1 currently has more than one (see
    // CHANGELOG.md's "PS1: selectable core (SwanStation / Beetle PSX HW)" entry). The
    // first entry is the default. Which one's actually active for a given system is a
    // TacoBoyPrefs-backed choice, not a fixed property -- use selectedCore(context).
    val cores: List<CoreDefinition>,
    val thumbnailFolder: String,
    val shortLabel: String,
    // RetroAchievements' own numeric console ID (rc_consoles.h in the vendored rcheevos
    // source, not guessed) -- not needed for game/achievement lookups (those key off RA's
    // own gameId), but is what a live-tracking session would need if it ever has to
    // disambiguate a hash that's ambiguous across consoles.
    val raConsoleId: Int,
    val needsBios: Boolean = false,
    // True where the core runs fine without the BIOS (Lynx: Handy falls back to an
    // internal HLE boot ROM). Only changes how the library reports a missing file --
    // a warning for PS1, which genuinely cannot boot, and a neutral note otherwise.
    val biosOptional: Boolean = false,
    // Which Controller-tab rows make sense to show for this system — real Game Boy
    // hardware has no X/Y or shoulder buttons at all, GBA has no X/Y, and only PS1's
    // real controller (DualShock) has L2/R2/L3/R3. Showing every RetroPad target for
    // every system would suggest buttons exist that don't, which is exactly the kind
    // of thing that looks like a bug to someone unfamiliar with RetroPad's generic
    // superset button set. Defaults to the SNES-shaped set (D-Pad/A/B/X/Y/L1/R1/
    // Start/Select) since that's the largest non-PS1 layout among supported systems.
    val relevantControllerTargets: Set<ControllerBindings.Target> = setOf(
        ControllerBindings.Target.DPAD_UP, ControllerBindings.Target.DPAD_DOWN,
        ControllerBindings.Target.DPAD_LEFT, ControllerBindings.Target.DPAD_RIGHT,
        ControllerBindings.Target.A, ControllerBindings.Target.B,
        ControllerBindings.Target.X, ControllerBindings.Target.Y,
        ControllerBindings.Target.L1, ControllerBindings.Target.R1,
        ControllerBindings.Target.START, ControllerBindings.Target.SELECT,
    )
) {
    GBA(
        listOf(CoreDefinition("libmgba_libretro_android.so", "mGBA")),
        "Nintendo - Game Boy Advance", "GBA",
        raConsoleId = 5, // RC_CONSOLE_GAMEBOY_ADVANCE
        // Real GBA hardware has no X/Y face buttons.
        relevantControllerTargets = setOf(
            ControllerBindings.Target.DPAD_UP, ControllerBindings.Target.DPAD_DOWN,
            ControllerBindings.Target.DPAD_LEFT, ControllerBindings.Target.DPAD_RIGHT,
            ControllerBindings.Target.A, ControllerBindings.Target.B,
            ControllerBindings.Target.L1, ControllerBindings.Target.R1,
            ControllerBindings.Target.START, ControllerBindings.Target.SELECT,
        )
    ),
    GAME_BOY(
        listOf(CoreDefinition("gambatte_libretro_android.so", "Gambatte")),
        "Nintendo - Game Boy", "GB",
        raConsoleId = 4, // RC_CONSOLE_GAMEBOY
        // Real GB/GBC hardware has no X/Y or shoulder buttons at all.
        relevantControllerTargets = setOf(
            ControllerBindings.Target.DPAD_UP, ControllerBindings.Target.DPAD_DOWN,
            ControllerBindings.Target.DPAD_LEFT, ControllerBindings.Target.DPAD_RIGHT,
            ControllerBindings.Target.A, ControllerBindings.Target.B,
            ControllerBindings.Target.START, ControllerBindings.Target.SELECT,
        )
    ),
    GAME_BOY_COLOR(
        listOf(CoreDefinition("gambatte_libretro_android.so", "Gambatte")),
        "Nintendo - Game Boy Color", "GBC",
        raConsoleId = 6, // RC_CONSOLE_GAMEBOY_COLOR
        relevantControllerTargets = setOf(
            ControllerBindings.Target.DPAD_UP, ControllerBindings.Target.DPAD_DOWN,
            ControllerBindings.Target.DPAD_LEFT, ControllerBindings.Target.DPAD_RIGHT,
            ControllerBindings.Target.A, ControllerBindings.Target.B,
            ControllerBindings.Target.START, ControllerBindings.Target.SELECT,
        )
    ),
    // Full SNES layout (D-Pad/A/B/X/Y/L1/R1/Start/Select) — uses the class default above.
    SNES(
        listOf(CoreDefinition("snes9x_libretro_android.so", "Snes9x")),
        "Nintendo - Super Nintendo Entertainment System", "SNES",
        raConsoleId = 3, // RC_CONSOLE_SUPER_NINTENDO
    ),
    /**
     * Mega Drive / Genesis. Genesis Plus GX's own RetroPad map (libretro.c's input
     * descriptors) is Y->A, B->B, A->C, L->X, X->Y, R->Z, Select->Mode -- so the six-button
     * pad lands exactly on the Pocket Taco's four face buttons plus two shoulders, with
     * Select free for Mode. That exact fit is why this system is here and N64 is not.
     *
     * The RetroPad names and the printed Genesis names disagree on every single button, so
     * anything user-facing has to relabel (see TouchControls.labelFor) rather than show the
     * raw target names.
     */
    GENESIS(
        listOf(CoreDefinition("genesis_plus_gx_libretro_android.so", "Genesis Plus GX")),
        "Sega - Mega Drive - Genesis", "GEN",
        raConsoleId = 1, // RC_CONSOLE_MEGA_DRIVE
        // The full six plus Mode and Start -- i.e. the class default set, spelled out here
        // because the mapping above is what makes it correct rather than a coincidence.
        relevantControllerTargets = setOf(
            ControllerBindings.Target.DPAD_UP, ControllerBindings.Target.DPAD_DOWN,
            ControllerBindings.Target.DPAD_LEFT, ControllerBindings.Target.DPAD_RIGHT,
            ControllerBindings.Target.A, ControllerBindings.Target.B,
            ControllerBindings.Target.X, ControllerBindings.Target.Y,
            ControllerBindings.Target.L1, ControllerBindings.Target.R1,
            ControllerBindings.Target.START, ControllerBindings.Target.SELECT,
        )
    ),
    // Handy's own RetroPad map (libretro.cpp's btn_map tables): A->A, B->B, L->Option 1,
    // R->Option 2, Start->Pause. The Lynx has no Select equivalent and no third face
    // button, so X/Y are left out. The D-pad is deliberately NOT remapped here for the
    // rotated games -- the core swaps its own direction table when it rotates the screen,
    // so a physical "up" stays "up" relative to the picture without our help.
    LYNX(
        listOf(CoreDefinition("handy_libretro_android.so", "Handy")),
        // Capitalised to sit with the other tab labels, which are all abbreviations
        // (GBA/GB/GBC/SNES/PS1). "Lynx" among them read as the odd one out.
        "Atari - Lynx", "LYNX",
        raConsoleId = 13, // RC_CONSOLE_ATARI_LYNX
        // The boot ROM is optional, not required: Handy passes `!bios_found` to CSystem as
        // a "use the internal HLE BIOS" flag, so a game still runs without it (see rom.cpp,
        // which also logs "Using internal fallback"). needsBios is still true because that
        // is what points systemDirectory at the staging folder -- without it the core would
        // never see an imported boot ROM at all.
        needsBios = true,
        biosOptional = true,
        relevantControllerTargets = setOf(
            ControllerBindings.Target.DPAD_UP, ControllerBindings.Target.DPAD_DOWN,
            ControllerBindings.Target.DPAD_LEFT, ControllerBindings.Target.DPAD_RIGHT,
            ControllerBindings.Target.A, ControllerBindings.Target.B,
            ControllerBindings.Target.L1, ControllerBindings.Target.R1,
            ControllerBindings.Target.START,
        )
    ),
    /**
     * Sega's 8-bit family, all three on the same Genesis Plus GX binary that Mega Drive
     * already uses — the core switches hardware from the ROM's extension, so nothing here
     * has to force `genesis_plus_gx_system_hw` away from `auto`.
     *
     * A two-button pad, mapped by the core as RetroPad B -> button 1 and RetroPad A ->
     * button 2 (the DEVICE_PAD2B branch of libretro.c's input update). Master System and
     * Game Gear both have a Pause/Start on the hardware; the SG-1000 genuinely has neither,
     * so it doesn't get one here.
     */
    MASTER_SYSTEM(
        listOf(CoreDefinition("genesis_plus_gx_libretro_android.so", "Genesis Plus GX")),
        "Sega - Master System - Mark III", "SMS",
        raConsoleId = 11, // RC_CONSOLE_MASTER_SYSTEM
        relevantControllerTargets = setOf(
            ControllerBindings.Target.DPAD_UP, ControllerBindings.Target.DPAD_DOWN,
            ControllerBindings.Target.DPAD_LEFT, ControllerBindings.Target.DPAD_RIGHT,
            ControllerBindings.Target.A, ControllerBindings.Target.B,
            ControllerBindings.Target.START,
        )
    ),
    GAME_GEAR(
        listOf(CoreDefinition("genesis_plus_gx_libretro_android.so", "Genesis Plus GX")),
        "Sega - Game Gear", "GG",
        raConsoleId = 15, // RC_CONSOLE_GAME_GEAR
        relevantControllerTargets = setOf(
            ControllerBindings.Target.DPAD_UP, ControllerBindings.Target.DPAD_DOWN,
            ControllerBindings.Target.DPAD_LEFT, ControllerBindings.Target.DPAD_RIGHT,
            ControllerBindings.Target.A, ControllerBindings.Target.B,
            ControllerBindings.Target.START,
        )
    ),
    SG_1000(
        listOf(CoreDefinition("genesis_plus_gx_libretro_android.so", "Genesis Plus GX")),
        "Sega - SG-1000", "SG",
        raConsoleId = 33, // RC_CONSOLE_SG1000
        // No Start/Pause: the SG-1000's controller has two buttons and nothing else.
        relevantControllerTargets = setOf(
            ControllerBindings.Target.DPAD_UP, ControllerBindings.Target.DPAD_DOWN,
            ControllerBindings.Target.DPAD_LEFT, ControllerBindings.Target.DPAD_RIGHT,
            ControllerBindings.Target.A, ControllerBindings.Target.B,
        )
    ),
    // .chd only, deliberately — .cue/.bin's sibling-file references don't fit our
    // single-file SAF virtual-file loading model. User's whole collection is
    // already .chd (via CHDroid), so this isn't a real-world limitation for them.
    //
    // Two selectable cores as of 2026-08-16 (see CHANGELOG.md's "PS1: selectable
    // core" entry). SwanStation is the default: it's the more accurate/actively
    // developed core, but its hardware renderer requests a desktop-style "OpenGL"
    // context, which LibretroDroid can't satisfy (GLES3-only) -- the core's GL
    // shaders fail to compile against GLES ("Invalid #version"), so SwanStation's
    // "OpenGL" choice produces audio with a black screen. Deliberately not offered
    // as a rendererChoice below (removed 2026-08-17) since it's simply broken on
    // this frontend, not a real trade-off for the user to pick between -- SwanStation
    // now only ever runs "Software", which sidesteps the GL context entirely and is
    // plenty fast for PS1 on modern phone hardware, hence also the default. Beetle
    // PSX HW (mednafen_psx_hw) is offered as an alternative specifically because it
    // properly targets GLES3 (hardware-confirmed: real HW-accelerated rendering, no
    // shader errors) -- pick it per-game if a title runs poorly under SwanStation
    // Software, or if real GPU-accelerated rendering is wanted. No curated
    // per-system core options exist for it yet (CoreOptions.forCore has no entry),
    // unlike SwanStation.
    PS1(
        listOf(
            CoreDefinition(
                "swanstation_libretro_android.so", "SwanStation",
                rendererOptionKey = "swanstation_GPU_Renderer",
                rendererChoices = listOf("Software"),
                defaultRenderer = "Software",
            ),
            CoreDefinition(
                "mednafen_psx_hw_libretro_android.so", "Beetle PSX HW",
                // Real choices, extracted via `grep -a` on the binary -- lowercase,
                // distinct casing from SwanStation's convention.
                // hardware_vk was offered here until 2026-08-23 and is now removed for
                // exactly the reason SwanStation's "OpenGL" was: LibretroDroid has no
                // Vulkan backend (see CHANGELOG.md's "Research: LibretroDroid's HW-render
                // path validated via Beetle PSX HW" entry), so it isn't a trade-off the
                // user can meaningfully pick -- it just misfires. A stale "hardware_vk"
                // saved before the removal falls back to defaultRenderer via
                // CoreDefinition.selectedRenderer, same as the stale "OpenGL" case.
                rendererOptionKey = "beetle_psx_hw_renderer",
                rendererChoices = listOf("software", "hardware_gl"),
                defaultRenderer = "hardware_gl",
            ),
        ),
        "Sony - PlayStation",
        "PS1",
        raConsoleId = 12, // RC_CONSOLE_PLAYSTATION
        needsBios = true,
        // Only PS1's real controller (DualShock) has L2/R2/L3/R3 among supported
        // systems — full RetroPad button set.
        relevantControllerTargets = ControllerBindings.Target.entries.toSet()
    );

    val defaultCore: CoreDefinition get() = cores.first()

    /** Resolves TacoBoyPrefs' saved core choice against this system's real cores list,
     *  falling back to defaultCore if nothing's saved or the saved fileName no longer
     *  matches one of them (e.g. a stale value from a since-removed core). */
    fun selectedCore(context: Context): CoreDefinition {
        val fileName = TacoBoyPrefs.getSelectedCoreFileName(context, this)
        return cores.firstOrNull { it.fileName == fileName } ?: defaultCore
    }

    companion object {
        private val EXTENSION_MAP = mapOf(
            "gba" to GBA,
            "gb" to GAME_BOY,
            "gbc" to GAME_BOY_COLOR,
            "sfc" to SNES,
            "smc" to SNES,
            // Genesis Plus GX also advertises .bin, which is generic enough to be risky in
            // the abstract -- but the library only ever scans the folder the user picked
            // for this system, so a .bin in the Genesis folder is a Genesis ROM by
            // construction. (.cue/.iso/.chd are Mega CD and deliberately left out: that
            // needs a BIOS and multi-file content this app does not model.)
            "md" to GENESIS,
            "gen" to GENESIS,
            "smd" to GENESIS,
            "bin" to GENESIS,
            "sms" to MASTER_SYSTEM,
            "gg" to GAME_GEAR,
            "sg" to SG_1000,
            // Handy also advertises ".o", left out deliberately -- far too generic
            // a suffix to claim in a folder scan.
            "lnx" to LYNX,
            "lyx" to LYNX,
            "chd" to PS1,
        )

        val SUPPORTED_EXTENSIONS: Set<String> = EXTENSION_MAP.keys

        fun forFileName(fileName: String): GameSystem? {
            return EXTENSION_MAP[fileName.substringAfterLast('.', "").lowercase()]
        }
    }
}

/** One libretro core .so a GameSystem can be played with. Renderer fields work exactly
 *  like the old GameSystem-level ones did -- a single libretro core-option key (null for
 *  cores nothing to choose, e.g. every non-PS1 core here), the safe choices exposed to
 *  the user (Settings > Graphics), and which one applies until the user picks another. */
data class CoreDefinition(
    val fileName: String,
    val displayName: String,
    val rendererOptionKey: String? = null,
    val rendererChoices: List<String> = emptyList(),
    val defaultRenderer: String = "",
) {
    /** Resolves TacoBoyPrefs' saved renderer choice against this core's real rendererChoices,
     *  falling back to defaultRenderer if nothing's saved or the saved value no longer matches
     *  one of them (e.g. a stale "OpenGL" saved before that choice was removed as unsupported)
     *  -- same stale-value pattern as GameSystem.selectedCore. */
    fun selectedRenderer(context: Context, system: GameSystem): String {
        val saved = TacoBoyPrefs.getRendererChoice(context, system, fileName)
        return saved?.takeIf { it in rendererChoices } ?: defaultRenderer
    }
}
