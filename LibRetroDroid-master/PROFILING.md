# TacoBoy performance profiling

Roadmap 3.3. Measured 2026-08-24 on a Galaxy S25 Ultra (SM-S938B), debug build. Two runs:
a 60fps-capped comparison, and a headroom run with the cap removed.

Read the **Limitations** section before quoting any number here. One of them is serious.

## Method

The app logs one line per second while Settings > General > Show FPS is on:

```
perf system=PS1 core=swanstation_libretro_android.so shader=DEFAULT fps=60 worstFrameMs=17.1
```

`worstFrameMs` is the longest gap between two rendered frames in that second, not an
average — an average of 60 is equally consistent with a steady 60fps and with 70 quick
frames plus a 200ms stall, and only the second is something you feel.

`tools-profile.py` (in this directory) drives one config at a time: force-stop, rewrite the prefs file,
launch, wait 26s to settle, then sample for 45s while reading the app's CPU time from
`/proc/<pid>/stat` (utime+stime, reported as percent of one core). Battery temperature and
`thermal_zone0` are recorded either side of each window.

**Every config is verified before it is measured.** The harness reads back the perf line and
refuses to record anything unless the running system, core and shader are the ones it asked
for. This is not defensive decoration: the first attempt at this run silently measured the
same GBA config nine times and produced a beautiful, entirely meaningless table (see
History). A config that cannot be verified is reported as failed.

A repeat of the first config runs last as a drift control.

## Results

| config | avg fps | min fps | worst frame | p90 worst | CPU (% of one core) |
|---|---|---|---|---|---|
| GBA / mgba / default | 59.8 | 59 | 23.0ms | 19.5ms | 31.1 |
| GBA / mgba / LCD | 59.8 | 59 | 22.9ms | 20.2ms | 31.0 |
| GBA / mgba / Upscale3 | 59.9 | 59 | 23.2ms | 19.5ms | 31.8 |
| PS1 / SwanStation / default | 60.0 | 59 | 20.6ms | 18.9ms | 35.6 |
| PS1 / SwanStation / CRT | 59.9 | 59 | 20.1ms | 18.6ms | 35.6 |
| PS1 / SwanStation / Upscale3 | 59.9 | 59 | 19.7ms | 18.8ms | 28.0 |
| PS1 / Beetle PSX HW / default | 60.0 | 60 | 21.8ms | 20.2ms | 65.4 |
| PS1 / Beetle PSX HW / Upscale3 | 60.0 | 60 | 22.7ms | 19.7ms | 65.2 |
| GBA / mgba / default (control) | 59.9 | 59 | 23.1ms | 20.1ms | 31.0 |

Temperatures stayed between 32.4°C and 35.9°C for the whole 12-minute run, and the control
matched its original (31.0 vs 31.1). Nothing here is thermally limited, and the table can be
read top to bottom without correcting for drift.

## What this says

**Beetle PSX HW costs about twice the CPU of SwanStation** — 65% of a core against 28–36%.
This is the one large, unambiguous result: the gap is far bigger than any noise in the run,
and it holds with and without a shader. It is the number to weigh against Beetle's
[better colour handling](CHANGELOG.md) when choosing a PS1 core, and the reason to expect
Beetle to be the first thing that struggles on a weaker phone.

**Shaders are free at this resolution.** LCD costs 31.0 against a 31.1 baseline; Upscale3
costs 31.8; CRT is identical to no shader. On Beetle, Upscale3 changes 65.4 to 65.2 — i.e.
nothing. These run on the GPU, and at this output size the GPU is not the constraint.
Upscale3 being a three-pass shader does not change that.

**Everything holds a locked 60.** No config dropped below 59 average or 59 minimum, and
Beetle held a flat 60.0/60 — so on this device, frame rate is not currently a way to tell
these configs apart. Frame *pacing* shows the usual occasional missed vsync: a worst frame
of 20–23ms against a 16.7ms budget is one late frame somewhere in each second, consistent
across every config including the lightest, which points at the display/scheduling path
rather than at any core or shader.

**Practical upshot: on this phone the shader choice is a free aesthetic decision, and the
PS1 core choice is not.**

## Headroom (second measurement, same day)

The table above cannot separate these configs because every one is pinned at 60 by vsync.
This run removes that cap: `frameSpeed = N` steps the core N times per rendered frame, so a
config still holding 60fps is sustaining N x real-time, and one that falls to 25 is
sustaining N * 25/60. Harness: `tools-profile-headroom.py`, run at N=8, 30s per config,
Army Men - Air Attack for PS1 (see below).

