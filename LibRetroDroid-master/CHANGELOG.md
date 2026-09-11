# TacoBoy Changelog

Working log of changes made to this LibRetroDroid fork for the GameSir Pocket
Taco project. Kept up to date so a new session can pick up context without
re-deriving it. See `roadmap.md` for the longer-term plan; this file tracks
what's actually been done against it.

## 2026-09-11 (page size in Copy Diagnostics)

**Diagnostics now report the memory page size**, as `Page size: 4 KB` or `Page size: 16 KB`,
read from `Os.sysconf(_SC_PAGESIZE)`, which is available from API 21 and so across the whole
`minSdk` range.

The 0.2.1 release notes ask anyone with a 16 KB device for a report, and without this the
report is only as good as its author's description. "Works on my phone" proves nothing about
16 KB support unless the phone is known to use 16 KB pages, and most people have no ADB to
run `getconf PAGESIZE`. With the page size in *Copy Diagnostics*, the one fact that decides
whether a report counts comes with it by default. The About note beside the block now says so
directly, asking 16 KB users to send a copy even if everything works.

That note enumerates what the block holds, and it was already slightly wrong: it never
mentioned the on-screen pad line. It now lists all six lines, since a list of contents goes
stale the moment the contents change.

Seen rendering on the SM-S938B as `Page size: 4 KB`, matching `getconf PAGESIZE` of 4096. The
16 KB case is `16384 / 1024` through the same arithmetic but has not been observed on a device.
From 0.2.1 no build of TacoBoy runs in page-size compat mode, so the value is the device's real
page size; whether compat mode would change it for an older build is not established, which
is part of why the version line sits above it.

## 2026-09-11 (0.2.1: the 16 KB release)

**Version 3 / 0.2.1**, released to carry the 16 KB fix. 0.2.0 went out with an Advanced-tab
note claiming 16 KB compatibility the app did not have: on a 16 KB device it ran in page-size
compat mode, with a warning on every launch, beside text saying it was compatible. 0.2.1 makes
the claim true rather than only rewording it.

**Beetle's OpenGL renderer has now been run** in the rebuilt core, closing the one path the
entry below records as untested. It works. The texture artefacts documented earlier are still
there, worse in some games than others -- which is what an unchanged renderer should show,
since the source commit is the same. They predate this work and are not caused by it.

**Updates over 0.2.0 in place.** Same applicationId, same signing key and a higher
versionCode, so an existing install keeps its saves, BIOS files, settings and ROM folder
grant. That is the first time a public TacoBoy has been able to update rather than install
alongside, and the release notes say so, since 0.2.0's had to warn the opposite.

**Still not run on a 16 KB phone.** The evidence is every library checked as shipped, plus an
x86_64 16 KB emulator running the arm64 code through translation. The owner's phone may yet
receive a 16 KB update; when it does, that is the test that settles it.

## 2026-09-11 (all six cores rebuilt at their shipped commits: 16 KB compatible)

**TacoBoy is 16 KB compatible.** All six cores with a misaligned RELRO segment are rebuilt
from upstream source, `tools-check-16kb.sh` passes all nine libraries in the release APK,
and the 16 KB emulator that raised "RELRO alignment check failed" now launches the app with
no dialog at all. The Advanced tab's note, which described compat mode, now says so.

### The first attempt built the wrong source

Building each core from upstream master looked right and was not. Every rebuild was checked
against the binary it would replace, and two failed in ways alignment could never reveal:

  - **snes9x lost `snes9x_gfx_hires`.** Master replaced it with `snes9x_hires_blend`, a
    different option rather than a rename, so TacoBoy's hi-res setting would have silently
    done nothing. Master also exported 30 more `retro_*` functions than what shipped.
  - **Beetle would not compile.** Newer libretro-common uses ARM SHA intrinsics that clang 17
    (NDK 26.1) refuses to inline into a function built without the `sha2` target feature.

Master changes more than linker flags. The fix was to find out what actually shipped: most
cores embed their git hash in the version string, so it could be read straight out of each
binary -- `1.60 bd9246d`, `v1.7.4 b7e79b3`, `0.9.44.1-GLES3 d97afa8`, and for mgba
`0.11-219-e31759b`. Handy, gambatte and mgba had been built at their shipped commits by
coincidence; snes9x, Genesis Plus GX and Beetle had not. Rebuilt at the right commits, both
failures disappeared. The shipped snes9x turned out to be from 2019, very likely inherited
from the LibretroDroid sample, and at `d97afa8` Beetle predates the SHA intrinsics entirely.

### The recipe

| core | upstream | commit | build |
|---|---|---|---|
| handy | libretro/libretro-handy | `bc55d46` | `jni/` |
| gambatte | libretro/gambatte-libretro | `d9d6cd0` | `libgambatte/libretro/jni/` |
| mgba | libretro/mgba | `e31759b` | CMake |
| snes9x | libretro/snes9x | `bd9246d` | `libretro/jni/` |
| genesis_plus_gx | libretro/Genesis-Plus-GX | `b7e79b3` | `libretro/jni/` |
| mednafen_psx_hw | libretro/beetle-psx-libretro | `d97afa8` | `jni/`, plus `HAVE_HW=1` |

All with NDK 26.1.10909125. Clone with full history and set `git config core.abbrev 7`
first, so the embedded version string matches what shipped. The five `jni/` builds run
from *inside* the listed directory:

```
ndk-build NDK_PROJECT_PATH=. APP_BUILD_SCRIPT=Android.mk NDK_APPLICATION_MK=Application.mk     APP_ABI=arm64-v8a APP_PLATFORM=android-21     "APP_LDFLAGS=-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384"
# libs/arm64-v8a/libretro.so  ->  jniLibs/arm64-v8a/<core>_libretro_android.so
```

mgba has no Android makefile; libretro builds it with CMake, using the CI's own `CORE_ARGS`:

```
cmake -S . -B build-android -G Ninja     -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake     -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-21 -DCMAKE_BUILD_TYPE=Release     -DLIBMGBA_ONLY=ON -DBUILD_LIBRETRO=ON     "-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384"
cmake --build build-android --target mgba_libretro
llvm-strip --strip-unneeded -o libmgba_libretro_android.so build-android/mgba_libretro.so
```

The strip matters: CMake's Release build leaves symbols in, and unstripped it is 6.3 MB
against 2.4 MB. The output keeps the `lib` prefix the 2026-08-24 entry explains.

Gambatte's upstream `Android.mk` already passes `-z max-page-size=16384`, and its buildbot
RELRO was still misaligned: direct confirmation that libretro applied half the fix.

### Verified against the originals, not against expectations

Each rebuild was compared with its predecessor pulled from git history, on four counts:

| core | RELRO | `retro_*` API | source commit | TacoBoy's option keys |
|---|---|---|---|---|
| handy | bad -> aligned | identical (46) | `bc55d46` = `bc55d46` | 5/5 |
| gambatte | bad -> aligned | identical (46) | `d9d6cd0` = `d9d6cd0` | 5/5 |
| mgba | bad -> aligned | identical (25) | `e31759b` = `e31759b` | 5/5 |
| snes9x | bad -> aligned | identical (25) | `bd9246d` = `bd9246d` | 6/6 |
| genesis_plus_gx | bad -> aligned | identical (53) | `b7e79b3` = `b7e79b3` | 8/8 |
| mednafen_psx_hw | bad -> aligned | identical (55) | `d97afa8` = `d97afa8` | 27/27 |

The option-key column is the one that caught snes9x. `CoreOptions.kt` refers to each option by
key string, so a key missing from the core means a setting that silently does nothing -- the
same failure mode as the Lynx hashes: nothing visibly breaks.

Version strings are byte-identical for five of the six. mgba's reads `0.11-10126-e31759b`
where the buildbot's read `0.11-219-e31759b`. mgba counts every commit in the clone
(`rev-list --count`), not commits since a tag, so the middle number records whatever clone
the buildbot used; a clone to depth 219 gives 3030, because of merges. The commit hash, which
is what identifies the source, matches.

### Tested on hardware

Every system was played on the SM-S938B -- GB, GBC, GBA, SNES, Mega Drive, Master System,
Game Gear, SG-1000, Lynx and PS1 -- with picture, sound and controls unchanged. Beetle ran
Army Men: Air Attack, a real-time 3D game, in its **software** renderer, since the OpenGL
renderer has the dithering and texture artefacts documented earlier. **Beetle's OpenGL path
has therefore not been run in the rebuilt core.** The risk is low: the source commit is the
same and the version string, `0.9.44.1-GLES3`, confirms it was compiled with the same GLES3
renderer.

It was confirmed that the phone ran the new build rather than assumed: the installed APK's
SHA-256 matched the built one, and the libraries Android extracted and loads from -- this is
legacy packaging, so it loads its own copies, not the APK's -- carried the new sizes and
aligned RELRO. `adb install -r` also kills the app, so no old process survived into testing.

### Correction to the 2026-09-10 entry

That entry attributed the emulator's "Unknown error" on `liblibretrodroid`, `swanstation` and
`libzstd-jni` to the x86_64 translation layer, and called a clean emulator result
"encouraging, not proof". The first half was wrong: once the six real failures were fixed,
those three entries disappeared along with the whole dialog, so they were fallout from the
six rather than an artefact of their own. The emulator result stands as real evidence. What
it still is not is arm64 16 KB hardware, which no one has run TacoBoy on yet.

### No new copies in core-backups/

