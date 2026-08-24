"""TacoBoy headroom profiling (roadmap 3.3, second measurement).

The 60fps table in PROFILING.md cannot distinguish these configs, because every one of them
is pinned by vsync. This removes the cap instead: `frameSpeed = N` steps the core N times per
rendered frame, so a config that still holds 60fps is doing N x real-time work, and one that
falls to 40 is doing N * 40/60. That ratio is the headroom number roadmap 3.3 wanted before
any more per-frame passes get added.

Requires an APK built with FAST_FORWARD_SPEED set to SPEED below (it is a compile-time
constant; the harness asserts the running value from the app's log rather than trusting it).

PS1 uses Army Men - Air Attack rather than CTR: CTR's intro and menus are largely pre-rendered
FMV, so it measures video playback rather than emulation. Air Attack is real-time 3D
throughout, which is the workload that actually matters here.
"""

import csv
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import profile2 as base

SPEED = int(os.environ.get("TACOBOY_FF_SPEED", "4"))
FF_KEYCODE = 110  # KEYCODE_BUTTON_MODE -- not a default source for any RetroPad target.
SETTLE_S = 26
SAMPLE_S = 30

# Every config must start from a comparable thermal state or the run measures the order it
# was run in. Found the hard way: the same config read >=4x headroom from 38.6C and 3.25x
# from 48.6C. The gate waits for the phone to come back down with the app stopped.
COOL_TO_C = 42.0
COOL_MAX_WAIT_S = 240

GBA = base.GBA
PS1 = ("content://com.android.externalstorage.documents/tree/primary%3AEmulation%2FPS1/"
       "document/primary%3AEmulation%2FPS1%2FArmy%20Men%20-%20Air%20Attack%20(USA).chd")

CONFIGS = [
    dict(name="GBA/mgba/default",        sys="GBA", rom=GBA, shader="default",  core=None,        expect_core=base.MGBA),
    dict(name="GBA/mgba/upscale3",       sys="GBA", rom=GBA, shader="upscale3", core=None,        expect_core=base.MGBA),
    dict(name="PS1/swanstation/default", sys="PS1", rom=PS1, shader="default",  core=base.SWAN,   expect_core=base.SWAN),
    dict(name="PS1/swanstation/upscale3",sys="PS1", rom=PS1, shader="upscale3", core=base.SWAN,   expect_core=base.SWAN),
    dict(name="PS1/beetle/default",      sys="PS1", rom=PS1, shader="default",  core=base.BEETLE, expect_core=base.BEETLE),
    dict(name="PS1/beetle/upscale3",     sys="PS1", rom=PS1, shader="upscale3", core=base.BEETLE, expect_core=base.BEETLE),
    dict(name="GBA/mgba/default (control)", sys="GBA", rom=GBA, shader="default", core=None,      expect_core=base.MGBA),
]

FF_LOG = re.compile(r"Fast-forward (engaged|released) speed=(\d+)")


def set_config(cfg):
    """base.set_config plus the fast-forward binding the harness needs to engage it."""
    text = base.read_prefs()
    if "<map" not in text:
        raise RuntimeError("prefs unreadable")

    entries = {
        "last_rom_uri": ("string", cfg["rom"]),
        "resume_on_launch": ("boolean", "true"),
        "show_fps": ("boolean", "true"),
        "shader_choice_" + cfg["sys"]: ("string", cfg["shader"]),
        "fast_forward_mode": ("string", "TOGGLE"),
        "fast_forward_keycode": ("int", str(FF_KEYCODE)),
    }
    if cfg["core"]:
        entries["core_choice_PS1"] = ("string", cfg["core"])

    for junk in ("load_in_progress", "crash_streak"):
        text = base.drop_key(text, junk)
    for key in entries:
        text = base.drop_key(text, key)

    new = []
    for key, (kind, value) in entries.items():
        if kind == "string":
            new.append('    <string name="%s">%s</string>' % (key, value))
        elif kind == "int":
            new.append('    <int name="%s" value="%s" />' % (key, value))
        else:
            new.append('    <boolean name="%s" value="%s" />' % (key, value))
    text = text.replace("<map>", "<map>\n" + "\n".join(new), 1)
    base.write_prefs(text)

    back = base.read_prefs()
    for key in entries:
        if back.count('name="%s"' % key) != 1:
            raise RuntimeError("key %s appears %d times" % (key, back.count('name="%s"' % key)))


