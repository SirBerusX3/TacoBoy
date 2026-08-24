Phases:
Phase 0 – Foundation (done / nearly done)
Phase 1 – Core emulation loop (v0.1 MVP)
Phase 2 – ROM & core management (v0.2)
Phase 3 – Polish & handheld UX (v0.3)
Phase 4 – Hardening & release prep (v1.0)

Each phase lists:
Goals
Concrete tasks
Verification criteria
Notes / risks
------------------------------------------------
Phase 0 – Foundation (status: mostly complete)
Goal: Prove the stack builds, runs, and can display a game with a configurable black zone.

Completed / in progress
Confirmed Pocket Taco is standard Bluetooth HID; no custom protocol needed.

Created com.tacoboy package separate from com.swordfish.libretrodroid.

Implemented TacoBoyActivity:

Edge‑to‑edge, hides system bars.

ConstraintLayout with:

gamecontainer (top) for GLRetroView.

occlusion_zone (bottom, black, touch‑swallowing).

Draggable boundary_handle persisted via TacoBoyPrefs.

Hardcoded mGBA core + sample ROM path.

Input forwarding via onKeyDown/onKeyUp/onGenericMotionEvent.

Manifest: TacoBoyActivity as MAIN/LAUNCHER, locked to portrait.

Build verified: ./gradlew.bat :app:compileDebugKotlin succeeds.

Remaining in Phase 0
Run on a real device (even without Pocket Taco initially):

Verify:

App launches.

GLRetroView renders the sample ROM.

Boundary handle moves and persists.

Occlusion zone is black and swallows touch.

Confirm orientation feels right with a phone held vertically (simulate clamp with your hand).

Exit criteria for Phase 0:

Phase 1 – Core emulation loop (v0.1 MVP)
Goal: A usable “demo” that:

Lets you pick one system (e.g., GBA).

Lets you pick a ROM from a folder.

Runs the game with:

Correct aspect / scaling.

Pocket Taco–friendly layout.

Basic save/load/exit.

No library UI yet; just “pick file → play”.

1.1. Core & ROM selection (minimal)
Tasks:

Add a simple “launcher” screen before TacoBoyActivity:

Options:

“Select ROM folder” (use Intent.ACTION_OPEN_DOCUMENT_TREE).

“Select core” (for v0.1, you can hardcode mGBA and skip this).

Store selected folder path(s) in TacoBoyPrefs.

In TacoBoyActivity:

On first launch with no ROM selected, show a small overlay hint:

“Tap here to select a ROM folder” → opens folder picker.

Once a folder is chosen:

Scan for .gba files (for mGBA).

Show a simple list dialog to pick a ROM.

Launch the selected ROM with the mGBA core.

Verification:

First run:

App prompts for ROM folder.

After selecting, you see a list of GBA ROMs.

Picking one starts the game in GLRetroView.

Subsequent runs:

Last ROM (or folder) is remembered.

Game starts directly or with a single tap.

1.2. Basic in‑game menu
Tasks:

Implement a minimal quick menu accessible via:

A controller button combo (e.g., Select + Start, or a dedicated “M” button if you map it).

Or an on‑screen button in the game area (small, near the top).

Menu options:

Save state.

Load state.

Reset game.

Exit to launcher.

Wire these to LibretroDroid’s APIs for save/load/reset.

Verification:

In‑game:

Pressing the combo opens the menu.

Each option works reliably.

Menu dismisses cleanly and returns to gameplay.

1.3. Aspect ratio & scaling
Tasks:

Ensure the game surface:

Maintains correct aspect ratio for the system (e.g., 3:2 for GBA).

Scales within gamecontainer without distortion.

Options:

“Fit to width” vs “Fit to height” vs “Stretch” (you can start with one mode).

Make sure black bars (if any) appear within the game area, not under the occlusion zone.

Verification:

Games look correct (no stretching).

Changing orientation (if you temporarily allow it) doesn’t break layout.

1.4. Input sanity check
Tasks:

Test with:

Pocket Taco (when you have it).

Any other Bluetooth controller (as proxy).

Verify:

All buttons map correctly.

No missed key events.

Analog input (if relevant) works via onGenericMotionEvent.

Verification:

Play a game for several minutes with no input glitches.

Exit criteria for Phase 1 (v0.1):

You can:

Select a ROM folder.

Pick a GBA ROM.

Play it with correct layout and input.

Save/load/exit via a quick menu.

The boundary handle works and feels usable.

You consider this “good enough to share with a friend for testing”.

Phase 2 – ROM & core management (v0.2)
Goal: Turn TacoBoy from a “single‑system demo” into a practical multi‑system frontend.

2.1. Multi‑system support
Tasks:

Extend ROM scanner to support multiple extensions:

.gba, .gb, .gbc, .nes, .sfc/.smc, etc.

Add a “system” concept:

Map extensions to default cores:

GBA → mGBA

GB/GBC → SameBoy or Gambatte

NES → Nestopia or similar

SNES → Snes9x

Store per‑system core mapping in prefs.

Verification:

Adding a folder with mixed ROMs shows them grouped or tagged by system.