| config | fps at 8x | headroom | CPU (% of one core) |
|---|---|---|---|
| GBA / mgba / default | 59.8 | >=8x | 71.1 |
| GBA / mgba / Upscale3 | 59.8 | >=8x | 72.6 |
| PS1 / SwanStation / default | 59.9 | >=8x | 127.1 |
| PS1 / SwanStation / Upscale3 | 59.9 | >=8x | 124.9 |
| PS1 / Beetle PSX HW / default | 25.9 | **3.46x** | 122.4 |
| PS1 / Beetle PSX HW / Upscale3 | 25.0 | **3.34x** | 121.7 |
| GBA / mgba / default (control) | 59.8 | >=8x | 72.9 |

`>=8x` means the config never dropped a frame at the highest speed tested — its real ceiling
is somewhere above 8x and this run does not find it.

**Beetle stops scaling at about 3.4x; SwanStation does not stop below 8x.** The two draw
almost identical CPU at 8x (127% vs 122%) — but SwanStation converts that into eight times
real-time and Beetle into three and a half. Beetle is doing roughly 2.3x less work per unit
of CPU, which agrees with the 1.9x ratio measured at 1x by a completely different method.
Beetle's CPU pinning at ~122% while its throughput stalls is the signature of one saturated
thread rather than a machine-wide limit.

**There is room for another per-frame pass.** That was the question roadmap 3.3 wanted
answered before more shader work. Even the heaviest core sustains 3.4x real-time, and
Upscale3 costs 3% of that (3.46 -> 3.34) and ~1.5 CPU points on GBA (71.1 -> 72.6) *at eight
times normal speed*. A fourth pass is affordable on this device.

**Thermal state moves the numbers more than any config choice does.** The same Beetle config
measured >=4x from 38.6°C and 3.25x from 48.6°C. Every config here is therefore gated on
cooling the phone to 42°C first, with start and end temperatures recorded. Note that Beetle
still enters its own window at 46-47°C, because loading and settling that core heats the
phone before measurement begins — so part of Beetle's 3.4x is thermal, and its cold-burst
ceiling is higher. GBA and SwanStation never exceeded 38.6°C at 8x; Beetle reached 52.5°C.

**This also makes the frameSpeed=2 fast-forward feature look conservative.** Everything
except Beetle could run considerably faster than 2x.

## Limitations

**The PS1 shader comparison is not valid, and the SwanStation/Upscale3 row is the proof.**
It reads 28.0 against 35.6 for the same core with *less* work to do, which is not a real
effect: CTR boots into an attract sequence, so each PS1 run samples whatever scene happened
to be playing 26 seconds in — an FMV and a 3D demo are different workloads. The GBA rows sit
on a static title screen and are directly comparable; the PS1 rows are only comparable where
the difference is large enough to swamp scene variance, which is true of Beetle vs
SwanStation (2x) and not of any shader comparison within one core. **Fix before re-running:
load a save state at a fixed point instead of relying on elapsed time.**

**No battery figures.** The phone was on USB power throughout — `dumpsys battery` reports
`usb:true`, and current readings while charging measure the charger. CPU time is used as a
plugged-in-safe proxy. Real draw needs the phone off charge, which means wireless adb
(`adb tcpip 5555`, connect over WiFi, unplug) and a longer run per config.

**CPU only.** GPU utilisation is not measured, which matters precisely because the shader
results say the work is on the GPU. A shader that costs nothing in CPU terms is not thereby
free.

**One device, one game per system, one debug build.** A debug build is not release, and the
S25 Ultra is nearly the best case available.

## Worth measuring next

- ~~Headroom~~ — **done**, see the Headroom section above.
- **Beetle's cold ceiling.** Its 3.4x is partly thermal, since it enters its own measurement
  window at 46-47°C no matter how long the phone cools first. Measuring it from a genuinely
  cold start (longer cooldown, shorter settle, or a save state so the load phase is brief)
  would separate "Beetle is expensive" from "Beetle heats the phone".
- Save-state-pinned scenes, to make the PS1 rows comparable.
- Battery over wireless adb.
- A second, weaker device — where these differences start to matter.

## History

- **2026-08-24, first attempt: discarded.** Its prefs edit ran `sed` through two layers of
  shell quoting; the delete half was mangled, so new values were inserted *above* the
  surviving old ones, and SharedPreferences resolves a duplicated key to its last
  occurrence. Every config silently fell back to the same saved GBA settings. The tell was
  visible in the results — nine configs within 1.5% of each other — and was spotted by the
  user noticing only GBA games appeared on screen. The verification step above exists
  because of this, and the perf line carries system/core/shader for the same reason.
- **2026-08-24, second attempt: the table above.** Prefs edited in Python and pushed whole;
  every config verified against the running app before measurement; 9/9 verified.
