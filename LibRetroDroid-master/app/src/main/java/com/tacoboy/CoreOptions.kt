package com.tacoboy

import android.content.Context
import com.android.libretrodroid.R

/**
 * A curated subset of each core's real libretro core options -- see
 * CHANGELOG.md's "investigation: what core options do our 4 cores actually
 * expose" entry for the full catalog (17/28/~39/~106 respectively) this was
 * hand-picked from, live-verified via getVariables() against real ROMs.
 * Deliberately excludes options with long choice lists that don't fit a
 * tap-to-cycle row (palettes, CPU overclock percentages, resolution scale,
 * multitap per-port settings) and options irrelevant to a single-player
 * handheld (light guns, GB Link network play, per-sound-channel mutes).
 * Applied via GLRetroViewData.variables in TacoBoyActivity.setupRetroView,
 * same mechanism CoreDefinition.rendererOptionKey already uses -- kept as a
 * separate model rather than folding into CoreDefinition's renderer fields,
 * since that mechanism is already working/verified and this adds a
 * different shape (a list per core, not one key).
 *
 * Keyed by core .so fileName rather than GameSystem since 2026-08-16's
 * selectable-core work (see CHANGELOG.md's "PS1: selectable core" entry) --
 * a system's curated options can differ per core, same as the renderer
 * already did. Beetle PSX HW (mednafen_psx_hw_libretro_android.so) has ~92
 * of its own options; 30 are curated here as of 2026-08-18 (see the
 * entries below, and the class-scoped comment above that block for what's
 * still deliberately excluded and why) -- everything else falls through
 * to the System tab's existing "no core options" placeholder.
 */
object CoreOptions {
    data class Option(val key: String, val label: String, val choices: List<String>, val default: String)

    private val gambatteOptions = listOf(
        Option("gambatte_gb_colorization", "GB Colorization", listOf("disabled", "auto", "GBC", "SGB", "internal", "custom"), "disabled"),
        Option("gambatte_mix_frames", "Interframe Blending", listOf("disabled", "mix", "lcd_ghosting", "lcd_ghosting_fast"), "disabled"),
        Option("gambatte_gbc_color_correction", "Color Correction", listOf("GBC only", "always", "disabled"), "GBC only"),
        Option("gambatte_gb_bootloader", "Use Official Bootloader", listOf("enabled", "disabled"), "enabled"),
        Option("gambatte_up_down_allowed", "Allow Opposing Directions", listOf("disabled", "enabled"), "disabled"),
    )

