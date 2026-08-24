#!/usr/bin/env bash
# Verify every native library that ships in the APK is 16 KB page-aligned.
#
# 16 KB pages are what newer Android hardware is moving to; a library whose LOAD segments
# are aligned to 4 KB (0x1000) cannot be mapped on such a device and the app fails to load.
#
# The test is arithmetic, not textual: alignment must be a non-zero multiple of 16384. A
# larger power of two is fine -- Snes9x ships at 0x10000 (64 KB), which is perfectly valid
# and which a naive glob for "0x1000" flags as broken. That mistake was made once here
# already; hence the arithmetic.
#
# Run from LibRetroDroid-master/. Exits non-zero if anything is misaligned, so it can gate
# a release. See CHANGELOG.md's 2026-08-24 16 KB entry for context.
READELF=$(find "$LOCALAPPDATA/Android/Sdk/ndk" -name "llvm-readelf.exe" 2>/dev/null | head -1)
[ -z "$READELF" ] && { echo "no llvm-readelf found in the NDK"; exit 2; }

fail=0
checked=0
for f in app/src/main/jniLibs/*/*.so; do
  [ -f "$f" ] || continue
  checked=$((checked + 1))
  worst=0
  for a in $("$READELF" -l "$f" 2>/dev/null | awk '/LOAD/ {print $NF}' | sort -u); do
    dec=$((a))
    if [ "$dec" -eq 0 ] || [ $((dec % 16384)) -ne 0 ]; then worst=$dec; fi
  done
  if [ "$worst" -ne 0 ]; then
    printf "MISALIGNED  %#x  %s\n" "$worst" "$(basename "$f")"
    fail=1
  else
    printf "ok          %-9s %s\n" "$("$READELF" -l "$f" | awk '/LOAD/ {print $NF}' | sort -u | tr '\n' ' ')" "$(basename "$f")"
  fi
done

echo "---"
if [ $fail -eq 0 ]; then
  echo "PASS: all $checked bundled libraries are 16 KB aligned"
else
  echo "FAIL: fix the libraries above before release"
fi
exit $fail
