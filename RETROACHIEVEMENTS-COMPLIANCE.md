# RetroAchievements hardcore compliance — gap analysis

Audited 2026-08-24 against `RetroAchievementsRequirements` (RA's published hardcore
compliance page). Not urgent: nothing here blocks anything TacoBoy does today, and section D
means it cannot even be applied for yet. This exists so the requirements are tracked against
real code rather than remembered.

**Status key:** ✅ met · ⚠️ partial · ❌ missing · ⛔ auto-fail (section G) · N/A

**The single most important fact:** TacoBoy submits unlocks as **softcore only**, by design —
`RetroAchievementsClient.kt:234` hardcodes `hardcore = 0`, and `AchievementsSession`'s class
doc says hardcore was ruled out of scope because it requires disabling save states. So this is
not "a few rules away from compliant"; the hardcore submission path does not exist yet. That
is a feature decision to revisit, not a bug.

## A. RetroAchievements features

| # | Requirement | Status | Evidence / note |
|---|---|---|---|
| A1 | Achievement list accessible in-emulator | ✅ | `AchievementsActivity`, reachable from the library |
| A2 | Triggers evaluate correctly | ✅ | Native `rc_runtime_t` via `achievements.cpp`; hardware-confirmed live unlocks |
| A2b | Measured/Trigger flags visible in list **and** during gameplay | ⚠️ | Unlock toasts exist; **Measured progress display not implemented** — needs checking against a game that uses it |
| A3 | **Rich Presence** | ❌ | No implementation anywhere |
| A3 | **Leaderboards** | ❌ | No implementation anywhere |
| A4 | **Offline unlock queueing** | ❌ | Unlocks are submitted immediately; a failed submit is not cached for retry |
| A5 | Hit counts stored in save states | ❌ | Recommended, not required. `serializeState` is the core's state only; the rcheevos runtime is not included |
| A6 | RAIntegration DLL (Windows) | N/A | Android only |
| A7 | Standard save formats | ✅ (likely) | SRAM comes from the core's own `serializeSRAM`, written as `.srm`; matches other libretro frontends |

## B. Hardcore rules enforcement

| # | Requirement | Status | Evidence / note |
|---|---|---|---|
| B1 | Cheats disabled in hardcore | ✅ | No cheat engine, no Game Genie/GameShark support, no cheat file loading |
| B2 | Rewind disabled | ✅ | No rewind feature exists |
| B3 | Slowdown and frame advance disabled | ✅ | Neither exists. Fast-forward is speed **up**; B lists only slowdown and frame advance, and G repeats "rewind/slo-mo/frame advance" — so fast-forward appears permitted. Worth confirming with RA rather than assuming |
| B4 | **Loading save states ALWAYS blocked** | ✅ | Since 2026-09-14. `TacoBoyActivity.onLoadSlot` refuses to load while the running game is hardcore, before the state reaches the core; the disabled Load button is only the visible half. Seen on device: Load disabled in hardcore, enabled again once hardcore is turned off |
| B5 | Rich Presence/Leaderboards cannot be disabled in hardcore | N/A | Neither exists yet |
| B6 | **Resume/quick-resume must drop to Casual** | ✅ | Since 2026-09-14, by not resuming at all in hardcore: "Resume on Launch" leaves a hardcore player at the library prompt, with the last game kept for them to pick. Its resume boots fresh rather than restoring a state, so "drop to casual" would mean silently starting a casual game the player never chose; not starting one is the stricter reading. Seen on device |
| B7 | **Casual → hardcore mid-session must force a full game reset** | ✅ | Since 2026-09-14. A running game keeps the mode it loaded in (`sessionHardcore`); returning to it with the preference newly on reloads it through the Reset path (`enforceHardcoreTransition`). Hardcore → casual applies at once, as allowed. Seen on device both ways |
| B8 | States creatable in hardcore but not loadable | ✅ | Since 2026-09-14. Slots stay visible with Save enabled and Load disabled; a save in hardcore was seen to update its slot |
| B9 | No memory editors/debuggers/TAS | ✅ | None exist |

## C. Identity and integrity

| # | Requirement | Status | Evidence / note |
|---|---|---|---|
| C1 | Unique, stable, **incrementing** user agent | ✅ | Version from `BuildConfig.VERSION_NAME` since 2026-09-10, so it increments with every release. Was `TacoBoy/1.0 (Android)` before that |
| C1b | Target format | ✅ | Since 2026-09-14: `TacoBoy/0.2.1 (Android 17) mednafen_psx_hw_libretro_android/0.9.44.1-GLES3_d97afa8`, seen on device. The core segment follows RetroArch's construction and is sent on every call made while a game runs; library and Settings calls send the first two segments only |
| C2 | No undisclosed history of using another emulator's UA | ✅ | Has always sent its own; never publicly released under any other identity |

