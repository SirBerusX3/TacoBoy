# TacoBoy

A multi-system emulator frontend for Android, built on
[LibretroDroid](https://github.com/Swordfish90/LibretroDroid) and libretro cores.

This emulator is specifically made for the GameSir Pocket Taco clamp-on controller.
As it obscures the lower part of your display, this emulator features an adjustable
boundary layer that allows you to control the visibility of the app and helps prevent
the screen from staying on, thereby mitigating screen-burn in, reducing power usage and
reducing the risk of ghost touches occurring beneath the controller.

There are now also on-screen controls, so the app can still be used without the Pocket Taco.

Ten systems, one library screen, box art, per-system display shaders, a repositionable
on-screen pad, and RetroAchievements lookup. Everything is local — ROMs come from a
folder you grant access to, and nothing is uploaded anywhere.

Status: **pre-release** (`versionName 0.2.0`). Built and tested on arm64 devices only.

## Supported systems

Ten systems across seven core binaries. A system is listed here only if its core `.so`
actually ships in `jniLibs` — nothing is half-wired.

| System | Short | Core | BIOS |
|---|---|---|---|
| Game Boy | GB | Gambatte | — |
| Game Boy Color | GBC | Gambatte | — |
| Game Boy Advance | GBA | mGBA | — |
| Super Nintendo | SNES | Snes9x | — |
| Mega Drive / Genesis | GEN | Genesis Plus GX | — |
| Master System | SMS | Genesis Plus GX | — |
| Game Gear | GG | Genesis Plus GX | — |
| SG-1000 | SG | Genesis Plus GX | — |
| Atari Lynx | LYNX | Handy | optional |
| PlayStation | PS1 | SwanStation *or* Beetle PSX HW | **required** |

PS1 is the only system with a choice of core, switchable in Settings. Lynx runs without
its boot ROM (Handy falls back to an internal HLE one); PS1 genuinely cannot boot without
a real BIOS, and the library screen distinguishes those two cases rather than showing one
generic warning. See [BIOSregion.md](BIOSregion.md) for the PS1 filename-to-region map.

## What it does

- **Library** — grant a ROM folder, get a grid with box art pulled from libretro's
  thumbnail server and cached locally. CHD, ISO and the usual cartridge formats.
- **On-screen controls** — drag-to-reposition editor, per-system button sets (a Game Boy
  layout has no X/Y or shoulders, because real hardware doesn't), L3/R3, haptics with
  adjustable strength.
- **Physical controllers** — remappable bindings with presets, right analog stick,
  turbo / rapid fire.
- **Display** — per-system shaders (CRT, LCD, CUT upscalers 1–3), integer scaling,
  hardware-rendered PS1 via the core's declared maximum FBO.
- **Core options** — every option exposed is documented in the UI rather than left as a
  bare libretro string, and options found to be inert were removed rather than shown.
- **Save states**, fast-forward with a toggle mode, and a full in-game reset that actually
  reloads the core.
- **RetroAchievements** — game and achievement lookup with progress. Softcore only; see
  the compliance audit below.

## Relationship to LibretroDroid

TacoBoy is a **derivative of LibretroDroid**, which is Swordfish90's work and the reason
this project exists at all. The `libretrodroid` module here is that library, vendored and
modified; `app/` is TacoBoy and is ours.

Upstream is a low-velocity project; its master has not moved since `8835c30`
(2026-05-24), which was already current when TacoBoy started on 2026-08-24. The policy is
to cherry-pick individual upstream commits with a reason, not to track continuously,
because the divergence sits *inside* the module and every sync would cost a manual
three-way merge.

Full detail, including which upstream commits are worth taking and what each would cost:
**[UPSTREAM.md](LibRetroDroid-master/UPSTREAM.md)**. Regenerate it with
`tools-upstream-base.py` and `tools-upstream-report.py`.

## Building

Requires the Android SDK, a JDK, and the NDK/CMake toolchain for the native module.

| | Version | Notes |
|---|---|---|
| JDK | **17 or 21** | Gradle 8.10.2 does **not** run on JDK 24+ |
| Gradle | 8.10.2 | via the wrapper |
| AGP | 8.4.0 | |
| Kotlin | 2.0.21 | |
| compileSdk | 34 (app) / 33 (module) | `buildToolsVersion 34.0.0` |
| minSdk | 21 | targetSdk 33 |
| NDK | 26.1.10909125 | AGP 8.4.0's default; not pinned in the build |
| CMake | 3.22.1 | pinned in `libretrodroid/build.gradle` |
| ABI | `arm64-v8a` only | deliberate — cuts the native build to a quarter |

```bash
git clone https://github.com/SirBerusX3/TacoBoy.git
cd TacoBoy/LibRetroDroid-master
./gradlew :app:assembleDebug
```

AGP will download the NDK, CMake and the missing platforms on the first build. Android
Studio's setup wizard installs only the newest SDK, which is *not* what this project
pins, so let Gradle fetch the rest rather than assuming the wizard covered it.

### local.properties

Not in git — it holds this machine's SDK path. Android Studio writes it on first open, or
create it yourself:

```properties
sdk.dir=C:/Users/<you>/AppData/Local/Android/Sdk
```

### If your JAVA_HOME points at an unsupported JDK

Put the working one in `~/.gradle/gradle.properties` rather than the project file, since
the path is true only for your machine:

```properties
org.gradle.java.home=C:/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot
```

### Signing

`assembleRelease` is signed only if `keystore.properties` exists in
`LibRetroDroid-master/`. Without it the build still succeeds and still produces a working
**debug** APK — a clone must never be unable to build just because it lacks a private key.

The keystore lives outside the repository and `*.jks`, `*.keystore` and
`keystore.properties` are all gitignored, so an accidental copy landing in the tree cannot
be committed. To sign your own builds:

```properties
storeFile=/path/to/your.jks
storePassword=...
keyAlias=...
keyPassword=...
```

Debug and release are signed with the same key on purpose. Android refuses to update an
install signed with a different key, so mismatched signing turns every debug/release swap
into an uninstall — taking ROM folder grants, BIOS files and save states with it.

**Back the keystore up somewhere off-machine.** The original was destroyed by a Windows
reinstall on 2026-09-10 and had to be regenerated; there is no recovery for a lost signing
key, only a reinstall for every user.

## Cores

The seven `.so` files in `app/src/main/jniLibs/arm64-v8a/` are committed deliberately.
They are not built from this repository and cannot be regenerated from it, so a clone
without them cannot produce a working app. `core-backups/` keeps previous builds for the
same reason — the 2026-08-16 Beetle PSX HW build in particular is not otherwise
recoverable.

## Documentation

- **[CHANGELOG.md](LibRetroDroid-master/CHANGELOG.md)** — the real development record.
  Every change, why it was made, and what was ruled out.
- **[UPSTREAM.md](LibRetroDroid-master/UPSTREAM.md)** — fork relationship and sync policy.
- **[RETROACHIEVEMENTS-COMPLIANCE.md](RETROACHIEVEMENTS-COMPLIANCE.md)** — audit against
  RetroAchievements' hardcore rules. The headline finding: unlocks are submitted softcore
  only, by design, and hardcore is further away than the rule list suggests.
- **[BIOSregion.md](BIOSregion.md)** — PS1 BIOS filenames mapped to regions.

## Licence

**GPL-3.0**, inherited from LibretroDroid — see [LICENSE](LibRetroDroid-master/LICENSE).

The bundled cores carry their own licences, listed in the app's About tab. Note that
Snes9x and Genesis Plus GX are **non-commercial**, which is why there is no donation link
inside the app.
