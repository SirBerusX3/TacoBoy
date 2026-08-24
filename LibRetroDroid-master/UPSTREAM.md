# Upstream (LibretroDroid) relationship

Established 2026-08-24, when `upstream` was added as a git remote for the first time.
Upstream is [Swordfish90/LibretroDroid](https://github.com/Swordfish90/LibretroDroid).

Regenerate any of this with `tools-upstream-base.py` (which upstream commit are we on?)
and `tools-upstream-report.py` (what has upstream done to the files we changed?).

## Where this fork sits

The module arrived as a downloaded `master` ZIP, not a clone — the giveaway is that
`oboe` and `libretro-common` were empty, which is what happens to submodules in a ZIP —
so no base commit was ever recorded. It was recovered by content: for each upstream
commit, count how many of our 85 vendored files have a byte-identical blob.

- **56 of 85 files are byte-identical to upstream**, and identical across every upstream
  commit from `e04ef25` (2025-11-28) through `8835c30` (2026-05-24, current master).
  Upstream has not touched those files in that window, so **for everything we have not
  modified, we are already current with master.**
- **29 of 85 differ from every upstream revision.** Those are ours.

## The 29, split by what syncing would actually cost

**Ours alone — not in upstream at all, can never conflict (4):**
`achievements.cpp/.h`, `achievementsmemory.cpp/.h`. The whole RetroAchievements
integration is our own work, not something inherited and modified.

**Modified by us, untouched upstream since 2025-06 (10):** `environment.cpp/.h`,
`renderers/es3/es3utils.cpp/.h`, `renderers/es3/framebufferrenderer.cpp/.h`,
`renderers/es3/imagerendereres3.cpp`, `renderers/renderer.h`, `utils/utils.cpp`,
`vfs/vfs.cpp`. No merge pressure; our versions simply stand.

**Modified by us AND moved upstream (15).** This is the entire cost of ever syncing:
`CMakeLists.txt`, `immersivemode.cpp/.h`, `libretrodroid.cpp/.h`,
`libretrodroidjni.cpp/.h`, `shadermanager.cpp`, `video.cpp/.h`, `videolayout.cpp/.h`,
`GLRetroView.kt`, `GLRetroViewData.kt`, `LibretroDroid.java`.

## What upstream actually has that we don't

Only four changes matter, and only two are clearly worth taking:

| upstream commit | date | relevance |
|---|---|---|
| `4d3427c` Implement viewport alignment | 2025-10 | **Overlaps our own work.** Touches `videolayout.*`, which is exactly where integer scaling was built. Upstream may have solved some of the same problem differently — read before merging; this one could be redundant *or* conflicting. |
| `82ef1a7`/`61425f4`/`e04ef25` CUT shader updates | 2025-07..11 | **Directly relevant.** We ship CUT as Upscale 1–3 and modified `shadermanager.cpp` for the max-size FBO. Upstream refined the same shaders. |
| `2f2aff7` Fix texture unbinding in shader chain | 2025-11 | **Directly relevant.** We hit a texture-binding problem in this area (see CHANGELOG). Small, self-contained, in `video.cpp`. |
| `0ebd299` Core interaction methods on other threads (with guards) | 2026-02 | Possibly relevant to crash hardening — thread-safety guards around core calls. |
| `c148a6c` Rename ambientMode → immersive mode | 2025-11 | Cosmetic rename plus a config knob. Touches many of our files for little gain. Low priority. |

## Policy: cherry-pick, don't track

Upstream is a mature, low-velocity project — about ten commits in the thirteen months to
August 2026. Continuous syncing would idle almost all the time while costing a manual
three-way merge whenever it did fire, because our divergence sits *inside* the module.
Take individual commits when there is a reason to, and read them first.

**Upstream will not solve the 16 KB page alignment problem for us.** Checked 2026-08-24:
upstream's build config is identical to ours — AGP 8.4.0, Kotlin 2.0.21, compileSdk 33,
CMake 3.22.1. Its "Modernize project" commit did not move the toolchain past what we
already have. 16 KB alignment needs AGP 8.5.1+/NDK r27+, and taking that step is ours to
own. See the note in Settings > Advanced.