Each ROM launches with the correct core.

2.2. Core downloader / manager
Tasks:

Integrate core downloading (like RetroArch / Lemuroid):

Fetch core list from RetroArch CDN.

Download .so cores to the appropriate directory.

Show installed vs available cores.

Provide a simple “Install recommended cores” button for:

GBA, GB/GBC, NES, SNES (initially).

Verification:

First run:

App offers to download recommended cores.

After download, those systems are playable.

Core selection UI (even if simple) works.

2.3. ROM library UI
Tasks:

Replace the simple ROM picker with a proper library screen:

Grid or list of games.

Sorting: by name, system, last played.

Basic search/filter.

Store “last played” and “play count” metadata.

Verification:

You can browse a moderately sized ROM set (hundreds of games) without performance issues.

Launching games from the library works reliably.

Exit criteria for Phase 2 (v0.2):

TacoBoy supports at least 3–4 systems.

Core download/selection is functional.

Library UI is usable for daily play.

Phase 3 – Polish & handheld UX (v0.3)
Goal: Make TacoBoy feel like a purpose‑built handheld frontend, not just a generic emulator with a black bar.

3.1. Box art & metadata
Tasks:

Implement box art support:

Use RetroArch thumbnail naming scheme:

thumbnails/<Playlist>/<Game Name>.png .

Provide a “Download box art” action per system or globally.

Optionally integrate with an online DB (TheGamesDB, ScreenScraper) for:

Game names.

Release year.

Genre, etc.

Verification:

Library shows box art grid.

Missing art is clearly indicated; re‑scan works.

3.2. Handheld mode enhancements
Tasks:

Auto‑detect Pocket Taco (by Bluetooth device name) and:

Enable “handheld mode” automatically.

Optionally show a small toast: “Pocket Taco detected – handheld mode enabled”.

Refine the boundary handle:

Add a small first‑run hint (“Drag to adjust visible area”).

Optionally hide the handle after a few seconds of inactivity, show on touch near the boundary.

Consider:

Hiding the handle entirely in “pure” mode, only accessible via a menu.

Verification:

With Pocket Taco attached, the UX feels seamless.

New users can discover and use the boundary adjustment easily.

3.3. Performance & battery
Tasks:

Profile:

CPU/GPU usage during gameplay.

Battery drain over a 1–2 hour session.

Optimize:

Rendering resolution / scaling.

Audio buffer sizes.

Background work (disable unnecessary services while in‑game).

Verification:

Smooth gameplay on your target devices.

Acceptable battery life for handheld sessions.

Exit criteria for Phase 3 (v0.3):

TacoBoy feels polished for daily handheld use.

Box art and library UX are pleasant.

Performance is solid on your primary device.

Phase 4 – Hardening & release prep (v1.0)
Goal: Prepare for broader testing / release (even if just private beta).

4.1. Crash handling & logging
Tasks:

Add:

Global exception handler.

Basic crash logging (to file or a service).

Ensure:

Core crashes don’t brick the app.

User can always return to the library.

Verification:

Induce errors (e.g., bad ROM, missing BIOS) and confirm graceful handling.

4.2. Settings & configurability
Tasks:

Expand settings:

Default core per system.

Video options (aspect, scaling, integer scaling).

Audio options (latency, resampler).

Input options (button mappings, turbo).

Keep “handheld mode” defaults sensible.

Verification:

Changing settings has immediate or next‑launch effect as expected.

4.3. Documentation & onboarding
Tasks:

Add:

A short “Getting started” screen or flow:

Select ROM folder.

Install cores.

Adjust boundary.

In‑app help / FAQ:

How to use with Pocket Taco.

How to add ROMs.

How to update cores.

Verification:

A new user can follow the flow without external docs.

4.4. Testing & feedback
Tasks:

Share builds with a small group:

Pocket Taco users.

Different phones (sizes, OEMs).

Collect feedback on:

Layout / boundary behavior.

Core compatibility.

Library UX.

Verification:

You have a list of prioritized bugs/feature requests.

Critical issues are fixed before any wider release.

Exit criteria for Phase 4 (v1.0):

You’re comfortable calling it “beta” or “early access”.

Core functionality is stable.

Feedback is broadly positive for the Pocket Taco use case.

Immediate next steps (concrete)
Given where you are now:

Run on device (Phase 0 completion):

Deploy TacoBoyActivity to your phone.

Verify rendering, boundary drag, persistence.

Design the minimal ROM picker (Phase 1.1):

Decide:

Start with GBA only?

Use folder picker + simple ROM list dialog?

Sketch the UI (even on paper).

Wire core/ROM loading:

Replace the hardcoded sample ROM path with:

Folder selection.

ROM list scan.

Launch selected ROM with mGBA.

System support & controller suitability
Primary (v0.1): GB, GBC, GBA
Perfect match for Pocket Taco’s D‑pad + ABXY + shoulders. No analog required.

Secondary (v0.2): NES, SNES, Genesis/Mega Drive, Game Gear/Master System
Also excellent fits; SNES uses all four face buttons naturally.