def cool_down():
    """Idle until the phone is back under COOL_TO_C, or we run out of patience."""
    waited = 0
    while waited < COOL_MAX_WAIT_S:
        t = base.temperature()
        if t is None or t <= COOL_TO_C:
            return t, waited
        time.sleep(15)
        waited += 15
    return base.temperature(), waited


def run(cfg):
    print("=== %s ===" % cfg["name"], flush=True)
    base.shell("am force-stop %s" % base.PKG)
    t_cool, waited = cool_down()
    print("  cooled to %sC after %ds" % (t_cool, waited), flush=True)
    time.sleep(2)
    set_config(cfg)
    base.shell("am start -n %s/com.tacoboy.TacoBoyActivity" % base.PKG)
    time.sleep(SETTLE_S)

    base.adb("logcat", "-c")
    time.sleep(3)
    seen = base.perf_lines()
    if not seen:
        print("  FAIL: no perf output", flush=True)
        return None
    sysname, core, shader, _, _ = seen[-1].groups()
    if sysname != cfg["sys"] or core != cfg["expect_core"] or shader != cfg["shader"].upper():
        print("  FAIL: running %s/%s/%s" % (sysname, core, shader), flush=True)
        return None

    # Baseline at 1x, from the same scene we are about to fast-forward, so the ratio below is
    # against this config's own uncapped-at-60 behaviour rather than an assumed 60.
    base_fps = [int(m.group(4)) for m in seen]
    baseline = round(sum(base_fps) / len(base_fps), 1)

    base.adb("logcat", "-c")
    base.shell("input keyevent %d" % FF_KEYCODE)
    time.sleep(3)

    ff = [FF_LOG.search(l) for l in base.adb("logcat", "-d", "-s", "TacoBoy.Activity").splitlines()]
    ff = [m for m in ff if m]
    if not ff or ff[-1].group(1) != "engaged":
        print("  FAIL: fast-forward did not engage", flush=True)
        return None
    speed = int(ff[-1].group(2))
    if speed != SPEED:
        print("  FAIL: app is running speed=%d, harness expects %d" % (speed, SPEED), flush=True)
        return None
    print("  verified %s / %s / %s at frameSpeed=%d (1x baseline %s fps)"
          % (sysname, core, shader, speed, baseline), flush=True)

    t0 = base.temperature()
    base.adb("logcat", "-c")
    _, cpu0 = base.cpu_ticks()
    time.sleep(SAMPLE_S)
    _, cpu1 = base.cpu_ticks()
    t1 = base.temperature()

    samples = base.perf_lines()
    fps = [int(m.group(4)) for m in samples]
    if len(fps) > 4:
        fps = fps[1:-1]
    if not fps:
        print("  FAIL: no samples", flush=True)
        return None

    avg = sum(fps) / len(fps)
    headroom = round(SPEED * avg / 60.0, 2)
    row = dict(
        config=cfg["name"], speed=SPEED, samples=len(fps),
        baseline_fps=baseline, ff_avg_fps=round(avg, 1), ff_min_fps=min(fps),
        headroom_x=headroom,
        cpu_pct_of_one_core=round((cpu1 - cpu0) / SAMPLE_S, 1) if (cpu0 and cpu1) else None,
        temp_start_c=t0, temp_end_c=t1,
    )
    print("  " + "  ".join("%s=%s" % (k, v) for k, v in row.items() if k != "config"), flush=True)
    return row


def main():
    print("Headroom run at frameSpeed=%d" % SPEED, flush=True)
    rows = []
    for cfg in CONFIGS:
        try:
            row = run(cfg)
        except Exception as e:
            print("  ERROR: %s" % e, flush=True)
            row = None
        if row:
            rows.append(row)
    out = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                       "headroom_results_%dx.csv" % SPEED)
    if rows:
        with open(out, "w", newline="", encoding="utf-8") as f:
            w = csv.DictWriter(f, fieldnames=list(rows[0].keys()))
            w.writeheader()
            w.writerows(rows)
    print("WROTE %s (%d/%d)" % (out, len(rows), len(CONFIGS)), flush=True)


if __name__ == "__main__":
    sys.exit(main())
