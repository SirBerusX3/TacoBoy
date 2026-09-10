"""TacoBoy performance profiling harness, second attempt (roadmap 3.3).

The first attempt silently measured the same GBA config eight times: its prefs edit ran
sed through two layers of shell quoting, the delete half was mangled, and SharedPreferences
resolves a duplicated key to its LAST occurrence -- so every "new" value sat above the old
one and lost. Two changes make that failure impossible rather than unlikely:

  1. The prefs file is edited HERE, in Python, and pushed back whole. No nested quoting, and
     duplicate keys are removed explicitly rather than hopefully.
  2. Nothing is measured until the app's own perf log line confirms the system, core and
     shader actually running are the ones asked for. A config that cannot be verified is
     reported as failed, not quietly recorded.
"""

import csv
import os
import re
import subprocess
import sys
import time

ADB = os.path.expandvars(r"%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe")
PKG = "com.tacoboy"
PREFS = "shared_prefs/tacoboy_prefs.xml"
HERE = os.path.dirname(os.path.abspath(__file__))

SETTLE_S = 26
SAMPLE_S = 45

GBA = ("content://com.android.externalstorage.documents/tree/primary%3AEmulation%2FGBA/"
       "document/primary%3AEmulation%2FGBA%2FPokemon - Emerald Version (USA%2C Europe)"
       "%2FPokemon - Emerald Version (USA%2C Europe).gba")
PS1 = ("content://com.android.externalstorage.documents/tree/primary%3AEmulation%2FPS1/"
       "document/primary%3AEmulation%2FPS1%2FCTR - Crash Team Racing (USA).chd")

SWAN = "swanstation_libretro_android.so"
BEETLE = "mednafen_psx_hw_libretro_android.so"
MGBA = "libmgba_libretro_android.so"

CONFIGS = [
    dict(name="GBA/mgba/default",        sys="GBA", rom=GBA, shader="default",  core=None,   expect_core=MGBA),
    dict(name="GBA/mgba/lcd",            sys="GBA", rom=GBA, shader="lcd",      core=None,   expect_core=MGBA),
    dict(name="GBA/mgba/upscale3",       sys="GBA", rom=GBA, shader="upscale3", core=None,   expect_core=MGBA),
    dict(name="PS1/swanstation/default", sys="PS1", rom=PS1, shader="default",  core=SWAN,   expect_core=SWAN),
    dict(name="PS1/swanstation/crt",     sys="PS1", rom=PS1, shader="crt",      core=SWAN,   expect_core=SWAN),
    dict(name="PS1/swanstation/upscale3",sys="PS1", rom=PS1, shader="upscale3", core=SWAN,   expect_core=SWAN),
    dict(name="PS1/beetle/default",      sys="PS1", rom=PS1, shader="default",  core=BEETLE, expect_core=BEETLE),
    dict(name="PS1/beetle/upscale3",     sys="PS1", rom=PS1, shader="upscale3", core=BEETLE, expect_core=BEETLE),
    # Control: a repeat of the first config, last. If it does not match its own earlier
    # result, the run drifted (thermal throttling is the obvious suspect) and the middle
    # of the table cannot be compared against the ends.
    dict(name="GBA/mgba/default (control)", sys="GBA", rom=GBA, shader="default", core=None, expect_core=MGBA),
]


def adb(*args, **kw):
    return subprocess.run([ADB] + list(args), capture_output=True, text=True,
                          errors="replace", **kw).stdout


def shell(cmd):
    return adb("shell", cmd)


def read_prefs():
    return adb("shell", "run-as %s cat %s" % (PKG, PREFS))


def write_prefs(text):
    local = os.path.join(HERE, "prefs_push.xml")
    with open(local, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)
    adb("push", local, "/data/local/tmp/prefs_push.xml")
    shell("chmod 644 /data/local/tmp/prefs_push.xml")
    shell("run-as %s cp /data/local/tmp/prefs_push.xml %s" % (PKG, PREFS))


def drop_key(text, key):
    """Remove every element with this name, wherever it sits and however it is laid out."""
    pattern = re.compile(
        r'<(string|boolean|int|long|float)\s+name="%s"(?:\s[^>]*)?(?:/>|>.*?</\1>)'
        % re.escape(key), re.DOTALL)
    return pattern.sub("", text)