## D. Eligibility timeline

Six months of public availability required, of "the emulator, or the parent emulator it is
forked from". **Genuinely ambiguous for this project** and worth asking RA directly rather
than assuming either way:

- *For:* TacoBoy is forked from LibretroDroid, which has been public for years (tags v0.1.0 →
  0.9.0) and ships inside Lemuroid. The clause names the parent explicitly.
- *Against:* LibretroDroid is a **library**, never a publicly available emulator in itself —
  Lemuroid is the shipped emulator, and TacoBoy forked the library rather than Lemuroid. The
  stated rationale ("track record of stability, community trust") is about the applying client.
- *The cores are not the emulator for this purpose:* section C's user-agent format has
  separate fields for emulator **and** core, and every requirement here (API conversation,
  hardcore enforcement, achievement UI, offline queueing) is client-side work no core does.

Under every reading, releasing publicly sooner starts whatever clock applies, costs nothing,
and loses nothing.

## E. Defaults and UX

| # | Requirement | Status | Evidence / note |
|---|---|---|---|
| E1 | Hardcore one tap away if not default | ⚠️ | Lives in Settings > Achievements — several taps in |
| E2 | **Hardcore state visibly indicated during play** | ✅ | Since 2026-09-14. A red HARDCORE badge at the top centre for the whole of a hardcore session, following the running game's mode rather than the preference, so it cannot claim hardcore before the reset that makes it true. Seen on device |

## F. Transparency and legality

| # | Requirement | Status | Evidence / note |
|---|---|---|---|
| F1 | Monetization disclosure / features matrix | N/A | No monetization, ads or IAP |
| F2 | Published listing of every shipped FOSS core + licence | ⚠️ | **Built 2026-08-24** — Settings > About lists all ten components and their licences. Missing the "relevant upstream links" the requirement also asks for |
| F3 | No non-commercial cores alongside commercialization | ✅ | Snes9x and Genesis Plus GX are non-commercial; TacoBoy has no monetization. The decision to keep the donation link on the repo rather than in the app preserves this — see CHANGELOG 2026-08-24 |
| F4 | GPL/LGPL/MPL obligations satisfied | ⚠️ | About tab has a Source Code row, but `SOURCE_URL` is blank ("Not set yet") until the repo is published |
| F5 | **Privacy policy** (retention, server locations, GDPR) | ❌ | None exists. Required, and section G auto-fails a policy with placeholders or contradictions — so it must be written properly or not at all |

## G. Auto-fail criteria — current standing

| Criterion | Standing |
|---|---|
| Loading save states in hardcore | ✅ blocked in code (B4) |
| Rewind / slo-mo / frame advance in hardcore | ✅ none exist |
| Gameplay-altering cheats in hardcore | ✅ none exist |
| Switching to hardcore without a game reset | ✅ forces a reset (B7) |
| Non-unique user agent | ✅ unique, in RA's full format (C1, C1b) |
| Undisclosed history of another emulator's UA | ✅ |
| Non-commercial cores + any commercialization | ✅ |
| Privacy policy with placeholders/contradictions | N/A until one exists — then must be exact |

## If this is ever pursued, in cost order

**Cheap (hours each), and worth doing regardless of compliance:**
1. ~~Guard `onLoadSlot` on hardcore mode — a real check, not a hidden button (B4, auto-fail)~~ — done 2026-09-14
2. ~~Force a game reset when switching casual → hardcore (B7, auto-fail)~~ — done 2026-09-14,
   through the `EXTRA_FORCE_RELOAD_ROM_URI` reload path as planned
3. ~~On-screen hardcore indicator (E2) — same pattern as the TURBO badge~~ — done 2026-09-14
4. ~~Build the user agent from `versionName` + Android version + active core (C1)~~ — done 2026-09-14
5. ~~Resume-on-launch drops to casual (B6)~~ — done 2026-09-14, as no resume in hardcore
6. Upstream links in the About licence list (F2)

**Medium:**
7. Offline unlock queueing with retry (A4)
8. Write a real privacy policy (F5) — mostly a writing job, but must be exact
9. Measured-progress display (A2b)

**Large — the real cost:**
10. **Hardcore submission path**: `hardcore = 1`, gated on every rule above holding
11. **Rich Presence** (A3)
12. **Leaderboards** (A3)

Items 1–6 are worth doing on their own merits — they are correctness and clarity fixes that
happen to also be compliance items. Items 10–12 are a project, and only worth starting if
hardcore compliance is actually a goal.
