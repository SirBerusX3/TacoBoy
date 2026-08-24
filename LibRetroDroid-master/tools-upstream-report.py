"""For each file this fork has modified, report what upstream has done to it since.

Splits the 29 modified files into three buckets that mean very different things:
  - ours alone      : does not exist upstream at all, so nothing can conflict
  - quiet upstream  : exists upstream but untouched recently -- our changes stand
  - upstream moved  : exists upstream AND has upstream commits we do not have,
                      which is the entire cost of ever syncing
"""

import subprocess

REPO = r"C:\Users\dcrot\Documents\TacoBoy"
SINCE = "2025-06-01"

MODIFIED = """libretrodroid/src/main/cpp/CMakeLists.txt
libretrodroid/src/main/cpp/achievements.cpp
libretrodroid/src/main/cpp/achievements.h
libretrodroid/src/main/cpp/achievementsmemory.cpp
libretrodroid/src/main/cpp/achievementsmemory.h
libretrodroid/src/main/cpp/environment.cpp
libretrodroid/src/main/cpp/environment.h
libretrodroid/src/main/cpp/immersivemode.cpp
libretrodroid/src/main/cpp/immersivemode.h
libretrodroid/src/main/cpp/libretrodroid.cpp
libretrodroid/src/main/cpp/libretrodroid.h
libretrodroid/src/main/cpp/libretrodroidjni.cpp
libretrodroid/src/main/cpp/libretrodroidjni.h
libretrodroid/src/main/cpp/renderers/es3/es3utils.cpp
libretrodroid/src/main/cpp/renderers/es3/es3utils.h
libretrodroid/src/main/cpp/renderers/es3/framebufferrenderer.cpp
libretrodroid/src/main/cpp/renderers/es3/framebufferrenderer.h
libretrodroid/src/main/cpp/renderers/es3/imagerendereres3.cpp
libretrodroid/src/main/cpp/renderers/renderer.h
libretrodroid/src/main/cpp/shadermanager.cpp
libretrodroid/src/main/cpp/utils/utils.cpp
libretrodroid/src/main/cpp/vfs/vfs.cpp
libretrodroid/src/main/cpp/video.cpp
libretrodroid/src/main/cpp/video.h
libretrodroid/src/main/cpp/videolayout.cpp
libretrodroid/src/main/cpp/videolayout.h
libretrodroid/src/main/java/com/swordfish/libretrodroid/GLRetroView.kt
libretrodroid/src/main/java/com/swordfish/libretrodroid/GLRetroViewData.kt
libretrodroid/src/main/java/com/swordfish/libretrodroid/LibretroDroid.java""".split()


def git(*args):
    return subprocess.run(["git"] + list(args), cwd=REPO, capture_output=True,
                          text=True, errors="replace").stdout


def main():
    exists = set(git("ls-tree", "-r", "--name-only", "upstream/master",
                     "--", "libretrodroid/src/main/").split())

    ours_alone, quiet, moved = [], [], []
    for path in MODIFIED:
        if path not in exists:
            ours_alone.append(path)
            continue
        log = git("log", "--oneline", "--since", SINCE, "upstream/master",
                  "--", path).strip()
        if log:
            moved.append((path, log.splitlines()))
        else:
            quiet.append(path)

    print("=== ours alone (not in upstream at all -- nothing to merge, ever) ===")
    for p in ours_alone:
        print("  " + p.split("main/")[-1])

    print("\n=== exists upstream, but upstream has not touched it since %s ===" % SINCE)
    for p in quiet:
        print("  " + p.split("main/")[-1])

    print("\n=== upstream HAS moved on these -- this is the whole cost of syncing ===")
    for p, lines in moved:
        print("  " + p.split("main/")[-1])
        for l in lines:
            print("      " + l)

    print("\nsummary: %d ours alone, %d quiet upstream, %d upstream moved"
          % (len(ours_alone), len(quiet), len(moved)))


if __name__ == "__main__":
    main()