Conditional (v0.2+): PS1
Works for games that don’t require analog sticks. Mark such titles in UI.

Not recommended: N64 and other analog‑heavy systems
These systems rely on analog sticks and/or complex button layouts that the Pocket Taco does not provide. TacoBoy will not prioritize or optimize for these.

Game Boy / Game Boy Color
Primary recommendation: Gambatte (gambatte_libretro)

Very accurate GB/GBC emulation.

Light on resources.

Widely used and well‑tested on Android.

Good compatibility across the library.

Alternatives (optional):

SameBoy (sameboy_libretro)

Excellent accuracy, actively developed.

Slightly heavier than Gambatte, but great if you care about cycle‑accurate behavior.

mGBA (mgba_libretro)

Also supports GB/GBC, but primarily known for GBA.

Good if you want one core for GB/GBC/GBA, but Gambatte/SameBoy are often preferred for pure GB/GBC.

TacoBoy default: Gambatte.

Game Boy Advance
Primary recommendation: mGBA (mgba_libretro)

Best overall GBA core: accurate, fast, actively maintained.

Handles link cable features, RTC, etc.

Runs well on modern Android devices.

Alternatives (optional):

gpSP (gpsp_libretro)

Very lightweight, good for low‑end hardware.

Less accurate than mGBA; some games have quirks.

VBA‑M (vba_m_libretro)

Older, decent compatibility, but generally outclassed by mGBA.

TacoBoy default: mGBA.

NES
Primary recommendation: Mesen (mesen_libretro)

Extremely accurate NES emulation.

Good feature set (timing options, light gun support, etc.).

Solid on Android.

Alternatives (optional):

FCEUmm (fceumm_libretro)

Lightweight, good compatibility.

Slightly less accurate than Mesen, but fine for most games.

Nestopia (nestopia_libretro)

Older core, still usable, but Mesen is generally preferred now.

TacoBoy default: Mesen.

SNES
Primary recommendation: Snes9x (snes9x_libretro)

Great balance of accuracy and performance.

Runs well on Android, including mid‑range devices.

Good compatibility across the library.

Alternatives (optional):

bsnes (bsnes_libretro, “bsnes HD Beta” variants)

Higher accuracy (especially for special chips).

More CPU‑intensive; may be overkill for many devices.

Snes9x2010 (snes9x2010_libretro)

Older, lighter core; useful for very low‑end hardware.

TacoBoy default: Snes9x.

Genesis / Mega Drive
Primary recommendation: Genesis Plus GX (genesis_plus_gx_libretro)

Excellent accuracy and compatibility.

Handles SMS/GG as well in some builds.

Well‑maintained and widely recommended.

Alternatives (optional):

Genesis Plus GX Wide (genesis_plus_gx_wide_libretro)

Same core with widescreen hacks.

PicoDrive (picodrive_libretro)

Lighter, good for low‑end devices; also supports 32X/Sega CD in some builds.

ClownMDemu (clownmdemu_libretro)

Newer, very accurate, but less battle‑tested on Android than Genesis Plus GX.

TacoBoy default: Genesis Plus GX.

PlayStation 1 (conditional support)
If you add PS1 later:

Primary recommendation: SwanStation (swanstation_libretro)

Modern, actively developed PS1 core.

Good accuracy and performance on Android.

Better than older Beetle/PCSX cores for most users.

Alternatives (optional):

Beetle PSX HW (beetle_psx_hw_libretro)

Hardware‑accelerated, good for upscaling.

Slightly more complex; HW rendering can be picky on some GPUs.

PCSX ReARMed (pcsx_rearmed_libretro)

Older, lighter core; good for very low‑end devices.

TacoBoy default (if/when PS1 is added): SwanStation.

How to expose core choice in TacoBoy
You don’t need a complex UI up front. A simple, scalable approach:

v0.1–v0.2: system‑level default + optional override
Settings → Cores:

Per system, show:

“Default core” dropdown (e.g., GBA → mGBA).

Optional: “Alternative cores” list (multi‑select or single‑select).

Per‑game override (later):

In the game’s detail screen or long‑press menu:

“Use custom core” toggle.

If enabled, show a dropdown of available cores for that system.

Data model example:

kotlin
data class SystemCoreConfig(
    val systemId: String, // e.g. "gba", "snes"
    val defaultCoreId: String, // e.g. "mgba"
    val allowedCoreIds: List<String> // e.g. ["mgba", "gpsp", "vba_m"]
)

data class GameCoreOverride(
    val romPath: String,
    val coreId: String? // null = use system default
)

This gives you:

A simple default experience (most users never touch core settings).

The ability for power users to tune per‑game when needed.

Practical default config for TacoBoy v0.1
For your initial release, you could ship with:

GB/GBC: Gambatte

GBA: mGBA

NES: Mesen

SNES: Snes9x

Genesis: Genesis Plus GX

And optionally:

PS1: none in v0.1; add SwanStation in v0.2 if you decide to support it.

You can hard‑code these defaults in TacoBoyPrefs or a small CoreConfig object, then later add UI to change them.