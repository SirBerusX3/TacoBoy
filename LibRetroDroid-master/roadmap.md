# TacoBoy roadmap

Rewritten 2026-09-14, at `versionName 0.2.1`. The previous roadmap (2026-08-14) was never
updated as work landed; its Phases 0–4 are summarised below against what actually shipped,
and Phases 5–7 are new. `CHANGELOG.md` is the record of what was done and why; this file is
what is still to do.

| Phase | Target | Status |
|---|---|---|
| 0 – Foundation | — | Done |
| 1 – Core emulation loop | v0.1 | Done |
| 2 – ROM and core management | v0.2 | Done, except NES (carried into Phase 5) |
| 3 – Polish and handheld UX | v0.2 | Done, except battery (carried into Phase 5) |
| 4 – Hardening | v0.2 | Crash handling and settings done; help and feedback carried forward |
| **5 – Finish what's open** | **v0.3** | Not started |
| **6 – Achievements done properly** | **v0.4** | Started: 6.1 items 1–4 done |
| **7 – Reach** | **v1.0** | Not started |

## Principles

These held through Phases 0–4 and new work should keep to them.

- **Built for the Pocket Taco.** Portrait, clamp-on, D-pad + ABXY + shoulders, no analog
  sticks. A system is added because its controls fit that, not because a core exists.
- **Nothing half-wired.** A system is listed only if its core ships and every path through
  it works. Options found to be inert are removed rather than shown.
- **Cores ship in the APK.** Each is built from a recorded upstream commit with 16 KB page
  alignment, so it can be regenerated rather than only replaced.
- **Verified, and say how.** Every CHANGELOG entry states whether a change was compiled,
  run on an emulator, run on a device, or run with a real Pocket Taco.

---

## Phases 0–4: what shipped

### Phase 0 – Foundation
Done. `TacoBoyActivity`, the occlusion zone and the persisted boundary handle, run on a real
device on 2026-08-14.

### Phase 1 – Core emulation loop (v0.1)
Done.
- **1.1 ROM selection** — SAF folder picker, per-system folders, last ROM remembered.
- **1.2 Quick menu** — save states (4 slots per ROM), load, reset (a full core reload),
  exit, plus fast forward and achievement tracking toggles.
- **1.3 Aspect and scaling** — correct aspect per system, integer scaling, per-system shaders.
- **1.4 Input** — Pocket Taco and generic controllers, remappable bindings with presets.

### Phase 2 – ROM and core management (v0.2)
- **2.1 Multi-system** — done: ten systems across seven cores. **NES was never added**, and
  is Phase 5.1.
- **2.2 Core downloader** — **declined.** Cores ship in the APK instead: every core is
  pinned to a known-good, 16 KB-aligned build, with no network step before first play.
- **2.3 Library UI** — done: grid and list, search, sort by name, system, recently played
  and most played.

### Phase 3 – Polish and handheld UX
- **3.1 Box art** — done, from libretro's thumbnail server, cached locally. Online metadata
  (year, genre) is parked; see below.
- **3.2 Handheld mode** — done: Pocket Taco auto-detected over Bluetooth with its controller
  preset applied, and a first-run hint on the boundary handle.
- **3.3 Performance** — profiled; see `PROFILING.md`. Every core has headroom, the heaviest
  (Beetle PSX HW) at ~3.4x real time. **Battery was not measured**, because the phone was
  on USB power. That is Phase 5.4.

### Phase 4 – Hardening
- **4.1 Crash handling** — done: crashes written to disk, bad ROMs and missing BIOS refused
  cleanly, the library always reachable.
- **4.2 Settings** — done: core per system (PS1 is the only system with a choice), video,
  audio latency, bindings, turbo. No resampler option, because libretrodroid has exactly one.
- **4.3 Onboarding** — the "install cores" flow no longer applies. Replaced by the About tab:
  licences, source link, Copy Diagnostics. **In-app help is still to do**, as Phase 5.6.
- **4.4 Testing and feedback** — released publicly as v0.2.0 and v0.2.1. No structured
  feedback yet; that is Phase 7.5.

---

## Phase 5 – Finish what's open (v0.3)

**Goal:** close every gap the first roadmap left, and make room for the library to grow.

