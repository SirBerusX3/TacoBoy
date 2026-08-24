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
| B4 | **Loading save states ALWAYS blocked** | ⛔ | `TacoBoyActivity.onLoadSlot` has **no hardcore check** — it loads unconditionally. Hardcore only *hides* the slot UI (`TacoBoyActivity:517`). A UI-only guard is exactly what section G auto-fails on |
| B5 | Rich Presence/Leaderboards cannot be disabled in hardcore | N/A | Neither exists yet |
| B6 | **Resume/quick-resume must drop to Casual** | ❌ | "Resume on Launch" restores the last ROM with no mode change |
| B7 | **Casual → hardcore mid-session must force a full game reset** | ⛔ | `SettingsActivity:1721` just writes the pref. Section G: "The ability to switch to hardcore mode without a reset of the game" is an automatic rejection |
| B8 | States creatable in hardcore but not loadable | ⚠️ | Both are hidden together; the rule wants creation allowed, loading blocked |
| B9 | No memory editors/debuggers/TAS | ✅ | None exist |

## C. Identity and integrity

| # | Requirement | Status | Evidence / note |
|---|---|---|---|
| C1 | Unique, stable, **incrementing** user agent | ⚠️ | `RetroAchievementsClient.kt:48` sends `TacoBoy/1.0 (Android)`. Unique, but the version is hardcoded and never increments, and the core is not named. Its own comment says the version was hardcoded because the project had no `versionName` — **that changed 2026-08-24**, so this is now a small fix |
| C1b | Target format | ⚠️ | Wants `EmulatorName/v1.0.0 (OSName 10.0) core_name/v0.5.0`. Core segment is "strongly advised" for multi-core emulators, and TacoBoy ships seven |
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
| E2 | **Hardcore state visibly indicated during play** | ❌ | No on-screen indication of mode. The TURBO badge added 2026-08-24 is exactly the pattern this needs |

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
| Loading save states in hardcore | ⛔ **at risk** — UI-only guard (B4) |
| Rewind / slo-mo / frame advance in hardcore | ✅ none exist |
| Gameplay-altering cheats in hardcore | ✅ none exist |
| Switching to hardcore without a game reset | ⛔ **fails today** (B7) |
| Non-unique user agent | ✅ unique (though see C1) |
| Undisclosed history of another emulator's UA | ✅ |
| Non-commercial cores + any commercialization | ✅ |
| Privacy policy with placeholders/contradictions | N/A until one exists — then must be exact |

## If this is ever pursued, in cost order

**Cheap (hours each), and worth doing regardless of compliance:**
1. Guard `onLoadSlot` on hardcore mode — a real check, not a hidden button (B4, auto-fail)
2. Force a game reset when switching casual → hardcore (B7, auto-fail); the
   `EXTRA_FORCE_RELOAD_ROM_URI` reload path already does exactly this
3. On-screen hardcore indicator (E2) — same pattern as the TURBO badge
4. Build the user agent from `versionName` + Android version + active core (C1)
5. Resume-on-launch drops to casual (B6)
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