def set_config(cfg):
    text = read_prefs()
    if "<map" not in text:
        raise RuntimeError("prefs unreadable: %r" % text[:200])

    entries = {
        "last_rom_uri": ("string", cfg["rom"]),
        "resume_on_launch": ("boolean", "true"),
        "show_fps": ("boolean", "true"),
        "shader_choice_" + cfg["sys"]: ("string", cfg["shader"]),
    }
    if cfg["core"]:
        entries["core_choice_PS1"] = ("string", cfg["core"])
    # Never let a stale crash flag send us to the picker mid-run.
    for junk in ("load_in_progress", "crash_streak"):
        text = drop_key(text, junk)

    for key in entries:
        text = drop_key(text, key)

    new = []
    for key, (kind, value) in entries.items():
        if kind == "string":
            new.append('    <string name="%s">%s</string>' % (key, value))
        else:
            new.append('    <boolean name="%s" value="%s" />' % (key, value))
    text = text.replace("<map>", "<map>\n" + "\n".join(new), 1)

    write_prefs(text)

    # Prove it landed, and that nothing is duplicated, before anything is launched.
    back = read_prefs()
    for key in entries:
        if back.count('name="%s"' % key) != 1:
            raise RuntimeError("key %s appears %d times after write"
                               % (key, back.count('name="%s"' % key)))
    return True


def cpu_ticks():
    pid = shell("pidof %s" % PKG).strip().split()
    if not pid:
        return None, None
    pid = pid[0]
    stat = shell("cat /proc/%s/stat" % pid)
    if ")" not in stat:
        return pid, None
    fields = stat.rsplit(")", 1)[1].split()
    return pid, int(fields[11]) + int(fields[12])


PERF = re.compile(r"perf system=(\S+) core=(\S+) shader=(\S+) fps=(\d+) worstFrameMs=([\d.]+)")


def perf_lines():
    out = adb("logcat", "-d", "-s", "TacoBoy.Activity")
    return [m for m in (PERF.search(l) for l in out.splitlines()) if m]


def temperature():
    raw = shell("cat /sys/class/thermal/thermal_zone0/temp").strip()
    try:
        return round(int(raw) / 1000.0, 1)
    except Exception:
        return None


def run(cfg):
    print("=== %s ===" % cfg["name"], flush=True)
    shell("am force-stop %s" % PKG)
    time.sleep(2)
    set_config(cfg)
    shell("am start -n %s/com.tacoboy.TacoBoyActivity" % PKG)
    time.sleep(SETTLE_S)

    adb("logcat", "-c")
    time.sleep(3)
    seen = perf_lines()
    if not seen:
        print("  FAIL: no perf output -- did the ROM load?", flush=True)
        return None
    sysname, core, shader, _, _ = seen[-1].groups()
    want_shader = cfg["shader"].upper()
    if sysname != cfg["sys"] or core != cfg["expect_core"] or shader != want_shader:
        print("  FAIL: running %s/%s/%s, wanted %s/%s/%s"
              % (sysname, core, shader, cfg["sys"], cfg["expect_core"], want_shader), flush=True)
        return None
    print("  verified running %s / %s / %s" % (sysname, core, shader), flush=True)

    t0 = temperature()
    adb("logcat", "-c")
    pid, cpu0 = cpu_ticks()
    time.sleep(SAMPLE_S)
    _, cpu1 = cpu_ticks()
    t1 = temperature()

    samples = perf_lines()
    fps = [int(m.group(4)) for m in samples]
    worst = [float(m.group(5)) for m in samples]
    if len(fps) > 4:
        fps, worst = fps[1:-1], worst[1:-1]
    if not fps:
        print("  FAIL: no samples in window", flush=True)
        return None

    cpu_pct = round((cpu1 - cpu0) / SAMPLE_S, 1) if (cpu0 and cpu1) else None
    row = dict(
        config=cfg["name"], samples=len(fps),
        avg_fps=round(sum(fps) / len(fps), 1), min_fps=min(fps),
        worst_frame_ms=round(max(worst), 1),
        p90_worst_ms=round(sorted(worst)[int(len(worst) * 0.9)], 1),
        cpu_pct_of_one_core=cpu_pct, temp_start_c=t0, temp_end_c=t1,
    )
    print("  " + "  ".join("%s=%s" % (k, v) for k, v in row.items() if k != "config"), flush=True)
    return row


def main():
    rows = []
    for cfg in CONFIGS:
        try:
            row = run(cfg)
        except Exception as e:
            print("  ERROR: %s" % e, flush=True)
            row = None
        if row:
            rows.append(row)
    out = os.path.join(HERE, "profile_results.csv")
    if rows:
        with open(out, "w", newline="", encoding="utf-8") as f:
            w = csv.DictWriter(f, fieldnames=list(rows[0].keys()))
            w.writeheader()
            w.writerows(rows)
    print("WROTE %s (%d/%d configs)" % (out, len(rows), len(CONFIGS)), flush=True)


if __name__ == "__main__":
    sys.exit(main())