### 5.1 NES
- Build a NES core from source at a recorded commit, with the 16 KB linker flags, following
  the recipe in the 2026-09-11 CHANGELOG entry. Candidates: **FCEUmm** (light, very widely
  used) or **Mesen** (more accurate, heavier). Choose on size, accuracy and whether it builds
  cleanly with NDK 26.1.
- Record its licence in the About tab, and check whether it is non-commercial.
- Add `GameSystem` entry, extensions (`.nes`, `.fds` only if FDS BIOS handling is built),
  thumbnail folder, on-screen pad layout (D-pad, A, B, Select, Start), bindings.
- Verify its option keys against the binary, as was done for the six rebuilt cores.

**Verification:** `tools-check-16kb.sh` passes; a game plays on device with picture, sound,
save states and box art; RetroAchievements identifies it.

### 5.2 Sega CD / Mega CD
- Genesis Plus GX already emulates it; no new core is needed.
- It was left out because it needs a BIOS and multi-file content the app did not model for
  Genesis. PS1 has since built both — BIOS import and region detection, `.cue`/`.chd`
  handling — so this is reuse, not new infrastructure. Check how much of it generalises.
- Mega CD BIOS detection by hash, per region, alongside the PS1 list.

**Verification:** a `.chd` and a `.cue`/`.bin` game both boot; a missing BIOS is refused
cleanly, not a black screen.

### 5.3 Library tabs that scale
- The tab row has been tightened repeatedly and "buys room for one or two more systems at
  most". NES and Sega CD make twelve. Redesign before adding them, not after.
- Options to weigh: a system dropdown, a scrolling chip row, or hiding systems with no ROM
  folder set.

**Verification:** twelve systems usable on the SM-S938B and on a narrower phone, with no
wrapped or clipped labels.

### 5.4 Battery
- Measure drain off USB power, over a 1–2 hour session, at fixed brightness.
- Compare with the boundary occlusion on and off. The README says the black zone reduces
  power use; this is the test that shows whether and by how much.
- At least one light core (Gambatte) and one heavy one (Beetle PSX HW).

**Verification:** figures recorded in `PROFILING.md` with method, and the README claim either
backed by the number or reworded.

### 5.5 16 KB on real hardware
- Run on an arm64 phone with 16 KB pages. Everything so far is the page-size check on every
  library plus an x86_64 emulator running arm64 code translated.
- Either the owner's phone after a 16 KB update, or a user report whose Copy Diagnostics
  shows `Page size: 16 KB` with no "translated" note.

**Verification:** every system launches with no compat-mode dialog on that device.

### 5.6 In-app help
- A Help screen reachable from Settings and the library: using the Pocket Taco, adding ROMs
  and BIOS files, the boundary handle, save states versus SRAM saves, what softcore means.
- Short, and fact-checked against the app the way the settings notes were on 2026-09-10.

**Verification:** a new user can get from install to playing without the README.

**Exit criteria for v0.3:** NES and Sega CD playable; the library handles twelve systems;
battery measured; 16 KB confirmed on hardware; help screen in place.

---

## Phase 6 – Achievements done properly (v0.4)

**Goal:** make achievements correct and clear, and decide on hardcore with the facts in hand.
The item numbers and section references are from `RETROACHIEVEMENTS-COMPLIANCE.md`.

### 6.1 Cheap fixes (hours each, worth doing regardless of hardcore)
1. ~~Guard `onLoadSlot` on hardcore mode — a real check, not a hidden button (B4).~~ Done 2026-09-14.
2. ~~Force a game reset when switching casual to hardcore (B7), using the existing
   `EXTRA_FORCE_RELOAD_ROM_URI` reload path.~~ Done 2026-09-14.
3. ~~On-screen hardcore indicator (E2), same pattern as the TURBO badge.~~ Done 2026-09-14.
4. ~~Add the active core to the user agent (C1).~~ Done 2026-09-14.
5. Resume-on-launch drops to casual (B6).
6. Upstream links in the About licence list (F2).

### 6.2 Medium
7. Offline unlock queue with retry (A4) — an unlock earned without signal is not lost.
8. A real privacy policy (F5). Mostly writing, but it must be exact.
9. Measured-progress display (A2b), e.g. "37 / 100 coins".