    private val forCore: Map<String, List<Option>> = mapOf(
        "libmgba_libretro_android.so" to listOf(
            Option("mgba_frameskip", "Frameskip", listOf("disabled", "auto", "auto_threshold", "fixed_interval"), "disabled"),
            Option("mgba_color_correction", "Color Correction", listOf("OFF", "GBA", "GBC", "Auto"), "OFF"),
            Option("mgba_interframe_blending", "Interframe Blending", listOf("OFF", "mix", "mix_smart", "lcd_ghosting", "lcd_ghosting_fast"), "OFF"),
            Option("mgba_audio_low_pass_filter", "Audio Low-Pass Filter", listOf("disabled", "enabled"), "disabled"),
            Option("mgba_allow_opposing_directions", "Allow Opposing D-Pad Input", listOf("no", "yes"), "no"),
        ),
        "gambatte_libretro_android.so" to gambatteOptions,
        // Genesis Plus GX, added 2026-08-23 with the system. Values and defaults taken from
        // upstream's libretro_core_options.h, not guessed. Deliberately excluded: "System
        // Hardware", which can force the core into Master System/Game Gear/SG-1000 modes --
        // those aren't systems TacoBoy offers, and 'auto' is always right for a Genesis ROM.
        "genesis_plus_gx_libretro_android.so" to listOf(
            Option("genesis_plus_gx_region_detect", "System Region", listOf("auto", "ntsc-u", "pal", "ntsc-j"), "auto"),
            Option("genesis_plus_gx_no_sprite_limit", "Remove Per-Line Sprite Limit", listOf("disabled", "enabled"), "disabled"),
            Option("genesis_plus_gx_overscan", "Borders", listOf("disabled", "top/bottom", "left/right", "full"), "disabled"),
            Option(
                "genesis_plus_gx_aspect_ratio", "Core-Provided Aspect Ratio",
                listOf("auto", "NTSC PAR", "PAL PAR", "4:3", "Uncorrected"), "auto",
            ),
            Option(
                "genesis_plus_gx_blargg_ntsc_filter", "Blargg NTSC Filter",
                listOf("disabled", "monochrome", "composite", "svideo", "rgb"), "disabled",
            ),
            Option(
                "genesis_plus_gx_ym2612", "FM Sound Chip",
                listOf("mame (ym2612)", "mame (asic ym3438)", "mame (enhanced ym3438)", "nuked (ym2612)", "nuked (ym3438)"),
                "mame (ym2612)",
            ),
            Option("genesis_plus_gx_audio_filter", "Audio Filter", listOf("disabled", "low-pass", "EQ"), "disabled"),
            Option(
                "genesis_plus_gx_overclock", "CPU Speed",
                listOf("100", "125", "150", "175", "200", "250", "300"), "100",
            ),
        ),
        // Handy (Lynx), added 2026-08-23 with the system itself. Six options exist in the
        // 2026-08-20 build; five are here. Left out: "Frameskip Threshold (%)", which only
        // does anything while Frameskip is set to Manual and would otherwise sit there
        // reading as a setting that does nothing. "Color Depth" isn't in the Android build
        // at all -- it's behind FRONTEND_SUPPORTS_XRGB8888 upstream, and the key is absent
        // from the .so.
        "handy_libretro_android.so" to listOf(
            // The one that genuinely matters: several Lynx games were played with the
            // console held sideways, and 'Auto' takes the rotation from the ROM's own
            // header. The core rotates its D-pad mapping to match, so nothing on the
            // Controller tab needs to change with it.
            Option("handy_rot", "Display Rotation", listOf("Auto", "None", "270", "180", "90"), "Auto"),
            Option("handy_lcd_ghosting", "LCD Ghosting Filter", listOf("disabled", "2frames", "3frames", "4frames"), "disabled"),
            Option("handy_refresh_rate", "Video Refresh Rate", listOf("60", "50", "75", "100", "120"), "60"),
            Option("handy_overclock", "CPU Overclock Multiplier", listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "10"), "1"),
            Option("handy_frameskip", "Frameskip", listOf("disabled", "auto", "manual"), "disabled"),
        ),
        "snes9x_libretro_android.so" to listOf(
            Option("snes9x_overclock_cycles", "Reduce Slowdown (Hack)", listOf("disabled", "light", "compatible", "max"), "disabled"),
            Option("snes9x_audio_interpolation", "Audio Interpolation", listOf("gaussian", "cubic", "sinc", "none", "linear"), "gaussian"),
            Option("snes9x_gfx_hires", "Hi-Res Mode", listOf("enabled", "disabled"), "enabled"),
            Option("snes9x_region", "Console Region", listOf("auto", "ntsc", "pal"), "auto"),
            Option("snes9x_reduce_sprite_flicker", "Reduce Sprite Flicker (Hack)", listOf("disabled", "enabled"), "disabled"),
            Option("snes9x_up_down_allowed", "Allow Opposing Directions", listOf("disabled", "enabled"), "disabled"),
        ),
        "swanstation_libretro_android.so" to listOf(
            Option("swanstation_CPU_ExecutionMode", "CPU Execution Mode", listOf("Recompiler", "Interpreter", "CachedInterpreter"), "Recompiler"),
            // swanstation_GPU_PGXPEnable and swanstation_GPU_TextureFilter used to sit here and
            // were removed 2026-08-23: SwanStation's own embedded descriptions say each "only
            // works with the hardware renderers", and this core is pinned to Software above
            // (GameSystem.PS1's first CoreDefinition -- its OpenGL path was removed after
            // misfiring, and Vulkan isn't supported by LibretroDroid at all). Hardware
            // rendering on PS1 is Beetle PSX HW's job, which is why that core is offered.
            // They were switches that looked functional and did nothing; `omittedNotes` below
            // replaces them with an explanation rather than leaving a silent gap. Any value a
            // user already saved for them simply stops being read -- it was never applied to
            // anything either (see TacoBoyActivity.setupRetroView, which builds `variables`
            // straight from forSelectedCore).
            Option("swanstation_GPU_TrueColor", "True Color Rendering", listOf("false", "true"), "false"),
            Option("swanstation_GPU_DisableInterlacing", "Disable Interlacing", listOf("true", "false"), "true"),
            Option("swanstation_Display_CropMode", "Crop Mode", listOf("Borders", "None", "Overscan"), "Borders"),
            Option("swanstation_GPU_WidescreenHack", "Widescreen Hack", listOf("false", "true"), "false"),
        ),
        "mednafen_psx_hw_libretro_android.so" to listOf(
            // Real option key/values confirmed both live (GET_VARIABLE logging showed the
            // default "1x(native)") and via the core's own embedded description string
            // (extracted with a raw binary string search): "'1x (Native)' emulates native
            // low resolution dithering used by original hardware to smooth out color
            // banding artifacts... Recommended to be disabled when running at 32 bpp color
            // depth" -- note the "recommended... at 32bpp" clause, which is exactly the
            // deciding factor for `beetle_psx_hw_depth` below. Explains the visible
            // colored-speckle noise seen 2026-08-16 testing hardware_gl rendering (see
            // CHANGELOG.md's PS1 selectable-core entries) -- a real hardware-accuracy
            // feature, not a rendering bug, when paired with native 16bpp depth. 3rd choice
            // ("internal resolution", all lowercase -- distinct from "1x(native)"'s own
            // convention) confirmed live 2026-08-17 via temporary SET_VARIABLES/GET_VARIABLE
            // diagnostic logging, which captured the core's real raw registration string
            // verbatim: "Dithering Pattern; 1x(native)|internal resolution|disabled".
            Option(
                "beetle_psx_hw_dither_mode", "Dithering",
                listOf("1x(native)", "internal resolution", "disabled"), "1x(native)",
            ),
            // The actual fix for cutscene/FMV noise, found 2026-08-17 -- see this session's
            // CHANGELOG entry for the full trail. Disabling dither_mode above (done for
            // gameplay, which really was cleaner without it) unmasked 16-bit color banding on
            // gradient-heavy content -- glossy logo text, FMV video -- that dithering had been
            // hiding; flat-shaded low-poly PS1 game geometry rarely has smooth-enough gradients
            // to show the same banding, which is why gameplay looked fine while cutscenes
            // didn't. `beetle_psx_hw_depth`'s own choices are `16bpp(native)|32bpp`, uncurated
            // until now (sat at the core's compiled default, native 16bpp) -- raising it to
            // 32bpp removes the banding at the source instead of needing dithering to mask it.
            // Hardware-confirmed: the exact "007 Tomorrow Never Dies" gun-barrel FMV frame that
            // showed heavy colored speckle noise with dither_mode=disabled + depth=16bpp(native)
            // rendered completely clean once depth was set to 32bpp, dither_mode unchanged.
            // (`beetle_psx_hw_mdec_yuv`, tried first as a guess based on the option's name alone
            // -- "MDEC YUV Chroma Filter", the PS1's video-decode-specific option -- turned out
            // not to be the fix; not curated here, wasn't worth keeping as a real user-facing
            // toggle once depth turned out to be the actual answer.)
            Option("beetle_psx_hw_depth", "Color Depth", listOf("32bpp", "16bpp(native)"), "32bpp"),

            // Everything below added 2026-08-17, expanding coverage closer to parity with
            // SwanStation's list -- picked from the full ~92-option catalog captured that day
            // (see CHANGELOG.md). All default to each option's own first-listed choice, which
            // this file's `environment_handle_set_variables` parsing guarantees is the core's
            // real compiled default whenever nothing overrides it first (empirically confirmed
            // for dither_mode's default above) -- so every option here is a pure curation
            // add, not a deliberate behavior change, except depth above and widescreen_hack's
            // aspect ratio note below. Still excluded, same reasoning as the class doc comment:
            // long choice lists (CPU/GPU-cycle percentages, memory card slot indices, scanline
            // ranges), light gun / mouse / neGcon / multitap (irrelevant on a single-player
            // handheld with no such peripherals), debug-only options (texture dump/track,
            // full-VRAM display), the whole HD-texture-replacement cluster (no bundled texture
            // packs to point it at), the HDR display cluster (color_format's 30bit_hdr choice
            // needs a display pipeline this app doesn't model), the rest of the analog-video/
            // NTSC-signal simulation cluster beyond video_cable itself (phase_error/black_setup,
            // added 2026-08-18 -- see that entry below), BIOS override (psxonpsp/ps1_rom/
            // openbios choices need alternate BIOS files this app doesn't bundle -- picking one
            // that isn't present risks breaking boot), and memory-card behavior options
            // (use_mednafen_memcard0_method, enable_memcard1, shared_memory_cards -- would
            // interact with TacoBoy's own per-ROM virtual memory card system in ways not tested
            // here).

            // Loading / boot.
            Option("beetle_psx_hw_cd_access_method", "CD Access Method", listOf("sync", "async", "precache"), "sync"),
            Option(
                "beetle_psx_hw_cd_fastload", "CD Loading Speed",
                listOf("2x(native)", "4x", "6x", "8x", "10x", "12x", "14x"), "2x(native)",
            ),
            Option("beetle_psx_hw_skip_bios", "Skip BIOS", listOf("disabled", "enabled"), "disabled"),
            Option("beetle_psx_hw_region", "System Region", listOf("auto", "ntsc-j", "ntsc-u", "pal"), "auto"),

            // Performance.
            Option(
                "beetle_psx_hw_gpu_overclock", "GPU Overclock",
                listOf("1x(native)", "2x", "4x", "8x", "16x", "32x"), "1x(native)",
            ),
            Option("beetle_psx_hw_gte_overclock", "GTE Overclock", listOf("disabled", "enabled"), "disabled"),
            Option(
                "beetle_psx_hw_cpu_dynarec", "CPU Execution Mode",
                listOf("disabled", "execute", "run_interpreter"), "disabled",
            ),
            Option("beetle_psx_hw_spu_silent_voice", "SPU Silent Voice Optimization", listOf("enabled", "disabled"), "enabled"),
            Option("beetle_psx_hw_frame_duping", "Frame Duping", listOf("disabled", "enabled"), "disabled"),

            // Upscaling/filtering -- internal_resolution, scaled_uv_offset (a precision fix
            // that only matters once resolution is raised past native) and filter are one
            // related group.
            //
            // adaptive_smoothing, super_sampling and msaa were listed here until 2026-08-23
            // and are gone because they are inert on this frontend: all three sit inside
            // `#ifdef HAVE_VULKAN` upstream and each one's own description ends "Only
            // supported by the Vulkan renderer", so neither software nor hardware_gl -- the
            // only renderers TacoBoy offers -- does anything with them. Same reasoning as the
            // two SwanStation options removed earlier the same day: an option that cannot do
            // anything is worse than no option, because the user spends a game working out
            // that it changed nothing.
            Option(
                "beetle_psx_hw_internal_resolution", "Internal Resolution",
                listOf("1x(native)", "2x", "4x", "8x", "16x"), "1x(native)",
            ),
            Option("beetle_psx_hw_scaled_uv_offset", "Texture UV Offset Fix", listOf("enabled", "disabled"), "enabled"),
            Option(
                "beetle_psx_hw_filter", "Texture Filtering",
                listOf("nearest", "SABR", "xBR", "bilinear", "3-point", "JINC2"), "nearest",
            ),
            Option("beetle_psx_hw_line_render", "Line Rendering Hack", listOf("default", "aggressive", "disabled"), "default"),

            // PGXP -- the well-known PS1-emulation geometry-precision fix (eliminates the
            // "wobbly" polygon/texture look from the real PS1 GPU's lack of subpixel accuracy).
            // Same category as SwanStation's own "PGXP Geometry Correction" toggle above.
            Option(
                "beetle_psx_hw_pgxp_mode", "PGXP Geometry Correction",
                listOf("disabled", "memory only", "memory + CPU"), "disabled",
            ),
            Option("beetle_psx_hw_pgxp_nclip", "PGXP Primitive Culling", listOf("disabled", "enabled"), "disabled"),
            Option("beetle_psx_hw_pgxp_vertex", "PGXP Vertex Cache", listOf("disabled", "enabled"), "disabled"),
            Option("beetle_psx_hw_pgxp_texture", "PGXP Perspective Texturing", listOf("disabled", "enabled"), "disabled"),

            // Display / aspect.
            Option(
                "beetle_psx_hw_aspect_ratio", "Aspect Ratio",
                listOf("corrected", "uncorrected", "4:3", "ntsc", "16:9"), "corrected",
            ),
            Option("beetle_psx_hw_crop_overscan", "Crop Overscan", listOf("smart", "disabled", "static"), "smart"),
            Option(
                "beetle_psx_hw_deinterlacer", "Deinterlace Method",
                listOf("weave", "bob", "bob_offset", "fastmad", "off"), "weave",
            ),
            Option("beetle_psx_hw_widescreen_hack", "Widescreen Hack", listOf("disabled", "enabled"), "disabled"),
            Option(
                "beetle_psx_hw_widescreen_hack_aspect_ratio", "Widescreen Aspect Ratio",
                listOf("16:9", "16:10", "18:9", "19:9", "20:9", "21:9", "32:9"), "16:9",
            ),
            // Added on request 2026-08-18, pulled out of the "analog-video/NTSC-signal
            // simulation" cluster this file's exclusion note otherwise skips -- a genuine CRT
            // look via signal simulation rather than a post-process shader. phase_error/
            // black_setup (fine-tuning knobs for the simulated signal) stay excluded for now;
            // just this one gets someone most of the way to a composite-cable look on its own.
            Option("beetle_psx_hw_video_cable", "Analog Video Cable", listOf("off", "rgb", "svideo", "composite", "rf"), "off"),

            // DualShock analog stick behavior.
            Option("beetle_psx_hw_analog_calibration", "Analog Self-Calibration", listOf("disabled", "enabled"), "disabled"),
            Option(
                "beetle_psx_hw_analog_toggle", "Analog Mode on Boot",
                listOf("disabled", "enabled", "enabled-analog"), "disabled",
            ),
        ),
    )


    /**
     * Plain-English explanation per option key, surfaced collapsed behind each System-tab row's
     * info button (see SettingsActivity.renderSystemCoreOptionsList). Kept as a side map keyed by
     * `key` rather than a field on [Option] so the curated lists above stay a readable catalog of
     * what each core exposes -- the two are edited for different reasons, and an option is still
     * perfectly usable without a description (missing ones simply render no info button).
     *
     * Deliberately written for someone who has never seen a libretro core option: what the setting
     * changes, what it costs, and -- for the several here where they differ -- which choice is
     * faithful to the original hardware versus which one looks better on a modern screen.
     */
    private val descriptions: Map<String, Int> = mapOf(
        "mgba_frameskip" to R.string.core_desc_mgba_frameskip,
        "mgba_color_correction" to R.string.core_desc_mgba_color_correction,
        "mgba_interframe_blending" to R.string.core_desc_mgba_interframe_blending,
        "mgba_audio_low_pass_filter" to R.string.core_desc_mgba_audio_low_pass_filter,
        "mgba_allow_opposing_directions" to R.string.core_desc_mgba_allow_opposing_directions,
        "gambatte_gb_colorization" to R.string.core_desc_gambatte_gb_colorization,
        "handy_rot" to R.string.core_desc_handy_rot,
        "genesis_plus_gx_region_detect" to R.string.core_desc_gpgx_region_detect,
        "genesis_plus_gx_no_sprite_limit" to R.string.core_desc_gpgx_no_sprite_limit,
        "genesis_plus_gx_overscan" to R.string.core_desc_gpgx_overscan,
        "genesis_plus_gx_aspect_ratio" to R.string.core_desc_gpgx_aspect_ratio,
        "genesis_plus_gx_blargg_ntsc_filter" to R.string.core_desc_gpgx_blargg_ntsc_filter,
        "genesis_plus_gx_ym2612" to R.string.core_desc_gpgx_ym2612,
        "genesis_plus_gx_audio_filter" to R.string.core_desc_gpgx_audio_filter,
        "genesis_plus_gx_overclock" to R.string.core_desc_gpgx_overclock,
        "handy_lcd_ghosting" to R.string.core_desc_handy_lcd_ghosting,
        "handy_refresh_rate" to R.string.core_desc_handy_refresh_rate,
        "handy_overclock" to R.string.core_desc_handy_overclock,
        "handy_frameskip" to R.string.core_desc_handy_frameskip,
        "gambatte_mix_frames" to R.string.core_desc_gambatte_mix_frames,
        "gambatte_gbc_color_correction" to R.string.core_desc_gambatte_gbc_color_correction,
        "gambatte_gb_bootloader" to R.string.core_desc_gambatte_gb_bootloader,
        "gambatte_up_down_allowed" to R.string.core_desc_gambatte_up_down_allowed,
        "snes9x_overclock_cycles" to R.string.core_desc_snes9x_overclock_cycles,
        "snes9x_audio_interpolation" to R.string.core_desc_snes9x_audio_interpolation,
        "snes9x_gfx_hires" to R.string.core_desc_snes9x_gfx_hires,
        "snes9x_region" to R.string.core_desc_snes9x_region,
        "snes9x_reduce_sprite_flicker" to R.string.core_desc_snes9x_reduce_sprite_flicker,
        "snes9x_up_down_allowed" to R.string.core_desc_snes9x_up_down_allowed,
        "swanstation_CPU_ExecutionMode" to R.string.core_desc_swanstation_CPU_ExecutionMode,
        "swanstation_GPU_TrueColor" to R.string.core_desc_swanstation_GPU_TrueColor,
        "swanstation_GPU_DisableInterlacing" to R.string.core_desc_swanstation_GPU_DisableInterlacing,
        "swanstation_Display_CropMode" to R.string.core_desc_swanstation_Display_CropMode,
        "swanstation_GPU_WidescreenHack" to R.string.core_desc_swanstation_GPU_WidescreenHack,
        "beetle_psx_hw_dither_mode" to R.string.core_desc_beetle_psx_hw_dither_mode,
        "beetle_psx_hw_depth" to R.string.core_desc_beetle_psx_hw_depth,
        "beetle_psx_hw_cd_access_method" to R.string.core_desc_beetle_psx_hw_cd_access_method,
        "beetle_psx_hw_cd_fastload" to R.string.core_desc_beetle_psx_hw_cd_fastload,
        "beetle_psx_hw_skip_bios" to R.string.core_desc_beetle_psx_hw_skip_bios,
        "beetle_psx_hw_region" to R.string.core_desc_beetle_psx_hw_region,
        "beetle_psx_hw_gpu_overclock" to R.string.core_desc_beetle_psx_hw_gpu_overclock,
        "beetle_psx_hw_gte_overclock" to R.string.core_desc_beetle_psx_hw_gte_overclock,
        "beetle_psx_hw_cpu_dynarec" to R.string.core_desc_beetle_psx_hw_cpu_dynarec,
        "beetle_psx_hw_spu_silent_voice" to R.string.core_desc_beetle_psx_hw_spu_silent_voice,
        "beetle_psx_hw_frame_duping" to R.string.core_desc_beetle_psx_hw_frame_duping,
        "beetle_psx_hw_internal_resolution" to R.string.core_desc_beetle_psx_hw_internal_resolution,
        "beetle_psx_hw_scaled_uv_offset" to R.string.core_desc_beetle_psx_hw_scaled_uv_offset,
        "beetle_psx_hw_filter" to R.string.core_desc_beetle_psx_hw_filter,
        "beetle_psx_hw_line_render" to R.string.core_desc_beetle_psx_hw_line_render,
        "beetle_psx_hw_pgxp_mode" to R.string.core_desc_beetle_psx_hw_pgxp_mode,
        "beetle_psx_hw_pgxp_nclip" to R.string.core_desc_beetle_psx_hw_pgxp_nclip,
        "beetle_psx_hw_pgxp_vertex" to R.string.core_desc_beetle_psx_hw_pgxp_vertex,
        "beetle_psx_hw_pgxp_texture" to R.string.core_desc_beetle_psx_hw_pgxp_texture,
        "beetle_psx_hw_aspect_ratio" to R.string.core_desc_beetle_psx_hw_aspect_ratio,
        "beetle_psx_hw_crop_overscan" to R.string.core_desc_beetle_psx_hw_crop_overscan,
        "beetle_psx_hw_deinterlacer" to R.string.core_desc_beetle_psx_hw_deinterlacer,
        "beetle_psx_hw_widescreen_hack" to R.string.core_desc_beetle_psx_hw_widescreen_hack,
        "beetle_psx_hw_widescreen_hack_aspect_ratio" to R.string.core_desc_beetle_psx_hw_widescreen_hack_aspect_ratio,
        "beetle_psx_hw_video_cable" to R.string.core_desc_beetle_psx_hw_video_cable,
        "beetle_psx_hw_analog_calibration" to R.string.core_desc_beetle_psx_hw_analog_calibration,
        "beetle_psx_hw_analog_toggle" to R.string.core_desc_beetle_psx_hw_analog_toggle,
    )

    /**
     * Explains options a core really does expose but this app deliberately doesn't list, for
     * the cases where their absence is something someone would otherwise notice and wonder
     * about. Rendered under that core's option list (see
     * SettingsActivity.renderSystemCoreOptionsList). Keyed by core .so fileName, same as
     * [forCore], so it follows the selected core rather than the system.
     */
    private val omittedNotes: Map<String, Int> = mapOf(
        "swanstation_libretro_android.so" to R.string.core_omitted_swanstation,
    )

    /** 0 when this core has nothing worth explaining away. */
    fun omittedNoteRes(context: Context, system: GameSystem): Int =
        omittedNotes[system.selectedCore(context).fileName] ?: 0

    /** 0 when this option has no description yet -- callers skip the info button entirely. */
    fun descriptionRes(option: Option): Int = descriptions[option.key] ?: 0
    fun forSelectedCore(context: Context, system: GameSystem): List<Option> {
        return forCore[system.selectedCore(context).fileName].orEmpty()
    }

    fun currentValue(context: Context, system: GameSystem, option: Option): String {
        return TacoBoyPrefs.getCoreOptionValue(context, system, option.key) ?: option.default
    }

    fun isCustomized(context: Context, system: GameSystem, option: Option): Boolean {
        return TacoBoyPrefs.getCoreOptionValue(context, system, option.key) != null
    }
}
