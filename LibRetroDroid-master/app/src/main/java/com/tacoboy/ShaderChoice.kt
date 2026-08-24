package com.tacoboy

import com.swordfish.libretrodroid.ShaderConfig

/**
 * Which display shader a system's picture is drawn through. These are post-processing passes
 * over the finished frame -- entirely separate from the hardware/software renderer choice on
 * the same Settings tab, which decides how the core draws that frame in the first place.
 *
 * A curated subset of what `ShaderConfig` actually offers, in increasing order of cost.
 * The UPSCALE entries map to its CUT/CUT2/CUT3 edge-directed upscalers, and are the only
 * multi-pass ones here -- so they are the only entries that exercise `previousPass`, the path
 * the 2026-08-23 max-size-FBO change had to alter blind (see CHANGELOG.md). If they render
 * wrong while the single-pass entries look right, `shadermanager.cpp`'s `textureScale` uniform
 * and the `passCoords` divisions are the first place to look.
 *
 * Per-system rather than global because the right answer genuinely differs: an LCD grid suits
 * a handheld's screen and looks wrong on PlayStation output, and a CRT mask is the reverse.
 * Same reasoning -- and the same storage shape -- as the per-system renderer choice.
 */
enum class ShaderChoice(val prefValue: String) {
    /** Straight passthrough. What every system used before this was selectable. */
    DEFAULT("default"),

    /** Sharp-edged scaling that keeps pixels crisp instead of blurring them when upscaled. */
    SHARP("sharp"),

    /** Scanline/aperture mask, approximating a CRT television. */
    CRT("crt"),

    /** Pixel grid, approximating an LCD handheld panel. */
    LCD("lcd"),

    /** CUT: cheapest of the edge-directed upscalers -- smooths diagonal jaggies while
     *  leaving flat areas alone. Two passes. */
    UPSCALE1("upscale1"),

    /** CUT2: adds a soft-edge sharpening pass and a more careful edge search than CUT. */
    UPSCALE2("upscale2"),

    /** CUT3: CUT2 plus a longer hard-edge search (4 texels), the best-looking and the
     *  most expensive of the three. */
    UPSCALE3("upscale3");

    /** Left at ShaderConfig's own defaults deliberately: every parameter these carry is
     *  tunable, but nothing in the UI exposes them yet, and the defaults are what the
     *  upstream shader was authored against. */
    fun toConfig(): ShaderConfig = when (this) {
        DEFAULT -> ShaderConfig.Default
        SHARP -> ShaderConfig.Sharp
        CRT -> ShaderConfig.CRT
        LCD -> ShaderConfig.LCD
        UPSCALE1 -> ShaderConfig.CUT()
        UPSCALE2 -> ShaderConfig.CUT2()
        UPSCALE3 -> ShaderConfig.CUT3()
    }

    companion object {
        fun fromPrefValue(value: String?): ShaderChoice =
            entries.firstOrNull { it.prefValue == value } ?: DEFAULT
    }
}