### 6.3 Hardcore (stretch)
10. Hardcore submission path: `hardcore = 1`, gated on every 6.1 rule holding.
11. Rich Presence (A3).
12. Leaderboards (A3).

6.3 is a project in its own right, and section D means eligibility cannot be applied for
yet regardless. Start it only as a deliberate decision, not because 6.1 went quickly.

**Verification:** each fix exercised on device; the compliance doc's standing table updated
to match.

**Exit criteria for v0.4:** 6.1 and 6.2 done; a written decision on 6.3.

---

## Phase 7 – Reach (v1.0)

**Goal:** remove what keeps TacoBoy below 1.0, and hear from the people using it.

### 7.1 Raise targetSdk
- `targetSdk 33` is now the only thing keeping the app off Google Play. Raise it to Play's
  current minimum and work through each intervening level's behaviour changes, such as
  edge-to-edge enforcement and predictive back, on device.
- Whether to publish on Play is a separate decision. Snes9x and Genesis Plus GX are
  non-commercial, which rules out a paid listing but not a free one.

### 7.2 Upstream fixes worth taking
From `UPSTREAM.md`, cherry-picked with a reason each:
- `2f2aff7` — texture unbinding in the shader chain. Small, in `video.cpp`, and an area where
  a texture-binding problem was already hit.
- `82ef1a7` / `61425f4` / `e04ef25` — CUT shader refinements. These are Upscale 1–3.
- Read `4d3427c` (viewport alignment) against the integer scaling work before deciding.

### 7.3 Core option descriptions checked
- The 61 descriptions are verified to exist and map to live options, but not checked against
  what each option actually does. Change each on device and confirm the description is true.

### 7.4 Decide the permanent scope
The CHANGELOG gives three reasons TacoBoy is below 1.0: softcore-only achievements,
`targetSdk 33`, and arm64 only. 7.1 resolves one. The other two need a decision, recorded
here:
- **arm64 only** — keep (32-bit ARM phones are now rare, and it cuts the native build to a
  quarter) or add ABIs.
- **Softcore only** — settled by Phase 6's decision on 6.3.

Either answer is fine for 1.0, provided it is a stated choice rather than an unfinished one.

### 7.5 Feedback
- GitHub issue templates for bugs and system requests, asking for the Copy Diagnostics block.
- A small group of testers across phone sizes and OEMs, Pocket Taco users first.
- Keep a prioritised list of what comes back.

**Exit criteria for v1.0:** targetSdk raised; upstream fixes taken or declined with a reason;
option text verified; permanent scope written down; no known critical issues from testers.

---

## Parked and declined

Considered and deliberately not on the plan. Each can come back with a reason.

| Item | Status | Why |
|---|---|---|
| Core downloader | Declined | Bundled cores are pinned, verified and 16 KB-aligned; downloads would give that up. |
| N64 and other analog-heavy systems | Declined | The Pocket Taco has no analog sticks. |
| Landscape | Declined | The clamp is held in portrait. |
| Resampler choice | Declined | libretrodroid has exactly one resampler. |
| Per-game core override | Parked | Only PS1 has a choice of core. Revisit if a second system gets one. |
| Online metadata (year, genre) | Parked | Box art covers the library's needs so far. |
| Auto-hide the boundary handle | Parked | The first-run hint solved discoverability. |

## Candidate systems after Phase 5

All fit the Pocket Taco's controls, and each needs a core built from source like the rest.
Listed for reference, not commitment.

| System | Core | Note |
|---|---|---|
| PC Engine / TurboGrafx-16 | Beetle PCE Fast | Two buttons plus Run/Select. CD titles need a BIOS. |
| Neo Geo Pocket / Color | Beetle NeoPop | D-pad plus two buttons. |
| WonderSwan / Color | Beetle Cygne | Has a vertical mode, which suits a portrait clamp. |
| 32X | PicoDrive | A second Sega core; low demand. |
| Atari 2600 | Stella | Simple controls. |

## Shipped cores

For reference. Build commits and recipes are in the 2026-09-11 CHANGELOG entry.

| System | Core |
|---|---|
| GB, GBC | Gambatte |
| GBA | mGBA |
| SNES | Snes9x |
| Mega Drive, Master System, Game Gear, SG-1000 | Genesis Plus GX |
| Atari Lynx | Handy |
| PS1 | SwanStation (default) or Beetle PSX HW |
