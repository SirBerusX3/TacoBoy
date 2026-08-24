"""Find which upstream LibretroDroid commit this fork was taken from.

There is no recorded base: the module arrived as a downloaded master ZIP, so the only
evidence is the file contents themselves. Git already content-addresses everything, so
for each upstream commit we can simply count how many of our vendored files have a byte
identical blob in that commit's tree. The commit that matches the most files is the base,
and the files that match nowhere are the ones we have modified.

Vendored third-party trees (oboe, rcheevos, libretro-common) are excluded: they were
empty in the ZIP and were filled in by hand, so they say nothing about the base.
"""

import collections
import subprocess

REPO = r"C:\Users\dcrot\Documents\TacoBoy"
OUR_PREFIX = "LibRetroDroid-master/"
MODULE = "libretrodroid/src/main/"
EXCLUDE = ("/oboe/", "/rcheevos/", "/libretro-common/", "/libretro/libretro.h")


def git(*args):
    return subprocess.run(["git"] + list(args), cwd=REPO, capture_output=True,
                          text=True, errors="replace").stdout


def ours():
    """path (upstream-relative) -> blob hash, for our copy of the module."""
    out = git("ls-files", "-s", OUR_PREFIX + MODULE)
    result = {}
    for line in out.splitlines():
        meta, path = line.split("\t", 1)
        blob = meta.split()[1]
        rel = path[len(OUR_PREFIX):]
        if any(x in "/" + rel for x in EXCLUDE):
            continue
        result[rel] = blob
    return result


def main():
    mine = ours()
    print("comparing %d of our files against upstream history" % len(mine))

    commits = git("rev-list", "upstream/master").split()
    print("scanning %d upstream commits" % len(commits))

    best = []
    # Track, per file, whether it EVER matched anywhere upstream. A file that never
    # matches any commit is one we changed (or one upstream never had).
    ever_matched = set()

    for commit in commits:
        out = git("ls-tree", "-r", commit, "--", MODULE)
        theirs = {}
        for line in out.splitlines():
            meta, path = line.split("\t", 1)
            parts = meta.split()
            if parts[1] != "blob":
                continue
            theirs[path] = parts[2]
        hits = 0
        for path, blob in mine.items():
            if theirs.get(path) == blob:
                hits += 1
                ever_matched.add(path)
        best.append((hits, commit, len(theirs)))

    best.sort(reverse=True)
    print("\n=== best matching upstream commits ===")
    for hits, commit, total in best[:5]:
        info = git("show", "-s", "--format=%h %ad %s", "--date=short", commit).strip()
        print("  %3d/%d files identical  %s" % (hits, len(mine), info))

    never = sorted(set(mine) - ever_matched)
    print("\n=== files that match NO upstream commit (locally modified or new) ===")
    for path in never:
        print("  " + path)
    print("\n%d of %d files differ from every upstream revision" % (len(never), len(mine)))


if __name__ == "__main__":
    main()