The six previous binaries total about 31 MB and are all committed, so git history already
holds them exactly: `git show 0170c90:LibRetroDroid-master/app/src/main/jniLibs/arm64-v8a/<file>`
recovers any of them (Handy's is also kept as `*.pre-relro-2026-09-10.bak`). `core-backups/`
exists for builds that history cannot supply -- the 2026-08-16 Beetle predates the repository
-- and 31 MB of duplicates would undo some of the 644 MB purge for no gain.

## 2026-09-10 (Handy rebuilt from source: first of the six)

**Handy now passes all three 16 KB checks**, rebuilt from upstream source rather than taken
from the buildbot. It was the pilot for the six misaligned cores -- the smallest and simplest
build -- to prove the method before spending it on the rest.

| | buildbot Handy | rebuilt |
|---|---|---|
| RELRO end | `0x65000`, +0x1000 past 16 KB | `0x64000`, aligned |
| `retro_*` exports | 46 | the identical 46 |
| `tools-check-16kb.sh`, in the APK | FAIL | ok |

**The recipe**, so this core can be regenerated from source rather than only replaced:

```
git clone https://github.com/libretro/libretro-handy      # built at bc55d46, 2026-04-20
cd libretro-handy/jni
ndk-build NDK_PROJECT_PATH=. APP_BUILD_SCRIPT=Android.mk NDK_APPLICATION_MK=Application.mk     APP_ABI=arm64-v8a APP_PLATFORM=android-21     "APP_LDFLAGS=-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384"
# libs/arm64-v8a/libretro.so  ->  app/src/main/jniLibs/arm64-v8a/handy_libretro_android.so
```

NDK 26.1.10909125. It has to be run from *inside* `jni/`: libretro's Android makefiles build
source paths from `$(LOCAL_PATH)/..`, and invoked from the core's root that resolves to
`jni/jni/../lynx/...` and fails. `APP_PLATFORM=android-21` matches `minSdkVersion`.

Upstream's `Android.mk` passes no page-size flags at all. The buildbot's cores have 16 KB
`p_align` and 4 KB RELRO, which is what `max-page-size` without `common-page-size`
produces, so it evidently supplies the first flag only -- whether explicitly or through a
newer NDK's default, the effect is the same half-fix.

**Tested on hardware, not only gated.** Alignment proves a library will map on 16 KB; it
says nothing about whether the core was built correctly. Both Lynx formats were played on
the SM-S938B: Critter Championship (a headered `.lnx`, which also re-exercises the hashing
fix) and California Games (a headerless `.lyx`). Both ran normally. The log shows the core
loading each, nothing in the crash buffer, and two entries that look like errors and are
not: "BIOS file missing", expected because the Lynx boot ROM is optional, and "Invalid cart
(no header?) -- Guessing a ROM layout", which upstream Handy logs for every `.lyx` because
that format has no header by definition.

The buildbot build is kept as `core-backups/handy_libretro_android.so.pre-relro-2026-09-10.bak`,
following the convention the 2026-08-24 alignment work set.

**Five remain:** gambatte, mgba, snes9x, genesis_plus_gx, mednafen_psx_hw. Until all six
are done the app still runs in compat mode on 16 KB devices, so this changes nothing a user
can see yet -- it only proves the method works.

## 2026-09-10 (16 KB: the RELRO half was never checked)

**Correction to two earlier entries.** The 2026-08-24 entry says "every native library
TacoBoy ships is now 16 KB page-aligned", and the fact-check entry below says the alignment
note was "re-verified against the release APK". Both were true of what was measured and
false of what matters. On a 16 KB emulator Android refused the app outright:

> This app isn't 16 KB compatible. RELRO alignment check failed. This app will be run using
> page size compatible mode.

**Only one of the three things the loader requires had ever been tested.** For each LOAD
segment `p_align` must be a multiple of 16384 and `p_offset` must be congruent to `p_vaddr`
modulo 16384; and `PT_GNU_RELRO` must *end* on a 16384 boundary, because RELRO is
write-protected after relocation, and a segment ending mid-page cannot be protected without
also protecting the writable data after it. `tools-check-16kb.sh` checked `p_align` alone,
and the re-verification repeated its blind spot rather than testing the loader's rules.
Measured directly from the ELF program headers:

| library | p_align | offset/vaddr | RELRO end |
|---|---|---|---|
| gambatte, genesis_plus_gx, snes9x | ok | ok | +0x2000 past a 16 KB boundary |
| handy, mgba | ok | ok | +0x1000 |
| mednafen_psx_hw | ok | ok | +0x3000 |
| liblibretrodroid, swanstation | ok | ok | aligned |
| libzstd-jni | ok | ok | no RELRO segment |

Six of the seven prebuilt cores fail. Every remainder is a multiple of 0x1000, which is the
signature of a RELRO end padded to 4 KB.

**Why: LLD pads the RELRO end to `common-page-size`, not `max-page-size`.** Tested with NDK
26.1 / LLD 17 across five `.data.rel.ro` sizes: `-z max-page-size=16384` alone left the RELRO
end misaligned in four of the five; adding `-z common-page-size=16384` aligned all five.
This library was linked with the first flag only -- **it passed by luck**, a one-in-four
chance any change to its size could have undone. Both flags are passed now. Its segment
layout is byte-identical today, so the change costs nothing now and removes the luck.

**Newer cores do not help.** The buildbot nightlies of 2026-09-09/10 have the identical
defect for the same six, so the cause is the buildbot's toolchain, and replacing the files --
which is how gambatte and mgba were "fixed" on 2026-08-24 -- cannot fix this. The cores need
rebuilding from source with both flags.

**`tools-check-16kb.sh` rewritten** to test all three conditions, and to check the libraries
*as shipped*, extracted from the APK. It previously scanned `jniLibs/`, which holds 7 of the 9
libraries; `liblibretrodroid.so` is built here and `libzstd-jni` comes from Maven, so it had
never looked at either. Run against the 0.2.0 release APK it now fails with exactly the six
cores above and exits 1. A gate that cannot fail is not a gate, and the old one could not.

**External checkers share the old blind spot.** 16kbchecker.com passed all nine libraries on
ELF alignment -- including the six Android itself rejected. Its two warnings were Play Store
requirements, not this bug: compressed native libraries, and targetSdk 33. Its advice to set
`useLegacyPackaging = false` would be actively harmful here: it makes libraries load straight
from the APK, which *requires* 16 KB zip alignment, and AGP cannot produce that before 8.5.1.

**What users see.** On a 16 KB device running Android 16 or later, TacoBoy works, in page-size
compat mode, with a warning dialog on every launch. Not a crash. 4 KB devices are unaffected.
The Advanced tab's note claimed full compatibility and has been corrected to say this.

**A caveat about the emulator that found it.** It was `sdk_gphone16k_x86_64`, running
TacoBoy's arm64 code through `libndk_translation`. Its dialog also listed `liblibretrodroid`,
`swanstation` and `libzstd-jni` -- each passes every check -- with "Unknown error". That points
at the translation layer rather than those libraries, and it means a clean result on an x86_64
emulator is encouraging, not proof. Only arm64 16 KB hardware or an arm64 16 KB image is.

## 2026-09-10 (released publicly as v0.2.0)

TacoBoy is public: `SirBerusX3/TacoBoy`, GPL-3.0, with `v0.2.0` tagged and an
`app-release.apk` attached to the GitHub release.

**The tag exists because GPL-3 asks for the source corresponding to the binary someone was
given, and `main` moves.** A link to a branch answers "here is the project"; a tag answers
"here is what built the file you have". Verified from outside by downloading the asset
unauthenticated, the way a stranger would: HTTP 200, and its SHA-256 matches the checksum
printed in the release notes (`da8e1ca7...`), so the verification instructions are true
for the file people actually receive rather than for the one on this machine.

The release notes lead with what would otherwise become confused issues: arm64-v8a only,
no games or BIOS included, achievements softcore only, and -- most important for anyone
who had an earlier personal build -- that the applicationId change means this installs
*alongside* the old app rather than updating it, so saves and BIOS do not carry over.

### Two footguns specific to this repository

Both stem from `upstream` being a configured remote, and both were hit or nearly hit while
publishing:

  - **`gh` resolves to `Swordfish90/LibretroDroid`.** Creating the release without
    `--repo SirBerusX3/TacoBoy` failed with a request to push the tag to *upstream*. It
    failed safely, but every `gh` command here needs `--repo`.
  - **Never `git push --tags`.** Fetching upstream brought all 36 of LibretroDroid's
    version tags (`0.1.0` through `0.14.0`) into this repository. They are local only;
    `v0.2.0` was pushed by name. A blanket `--tags` would publish the lot and present
    years of releases that were never ours. Note upstream has its own `0.2.0` -- ours is
    `v0.2.0`, so they do not collide, but the resemblance is not helpful.

### Known and deliberate, carried into the next session

Not defects to be surprised by later; each was considered and left:

  - **Achievements are softcore only.** The two hardcore auto-fails -- no real guard on
    `onLoadSlot`, and no game reset when switching casual to hardcore -- are described with
    a cost-ordered fix list in `RETROACHIEVEMENTS-COMPLIANCE.md`. Section D means
    eligibility cannot be applied for yet regardless.
  - **The user agent still omits the active core**, which that audit's C1 asks for. It
    needs the client to know which core is loaded.
  - **`targetSdk 33`** keeps this off Google Play. Dropping `MANAGE_EXTERNAL_STORAGE`
    removed the other blocker, so the SDK level is now the only one.
  - **The 61 core option descriptions** are verified to exist and to map one-to-one to live
    options, but not checked against what each core actually does. That wants the options
    in front of a device.
  - **`versionCode 1` is spent** on builds that only ever existed on a test device. The
    first public build is 2.

**The signing key now matters more than it did this morning.** It has signed something in
public hands, so replacing it is no longer a private inconvenience -- every install would
have to be uninstalled. Backed up to cloud storage on release day, and the practice is a
copy at the end of every session.

## 2026-09-10 (pre-release pass: GPL source link, dead permissions, honest user agent)

Four things that were survivable while the only install was a personal test device and are
not survivable once strangers can download a build.

**The GPL source link was empty.** `SOURCE_URL` was `""`, so the About tab's Source Code
row rendered "Not set yet" -- and the note beside it says, in the app's own words, that
the licence "requires anyone who receives a copy of the app to be able to get its source
code". Falling back to "ask whoever gave you this build" is fine for a build handed to one
person and is not a source offer to the public. Now points at the repository. Verified in
the built release APK rather than in the source: the URL is present in `classes.dex`.

**Three permissions were declared and never used.** `MANAGE_EXTERNAL_STORAGE`,
`READ_EXTERNAL_STORAGE` and `RECORD_AUDIO` were all in the manifest; none was ever
requested anywhere in the app, and ROMs are reached through SAF tree URIs, which need no
storage permission at all. The libretrodroid module declares no permissions of its own, so
all three came from us and removing them was a manifest edit rather than a merger fight.

This is not tidiness. "All files access" and microphone on an emulator downloaded from
GitHub is the exact shape of a thing people assume is malware, and it bought nothing:
`RECORD_AUDIO` could not have worked regardless, because the runtime request it would have
needed was never written. LibretroDroid's native layer does expose a libretro microphone
interface, but no core bundled here uses one. The release APK now requests `INTERNET` and
`VIBRATE`, plus `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, which AndroidX adds itself.

**The user agent lied about the version.** It was the literal `TacoBoy/1.0 (Android)` while
`versionName` was `0.1.0`. RetroAchievements identifies clients by user agent and treats a
non-unique one as an auto-fail, so it has to be both distinctive and true; a version that
never existed makes anything RA sees from the field impossible to tie to a build. Built
from `BuildConfig.VERSION_NAME` now, so it cannot drift from the manifest again. That meant
turning `buildConfig` on, off by default since AGP 8, and importing `BuildConfig` from
`com.android.libretrodroid` -- the namespace package, the same reason `R` is imported from
there. Still no active core in the string, which the audit's C1 wants; that needs the
client to know which core is loaded and is left until it does.

**`LICENSE` copied to the repository root**, byte-identical to the module's. GitHub looks
for it there, and a GPL-3 project whose licence is invisible on its own front page is a bad
look for exactly the obligation it is meant to advertise.

**Version is now 2 / 0.2.0.** The rule is this file's own: two people running "TacoBoy"
should never be unable to say which one they have. Builds labelled 0.1.0 already exist on
a test device, and this one differs in applicationId, permissions, hashing and the source
link, so it cannot honestly share their number. Kept below 1.0 deliberately -- achievements
are softcore only, `targetSdk` is 33 and only arm64 ships. 1.0 would claim more than is
true.

Not blockers, and deliberately not done: the two RetroAchievements hardcore auto-fails
(section G) do not apply while unlocks are softcore only and disclosed as such, and section
D means eligibility cannot be applied for yet anyway. `targetSdk 33` is fine for
distribution outside Play.

## 2026-09-10 (info text fact-checked; Lynx hashes were wrong)

**The RetroAchievements settings note described an app from several weeks ago.** It
claimed identification worked for "GB, GBC, GBA, and SNES" only, that "PS1 isn't supported
yet", and that "achievement lists and unlock tracking haven't been built yet". All three
were false: `Ps1Hasher` implements `rc_hash_psx` end to end, `RomHasher` dispatches on
`GameSystem` across all ten systems, and `AchievementsActivity` plus `AchievementsSession`
have been listing achievements and submitting live unlocks for some time. Rewritten to
describe what ships, keeping the one limitation that is still true -- softcore only,
because `awardAchievement` still passes `hardcore = 0`.

**Checked, and left alone, because they are still correct:** the Hardcore Mode note (it
says unlock reporting is unchanged, and it is), the live-tracking note including the
separate-password detail, and the 16 KB alignment note -- re-verified against the release
APK, where every one of the nine bundled libraries is 16 KB aligned or better (snes9x is
64 KB, which satisfies it). All 61 `core_desc_*` strings map one-to-one to live options,
with none dead and none missing.

**Found while checking which systems identification actually covers: Lynx hashes were
wrong.** rcheevos' `rc_hash_lynx()` skips a 64-byte header when a file opens with the
magic; `RomHasher` special-cased only SNES and PS1 and sent everything else through a raw
MD5. Mapping all ten systems against rcheevos showed nine correct and Lynx the sole gap --
a headered `.lnx` produced a hash RetroAchievements has never seen, surfacing as the
ordinary "not recognized" message, which is exactly why it went unnoticed.

Two rcheevos quirks are matched deliberately rather than tidied up, because a hash is
worthless unless it is identical to RA's:

  - the magic is compared as **five** bytes against the literal `"LYNX"`, taking in its
    terminating NUL, so a file starting `LYNX!` is not headered
  - the guard is `> 64`, not `>=`, so a file exactly the size of the header is left alone
    rather than hashed as zero bytes

The decision is split into a pure `lynxHeaderSize(fileLength, leadingBytes)` with the SAF
reading kept in a wrapper, matching how `awardAchievementSignature` is arranged, and
`RomHasherTest` covers both quirks along with short reads. A truncated read counts as "no
header": fewer bytes than the magic means the magic was never confirmed.

**Verified against RetroAchievements' own hash library, not against the app's opinion of
itself.** `dorequest.php?r=hashlibrary&c=13` returns the 133 Lynx hashes RA knows; it is
public and unauthenticated, so this needed no account credentials and sent nothing but a
console ID. Hashing the real files on the device both ways and looking up each result:

| ROM | header | old full-file hash | new hash |
|---|---|---|---|
| Critter Championship (Aftermarket) | yes | not in RA's list | gameId 19715 |
| Timeloop (Aftermarket) | yes | not in RA's list | gameId 27007 |
| Z.A.P. (Aftermarket) | yes | not in RA's list | gameId 19714 |
| Running Knight (LynxJam 2023) | yes | not in RA's list | gameId 29053 |
| Block Out (USA, Europe) | **no** | gameId 11235 | unchanged |

So four Lynx games were not merely hashing differently, they were unrecognisable -- their
old hashes appear nowhere among the 133. Block Out is the control and matters as much: a
headerless file that happens to carry the `.lnx` extension, still resolving through the
plain full-file path. Skipping 64 bytes off every `.lnx` on the strength of its extension
would have broken it, which is why the magic is read rather than the filename trusted.

Worth noting for the next time hashing is touched: **a wrong hash and a game RA simply
does not have produce the identical "not recognized" toast.** There is no failure the user
can see, which is exactly how this survived from the day Lynx was added. Checking a hash
against a known-good value catches it; checking whether the UI looks happy never will.

**Confirmed on the device**, not just by hash lookup: all five Lynx games -- the four
headered ones and Block Out -- now open their achievement lists from "Check
RetroAchievements", where the four previously refused.

**Then the same check was run across every other system**, on the reasoning that if the
Lynx rule was wrong for two years nothing guaranteed the rest were right. Hashes were
computed on the device (`adb shell md5sum`, so no ROM copies left the phone) and looked up
in each console's public RA hash library:

| System | matched | missed |
|---|---|---|
| Game Boy | 12 | 0 |
| Game Boy Color | 12 | 0 |
| Game Boy Advance | 12 | 0 |
| SNES | 10 | 1 |
| Mega Drive | 12 | 0 |
| Master System | 12 | 0 |
| Game Gear | 12 | 0 |
| SG-1000 | 12 | 0 |

94 of 95. The single miss is `Yoshi's Cookie (USA).sfc`, and it is **not** a hashing
defect: the file is 524288 bytes, `524288 % 0x2000 == 0`, so it carries no copier header
and the plain hash is what rcheevos would compute too. Checked both ways to be sure --
neither the plain hash nor the header-skipped one appears among RA's 5201 SNES hashes, so
no rule would recognise this file. RA does not have this particular dump.

Our SNES rule was re-read against `rc_hash_snes()` while investigating and is equivalent:
rcheevos computes `(size / 0x2000) * 0x2000` and strips 512 bytes when the remainder is
512, which is what `size % 0x2000 == 512` says.

**Four dead strings removed** -- `achievements_list_earned_label`, `rom_scanning`,
`settings_coming_soon` and `settings_tooltip_close`, defined but referenced from nowhere.
Safe to delete because nothing in the app resolves strings dynamically; there is no
`getIdentifier` call anywhere, so a name that does not appear literally is genuinely
unused. 350 strings down to 346.

**Not done:** the 61 core option descriptions were checked for existence, not for whether
each still describes what its core actually does. That is a much larger job and wants the
options in front of a real device.

## 2026-09-10 (applicationId is now com.tacoboy)

**`com.android.gl2jni` is gone.** The app had shipped under the applicationId of the
LibretroDroid sample it was built from -- the build script's own comment called it a
placeholder to be set "before sharing a build", and with the repository now published
that moment had arrived. It is `com.tacoboy` from here.

**This is not a rename.** Android treats a changed applicationId as a different app, so
the build installs alongside the old one rather than over it, with an empty `filesDir`.
Everything keyed to the old ID is orphaned: save states (`filesDir/savestates/`), imported
BIOS files including the PS1 one, controller presets, `tacoboy_prefs` in full (settings
and the RetroAchievements API key), and the SAF grant on the ROM folder, which is issued
per package and must be given again. Box art and the ROM library cache regenerate on their
own. Done now, while the only install anywhere is one personal test device; after
distribution it would cost every user the same loss.

Migrating rather than starting over, on a debuggable build:

```
adb shell "run-as com.android.gl2jni tar -cf - -C /data/data/com.android.gl2jni files shared_prefs" > tacoboy-data.tar
adb shell "run-as com.tacoboy tar -xf - -C /data/data/com.tacoboy" < tacoboy-data.tar
```

Take the backup before uninstalling the old app; `run-as` cannot reach data that no longer
exists.

**`tools-profile.py` updated** -- its `PKG` constant drove every `adb` call in the
profiler, so it would have silently profiled a package that no longer exists.

**The namespace was deliberately left alone.** It is still `com.android.libretrodroid`,
which is what `R` and `BuildConfig` are generated into, and ten files import
`com.android.libretrodroid.R`. That is a separate change with a real diff, and bundling it
into an applicationId fix would have made a one-line change into an eleven-file one.
Nothing about the two has to match.

**The `merged_native_libs` trap did not fire this time** -- the note at the end of the
2026-08-24 signing entry warns that ABI or applicationId changes leave stale intermediates
that fail packaging with no useful message. Packaging succeeded without clearing anything.
Verified anyway rather than assumed: `aapt2 dump packagename` reports `com.tacoboy`, and
all seven cores plus `liblibretrodroid` and the zstd JNI library are present in the APK.

## 2026-09-10 (Windows reinstall: the signing key was lost; environment rebuilt)

**The release keystore no longer exists.** A corrupted bootloader forced a Windows
reinstall, and the key lived at `C:/Users/dcrot/.android-keystores/tacoboy-release.jks` --
inside the user profile, which the reinstall destroyed. No copy existed on any drive; the
whole machine was searched. **The path recorded in the 2026-08-24 entry above is dead.**

**A new key was generated** at `F:/Keys/tacoboy-release.jks` -- deliberately on a non-OS
drive, so that reinstalling Windows on C: cannot take it a second time. Same identity
(`CN=TacoBoy`), 4096-bit RSA, SHA384withRSA, PKCS12 rather than the proprietary JKS
format. The dead key's passwords are kept at `F:/Keys/OLD-keystore.properties.DEAD-KEY-LOST`
in case that `.jks` ever resurfaces from a backup.

**What this cost:** nothing, because TacoBoy is unreleased and installed on one personal
device. What it *would* have cost after distribution is every user uninstalling and losing
their saves -- v3 signing lineage cannot help, because rotation requires the old key. The
practice from here is a copy to cloud storage at the end of every session.

**Nothing else was lost.** All 1820 tracked files were verified byte-identical to `HEAD` by
re-hashing their contents rather than trusting size and timestamp, which a restore
preserves even when the bytes are wrong. `git fsck` clean. The cores and `core-backups/`
came through intact.

**The build environment was rebuilt from nothing**, and the setup section of the new root
README is written from it. Two findings worth keeping: Gradle 8.10.2 will not run on the
JDK 24+ that a current Adoptium install provides, so the daemon is pinned to JDK 21 via
`org.gradle.java.home` in `~/.gradle/gradle.properties` -- user-level, because the path is
machine-specific and would break any other clone. And Android Studio's setup wizard
installs only the newest SDK (API 37, build-tools 36), which is not what this project
pins; AGP fetches the NDK, CMake 3.22.1 and API 33/34 itself on the first build.

## 2026-09-10 (644 MB of dead weight purged; the build gets enough heap)

**A 644 MB heap dump was committed to the repository** -- `java_pid44204.hprof`, added by
"Drop dead weight inherited from the LibretroDroid sample", which is precisely the commit
that removed dead weight. Purged from history with `filter-branch`, taking `.git` from
229 MB to 48 MB. Every other file was verified byte-identical afterwards and all eight
commit messages preserved; the four commits from that one onward have new SHAs, and
nothing referenced them. Safe only because the repository had never been pushed anywhere.

**The dump existed because packaging runs out of memory.** `:app:packageDebug` has died of
an `OutOfMemoryError` on two machines now -- 2026-08-24 and again on 2026-09-10 -- each
time writing ~600 MB next to the build. `gradle.properties` set no heap limit at all.
It is now `-Xmx4096m` with heap dumps disabled, in the project file rather than the user
one because it is a property of this build and anyone who clones needs it. `*.hprof` is
ignored so a third one cannot be committed.

## 2026-09-10 (published to GitHub, privately)

**The project is now at `SirBerusX3/TacoBoy`**, private for the time being. Checked before
pushing, because history is permanent and a private repository can always be made public:
`keystore.properties`, `local.properties` and any `.jks` have never been committed in any
commit on any branch, and no credential is hardcoded in source -- the RetroAchievements API
key is user-supplied at runtime and `KEY_RA_SESSION_TOKEN` is a preferences key name, not a
value.

**A root README was added.** There had been none, so GitHub showed a bare file listing, and
the only README in the tree was upstream's -- opening "LibretroDroid is a simple C++
LibRetro frontend library" with no mention of TacoBoy. A GPL-3 derivative presenting itself
as an unlabelled copy of someone else's project is the wrong first impression to leave.

**Upstream has still not moved.** Fetched and checked: master is `8835c30` (2026-05-24),
unchanged since three months before this project began. Worth recording that "ahead" is not
measurable here -- the module came from a ZIP rather than a clone, so there is no common
ancestor and `git merge-base` against upstream returns nothing. A count of commits
"ahead" will look like 269 and mean nothing; it is just the whole of upstream's history.

## 2026-08-24 (release signing — and the first release build ever run)

**The release APK was unsigned, and an unsigned APK cannot be installed by anyone.** That was
the single hard blocker on handing a build to another person. There is now a release keystore
and a signing config, and `assembleRelease` produces `app-release.apk` verified under
signature schemes v1, v2 and v3.

**Where the credentials live.** The keystore is at `~/.android-keystores/tacoboy-release.jks`
— deliberately *outside* the repository, so that no mistake in `.gitignore` can commit it.
Credentials are in `keystore.properties` at the project root, which is gitignored, along with
`*.jks` and `*.keystore` as a second line of defence. **Both must be backed up**: lose the key
and no future build can update an existing install; users would have to uninstall, losing
their saves. There is no recovery.

**The config is deliberately optional.** A clone without the keystore still builds, and only
`assembleRelease` is affected. Hard-failing would mean nobody but this machine could build the
project — a poor trade for a GPL-3 project that must be able to hand out its source.

**Debug is signed with the same key.** Android refuses to update an installed app with one
signed by a different key, so mixed signing makes every swap between debug and release an
uninstall — taking ROM folder grants, BIOS files and save states with it. Matching them makes
the two interchangeable from here on.

**v3 signing enabled**, which records the signing lineage and is the only mechanism for
rotating to a new key later without every existing install refusing the update. Impossible to
add retroactively to builds already handed out.

**A release build had never been executed — until now.** Tested side-by-side under a temporary
`applicationIdSuffix` so the real install was never at risk (that suffix has been reverted;
the test app is uninstalled). It installed, granted a ROM folder, scanned the library, loaded
a core and ran a GBA game. R8 is off, so there was less to go wrong than usual, but "never
run" is not a state to release from.

**Found while doing it: the app told users it was LibretroDroid.** The *application* label was
still `LibretroDroid`, so a first-run folder grant asked to "Allow LibretroDroid to access
folder" for an app installed as TacoBoy — as did the Apps list, battery usage and permission
screens. The launcher looked correct only because `TacoBoyActivity` overrides the label for
its own entry. Now `TacoBoy` throughout. This is the sort of thing only a genuine first-run
finds, which is an argument for doing more of them.

Also added the `proguard-rules.pro` the build script has always named but which never existed
— harmless while `minifyEnabled = false`, and a trap waiting for whoever first turns shrinking
on. It documents what would need keeping: JNI entry points, and the zstd/xz codecs reached
reflectively during CHD decoding.

**Note for future ABI or applicationId changes**: both leave stale `merged_native_libs`
intermediates that fail packaging with no useful message. `rm -rf app/build/intermediates`
clears it.

## 2026-08-24 (drop dead weight inherited from the sample)

**arm64-v8a only.** The APK carried `armeabi-v7a`, `x86` and `x86_64` builds of
`liblibretrodroid` and `libzstd` — but the emulator cores are prebuilt binaries that only
exist for arm64, so on any other ABI the app installed and then had nothing to run. Six
megabytes of libraries for architectures that cannot emulate anything. `abiFilters
'arm64-v8a'` in both modules means such a device now declines the install instead of
accepting a broken one, and the native build does a quarter of the work it did.

**Removed `SampleActivity` and `VirtualGamePadConfigs`**, LibretroDroid's demo screen and its
pad layout. Nothing in TacoBoy referenced either; they were reachable only through their own
manifest entry. This is the activity whose launcher intent-filter once produced a duplicate
app icon — that can no longer recur, because the activity is gone rather than patched. It was
also an exported activity nothing needed.

**That removed a whole dependency**: `radialgamepad` was used only by the sample. TacoBoy has
its own `TouchControls`, which is a custom View precisely because the pad had to fit the
Pocket Taco's clamp geometry.

**Debug APK 17.2 MB → 13.8 MB; release APK is 11.5 MB** and now contains exactly nine native
libraries: the seven cores, `liblibretrodroid`, and `libzstd`. (The earlier release APK's
size was never recorded, so only the debug figure is a measured before/after.)

Verified on device afterwards: a Game Boy game runs at 60fps, and
`cmd package query-activities` reports exactly one launcher entry.

## 2026-08-24 (16 KB page alignment)

**Every native library TacoBoy ships is now 16 KB page-aligned.** Newer Android hardware is
moving to 16 KB memory pages, and a library whose LOAD segments are aligned to 4 KB cannot be
mapped on such a device — the app simply fails to load. The Advanced tab has carried a note
about this since long before it was actionable; that note is now accurate rather than a
warning.

**Measured rather than assumed, and only two things were actually broken.** Of the nine
native libraries in the APK, seven were already fine:

| library | before | after |
|---|---|---|
| Gambatte (GB/GBC) | 0x1000 | **0x4000** |
| mGBA (GBA) | 0x1000 | **0x4000** |
| Genesis Plus GX, Handy, Beetle PSX HW, SwanStation | 0x4000 | unchanged |
| Snes9x | 0x10000 | unchanged (64 KB is a multiple of 16 KB) |
| liblibretrodroid (ours) | 0x4000 | unchanged — NDK 26.1 already aligns it |
| libzstd-jni | 0x4000 | unchanged |

**The two broken ones could not be fixed by build settings**, because they are prebuilt
binaries from the libretro buildbot rather than anything compiled here. Replaced with current
buildbot builds, which are produced with a newer NDK and ship aligned. Both kept their
existing filenames — including mGBA's `lib` prefix, which does not match the buildbot's own
naming — so no code changed. Previous builds are in `core-backups/` as
`*.pre-16kb-2026-08-24.bak`. **Hardware-tested**: a GBA game and a Game Boy Color game both
load and run at 60fps on the replaced cores.

**Zip alignment turned out not to apply.** Native libraries are stored *compressed* in the
APK, in release as well as debug, because `minSdkVersion 21` puts AGP into legacy packaging.
Compressed libraries are extracted to the filesystem when the app installs and loaded from
there, so the loader never maps them out of the APK and their offsets within it are
irrelevant. Only the ELF alignment matters. **If minSdk is ever raised to 23+**, AGP will
switch to uncompressed libraries mapped directly from the APK, and 16 KB *zip* alignment will
then be required too — which needs AGP 8.5.1+ (this project is on 8.4.0).

**Not verified: actually running on a 16 KB device.** The test phone reports a 4096-byte page
size, so what has been confirmed is that every library is correctly aligned, not that the app
has been observed working on 16 KB hardware.

`tools-check-16kb.sh` verifies all of this and exits non-zero on failure, so it can gate a
release. Its test is arithmetic — a multiple of 16384 — rather than a glob for "0x1000", which
in a first version flagged Snes9x's perfectly valid 0x10000 as broken.

## 2026-08-24 (About tab — licences, source link, diagnostics)

Replaces roadmap 4.3's "Getting started" flow, which assumed a core install/update step this
app does not have (cores ship in the APK). What a new user — and more to the point, a new
*recipient of a build* — actually needs is attribution, a source link and a way to report
problems. Settings > About now carries version, source, support, licences and diagnostics.

**The source link is an obligation, not a nicety.** LibretroDroid is GPL-3 and TacoBoy is a
derivative of it, so anyone handed a build is entitled to the corresponding source. Handing
APKs to other Pocket Taco owners — which crash hardening just unblocked — is exactly when
that starts to matter. `SOURCE_URL` is currently blank and renders as "Not set yet"; **fill it
in before sharing a build.**

**Every bundled core's licence was read from that project's own source, not assumed:**

| component | licence |
|---|---|
| LibretroDroid (app base) | GPL-3.0 |
| mGBA — GBA | MPL-2.0 |
| Gambatte — GB/GBC | GPL-2.0 |
| Snes9x — SNES | **non-commercial** |
| Genesis Plus GX — GEN/SMS/GG/SG-1000 | **non-commercial** |
| Handy — Lynx | zlib-style, (c) 2004 K. Wilkins |
| Beetle PSX HW — PS1 | GPL-2.0 |
| SwanStation — PS1 | GPL-3.0 |
| rcheevos | MIT |
| Oboe | Apache-2.0 |

**Two of those constrain what TacoBoy may become.** Snes9x is "freeware for PERSONAL USE
only", and Genesis Plus GX's terms read: *"Redistributions may not be sold, nor may they be
used in a commercial product or activity."* That second clause is broad — not merely "may not
be sold" — and nothing may ever be gated behind payment while those cores ship. Handy was the
awkward one: the libretro repo carries no licence file at all, so the terms had to come from
the source headers.

**No donation link in the app, by decision.** One was built and then removed: a link on the
source repository is trivially reworded or removed if a core author ever objects, whereas one
compiled into an APK is already in every copy handed out. Since the source has to be public
anyway (GPL-3), the repository is the natural place for it. The source link is now the app's
only outbound link.

**Diagnostics block with a copy button**, holding build, Android version, device, selected PS1
core and pad state — nothing personal. It pairs with Export Logs: between them a bug report
arrives with the context whose absence would otherwise be the first three replies.

**Also added `versionCode`/`versionName` to `app/build.gradle`** (0.1.0 / 1, a placeholder to
be set): they were never defined, so the app had no version to report and two people could not
have said which build they were running.

**Removed two cores that shipped but were never used**: `libparallel.so` (parallel-n64's RSP
plugin) and `libretro-test-gl.so`, both LibretroDroid sample leftovers referenced nowhere in
the codebase. Moved to `core-backups/` rather than deleted, matching how the previous Beetle
build was kept. **APK 18.1 MB → 17.2 MB** — 0.9 MB, about 5%. (A first estimate of "15%" was
wrong: it compared raw file sizes, and the APK compresses those libraries.) The APK now ships
exactly the seven cores `GameSystem` references, which is also what the licence list claims.

## 2026-08-24 (performance profiling — roadmap 3.3)

**Profiled for the first time.** Full method, table and caveats in `PROFILING.md`; the
harness that produced it is `tools-profile.py`. Two findings worth acting on:

- **Beetle PSX HW costs about twice the CPU of SwanStation** — 65% of one core against
  28–36%, with and without a shader. That is the number to weigh against Beetle's colour
  handling when picking a PS1 core, and the reason to expect Beetle to be the first thing
  that struggles on a weaker phone.
- **Shaders are free at this output size.** LCD 31.0 against a 31.1 baseline, Upscale3 31.8,
  CRT identical to none; on Beetle, Upscale3 moves 65.4 to 65.2. They run on the GPU and the
  GPU is not the constraint here — Upscale3 being three passes does not change that.

Everything held a locked 60fps, so frame rate currently cannot tell these configs apart on
this device at all -- which is why a **second, headroom run** followed, removing the vsync cap
by running the core at 8x (`frameSpeed`) so fps becomes a throughput measure. It answers the
question roadmap 3.3 actually wanted answered:

- **GBA and SwanStation sustain >=8x real-time; Beetle stops at ~3.4x.** The two PS1 cores
  draw almost identical CPU at 8x (127% vs 122% of one core) -- SwanStation turns that into
  eight times real-time, Beetle into three and a half. Beetle does ~2.3x less work per unit of
  CPU, agreeing with the 1.9x measured at 1x by a different method.
- **There is room for another per-frame shader pass.** Upscale3 costs 3% of Beetle's ceiling
  (3.46x -> 3.34x) and ~1.5 CPU points on GBA, *at eight times normal speed*.
- **Thermal state moves the numbers more than any config choice.** The same Beetle config read
  >=4x from 38.6C and 3.25x from 48.6C, so every config is now gated on cooling to 42C first,
  with temperatures recorded. Beetle still enters its window at 46-47C because loading it
  heats the phone, so part of its 3.4x is thermal.

Also worth noting: the 2x fast-forward speed looks conservative -- everything except Beetle
could run considerably faster. Worst-frame times of 20–23ms against a 16.7ms budget appear in *every*
config including the lightest, which points at the display/scheduling path rather than at any
core or shader.

**The app now logs one perf line per second while Show FPS is on**, carrying system, core,
shader, fps and the worst frame interval of that second — so a profiling run can be driven
and read from adb instead of by watching the corner of the screen. The FPS overlay gained the
worst-frame figure for the same reason: an average of 60 is equally consistent with a steady
60 and with 70 quick frames plus a stall, and only the second is something you feel.

**The first run of this was wrong, and the reason is worth keeping.** Its prefs edit ran
`sed` through two layers of shell quoting; the delete half was mangled, so new values landed
*above* the surviving old ones — and SharedPreferences resolves a duplicated key to its
**last** occurrence. Every config silently fell back to the same saved GBA settings and
produced a clean-looking table of nine near-identical rows. The user spotted it by noticing
only GBA games ever appeared on screen. The harness now edits prefs in Python and pushes the
file whole, and — more importantly — **refuses to measure a config until the app's own log
confirms the system, core and shader actually running are the ones asked for.** The perf line
carries those three fields specifically so that check is possible.

**Known-invalid row, left in deliberately**: PS1/SwanStation/Upscale3 reads *lower* than the
same core with no shader, because CTR boots into an attract sequence and each PS1 run samples
whatever scene was playing 26s in. PS1 rows are only comparable where the gap swamps scene
variance — true of Beetle vs SwanStation, not of shaders within one core. Fix is to pin the
scene with a save state. Battery draw is not measured at all: the phone was on USB power, so
current readings measure the charger.

## 2026-08-24 (turbo / rapid fire)

**The last open item on `settingslayoutideas.md` is built.** A bindable **Turbo Key** per
system (Settings > Controller, at the end of that system's bindings list), plus global
**Turbo (Rapid Fire)** Off/Hold/Toggle and a **Turbo Speed** slider (4–15 presses/sec,
default 10). Hold or latch the key and whatever face or shoulder buttons you are holding
repeat at that rate.

**Per system, unlike the fast-forward key, because it is a *binding*.** It competes with A
and B for the same physical buttons, and which button is spare differs by console — a Game
Boy layout leaves R2 free, a PS1 one does not. It sits in the per-system list for the same
reason. Mode and speed stay global: those describe how fast the user wants to tap, not the
console.

**It deliberately never repeats the D-pad**, or Start/Select/L3/R3. A repeating direction is
not rapid fire, it is a stutter — the core sees the input centre between every pulse, so the
character stops dead ten times a second. Nothing is improved by opening a menu ten times a
second either. Face buttons and shoulders only.

**The upper bound on speed comes from the emulated hardware, not from us.** A press and its
release have to land on separate frames to be seen at all, so past roughly 15/s a 60Hz core
starts missing presses — which is why the slider stops there. The pulser posts one
half-cycle at a time: down for half the period, up for the other half.

**Two states that had to be got right.** Disengaging mid-pulse re-presses anything still
physically held, or a button the user is still holding would read as released until they let
go and press again. And going into the background — or merely losing window focus to a
dialog — *abandons* that state instead, without re-pressing: the release for those keys is
going to be delivered somewhere else, so a held button left in the set would rapid-fire a
button nobody is touching the next time turbo engaged.

Same accepted edge case as fast-forward: a physical key can only do one job, so binding one
to both turbo and a RetroPad target means turbo wins and the target becomes unreachable.
Fast-forward is checked first, so a key bound to both of *those* is fast-forward. Turbo is a
physical-controller feature; the on-screen pad sends its presses on a different path and has
no spare button to bind a modifier to.

**Hardware-verified on the Pocket Taco**, once one was connected. The app logs the rate it is
actually delivering (debug level, once a second, only while pulsing), so this is measured
rather than eyeballed:

- **Rate**: 9.8–10.0/s against a configured 10/s, across several attempts. The slight
  undershoot is inherent — each half-period is an integer 50ms plus a little handler
  scheduling, so a cycle lands at ~101ms.
- **Both press orders**: button-then-turbo (picked up as already-held) and turbo-then-button
  (picked up on the next press) both fire; both release orders stop cleanly.
- **The exclusions hold**: turbo + D-pad and turbo + Start both log `targets=[] 0.0/s` —
  nothing rapid-fires. The Pocket Taco reports its D-pad as DPAD_LEFT/RIGHT keycodes rather
  than a hat axis, so those do enter the held set and are filtered at the pulse, which is the
  path that needed proving.

Binding still goes through the same listening-row flow as every other binding, and Toggle
engages/releases on successive presses.

**An on-screen TURBO badge shows while it is engaged**, under the FPS counter, in amber so it
reads as a mode the pad is in rather than another readout. Added at the user's request after
testing: in Hold mode the state is self-evident because you are holding the key, but in Toggle
mode nothing otherwise tells you the pad is still in rapid fire, and that is the kind of thing
you discover by losing. It hides on release and is dropped by `abandonTurbo` too, so
backgrounding mid-turbo cannot strand it on screen. `goneMarginTop` keeps its position when
the FPS overlay is off, which is the normal case.

## 2026-08-24 (crash hardening — roadmap 4.1)

**Crashes are now written to disk, because the ones worth reading were the ones being
thrown away.** `TacoBoyLog`'s buffer lives in memory, and a crash is precisely the moment
that memory is discarded — so Export Logs could never contain the crash anyone wanted to
export. `CrashLog` appends a short record to `files/crash_log.txt` (last 20, plain text,
sent nowhere), and Export Logs now emits crash history followed by the current session.
Settings > Advanced gained **Clear Crash History**, kept separate from Reset All Settings
because a crash log is evidence, not configuration.

**Native crashes are recorded too, which needed a different trick.** A SIGSEGV in a core
kills the process with no handler running, so there is nothing to catch — the only evidence
is the `load_in_progress` flag from 2026-08-18, set before a ROM loads and cleared only once
a frame actually renders. `initRomFlow` already read that flag to avoid retrying a ROM that
killed the app; it now also writes a record naming the ROM, the system, **and the specific
core that was active** before clearing it. That last part is the useful bit: "Beetle PSX HW
died loading CTR" immediately suggests trying SwanStation.

**A restart loop was possible and now isn't.** `TacoBoyApplication` deliberately relaunches
after an uncaught exception rather than showing Android's "app has stopped" dialog — which
means that if the crash reproduces on startup, it relaunches into it again, forever, with
the user unable to intervene. Crashes within 30s of launch now increment a streak; at two,
`initRomFlow` comes up at the picker instead of auto-loading anything, ahead of *every*
branch that loads by itself (forced reload and resume-on-launch included). Surviving 30s
clears the streak, so the safe state can't get stuck. Verified with `adb shell am crash`:
first crash restarts normally, second comes up safe, and the streak clears on its own.

**Missing BIOS is refused up front instead of failing as a black screen.** `prepareActiveBios`
already returned a Boolean saying whether it had staged anything; `setupRetroView` discarded
it, so a PS1 ROM with no BIOS imported handed the core an empty directory and let it die on
its own terms — which tells the user nothing. `loadRom` (and `canOpenRom`, which must agree
with it or a ROM switch would `recreate()` into a blank picker) now checks first via a new
side-effect-free `BiosManager.hasUsableBios`, and says what to do about it. Systems whose
BIOS is genuinely optional — Lynx, which falls back to Handy's internal HLE boot ROM — are
deliberately not gated. A missing BIOS also no longer clears `lastRom`: the game is fine and
will load as soon as a BIOS is imported, so forgetting it would be wrong.

**Verified against the roadmap's own list** (4.1 says "induce errors and confirm graceful
handling"), on device, each restored afterwards: BIOS moved aside → clear refusal;
`load_in_progress` forced → native-crash record written naming ROM/system/core; two real
`am crash` calls → disk record with stack trace, normal restart, then safe start.

## 2026-08-24 (ROM switching fixed; on-screen control sizing)

**Switching ROMs could get stuck loading one game.** Reported from play-testing: after a
while the library would load the same game whatever you picked, until the app was restarted
— at which point it came up on the ROM you had actually chosen. Both halves have one cause.

`EXTRA_FORCE_RELOAD_ROM_URI`, added on 2026-08-18 so in-game Reset could force a full
reload, is set on the Activity's own intent — which survives `recreate()`, and nothing ever
cleared it. Switching ROMs also goes through `recreate()`, and `initRomFlow` reads the
forced ROM first and unconditionally. So one press of Reset pinned the pad to that game for
the rest of the session; a restart brought a fresh launcher intent with no extra, and the
`lastRom` preference — written correctly on every switch all along — finally took effect.
`initRomFlow` now strips the extra as it reads it. **Treat that extra as one-shot: it lives
somewhere permanent, so whatever sets it must arrange for it to be consumed.**

**Second bug in the same path, found while fixing the first.** The library's switch path
signalled the new ROM only by writing `setLastRomUri` — but the branch that consumes it is
gated on "Resume on Launch", a preference about *cold starts*. With it off, switching ROMs
mid-session recreated into a blank picker. The switch path now sets the forced-ROM extra
explicitly, which is what that extra means. Both fixes hardware-verified, including that a
genuine cold start with the preference off still stops at the picker.

**On-screen controls can be resized, two ways.** Settings > Controller has an On-Screen
Control Size slider (60–120%) that scales the whole pad; in Edit On-Screen Layout, tapping a
control now selects it and puts −/+ handles beside it for that control alone (50–200%, 10%
a tap). The two compose — per-control tweaks ride on top of the global size — so someone who
needs everything larger can still pull one crowded button back down. Sizes are saved beside
positions in `touch_layouts.json`, per system, as an optional third element in each entry;
layouts written before today are two-element arrays and read back unchanged. Reset On-Screen
Layout clears both, and deliberately leaves the global slider alone: it is not part of "this
system's layout", and someone whose hands need bigger buttons has not changed their mind by
asking for the arrangement back.

**The global scale is applied to the layout's `unit`, not to each radius at the end**, so
the gaps *inside* a cluster grow with the buttons — the face diamond's spread and the
D-pad's arms were already expressed in units. Anchors stay fractions of the zone, so
clusters grow in place instead of drifting.

**Scaling the pad exposed that several anchors were fixed fractions of the width**, which is
fine at one size and wrong at every other. Select/Start, L2/R2 against L1/R1, and L3/R3
against their sticks are now positioned by the size of the control involved rather than by a
fraction of the zone, and the face cluster and D-pad are clamped as whole clusters (the
per-control clamp pulls one button out of formation and leaves the diamond lopsided). At the
default size every one of these lands within a few pixels of where it always did.

**The ceiling is 120% because that is where the tightest layout still fits, measured on
device.** Two different pads bind it: Game Boy / GBA / Lynx are the widest — a big D-pad
opposite a two-button cluster needs about 0.73 of the zone's width, and at 160% they visibly
overlap — and PS1 is the tallest, its diamond meeting the stick below it at about 120%.
Overlap there is not cosmetic: buttons win hit-test ties, so a button over a stick would
press Cross instead of moving it. A slider that can break the pad it is sizing isn't worth
the extra few percent; anyone who wants one control bigger than that can scale it on its own
in edit mode, where the consequence is visible while they do it.

**The edit-mode hint moved to the bottom edge of the zone.** It was drawn a fixed distance
below the top, which put it through the shoulder row, and its size scaled with the pad. The
strip below the lowest row — kept clear of the system's back/home gestures — is the only
full-width band no layout occupies.

## 2026-08-24 (PlayStation glyphs; haptic strength; Settings tab rows unwrapped)

**PS1's face buttons now show the PlayStation shapes** — triangle, square, circle, cross,
in the DualShock colours — instead of the RetroPad letters, so they match the prompts the
games themselves draw and there's no letter-to-shape translation to do mid-game.

Nothing had to move: the diamond already placed X top, Y left, A right, B bottom, and the
standard RetroPad-to-PlayStation mapping (B=Cross, A=Circle, Y=Square, X=Triangle) turns
that into exactly a real DualShock — triangle top, square left, circle right, cross bottom.
That the glyphs land in the correct arrangement without touching the layout is itself a
check on the mapping being right.

**Drawn as vector shapes, not Unicode characters.** The first pass typed U+25B3/25A1/25CB/
2715 and they rendered, but at visibly unequal sizes — U+2715 is a heavy multiplication
sign while U+25CB is a light geometric circle, so the cross came out noticeably bigger than
the circle and triangle. Drawing them fixes that and is also what allows the colours.

Each shape carries an optical scale, because equal geometry does not read as equal size: an
equilateral triangle is 1.73x its circumradius wide against a circle's 2x, and a cross drawn
on the diagonals measures narrowest of the four. Tuned by measuring the rendered pixels
rather than by eye — the four now land at 68 / 69 / 67 / 65 px wide (triangle / circle /
cross / square) on this device, within about 6% of each other.

**Haptics gained a strength setting** — Off / Light / Medium / Strong in Settings >
Controller, replacing the on/off toggle added hours earlier. "On or off" turned out to be
the wrong question: what people disagree about is intensity. Each tap of the row plays the
level it just selected, so the difference can be felt while choosing rather than guessed at
and then tested in a game.

**This changes how the buzz is produced, and the trade is worth recording.** The toggle used
`View.performHapticFeedback`, which has no intensity control but does respect the system's
own touch-feedback setting. Controlling strength means driving the vibrator directly
(`VibrationEffect.createOneShot` with an amplitude), which needs the `VIBRATE` permission —
now declared — and is *not* gated by that system setting. So the earlier entry's claim that
"the device's own haptic setting still wins" no longer holds: TacoBoy's own Off is the way
to silence it. On hardware without amplitude control the levels fall back to differing by
pulse length instead, which is coarser but still tells them apart.

The old boolean lives under a different preference key, deliberately: reading a stored
`Boolean` as a `String` throws, so the strength setting uses a new key and the stale one is
cleared by Reset All Settings.

### Two defects found while testing this

**The Settings system-tab rows were wrapping.** Both of them (System > core options, and
Controller > bindings) are built in code as weighted rows, one share per system — fine at
five, and at ten it broke "SNES" and "LYNX" onto two lines. Same problem the library's tab
row hit, and the same answer: they now scroll, through a shared `wrapInScrollingRow` helper
carrying the same chevron affordance, and the tabs go back to their natural width instead of
being squeezed smaller. Worth noting these were missed when the library row was fixed —
adding a system touches three tab rows, not one.

**The scroll chevrons could fail to appear at all.** Both the library row and the new
Settings rows computed their initial state in a single `post {}`, which runs before the row
has been measured — so `canScrollHorizontally` was still false and the chevron stayed
hidden on a row that did in fact scroll. The library row got away with it because
`updateTabHighlight` posts a second check; the Settings rows had nothing to mask it, which
is how it surfaced. Both now recheck on every layout instead.

**Verified on hardware:** the glyph diamond renders in DualShock order; the Settings
Controller tab shows all ten systems on one line with the right-hand chevron present and the
left correctly hidden at the start of the row; and the vibration row reads Medium by
default and cycles.

### Open questions
- The DualShock colours are the only colour on an otherwise grey pad. That's deliberate —
  they're the one place where colour carries meaning rather than decoration — but it does
  make the PS1 pad look different in kind from the others.

## 2026-08-24 (On-screen pad: L3/R3, haptics, and a drag-to-reposition editor)

**Added: L3 and R3 as their own small buttons, inboard of each PS1 stick.** These were
deliberately left out when the pad was built, on the grounds that a clickable stick can't
tell a press from a nudge — which is true, and is exactly why a separate button is the
right answer rather than a reason to skip them. The comment explaining the old exclusion
has been replaced so it doesn't read as still-current reasoning.

The gap between them and the sticks is load-bearing: buttons win hit-test ties, so the
first placement (which looked fine) had L3's touch area reaching inside the stick's *drawn*
circle, meaning a thumb aimed at the stick's inner edge would have pressed L3 instead.
Moved from 0.385/0.615 to 0.42/0.58 of the width so each button's reach stops short of the
stick's outline. Caught on hardware, not in review.

**Added: haptics.** A short tick on each button press and on each new D-pad direction —
one per change of direction, not one per direction, so rolling round the D-pad doesn't feel
twice as strong on the diagonals. Never fires while a stick is moving, which would buzz
continuously for as long as a thumb rested on it. On by default (a glass button with no
feedback is the main thing that makes a touch pad feel worse than a physical one), with a
toggle in Settings > Controller, and the device's own haptic setting still wins because
FLAG_IGNORE_GLOBAL_SETTING is deliberately not passed.

### Repositioning

**Any control can now be dragged to a new position**, per system, persisted across
sessions. Entered from the quick menu ("Edit On-Screen Layout"), which is where it is
rather than behind a long-press on the pad: a long-press is invisible until you already
know about it, and the pad's surface is otherwise busy playing the game. The menu closes
on entry, since it would cover the controls being dragged.

Design decisions worth keeping:

- **Positions are fractions of the zone, not pixels**, so a saved layout survives the
  boundary handle being dragged to a different height — the same reason the default layout
  is expressed in fractions.
- **Only moved controls are stored.** A control the user never touched isn't in the file
  and falls back to its computed default, so a later change to the default layout still
  reaches them. "Reset" deletes the entry rather than writing today's defaults into it, for
  the same reason.
- **Saved on each drop, not on leaving edit mode**, so a layout can't be lost to the app
  being killed with the editor open.
- **Written via a temp file and rename.** Other systems' layouts share the file; a failed
  write must not be able to take them with it.
- **Dragging is single-pointer**, and controls are clamped inside the view — a control
  dragged off the edge would be unrecoverable short of a full reset.
- **Nearest centre wins the grab**, not first hit, because in edit mode controls can be
  dragged into overlapping positions and the closer one is far more often the intended one.

Every control is outlined in blue while editing, so the pad doesn't look identical whether
it's live or being rearranged, and a "Drag any control to move it" line sits at the top of
the zone.

**Verified on hardware, end to end:** L3 lights and presses without disturbing the stick
beside it; edit mode outlines every control and closes the menu; dragging L3 up beside the
D-pad moved it and wrote `{"PS1":{"L3":[0.430,0.529]}}` — only the moved control; the
position survived a force-stop and relaunch; and "Reset On-Screen Layout" restored the
default and left the file as `{}`. The test layout was reset afterwards, so the device is
back to defaults.

### Open questions
- Haptics is wired and respects both the pref and the system setting, but whether the tick
  *feels* right is a judgement only the user can make on the device.
- Controls can be moved but not resized. Resizing is the obvious next want, especially for
  anyone with larger or smaller hands than the defaults assume.

## 2026-08-23 (Master System, Game Gear, SG-1000; scrolling system tabs)

**Added: Sega's three 8-bit systems, on the Genesis Plus GX binary already there.**
`.sms`, `.gg` and `.sg`. No new core, no BIOS, and no need to touch
`genesis_plus_gx_system_hw` — the core picks its hardware from the ROM's extension, which
now reaches it correctly through the `GET_GAME_INFO_EXT` work done for Lynx.

Two-button pads, mapped by the core as RetroPad B -> button 1 and RetroPad A -> button 2
(the `DEVICE_PAD2B` branch of the Sega 8-bit path in libretro.c, read rather than guessed).
`TouchControls` labels them **1** and **2** accordingly. Master System's pause lives on the
console itself so its Start is labelled **PAUSE**; Game Gear has a real Start; the SG-1000
has neither, so it gets no Start/Pause target at all rather than a button its hardware
never had.

TacoBoy is now ten systems on six cores, and Genesis Plus GX carries four of them.

### The tab row scrolls

Ten tabs is well past what a weighted row can hold — seven had already forced 12sp text
and 2dp padding to stop SNES and LYNX wrapping. The row is now a `HorizontalScrollView`,
and the tabs went back to a comfortable 13sp with 10dp padding rather than being squeezed
further.

**Scrollability is signalled three ways**, because a row that silently continues off-screen
is a row people don't know to scroll:

- A `‹` / `›` chevron either side, each shown *only* when there is actually something
  further in that direction — so at either end of the row you can see you've reached it.
  They're `INVISIBLE` rather than `GONE`, so the row doesn't shift sideways as they come
  and go.
- Tapping a chevron pages the row by three quarters of a width. Not a full width, so a tab
  stays visible across the jump and it never looks like the row teleported.
- Fading edges (`requiresFadingEdge`), which catch anyone who doesn't read the chevrons.

`updateTabHighlight` now also centres the selected tab. Without it, reopening the library
on a system near the end of the row would land on a screen where the highlighted tab isn't
visible at all.

**Verified on hardware:** all ten tabs present and scrolling; the selected tab auto-centres
(GEN was centred on open, with both chevrons showing); paging right to the end made the
right chevron disappear while the left stayed; the SMS tab selects and shows its
"No folder set for SMS yet" empty state.

**Confirmed with real ROMs 2026-08-24:** all three boot and play — the user reported
Master System, Game Gear and SG-1000 all running, with Ax Battler: A Legend of Golden Axe
on Game Gear as the live example. Box art fetched for all three, which also confirms the
thumbnail folder names ("Sega - Master System - Mark III", "Sega - Game Gear",
"Sega - SG-1000") were right. Nothing needed changing after the untested first pass, so
the extension-driven hardware selection through `GET_GAME_INFO_EXT` works for the Sega
8-bit family exactly as it does for Mega Drive.

## 2026-08-23 (Right analog stick; Sega Mega Drive / Genesis added)

**Added: a second analog stick on the PS1 on-screen pad**, for the camera-and-aiming
games that put it there. The pad is now symmetric — D-pad and face buttons share a row,
with a stick directly below each, which is both what a DualShock looks like and what puts
the thumbs' resting position on the sticks.

Making room needed more than moving the buttons up. The face diamond is *taller* than the
D-pad cross for the same nominal size (half a spread plus a button radius, against one
arm), so simply aligning their centres dropped B straight onto the right stick — visible
in the first hardware screenshot. Fixed by tightening the diamond when sticks are present
(spread 0.135→0.122, radius 0.068→0.060 of the layout unit), lifting the shoulder and
Start/Select rows to 0.085/0.21, and pulling the sticks down to 0.81. The sticks' grab
radius also came down from 1.4× to 1.15×: buttons deliberately win hit-test ties, so an
over-wide stick reaching up into B would have made B steal touches meant for the stick —
exactly the wrong way round for the control a thumb rests on.

`TouchControls` grew a `Stick` enum and a `StickState` class rather than a second set of
loose fields, so the pointer map just holds whichever stick a finger grabbed, and the view
still knows nothing about GLRetroView's motion-source constants.

### Sega Mega Drive / Genesis, the seventh system

Genesis Plus GX (2026-08-21 nightly), `.md` / `.gen` / `.smd` / `.bin`. `.bin` is generic
enough to be risky in the abstract, but the library only ever scans the folder the user
picked *for this system*, so a `.bin` in the Genesis folder is a Genesis ROM by
construction. Mega CD's `.cue`/`.iso`/`.chd` are deliberately excluded — that needs a BIOS
and multi-file content this app doesn't model.

**This is the system that justified the six-button reasoning.** Genesis Plus GX's own
input descriptors map Y→A, B→B, A→C, L→X, X→Y, R→Z, Select→Mode, so all six action
buttons land exactly on the Pocket Taco's four face buttons plus two shoulders, with
Select free for Mode. That exact fit is why Genesis is here and N64 isn't.

**Every RetroPad name disagrees with the printed Genesis name**, so anything user-facing
has to relabel — showing raw targets would mislabel the entire pad. `TouchControls` now
does that (`labelFor`), and goes further: Genesis gets the two rows of three its real
six-button pad had, `X Y Z` above `A B C`, with no shoulder row at all. On the Pocket Taco
X and Z genuinely *are* the shoulder buttons, because that's the only place six actions
fit on that hardware — but on screen there's no such constraint, and a diamond-plus-
shoulders would put two face buttons in the wrong place on the one system that prints all
six together.

Eight curated core options with descriptions, values taken from upstream's
`libretro_core_options.h` rather than guessed. "System Hardware" is deliberately excluded:
it can force the core into Master System / Game Gear / SG-1000 modes, which aren't systems
TacoBoy offers, and `auto` is always right for a Genesis ROM.

**The library tabs needed tightening, as predicted.** Seven tabs made SNES and LYNX wrap
onto two lines, so the row went to 12sp text, 2dp horizontal padding, 3dp gaps and
`maxLines="1"`. That buys room for one or two more systems at most — beyond that the row
wants to become horizontally scrollable rather than keep shrinking.

**Verified on hardware.** All seven tabs on one line; Genesis library populated with box
art (so the "Sega - Mega Drive - Genesis" thumbnail folder is right); Aaahh!!! Real
Monsters boots through the Nickelodeon logo to its title screen; the pad renders the
X Y Z / A B C layout with MODE and Start and no shoulder row; a D-pad press lights the
correct arms including diagonals; and the title-screen cursor moved from START to OPTIONS,
so input reaches the core. The right stick was checked by holding it off-centre — the knob
tracks and clamps to its radius, clear of B.

### Open questions
- The tab row is now near its limit. Master System / Game Gear would need it scrollable.
- Genesis Plus GX also runs SMS, Game Gear and SG-1000. Wiring those is mostly a
  `GameSystem` entry each plus the `system_hw` option — no new core binary.

## 2026-08-23 (On-screen controls)

**Added: an on-screen gamepad, off by default, toggled by a new 🎮 button on the in-game
top row.** The reason it exists is worth recording, because it is not "other emulators
have one": without it, someone who doesn't have the Pocket Taco to hand has to move their
saves to another app and back. That friction is the thing being removed. Anyone playing
on the physical controller never sees it.

**It lives in the occlusion zone below the boundary handle**, not over the picture. That
space was already reserved, already black and already swallowing touches, so the pad costs
the game nothing and needs no translucency -- the usual see-the-game-underneath reason for
it doesn't apply here.

**Layout is "thumb-reach" rather than console-facsimile** (chosen by the user from two
mockups): D-pad and face buttons sit low in the bottom corners where thumbs rest holding a
phone in portrait, Start/Select are lifted up out of that travel path so they can't be
caught mid-game, and shoulders run along the top edge furthest from both. Everything is a
fraction of the zone rather than a dp size, because the zone's height is whatever the
handle has been dragged to -- and the lowest row deliberately stops short of the bottom
edge, since controls flush against it collide with the system back/home gestures.

**One custom View, not a ViewGroup of child Views.** A gamepad is inherently multi-touch
-- D-pad plus a face button at once is the normal case -- and Android hands the touch
stream to whichever child claimed the first pointer, so child Views would need the same
pointer bookkeeping anyway plus a layout pass. Doing it in one View also decouples hit
areas from drawn size, which is what keeps the targets hittable when the zone is small
(they are deliberately ~35% larger than the circles drawn).

**The button set comes from `GameSystem.relevantControllerTargets`**, so it was already
correct per system with no new data: Game Boy gets a D-pad, A/B and Start/Select; Lynx
gets its two Options and Pause (labelled `OPT 1`/`OPT 2`/`PAUSE` rather than the RetroPad
`L1`/`R1`/`Start`, which would be actively misleading on the one system whose printed
names differ); SNES fills the diamond; PS1 adds L2/R2. L3/R3 are excluded even on PS1 --
they are stick clicks, and an on-screen stick has nowhere sensible to put one.

**PS1 gets an analog stick**, which is the part that unlocks something the hardware
cannot: the DualShock-requiring games the Pocket Taco physically can't play. The stick
takes the left thumb's home position with the D-pad above it, since analog games use it
constantly while the D-pad is still wanted for menus. Note the pad does not force the core
into analog mode -- that is Beetle's own "Analog Mode on Boot" option in Settings > System,
and forcing it would break games that only understand the digital pad.

Two smaller things fell out of it. `BoundaryController` gained
`setMaxPercentOverride`, so with the pad showing the handle can no longer be dragged past
0.75 -- below roughly a quarter of the screen the controls are too small to hit reliably,
and a pad you can't press is worse than no pad. And anything held is force-released when
the pad is hidden, or the core would keep seeing the press for the rest of the session.

**Verified on hardware.** Pressing Start on the pad advanced Bloody Roar II out of its
attract-mode demo (so the key path reaches the core), and the per-system layouts were
checked on PS1 (full set plus stick) and Lynx (two buttons, OPT/PAUSE labels). First pass
sat too close to the bottom edge and the row positions were lifted; the screenshots after
that show clear margin.

### Open questions
- Only the in-game toggle exists; there is no Settings row for it. That is probably right
  (it is a per-moment thing, not a preference) but worth revisiting if it proves hard to
  find.
- Deliberately not built yet: haptics on press, and any way to reposition or resize
  individual controls. Both are real wants if the pad sees much use.

## 2026-08-23 (Atari Lynx added; GET_GAME_INFO_EXT implemented in LibretroDroid)

**Added: Atari Lynx, the sixth system.** `.lnx` and `.lyx` (`.o` is also advertised by
the core but deliberately not claimed -- far too generic a suffix for a folder scan),
running on the Handy core that was already sitting unused in `jniLibs`. It fits the
Pocket Taco better than anything else here: D-pad, two face buttons, two Option
buttons, Pause. Handy's own RetroPad map is A->A, B->B, L->Option 1, R->Option 2,
Start->Pause, so `relevantControllerTargets` leaves out X/Y and Select entirely.

**The bundled Handy was a stale leftover and has been replaced** (2026-08-20 nightly;
the old one is kept at `core-backups/handy_libretro_android.so.upstream-sample.bak`).
The version that shipped with the upstream LibRetroDroid sample had exactly one core
option, `handy_rot`; the current build has six. Five are curated into the System tab
with descriptions: Display Rotation, LCD Ghosting Filter, Video Refresh Rate, CPU
Overclock Multiplier and Frameskip. Left out: "Frameskip Threshold", which only does
anything while Frameskip is Manual, and "Color Depth", which isn't in the Android build
at all (it sits behind `FRONTEND_SUPPORTS_XRGB8888` upstream and the key is absent from
the `.so`). Display Rotation matters more than it sounds: several Lynx games were played
with the console turned on its side, and the core rotates its own D-pad table to match,
so nothing on the Controller tab has to change with it.

### The real find: LibretroDroid never implemented GET_GAME_INFO_EXT

First run booted the Lynx BIOS to its "INSERT GAME" screen with the log line
`[Handy] Failed to open Cart file: Baseball Heroes (USA, Europe).lyx`. The cause is not
Lynx-specific and is worth recording properly:

**A core is free to read its content only through `RETRO_ENVIRONMENT_GET_GAME_INFO_EXT`
(env 66), and Handy does exactly that** -- it ignores `retro_game_info::data`
completely. LibretroDroid didn't implement the call, so Handy fell through to its
fallback of opening `retro_game_info::path` off the filesystem. Under this frontend that
path is a virtual filename with nothing behind it, so the cart silently failed to load
while the core happily carried on running the BIOS. Every core bundled before now
happens to read `info->data`, which is why this has never surfaced.

Implemented in `environment.cpp`: `Environment::setLoadedContent` builds a
`retro_game_info_ext` (deriving `dir`/`name`/`ext` from the virtual filename, since
there is no real path -- `name` is what a core builds its own save filenames from, e.g.
Handy's `.eeprom`, and `ext` is lower-cased as libretro.h requires), and the new case
hands out a pointer to it. All three `loadGame*` paths publish, and `destroy` clears.
`persistent_data` is advertised false, because the buffer goes away with the game rather
than lasting to `retro_deinit`. A core that gets `false` here -- content loaded through
VFS, so no memory buffer -- falls back to the path exactly as before, which is why this
is safe for the existing five systems.

### BIOS handling is now per-system rather than PS1-shaped

`BiosManager` grew a `BiosSpec` per system, because the two BIOSes are recognised in
opposite ways and it is worth being explicit about why:

- **PS1 by filename** (BiosRegion's `scph####`). SwanStation identifies valid images by
  content and takes any name, so the name is the only thing that can tell us a dump's
  region -- and staging keeps whatever the file is already called.
- **Lynx by content**: exactly 512 bytes with CRC32 `0x0D973C9D`. Handy hardcodes both
  (`ROM_SIZE`/`ROM_CRC32` in `lynx/rom.h`) and falls back to its internal HLE BIOS for
  anything else, so matching the same two things means the picker can never offer a file
  the core would refuse. It also frees the user's dump to be named anything, which
  matters because Handy wants one exact filename -- `lynxboot.img` -- and real dumps
  circulate under several. Staging renames it. Length is tested before the hash, so no
  ROM or PS1 BIOS in the library is ever read just to be rejected.

`GameSystem.biosOptional` is new and only affects wording: PS1 genuinely cannot boot
without a BIOS ("BIOS ⚠"), Lynx runs fine on the core's internal one, so it gets a
neutral "BIOS –" and "No boot ROM imported — games still run on the core's built-in
one." The dialog title is now per-system ("Lynx BIOS"), and the multiple-BIOS region
hint is suppressed where region means nothing.

**Also worth knowing for the next system:** the library's system tabs were the one place
that *isn't* driven by `GameSystem.entries` -- they're a hardcoded `mapOf` against
hardcoded `TextView`s in `activity_rom_library.xml`. Everything else (Settings' Graphics
rows, both sets of system tabs in Settings, ControllerBindings, TacoBoyPrefs) picked
Lynx up with no changes at all. Adding a seventh system means remembering that one file.

The tab label is `LYNX`, not `Lynx`: every other label here is an abbreviation
(GBA/GB/GBC/SNES/PS1) and the mixed-case one read as the odd one out. `shortLabel`
carries it, so the Settings tabs and every "<system> Shader/Core/BIOS" row match too.

**Verified on hardware, end to end.** Folder picked, BIOS imported through the real UI,
and:

- `.lyx` (Baseball Heroes, headerless) and `.lnx` (Block Out, headered) both boot to
  their title screens.
- `[Handy] BIOS loaded: /data/.../files/bios_active/lynxboot.img` in logcat -- detection,
  staging and rename all confirmed by the core itself, not inferred.
- The BIOS button showed the neutral "BIOS –" before import and "BIOS ✅" after.
- Box art downloads correctly, so the "Atari - Lynx" thumbnail folder name is right.
- Regression checks on the GET_GAME_INFO_EXT change: GBA (Baldur's Gate: Dark Alliance,
  LCD shader) and PS1 (Bloody Roar II, Beetle + hardware_gl, BIOS staging correctly
  swapping back to `PSX - SCPH7003.BIN`) both boot unchanged.

### Open questions
- `libparallel.so` (ParaLLEl N64) is still in `jniLibs` wired into nothing. Unlike Handy
  it has no route to being used -- `roadmap.md` explicitly declines N64 as analog-heavy,
  and the Pocket Taco has no sticks -- so it is ~2.8 MB of APK for a system this project
  has decided against. Drop it.
- Handy logs `Invalid cart (no header?) - Guessing a ROM layout...` at ERROR level for
  every `.lyx`. That is the normal headerless path working as intended, not a fault; noted
  here so it isn't mistaken for one later.

## 2026-08-23 (Integer scaling added; hardware_gl artifacting documented; three inert options removed)

**Added: Integer Scaling, a global toggle at the top of Settings > Graphics** (off by
default). The picture is snapped down to a whole multiple of the frame's own pixel
grid instead of being scaled by whatever fraction fills the viewport, leaving a thin
border. Without a shader that is a subtle sharpness change; with one it is the
difference between an even LCD grid or CRT scanline spacing and an uneven, shimmering
one, which is why it was asked for.

**Only the vertical axis is snapped, and the width follows it by the same factor.**
That is a deliberate choice, recorded in `VideoLayout::updateIntegerScale`: snapping
both axes independently would mean square source pixels, which is *wrong* for a PS1
running its 256- or 384-wide modes (the hardware stretches those to 4:3) and would
narrow SNES from 4:3 to 8:7. Vertical-only still delivers the thing the feature exists
for, because a CRT shader's scanlines are horizontal and their spacing depends on the
vertical ratio alone. And where a core reports its native square aspect the derived
width lands on a whole multiple anyway -- on this 1440-wide screen GB (1440/160 = 9)
and GBA (1440/240 = 6) are *already* exact multiples at full width, so the toggle is a
no-op for them and the LCD grid was uniform all along. It is SNES and PS1 that gain
borders.

Plumbing follows the `viewportAlignment` path exactly: `VideoLayout` gains
`updateIntegerScale` and `updateFrameSize`, `Video`/`LibretroDroid` forward it, JNI +
`LibretroDroid.java` + `GLRetroView.integerScale` + `GLRetroViewData` expose it, and
`TacoBoyPrefs.isIntegerScaleEnabled` (global, cleared by resetAllSettings) feeds it at
game load. Frame size is pushed from `Video::renderFrame` rather than a geometry hook,
so a PS1 changing video mode mid-game cannot leave it stale; `VideoLayout` ignores a
size it already has, so that costs nothing per frame.

**Verified on hardware, measured rather than eyeballed.** Same PS1 game and screen,
integer scaling off then on, via a screenshot A/B (the setting was flipped directly in
`shared_prefs` with the app force-stopped, and the device was restored to its original
state afterwards):

| | picture width | picture height | aspect |
|---|---|---|---|
| off | 1440 px | ~1181.5 px (4.92 x 240) | 1.21879 |
| on | 1170 px | 960 px (exactly 4 x 240) | 1.21875 |

The height landed on an exact 4x multiple of the core's 240-line frame, the width
followed to keep the aspect ratio identical to five significant figures, and the
borders came out symmetric (135 px each side). Note the aspect here is ~1.219, not
4:3 -- that is Beetle's "corrected" aspect option doing its job, and integer scaling
deliberately preserves whatever the core asked for rather than imposing 4:3 of its own.

**Changed: the renderer note now warns about the hardware_gl artifacting.** Settings >
Graphics' renderer row explains that Beetle PSX HW's hardware_gl scatters a few
wrong-coloured pixels over some 2D artwork, that it is a bug in the core rather than in
TacoBoy, that it mostly shows up on menus, logos and character-select screens rather
than during play, and that software gives a clean picture at the cost of the higher
internal resolutions and texture filtering only the hardware renderer can do. This is
the documented-and-move-on outcome of the speckle investigation: the 2026-08-22 core
did not fix it, which was the expected result.

**Removed: `hardware_vk` from Beetle's renderer choices, and three inert core options.**

`hardware_vk` goes for exactly the reason SwanStation's "OpenGL" went on 2026-08-17 --
LibretroDroid has no Vulkan backend, so it is not a trade-off the user can meaningfully
pick, it just misfires. A stale saved value falls back to `defaultRenderer` through the
existing `CoreDefinition.selectedRenderer` path.

Adaptive Smoothing, Supersampling and Anti-Aliasing (MSAA) go because they cannot do
anything on this frontend. Confirmed against upstream's `libretro_core_options.h`
rather than assumed: all three sit inside `#ifdef HAVE_VULKAN` and each one's own
description ends "Only supported by the Vulkan renderer" -- so neither software nor
hardware_gl, the only renderers TacoBoy now offers, honours them. Same reasoning as the
two SwanStation options removed earlier the same day. Beetle's curated option count
goes 30 -> 27.

Worth recording what was checked and turned out *not* to be Vulkan-only, since the
guess was wrong twice: **PGXP Perspective Correct Texturing works on both renderers**
("Supported by the hardware renderers and the software renderer"), and **Beetle's
software renderer does support internal-resolution upscaling** -- it is just slow ("has
steep performance requirements when running at increased internal GPU resolutions").
That leaves hardware_gl's real, exclusive benefits as exactly two: upscaling at
playable speed, and Texture Filtering (SABR/xBR/JINC2/bilinear/3-point, "Only supported
by the hardware renderers").

### Open questions
- `libparallel.so` (ParaLLEl N64, confirmed from its symbols) and
  `handy_libretro_android.so` (Lynx) are in `jniLibs` but wired into nothing --
  neither appears in `GameSystem`. ~3 MB of a 21 MB APK for cores that cannot be
  reached. Leftovers from the upstream sample app; drop them or wire them up.

## 2026-08-23 (Beetle PSX HW updated to the 2026-08-22 nightly; CUT upscalers exposed)

**Changed:** `mednafen_psx_hw_libretro_android.so` swapped for the current libretro
nightly (built 2026-08-22 08:20 UTC, 13,146,264 bytes, md5
`52c5956c2c5f1ca19f1fe6b299838abb`). The build it replaces is kept at
`core-backups/mednafen_psx_hw_libretro_android.so.2026-08-16.bak` (md5
`d5a1cf135e9b400f41685fae88b269cd`) -- the buildbot only serves `latest`, so the old
one is not re-downloadable and this project is not under version control.

Nothing in the app had to change for it: the core's option keys are byte-identical
between the two builds (90 `beetle_psx_hw_*` strings, no additions, no removals), so
the 30 options Settings exposes are all still valid.

**Expectation management on the speckle.** This swap was step 1 of the plan because it
was cheap, not because it was likely. Upstream's commit log between the two builds
(libretro/beetle-psx-libretro, 2026-08-16 -> 2026-08-22) is: one PR and three
follow-ups on HD texture replacement (`rhi_tt`), and one Vulkan draw-queue bound.
**Nothing touched the GL rasterizer.** The GL-side work in that window --
`rhi_lib_gl: reserve index space per primitive` (08-13), `(GL) Restore the caller's
scissor state in rhi_gl_load_image` (08-11), `gl: complete dependency-driven
framebuffer mirrors` (08-10) -- all predates the build we were already running. So if
the Bloody Roar II character-select specks are still there, that is the expected
result and not worth further local investigation: per the 2026-08-23 localisation
entry below, the case is already fully evidenced and the next step is the upstream
report, not another local A/B.

**Added:** the CUT / CUT2 / CUT3 upscalers, as `ShaderChoice.UPSCALE1/2/3` ("Upscale
1/2/3" in Settings > Graphics), appended after LCD so existing `shader_choice_<system>`
pref values keep their meaning. Parameters are left at `ShaderConfig`'s own defaults;
nothing in the UI exposes them yet.

**This was also the missing verification of the max-size-FBO change.** These are the
only multi-pass shaders in the chain, so they are the only ones that sample
`previousPass` -- the path the FBO change had to alter blind. Re-read statically while
adding them, and the maths holds: intermediate passes are still built at frame size
(`FramebufferRenderer::rebuildPasses` -> `buildShaderPasses(width, height, ...)`),
while the render target alone carries the allocation, so `passCoords = c05 /
textureScale` converts an allocation-normalised coordinate back to a frame-normalised
one exactly as intended. Per-pass `scale` doesn't disturb this -- normalised
coordinates are scale-independent. That is an argument, not a hardware run: if
Upscale 1-3 render wrong while Sharp/CRT/LCD stay right, `shadermanager.cpp`'s three
`passCoords` lines and video.cpp's `textureScale` upload are still the first place to
look.

**Verified:** compiled clean and installed to the Pocket Taco (`adb install -r`,
Success). Both changes are pending a hardware look: the Bloody Roar II character-select
screen for the specks, and any system's Upscale 1/2/3 for the multi-pass path.

## 2026-08-23 (display shaders exposed per system)

**Shaders are now selectable in Settings > Graphics, one row per system.** Off /
Sharp / CRT / LCD, cycling on tap with the same reset affordance as the core and
renderer rows above them.

Worth recording why this was so small: **the shader pipeline was already built and
working the whole time**, and had nothing to do with the hardware-rendering effort
that stalled. `ShaderManager` already carried seven implementations (Default, CRT,
LCD, Sharp, and the CUT/CUT2/CUT3 upscalers), `ShaderManager::Chain` already
supported multi-pass with per-pass scale and filtering, and `ShaderConfig` already
exposed all of it to Kotlin with tunable parameters. The only thing missing was that
`TacoBoyActivity` hardcoded `shader = ShaderConfig.Default` and nothing ever offered
a choice. Nor is slang/GLSL preset support needed for this: those are RetroArch's
*preset* formats, and the complexity there is the parser and parameter binding, not
the shading language -- what's here is already the simpler form, one GLSL ES string
per effect compiled at startup.

New `ShaderChoice` enum maps the curated subset to `ShaderConfig`; `TacoBoyPrefs`
stores it per system under `shader_choice_<system>`, and unlike the core/renderer/BIOS
choices it *is* cleared by resetAllSettings -- those pick which resource is active,
this is pure appearance. Per-system rather than global because the right answer
genuinely differs: an LCD grid suits a handheld and looks wrong on PlayStation output,
and a CRT mask is the reverse.

**CUT/CUT2/CUT3 are deliberately not offered yet.** They are the only multi-pass
entries, so they are the only ones that exercise `previousPass` -- a path this app has
never run, and one the max-size-FBO change above had to alter blind (the three
`passCoords = c05 / textureScale;` lines). They can be added with no further work once
that is verified on hardware; `ShaderChoice` needs only new entries.

**Hardware-verified**: all five shader rows render and cycle correctly; PS1 set to CRT
and reloaded produces clean, evenly-spaced scanlines (measured, and inspected at 4x
nearest-neighbour). That doubles as confirmation that the max-size FBO's texture-
coordinate scaling is right -- a wrong scale would have misaligned or duplicated the
picture rather than just filtering it.

## 2026-08-23 (hardware-render FBO now allocated at the core's declared maximum)

**The hardware-render target is allocated once at `max_width`/`max_height` instead of
being destroyed and rebuilt to match every frame.** This is what the libretro contract
expects and what RetroArch does; the previous behaviour is what all three resize bugs
fixed on 2026-08-17 were symptoms of.

Measured on the device before changing anything, Bloody Roar II declares a **700x576**
maximum and then swings between **320x240, 256x240, 640x239 and 640x478** -- twice
inside the same millisecond at one transition. Every one of those changes previously
tore down and reallocated the colour texture *and* the depth renderbuffer. Now the
allocation is stable at 700x576 and only the sampled sub-rect changes.

The awkward part was never the allocation, it was sampling it. `Video::renderFrame`
binds the main texture for *every* shader pass, while `videolayout`'s texture
coordinates are hardcoded 0..1 -- so a naive change breaks the moment a shader reads
`previousPass` alongside. What makes it tractable is that every shader derives its
lookups as `coords * textureSize` and back again: scaling the coordinates down by
frame/allocation while simultaneously passing `textureSize` as the *allocated* size
leaves `screenCoords` in frame pixels exactly as before, and every derived lookup lands
correctly inside the larger texture, with no shader edit at all. That covers the CUT /
CUT2 / CUT3 upscalers' ~50 sampling sites for free.

Only `previousPass` needed touching, because intermediate passes stay frame-sized: three
`passCoords = c05;` lines become `passCoords = c05 / textureScale;`, with a matching
`textureScale` uniform. `ImmersiveMode` needed the same treatment for the one pass that
reads the game texture (its blur passes read its own framebuffers and keep sampling
0..1).

Supporting changes: `Renderer::getTextureAllocationSize()` (defaulting to the frame size,
so `ImageRendererES3` is bit-for-bit unaffected -- scale 1, `textureSize` = frame size,
exactly as before); `RenderingOptions` carries the max dimensions from
`retro_get_system_av_info`; `FramebufferRenderer` splits its old single `isDirty` into
`passesDirty` and `allocationDirty`, so a resolution change now rebuilds only the pass
chain. The allocation still grows if a core ever draws larger than the maximum it
declared -- that would be a core bug, but clipping the picture silently would be worse --
and it never shrinks back, since churning the target is the thing being removed.

**Hardware-verified**: PS1 under Beetle/`hardware_gl` renders correctly through the whole
boot sequence, including the real PS1 BIOS splash (the case that was broken twice before)
and real-time 3D on two different attract-mode stages, across all four of the resolutions
above. GBA under mGBA (the `ImageRendererES3` path, scale 1) is unchanged. A temporary
probe confirmed the new path is genuinely exercised rather than silently degenerating to
scale 1 -- the numbers quoted above are from it.

**This does not fix the speckle**, and was never expected to: the entry above establishes
that lives in Beetle PSX HW's GL renderer. This is a correctness and stability change.

## 2026-08-23 (hardware_gl speckle localised to the core; GL correctness pass)

**Investigation: Bloody Roar II's speckle is Beetle PSX HW's GL renderer, not
this frontend.** User's repro -- scattered wrong-coloured single pixels on the
character-select art, "on rendered textures, not pre-rendered frames". Measured
by cropping a flat area of Yugo's orange jacket and counting isolated-bright
pixels: `hardware_gl` scores ~71, both software paths score 10-20. The decisive
test is same core, same screen, `renderer = software` -> clean, with the game's
authentic ordered dither cross-hatch intact. So it is not the core generally, not
the core options, and not the display path (a passthrough `texture2D`).

Also established: the specks are fully deterministic -- 68 of 71 at identical
coordinates across two separate boots with different core options -- which rules
out races, timing and uninitialised memory independently.

Five hypotheses tested and eliminated, each with a real A/B rather than reasoning:

1. `beetle_psx_hw_video_cable` (user had `rgb`): frames byte-identical with it off.
2. `beetle_psx_hw_dither_mode` -> `disabled`: specks unchanged. It *is* applying --
   the ordered cross-hatch vanishes under Beetle while remaining under SwanStation --
   so the long-standing "dithering + 32bpp" theory doesn't explain this one.
3. **Uninitialised FBO memory.** `ES3Utils::createFramebuffer` uses
   `glTexStorage2D`, whose contents are undefined, and the FBO is rebuilt on every
   resolution change. Tested by clearing new framebuffers to **magenta** instead of
   black, so the specks would turn magenta if that were the source. Zero magenta
   pixels anywhere. Reverted.
4. **Depth precision.** The no-stencil path allocates `GL_DEPTH_COMPONENT16` while
   the stencil path already gets 24-bit via `GL_DEPTH24_STENCIL8`; Beetle requests
   depth, so z-fighting on coplanar 2D quads was plausible and would be
   deterministic. 16 -> 24: 71 vs 71. Reverted (24-bit costs tiler bandwidth for no
   measured gain).
5. `beetle_psx_hw_renderer_software_fb`, uncurated, whose own text says that when
   disabled "these operations are omitted (OpenGL)" and it "may cause severe
   graphical errors". Curated it forced to `enabled`: 71 vs 71 -- the core's default
   already is enabled. Reverted the curation too.

**Correctness pass on the GL code, kept separate from the above** -- these are real
defects found by reading, none of which cause the speckle:

- **`FramebufferRenderer::initializeBuffers` leaked the whole shader-pass chain.**
  Assigning over `framebuffers` destroys the vector, but `ES3Utils::Framebuffer` is
  a plain struct of GL names with no destructor, so every resize and every shader
  change leaked a framebuffer + texture + renderbuffer per pass. `ImageRendererES3`
  already freed its passes correctly, which is what made the omission next door
  obvious -- both now share a new `ES3Utils::deleteFramebuffers` helper instead of
  one open-coded loop and one missing one. Only multi-pass shaders allocate passes
  (the default chain has one pass and builds none), so this would have surfaced the
  moment CRT/shader support landed.
- **`buildShaderPasses` could loop ~2^64 times.** `for (int i = 0; i < passes.size() - 1; ...)`
  on an unsigned `size()`: an empty chain wraps to SIZE_MAX rather than skipping.
  Guarded explicitly.
- **`setEGLConfigChooser` was never called**, so GLSurfaceView fell back to its own
  default chooser, which only requires 4/4/4/0 and may return an RGB565 surface --
  which would quantise the final RGBA8 blit to 5/6/5 and band every gradient. Probed
  this device first: it returns R8 G8 B8 A0, so this pins down luck rather than
  fixing a live bug. Now requests 8/8/8/0 explicitly.
- `layer >= 0` on an unsigned `layer` was always true, in both renderers.

**Hardware-verified** after the pass: PS1 under Beetle/`hardware_gl` (the
FramebufferRenderer path) and GBA under mGBA (the ImageRendererES3 path) both boot
and render correctly, with no EGL config errors in logcat.

**Allocating the hardware-render FBO at `max_width`/`max_height` was deferred out of
this entry and done straight afterwards** -- see the entry above it.

## 2026-08-23 (Every core option explained; two SwanStation options found inert)

**All 53 curated core options now carry a plain-English explanation**, collapsed
behind the same info button the rest of Settings just gained. These were the
tab most in need of it -- rows like "GTE Overclock", "PGXP Vertex Cache" or
"Texture UV Offset Fix" are meaningless to anyone who hasn't already read
libretro documentation, and the tab showed nothing but the label and a value to
cycle. Written to say what the setting changes, what it costs, and -- for the
several here where they differ -- which choice is faithful to the original
hardware versus which one looks better on a modern screen.

Stored as `CoreOptions.descriptions`, a side map from option `key` to string
resource, rather than a field on `Option`: the curated lists stay a readable
catalog of what each core exposes, the two get edited for different reasons, and
an option with no description simply renders no info button (`descriptionRes`
returns 0, and `renderSystemCoreOptionsList` adds a plain row). Resource ids, not
resolved strings, because `withNote` keys its expanded state by the first id.

**Descriptions were checked against each core's own embedded English strings,
not written from memory** -- the same raw-binary string extraction used for
`beetle_psx_hw_dither_mode` in the 2026-08-17 entry. Eight first drafts were
wrong or incomplete and got corrected:

- **Adaptive Smoothing** and **Anti-Aliasing (MSAA)** are "Only supported by the
  Vulkan renderer" per Beetle PSX HW itself. Both now say so and point at the
  Graphics tab. Worth knowing: the PS1 renderer defaults to `hardware_gl`, so
  both are inert out of the box.
- **Texture Filtering** (Beetle) does nothing on the software renderer.
- **PGXP Vertex Cache** -- the core's own text ends "It is currently recommended
  to leave this option disabled", which the description now passes on.
- **Supersampling** gained the core's "best in titles that mix 2D and 3D" note.
- **Disable Interlacing** (SwanStation) gained its "others will break" caveat.

**Two SwanStation options can't do anything in this app.** SwanStation's own
strings say *PGXP Geometry Correction* ("Only works with the hardware renderers")
and *Texture Filtering* ("Only applies to the hardware renderers") are
hardware-renderer features -- but `GameSystem.PS1`'s SwanStation `CoreDefinition`
pins `rendererChoices = listOf("Software")`, deliberately (see the 2026-08-16
selectable-core entries: its OpenGL choice was removed after misfiring). So both
rows have been inert since the day they were curated.

**Both were then removed outright**, on the user's call: SwanStation as built here
simply can't do hardware rendering, which is the whole reason Beetle PSX HW is
offered alongside it. A switch that looks functional and does nothing is worse than
a missing one, so they're gone from `CoreOptions`' SwanStation list along with their
descriptions.

Their absence would itself be puzzling, though, so it gets explained rather than
left as a silent gap: new `CoreOptions.omittedNotes` maps a core .so fileName to a
string resource, and `renderSystemCoreOptionsList` renders it through the same
`standaloneNote` helper as a footnote under that core's rows -- "Why some options
are missing", expanding to say the two needed a hardware renderer and to switch the
PS1 core to Beetle PSX HW under Graphics for both. After the rows, not above them:
it's a footnote about what isn't there, not an introduction to what is. Keyed by
core rather than system, so it follows the selected core the way `forCore` does.

A value someone already saved for either option simply stops being read; it was
never applied to anything either, since `TacoBoyActivity.setupRetroView` builds
`variables` straight from `forSelectedCore`.

**Hardware-verified** with the PS1 core set to SwanStation: five rows remain, the
two removed ones are gone, and the footnote renders and expands correctly beneath
them.

**Hardware-verified** on the connected device: every GBA, SNES and PS1 row shows
an info button, expanding gives the right text in place, and switching system
tabs re-renders correctly. Cycling an option's value doesn't rebuild the section
(`coreOptionRow` updates its own button text), so a description stays open while
trying the choices it describes -- which is the whole point of putting it there.

## 2026-08-23 (Settings notes collapsed behind info buttons)

**Every explanatory note in Settings is now collapsed by default.** Each one used
to render as always-on body text under the control it described, which meant the
prose, not the settings, was what filled the screen: General showed four toggles
buried under roughly thirty lines of grey text (Auto-save alone ran to eleven),
and Controller pushed half its GBA bindings below the fold behind three lines of
instructions. Same clamp-boundary logic as the BIOS list above -- vertical space
on this screen is the scarce resource.

Two new helpers in `SettingsActivity`. `withNote(row, vararg noteRes)` slips an
info button into a label+value row at index 1 (after the label, which carries the
layout weight, so it lands just left of the value control) and hangs the collapsed
note under it -- used for Fast Forward, Show FPS, Auto-save, Resume on Launch,
Low-latency audio, Hardcore Mode, Log level, PS1 Core, PS1 Renderer, and
Auto-configure Pocket Taco bindings. `standaloneNote(titleRes, vararg noteRes)`
covers notes with no row to hang off -- a section intro, or one describing a whole
block of controls -- rendering a dim one-line "ⓘ <title>" header (the whole line is
the target, not just the glyph) for System's core-options intro, Achievements'
two login explanations, Controller's binding instructions, and Advanced's
alignment and reset-all notes. New title strings exist only so the collapsed state
still says what's hidden.

**No text was cut** -- every existing note string is unchanged and reachable in
one tap. Notes that ran to two paragraphs (Auto-save + the PS1 memory-card
explanation) share one button and open together, as they read.

Expansion state lives in `expandedNotes`, keyed by the note's string resource id,
rather than on the views -- `renderGeneralSection` and `renderGraphicsSection`
rebuild their whole section on any change, so without it, opening a note and then
touching any control on the same tab would silently collapse it again. That is
also why the helpers take resource ids rather than resolved strings: the first id
doubles as the stable key. Two incidental cleanups fell out of the same pass: the
renderer note moved from one shared copy at the bottom of Graphics onto each
renderer row it actually describes (so it disappears with them when no system has
a renderer choice), and `selectableItemBackgroundResId` joined the borderless
variant this Activity already had.

**Hardware-verified** on the connected device across all seven tabs: General
collapsed from a wall of text to five rows in the same space; expanding Auto-save
showed both paragraphs and re-collapsed cleanly; Controller now fits all ten GBA
bindings on screen; and Achievements, Advanced, Audio and Graphics each render as
rows plus one-line note headers. Confirmed a `standaloneNote` expands from a tap
on its title text, not just the icon.

## 2026-08-23 (BIOS list moved into a dialog, gained delete; 7500-series BIOSes now detected)

**The BIOS list no longer stacks under the system tabs.** Inline, it was
vertical cost paid by exactly one system: on PS1 the ROM grid started several
rows lower than on GBA/GB/GBC/SNES, and it got worse with every BIOS imported
-- on a screen a clamp-on Pocket Taco has already shortened via the boundary
guideline, that was the wrong place to spend space. `bios_status_row` is gone
from `activity_rom_library.xml` entirely (the recycler now anchors straight to
`search_row`, same as every other system), and the list moved into a dialog
behind the existing PS1-only toolbar button (`RomLibraryActivity.showBiosDialog`).
The at-a-glance part survives without costing a row: the button's own label
carries the active BIOS's region flag (e.g. "BIOS [US flag]"), or a warning glyph when PS1
has no BIOS at all -- so which region is live is still readable without opening
anything. Import moved into that dialog as its neutral button, wired after
`show()` so picking a file doesn't dismiss the dialog: the import lands back in
a list that's still open (`biosListContainer` is held for exactly that), making
"import, then choose which one is active" one flow instead of two.

**BIOS files can now be deleted.** Each dialog row has a delete button beside
its select target -- `BiosManager.deleteBios` removes the file from `filesDir`,
clears it as any system's saved choice (`TacoBoyPrefs.clearActiveBiosFileName`,
new -- iterates `GameSystem.entries`), and drops the staged copy in
`bios_active` immediately rather than waiting for `prepareActiveBios`'s
next-launch wipe, so a deleted BIOS can never be the one handed to a core.
Deleting the *active* file is allowed: `resolveActiveFileName` already falls
back to the first remaining detection. Delete is confirmed, select isn't -- a
BIOS dump can't be re-fetched from inside the app the way box art can, so a
mis-tap next to "Select" would cost a file the user may have no other copy of.

**`BiosRegion` gained the SCPH-7500 series (plus 5552).** Found while testing
on hardware: the device's own library held a `PSX - SCPH7502.BIN` that
`BiosRegion.detect` returned null for, since the table was built only from
`BIOSregion.md`'s `[SIMPLE]` section, which stops at the 7000-series. That was
already a display gap, but the picker makes it a functional one -- a file the
list can't identify is one the user can neither select nor delete, so it sits
in the library unreachable. Added 7500 (Japan), 7501 (USA), 7502 (Europe), 5552 (Europe -- 5502's
twin, differing only in what was in the box) and 7503 (Asia) from the file's
`[DETAILED]` catalog, which names 7503 the Southeast Asian model outright
rather than folding it into the UK bucket its 7003 predecessor sits in.

**Hardware-verified** end to end on the connected device: the PS1 grid now
starts level with every other system's; the dialog listed all four real
BIOSes including the previously-invisible 7502; selecting a different BIOS
updated both the row highlight and the toolbar flag; and a throwaway copy
(`DELETE-ME - SCPH5501.BIN`, made via `run-as` so no real dump was risked) was
deleted through the UI, confirmed gone from `filesDir`, with the list
refreshing in place and the unrelated active selection untouched.

## 2026-08-18 (In-game Reset now fully reloads; composite-cable option added; James Bond "squares" investigated)

**In-game Reset didn't apply Settings changes made mid-session** -- user
found they had to switch ROMs away and back to get a changed core option to
actually take effect; a plain `retroView.reset()` (`retro_reset()`, the
console's own reset button) never re-reads `variables` at all, since those
are only applied once in `setupRetroView` when a game is freshly loaded.
Since a PS1 `retro_reset()` already reboots through the BIOS anyway (no
faster than a fresh load), there's no real cost to doing a full reload
instead. `TacoBoyActivity.onResetClicked` now sets a new
`EXTRA_FORCE_RELOAD_ROM_URI` intent extra (the exact ROM already running)
and calls `recreate()` -- the same safe reload path `libraryLauncher`
already used for switching ROMs (manually tearing down/rebuilding
`retroView` in place is documented there as unsafe: LibretroDroid is a
native singleton, verified SIGSEGV history). `initRomFlow` checks this
extra before its normal `TacoBoyPrefs`-driven "resume last ROM" logic, so
Reset reloads reliably regardless of whether the user has "Resume on
Launch" enabled -- that preference is about cold app launches, an
unrelated concern. **Hardware-verified**: triggered Reset mid-session and
confirmed a full fresh reboot (developer splash → BIOS → game boot),
proving it reloads the same ROM from scratch rather than getting stuck at
the picker.

**`beetle_psx_hw_video_cable` ("Analog Video Cable") added** as
`CoreOptions.kt`'s 30th Beetle PSX HW entry, at the user's request after
they suggested it as a CRT-look option ("composite cable simulation...
without having to turn on shaders"). Choices `off|rgb|svideo|composite|rf`,
defaulted to `off` (the core's own real default, confirmed by this file's
existing default-parsing guarantee) -- a pure addition, not a behavior
change. Its NTSC-signal-simulation siblings (`phase_error`/`black_setup`,
fine-tuning knobs that only matter once a cable type is actually selected)
stay excluded for now.

**James Bond's jacket "covered in squares" under `hardware_gl`,
investigated.** Reproduced on hardware (Tomorrow Never Dies, third-person
pause-screen view, snowy level lit by both an orange explosion and blue
moonlight) and inspected a zoomed crop: isolated single pixels of
saturated, essentially random hues (green/cyan/orange) scattered across
the jacket's dark fabric texture. This is a **different visual signature**
from the earlier-fixed color-banding issue (which was a dense, ordered,
uniform stipple present across *all* 16-bit-rendered content -- gameplay,
menus, FMV alike) -- this new pattern is sparse, randomly-colored, and
only appears on the dynamically-lit character model, not on flat 2D
content (HUD/menus stayed crisp in the same frame). Current best
assessment: this matches a well-documented, authentic characteristic of
real PS1 hardware -- Gouraud-shaded (per-vertex, not per-pixel) lighting
on very low-polygon character models, combined with strong, contrasting
colored dynamic lights, produces exactly this kind of "sparkle" where
texture detail shows through sharp per-vertex color interpolation
boundaries. All of this session's newly-curated options that touch
texturing/sampling (`internal_resolution`, `msaa`, `super_sampling`,
`adaptive_smoothing`) were confirmed still at their safe/native/disabled
defaults, so none of today's additions are implicated. **Not fully
conclusive** -- no direct comparison was done against SwanStation's
software renderer or real hardware footage to rule out a hardware_gl-
specific exaggeration of this effect. If revisited: that comparison (does
the identical scene show the same sparkle under SwanStation's forced
Software renderer?) would be the decisive next test -- if yes, it's
authentic content; if no, it's specific to Beetle's hardware_gl path.

## 2026-08-17 (Beetle PSX HW: core options expanded to 29, closer to SwanStation parity)

**User request following the color-depth fix**: bring Beetle PSX HW's
Settings > System > PS1 coverage up towards SwanStation's level, since only
`dither_mode`/`depth` were curated out of the ~92-option catalog already
captured earlier the same day. Added 27 more `CoreOptions.kt` entries (29
total for this core), grouped by theme with inline comments: loading/boot
(`cd_access_method`, `cd_fastload`, `skip_bios`, `region`); performance
(`gpu_overclock`, `gte_overclock`, `cpu_dynarec`, `spu_silent_voice`,
`frame_duping`); upscaling/filtering (`internal_resolution`,
`scaled_uv_offset`, `filter`, `adaptive_smoothing`, `super_sampling`,
`msaa`, `line_render`); PGXP geometry-precision correction (`pgxp_mode`,
`pgxp_nclip`, `pgxp_vertex`, `pgxp_texture` -- same category as
SwanStation's own PGXP toggle); display/aspect (`aspect_ratio`,
`crop_overscan`, `deinterlacer`, `widescreen_hack`,
`widescreen_hack_aspect_ratio`); and DualShock analog behavior
(`analog_calibration`, `analog_toggle`).

Every new option defaults to its own first-listed choice, which
`environment_handle_set_variables`'s parsing guarantees is the core's real
compiled default whenever nothing pre-populates it (this exact mechanism
was empirically confirmed for `dither_mode` earlier the same day via live
`GET_VARIABLE` logging) -- so this batch is a pure curation add, not a
behavior change, unlike `depth`'s deliberate `32bpp` override.

**Deliberately still excluded**, matching the file's existing exclusion
philosophy (long choice lists, peripherals irrelevant to a single-player
handheld): CPU/GPU-cycle-percentage and memory-card-slot-index options
(dozens of choices each); light gun / mouse / neGcon / multitap options;
debug-only options (texture dump/track, full-VRAM display); the whole
HD-texture-replacement cluster (no bundled texture packs to point it at);
the HDR display cluster (`color_format`'s `30bit_hdr` choice needs a
display pipeline this app doesn't model, and pulls in 5 more
HDR-specific options that only matter if it's on); analog-video-cable/
NTSC-signal simulation (`video_cable`/`phase_error`/`black_setup` -- a
possible future "CRT look" theme, not this pass); BIOS override (needs
alternate BIOS files this app doesn't bundle -- picking a missing one
risks breaking boot); and the memory-card-behavior cluster
(`use_mednafen_memcard0_method`/`enable_memcard1`/`shared_memory_cards` --
would interact with TacoBoy's own per-ROM virtual memory card system in
untested ways).

**Verified on hardware**: installed, confirmed all 29 rows render
correctly in Settings > System > PS1 with sane default values (scrolled
through the full list), then booted two different games (Crash Team
Racing, Bloody Roar II) fresh with all 29 variables applied simultaneously
-- no crashes, no rendering regressions, menus and gameplay unaffected.
Individual per-option visual effects weren't each exhaustively verified
(would take hours for 27 options) -- relying on correct variable plumbing
(proven this session across `dither_mode`, `mdec_yuv`, and `depth`) plus
the existing per-option and Reset-All-Settings reset mechanisms as the
safety net if any individual default surprises the user.

## 2026-08-17 (Beetle PSX HW: cutscene noise -- actual root cause found, real fix shipped)

**Following up same day yet again.** The `mdec_yuv` ("Cutscene Smoothing")
guess below turned out not to be the fix -- user tested with "007 Tomorrow
Never Dies" (a real pre-rendered FMV cutscene, the gun-barrel intro) and the
colored speckle noise was still there. Rather than guess again, captured
hard evidence: added temporary `LOGI` diagnostics in
`LibretroDroid::callback_hw_video_refresh` confirming `data` is *always*
`RETRO_HW_FRAME_BUFFER_VALID` for this content (ruling out a raw-pixel-buffer
upload gap in `FramebufferRenderer` -- the core really is rendering FMV
through the same GL hardware-render path as everything else, not bypassing
it), then re-examined the "Dithering" option's own core-provided description
text already on file: *"...Recommended to be disabled when running at 32bpp
color depth."* That's the tell -- `beetle_psx_hw_dither_mode` was disabled
(for gameplay, correctly) without ever touching `beetle_psx_hw_depth`
(`16bpp(native)|32bpp`, uncurated, sitting at the core's native-16bpp
default). Real root cause: disabling dithering unmasked ordinary 16-bit
color banding on gradient-heavy content -- glossy 3D logo text, FMV video --
that the dithering pattern had been hiding. Flat-shaded, low-poly PS1 game
geometry rarely has smooth-enough gradients to show the same banding, which
is exactly why gameplay looked clean while logos and cutscenes didn't.

**Fix:** added `beetle_psx_hw_depth` to `CoreOptions.kt` as "Color Depth",
defaulted to `32bpp` -- removes the banding at its source instead of
depending on dithering to mask it, so both gameplay (already fine without
dithering) and cutscenes/logos (now also fine without needing dithering)
can stay on `dither_mode = disabled`. Removed the `mdec_yuv` curated entry
added below; it wasn't the fix and wasn't worth keeping as a real
user-facing toggle once depth turned out to be the actual answer.

**Hardware-verified, directly comparable before/after.** Same "007 Tomorrow
Never Dies" gun-barrel FMV frame that showed heavy colored speckle noise
with `dither_mode=disabled` + `depth=16bpp(native)` now renders completely
clean with `depth=32bpp`, `dither_mode` unchanged. Also re-checked a second,
independent FMV frame (an explosion/satellite scene, heavy on color
gradients -- the worst case for 16-bit banding) — clean. Re-checked gameplay
(Bloody Roar II, including an active hit-spark VFX moment that had shown
noise earlier in the same investigation) -- still clean, no regression.
Diagnostic logging removed after confirming.

## 2026-08-17 (Beetle PSX HW: cutscene noise -- new MDEC chroma filter option added, later found not to be the fix)

**Following up same day again**, after user feedback that gameplay was
confirmed clean (per the dithering re-investigation below) but cutscenes
still looked noisy. This pointed away from `beetle_psx_hw_dither_mode`
(GPU polygon dithering, already confirmed reaching the core correctly)
towards MDEC, the PS1's dedicated video-decode hardware used for FMV --
a completely different rendering path than the GPU polygon renderer.
Added temporary `LOGI` diagnostics to `environment_handle_set_variables`
(removed after) to capture Beetle PSX HW's *entire* option catalog (92
options, up from the ~80 estimated 2026-08-16) via `adb logcat` during a
fresh boot. Found `beetle_psx_hw_mdec_yuv` ("MDEC YUV Chroma Filter",
`disabled|enabled`) sitting uncurated at the core's own compiled default
(`disabled`, first-listed). PS1 FMV stores chroma at lower resolution
than luma (standard YUV subsampling); without a filter smoothing the
upsample back to RGB for display, that reads as blocky/speckled noise --
a plausible, well-targeted match for the reported symptom. Added as
`CoreOptions.kt`'s second Beetle PSX HW entry ("Cutscene Smoothing"),
defaulted to `enabled` (unlike `dither_mode`, which matches the core's
own default) since this looks like a straightforward improvement for
exactly the reported problem, not a real trade-off. Confirmed the new
Settings > System > PS1 row appears and defaults correctly. **Not yet
visually verified against a real FMV cutscene** -- couldn't get one to
trigger quickly within this session's game library (Crash Team Racing's
intro turned out to be real-time rendered via the same clean GL path,
not pre-rendered FMV, so it wasn't a useful test case).

**Superseded same day, see the entry above** -- tested against a real FMV
cutscene shortly after this shipped and the noise was still there. This
option (and the diagnostic technique of dumping the full option catalog)
wasn't wasted work -- it's what made the *actual* fix (`beetle_psx_hw_depth`
-> `32bpp`) findable quickly -- but `mdec_yuv` itself was removed from
`CoreOptions.kt` once the real cause was confirmed.

## 2026-08-17 (Beetle PSX HW: real BIOS splash scaling gap closed; dithering re-verified, not a bug)

**Following up same day** on the "scaling glitch fixed" entry below, after user
testing found the real PS1 BIOS splash screen ("SONY COMPUTER ENTERTAINMENT")
still rendered oversized/cropped on a different game, even though the
FromSoftware/Armored Core boot logo case was confirmed clean. Root cause: a
second, distinct gap in the same code. `FramebufferRenderer`'s FBO only ever
resized via `updateRenderedResolution`, which was only ever called from the
SET_GEOMETRY path (now synchronous, per the earlier fix). But
`FramebufferRenderer::onNewFrame`'s own `width, height` parameters -- the
size the core declares directly via `video_refresh_cb`, which feeds
`lastFrameSize` (used for on-screen scaling) -- were never compared against
the FBO's actual tracked size. The real PS1 BIOS splash is the core's very
first rendered content, before the game itself has ever called SET_GEOMETRY
-- so `lastFrameSize` updated correctly but the FBO's tracked size never
did, permanently (not just transiently) mismatching the on-screen scale math
for that entire screen. Fix: `onNewFrame` now also calls
`updateRenderedResolution(width, height)` with its own parameters, treating
the core's per-frame declared size as authoritative too, same as an explicit
SET_GEOMETRY call. Reallocation itself stays deferred to `getFramebuffer()`'s
existing lazy check (next frame), not done inside `onNewFrame`, so the
just-rendered frame's content isn't discarded by resizing out from under it.

**Hardware-verified.** Rebuilt, installed, launched Bloody Roar II fresh
(different game from the first fix's Armored Core test) from the Library
while `adb shell screenrecord` captured the boot sequence, then inspected it
frame-by-frame with `ffmpeg`. The real "SONY COMPUTER ENTERTAINMENT" BIOS
splash -- previously the persistent bug this session's user report was about
-- now renders correctly scaled and centered from its very first frame, no
cropping.

**Dithering re-investigated after user reported it "still happening even
with it disabled."** Added temporary `LOGI` diagnostics (`environment.cpp`'s
`updateVariable`/`environment_handle_set_variables`/
`environment_handle_get_variable`, removed again after) to trace the real
value flow, since `LOGD` compiles to nothing with this build's
`VERBOSE_LOGGING = false`. Findings, captured live via `adb logcat` during a
fresh Bloody Roar II boot:
- The core's real registered choices are `1x(native)|internal
  resolution|disabled` -- three, not the two `CoreOptions.kt` previously
  offered. The 3rd choice ("internal resolution") couldn't be confirmed with
  confidence back on 2026-08-16 and was deliberately left out; now confirmed
  verbatim from the core's own SET_VARIABLES registration string and added.
- Every single `GET_VARIABLE` call for `beetle_psx_hw_dither_mode` returned
  `disabled` -- the frontend is not the problem; "disabled" reaches the core
  correctly, every time, from the very first read.
- Re-tested visually with dithering confirmed "disabled": extensive footage
  (attract-mode demo fight, a lion-transformation cutscene, the character
  lineup screen) showed zero speckle noise. The one screenshot that *did*
  show it, from earlier in the same testing session, was during an active
  "2 HIT" combo -- almost certainly the game's own hit-spark VFX (PS1
  fighting games commonly use dithered/stippled transparency for impact
  sparks), not the hardware-accuracy dithering this option controls.
- Conclusion: not a regression, not currently reproducible outside of what
  looks like legitimate in-game effects. Diagnostic logging removed after
  confirming; `CoreOptions.kt`'s dithering entry keeps its comment updated
  with what was learned instead of re-adding placeholder logging.

## 2026-08-17 (Beetle PSX HW: scaling glitch fixed; SwanStation OpenGL option removed)

**Scaling glitch fixed**, closing out the "not yet fixed" entry immediately
below. Both structural gaps it traced were closed:
- `environment.cpp`'s `RETRO_ENVIRONMENT_SET_GEOMETRY`/`SET_SYSTEM_AV_INFO`
  case now invokes a new `geometryChangedCallback` synchronously, still
  inside the core's `retro_run()`, instead of only setting a flag for
  `LibretroDroid::step()` to poll after `retro_run()` returns. Wired via
  `Environment::setGeometryChangedCallback` (same function-pointer pattern
  as `callback_get_current_framebuffer`) to a new
  `LibretroDroid::handleGeometryChanged`, which calls
  `video->updateRendererSize(...)` immediately. The now-dead polling flag
  (`isGameGeometryUpdated`/`clearGameGeometryUpdated`/`gameGeometryUpdated`)
  was removed rather than left unused.
- `FramebufferRenderer::getFramebuffer()` (called by the core mid-frame,
  after `SET_GEOMETRY` but before it renders, via `get_current_framebuffer`)
  now reallocates the FBO immediately if `isDirty`, instead of waiting for
  `onNewFrame`'s end-of-frame check. Without this half too, the first fix
  alone still handed the core a stale-sized FBO to render into for that
  frame — the resize metadata was current but the actual GL objects
  weren't reallocated until one frame later.
- Together: by the time a HW-rendered core asks for its framebuffer this
  frame, both the renderer's recorded size and the FBO itself already
  reflect the geometry the core told us about moments earlier in the same
  `retro_run()` call, instead of one (or two) frames behind. Native code
  builds clean (`externalNativeBuildDebug`, all 4 ABIs).

**Hardware-verified same day.** Installed the rebuilt debug APK to the
user's real device via adb, set PS1 Core = Beetle PSX HW in Settings >
Graphics, launched Armored Core fresh from the Library while
`adb shell screenrecord` captured the boot sequence, then inspected it
frame-by-frame with `ffmpeg`. Both geometry-changing transitions that
previously showed the oversized/cropped glitch (Library → FromSoftware
boot logo, boot logo → title screen) are now clean on their very first
rendered frame — no cropped or oversized frame at either cut.

**SwanStation's "OpenGL" renderer choice removed from Settings.** It was
never a real option — selecting it produces audio with a black screen (see
the PS1-selectable-core entry below for why: LibretroDroid only ever hands
out a GLES3 context, and SwanStation's HW renderer specifically requests a
desktop-style OpenGL one). Leaving it selectable in Settings > Graphics
was presenting a broken state as a legitimate trade-off. `GameSystem.kt`'s
SwanStation `CoreDefinition` now has `rendererChoices = listOf("Software")`
only. Also hardened against the same stale-saved-value class of bug noted
in the PS1-selectable-core entry's "Gotcha" below: added
`CoreDefinition.selectedRenderer(context, system)`, which validates the
saved renderer choice against the core's current `rendererChoices` and
falls back to `defaultRenderer` if it no longer matches (e.g. a value
saved back when "OpenGL" was still offered) — same stale-value pattern
`GameSystem.selectedCore` already used for core-file-name fallback. Used in
both `TacoBoyActivity.setupRetroView` (what actually gets passed to the
core) and `SettingsActivity.rendererRow` (what the row displays), replacing
the previous unvalidated `TacoBoyPrefs.getRendererChoice(...) ?:
defaultRenderer` reads at both sites.

## 2026-08-16 (Beetle PSX HW: dithering noise fixed, scaling glitch root-caused)

**Following up on real hardware feedback** after shipping selectable PS1
cores (see entries below): user reported two visual issues specific to
Beetle PSX HW's `hardware_gl` renderer — colored speckle noise across
gameplay, and the PS1 intro/logo screens rendering oversized/cropped past
the visible window before self-correcting once real gameplay starts.

**Speckle noise — root-caused and fixed.** Confirmed via live
`GET_VARIABLE` logging that the core queries `beetle_psx_hw_dither_mode`
every single frame (no other option comes close to that call frequency).
Extracted the core's own embedded option description via a raw binary
string search: *"'1x (Native)' emulates native low resolution dithering
used by original hardware to smooth out color banding artifacts...
Recommended to be disabled when running at 32 bpp color depth."* This is
a genuine PS1-hardware-accuracy feature (not a rendering bug) that's
simply more visible on modern flat panels than it was on original CRTs —
and our `color_format` default is effectively 32bpp, exactly the case the
core's own docs say to disable it for. Added `CoreOptions.kt`'s first
Beetle PSX HW entry: `beetle_psx_hw_dither_mode` ("Dithering", choices
`1x(native)`/`disabled`, default `1x(native)` to match the core's own
default unprompted). Deliberately excludes the core's third choice
("Internal Resolution") since its raw value string couldn't be confirmed
from the binary with the same confidence as these two — shipping a wrong
guess here would silently mismatch exactly like the cross-core renderer
leak bug did (see below). **Verified on hardware:** set to "disabled" via
the real Settings > System > PS1 row, loaded two different games (Crash
Team Racing, Bloody Roar II) under `hardware_gl` — zero speckle noise on
title screens, copyright screens, and real 3D gameplay, no crashes.

**Scaling glitch — root cause identified via code tracing, not yet
fixed.** Reproduced reliably: PS1 logo/intro/title screens using a
different internal resolution than the game's main resolution render
oversized and cropped past the screen edges for the duration of that
screen, self-correcting once gameplay's own resolution takes over.
Traced the actual mechanism in `libretrodroid.cpp`/`framebufferrenderer.cpp`:
- A HW-rendered core signals a resolution/geometry change via the
  `SET_GEOMETRY`/`SET_SYSTEM_AV_INFO` environment callback, called
  synchronously *during* that frame's `core->retro_run()` — before the
  core issues its GL draw calls for the new size.
- `LibretroDroid::step()` only checks `Environment::isGameGeometryUpdated()`
  and calls `video->updateRendererSize(...)` (which sets `isDirty` on the
  `FramebufferRenderer`) *after* `retro_run()` returns — i.e., one full
  frame after the core already knew about and rendered at the new size.
- `FramebufferRenderer::initializeBuffers()` (which actually deletes and
  recreates the FBO's GL objects at the new size) only runs reactively
  inside `onNewFrame`, gated by that `isDirty` flag — so the FBO doesn't
  actually get resized until the *following* frame's video-refresh
  callback, one frame later still.
- Net effect: every time a HW-rendered core changes resolution, there's a
  structural one-frame lag between the core's own understanding of its
  render target size and our FBO's actual size/aspect, causing exactly
  the observed oversized/cropped look until the lag resolves itself.
- This is a `FramebufferRenderer`-only bug — the software `ImageRendererES3`
  path re-samples the incoming CPU-side pixel buffer's declared
  width/height fresh on every single call, so there's no equivalent lag
  possible there. This also explains why nothing caught this until now:
  Beetle PSX HW is the first core in this app to ever exercise the real
  `FramebufferRenderer` path end-to-end (see "Research: LibretroDroid's
  HW-render path validated via Beetle PSX HW" below).

**Deliberately not fixed this session** — the real fix means restructuring
when/where the FBO resize happens (synchronously at the `SET_GEOMETRY`
environment-callback site, not reactively after `retro_run()` returns),
which touches shared, foundational rendering code used by every future
HW-rendered core, not just PS1. Impact today is purely cosmetic (a few
ugly transitional frames during boot/menu screens, self-corrects, never
affects sustained gameplay) — real but low-severity, and a native-code
change to this specific timing path deserves deliberate scoping and
testing rather than a rushed fix bundled into an already-large session.

## 2026-08-16 (PS1: selectable core — SwanStation / Beetle PSX HW)

**Added, following up on the same day's "Research: LibretroDroid's HW-render
path validated via Beetle PSX HW" entry below** — that investigation showed
Beetle PSX HW gets real hardware-accelerated PS1 rendering where SwanStation
can't, so instead of leaving it as an unused proof-of-concept binary, built
proper per-system core switching with PS1 as the first (and so far only)
system that has it.

**Architecture:** `GameSystem.kt`'s `coreFileName`/`rendererOptionKey`/
`rendererChoices`/`defaultRenderer` fields (one core per system, hardcoded)
replaced with `cores: List<CoreDefinition>` — a new top-level data class
bundling a core's `.so` fileName, display name, and its own renderer
option key/choices/default. Every existing system still gets exactly one
`CoreDefinition` (no behavior change for GBA/GB/GBC/SNES); PS1 gets two.
`GameSystem.selectedCore(context)` resolves `TacoBoyPrefs`' saved choice
against the real list, falling back to `defaultCore` (`cores.first()`) if
nothing's saved or the saved fileName is stale.

**Real bug fixed in the same pass, found empirically while testing:**
renderer choice was previously saved per-*system*
(`renderer_choice_PS1`), not per-core. Since SwanStation and Beetle PSX HW
use completely different value conventions for the same concept
(`"Software"`/`"OpenGL"` vs lowercase `"software"`/`"hardware_gl"`/
`"hardware_vk"`), a SwanStation-era saved value leaked into Beetle's
option and silently mismatched, falling back to Beetle's own software
mode with no visible error — exactly the bug that made the "does
LibretroDroid's HW path work at all" question hard to answer in the first
investigation. `TacoBoyPrefs.getRendererChoice`/`setRendererChoice`/
`clearRendererChoice` now take a `coreFileName` param and key by
`(system, coreFileName)` instead of just `system` — as a side benefit,
each core now remembers its own renderer choice independently across
switches, rather than clobbering a shared value.

**CoreOptions.kt** (System tab's curated per-system option lists) similarly
re-keyed from `Map<GameSystem, List<Option>>` to `Map<String, List<Option>>`
by core fileName, via new `CoreOptions.forSelectedCore(context, system)`.
No curated options exist yet for Beetle PSX HW (~80 of its own, none
hand-picked) — the System tab correctly falls back to its existing "No
core options for this system yet" placeholder rather than showing
SwanStation's now-inapplicable list.

**Settings > Graphics** gained a "PS1 Core" row (cycle SwanStation ↔
Beetle PSX HW, same cycle-on-tap pattern as every other choice row) above
the existing "PS1 Renderer" row. Changing core rebuilds the whole Graphics
section (`renderGraphicsSection`) since the renderer row's choices/default
depend on which core is now selected — same rebuild-on-change pattern as
`renderAchievementsStatus`. A new note under the Core row warns switching
can affect save-state compatibility (SRAM/cart save is unaffected).

**Verified end-to-end on hardware**, via the shipped UI (not manual prefs
edits): Core row correctly switches SwanStation ↔ Beetle PSX HW; Renderer
row correctly rebuilds to each core's own choices with no cross-core value
leakage (`hardware_gl` showed cleanly for Beetle immediately, not a
mismatched leftover); System tab shows real SwanStation options when
SwanStation is selected and the "no options yet" placeholder for Beetle;
loaded two real PS1 games (Army Men 3D, Crash Team Racing) under Beetle
PSX HW + `hardware_gl` — real textured 3D rendered correctly, no shader
errors, no crashes; quick menu's Info dialog correctly read
`currentCore.displayName` ("Core: Beetle PSX HW") instead of the old
hardcoded per-system mapping; Reset All Settings correctly cleared both
`core_choice_PS1` and the renderer choice, reverting to SwanStation/
Software, while leaving RetroAchievements login untouched. `adb logcat`'s
`AndroidRuntime:E`/`*:F` clean throughout (one unrelated benign
`libEGL: call to OpenGL ES API with no current context` line, not from
our code, no visible effect).

## 2026-08-16 (Research: LibretroDroid's HW-render path validated via Beetle PSX HW)

**Not a shipped change — investigation only, reverted after testing.** Was
reviewing user-authored `OpenGLmethodideas.md`/`VulkanIntegration.md` docs
for PS1's black-screen-on-hardware-rendering problem (see "PS1 support via
SwanStation" entry below). Cross-checked the docs' 6 proposed EGL/environment
fixes against the actual vendored source and found none of them address the
real root cause (SwanStation requests desktop `RETRO_HW_CONTEXT_OPENGL`,
LibretroDroid only hands out GLES3 -- a shader-source incompatibility, not
an EGL config/context-negotiation issue).

**Real finding:** downloaded Beetle PSX HW (`mednafen_psx_hw_libretro_android.so`,
from the same libretro nightly buildbot SwanStation came from, URL verified
via fetch, not guessed) and temporarily swapped it in as PS1's core to test
whether LibretroDroid's hardware-render code path (`environment.cpp`'s
`SET_HW_RENDER` handling, `renderers/es3/framebufferrenderer.cpp`) -- never
successfully exercised by any core in this app before -- actually works.
Added temporary `LOGI` diagnostics (since `LOGD` is compiled out in this
build, `log.h`'s `VERBOSE_LOGGING false`) to `video.cpp`/`environment.cpp`,
all reverted after testing. Confirmed on hardware:
- `beetle_psx_hw_renderer` (extracted via `grep -a` on the binary, real
  choices are `software`/`hardware_gl`/`hardware_vk` -- lowercase, distinct
  from SwanStation's `Software`/`OpenGL` convention) forced to `hardware_gl`
  → core called `RETRO_ENVIRONMENT_SET_HW_RENDER` with `context_type=4`
  (`RETRO_HW_CONTEXT_OPENGLES3`) → accepted → `hardwareAccelerated=1` in
  `Video::initializeRenderer`, meaning `FramebufferRenderer` (the real ES3
  HW path) engaged for the first time ever in this fork.
- Two real PS1 games (Armored Core, a 3D mecha game; From Software's PS1
  boot logo) rendered correctly under this path, no shader compile errors,
  no crashes, `adb logcat`'s `AndroidRuntime:E`/`*:F` clean throughout.
- **First attempt appeared to fail** (still `hardwareAccelerated=0` after
  forcing `hardware_gl` in `GameSystem.kt`'s `defaultRenderer`) -- root
  cause was a stale `renderer_choice_PS1=Software` already saved in
  `tacoboy_prefs.xml` from earlier real SwanStation sessions, silently
  overriding the new default via `TacoBoyPrefs.getRendererChoice(...) ?:
  system.defaultRenderer`. Beetle PSX HW didn't recognize `"Software"`
  (capitalized, SwanStation's convention) as one of its own lowercase
  choices and silently fell back to software rendering. Fixed by cycling
  the value through the real Settings > Graphics UI instead of editing
  prefs directly.

**Conclusion:** LibretroDroid's own HW-render frontend code is NOT broken
-- it correctly serves a well-behaved GLES3-requesting core. SwanStation's
black screen is specific to SwanStation requesting desktop GL, not a
LibretroDroid limitation. Beetle PSX HW is a viable, immediately-available
path to real hardware-accelerated PS1 rendering with zero LibretroDroid
frontend changes needed -- no Vulkan required. **Decision on whether to
adopt it (as PS1's new default core, an alternate core choice, or not at
all) is still open** -- this was a feasibility test, not a commitment.
`mednafen_psx_hw_libretro_android.so` (~13MB) was left in
`app/src/main/jniLibs/arm64-v8a/` since it's a real, working, validated
binary, but `GameSystem.kt`'s `PS1` entry was reverted back to stock
SwanStation config (`swanstation_libretro_android.so`, `Software`/`OpenGL`
choices) -- the phone is back to its normal state.

## 2026-08-16 (Masked Web API key display)

**Added, last open item from `settingslayoutideas.md`'s Achievements
list.** `renderAchievementsStatus` now shows a second line, "Web API Key:
••••••••••••••••••••s7Y8" (new `maskApiKey`, keeps the last 4 characters
visible), right under the "Logged in as X" row whenever credentials are
saved. Purely a display-confirmation of which key is set — the full key is
never shown again after it's entered in `promptRetroAchievementsLogin`.
Reuses the existing `placeholderText` style rather than adding a new row
primitive, same as every other secondary-info line in this screen.

**Verified on hardware:** logged in as ChikinNuggit — masked line appeared
correctly under the status row, last 4 characters matched the real key.
`adb logcat`'s `AndroidRuntime:E`/`*:F` clean.

## 2026-08-16 (Advanced tab: dev/power-user tools built out)

**Added, closes out `settingslayoutideas.md`'s Advanced tab list** — the tab
had been a bare "coming soon" placeholder since the System/Advanced swap
earlier the same day. Four items:

1. **Log level** (Error/Warn/Info/Debug, cycle-on-tap row like
   `fastForwardModeRow`) — gates a new in-memory ring buffer (`TacoBoyLog`,
   capped at 500 lines) that Export Logs reads from.
2. **16 KB alignment note** — static info text, the exact wording from the
   doc, referencing the compatibility warning already logged 2026-08-13.
3. **Export Logs** — saves the ring buffer to a user-picked location via the
   Storage Access Framework (`ActivityResultContracts.CreateDocument`, no
   new permission needed). Deliberately does NOT mean "grab real Android
   logcat" — that's not readable by a third-party app on modern Android
   without a signature/system permission. Empty buffer shows a toast
   instead of opening the save picker.
4. **Reset All Settings** — curated, not `SharedPreferences.clear()`. Clears
   every Settings-screen-exposed pref (fast forward, FPS overlay, auto-save,
   resume-on-launch, low-latency audio, hardcore mode, log level, plus the
   prefixed per-system ones: renderer choice, core option overrides, button
   bindings) but deliberately leaves RetroAchievements login/session, ROM
   folder grants, saved controller presets, and library state (view mode,
   sort, hidden ROMs, custom titles, play stats) untouched. Confirm dialog
   matches `confirmDeletePreset`'s `AlertDialog` pattern; on confirm, calls
   `recreate()` so every tab immediately reflects the reset defaults (lands
   back on General, a minor known side effect of the full recreate).

**New chokepoint:** every raw `android.util.Log.d/e` call in the app (21
call sites across 9 files) now routes through `TacoBoyLog` instead, which
still forwards to `android.util.Log` unconditionally first — `adb logcat`
during development isn't affected by the in-app filter — and only gates
what reaches the exportable buffer. `TacoBoyPrefs.setLogLevel` pushes the
new level into `TacoBoyLog`'s in-memory cache immediately, so call sites
never need a `Context` to check the current filter.

**Verified on hardware:** cycled Log level through all four values,
confirmed the value persists across navigating away and back. Export Logs
correctly toasted "No logs captured yet" when the buffer was empty (default
Error level barely gets exercised by everyday use) rather than opening the
save picker on nothing. Reset All Settings' confirm dialog matched the
doc's wording; confirming it visibly reset Fast Forward Key to "Not set",
Fast Forward mode to "Off", Show FPS to "Off", and Log level back to
"Error", then landed on the General tab with a "Settings reset" toast —
while Achievements' RetroAchievements login (ChikinNuggit) and live
tracking session survived untouched, confirming the curated scope. `adb
logcat`'s `AndroidRuntime:E`/`*:F` clean throughout.

**This closes out every item in `settingslayoutideas.md`'s Advanced tab
list.** Remaining known-open items from the doc: turbo button binding
(Controller tab, flagged as a bigger feature needing real key-repeat logic)
and masked Web API key display (Achievements tab, minor/low priority).

## 2026-08-16 (System/Advanced swap — core options relocated per the doc)

**Changed, pure relocation, no behavior change:** per
`settingslayoutideas.md`'s "System (Per-system emulation options...)"
section, moved the curated per-system core options UI from the Advanced
tab to System, leaving Advanced as a bare "coming soon" placeholder
again (ready for the doc's dev/power-user tools — log level, export
logs, reset all settings, 16KB alignment note — none of which are built
yet). `SettingsActivity.populateSystemSection` and `populateAdvancedSection`
swapped bodies; internal state/helpers renamed to match
(`advancedSystemTabs`→`systemCoreOptionTabs`, `advancedOptionsList`→
`systemCoreOptionsList`, `advancedOptionsSystem`→`systemCoreOptionsSystem`,
`updateAdvancedTabHighlight`→`updateSystemCoreOptionTabHighlight`,
`renderAdvancedOptionsList`→`renderSystemCoreOptionsList`) rather than
leaving misleadingly-named functions populating the wrong tab.
`settings_advanced_note` string renamed to `settings_system_core_options_note`.
`TacoBoyPrefs.getCoreOptionValue`/`setCoreOptionValue`'s storage keys are
untouched — any previously-set override keeps working identically,
this only moved which tab reads/writes them.

**Verified on hardware:** System tab now shows the real per-system core
options UI (GBA/GB/GBC/SNES/PS1 tabs, Frameskip/Color Correction/etc.
rows) exactly as it looked under Advanced before. Advanced tab now shows
"More settings coming soon." `adb logcat`'s `AndroidRuntime:E`/`*:F`
clean throughout.

## 2026-08-16 (Quick menu: Achievement Tracking toggle)

**Added, item 5 of `settingslayoutideas.md`'s in-game menu list** — a
lightweight per-session pause for live achievement tracking, distinct
from Settings > Achievements' login/logout (which tears down the session
token entirely). New `AchievementsSession.trackingEnabled` flag,
`setTrackingEnabled`/`isTrackingEnabled`, checked in
`onAchievementTriggered` right after the existing dedup check — when
paused, a triggered achievement is silently dropped (no toast, no
`awardAchievement` call). Doesn't touch the native `rc_runtime_t` at all;
it keeps evaluating conditions and firing trigger events exactly as
before, this only gates what the Kotlin side does with them. Documented
consequence: since `rc_runtime_t` never re-fires an achievement it's
already triggered once, one that fires *while* paused is gone for the
rest of that session even after resuming — there's no native "replay."

**Real bug caught before it shipped, not after:** the toggle's visibility
can't just check `achievementsSession != null` — `TacoBoyActivity`
constructs and assigns that field unconditionally on every game load
(see `setupRetroView`), before `start()`'s async activation work (login
check, game recognition, fetching achievements) has resolved one way or
the other. A non-null check alone would show the toggle even when the
user isn't logged into live tracking, or the game wasn't recognized, or
it has no unearned achievements — nothing to actually pause. Added a
second `AchievementsSession.isActive()` flag, set `true` only at the
exact point `loadAchievements()` is called into the native runtime with
a real activation list, and gated the toggle's visibility on that
instead (`updateAchievementTrackingToggle` in `TacoBoyActivity`).

**Verified on hardware:** launched a real GBA game with live tracking
already configured, waited for activation, opened the quick menu — the
toggle correctly appeared ("Achievement Tracking: On") in the right
position (after Exit to Library, before Info). Tapped it: toast "Achievement
tracking paused for this session," menu closed. Reopened the menu:
label correctly read "Off." Toggled back on to leave a clean state.
`adb logcat`'s `AndroidRuntime:E`/`*:F` clean throughout.

**This closes out every item in `settingslayoutideas.md`'s in-game quick
menu section.**

## 2026-08-16 (Quick menu: Fast Forward Toggle + Info dialog)

**Added, items 3 and 6 from `settingslayoutideas.md`'s in-game menu
list** (item 5, Achievement Tracking toggle, not built this slice):

1. **Fast Forward Toggle** — a new row in `quick_menu_panel`
   (`fast_forward_toggle_button`) that flips `fastForwardEngaged`
   directly via the same `setFastForwardEngaged` the physical-key path
   uses. Deliberately independent of the Settings > General mode/key
   config — always available as a touch-only alternative regardless of
   whether a key is bound or what mode is selected, since a menu tap
   doesn't have a natural "hold" semantic the way a physical key does.
   Label reads "Fast Forward: Off"/"On", refreshed each time the quick
   menu opens (`updateFastForwardToggleLabel`, called from
   `toggleQuickMenu` alongside the existing `updateSlotLabels`); tapping
   it closes the menu, same pattern as Reset/Exit to Library.
2. **Info dialog** — a new "Info" row opens an `AlertDialog` showing
   Core, System, ROM name, and session time. Core name uses a small new
   `GameSystem.coreName` extension property (mGBA/Gambatte/Snes9x/
   SwanStation) rather than showing `coreFileName`'s raw `.so` filename —
   kept as a standalone mapping instead of a new `GameSystem` constructor
   field since nothing else needs it yet. Session time is wall-clock
   elapsed since `setupRetroView` ran (`sessionStartTimeMs`, new field),
   formatted `H:MM:SS` past an hour or `M:SS` under one.

**Verified on hardware:** opened the quick menu on a real running game
(Pokémon Emerald, auto-resumed) — both new rows appeared in the right
order (Reset, Fast Forward: Off, Exit to Library, Info). Tapped Fast
Forward Toggle, reopened the menu, confirmed the label flipped to "On"
and stayed there (didn't self-revert, confirming this isn't accidentally
wired through the Hold-mode key path). Tapped Info: dialog showed
exactly "Core: mGBA / System: GBA / ROM: Pokemon - Emerald Version (USA,
Europe).gba / Session time: 1:07" — every field correct against the real
session. Toggled Fast Forward back off afterward to leave a clean state.
`adb logcat`'s `AndroidRuntime:E`/`*:F` clean throughout.

## 2026-08-16 (Achievements diagnostic buttons — Test Connection + Test Achievement)

**Added, items 5/6 from `settingslayoutideas.md`'s Achievements list:**

1. **Test Connection** — pings RA with the currently-saved Web API key
   credentials and toasts success/failure. Pure reuse of already-existing
   plumbing: `RetroAchievementsClient.verifyCredentials`, the exact same
   call the login dialog already uses to validate a key before saving it.
   Only rendered when credentials are actually saved (nothing to test
   otherwise) — `renderTestConnectionRow` is called both at
   `populateAchievementsSection` setup and from the end of
   `renderAchievementsStatus`, so it stays in sync across login/logout
   without a separate call site to remember. Guarded with
   `::testConnectionContainer.isInitialized` since
   `renderAchievementsStatus`'s first call happens before the container
   exists yet.
2. **Test Achievement** — always available regardless of login state
   (it's testing the toast, not RA connectivity). Fires the exact same
   `Toast.makeText(..., R.string.achievement_unlocked_toast, ...)` call
   `AchievementsSession.onAchievementTriggered` uses for a real unlock,
   with fabricated data ("Test Achievement", 5 points) — no new toast
   styling to build or keep in sync with the real one, since it's
   literally the same code path.

New `actionButtonRow` helper (`SettingsActivity.kt`) — a plain full-width
tappable button, distinct from the existing `toggleRow` (on/off) and
cycle-choice row patterns, since this is neither.

**Verified live against the real account** (`ChikinNuggit`): Test
Connection returned "Connected as ChikinNuggit" — a genuine network
round-trip, not a mock. Test Achievement showed "Achievement unlocked:
Test Achievement (+5)" in the identical visual format a real unlock
uses. `adb logcat`'s `AndroidRuntime:E`/`*:F` clean throughout.

## 2026-08-16 (General tab finished — Show FPS, Auto-save relocated, Resume-on-launch toggle)

**Added, the remaining 3 items from `settingslayoutideas.md`'s General
list** (Fast Forward, the 2nd item, shipped earlier the same day):

1. **Show FPS overlay** — a small top-center `TextView` (`fps_overlay` in
   `activity_tacoboy.xml`), only populated/shown when
   `TacoBoyPrefs.isShowFpsEnabled` (default off — a dev/perf tool, not
   for every player). `setupRetroView` collects `getGLRetroEvents()`
   continuously (not the one-shot `.first{}` pattern used elsewhere in
   this method), counting `FrameRendered` events and updating the text
   once a second via a `delay(1000)` loop. Checked once per game load,
   same "takes effect next load" convention as renderer/audio-latency
   options.
2. **Auto-save SRAM relocated** from System to General — user's own
   call ("its a global behaviour rather than per-system"). Same
   `TacoBoyPrefs` key/behavior, purely a UI move; System tab now shows
   the standard "coming soon" placeholder until/unless the doc's
   System/Advanced core-options swap gets actioned.
3. **Resume last game on launch** toggle — `TacoBoyPrefs.isResumeOnLaunchEnabled`
   (default true, preserving the app's original unconditional-auto-resume
   behavior) now gates the auto-load branch in `initRomFlow`. The crash-
   recovery check (detecting a ROM that crashed mid-load last session)
   stays unconditional regardless of this setting — that's a safety
   mechanism, not the resume convenience this toggle controls. When off,
   `lastRomUri` is still remembered and can be resumed manually via the
   Library screen's existing resume button — this toggle only changes
   what happens automatically at cold start.

**Verified on hardware:** all three render correctly in Settings >
General alongside Fast Forward (confirmed via screenshot after an
initial `uiautomator dump` came back stale/truncated — screenshot showed
the real, fully-correct state). Show FPS: enabled it, loaded Baldur's
Gate - Dark Alliance (GBA), screenshot confirmed a live "60 FPS" overlay
in the top corner. Resume-on-launch: turned it off, force-stopped and
cold-started the app, screenshot confirmed it landed on the "Open the
library to select your ROMs" prompt instead of auto-loading the
in-progress game — `last_rom_uri` remained in prefs, confirming manual
resume would still work. `adb logcat`'s `AndroidRuntime:E`/`*:F` clean
throughout. Reset both test toggles back to their defaults (Show FPS
off, Resume-on-launch on) afterward.

**This closes out every item in `settingslayoutideas.md`'s General
section** and gives every one of TacoBoy's 7 Settings tabs real content
for the first time.

## 2026-08-16 (Fast Forward — General tab, Off/Hold/Toggle + bindable key)

**Added:** TacoBoy's General tab was still a placeholder — now hosts a
real Fast Forward feature, the first item from `settingslayoutideas.md`'s
General list. New `FastForwardMode` enum (OFF/HOLD/TOGGLE).
`TacoBoyPrefs.getFastForwardMode`/`getFastForwardKeyCode` store the mode
and a bound raw physical keycode (null = unbound, deliberately not
defaulted to a guessed key like Pocket Taco's R2 — unlike
`ControllerBindings.DEFAULT_SOURCE`, no such default was ever confirmed
against real hardware).

Uses `GLRetroView.frameSpeed` — a libretrodroid field that already existed
(native `LibretroDroid::setFrameSpeed`, multiplies both core steps-per-
frame and audio playback speed) but this app had never called at all
before today. `TacoBoyActivity.handleFastForwardKey`, checked before
`ControllerBindings.resolveTarget` in `onKeyDown`/`onKeyUp`, intercepts
the bound key entirely — it's a frontend action, never forwarded to the
core as a RetroPad button. Hold speeds up only between down/up; Toggle
flips a persistent `fastForwardEngaged` state on down only (guarded by
`event.repeatCount == 0`, since OS key-auto-repeat would otherwise flip
Toggle back and forth every ~30-40ms while held). Binding the same
physical key to both fast-forward and a RetroPad target isn't prevented —
fast-forward silently wins since it's checked first — an accepted edge
case, not something the UI guards against yet.

Settings UI (`SettingsActivity.populateGeneralSection`) mirrors existing
patterns: the mode row cycles like Graphics' renderer choice; the key row
reuses the exact "tap to listen for next physical key" capture flow
already built for Controller tab bindings (`ControllerBindings.describeSource`
for display, `dispatchKeyEvent` extended with its own
`listeningForFastForwardKey` flag, separate from `listeningTarget` since
this isn't a `ControllerBindings.Target`). `section_general` in
`activity_settings.xml` converted from a static placeholder to a
`LinearLayout`, same conversion already done for Audio/Advanced.

**Verified on hardware:** compiled clean, installed, bound a real key via
the Settings UI capture flow (adb synthetic keyevent → captured →
persisted → displayed as "R2" via `describeSource`). Added a temporary
`Log.i` in `setFastForwardEngaged` (removed after) to distinguish the two
modes precisely: **Toggle**, one tap → exactly one log line
(`engaged=true frameSpeed=2`), confirming the key-up is correctly ignored
and fast-forward stays engaged; a second tap → `engaged=false frameSpeed=1`.
**Hold**, one tap → two log lines from the single down+up
(`engaged=true`→`engaged=false`), confirming it only speeds up while
physically held, unlike Toggle. `adb logcat`'s `AndroidRuntime:E`/`*:F`
clean across the whole session, no crashes launching/playing a real ROM
with fast-forward actively engaging mid-session. Reset the test binding
back to Off/unbound afterward so the device doesn't carry a guessed key
assignment the user didn't choose themselves.

**Not built:** a configurable speed multiplier (hardcoded 2x, matching
RetroArch's common default, not independently tuned) and the in-game
quick-menu "Fast Forward Toggle" item from the same doc — this slice was
scoped to the Settings-side mode+binding only.

## 2026-08-16 (Hardcore Mode toggle + PS1 BIOS region flag)

**Added, from the user's `settingslayoutideas.md` reorg doc:**

1. **Hardcore Mode** (Settings > Achievements) — a toggle that hides the
   quick menu's four save-state slots (`save_slots_container` in
   `activity_tacoboy.xml`), replacing them with an explanatory note, while
   leaving `SramManager` (auto-save and manual cart-battery saves)
   completely untouched. Scoped deliberately narrow after the user
   clarified: RA's Hardcore rules forbid save-states specifically (an
   emulator-only convenience), not SRAM (every real cartridge/memory card
   already has one). `TacoBoyPrefs.isHardcoreModeEnabled` gates
   `TacoBoyActivity.toggleQuickMenu`. Explicitly **not** built: this
   doesn't yet change how achievement unlocks are reported to RA — real
   Hardcore-mode submission needs a login-time `h=1` flag distinguishing
   hardcore/softcore score, which this UI-only toggle doesn't touch. Said
   so directly in the settings note so it isn't assumed done.

2. **PS1 BIOS region flag** (RomLibraryActivity's PS1 tab) — new
   `BiosRegion.kt` maps SCPH model numbers to a region + flag emoji, from
   the "[SIMPLE]" section of `BIOSregion.md`. `BiosManager.listDetectedBios`
   scans `filesDir` directly for scph-pattern filenames rather than
   keeping a persisted import log — deliberately, so it can't drift from
   reality and correctly picks up BIOS files that predate this feature.
   New `bios_status_row` in `activity_rom_library.xml` shows the result,
   refreshed on system-tab switch and after each import. Purely a status
   display — SwanStation already auto-detects valid BIOS files by content
   regardless of filename, so this changes nothing about whether PS1
   actually works.

**Verified on hardware:** Hardcore Mode toggled on, quick menu opened —
slots hidden, note shown, Reset/Exit to Library still worked, no crash.
Toggled off, slots reappeared. BIOS flag: the user's real, already-working
PS1 BIOS file (`PSX - SCPH7003.BIN`) was detected and flagged 🇬🇧 on the
very first load, with zero new configuration — confirms the
scan-don't-track design correctly picked up a file that predates the
feature. `AndroidRuntime:E`/`*:F` clean across the whole session.

**Note for later:** `BIOSregion.md`'s "[SIMPLE]" and "[DETAILED]" sections
disagree on SCPH-7003's region (SIMPLE groups it under UK; DETAILED calls
it Southeast Asian) — `BiosRegion.kt` followed SIMPLE as instructed. Worth
flagging to the user if it ever comes up; not resolved here since the two
sources of their own doc conflict and neither was called authoritative.

## 2026-08-15 (Settings: real Advanced tab — curated per-system core options)

**Added:** the Advanced tab in Settings (previously a "coming soon"
placeholder, same as General still is) now exposes a hand-picked subset
of each core's real libretro core options, applied via the same
`GLRetroViewData.variables`/`getVariables()` mechanism the PS1 renderer
choice already used — see this same date's "investigation" entry below
for the full catalog this was chosen from and the reasoning for keeping
the pick small.

New `CoreOptions.kt` defines `Option(key, label, choices, default)` and a
`forSystem: Map<GameSystem, List<Option>>` with 5 options for GBA (mGBA),
5 shared between GB/GBC (Gambatte), 6 for SNES (Snes9x), and 7 for PS1
(SwanStation) — all deliberately picked with short (2-7 item) choice
lists that fit a tap-to-cycle row, unlike the long lists (palettes, CPU
overclock percentages, resolution scale) the investigation also found.
`TacoBoyPrefs.getCoreOptionValue`/`setCoreOptionValue`/`clearCoreOptionValue`
store per-system, per-key overrides (null = use the option's own
default), same nullable-override shape as `getRendererChoice`.
`TacoBoyActivity.setupRetroView`'s `variables` assignment now builds a
combined list (the existing renderer variable, if any, plus every
`CoreOptions.forSystem[system]` entry) instead of just the one renderer
`Variable` — a small, low-risk change since it's additive to already-
working code, not a rewrite of it.

Settings UI (`SettingsActivity.populateAdvancedSection`) mirrors the
Controller tab's system-tabs-then-rows pattern (its own state, not
shared with Controller's) — tap a system tab, see that system's options,
tap a value to cycle choices (same interaction as the Graphics tab's
renderer row), reset icon appears once customized. `section_advanced` in
`activity_settings.xml` converted from a static placeholder `TextView` to
a `LinearLayout`, same conversion already done for `section_audio`.

**Verified:** `:app:compileDebugKotlin`/`:app:assembleDebug` pass.
Installed on hardware, navigated to Settings > Advanced via
`uiautomator`-derived taps (the tab row needed a horizontal swipe first —
Achievements/Advanced sit off-screen at this device's tab-row width).
Confirmed GBA's 5 options render with correct labels/values, cycled
"Frameskip" from `disabled` to `auto`, confirmed the reset icon appeared
and `shared_prefs/tacoboy_prefs.xml` recorded
`core_option_GBA_mgba_frameskip=auto` via `run-as cat`. Switched to the
PS1 tab and confirmed all 7 of its options render correctly too. Launched
a real GBA ROM with the customized frameskip value active — `adb logcat
-s libretrodroid` showed a clean init (audio stream, GL context, "Starting
game") with no errors, confirming the merged multi-`Variable` array
reaches the core without breaking anything. Reset the test override back
to default afterward via the UI, confirmed no `core_option_*` keys remain
in prefs. `AndroidRuntime:E`/`*:F` clean across the whole session.

## 2026-08-15 (investigation: what core options do our 4 cores actually expose)

**Research only, no shipped feature.** User asked whether mgba/gambatte/
snes9x/swanstation have RetroArch-style core options TacoBoy could surface
in the still-empty General/Advanced tabs. Checked the mechanism first:
`environment.cpp`'s `handle_callback_environment` already handles
`RETRO_ENVIRONMENT_SET_VARIABLES`/`GET_VARIABLE` (the classic/legacy
libretro core-options API — not the newer `SET_CORE_OPTIONS`/`_V2`, which
aren't handled and would silently no-op for a core that only registers
those), and `GLRetroView.getVariables(): Array<Variable>` already exposes
the result to Kotlin — this is the exact plumbing `GameSystem.rendererOptionKey`
already uses for PS1's Software/OpenGL choice, just never used for anything
else. No native/NDK changes needed to read more of it.

Cores are precompiled `.so` blobs with no bundled source/`.info` files, so
the only way to find out what each one really registers is to load it and
read `getVariables()` live — added a temporary `Log.i` probe in
`TacoBoyActivity.setupRetroView` (removed again after this investigation),
force-stopped the app between each system to avoid a real gotcha this
surfaced (see below), and launched one real ROM per system.

**Results — every core registers substantially more than the one PS1
renderer key already used:**
- **mGBA (GBA): 17 options.** Frameskip (mode/interval/threshold), audio
  low-pass filter + level, color correction, GB palette (huge list) +
  hardware preset, interframe blending, GB model override, idle-loop
  removal, SGB borders, skip-BIOS, solar sensor level, use-BIOS toggle,
  allow-opposing-directions.
- **Gambatte (GB/GBC): 28 options.** GB colorization/hwmode/internal
  palette (huge list incl. two 100-entry "TWB64" community palette packs),
  color correction + mode + frontlight position, interframe blending, dark
  filter level, rumble strength, turbo period, official-bootloader toggle,
  opposing-directions, plus a full GB Link cable network-play sub-block
  (mode/port/12-part IP address fields — not relevant to a single-player
  handheld).
- **Snes9x (SNES): ~39 options.** Aspect ratio, audio interpolation
  (gaussian/cubic/sinc/none/linear), Blargg NTSC filter, hi-res mode +
  blending, transparency, graphic-clip windows, VRAM access blocking,
  8 individual sound-channel toggles, 5 individual layer toggles,
  SuperFX overclocking, general overclock/"reduce slowdown" hack, overscan
  crop, region, reduce-sprite-flicker hack, randomize-memory, plus
  Justifier/Super Scope/M.A.C.S. Rifle light-gun color+crosshair settings
  (not relevant — no light gun on Pocket Taco).
- **SwanStation (PS1): ~106 options**, by far the largest — CPU (execution
  mode, overclock %, recompiler fastmem/block-linking/ICache), Display
  (aspect ratio, crop mode, active/line start-end offsets, force-4:3),
  GPU (renderer — already used, resolution scale, texture filter, MSAA,
  true color, scaled dithering, widescreen hack, full PGXP geometry-
  correction sub-suite: CPU mode/culling/depth-buffer/tolerance/texture-
  correction/vertex-cache), CD-ROM (read/seek speedup, async readahead,
  preload-to-RAM, pre-cache CHD, region check, mute), memory cards (type,
  playlist-card-sharing), runahead frame count, texture-replacement
  toggles, plus a full per-port block (×8 controller ports!) of
  analog-mode/deadzone/vibration/axis-scale settings that only matter for
  multitap setups, not this app's single-controller Pocket Taco case.

**Real gotcha found during the investigation itself, worth remembering for
any future core-options work:** `Environment`'s `variables` map
(`environment.cpp`) is **never cleared between core loads within the same
app process** — `deinitialize()` clears rumble/memory-map/geometry state
but not `variables`. Loading GBA then GB without a force-stop in between
showed GB reporting 45 "variables" (28 real gambatte ones + 17 stale
leftover `mgba_*` keys from the previous core, still sitting in the map).
Harmless for *lookup* correctness (`GET_VARIABLE` is keyed by exact string,
and `mgba_*` keys never collide with `gambatte_*`/`snes9x_*`/`swanstation_*`
ones), but a live "show me this session's core options" UI built directly
on `getVariables()` would need to filter by the current core's known key
prefix, not trust the raw list wholesale — confirmed by force-stopping
between each of the 4 captures above to get clean per-core reads.

**Recommendation, not yet actioned:** most of what's listed above is noise
for this app's actual use case (light-gun colors, 8-port multitap configs,
GB Link network play, individual sound-channel mutes) — a useful "core
options" UI would hand-pick maybe 5-10 options per system that plausibly
matter for a Pocket Taco/handheld context (e.g. GBA/GB frameskip and color
correction for Audio/Graphics tabs; SNES overclock and audio interpolation;
PS1 CPU overclock, resolution scale, PGXP, CD-ROM read speedup for
Advanced), not expose the full raw list RetroArch-style. See this entry if
that work is picked up later — the option catalog above is already
verified, no need to re-probe unless a core binary itself changes.

## 2026-08-15 (Settings: real Audio tab — low-latency audio toggle)

**Added:** the Audio tab in Settings was a static "More settings coming
soon" placeholder (like General and Advanced still are) — it's now a real
section. `GLRetroViewData.preferLowLatencyAudio` (a libretrodroid field
that already existed, defaulting to `true`) was hardcoded `true` in
`TacoBoyActivity.setupRetroView` and never exposed as a choice; it's now
read from `TacoBoyPrefs.isLowLatencyAudioEnabled` (default `true`, so
existing behavior is unchanged unless a user turns it off). Toggling it
off switches libretrodroid's native `audio.cpp` from its low-latency Oboe
path (`LOW_LATENCY_SETTINGS`: 4 video frames of buffer,
`PerformanceMode::LowLatency`) to the default path (`DEFAULT_LATENCY_SETTINGS`:
8 frames) — an escape hatch for the rare device/core pairing where the
smaller buffer underruns audibly.

**Deliberately not built:** roadmap.md's "Audio options (latency,
resampler)" line also names a resampler choice — checked
libretrodroid's `audio.cpp`/`audio.h` and there's exactly one resampler
implementation (`LinearResampler`), not a selectable set, so unlike
latency there's no real axis to expose there. Only the toggle that
corresponds to something actually configurable got built.

**Verified:** `:app:compileDebugKotlin`/`:app:assembleDebug` pass.
Installed on hardware, navigated to Settings > Audio via
`uiautomator`-derived taps, confirmed the toggle renders and persists
(`shared_prefs/tacoboy_prefs.xml` → `low_latency_audio`). Turned it off
and launched a real ROM (Pokémon Emerald) — this exercises `audio.cpp`'s
`DEFAULT_LATENCY_SETTINGS` branch, which this app had never actually run
before (always hardcoded to the low-latency branch). `adb logcat -s
libretrodroid` confirmed the native log itself: `Using low latency
stream: 0` / `Average audio latency set to: 66.970825 ms` (vs. the
low-latency branch's much smaller figure), game launched and ran with no
errors, `AndroidRuntime:E`/`*:F` clean across the whole session. Restored
the toggle to its default "On" afterward.

## 2026-08-15 (controller presets: default-on-Pocket-Taco-connect)

**Added:** the last missing piece of the controller-presets feature (see the
2026-08-14 "named, saveable controller presets" entry below) — a saved preset
can now be starred as the one to auto-apply, to every system, whenever a
Pocket Taco connects. Previously connecting only ever offered a binary
choice: reset every system back to raw `DEFAULT_SOURCE` identity bindings
(auto-bind on), or do nothing at all (auto-bind off) — there was no way to
say "always apply my custom layout on connect."

`TacoBoyPrefs.getDefaultControllerPresetName`/`setDefaultControllerPresetName`
store the chosen preset's name (nullable — no default set is the existing
behavior, unchanged). `TacoBoyActivity.inputDeviceListener` now checks this
before falling back to `ControllerBindings.resetAllToDefaults`: if a default
preset is set and still exists, applies it via `ControllerPresets.applyToSystem`
across every `GameSystem` (not scoped to one, same reasoning as the existing
reset-to-defaults path — a stale customization could exist for any system) and
toasts the preset's name instead of the generic "Controls configured" message.
`ControllerPresets.delete` now also clears the default pointer if the deleted
preset was the one marked default, so it can't dangle.

Settings > Controller's preset chips (`SettingsActivity.renderPresetsRow`) each
got a second small tappable star (★ default / ☆ not) next to the existing
tap-to-apply chip — a separate control, not a repurposed long-press, since
long-press already means delete. Updated `controller_auto_bind_note` to
mention the starred-preset behavior instead of the stale "or applied preset"
wording that predated this feature actually existing.

**Verified:** `:app:compileDebugKotlin` and `:app:assembleDebug` both pass.
Installed on the real device (`adb install -r`), navigated to Settings >
Controller via `uiautomator dump`-derived tap coordinates (not
screenshot-pixel guessing — see the PS1 session's note on why), starred the
user's real saved "PocketTacoGBA" preset, confirmed the star fills in (☆ → ★)
and `shared_prefs/tacoboy_prefs.xml` records
`default_controller_preset=PocketTacoGBA` via `run-as cat`. `adb logcat`
clean throughout, no crashes. **Not yet verified:** an actual Pocket Taco
disconnect/reconnect triggering `applyToSystem` from this new code path —
that needs the physical controller, not just adb; the connect-time branch
itself is a small, direct change to logic that was already hardware-confirmed
for the reset-to-defaults case.

## 2026-08-15 (RetroAchievements: fixed save-state loading silently killing live tracking)

**Fixed:** loading any save-state slot mid-session was silently stopping achievement
tracking for the rest of that session — not just losing in-progress hit-count progress
(the known, documented tradeoff), but fully deactivating every achievement with no code
path that ever re-activated them. Found via user review of the just-shipped live-tracking
feature (three targeted questions about encryption, save-state interaction, and
award-failure handling — this was the save-state one) rather than by testing, since the
failure is invisible: no toast, no log, no crash, tracking just quietly stops.

**Root cause:** `LibretroDroid::unserializeState` called `Achievements::reset()` after a
successful load, and `reset()` does a full `rc_runtime_destroy`+`rc_runtime_init` —
deactivating every achievement, not just clearing their progress. Nothing calls back into
`AchievementsSession` (the only place that re-activates achievements) after a state load,
so the runtime stayed empty for the rest of the session.

**Fix:** `rc_runtime_t` has a purpose-built API for exactly this — `rc_runtime_reset`
(`Achievements::resetProgress()`, new, distinct from the existing `reset()`) iterates every
*currently-active* trigger and resets its internal hit-count/delta state without
deactivating it. Swapped `unserializeState`'s call from `reset()` to `resetProgress()`. No
Kotlin changes needed at all — the fix is entirely in which one native function gets
called. Achievements now correctly keep tracking after a state load; in-progress hit-count
progress toward multi-step achievements is still lost on the load (RA's own client instead
serializes runtime progress alongside the save state to avoid even that, which this app
doesn't do), a real but much smaller and already-documented tradeoff.

**Still open, raised in the same review, not yet addressed:** the RA session token is
stored in plain (unencrypted) `SharedPreferences`, same as everything else in
`TacoBoyPrefs` — readable in plaintext with device/root access; and `r=awardachievement`
failures (network error, timeout, transient server error) are silently swallowed —
`AchievementsSession.onAchievementTriggered` discards the call's return value entirely, no
retry, no queue, no error surfaced, and the local "unlocked" toast fires regardless of
whether the server actually recorded it.

## 2026-08-15 (RetroAchievements: live in-game achievement tracking — real-time unlocking, hardware-confirmed)

**Added:** the actual core RetroAchievements feature — achievements now unlock live during
gameplay and get reported to RA's servers, not just identified/listed. Previously the app
only did one-shot game identification (hash-based, all 5 systems) and read-only achievement
list viewing; nothing watched memory or fired unlocks. This closes that gap.

**Architecture (confirmed with the user before building):** vendor RetroAchievements' own
`rcheevos` C library's lower-level `rc_runtime_t` API (pure local condition evaluation, no
networking) into the native build, rather than reimplementing the achievement
condition-evaluation engine in Kotlin the way PS1 hashing was. Unlike the PS1 hash
algorithm (small, bounded, safely re-derivable), `rc_runtime_t`'s condition language
(memory-size variants, deltas, hit counts, multi-group logic) is a large, actively-evolving
surface — reimplementing it risks silent misfires that are effectively unauditable. Found
the whole `rcheevos` tree already sitting vendored, unbuilt, at
`libretrodroid/src/main/cpp/libretro/rcheevos/` — wired the minimal `rc_runtime_t`-only
subset into `CMakeLists.txt` (14 files, verified by reading every `#include`, not guessed;
confirmed via a trial build that it links clean before writing any application code on
top). All actual RA networking stays in Kotlin (`RetroAchievementsClient.kt`), matching
every other call in that file — native only evaluates conditions and reports trigger
events up.

**Native layer:** `achievements.h/.cpp` (new) wraps `rc_runtime_t` — `loadAchievements`
activates each fetched achievement's `MemAddr` definition, `doFrame` (hooked into
`LibretroDroid::step()` right after the `retro_run()` loop, same `coreLock` scope) calls
`rc_runtime_do_frame` once per rendered frame and collects trigger events. New JNI exports
(`loadAchievements`/`resetAchievements`) follow the existing try/catch/`LOGE` pattern; a new
native→Kotlin up-call (`sendAchievementTriggeredEvent`) reuses the exact
`env->CallVoidMethod`-on-`glRetroView` pattern already used for rumble events, surfaced via
a new dedicated `GLRetroView.getAchievementTriggeredEvents(): Flow<Int>` (deliberately *not*
folded into the existing replay-cached `GLRetroEvents` — a discrete unlock stream shouldn't
replay a stale trigger to a late subscriber, so it mirrors `RumbleEvent`'s separate
no-replay `MutableSharedFlow` instead).

**Two live-API corrections, found only by testing against the real server, not by reading
docs/source alone** — this project's now well-established pattern of live-verifying
Connect API behavior paid off twice more here:

1. **`r=patch` doesn't work — the real endpoint is `r=achievementsets`.** The first
   implementation of `RetroAchievementsClient.getAchievementDefinitions` matched
   `rc_api_init_fetch_game_data_request` (`r=patch`) in vendored rcheevos'
   `rapi/rc_api_runtime.c` — read directly from source, not paraphrased, same as every
   other call in this file. It still 401'd live with credentials that work fine for every
   other call. Reading `rc_client.c` (the actual current high-level client, not just the
   lower-level rapi helpers it wraps) showed it never calls `fetch_game_data`/`r=patch` at
   all — only `rc_api_init_fetch_game_sets_request`, i.e. `r=achievementsets`, an evolved
   replacement with a different response shape (`Sets[]`, each with its own `Type`
   `core`/`bonus`/`specialty`/`exclusive` and `Achievements[]`, not the old flat
   `PatchData.Achievements[]`).
2. **The permanent Web API key doesn't work for `r=achievementsets`/`r=awardachievement`
   either — RA needs a real login session token for those specifically.** Confirmed live:
   `r=gameid` accepts the permanent key fine; `r=achievementsets`, `r=ping`,
   `r=postactivity`, and `r=awardachievement` all 401 "Invalid user/token combination" with
   the exact same account and key. This matches `rc_client.c`'s `client->user.token` coming
   from a real `r=login2` password exchange, not the permanent key — a genuinely different
   auth tier this app never previously needed (identification + read-only list viewing only
   ever needed the key). Added a *second*, separate login: Settings > Achievements now has
   a "Live Tracking" section (username + real account password, POST via `r=login2` per
   `rc_api_init_login_request_hosted`) that exchanges the password once for a session token
   — the password itself is never stored, only the returned token
   (`TacoBoyPrefs.setRetroAchievementsSession`), matching the pattern rcheevos' own docs
   describe (`rc_client_begin_login_with_password` once, cache the token, use
   `rc_client_begin_login_with_token` thereafter) specifically so the password never has to
   be re-entered or persisted. The `r=awardachievement` request-signature algorithm
   (`v=MD5(achievementId+username+hardcoreFlag)`) was read directly from
   `rc_api_init_award_achievement_request_hosted`, not guessed, and is covered by a JVM
   unit test cross-checked against an independently-computed Python MD5.

**A real, hardware-only-visible bug, caught by the user mid-test, not by any of the above:**
first live test (Pokémon Emerald, softcore, a completely fresh save — confirmed via
`play_count=1` in prefs) produced a burst of 61 simultaneous "unlocks" seconds after
tracking started, spanning the entire game from the first gym badge through post-Elite-Four
Battle Frontier gold symbols that take 100+ hours to legitimately reach. Initial assumption
that this was legitimate retroactive crediting from prior save progress was wrong — the
user pushed back with specifics (just won the first battle, got the starter) that made it
obviously impossible, and was right to. Root-caused with targeted native logging (`LOGD` is
a compiled-out no-op in this build's `log.h`, `LOGI` isn't — cost one wasted rebuild cycle):
`retro_get_memory_data(RETRO_MEMORY_SYSTEM_RAM)` for mGBA reports exactly 256KB — EWRAM
only. RA's GBA achievement conditions also reference IWRAM, conventionally mapped
immediately after EWRAM in a unified address space; reads there were silently returning 0
(out-of-bounds in the naive single-buffer implementation), and enough achievement
conditions structurally happened to be satisfied by a constant 0 to produce the mass
false-positive burst.

**Fix:** capture the core's real memory map. Added `RETRO_ENVIRONMENT_SET_MEMORY_MAPS`
handling to `Environment` (previously silently unhandled, fell into the `default:` case —
mGBA does call this, since RetroArch's own achievement support depends on it existing).
`achievementsmemory.h/.cpp` (new) is a scoped, direct port of just the memory-region
resolution logic from vendored rcheevos' `rc_libretro.c`
(`rc_libretro_memory_init`/`_read` and their static helpers) — deliberately a port rather
than compiling that file directly, since it also pulls in the *entire* ROM/disc hashing
dispatch table (`rhash/hash.c` alone references every per-console hash function across
dozens of consoles) for functionality this app doesn't need, PS1 hashing already being
independently implemented in Kotlin (`Ps1Hasher.kt`) — the exact same "port the specific
algorithm, don't vendor the whole library for one small piece" call already made for that
work. `GameSystem.raConsoleId` (added earlier, previously unused) turned out to be
load-bearing after all — `rc_console_memory_regions(consoleId)` needs it to know a given
console's region layout.

**Verified end-to-end on real hardware, twice — once catching the bug, once confirming the
fix:** first pass (Alien Trilogy identification smoke-check, then Pokémon Emerald live
test) surfaced the IWRAM bug via the IDE user's real-account unlock burst, independently
confirmed against RA's own server (`API_GetGameInfoAndUserProgress.php` showed the same 61
achievements, real timestamps). After the fix, the user reset progress on RA and started a
fresh save: confirmed only the single legitimate achievement unlocked. Full JVM test suite
(new: `RetroAchievementsClientTest` covering the `r=achievementsets` response parser and
the `r=awardachievement` signature algorithm against an independent Python MD5;
`GameSystemTest` covering `raConsoleId` against `rc_consoles.h`) passes throughout.

**Scope, deliberately:** softcore only — hardcore mode requires disabling save-states
during tracking, which conflicts with this app's existing, valued save-state feature, so
it's out of scope for this pass. Always-on whenever both logins are set up (no separate
settings toggle), confirmed with the user up front.

## 2026-08-15 (RetroAchievements PS1 support: finished, hardware-confirmed — step 3, final)

**Added:** the remaining pieces of PS1 RetroAchievements identification, completing the
work started in the two entries below. `ChdCdCodec.kt` decompresses a hunk under its
`cdlz`/`cdzl`/`cdzs` codec slot — ported the frame-splitting algorithm from libchdr's real
`cdlz_codec_decompress` source (an ECC bitmap + compressed-length header, then base-codec
sector data), deliberately skipping ECC/sync regeneration and subcode decoding once real
device bytes confirmed neither is needed to reach the 2048-byte user data PS1
identification actually reads. `ChdDisc.kt` ties header + hunk map + codec into
"read cooked sector N" with a 1-hunk cache. `Iso9660.kt` reads the PVD/root directory and
resolves `SYSTEM.CNF`'s `BOOT=` line, including subdirectory paths. `Ps1Hasher.kt`
orchestrates the chain into RA's exact hash (MD5 of boot-exe name + content). Wired into
`RomHasher.raHash` for PS1 via a real seekable `FileChannel` (SAF's normal stream is
sequential-only); removed the PS1-unsupported toast from `RomLibraryActivity`.

**Verified the same way as the header/hunk-map work — real device bytes first, Python
reference before Kotlin, then a real JUnit test:** pulled real compressed hunks for both
codecs actually present in the user's library (LZMA/`cdlz` from Crash Bandicoot,
Zstandard/`cdzs` from Alien Trilogy) via targeted `adb shell dd` byte ranges. An
independent Python port of the whole chain (bit reader, Huffman decoder, hunk-map RLE, raw
LZMA1 via `lzma.LZMADecompressor(FORMAT_RAW)`, real `zstandard` library) walked Crash
Bandicoot's actual bytes end to end: PVD at sector 16 (`PLAYSTATION`/`CD001` at the exact
predicted offset), root dir at LBA 22, `SYSTEM.CNF` at LBA 23
(`BOOT = cdrom:\SCUS_949.00;1`), boot exe at LBA 112018 with a valid `PS-X EXE` header and
declared size 288768 — plus 2048 equals 290816, matching the ISO9660 directory's own
file-length field for that entry exactly, a cross-check independent of anything else in
the chain. The resulting hash (`35386fd0891e594c9b6d8a4a5baa0027`) is reproduced exactly by
`Ps1HasherTest`, which replays ~106KB of the same real captured hunk bytes through the
actual Kotlin code. `ChdCdCodecTest` separately covers the `cdzs` path. Full suite: 10
tests, all passing.

**Real bug, only visible on hardware — the JVM test suite's green run was not enough
proof.** `io.airlift:aircompressor` (chosen earlier for being pure Java, avoiding
`2.0+`'s `java.lang.foreign` which needs Java 22+) crashed on the real device with
`NoSuchFieldError: No field ARRAY_BYTE_BASE_OFFSET of type I in class Lsun/misc/Unsafe` —
Android's ART `Unsafe` shim is missing fields desktop OpenJDK's has, which aircompressor
assumes exist. Gradle's `testDebugUnitTest` runs on a real desktop JVM and can't see this
gap. Swapped to `com.github.luben:zstd-jni` (real native zstd, ships an actual Android
`.aar` with prebuilt `.so` per ABI); confirmed all 4 ABI `.so` files land in the built
APK. See memory's `feedback_android_sun_misc_unsafe_libraries` for the general lesson.

**Confirmed on real hardware, both codec paths:** installed the APK, used `uiautomator
dump` for exact tap coordinates (screenshot-pixel-math guessing misfired twice — once
accidentally launching a game instead of the long-press menu). "Check RetroAchievements"
on Alien Trilogy (`cdzs`) opened the real achievement list (0/89, 840 points, real
names/descriptions/badge art) — this run is what caught the aircompressor crash. After
the zstd-jni fix: Alien Trilogy succeeds, and CTR - Crash Team Racing (`cdlz`, 0/117, 990
points) also succeeds — zero crash-log lines either time. RA support (login, achievement
list, hashing) is now complete and hardware-confirmed for all 5 supported systems.

## 2026-08-15 (RetroAchievements PS1 support: CHD v5 hunk map decoder — step 2 of several)

**Added, continuing from the header parser below:** the hardest single piece
of CHD support — locating a hunk's actual compressed bytes. The header
only gives a `mapOffset`; the map itself turned out to be Huffman+RLE
coded with self/parent hunk references, not a flat table (flagged as an
open risk in the previous entry). Read the real algorithm from
`decompress_v5_map` in `libchdr/src/libchdr_chd.c` plus its Huffman
helper (`huffman.c`) and bitstream reader (`bitstream.c`) — same
clone-and-read-source approach as the header, not doc summaries.

- `ChdBitReader.kt` (new) — MSB-first bit reader with a 32-bit
  shift-register buffer, matching `bitstream.c`'s `peek`/`remove`/`read`
  exactly.
- `ChdHuffmanDecoder.kt` (new) — canonical Huffman decoder, RLE-tree
  import only (`huffman_import_tree_rle` — the only tree-import mode the
  hunk map uses; `huffman_import_tree_huffman`, used elsewhere in CHD
  for metadata, wasn't needed and wasn't ported). Decoder-only: this app
  never builds a Huffman tree from data, only imports one that's already
  in the file.
- `ChdHunkMap.kt` (new) — the actual map decode: first pass decodes one
  compression-type value per hunk via the Huffman decoder (with RLE
  runs — note the RLE repeat-count fields are themselves further
  Huffman-decoded values, not raw bits, easy to get wrong reading the
  source too quickly); second pass reads per-hunk length/offset/CRC
  fields at variable bit-widths (`lengthbits`/`selfbits`/`parentbits`,
  themselves stored in the map header) using the compression type
  already known from pass one. Resolves the "pseudo-types"
  (`SELF_0`/`SELF_1`/`PARENT_SELF`/`PARENT_0`/`PARENT_1` — shorthand
  encodings for common self/parent-reference patterns that don't consume
  extra bitstream bits) down to plain `SELF`/`PARENT` entries so callers
  never see them. Reconstructs the same 12-byte-per-hunk record layout
  libchdr uses internally and checks its CRC16 against the map header's
  own embedded checksum before returning anything — returns `null` on
  mismatch rather than handing back a map that might be subtly wrong,
  the same integrity guard libchdr itself applies.
- `ChdRandomAccess` (in `ChdHunkMap.kt`) — a one-method reader interface
  (`read(offset, length): ByteArray`) rather than assuming `InputStream`.
  SAF only gives sequential streams; real usage will need actual seeking
  (a hunk's compressed data can sit anywhere in the file, no ordering
  guarantee to rely on) via a `FileChannel` over a `ParcelFileDescriptor`
  — not built yet, this interface just keeps the map decoder testable
  now without depending on that.

**Verified — decisively, not just plausibly:** wrote an independent
Python reference implementation of the same algorithm first, ran it
against the real hunk map pulled from the same `Crash Bandicoot (USA).chd`
file `ChdHeaderTest` uses (130398 compressed bytes at the file's real
`mapOffset`, pulled via a single batched `adb shell` call). **The
reconstructed map's CRC16 matched the file's embedded checksum exactly**
— computed over 403,116 reconstructed bytes, so a match all but rules
out a bug anywhere in the bitstream reader, RLE decoding, or canonical
Huffman code assignment. Ported the Python reference to Kotlin only
after that match, then added `ChdHunkMapTest.kt` against the same real
bytes (saved as a binary test resource,
`app/src/test/resources/chd_test_data/crash_bandicoot_hunk_map.bin` —
too large at 130KB for a Kotlin string constant), re-confirming the same
CRC16 check passes from the Kotlin side too, plus exact first/last-entry
assertions against the Python-computed values. `:app:testDebugUnitTest`
passes both this and the header test.

### Open questions / discrepancies to resolve
- `COMPRESSION_PARENT` is implemented (correctly consumes its bits) but
  not meaningfully exercised — this session's whole library has no
  parent CHDs (`parentbits=0` on the one file inspected), so that path
  is unverified beyond "doesn't crash and reads the right bit width."
  Not expected to matter: every ROM this app loads is a standalone file.
- No `ChdRandomAccess` implementation backed by a real seekable file
  exists yet — next piece (per-hunk decompression) will need one to read
  actual hunk bytes rather than test fixtures.
- Per-hunk `crc` (the 16-bit value read alongside length/offset) is
  captured and folded into the whole-map CRC16 check but not exposed or
  separately verified per-hunk — matches libchdr's default build
  (`VERIFY_BLOCK_CRC` is off there too), so not a gap relative to the
  reference implementation, just noting it wasn't added as extra
  robustness beyond what upstream does either.

## 2026-08-15 (RetroAchievements PS1 support: research + CHD v5 header parser — step 1 of several)

**Added, per user request:** the first concrete piece of PS1 identification
support, after re-scoping the feasibility of the whole feature (see below).
Not PS1 identification itself yet — that needs several more pieces (hunk
map decoding, per-hunk decompression, ISO9660/SYSTEM.CNF parsing, hash
construction) — just the container header, done properly and verified
before building on top of it.

**Feasibility was re-derived this session, correcting the earlier
"needs native libchdr" verdict** (see the RA login/identification entry
above). Cloned `RetroAchievements/rcheevos` and `rtissera/libchdr`
directly and read the actual source rather than trusting doc summaries
(WebFetch's paraphrasing had already been caught wrong once this session
on the achievement-list field names — same risk here, so went straight
to source this time):

- `rcheevos` has **zero CHD support itself** — CD reading is a pluggable
  callback struct (`rc_hash_cdreader_t`) the host app supplies; RetroArch
  pairs it with `libchdr` separately. Nothing requires vendoring
  `libchdr` specifically, just something that can hand back raw sector
  bytes.
- The exact PS1 hash algorithm, from `rc_hash_psx()` /
  `rc_hash_find_playstation_executable()` in `rcheevos/src/rhash/hash_disc.c`:
  open track 1, read the PVD at sector 16, find the root directory, scan
  for `SYSTEM.CNF` (ISO9660 directory records: 1-byte length, filename at
  +33), parse its `BOOT=`/`BOOT2=` line (strip `cdrom:` prefix and
  leading backslashes, capture up to whitespace/`;` — this is where the
  disc-version suffix `;1` gets dropped), look up that executable the
  same way, verify its `PS-X EXE` header (only 7 of the 8 magic bytes
  are actually checked — a quirk to match exactly, not "fix"), read its
  declared size from header bytes 28-31 plus 2048 for the header sector
  itself. **Hash = MD5(exe_name_bytes + executable_content_bytes)**,
  name first, no separator, content read in 2048-byte cooked sectors.
  Fallback to a literal `PSX.EXE` in the root if no `BOOT=` line
  resolves.
- Checked the user's real PS1 library (209 files,
  `/storage/emulated/0/Emulation/PS1/` on-device) against the exact CHD
  v5 header format read from `libchdr/src/libchdr_chd.c`'s
  `header_read()` — via a header-only script over a single batched
  `adb shell` call (`dd`+`base64`, no full-file pulls): **all 209 are
  valid CHD v5, 170 use `cdzs` (Zstandard), 39 use `cdlz` (LZMA), zero
  use `cdzl` (plain zlib)**. (Briefly thought these were BIN/CUE because
  CHDroid's UI mislabels a CHD's internal track format as "BIN" —
  confirmed genuinely `.chd` on disk.)
- Read the CD-frontend codec's actual sub-structure
  (`cd_codec_decompress` in `libchdr_cdrom.c`, `cdzl`/`cdlz`/`cdzs` all
  share it): each hunk holds a small header (a per-frame ECC-reconstruction
  bitmask + a 2-or-3-byte compressed-length field) followed by the
  sector-data sub-stream, optionally followed by a subcode sub-stream.
  **We don't need the ECC/sync reconstruction or the subcode stream at
  all** — the 2048 bytes of ISO9660 user data we actually want sit at a
  fixed offset (16..2064) within each decompressed 2352-byte "frame",
  untouched by whether sync/ECC bytes get reconstituted. This
  meaningfully narrows what needs implementing.
- **Revised plan: no native/NDK dependency needed.** `org.tukaani:xz`
  (LZMA, `cdlz`) and `io.airlift:aircompressor` pinned to a pre-v3
  release (Zstandard, `cdzs` — v3+ added an optional
  `java.lang.foreign` native-acceleration path that doesn't exist on
  Android, so newer isn't automatically better here) are both pure-JVM.
  Together they cover 100% of the user's real library with zero file
  reconversion needed. (The user independently researched this via
  Perplexity in parallel and landed on the same conclusion for
  `aircompressor`, but had `com.github.luben:zstd-jni` down for the
  Zstandard side — that one's a JNI wrapper around native code despite
  an inline comment calling it pure-Java, would have reintroduced the
  exact risk being designed around. Caught before anything was built on
  it.)

**Added:**
- `ChdHeader.kt` (new) — parses the fixed 124-byte v5 header: magic,
  version, the 4 compression-codec slots (a single file can mix codecs
  across hunks — confirmed live, this real file uses `cdlz`/`cdzl`/`cdfl`
  in slots 0-2), logical size, map/meta offsets, hunk/unit byte sizes.
  Only header parsing — the hunk map itself is Huffman+RLE coded with
  self/parent hunk references (`decompress_v5_map` in libchdr), a
  separate and more involved piece, not a flat table the way the header
  might suggest.
- `app/src/test/` (new test source set) + `junit:junit:4.13.2` — this
  project had no unit tests before now (every other feature verified via
  `adb`/hardware, appropriate for UI/Android-lifecycle code). Container
  parsing, ISO9660 traversal, and hash construction are pure deterministic
  logic with no Android dependency, exactly what's expensive to verify
  by rebuilding+installing+manually testing on a device and cheap to
  verify with a JVM unit test instead. Expect more of these as the
  CHD/ISO9660/hash pieces get built.
- `ChdHeaderTest.kt` (new) — asserts against the *actual* first 124
  bytes of a real file from the user's library (`Crash Bandicoot (USA).chd`),
  pulled via `adb` and independently cross-checked in Python before the
  Kotlin test was written, not synthetic/hand-built bytes. Covers the
  happy path, the CD-frame-multiple invariant on `hunkBytes`, a bad-magic
  rejection, and a truncated-header rejection.

**Verified:** `:app:testDebugUnitTest` passes, including the real-data
assertions above. `:app:compileDebugKotlin` unaffected/still passes.

### Open questions / discrepancies to resolve
- The hunk map decoder (Huffman+RLE, self/parent hunk references) is
  real, non-trivial work still ahead — the next piece, not started.
- After the map: per-hunk decompression dispatch (`cdzs`/`cdlz`/`cdzl`
  via the two new pure-JVM libraries, once actually added as
  dependencies — not yet in `build.gradle`), then the ISO9660/SYSTEM.CNF
  layer, then the hash construction itself, then wiring into `RomHasher`
  in place of its current `if (system == GameSystem.PS1) return null`.
  Several distinct pieces still ahead of a working end-to-end PS1 hash.

**Added, per user request** after confirming the login/identification
foundation on hardware (see the entry below): a real screen showing a
game's achievement list with earned/unearned status, built on top of
that foundation rather than as its own separate auth path. PS1 support
and live unlock detection during play are both still out of scope — see
[[project-retroachievements-priority]].

Verified the endpoint live against the real account before writing the
parser (same practice as the login correction below caught) — good thing,
because the docs' summary of the response shape was wrong in a way that
would have silently broken parsing: it described lowercase field names
(`id`, `title`, `achievements`, `badgeName`, `dateEarned`), but the actual
live response uses **PascalCase** throughout (`ID`, `Title`,
`Achievements`, `BadgeName`, `DateEarned`). Also confirmed live:
`DateEarned`/`DateEarnedHardcore` are absent from an achievement's JSON
object when unearned, not present-with-a-null-value — presence is the
signal, which `JSONObject.has()` checks directly rather than needing a
null check.

- `RetroAchievementsClient.kt` — `getGameProgress(username, apiKey,
  gameId)` calls the Web API's `API_GetGameInfoAndUserProgress.php`
  (`u`, `y`, `g` params), which conveniently returns the full achievement
  list pre-merged with this user's per-achievement earned status in one
  call — no second request needed to overlay unlock state. New
  `AchievementInfo`/`GameProgress` data classes; achievements are sorted
  by RA's own `DisplayOrder` before being handed back, since the JSON
  object they arrive in (keyed by achievement ID) carries no ordering
  guarantee at all. `AchievementInfo.badgeUrl` picks between RA's normal
  and `_lock`-suffixed greyscale badge image (`i.retroachievements.org/Badge/`)
  based on earned status — both variants confirmed to actually resolve
  via a live request, not assumed from the URL convention alone.
- `AchievementsActivity.kt` / `activity_achievements.xml` (new) —
  clamp-aware like every other non-gameplay screen (same
  BoundaryController scaffold as Settings/Library), showing the game's
  icon, an earned-count/points summary, and the full achievement list.
  Always fetches fresh on open rather than caching — earned status
  changes as the user plays, and this screen has no way to know a cached
  copy went stale.
- `AchievementsAdapter.kt` / `item_achievement.xml` (new) — one row per
  achievement (badge, title, description, points), loaded via Coil
  (already a dependency — no new image-loading code needed, unlike the
  box-art path which predates it and hand-rolls its own download/cache).
  Earned state shown via the badge art itself plus points color, not a
  separate label competing for space in an already dense row.
- `RomLibraryActivity.checkAchievements` — a successful hash match now
  opens `AchievementsActivity` directly instead of just toasting a game
  ID. Every other outcome (not logged in, PS1, not recognized, request
  failed) still has nothing to show a screen for, so those stay toasts.
  `achievements_check_recognized` string removed as a result — nothing
  reads it anymore.

**Verified:** `:app:compileDebugKotlin` and `:app:assembleDebug` both
succeed. Endpoint response shape and both badge-image URL variants
confirmed live via `curl` against the real account before any parsing
code was written.

**Verified on hardware:** built, installed, and tested against multiple
real games via "Check RetroAchievements" — the achievement list screen
opened correctly for every one (icon, summary, badge art, earned/unearned
state) with no reported issues. `adb logcat` filtered to
`TacoBoy.*`/`AndroidRuntime:E`/`*:F` throughout: completely empty, not
even the unrelated SystemUI noise seen during the login/identification
testing earlier — no exception anywhere in the fetch/parse/render path
across every game tested.

### Open questions / discrepancies to resolve
- No offline/error retry affordance on `AchievementsActivity` — a failed
  load just shows a message with no retry button; closing and reopening
  the screen is the only way to try again. Fine for now given how early
  this feature is, worth revisiting if it turns out flaky connections are
  common enough to be annoying.
- Coil's default disk/memory cache handles badge image reuse — no
  explicit cache invalidation was added for the (currently impossible,
  since RA doesn't allow it mid-session here) case of a badge changing
  between saves.

## 2026-08-15 (RetroAchievements: login + hash-based ROM identification)

**Added, per user request:** the foundation layer of RetroAchievements (RA)
integration — real account login and per-ROM hash identification against
RA's database. No achievement list or unlock UI yet; that's the deliberately
deferred next slice once this foundation exists to build it on. See
[[project-retroachievements-priority]] in memory for how this feature's
priority evolved.

Researched RA's actual Connect API and hashing rules before writing any
code (rather than assuming), then — with a real RA account the user
provided for testing — verified the auth model live with `curl` before
trusting it, which caught a wrong assumption baked into the first version
of this entry (see "Corrected mid-session" below). The API this app talks
to is a mix of two: RA's Connect API (`dorequest.php`, the single
non-RESTful endpoint real libretro-based frontends use) for hash
identification, and the separate read-only Web API (`retroachievements.org/API/`)
purely to validate a key before saving it.

- `RetroAchievementsClient.kt` (new) — `verifyCredentials(username,
  apiKey)` calls the Web API's `API_GetUserSummary.php` to confirm a
  username/key pair is valid before it gets saved (pure UX, not itself
  used for identification). `identifyGameId(username, apiKey, md5)` calls
  the Connect API's `r=gameid` to resolve a hash to RA's game ID, or `0`
  for a legitimate "not recognized" (returns `null` only on an actual
  request failure — the two aren't the same thing and callers need to
  tell them apart). Every Connect API request sends a `User-Agent`
  header — RA's docs say requests without one are rejected outright, easy
  to miss since nothing about a missing header looks wrong until it's
  actually tested against the live API, which is exactly how this got
  caught.
- `RomHasher.kt` (new) — computes RA's own per-console identification
  hash: GB/GBC/GBA hash the whole file; SNES strips a leading 512-byte
  copier header first when the file size indicates one is present (`size
  mod 0x2000 == 512`). **PS1 is deliberately not implemented** — RA
  identifies PS1 games by parsing `SYSTEM.CNF` out of the disc's ISO9660
  filesystem to find and hash the boot executable, real disc-image
  parsing that this app's `.chd`-only PS1 support would also need CHD
  decompression (libchdr) just to reach. Big enough to be its own
  follow-up.
- `TacoBoyPrefs.kt` — `getRetroAchievementsUsername`,
  `getRetroAchievementsApiKey`, `setRetroAchievementsCredentials`,
  `clearRetroAchievementsCredentials`. Named around "API key," not
  "token" — see the correction below for why that's not just a naming
  preference.
- Settings "Achievements" tab — no longer a placeholder: a status row
  (logged in as / not logged in) with a Log In/Log Out chip, matching
  the toggle-row visual pattern already used elsewhere in Settings.
  Login is a username + Web API key `AlertDialog` (two-`EditText`, key
  field masked, plus a note on where to find the key on RA's site) —
  never a password field, since RA has no password-based login step to
  put one in front of.
- Library long-press game menu — new "Check RetroAchievements" action
  (`RomLibraryActivity.checkAchievements`) that hashes the ROM and looks
  it up, one ROM at a time. Deliberately manual rather than wired into
  the bulk library scan: `RomHasher` reads the entire ROM file, and
  auto-identifying an entire library on every scan would be a real cost
  with nothing built yet (achievement list, unlock UI) to justify it.
  Toasts one of: not logged in, system unsupported (PS1), recognized
  (with game ID), not recognized, or request failed — these are
  distinguishable outcomes on purpose, not collapsed into a single
  generic failure message.

**Corrected mid-session — the first version of this feature was built on
a wrong assumption:** initially implemented login as `dorequest.php?r=login2`
trading a real password for a session token, based on the Connect API
docs' description of that endpoint and secondary sources suggesting a
Web API key would work interchangeably in the password field. The user
then pointed out RA login only ever needed a username + API key, no
password, and offered real test credentials. Live `curl` calls against
both APIs with those credentials showed: `r=login2` genuinely 401s when
given an API key (`invalid_credentials`) — the docs-plus-secondary-source
assumption was simply wrong, not a matter of interpretation — while the
same API key works directly as the Connect API's `t` parameter on
`r=gameid` with no login step at all, and independently validates fine
against the Web API's `API_GetUserSummary.php`. There is no session/token
concept in this API — the Web API key is a permanent credential, not
something exchanged for a shorter-lived one. Everything above already
reflects the corrected design; this note exists so a future session
doesn't reintroduce the `login2`/password approach having only read the
Connect API docs' description of that one endpoint in isolation.

**Verified:** `:app:compileDebugKotlin` succeeds. Auth model (credential
verification, hash->game-ID lookup, User-Agent requirement, error-response
shapes) verified live via `curl` against the real API using the user's
own account, including both the success and invalid-credentials paths.

**Verified on hardware, full round trip:** built and installed
`app-debug.apk` on a real device (`adb install -r`), logged into Settings
> Achievements with the real account, confirmed the status row updated to
"Logged in as ChikinNuggit". Long-pressed a real GBA ROM in the library
and ran "Check RetroAchievements" — correctly recognized against RA's
database. Ran it again against a PS1 ROM — correctly toasted "not
supported yet" instead of attempting a hash. `adb logcat` filtered to
`TacoBoy.*`/`AndroidRuntime:E`/`*:F` throughout both tests: clean, not one
line — the only capture was unrelated Samsung SystemUI noise, meaning no
exception was thrown anywhere in the hash/network/UI path (RomHasher and
RetroAchievementsClient only log on the exception path, so silence here
is a positive result, not just an absence of information).

Also tested against real SNES ROMs (still on hardware, same session) —
recognized successfully too. **Still not fully closed out:** which code
path that exercised is unconfirmed — "recognized" proves RomHasher's MD5
computation is correct for whichever bytes it hashed, but doesn't by
itself prove the `size mod 0x2000 == 512` header-stripping branch ran,
since most modern SNES dumps don't carry a copier header and would take
the same whole-file path GBA already proved. Worth confirming with a
ROM known to have the 512-byte header (or checking file size directly)
before fully retiring this as a risk.

### Open questions / discrepancies to resolve
- `RomHasher`'s SNES header-stripping rule (`size mod 0x2000 == 512`) is
  sourced from RA's docs; SNES ROMs now confirmed to identify
  successfully on hardware, but not confirmed to be *headered* ROMs, so
  the header-stripping branch itself is still unproven — everything
  tested so far may have taken the same whole-file path GBA already
  covers. Low residual risk (the rule is simple and well-documented,
  and any headered ROM hashed via the wrong branch would just fail to
  identify, not crash), but worth closing out with a known-headered ROM.
- No logout confirmation dialog (unlike preset deletion) — logging out
  just clears the saved credentials immediately. Seemed low-stakes enough
  (no data loss, just log back in with the same key) to match the
  auto-bind toggle's no-confirmation pattern rather than preset
  deletion's.
- PS1 identification remains unimplemented (see above) — the next RA
  slice (achievement list UI) will need to either accept PS1 has no
  achievements yet, or this gets tackled first.
- The API key used for live verification this session came from the user
  directly in chat, not entered through the app. Regenerating it from the
  RA profile's Settings page afterward is a reasonable precaution, purely
  because it now exists in a conversation transcript — not because
  anything here mishandled it (never written to a file, never logged,
  only used in-memory for the `curl` calls above).

## 2026-08-15 (auto-save SRAM toggle + PS1 memory card note; reconstructed after a lost session)

**Note on how this entry came to exist:** the session that did this work
ended in a VS Code crash (mid extension install) before it could write
its own changelog entry or hand off context. This entry was
reconstructed after the fact from the working tree itself — file
contents and modification times — not from a live session log, so
there's no "verified on hardware" trail for it the way other entries
have one. Worth a quick sanity check on-device next time this area is
touched.

**Added:** a General/System settings toggle for whether the app
auto-saves and auto-restores cart battery data (SRAM) — separate from
save states — plus an explanatory note about PS1's per-game ~128KB
virtual memory card.

- `TacoBoyPrefs.kt` — `KEY_AUTO_SAVE_SRAM` (`isAutoSaveSramEnabled` /
  `setAutoSaveSramEnabled`), defaulting to on.
- `TacoBoyActivity.kt` — `persistSram()` (called from `onPause`, so it
  survives backgrounding/ROM-switch/process death, same rationale as
  the existing save-state persistence) and the `setupRetroView`
  restore path both now gate on this flag via `SramManager`.
- `SettingsActivity.kt` — `populateSystemSection` gained a `toggleRow`
  for the setting plus two `placeholderText` notes
  (`settings_auto_save_note`, `settings_memory_card_note`).
- `strings.xml` — `settings_auto_save_label`, `settings_auto_save_note`,
  `settings_memory_card_note`.

`RomLibraryActivity.kt`, `activity_rom_library.xml`, and
`ControllerBindings.kt` also show edits from this same session window,
but no functional change tied to them turned up under inspection —
likely incidental resaves (formatting/whitespace) rather than new
behavior. Flagging in case something there was actually mid-edit and
just isn't visible from a static read.

### Correction to prior belief
A prior session's memory note characterized RetroAchievements as the
likely next feature in progress. On inspection, no RetroAchievements
code exists anywhere in this tree (no hashing, no network client, no
`rc_client`-style integration) — only the pre-existing placeholder tab
in Settings. Whatever RA discussion happened, if any, didn't reach the
working tree before the crash. RA integration starts from a clean
slate.

## 2026-08-14 (named, saveable controller presets)

**Added, per user request:** the last piece of the controller-bindings
vision from memory (`project-tacoboy-controller-presets`) that was still
buildable today — named presets, save/apply/delete, layered directly on
top of the just-verified binding editor. Auto-assigning a preset on
Pocket Taco Bluetooth detect remains deliberately deferred (needs a
"default preset" concept this session didn't add) — RetroAchievements
was discussed as the other obvious next big feature but explicitly
deprioritized by the user in favor of "other things that are more
useful," so it stays parked in memory.

- `ControllerPresets.kt` (new) — named `Map<ControllerBindings.Target, Int>`
  snapshots, persisted as a JSON file (`filesDir/controller_presets.json`),
  matching `RomLibraryCache`'s pattern rather than `TacoBoyPrefs` — a
  growing list of named objects doesn't fit flat SharedPreferences
  key-value storage the way scalars do. `save()` overwrites same-named
  presets; `applyToSystem()` bulk-writes every target directly via
  `TacoBoyPrefs.setButtonBindingSource` rather than going through
  `ControllerBindings.setSource`'s swap-conflict logic — a saved preset
  is already a clean bijection by construction (captured via `getSource`,
  which never returns a keycode for two targets at once), so a plain
  overwrite can't introduce a collision the way a single reassignment
  could.
- Settings Controller tab — new horizontally-scrollable presets row
  (wrapped in a `HorizontalScrollView` from the start this time, having
  already hit a toolbar-overflow bug once this session from not doing
  that) sitting above the bindings list: a "Save Preset…" chip always
  first, then one chip per saved preset. Tap applies the preset to
  whichever system tab is currently selected; long-press asks for
  confirmation before deleting (the one destructive, irreversible action
  in this feature — matches this app's existing pattern of confirming
  before permanent data loss but not for reversible actions like hiding
  a ROM or resetting box art). Saving prompts for a name via the same
  `AlertDialog` + `EditText` pattern already used for ROM renaming.

**Verified on hardware, full round trip:** saved a preset from GBA's
clean identity defaults ("My_Layout"), confirmed the exact 16-entry JSON
persisted correctly. Switched to SNES, deliberately customized "A" to L1
(confirming the swap-conflict logic still correctly gave L1 "Button A" in
exchange). Applied "My_Layout" to SNES: toast confirmed, screenshot
confirmed A/L1 both cleanly reverted to their identity defaults,
`shared_prefs` confirmed all 16 SNES targets now explicitly stored
matching the preset. Long-pressed the chip: confirmation dialog appeared
correctly; confirmed deletion: chip disappeared, `controller_presets.json`
confirmed empty array. `adb logcat` clean of `FATAL`/`AndroidRuntime`
throughout. Cleared all test binding overrides afterward to leave a clean
slate.

### Open questions / discrepancies to resolve
- Applying a preset marks every target in that system as "customized"
  (shows a reset icon) even for values that happen to match the
  underlying default — cosmetically busier than ideal right after
  applying, but not a correctness issue (reset still works correctly
  per-target). Same class of minor cosmetic gap as the swap-conflict
  reset behavior noted in the binding-editor entry.
- No rename-preset action (only save-as-new and delete) — not requested,
  not added.
- Auto-assigning a preset when a Pocket Taco connects is still unbuilt.
  Would need a "default preset per system" concept (or per-controller)
  layered on top of what exists now, then wiring `PocketTacoDetector`'s
  connect signal to it instead of (or alongside) the current bare toast.

## 2026-08-14 (default bindings were actually wrong — identity, not swapped; per-system relevance)

**Fixed and improved, per user question** ("are the assigns correct... A
Nintendo controller would think it's wrong when A is assigned to B") —
this turned out not to be just a labeling concern. Verifying it properly
found the swapped defaults shipped in the previous entry were genuinely
incorrect for the Pocket Taco, not merely confusing-looking.

**Root cause:** `ControllerBindings`' default table mirrored
`GamepadsManager`'s A/B and X/Y swap, which exists to compensate for
controllers that report button *position* using Xbox-style convention
(bottom=A, right=B, top=X, left=Y) regardless of what's printed on the
button — a real phenomenon for some generic Android gamepads, where a
Nintendo-labeled pad's physically-bottom "B" button would get reported as
position-A and needs un-swapping. That assumption was never verified
against the actual Pocket Taco, and it doesn't hold: three separate live
`adb shell getevent`/keylayout captures against the real unit (pressing
B alone first, then confirming A/X/Y together in one pass) showed its
firmware reports each button's own **printed label** directly —
physical A → `KEYCODE_BUTTON_A`(96), B → `BUTTON_B`(97), X →
`BUTTON_X`(99), Y → `BUTTON_Y`(100), all confirmed via
`/system/usr/keylayout/Generic.kl`'s raw-code-to-keycode table, not
assumed. No Xbox-position relabeling happens at the driver level for
this device. Applying the swap anyway meant every face button was
silently wrong by one position — not just "looks odd to newcomers," a
genuine, confirmed misbinding.

- `ControllerBindings.DEFAULT_SOURCE` — changed from the swap to plain
  identity (`Target.entries.associateWith { it.keyCode }`). Only affects
  systems/targets nobody has explicitly customized yet — any binding a
  user already set via the editor is untouched, since defaults only
  apply as a fallback when no override is stored.
- Added `GameSystem.relevantControllerTargets` and filtered the
  Controller tab's row list by it — addresses the user's separate,
  correct observation that showing all 16 RetroPad targets for every
  system implies buttons that don't exist on that system's real
  hardware. GB/GBC (no X/Y, no shoulders at all), GBA (no X/Y), SNES (full
  face+shoulder set, the class default), PS1 (the only system among
  those supported whose real controller — DualShock — has L2/R2/L3/R3,
  so it gets the full `Target` set). L3/R3 stay in the `Target` enum
  itself regardless — the Pocket Taco has no analog sticks at all (per
  `roadmap.md`'s own system-suitability notes, "no analog required"), so
  they're simply unreachable on this device, but keeping them costs
  nothing and matters if someone ever plays with a different controller.

**Verified on hardware:** GBA tab confirmed showing exactly 10 rows (D-Pad
×4, A, B, L1, R1, Start, Select — no X/Y) with clean `A→Button A, B→Button B`
identity defaults, no swap. GB tab confirmed showing exactly 8 rows (no
shoulders, no X/Y either). PS1 tab confirmed showing the full 16-row set
including L2/R2/L3/R3, also clean identity defaults throughout. No crash
across all three tab switches.

### Open questions / discrepancies to resolve
- Identity-by-default is now confirmed correct for the Pocket Taco
  specifically. If TacoBoy is ever used with a different, genuinely
  position-reporting Android gamepad, that pad's A/B might need the old
  swap behavior — the per-button editor already covers that case
  manually (rebind two buttons), but there's no per-controller-profile
  default yet. Not worth solving until it's an actual reported problem
  with a second controller.
- GBA's `relevantControllerTargets` omits L2/R2 as well as X/Y — worth
  double-checking real GBA hardware truly has zero use for those (it
  does: GBA's own pad is D-Pad/A/B/L/R/Start/Select only), but flagging
  since it wasn't re-verified against a physical GBA cartridge/core
  during this session, just against known hardware facts.

## 2026-08-14 (real, editable controller bindings — Controller tab)

**Added, per user request:** the actual binding editor behind the
Controller tab (previously just a reserved placeholder) — per-system,
per-button, tap-to-listen-and-assign. First real slice of the vision
recorded in memory (`project-tacoboy-controller-presets`); named/saveable
presets and auto-assignment on Pocket Taco detect are still deliberately
not built (see Open questions).

- `ControllerBindings.kt` (new) — `Target` enum (A/B/X/Y/L1/R1/L2/R2/
  Start/Select/L3/R3/D-Pad×4), each holding the Android
  `KEYCODE_BUTTON_*`/`KEYCODE_DPAD_*` value LibretroDroid's native side
  already understands as a RetroPad slot (`GamepadsManager.GAMEPAD_KEYS`)
  — no new native-side concept needed. `DEFAULT_SOURCE` reproduces
  `GamepadsManager`'s existing A/B and X/Y swap exactly, so introducing a
  user-configurable table changes nothing until someone actually
  customizes a binding. `resolveTarget(sourceKeyCode)` is what
  `TacoBoyActivity` now calls per key event.
- **Superseded GLRetroView's own key handling rather than layering on top
  of it.** `GLRetroView.onKeyDown`/`onKeyUp` already do the right thing
  (filter to real gamepad keys, apply the A/B/X/Y swap) — but only if
  `GLRetroView` holds view focus, which nothing granted it (that's the
  same root cause as the previous entry's B/Back bug). Rather than
  fighting that mechanism to make it user-configurable, `TacoBoyActivity`
  now deliberately does **not** focus `GLRetroView` and owns key/motion
  dispatch itself end to end, applying `ControllerBindings.resolveTarget`
  per key. This is a partial revert of the previous entry's fix (which
  had focused `GLRetroView`) — necessary because a hardcoded library-level
  swap and a user-editable one can't both own the same input at once;
  `GLRetroView`'s own handlers are effectively retired now, not deleted
  (still there in the vendored library code, just never reached).
- `SettingsActivity` — Controller tab rebuilt: a system-tab row (which
  `GameSystem`'s bindings are being edited — bindings are genuinely
  per-system, per the user's stated design) plus one row per `Target`
  showing its currently-assigned physical button, matching the app's
  existing reset-icon convention from the Graphics section (only shown
  once a target has an explicit override). Tapping a row's value enters a
  "Press a button…" listening state; `SettingsActivity.dispatchKeyEvent`
  captures the very next physical key press and assigns it — intercepting
  at `dispatchKeyEvent` rather than `onKeyDown` specifically so it wins
  before any normal back-navigation/focus handling gets a look.
- Extended `PocketTacoDetector.isSpuriousBack` (was inline in
  `TacoBoyActivity`, now shared) to `RomLibraryActivity` and
  `SettingsActivity` too, not just the game screen — needed for the
  listening flow specifically (the Taco's B fires `BUTTON_B` immediately
  followed by a spurious `BACK`; if listening captured the first and
  stopped listening, the leftover `BACK` would otherwise reach normal
  dispatch and could close Settings right after a successful bind), but
  it's a real latent bug on every screen the Taco can reach, not just
  this one.

**Bug found and fixed during testing — a real correctness bug, not a
one-off:** initial version of `ControllerBindings.setSource` only
*cleared* a conflicting target's stored override when reassigning a
button. That's a no-op when the conflicting target was still sitting on
its **default** (unoverridden) value — exactly the A/B case, since they
default to *each other's* physical button. Assigning physical Button-A to
target A left target B still silently defaulting to that same physical
button; `resolveTarget`'s `firstOrNull` always picked A, so B's row kept
displaying a binding that could never actually fire. Fixed by swapping
instead of clearing — whichever other target currently resolves to the
newly-assigned button gets *this* target's previous value, keeping the
whole table a bijection (one physical button per target) after every
single assignment. The same collision risk existed for reset-to-default
too (resetting A back to its default re-collided with B's still-explicit
override) — fixed by routing `clearSource` through the identical
conflict-resolution path, since "reset" is just "assign the default
value."

**Verified on hardware, methodically:** first pass showed impossible-looking
results (14 of 16 targets suddenly holding explicit identity bindings
after what looked like one tap) — rather than assume a UI/timing bug,
isolated each step (tap alone, wait alone, screenshot alone) with a
direct `shared_prefs` check after each one, which traced it to genuine
physical button presses landing during the slower batched test, not a
logic error — a real lesson in not trusting an apparent bug without
isolating cause from effect first. Confirmed the A/B swap bug itself with
a controlled synthetic `adb shell input keyevent 96`
(`KEYCODE_BUTTON_A`) rather than relying on ambient presses: reproduced
the collision, applied the swap fix, reproduced clean (`A=96, B=97`,
screenshot-confirmed both rows showing correctly with reset icons).
Reset-collision fix verified the same way (reset A → `B` correctly
absorbed A's old value, `shared_prefs` confirmed no two targets ever
shared a source afterward). Full regression pass at the end: cleared all
overrides, cold-launched, confirmed the game auto-resumes with no crash
and `adb logcat` clean of `FATAL`/`AndroidRuntime`.

### Open questions / discrepancies to resolve
- Named/saveable presets and auto-assigning one on Pocket Taco Bluetooth
  detect are still fully unbuilt — this session only landed the editable
  table itself. `PocketTacoDetector`'s connect signal still just shows a
  toast (see previous entries), not wired to bindings at all yet.
- The listening flow's synthetic-keyevent and swap-logic testing was
  thorough; actually rebinding a button and confirming the *game* responds
  differently (not just that `shared_prefs` and the UI update correctly)
  wasn't separately re-verified after the swap-conflict fix landed — worth
  a real in-game check next time the Taco's in hand.
- Reset-to-default doesn't perfectly restore a swapped pair's *original*
  cosmetic state — resetting A after an A/B swap leaves B holding an
  explicit override that happens to equal its own default (so B's reset
  icon shows even though it's functionally back at default). No collision
  risk, just a minor display inconsistency; full swap-history undo would
  need real transaction tracking, judged not worth it for this pass.

## 2026-08-14 (real fix for wrong/broken button input, incl. "B closes the app")

**Fixed, per user report:** B was acting as a back button, minimizing
TacoBoy to the phone's home screen on every press — reported as part of
"the controller still has some incorrect bindings." Root-caused this
properly on-device rather than patching the symptom, and it turned out to
be one underlying architectural bug, not a one-off B-specific glitch.

**Diagnosis, step by step, each stage confirmed on real hardware before
moving to the next (never guessed):**
1. `adb shell dumpsys bluetooth_manager` + `getevent -l` while the user
   pressed B: raw HID reports `BTN_EAST` cleanly, one DOWN/UP pair per
   press — the physical button itself isn't the problem.
2. `adb shell dumpsys input` showed the device uses the stock
   `/system/usr/keylayout/Generic.kl` (no custom layout), and that file
   maps raw key 305 (`BTN_EAST`) to `BUTTON_B` — so the OS-level
   translation is also correct in isolation.
3. Added temporary `Log.d` to `TacoBoyActivity.onKeyDown`/`onKeyUp`
   (removed again once the fix landed) and captured real presses via a
   backgrounded `adb logcat` window timed against the user actually
   pressing the button (a live back-and-forth — several earlier capture
   attempts caught nothing because the timing didn't line up with a real
   press, which is its own lesson: don't trust an empty capture as a
   negative result if the timing wasn't independently confirmed). The
   real capture showed the answer: **every single B press delivers two
   separate KeyEvents to the app — `KEYCODE_BUTTON_B` (97) *and*
   `KEYCODE_BUTTON_B`'s doppelganger `KEYCODE_BACK` (4), both attributed
   to the "GameSir-Pocket 1" device, same timestamp.** One physical press,
   two keycodes — not a mislabeled button.
4. Reading `GLRetroView.kt` (this LibretroDroid fork's own library code,
   not something TacoBoy-specific) turned up the real design flaw:
   `GLRetroView` already has its own correct `onKeyDown`/`onKeyUp`/
   `onGenericMotionEvent` — filters to `GamepadsManager.GAMEPAD_KEYS`,
   applies the documented Android-to-RetroPad A/B and X/Y swap, and
   forwards D-pad/analog axes — but none of it was ever running, because
   nothing in `TacoBoyActivity` had ever given `retroView` view focus.
   Android only dispatches key/generic-motion events to a view that
   holds focus; without it, `GLRetroView`'s handlers are silently dead
   code, and every button press was instead reaching only
   `TacoBoyActivity`'s own hand-rolled forwarding — which sent the
   **raw, unmapped** keycode straight to the core (no A/B swap) and,
   critically, always called `super.onKeyDown()`/`onKeyUp()` afterward,
   letting the spurious `KEYCODE_BACK` trigger Android's default
   back-navigation on every single B press.

**Fix — architectural, not a patch:** `TacoBoyActivity.setupRetroView()`
now makes `retroView` focusable (`isFocusable`/`isFocusableInTouchMode`
= true) and calls `requestFocus()` right after adding it to the view
hierarchy, so `GLRetroView`'s own correct handling actually runs.
`TacoBoyActivity.onKeyDown`/`onKeyUp` no longer manually forward
anything — the manual `sendKeyEvent`/`sendMotionEvent` calls were
removed entirely (including the `onGenericMotionEvent` override, since
`GLRetroView`'s own version already does the same D-pad/analog-stick
forwarding correctly once focused) — and now exist solely to swallow the
one confirmed-spurious case: `KEYCODE_BACK` arriving from the Pocket
Taco specifically (checked via the existing `PocketTacoDetector`,
reusing the device-identity match from the Bluetooth auto-detect work).
The phone's own back gesture/button arrives from a different input
device and is completely unaffected.

**Verified on hardware:** rebuilt with diagnostic logging first, captured
real B presses via a properly-timed background `adb logcat` window,
confirmed the dual-keycode finding before writing any fix. After the fix:
relaunched (game auto-resumed, no crash), user pressed B directly and
confirmed **the app no longer minimizes**. `adb logcat` clean of
`FATAL`/`AndroidRuntime` throughout. Diagnostic `Log.d` calls removed
from the shipped fix.

### Open questions / discrepancies to resolve
- This fixes B specifically (confirmed) and, by removing the raw/unmapped
  forwarding entirely, should also fix the A/B and X/Y swap for every
  other face button — but only B was directly confirmed against real
  hardware this session. Worth a quick pass over A/X/Y/shoulders next
  time the Taco is in hand, to confirm they now feel correct too, not
  just B.
- The dual-keycode behavior (`BUTTON_B` + `BACK` from one press) is
  presumably a deliberate firmware choice on GameSir's part (common on
  simple/TV-remote-style gamepads, so the controller works as a basic
  Android navigation remote even in apps with no gamepad support at all)
  rather than a defect in the unit — not something to chase upstream,
  just something TacoBoy now specifically compensates for.
- This is still just making the *existing* GamepadsManager-level A/B/X/Y
  swap actually take effect — real per-system custom bindings and
  presets (the user's larger stated goal) remain unbuilt, tracked in
  memory (`project-tacoboy-controller-presets`).

## 2026-08-14 (D-pad was leaking into app chrome, not just the game)

**Fixed, per user report:** the Pocket Taco's D-pad was simultaneously
controlling the game *and* navigating/activating `menu_button`,
`library_button`, the quick-menu's Save/Load/Reset/Exit buttons, and
`boundary_handle` — reported as those elements "highlighting and
interacting" under D-pad input even with no bindings configured yet.

**Root cause, confirmed on-device before touching any code:** sent
`adb shell input keyevent 20` (DPAD_DOWN) to the running game and dumped
the view hierarchy (`adb shell uiautomator dump`) — `library_button`
showed `focused="true"` after a single press. This is standard Android
behavior, not a TacoBoy-specific bug: any hardware key event (D-pad
included) makes Android exit "touch mode" and start using D-pad-shaped
key events for standard view-focus navigation across every
clickable/focusable view — and since API 26, `setOnClickListener` makes
a view auto-focusable unless told otherwise. Every clickable button in
`TacoBoyActivity`'s layout was an unintended focus-navigation target.
Meanwhile game input kept working the whole time via a separate,
unaffected path — the D-pad's hat-switch axis reports as a `MotionEvent`
(`onGenericMotionEvent` → `MOTION_SOURCE_DPAD`), which Android's
focus-navigation system doesn't touch at all — so both symptoms (game
still responds + chrome also lights up) were really two different input
paths firing off the same physical D-pad press.

**Fix:** `android:focusable="false"` + `android:focusableInTouchMode="false"`
on every clickable element in the game screen's chrome —
`library_button`, `menu_button`, `occlusion_zone`, `boundary_handle`,
`rom_picker_button`, `reset_button`, `exit_to_library_button`, and
`quick_menu_slot_row.xml`'s `slot_save`/`slot_load`. Touch behavior is
completely unaffected (`clickable="true"` untouched throughout) — only
key/D-pad-driven focus is disabled, so with nothing left in the hierarchy
for D-pad focus-search to land on, the key event now falls through
untouched to `TacoBoyActivity.onKeyDown`/`onKeyUp`, which already forward
it to `retroView` exactly as before. Scoped to the game screen only —
`RomLibraryActivity`'s grid/tabs and `SettingsActivity`'s tabs were left
alone, since D-pad-driven browsing of a list or settings menu is
plausibly desirable UX there rather than a bug, and the user didn't
report an issue on those screens.

**Verified on hardware:** same reproduction method run again after the
fix — `adb shell input keyevent 20`/`22` (down/right) sent repeatedly on
the game screen, `uiautomator dump` showed zero `focused="true"` nodes
anywhere in the hierarchy. Confirmed touch clicks on `library_button`
still work unchanged (opened the library normally). Opened the quick
menu, sent DPAD_DOWN ×2 + DPAD_RIGHT + DPAD_CENTER (the actual "click"
key) directly at it: all 4 slots stayed "Empty", Reset didn't fire, Exit
to Library didn't fire, quick menu stayed open — DPAD_CENTER no longer
has anything focused to click. `adb logcat` clean of
`FATAL`/`AndroidRuntime` across the entire repro-then-verify pass.

### Open questions / discrepancies to resolve
- This was the button/chrome half of "controller doesn't affect the core
  correctly yet" — actual per-system button *mapping* (D-pad/face buttons
  → correct retropad inputs per system) is still the unbuilt Controller
  tab work tracked in memory (`project-tacoboy-controller-presets`), not
  addressed by this fix.

## 2026-08-14 (Pocket Taco Bluetooth auto-detect + Controller settings tab)

**Added, per user request:** roadmap Phase 3.2's "auto-detect Pocket Taco"
item, reframed around the user's actual goal for it — a hook for future
controller-preset auto-assignment, not just a toast. Also added a new
"Controller" section to Settings, reserved (like Achievements was) for
per-system button bindings and saveable presets, which the user
explicitly wants — configure once with a real Pocket Taco attached, save
as a named preset, assign presets as per-system defaults, with detection
eventually auto-assigning the right one. That binding/preset system is
a large, separate feature and was **not** built this session — scoped
down to just the detection mechanism plus the reserved tab, matching how
RetroAchievements was handled (see memory).

- **Confirmed the real device identity before writing any matching code**
  (per project practice of never guessing/fabricating an identifier):
  `adb shell dumpsys bluetooth_manager` against the user's own paired
  Pocket Taco showed it bonds as classic BR/EDR HID under the name
  `GameSir-Pocket 1`; `adb shell dumpsys input` confirmed the same name
  surfaces as a live `InputDevice` (`Device 136: GameSir-Pocket 1`,
  `IsExternal: true`) while connected.
- **Switched approach away from raw Bluetooth APIs** once that was known:
  matching via `InputDevice.getName()` needs zero manifest permissions
  (no `BLUETOOTH_CONNECT` runtime prompt on Android 12+, unlike
  `BluetoothAdapter`), and it directly reflects what actually matters —
  whether the app can receive input from it — rather than raw pairing
  state. `PocketTacoDetector.kt` (new) — one method, prefix-matches
  `"GameSir-Pocket"` rather than the full string, in case a firmware/unit
  revision changes the trailing " 1".
- `TacoBoyActivity` — registers an `InputManager.InputDeviceListener` in
  `onResume`/unregisters in `onPause`, toasting
  (`pocket_taco_detected`) only on a genuine `onInputDeviceAdded` connect
  transition, **not** on a static "is it connected right now" check at
  every `onCreate`/`onResume`. Deliberate: the Taco is normally already
  clamped on and connected before the app is even opened, and this
  Activity gets `recreate()`d fairly often (switching ROMs from the
  library) — a static check would either miss the common case entirely
  (if scoped to onCreate) or spam a toast on every trip back from
  Settings/Library (if scoped to onResume). An edge-triggered listener
  avoids both failure modes.
- Settings — new "Controller" tab (`section_controller`, between Graphics
  and Audio) with an honest placeholder describing the plan rather than
  the generic "coming soon", so re-opening Settings before the real
  feature lands doesn't look like a dead end.

**Verified on hardware:** cold-launched with the Taco already connected
(the common case) — confirmed via screenshot no toast fires, matching the
edge-triggered design (nothing "just happened" to announce). Cycled
Settings → Library → back → Settings → back several times: no crash, no
repeated toast, confirmed via `adb logcat` clean of
`FATAL`/`AndroidRuntime` across the whole sequence. Controller tab renders
correctly in the scrollable strip (screenshot) with its placeholder text.
**Live-detection path confirmed by the user directly** (power-cycled the
real Pocket Taco while the app was in the foreground, something this
session couldn't safely simulate itself without dropping the user's other
paired Bluetooth devices) — the "Pocket Taco connected" toast fired
correctly on reconnect. `onInputDeviceAdded` → `PocketTacoDetector` match
→ toast is confirmed working end-to-end, not just compiling.

### Open questions / discrepancies to resolve
- Detection is currently only wired into `TacoBoyActivity`. Whether
  `RomLibraryActivity`/`SettingsActivity` should get it too (e.g. for a
  future "Controller" tab status row showing "Pocket Taco: Connected")
  wasn't decided — natural to revisit once the Controller tab has real
  content.
- The Controller tab's actual feature set (per-system binding editor, a
  preset data model, save/load/assign UI, and wiring detection to
  auto-assign) is fully unbuilt — this session only reserved the tab and
  the detection signal it'll eventually key off of.

## 2026-08-14 (library search + sort)

**Added, per user request:** search and sort for the ROM library
(roadmap Phase 2.3), motivated specifically by a gap in the only other
emulator built for the Pocket Taco (now defunct) — it never had this
either, so this puts TacoBoy ahead of the field it's targeting even
against software that no longer exists to compete with.
- `TacoBoyPrefs.recordRomPlayed`/`getLastPlayed`/`getPlayCount` — per-ROM
  (keyed by URI, same pattern as hidden/custom-title) last-played
  timestamp and play count. Recorded in `TacoBoyActivity.loadRom()` the
  moment a ROM is successfully handed to the emulator core — same point
  `currentRomIdentifier` gets set — not gated on a confirmed rendered
  frame the way the crash-loop breaker is, since this is UX ordering, not
  crash safety.
- `RomLibraryActivity.SortMode` (`NAME`/`RECENTLY_PLAYED`/`MOST_PLAYED`) —
  persisted via `TacoBoyPrefs.getLibrarySortMode`/`setLibrarySortMode`,
  mirroring the existing `ViewMode` pattern exactly. Cycled by a new ⇅
  toolbar button; each tap shows a toast ("Sorted by Recently Played" etc.)
  since a single static glyph can't self-describe 3 states the way the
  grid/list toggle's icon-swap does. Unplayed ROMs sort to the end under
  either played-based mode (`getLastPlayed`/`getPlayCount` both default to
  0) and keep their relative alphabetical order there, since Kotlin's
  `sortedByDescending` is stable and the source list is already
  alpha-sorted.
- Search: new 🔍 toggle opens a `search_row` (EditText + ✕ clear) below
  the system tabs; filters live via `TextWatcher`, matching against the
  same display title (`getCustomTitle(...) ?: displayName`) the adapters
  actually render, not the raw filename, so a search matches what's on
  screen. Resets automatically on system-tab switch — a query for one
  system's titles wouldn't mean anything against another's.

**Fixed along the way — toolbar overflow, caught before it shipped:**
first pass added `search_button`/`sort_button` directly into
`toolbar_row` alongside the existing 6 elements. On-device this squeezed
"Select a ROM" into an unreadable 4-line wrap — turned out 7-8 icon
buttons structurally don't fit this toolbar row on real hardware
regardless of title length (the icons alone measure wider than the
screen). Fixed properly rather than papering over it: split
`view_mode_toggle`/`sort_button`/`search_button` out into a new
`list_controls_row` beneath the toolbar (grouped together as "how the
list is displayed", separate from the toolbar's library-management
actions), and shortened the title string itself from "Select a ROM" to
"Library" (`rom_picker_dialog_title`, also now `maxLines=1`/`ellipsize=end`
as a safety net for future additions) — it's been a full standalone
screen since the cached-library entry, not a picker dialog, so the old
name was stale anyway.

**Verified on hardware:** confirmed the restructured toolbar renders
"Library" on one line with room to spare (screenshot). Typed "alien" into
search: PS1 grid live-filtered to exactly the 2 matching titles
(Resurrection, Trilogy); tapped ✕: full list and keyboard both closed
cleanly. Cycled sort through all 3 modes: Recently Played correctly
surfaced "007 - The World Is Not Enough" (the one PS1 title actually
launched during this session's testing) to the top with its toast
confirming; Most Played kept it first (only title with a nonzero play
count); Name returned to alphabetical with 007 titles first, confirmed via
`shared_prefs/tacoboy_prefs.xml` showing `library_sort_mode=NAME` and a
`play_count`/`last_played` pair keyed to that ROM's URI (count reached 3
from repeated auto-resume during this session's testing, not a bug —
every real load increments it). Opened search with a query, then switched
to the GBA tab: search closed and cleared automatically, GBA's 485-title
list rendered correctly with no BIOS button (GBA doesn't need one) and no
crash. `adb logcat` clean of `FATAL`/`AndroidRuntime` across the entire
pass.

## 2026-08-14 (Settings screen — reset-to-default, boundary hint parity)

**Added, per user request (next item off the "what's next" list):** closed
the "no settings persist warning/reset-to-default UI yet" open question
from the Settings-screen shell entry — a saved PS1 renderer choice lived
forever in `SharedPreferences` with no way back to the default short of
the cycle button happening to land on it again, or clearing app data.
- `TacoBoyPrefs.clearRendererChoice` — removes the saved key entirely
  rather than writing `GameSystem.defaultRenderer`'s current value back
  into it, so a reset keeps tracking the default if it's ever changed in
  a future update instead of pinning to whatever it was at reset time.
- `SettingsActivity.rendererRow` — added a small ↺ reset button next to
  each renderer's value pill, visible only when a choice has actually
  been saved for that system (`getRendererChoice(...) != null`) — nothing
  to reset back from otherwise. Uses the same borderless-ripple background
  every other icon button in this app gets via XML
  (`?attr/selectableItemBackgroundBorderless`), resolved manually since
  this row is built in code, plus a `TooltipCompat` label matching the
  library toolbar's icon-button convention.

**Also closed a small gap in the previous entry's first-run hint:**
`SettingsActivity` already reuses `BoundaryController` (it's clamp-aware
like every other screen), but wasn't passed a `boundary_hint` view, so a
user who opened Settings before ever touching the handle elsewhere
wouldn't see the hint there. Added the same `boundary_hint` `TextView` to
`activity_settings.xml` and wired it into the `BoundaryController` call,
matching `TacoBoyActivity`/`RomLibraryActivity`.

**Left alone, deliberately:** the other open question from that entry
(unifying the "coming soon" placeholder style with the Graphics section's
dynamic-row pattern) is still premature — it explicitly depends on a
second section gaining real content to compare against, which hasn't
happened yet.

**Verified on hardware:** opened Settings → Graphics with PS1's renderer
already saved as `Software` from earlier testing — reset icon showed
correctly. Tapped it: icon disappeared, value stayed "Software" (still
the default), confirmed via `shared_prefs/tacoboy_prefs.xml` the
`renderer_choice_PS1` key was removed entirely (not just overwritten).
Tapped the value pill to cycle to `OpenGL`: reset icon reappeared
immediately. Tapped reset again: reverted to `Software`, icon gone,
pref cleared again. Closed Settings back to the library: no crash,
confirmed via `adb logcat` (no `FATAL`/`AndroidRuntime` lines), grid
content intact.

## 2026-08-14 (revoked/missing ROM grant handling)

**Fixed, per user request (next item off the "what's next" list):** closed
the open question flagged back in the ROM-folder-picker entry — no
handling existed yet for a ROM whose SAF access has gone bad (file moved
or deleted, folder permission revoked by clearing the picker app's data,
etc.), and it had never actually been exercised. Two real gaps found once
tested properly, not just one:

1. **`TacoBoyActivity.loadRom()` wasn't defensive against the
   `DocumentFile` name lookup throwing**, only against `openFileDescriptor`
   failing. Depending on the platform's `DocumentsProvider`, a revoked
   grant can surface as a thrown `SecurityException` rather than a null
   name — and critically, that would happen *before*
   `TacoBoyPrefs.beginRomLoad()`, i.e. outside the crash-loop breaker's
   protected window. An uncaught exception there would hit
   `TacoBoyApplication`'s global handler, restart the app, and immediately
   retry the same broken `lastRomUri` in `initRomFlow()` again — a genuine
   crash-loop, not a hypothetical one. Wrapped the `DocumentFile` lookup in
   `loadRom()` in the same try/catch pattern already used for the
   `openFileDescriptor` call.
2. **Picking a new ROM from the library while a game was already
   running unconditionally called `recreate()`** — set as `lastRomUri`
   and committed to the Activity restart *before* ever checking the new
   ROM was actually openable. A stale pick (deleted file, revoked grant)
   would silently kill the in-progress game and dump the user on a blank
   picker, with no explanation. Added `TacoBoyActivity.canOpenRom()` — the
   same checks as `loadRom()` (name lookup, system resolution, fd open)
   but side-effect-free, so it's safe to call as a preflight probe without
   touching Activity/native state. The `retroView != null` branch now
   checks this first; on failure it shows a toast
   (`rom_picker_rom_unavailable`) and returns without touching
   `lastRomUri` or calling `recreate()` — the running game is left alone.

**Also distinguished the messaging for auto-resume failures:**
`initRomFlow()`'s fallback previously showed the same generic
`rom_picker_hint` ("Open the library to select your ROMs") whether this
was a genuine first launch or a remembered ROM that had gone missing.
Added `rom_picker_last_rom_missing`, shown only when a `lastRomUri` existed
but failed to load — mirrors the precedent already set by
`rom_picker_crash_recovered` for the crash-loop-breaker case, so the user
gets an honest reason instead of a first-run-shaped prompt.

**Verified on hardware, without touching any real ROM file:**
- Auto-resume case: edited `last_rom_uri` in `shared_prefs` to point at a
  nonexistent document under the real (still-granted) PS1 tree, force-
  stopped, relaunched. Confirmed via screenshot the new
  `rom_picker_last_rom_missing` message renders (not the generic hint),
  confirmed via `adb logcat` no `FATAL`/`AndroidRuntime` lines, and
  confirmed `last_rom_uri` was removed from `shared_prefs` afterward (not
  left to retry forever).
- Running-game-survives case: started a real PS1 game, then injected a
  fake unreachable entry ("Broken Test ROM (deleted)") as the first
  element of `rom_library_cache_PS1.json` — safe/non-destructive since the
  cache is just a scan-result cache, not the real ROM files — reopened the
  library, and tapped it. Confirmed via screenshot the `rom_picker_rom_unavailable`
  toast appeared *over the still-running game* (no `recreate()`, no blank
  picker), confirmed via logcat no crash, then hit the library's Refresh
  button to force a real rescan and confirm the injected fake entry was
  gone from the cache afterward (209 real entries, no trace of the test
  data).

## 2026-08-14 (boundary-handle first-run hint)

**Added, per user request (picked as the smallest item off the "what's
next" list):** roadmap Phase 3.2's first-run hint for the boundary drag
handle ("Drag to adjust the visible area") — the handle has always been
functional but undiscoverable, with no label of any kind.
- `TacoBoyPrefs.hasSeenBoundaryHint`/`setBoundaryHintShown` — one
  device-wide `Boolean` flag, deliberately not per-screen: dismissing the
  hint on either `TacoBoyActivity` or `RomLibraryActivity` retires it on
  both, since it's teaching one shared mechanism
  (`BoundaryController`/`boundary_handle`), not two.
- `BoundaryController` — took an optional 5th constructor param
  (`hintView: View?`, defaulting to `null` so nothing else calling it
  breaks). On init, shows the hint if a view was passed and the flag isn't
  set yet; on the handle's first `ACTION_DOWN`, hides it and persists the
  flag immediately — dismissal happens the instant the user starts
  actually using the feature it's teaching, not on a timer or a separate
  "got it" tap.
- `boundary_hint` `TextView` (new, in both `activity_tacoboy.xml` and
  `activity_rom_library.xml`, same dark-rounded-pill style as
  `quick_menu_panel_bg` via new `boundary_hint_bg.xml`) — constrained
  directly above `boundary_handle`, `gone` by default, wired into
  `BoundaryController`'s new param from each Activity's `onCreate`.

**Verified on hardware:** cleared prefs to simulate a genuine first run —
hint rendered correctly above the handle on launch (screenshot). Tapped
the handle: hint disappeared, `boundary_percent` unchanged (a tap is a
same-position down/up, no unwanted drag), and `shared_prefs/tacoboy_prefs.xml`
confirmed `boundary_hint_shown=true`. Force-stopped and cold-relaunched:
hint correctly did not reappear. Opened the library screen afterward:
handle renders with no hint bubble there either, confirming the
device-wide (not per-screen) flag works as designed.

## 2026-08-14 (Settings screen — shell + first real setting)

**Added, per user request:** a Settings screen, clamp-aware like the
library (reuses `BoundaryController`, no separate "raise it later" gap
this time). User specified six sections up front — General, System,
Graphics, Audio, Achievements, Advanced — with the explicit understanding
this is a shell to build into over time, not everything at once.
- `SettingsActivity.kt` / `activity_settings.xml` (new) — same
  guideline/occlusion-zone/handle pattern as `RomLibraryActivity`. Section
  tabs live in a `HorizontalScrollView` rather than equal-weight tabs like
  the system-tab row — "Achievements" is long enough that fixed-width tabs
  would squeeze the shorter labels, and a 6th tab was one more than the
  library's 5 system tabs comfortably fit on one row anyway. Sections are
  plain sibling views toggled by visibility (matching this app's existing
  no-fragments style), not fragments.
- Registered in the manifest, not exported — same as `RomLibraryActivity`.
  Reached via a new ⚙ icon in the library toolbar
  (`RomLibraryActivity` → `settings_button`).
- General/System/Audio/Achievements/Advanced are placeholders
  ("More settings coming soon.") for now. Flagged two honest scope notes
  to the user before building rather than after: **Vulkan isn't
  implemented anywhere in this LibretroDroid fork** (confirmed while
  chasing the PS1 renderer bug two entries up — `environment.cpp` only
  ever hands out a GLES3 context, `libretro_vulkan.h` is unused dead
  code), so a Vulkan toggle needs real native work first, not just a
  settings row. **RetroAchievements is its own separate integration**
  (hashing, login, RA API calls) — reserved a section for it, not
  attempting it yet.

**Graphics section — first real, working setting:** generalized the PS1
renderer fix from the previous entry (which hardcoded
`swanstation_GPU_Renderer = Software` via a one-off
`GameSystem.forcedCoreVariables` map) into a proper user-facing choice.
- `GameSystem` — replaced `forcedCoreVariables` with
  `rendererOptionKey`/`rendererChoices`/`defaultRenderer`. PS1 offers
  `Software` and `OpenGL` — deliberately not `Vulkan`/`D3D11`/`D3D12`/
  `Auto`: Vulkan has no backend here (see above), D3D is Windows-only and
  irrelevant on Android, and `Auto` is exactly what silently picked the
  broken OpenGL path in the first place.
- `TacoBoyPrefs.getRendererChoice`/`setRendererChoice` — per-system saved
  choice; `TacoBoyActivity.setupRetroView` uses the saved choice if
  present, else `GameSystem.defaultRenderer`.
- `SettingsActivity.populateGraphicsSection` builds one row per
  `GameSystem` with non-empty `rendererChoices` — nothing in the UI code
  names PS1 specifically, so a future system with its own renderer option
  gets a row automatically. Tapping a row's value cycles through the
  choices and saves immediately.

**Verified on hardware:** opened Settings from the library (⚙ icon,
confirmed visible in the toolbar alongside BIOS/folder/art icons).
General tab shows the placeholder; tab row confirmed scrollable (Achieve­
ments cut off at the screen edge, as expected with 6 tabs). Graphics tab
shows "PS1 Renderer: Software" (correctly read from `defaultRenderer`
with no saved pref yet). Tapped the value: label updated to "OpenGL" and
`shared_prefs/tacoboy_prefs.xml` confirmed `renderer_choice_PS1=OpenGL`
persisted correctly. Toggled back to "Software" before finishing, since
leaving it on OpenGL would reintroduce the black-screen bug for real use
— confirmed reverted in prefs. Closed via the ✕ button: returned cleanly
to the library with no crash, PS1 grid/box art/BIOS button all still
correct.

### Open questions / discrepancies to resolve
- Graphics section only ever shows a row when a system has
  `rendererChoices` — currently just PS1. Fine today, but if a future
  section (e.g. Audio) ends up with zero configurable systems too, the
  "coming soon" text and this dynamic-row pattern will look inconsistent
  side by side — worth a single shared "nothing configurable yet" style
  once more sections have real content to compare against.
- No settings persist warning/reset-to-default UI yet — a saved renderer
  choice lives forever in `SharedPreferences` with no way to clear it
  short of the cycle button or clearing app data.

## 2026-08-14 (PS1 support via SwanStation)

**Added:** PS1 as a fourth system, `.chd`-only by design — PS1's usual
`.cue`/`.bin` pairing doesn't fit this app's single-file SAF virtual-file
loading model, and the user's whole PS1 collection is already `.chd` (via
the CHDroid conversion app), so this isn't a real-world limitation.

- `app/src/main/jniLibs/arm64-v8a/swanstation_libretro_android.so` — new
  core binary, downloaded from libretro's official nightly buildbot
  (`buildbot.libretro.com/nightly/android/latest/arm64-v8a/`), same
  distribution channel RetroArch itself uses. Verified the exact URL via
  fetch before hardcoding it, per project practice of never guessing URLs.
- `GameSystem.kt` — added `PS1` entry (`chd` extension, `needsBios = true`,
  thumbnail folder `Sony - PlayStation`). Added a `forcedCoreVariables` map
  to `GameSystem` for per-system libretro core-option overrides (see bug
  fix below) — empty for every other system.
- `BiosManager.kt` (new) — copies a user-picked BIOS file into
  `GLRetroViewData.systemDirectory` (`filesDir`), preserving its original
  filename. SwanStation scans the whole system directory and identifies
  valid PS1 BIOS images by content, not by exact filename, so this is the
  most broadly compatible import strategy.
- `RomLibraryActivity` — added a "BIOS" toolbar button, visible only when
  `GameSystem.needsBios` is true for the selected tab; launches a document
  picker (`GetContent("*/*")`) that hands off to `BiosManager`.
- `activity_rom_library.xml` — added the `PS1` tab (5 tabs now fit one row
  without wrapping) and the conditional `import_bios_button`.
- `TacoBoyActivity` — wired `GLRetroView.getGLRetroErrors()`, which had been
  completely unconnected until now. A load failure (bad dump, unsupported
  format, or for PS1 specifically a missing/invalid BIOS) previously failed
  completely silently — no crash, no feedback. Now shows a toast, with a
  PS1-specific message pointing at the BIOS button when `needsBios` is true.
- `strings.xml` — added `system_label_ps1`, `library_bios_*`,
  `game_load_failed_*`.

**Bug found and fixed — PS1 loaded audio but no video.** First real-device
test (BIOS imported, ROM folder set, box art downloaded, game launched)
produced audio with a black screen. `adb logcat` during a repro showed the
actual cause:

```
Libretro Core: [HostInterface] Renderer = OpenGL
Libretro Core: [HostInterface] Requesting hardware renderer context for OpenGL
AdrenoGLES-0: ERROR: Invalid #version
Libretro Core: [SwitchToHardwareRenderer] Failed to create hardware host display
```

SwanStation's default renderer setting (`Auto`) resolves to a desktop-style
`OpenGL` hardware context on this core build. `environment.cpp`'s
`RETRO_ENVIRONMENT_SET_HW_RENDER` handler accepts any requested
`context_type` unconditionally (no validation) and always hands back a
GLES3 context — LibretroDroid never implements a real desktop-GL or Vulkan
backend (`libretro_vulkan.h` is present but unused/unwired). SwanStation's
OpenGL path then generates desktop GLSL (`#version 330`-style), which the
Adreno GLES driver rejects outright. The emulation loop and audio callback
keep running regardless, since renderer init failure isn't fatal to the
core — hence audio-with-no-video rather than a crash.

Fix: force SwanStation's `swanstation_GPU_Renderer` core option to
`Software` via the new `GameSystem.forcedCoreVariables` mechanism, applied
through `GLRetroViewData.variables` → `LibretroDroid.create(...)` before
the core loads. Confirmed via `libretro/swanstation`'s
`libretro_core_options.h` (fetched from source, not guessed) that
`"Software"` is a valid value for that exact key. Software rendering
sidesteps the broken GL-context negotiation entirely and is easily fast
enough for PS1 on modern phone hardware.

**Verified on hardware:** after the fix, relaunching the same game shows
the PlayStation BIOS boot logo rendering correctly (3D "PlayStation"
wordmark, "Licensed by Sony Computer Entertainment America / SCEA" text) —
confirmed via screenshot. BIOS import flow, PS1 tab selection, and the
conditional BIOS button were also confirmed working during this session.

## 2026-08-14

**Confirmed:** Pocket Taco is a Bluetooth HID gamepad (not a custom protocol).
`TacoBoyActivity`'s existing `onKeyDown`/`onKeyUp`/`onGenericMotionEvent`
forwarding (same pattern as `SampleActivity`) should receive its input
without any Pocket Taco-specific code — not yet verified against real
hardware.

**Added `com.tacoboy` package** — new app-layer code, kept separate from the
untouched upstream `com.swordfish.libretrodroid` library/sample so future
`git pull`/merges from upstream LibRetroDroid stay low-conflict.

- `app/src/main/java/com/tacoboy/TacoBoyPrefs.kt` — `SharedPreferences`
  wrapper storing the black-zone boundary as a 0.3–0.95 fraction of screen
  height (`boundary_percent`, default `0.65`). Per-device, not per-game,
  since it reflects physical clamp position on that phone.
- `app/src/main/java/com/tacoboy/TacoBoyActivity.kt` — new launcher activity,
  the actual app entry point:
  - Edge-to-edge (`WindowCompat.setDecorFitsSystemWindows(false)`) + hides
    system bars on focus, so the game area isn't shrunk by status/nav bars.
  - `ConstraintLayout` with a horizontal `Guideline` (`boundary_guideline`)
    splitting the screen into `gamecontainer` (top, holds `GLRetroView`) and
    `occlusion_zone` (bottom, opaque black, touch-swallowing).
  - `boundary_handle`: a small always-visible pill straddling the guideline,
    reachable from the game region. Drag it to move the guideline live;
    releasing persists the new position via `TacoBoyPrefs`. No separate
    "calibration mode" — always draggable.
  - Loads a hardcoded mGBA core + sample ROM path (same placeholder
    `SampleActivity` uses) — real ROM/core selection is not built yet.
  - Physical controller input forwarded exactly as `SampleActivity` does
    (`onKeyDown`/`onKeyUp`/`onGenericMotionEvent` → `retroView.sendKeyEvent`/
    `sendMotionEvent`).
- `app/src/main/res/layout/activity_tacoboy.xml`,
  `app/src/main/res/drawable/boundary_handle_bg.xml` — layout + handle
  drawable for the above.
- `AndroidManifest.xml` — registered `TacoBoyActivity` as a second
  `MAIN`/`LAUNCHER` activity, **locked to `portrait`**. `SampleActivity` left
  untouched/still launchable, for testing the raw library independent of
  TacoBoy-specific UI.
- `strings.xml` — added `tacoboy_activity_title` ("TacoBoy").
- `local.properties` — points `sdk.dir` at the local Android SDK
  (`C:/Users/dcrot/AppData/Local/Android/Sdk`) so Gradle can build. Not
  meant to be shared across machines if this ever goes in version control.

**Verified:** `./gradlew.bat :app:compileDebugKotlin` succeeds (exit code 0).
This only proves the Kotlin compiles — none of it has been run on a device
or against a real Pocket Taco yet.

### Open questions / discrepancies to resolve
- No game/core selection UI yet — core + ROM path are still hardcoded to
  the library's sample GBA data. Tracked as roadmap Phase 1.1.
- No first-run hint for the drag handle; it's discoverable but unlabeled.
  Tracked as roadmap Phase 3.2.
- **Core library gap:** `roadmap.md`'s recommended default cores per system
  are Gambatte (GB/GBC), mGBA (GBA), Mesen (NES), Snes9x (SNES), Genesis
  Plus GX (Genesis). `app/src/main/jniLibs/arm64-v8a/` currently only has
  `gambatte`, `libmgba`, `snes9x`, plus `handy`, `libparallel`, and a GL
  test core — no Mesen or Genesis Plus GX `.so` yet. Relevant when Phase 2
  ("Core downloader / manager") starts.

~~**Orientation conflict**~~ — resolved 2026-08-14: portrait confirmed
correct. A landscape clamp wouldn't be holdable by hand without it being
tablet-sized, so `roadmap.md`'s earlier §5.3 landscape mention was the
error, not the `portrait` lock in `TacoBoyActivity`. The redesigned
`roadmap.md` (see below) reflects this.

## 2026-08-14 (ROM folder picker — Phase 1.1)

**Added:** ROM folder picker replacing the hardcoded sample ROM path.
- `app/src/main/java/com/tacoboy/RomLibrary.kt` — recursively scans a SAF
  tree `Uri` for `.gba` files (v0.1 is GBA-only per roadmap Phase 1.1;
  multi-system extension mapping is Phase 2).
- `TacoBoyPrefs` — added `romFolderUri` and `lastRomUri` persistence.
- `TacoBoyActivity` — replaced `setupRetroView()`'s hardcoded
  `gameFilePath` with a full flow: first launch shows a "Select ROM
  Folder" prompt (`ACTION_OPEN_DOCUMENT_TREE` via
  `ActivityResultContracts.OpenDocumentTree()`, with
  `takePersistableUriPermission` so the grant survives reboots) → scans
  the folder → `AlertDialog` ROM list → picking one loads it and
  remembers it as `lastRomUri`. Subsequent launches auto-load the last
  ROM directly (no dialog), matching roadmap Phase 1.1's verification
  criteria. A small "Library" text button (top-end corner of the game
  region, only reachable from above the clamp) reopens the ROM list to
  switch games later.
- ROM loading now goes through `GLRetroViewData.gameVirtualFiles` +
  `ContentResolver.openFileDescriptor()` instead of `gameFilePath`, since
  SAF only hands back `content://` Uris, not filesystem paths.
- `app/build.gradle` — added `androidx.documentfile:documentfile:1.0.1`.
- No new manifest permissions needed — SAF handles its own access grants.

**Fixed — real crash, not a TacoBoy bug:** picking a ROM through the new
picker crashed the app immediately (`SIGABRT`, "fdsan: attempted to close
file descriptor N, expected to be unowned, actually owned by FILE*").
Root cause was in **upstream native code**, not anything added here — it
just had never been exercised before because `SampleActivity` only ever
used `gameFilePath` (plain filesystem paths), never
`gameVirtualFiles` (fd-based SAF loading).

Two related bugs found via device logcat + native backtraces
(`adb logcat`, Samsung tombstones from `DEBUG` tag):
1. First (wrong) guess: `VFS::virtualOpen()` in
   [vfs.cpp](libretrodroid/src/main/cpp/vfs/vfs.cpp) set both
   `stream->fd` and `stream->fp` to the same `dup()`'d fd, and
   `libretro-common`'s shared close routine closes both — `fclose(fp)`
   then a redundant `close(fd)`. Fixed by leaving `stream->fd = 0` (matches
   how the non-virtual open path in libretro-common keeps `fd`/`fp`
   mutually exclusive). **This fix is real and correct, but wasn't what
   was actually crashing** — mGBA doesn't set `need_fullpath`, so a single
   ROM never goes through `VFS::virtualOpen` at all. Kept the fix anyway
   since it's a legitimate latent bug for cores that do need full-path VFS
   or multi-file content (relevant again if PS1/multi-disc support is
   ever added, per roadmap).
2. **Actual crash site** (confirmed via native backtrace pointing directly
   at it): `Utils::readFileAsBytes(int)` in
   [utils.cpp](libretrodroid/src/main/cpp/utils/utils.cpp) — `fdopen()`'d
   the fd (which tags it as FILE*-owned for fdsan), read it, then called
   raw `close(fileDescriptor)` instead of `fclose(file)`. fdsan (strictly
   enforced on this Samsung/Android build) aborts the whole process on
   that ownership-tag mismatch. Also would have caused a *second*,
   independent double-close afterward via `VFSFile`'s `FDWrapper`
   destructor, which separately owns and closes that same original fd.
   Fixed by operating on a `dup()`'d descriptor instead — `fdopen()` +
   `fclose()` the duplicate locally, leaving the original fd's ownership
   solely with `FDWrapper` (mirrors the correct pattern already used in
   `VFS::virtualOpen`).

**Lesson:** don't stop at the first plausible-looking bug that matches the
symptom — verify against the actual native backtrace
(`adb logcat` around a `F DEBUG` / `beginning of crash` block gives full
symbolized frames). The first fix was real but not the cause; only the
device backtrace pointing at `readFileAsBytes` revealed the actual bug.

**Fixed — TacoBoy-side bug:** `initRomFlow()`'s fast path (auto-loading a
remembered `lastRomUri` on subsequent launches) never called
`hideRomPickerPrompt()`, so the "Library" button — the only way to switch
ROMs after the first pick — stayed permanently invisible once a ROM was
remembered. Caught because the user noticed "no choice to pick another
rom" after the fdsan fix let a ROM load successfully for the first time.

**Verified on hardware:** after both native fixes, picking a ROM (tested
with two different GBA ROMs) no longer crashes; confirmed via live
`adb logcat` streaming that no `fdsan`/`FATAL` lines appear after the
fixed build's install timestamp. Screenshot confirms game content renders
correctly within the game region (with letterboxing bars above/below the
actual image — GBA's 3:2 aspect ratio doesn't fill the tall game
container at `ViewportAlignment.CENTER`; worth revisiting alignment for
Phase 3 polish so the letterbox bar sits next to the boundary instead of
wasting space at the top of the screen).

**Fixed — confirmed real, not hypothetical:** the "SAF scan might be slow"
concern above wasn't theoretical — measured **4.5 seconds** to scan a real
485-ROM library (one subfolder per game), running synchronously on the
main thread from `showRomList()`. Close enough to Android's ~5s ANR window
to be a real risk, not just a future polish item. Fixed by moving the scan
to `Dispatchers.IO` via `lifecycleScope.launch` + `withContext`, added a
"Scanning ROM library…" `Toast` for feedback since the tap-to-result delay
is still human-noticeable, and guarded against double-tapping the Library
button mid-scan (`isScanningRoms` flag, button disabled while scanning).
Verified on device: game keeps animating during the scan instead of
freezing, toast displays correctly.

### Open questions / discrepancies to resolve
- ~~`ViewportAlignment.CENTER` wastes space at the top~~ — corrected
  2026-08-14: that's actually desirable, not a bug. The letterbox gap at
  the top of the game region lines up with the phone's camera cutout on
  many devices, so keeping it black there (rather than pushing game
  content into it) is the right call. User's suggestion: use that
  cutout-safe strip for chrome/menu buttons — `library_button` already
  lives there.
- ROM scan is now off the main thread, but still takes ~4.5s wall-clock
  for a 485-ROM library — fine with the toast, but revisit with pagination
  or a cached index if Phase 2's libraries get larger.
- No handling yet for a ROM whose `content://` grant is revoked (e.g. the
  user moves/deletes the file, or clears the picker app's data) beyond
  falling back to `TacoBoyPrefs.setLastRomUri(this, null)` and re-showing
  the folder/prompt flow — not yet exercised on hardware.

## 2026-08-14 (crash hardening)

**Added, self-directed (user deferred to my recommendation for "next
step, as long as we're making forward progress"):** roadmap Phase 4.1
("global exception handler... core crashes don't brick the app").
Motivated directly by today's two real native crashes (fdsan double-close,
FPSSync null deref) — both were process-killing, found only through
actual device testing, not defensive coding. That's the right signal that
hardening was due, not a hypothetical.
- `TacoBoyApplication.kt` (new `Application` subclass, registered via
  `android:name` in the manifest) — `Thread.setDefaultUncaughtExceptionHandler`
  catches uncaught **Kotlin/Java** exceptions, logs them, restarts cleanly
  into `TacoBoyActivity` (`FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_CLEAR_TASK`),
  then kills the crashed process. Explicitly does **not** catch native
  crashes (SIGSEGV/SIGABRT) — those kill the process before any JVM-level
  handler can run; today's two real crashes were both native and would
  have bypassed this entirely.
- Native crashes needed a different fix: a **crash-loop breaker** in
  `TacoBoyPrefs`/`TacoBoyActivity`, since `initRomFlow()` auto-resumes
  `lastRomUri` unconditionally — a ROM that crashes the native core on
  load would otherwise crash the app again on every subsequent launch
  too, with no way out short of clearing app data. Mechanism:
  `beginRomLoad()` (using `commit()`, not `apply()` — a process-killing
  crash can happen within milliseconds, and an async write might never
  reach disk in time) records the ROM URI right before `setupRetroView()`;
  `endRomLoad()` only clears it once `GLRetroView.getGLRetroEvents()`
  actually emits `FrameRendered` — proof the core survived, not just that
  `loadRom()` returned without throwing synchronously (the two crashes
  found today both happened *after* that point, on the GL thread). If
  `initRomFlow()` finds the flag still set and matching `lastRomUri` on
  the next launch, that ROM wasn't auto-resumed — shown a "that ROM
  crashed last time" prompt pointing back to the library instead.

**Verified on hardware:**
- Global handler: `adb shell am crash com.android.gl2jni` forced a real
  uncaught exception. Confirmed via logcat the handler fired
  ("Uncaught exception on main, restarting") and the app relaunched
  cleanly into `TacoBoyActivity`'s normal cold-start screen — no Android
  "app has stopped" dialog, no dump to home screen.
- Crash-loop breaker: manually edited `shared_prefs/tacoboy_prefs.xml` to
  set `load_in_progress` equal to the current `last_rom_uri` (simulating
  a crash that never reached `FrameRendered`), force-stopped, relaunched.
  Confirmed the app did **not** attempt to reload that ROM — instead
  showed "That ROM crashed last time, so it wasn't reopened
  automatically. Pick something from the library." and correctly cleared
  both prefs keys. Also regression-checked the happy path (normal launch
  auto-resumes and clears the flag correctly, confirmed via
  `shared_prefs` showing no lingering `load_in_progress` key after a
  clean load).

## 2026-08-14 (per-game long-press menu)

**Added, per user request:** long-press on a title now opens a per-game
menu. User specifically asked about an "alternate core" option too —
explicitly declined for now and documented why: only one core binary is
bundled per system (mgba/gambatte/snes9x), so a "switch core" menu item
would have nothing to switch to. Revisit once/if a second core is sourced
for a system that actually needs it.
- `TacoBoyPrefs` — added `getHiddenRoms`/`setHidden` (a
  `Set<String>` of ROM URIs, `SharedPreferences.putStringSet` — always
  writes a fresh copy, never mutates the returned set in place, per that
  API's documented footgun) and `getCustomTitle`/`setCustomTitle` (display
  override only, keyed by ROM URI — never touches the identifier used for
  box art / save states / core selection elsewhere in the app).
- `BoxArtCache.setCustomArt` — decodes a user-picked image (any format)
  and re-encodes to PNG at the same local path `ensureDownloaded` uses,
  so a custom pick is indistinguishable from a downloaded one to the rest
  of the app — including staying protected from being overwritten by a
  later bulk "Get Art" pass, which already only fetches when no local
  file exists yet. `BoxArtCache.clear` deletes the local file so a
  follow-up `ensureDownloaded` re-fetches from scratch.
- `RomListAdapter`/`RomGridAdapter` — added `onLongClick`, and now render
  `TacoBoyPrefs.getCustomTitle(...) ?: rom.displayName` instead of the
  raw filename.
- `RomLibraryActivity.onRomLongPressed` — `AlertDialog` with the 4
  actions, `Gravity.TOP` on the dialog window so it stays within the game
  region above the boundary, consistent with everything else on this
  screen. `renderRoms()` now filters `roms` against the hidden set before
  handing data to the adapters.

**Verified on hardware, with a genuine testing mishap worth recording:**
first long-press attempt used `input swipe x y x y 800` (same start/end
coordinates) to simulate a long-press. This actually opened the menu
mid-gesture, and the swipe's own release — still at the original (x, y)
— landed on whatever menu item the dialog happened to render at that
same position, silently "clicking" it. Result: accidentally hid
"Activision Anthology" from the real GBA library (confirmed via
`shared_prefs/tacoboy_prefs.xml`) rather than opening the menu for
inspection. Fixed the test technique (long-press on a tile far enough
down the grid that gravity-TOP dialog items can never overlap the release
point) and manually restored the prefs file to remove the accidental
hidden entry before continuing — it was test noise, not a real user
action, so it didn't belong in the persisted state.

With the corrected technique: menu opens correctly positioned near the
top; **Rename** confirmed end-to-end (renamed "Apotris (Ultimate Remix
Soundpack) (Tudd) (v1.1) (Aftermarket).gba" — a real homebrew Tetris
clone, good test case since it has no official box art — down to just
"Apotris", persisted, and displayed correctly in the grid afterward,
short enough to no longer need marquee scrolling). **Hide from Library**
confirmed working via the earlier mishap (persisted + correctly filtered
from the rendered list). **Custom Box Art** confirmed launching the real
system photo picker (Google Photos) correctly and cancelling cleanly with
no crash when backed out of without a selection — deliberately did not
pick one of the user's actual photos myself to test the full save path,
since that's the user's call, not something to do on their behalf.
**Reset Box Art — verified in a follow-up round:** long-pressed "Advance
Wars 2" (already had real downloaded art, a better test than Apotris
since Apotris has nothing to reset away from), tapped Reset. Confirmed
via file timestamp that `boxart/Advance_Wars_2_-_Black_Hole_Rising__USA_.gba.png`
was genuinely deleted and re-downloaded (fresh mtime matching the action,
not just left in place) — the clear-then-redownload cycle completed fast
enough there was no visible blank flicker in the UI, which is the ideal
outcome. All four menu items now fully verified end-to-end.

**Custom Box Art — confirmed by user directly:** the mis-tap that opened
the real photo picker turned out to be useful — user picked a real test
image through it themselves rather than me selecting one of their photos
on their behalf. Confirmed on device: `BoxArtCache.setCustomArt` wrote a
2.3MB PNG keyed to the ROM's real filename
(`Apotris__Ultimate_Remix_Soundpack___Tudd___v1.1___Aftermarket_.gba.png`)
— correctly the *actual* filename, not the "Apotris" rename override,
confirming the rename/art-identity separation holds in practice, not just
in code review. User confirmed the art displays correctly in the library.

**Process note:** while re-verifying the result, an adb tap opened what
turned out to be a live SNES folder-picker screen neither of us expected
— the user was interacting with the phone by hand at the same time
automated taps were being sent, and the two collided. Paused automated
input and checked in rather than risk tapping something on a screen the
user was mid-navigating; user confirmed it was fine either way (folder
selection is easy to redo if it changed). Worth remembering for future
sessions: once the user says they're testing something on-device
themselves, treat the phone as shared input and check before sending
further adb taps.

## 2026-08-14 (multi-folder-aware library — per-system tabs)

**Added, per user request:** switching between systems previously meant
re-picking a SAF folder every time via "Change Folder" — annoying with 4
systems in play. User's framing: a system-tab row in the library so each
system remembers its own folder and switching is instant, effectively
making the library double as the app's "home" without a separate launcher
screen.
- `TacoBoyPrefs` — replaced the single global `rom_folder_uri` key with
  one per `GameSystem` (`rom_folder_uri_<SYSTEM>`). Added
  `getLastSystem`/`setLastSystem` so the library reopens on whichever
  system tab was last active.
- `RomLibraryCache` — was a single shared cache file; now one file per
  system (`rom_library_cache_<SYSTEM>.json`). This was a latent bug this
  change forced a fix for: with per-system folders, a single shared cache
  file would only ever have reflected whichever system was scanned most
  recently, silently going stale for every other system.
- `RomLibraryActivity` — added a system-tab row (GBA/GB/GBC/SNES) below
  the toolbar. Selecting a tab persists it as the last-used system, looks
  up that system's remembered folder, and either loads its cache/rescans
  or shows a "No folder set for {system} yet" prompt if none is
  configured. Scan results are now filtered to
  `GameSystem.forFileName(name) == currentSystem` after scanning, so a
  folder with mixed-system files only shows the relevant ones under each
  tab. Dropped the `EXTRA_FOLDER_URI` launch contract entirely — the
  screen is now fully self-sufficient, resolving all folder/system state
  from `TacoBoyPrefs` itself.
- `TacoBoyActivity` — significantly simplified now that folder management
  moved entirely into the library: removed its own `folderPickerLauncher`
  /`onFolderPicked` and the SAF-picking capability that used to live
  there. `openLibrary()` no longer takes a folder argument.
  `libraryButton` is now unconditionally visible (previously gated on
  whether a global folder existed — that concept no longer exists).
  `rom_picker_button`'s label/hint changed from "Select ROM Folder" to
  "Open Library" to match its new job of just opening the self-managing
  library screen instead of launching a folder picker directly.
- One straightforward compile error along the way: a ternary building a
  tab's text color had one branch as `Int` (`.toInt()`'d) and the other
  as `Long` (missing `.toInt()`) — Kotlin couldn't unify the branch types
  against `setTextColor`'s overloads. Fixed by adding the missing
  `.toInt()`.

**Verified on hardware:** old single global folder pref doesn't carry
over to the new per-system keys (expected — clean cutover, not a
migration bug, since there's no production install to preserve data for
yet). User re-picked each system's folder once via the tab row; confirmed
all four systems now have independent listings and switching between them
requires no folder re-selection. User's words: "assigned the folders and
now each have their own listings without having to swap folders. It's
working very well."

## 2026-08-14 (save states, box art, multi-system)

**Added — save states / quick menu (roadmap Phase 1.2):**
- `SaveStateManager.kt` — 4 save-state slots per ROM, keyed by sanitized
  ROM display name (SAF URIs aren't stable filesystem paths), stored at
  `filesDir/savestates/<rom>/slot_N.state` via `GLRetroView.serializeState()`
  / `unserializeState()`. Separate from SRAM/battery saves.
- `TacoBoyActivity` — added a "Menu" button (top-start, mirrors "Library"
  on the top-end — both sit in the letterbox strip that avoids the camera
  cutout) opening an in-layout panel (not a system `AlertDialog`, so it's
  automatically constrained above the boundary like everything else):
  4 slot rows (each showing "Empty" or a save timestamp, Save/Load per
  slot), Reset, Exit to Library. Panel layout reused as
  `quick_menu_slot_row.xml` via 4x `<include>` — each instance's inner
  view ids collide across the 4 includes (standard Android `<include>`
  behavior), so lookups are scoped through each row's own unique
  `slot_row_N` id rather than a bare `findViewById`.
- Load surfaces a toast on failure (`unserializeState()` returning
  `false`); silent on success, matching normal emulator UX.

**Verified on hardware, with a real methodology detour worth remembering:**
first attempt looked broken — loading a save seemed to leave the game on
the title screen. Turned out to be two independent testing mistakes, not
app bugs: (1) the "gameplay" screenshot being compared against was
actually still the title screen (its cinematic tank artwork looks similar
to real gameplay at a glance), so the save was never mid-game to begin
with; (2) a second test's tap sequence didn't account for Save leaving the
quick menu open (by design, so you can save to multiple slots without
reopening the menu) — the next tap intended for "Load" landed on the raw
game surface instead. Added a temporary toast surfacing
`unserializeState()`'s actual return value to settle it definitively
(confirmed `true`) before cleaning it up into permanent failure-only UX.
Lesson: re-confirms the session's running theme — verify against ground
truth (return values, backtraces) rather than inferring from screenshots
when the visual evidence is ambiguous.

**Added — box art:**
- `BoxArtCache.kt` — downloads from libretro's thumbnail server
  (`thumbnails.libretro.com/<system>/Named_Boxarts/<rom-name>.png`),
  caches to `filesDir/boxart/`. A miss (common for aftermarket/homebrew/
  romhack titles, which have no official upstream art) is expected and
  logged at debug level only, not treated as an error.
- `RomListAdapter` / `RomGridAdapter` — both now show cached art via Coil
  (`io.coil-kt:coil:2.6.0`, new dependency — handles async decode +
  view-recycling-safe cancellation, which would've been easy to get wrong
  hand-rolled). List: small square thumbnail on the left. Grid: art fills
  the tile with the title as a scrim-backed overlay label at the bottom
  (falls back to the plain text tile from before when no art is cached).
- `RomLibraryActivity` — "Get Art" toolbar button batch-downloads art for
  every currently-listed title missing it, 4 at a time (considerate to
  libretro's shared server, still much faster than serial) via
  `Dispatchers.IO` + `async`/`awaitAll`, with progress/completion toasts.
- Coil 2.6.0 transitively requires `androidx.core:core:1.12.0`, which
  needs `compileSdk 34` — bumped from 33. Compile-time only; doesn't
  change `minSdk` (21) or `targetSdk` (33) runtime behavior.
- Added `INTERNET` permission (previously unneeded — SAF handled all file
  access without it).

**Verified on hardware:** downloaded art for the full 485-title GBA
library via "Get Art"; official titles got art, romhacks/aftermarket
titles correctly fell back to text tiles (expected — libretro's thumbnail
set only covers official releases). User confirmed art displays correctly
in both grid and list view.

**Added — multi-system support (roadmap Phase 2.1, pulled forward):**
user pushed for this next specifically because `gambatte` (GB/GBC) and
`snes9x` (SNES) cores were already sitting unused in `jniLibs/` — no new
core binary needed, unlike NES/Genesis which would require sourcing one
this project doesn't have.
- `GameSystem.kt` — extension → core-file mapping (`gba`→mGBA,
  `gb`→gambatte, `gbc`→gambatte, `sfc`/`smc`→snes9x). GB and GBC are kept
  as **separate** entries despite sharing the gambatte core, because
  libretro's thumbnail server keeps them in separate folders
  ("Nintendo - Game Boy" vs "Nintendo - Game Boy Color") — collapsing them
  would silently break box art matching for one of the two.
- `RomLibrary.scanRoms` now scans all of `GameSystem.SUPPORTED_EXTENSIONS`
  instead of a hardcoded `"gba"` set.
- `TacoBoyActivity.loadRom`/`setupRetroView` resolve `GameSystem` from the
  picked ROM's filename and pass its `coreFileName` into
  `GLRetroViewData.coreFilePath` instead of a hardcoded mGBA path. Fails
  gracefully (returns false, existing game if any keeps running) if a
  file somehow has an unrecognized extension despite `RomLibrary`
  already filtering — shouldn't happen, but the guard is essentially free.
- `BoxArtCache` resolves `GameSystem.thumbnailFolder` per ROM instead of
  a hardcoded GBA thumbnail path.

**Verified on hardware:** GBA regression-checked first (existing ROM still
auto-loads with no crash) — the multi-system change touches the shared
`loadRom`/`setupRetroView` path, so this mattered. User then independently
pointed the library at their GB and GBC folders (same `Emulation/`
parent, sibling folders to `Emulation/GBA`): both loaded and played
correctly, and "Get Art" correctly fetched art for them too (proving
`GameSystem.thumbnailFolder`'s GB/GBC split resolves against the right
libretro folders). SNES not yet tested — same code path, same
already-bundled `snes9x` binary, so expected to work identically, but
unconfirmed until tried with real SNES ROMs.

**Fixed — icon toolbar:** replaced the 4 word-label buttons (Grid/List,
Refresh, Get Art, Change Folder) with single-glyph icons (⊞/☰, ↻, 🖼, 📁)
plus `TooltipCompat.setTooltipText()` on each — a built-in AndroidX API
that shows the given text natively on long-press, so this needed zero
custom touch-handling code. Fixes the two-line toolbar wrap from the
previous entry; confirmed on device it now fits on one line, and the
long-press tooltip ("Rescan ROM folder" etc.) renders correctly.
Unused word-label string resources removed.

**Verified on hardware (unplanned, but conclusive):** the same screenshot
used to confirm the icon toolbar also had the library still pointed at
the user's SNES folder from earlier — it loaded correctly with box art in
grid view, so SNES is now confirmed working end-to-end (not just
"expected to work" by code-path similarity as noted above).

### Open questions / discrepancies to resolve
- No settings screen yet for the "configure box art manually" idea
  floated earlier (e.g. letting a user assign their own art to a romhack
  lacking upstream coverage) — explicitly deferred by the user as a later
  feature, not dropped.
- `RomLibraryCache`'s on-disk cache doesn't get invalidated by this
  session's scan-filter change (hardcoded `.gba`-only → all
  `GameSystem` extensions) — a folder scanned and cached before this
  update won't show newly-supported GB/GBC/SNES files in a *mixed* folder
  until the user hits "Refresh". Not a bug (cache is working exactly as
  designed — folder-URI-keyed, manually invalidated), just worth knowing
  if a mixed-system folder seems to be missing titles after this update.

## 2026-08-14 (library respects the clamp boundary too)

**Added, per user request:** made `RomLibraryActivity` clamp-aware too —
user pushed back on the previous entry's "not raised yet" open question,
specifically citing this as a gap in every other GBA emulator on Android
("major drawbacks of the other emulators available, they don't have this
kind of scaling"). Browsing/switching ROMs should never require removing
the Pocket Taco.
- `BoundaryController.kt` — extracted the drag-to-adjust logic (previously
  inline in `TacoBoyActivity`) into a small reusable class shared by both
  screens, so there's one implementation of the boundary/occlusion/handle
  behavior instead of two copies drifting apart.
- `activity_rom_library.xml` — added the same `boundary_guideline` /
  `occlusion_zone` / `boundary_handle` pattern as `activity_tacoboy.xml`.
  Toolbar + `RecyclerView` + empty/loading states now constrain to the
  guideline instead of the parent bottom.
- `RomLibraryActivity` — added edge-to-edge + hidden system bars (matching
  `TacoBoyActivity`) so the boundary percent means the same physical
  screen fraction on both screens.

**Fixed:** dragging the handle on the library screen updated
`TacoBoyPrefs.boundary_percent` correctly, but `TacoBoyActivity` never
picked up the change — it only applied the boundary once in `onCreate`,
and returning from `RomLibraryActivity` via back-press resumes the same
Activity instance rather than recreating it, so the guideline stayed
stuck at whatever it was when the Activity was first created. Fixed by
adding `BoundaryController.refresh()` (re-reads and reapplies the
persisted percent) and calling it from `TacoBoyActivity.onResume()`.
Verified on device: dragged the handle in the library, pressed back, main
game screen's handle position updated to match without needing an app
restart.

**Verified on hardware:** library screen's grid content correctly clips
above the boundary with the occlusion zone black below; handle drag on
the library screen persists to the same shared pref `TacoBoyActivity`
reads; full round trip (drag on library → back → main screen reflects new
position) confirmed with no crash.

## 2026-08-14 (cached grid/list ROM library)

**Added:** replaced the inline `AlertDialog` ROM picker with a dedicated
`RomLibraryActivity` screen, per user request (explicitly citing
GameSirBoy's app as the thing to do better than — it rescans every time
and has no grid/list toggle or marquee for long titles):
- `RomLibraryCache.kt` — persists the last scan as JSON
  (`filesDir/rom_library_cache.json`, keyed to the folder URI) so
  reopening the library is instant instead of re-running the ~4.5s SAF
  walk every time. A "Refresh" button forces a rescan (e.g. after adding
  ROMs); the folder URI mismatch check auto-invalidates the cache if the
  user changes folders.
- List and Grid view modes, toggleable, persisted via
  `TacoBoyPrefs.libraryViewMode`. Grid tiles use
  `android:ellipsize="marquee"` + `isSelected = true` (set in
  `RomGridAdapter.onBindViewHolder` — marquee only animates when the
  TextView reports itself selected, easy to miss) so long titles scroll
  instead of just truncating. Confirmed animating on device (caught
  mid-scroll in a screenshot, text sliding rather than static-ellipsized).
- `RomListAdapter.kt` / `RomGridAdapter.kt` — thin `RecyclerView` adapters
  over the same `RomLibrary.RomEntry` data; swapped along with the
  `LayoutManager` when the view mode toggles rather than one adapter
  handling both view types.
- `RomLibraryActivity` is a pure picker: returns the chosen ROM's `Uri` as
  an activity result rather than loading it itself — `TacoBoyActivity`
  still owns the single `GLRetroView` instance.
- `app/build.gradle` — added `androidx.recyclerview:recyclerview:1.3.2`.
- Manifest — registered `RomLibraryActivity` (not exported, internal-only).

**Fixed — new bug, this time genuinely TacoBoy's (not upstream):**
picking a *different* ROM while one was already running crashed
(`SIGSEGV`, null pointer deref in `libretrodroid::FPSSync::reset()`,
called from a stale `LibretroDroid.resume()` on the JNI side). Root cause:
`setupRetroView()` swapped `GLRetroView` instances in place (remove old
from the view + lifecycle, add new) without waiting for the old
instance's native teardown to finish first. `LibretroDroid`'s C++ side is
a singleton — the old instance's in-flight `resume()` call raced the new
instance's `create()`, which had already reset shared state out from
under it. This was never exercised before now because every previous test
either cold-started or picked a ROM for the first time (`retroView` was
always still null). Fixed by recreating the whole Activity
(`libraryLauncher`'s callback calls `recreate()` when `retroView != null`)
instead of trying to hand-sequence a correct in-place native teardown —
reuses the cold-start path that was already proven crash-free rather than
inventing a new risky one. `setupRetroView()`'s old teardown branch was
dead code once this guard exists (it can now only ever be called with
`retroView == null`) and was removed.

**Corrected note from the previous entry:** flagged
`ViewportAlignment.CENTER`'s top letterbox gap as wasted space — user
clarified that's backwards. That gap intentionally avoids the front
camera cutout many phones have, and the user's suggestion is to use that
cutout-safe strip for chrome (which `library_button` already does, by
coincidence of where it was placed). Not a bug; nothing to fix here.

**Verified on hardware:** first-ever open of `RomLibraryActivity`
scanned+cached correctly (spinner shown, ~4.5s, matches prior measurement).
Closing and reopening the library was instant (cache hit). List and Grid
both render correctly (485 ROMs); Grid mode's view-mode choice persisted
across a full Activity recreation. Selected a ROM from Grid view while a
different ROM was already running — recreated cleanly, no crash, new
game's boot screen rendered correctly within the game region.

### Open questions / discrepancies to resolve
- The library screen (`RomLibraryActivity`) is a normal full-screen
  Activity — it does **not** respect the black-zone boundary the way
  `TacoBoyActivity` does. If the Pocket Taco is physically clamped on
  while switching games mid-session, the bottom portion of the ROM list
  would be covered/unreachable. Not raised by the user yet; worth asking
  before investing in a fix, since it'd mean either duplicating the
  guideline/occlusion-zone layout pattern or extracting a shared base.
- Switching ROMs now costs a full Activity recreation (brief flicker/black
  frame) rather than an instant in-place swap. Acceptable given the
  crash it fixes, but if it feels slow in practice, a properly sequenced
  native teardown (Option B considered and rejected for now, see above)
  could remove the flicker later.
- Grid tile marquee is confirmed animating, but only one tile animates
  "selected" at a time in practice depends on RecyclerView recycling
  behavior — hasn't been checked for whether scrolling the grid
  interrupts/restarts marquees choppily on lower-end devices.

## 2026-08-14 (first device run)

**Fixed:** `libretrodroid/src/main/cpp/oboe` and
`libretrodroid/src/main/cpp/libretro/libretro-common` were empty — they're
git submodules, and this checkout came from a GitHub zip download (not a
real git clone), so submodule content was never fetched. `.gitmodules`
listed `branch = 1.5-stable` for oboe, but that's stale/unreliable (a
branch pointer, not the actual pinned commit); traced the real pinned
commits from `swordfish90/LibretroDroid`'s git tree instead
(`oboe` → `1.9.3` tag, `libretro-common` → `b0c348e`) and fetched each repo
at that exact commit into place. Confirms `oboe`'s public header layout
changed between 1.5-stable and 1.9.3 — `FifoBuffer.h` moved from
`src/fifo/` into the public `include/oboe/` directory, which is what
`audio.h`'s `#include <oboe/FifoBuffer.h>` actually needs.

**Verified on hardware:** `./gradlew.bat :app:installDebug` builds and
installs on a real device (Samsung SM-S938B, adb serial `RZCY12QN78V`).
Launched via `adb shell am start -n com.android.gl2jni/com.tacoboy.TacoBoyActivity`:
- No crash; `TacoBoyActivity` is the resumed/focused activity.
- Screenshot confirms `boundary_handle` renders at the correct position for
  the default 65% split.
- Expected (non-bug) error in logcat: `libretrodroid: Error in
  loadGameFromPath: std::bad_alloc` — the hardcoded sample ROM path
  (`/data/data/com.android.gl2jni/files/example.gba`) doesn't exist on
  device since no ROM has been pushed yet. `GLRetroView` caught it via its
  own error flow without crashing the app.
**Verified:** Dragged `boundary_handle` by hand on device — "smooth enough
to be functional" (user's words). Confirmed persistence end-to-end:
`shared_prefs/tacoboy_prefs.xml` recorded `boundary_percent=0.52652615`
after the drag, and a `force-stop` + relaunch restored the guideline to
that position (screenshot comparison, handle visibly higher up than the
0.65 default). Phase 0's boundary-handle exit criteria are met.

**Verified with a real ROM:** pushed a user-owned GBA ROM ("Final Fantasy
I & II - Dawn of Souls (USA).gba") to the hardcoded sample path via
`adb push` + `run-as ... cp` into `/data/data/com.android.gl2jni/files/example.gba`
(remember the `//` double-slash prefix on Windows/Git-Bash adb commands —
a single leading `/` gets MSYS-path-converted into a garbage Windows path
like `C:/Program Files/Git/data/...` and silently breaks the command).
Relaunched: the Square Enix boot logo renders correctly, fully contained
within the top region above `boundary_handle`, with the occlusion zone
below staying solid black. No errors in logcat. This confirms the core
mechanism end-to-end — Phase 0 is now fully verified.

**New finding — 16KB page size compatibility warning:** launching the app
triggers a Samsung system dialog ("Android app compatibility... This app
isn't 16 KB-compatible. ELF alignment check failed"), listing every native
`.so` including the prebuilt cores (`libmgba`, `gambatte`, `snes9x`,
`handy`) AND our own freshly-built `liblibretrodroid.so` — despite
`CMakeLists.txt` already setting
`target_link_options(libretrodroid PRIVATE "-Wl,-z,max-page-size=16384")`.
Only appears on debuggable builds, not currently blocking anything, but
Google requires 16KB-page alignment for Play Store apps targeting newer API
levels — worth revisiting before any release build (Phase 4). The prebuilt
core `.so` files would need to be rebuilt 16KB-aligned too, not just our
own library.

**Note:** `local.properties` now also implies an NDK was auto-provisioned
by Gradle (`ndk;26.1.10909125`) and SDK Platform 33, both installed to the
existing local SDK at `C:/Users/dcrot/AppData/Local/Android/Sdk`.

## 2026-08-14 (roadmap redesign)

**Changed:** `roadmap.md` was rewritten from scratch (phases, exit criteria,
per-system core recommendations, default core table). No code changes in
this entry — logging it here so a future session knows the roadmap
structure changed and why. Confirmed with the user: Pocket Taco is
hand-held in portrait; landscape was never viable since the controller
isn't tablet-sized.

## Template for new entries

```
## YYYY-MM-DD

**Added/Changed/Fixed:** ...
**Verified:** how it was tested (compiled only / ran on emulator / ran on
device / ran with real Pocket Taco).
### Open questions
- ...
```
