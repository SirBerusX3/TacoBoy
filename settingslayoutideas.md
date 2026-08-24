General:
1. Show FPS (boolean):
Overlay in the game screen (top corner, above the cutout strip).
Useful for performance checks and for you during dev.

2. Fast Forward (enum Off/Hold/Toggle)
Maps to LibRetros Fast-Foward feature.
Assign a default key eg R2 or a face button in controller.

3. Auto-save SRAM (boolean)
We already have this in System, perhaps move it to General since its a global behaviour rather than per-system.

4. Resume last game on launch toggle (boolean)
We already have the app running the last ROM on startup, but a toggle to disable that might be desireable for some users.

System (Per‑system emulation options, shader selection and core selection)
GBA:
1. Frameskip (enum: Disabled / Enabled)
2. Color Correction (enum: GBA / GBC / Off)
3. Interframe Blending (enum: Mix / Mix_Smart / LCD_Ghosting / LCD_Ghosting_Fast / Off)
4. Audio Low‑pass Filter (enum: Disabled / Enabled)
5. Allow Opposing D‑pad Input (enum: No / Yes)

GB:
1. GB Colorization (enum: GBC/SGB/Internal/Custom/Disabled/Auto)
2. Interframe Blending (enum: Mix/LCD_Ghosting/LCD_Ghosting_Fast/Disabled)
3. Color Correction (enum: GBC Only / Always / Disabled)
4. Use OFFICIAL Bootloader (enum: Disabled / Enabled)
5. Allow Opposing Directions (enum: Disabled / Enabled)

GBC core options
Same as GB:
1. GB Colorization (enum: GBC / SGB / Internal / Custom / Disabled / Auto)
2. Interframe Blending (enum: Mix / LCD_Ghosting / LCD_Ghosting_Fast / Disabled)
3. Color Correction (enum: GBC Only / Always / Disabled)
4. Use OFFICIAL Bootloader (enum: Disabled / Enabled)
5. Allow Opposing Directions (enum: Disabled / Enabled)

SNES core options
1. Reduce Slowdown (hack) (enum: Disabled / Enabled)
2. Audio Interpolation (enum: Gaussian / Cubic / Sinc / Linear / None)
3. Hi‑Res Mode (enum: Enabled / Disabled)
4. Console Region (enum: Auto / NTSC / PAL)
5. Reduce Sprite Flicker (hack) (enum: Disabled / Enabled)
6. Allow Opposing Directions (enum: Disabled / Enabled)

PS1 core options
1. CPU Execution Mode (enum: Recompiler / Interpreter / CachedInterpreter)
2. PGXP Geometry Correction (enum: False / True)
3. Texture Filtering (enum: Nearest / Bilinear / BilinearBinAlpha / JINC2 / JINC2BinAlpha / xBR / xBRBinAlpha)
4. True Color Rendering (enum: False / True)
5. Disable Interlacing (enum: False / True)
6. Crop Mode (enum: Borders / Overscan / None)
7. Widescreen Hack (enum: True / False)
8. BIOS file management with region flag for identification.
PS1 BIOS files follow the SCPH-xxxxx.bin naming convention.
Check BIOSregion.md for the BIOS names matched with region.

Graphics: Global rendering options, not per‑system core options.
1. PS1 Renderer
2. Global Shader preset – if we add scanlines/CRT/etc.
3. Aspect Ratio override (enum: Core default / 4:3 / 16:9 / Stretch)
Keep this tab light; most video options live under System.

Controller:
1. Pocket Taco auto-apply presets
2. Button/Input Bindings (add a turbo button binding to each system)

Audio: 
1. Low Latency Audio 
2. Audio interpolation (enum: None / Linear / Cubic / etc.) – note SNES already has its own interpolation option
3. Audio buffer size / latency (slider or enum) – only if users need to tune it per device.
Keep this minimal; most audio options are per‑system under Core options.

Achievements: RetroAchievements configuration and status.
1. Account Section
2. Web API Key (string/masked) For read-only operations.
3. Live tracking toggle to disable Live Tracking if desired.
4. Hardcore Mode Toggle to disable the save-states menu as is requried by RetroAchievements in their Hardcore Rules.
5. Test connection (button)
Pings RA with current credentials, shows success/failure.
6. Test Achievement (button) 
Displays a fake achievement to test the Toast.

Advanced: Dev-Only or Power-User Options.

1. Log level (enum: Error / Warn / Info / Debug)
Controls how verbose your logs are.
2. 16 KB page alignment note (info text)
“Native libraries are not yet 16 KB‑aligned. This is OK for debug builds; release builds will need to address this.”
3. Export logs (button).
4. Reset all settings (button).

Menu Button above game window.
1. Four Save Slots (disabled when using Hardcore Mode)
2. Reset Game (button)
3. Fast Forward Toggle
4. Quit to Library.
5. Achievement Tracking toggle.
6. Info (core, system, ROM name, Session time)
Core options themselves live in Settings → System → {system}. Not in the in‑game menu (at least for now). We can add per‑game overrides later.

