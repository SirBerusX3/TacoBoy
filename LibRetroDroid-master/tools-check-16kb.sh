#!/usr/bin/env bash
# Verify every native library in the APK will load on a 16 KB-page device without Android
# falling back to page-size compat mode.
#
#   ./tools-check-16kb.sh [path/to/app.apk]     (default: the release APK)
#
# Run from LibRetroDroid-master/. Exits non-zero on any failure, so it can gate a release.
#
# WHAT IS CHECKED, AND WHY EACH ONE
#
# The first version of this script checked only LOAD segment alignment, passed, and was
# wrong: on a 16 KB emulator Android still rejected six libraries with "RELRO alignment check
# failed" and ran the whole app in compat mode. p_align is necessary, not sufficient. The
# loader needs all three of these, for every PT_LOAD and the PT_GNU_RELRO:
#
#   1. p_align is a non-zero multiple of 16384.
#      Arithmetic, not textual -- Snes9x ships at 0x10000 (64 KB), which is valid and which a
#      glob for "0x1000" once flagged as broken.
#   2. p_offset and p_vaddr are congruent modulo 16384.
#      mmap can only map a file offset to an address that shares its position within a page.
#   3. PT_GNU_RELRO ends on a 16384 boundary.
#      RELRO is write-protected after relocation. Ending mid-page, it cannot be protected
#      without also protecting the writable data after it. LLD pads this end to
#      common-page-size, which defaults to 4096, so -z max-page-size=16384 alone does NOT
#      fix it; -z common-page-size=16384 does. See CHANGELOG.md, 2026-09-10.
#
# WHY THE APK AND NOT jniLibs/
#
# jniLibs/ holds 7 of the 9 libraries that ship. liblibretrodroid.so is built here and
# libzstd-jni arrives from a Maven dependency; both exist only in the APK, so a jniLibs-only
# check never looked at them. Checking what ships is the only check that means anything.
#
# A clean result here is not the same as having run on 16 KB hardware. Third-party checkers
# test only (1) -- 16kbchecker.com passed all nine libraries this script fails six of -- so a
# passing external report proves little on its own.

APK="${1:-app/build/outputs/apk/release/app-release.apk}"
[ -f "$APK" ] || { echo "no APK at $APK -- build it first (./gradlew :app:assembleRelease)"; exit 2; }

READELF=$(find "$LOCALAPPDATA/Android/Sdk/ndk" -name "llvm-readelf.exe" 2>/dev/null | head -1)
[ -z "$READELF" ] && READELF=$(command -v llvm-readelf || command -v readelf)
[ -z "$READELF" ] && { echo "no llvm-readelf found"; exit 2; }

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
unzip -q -o "$APK" 'lib/*/*.so' -d "$WORK" 2>/dev/null

PAGE=16384
fail=0
checked=0
printf "%-8s %-8s %-10s %-14s %s\n" "align" "off=va" "RELRO end" "" "library"
for f in "$WORK"/lib/*/*.so; do
  [ -f "$f" ] || continue
  checked=$((checked + 1))
  align_ok=1; congr_ok=1; relro_txt="none"; relro_ok=1

  # -W keeps each segment on one line. Offset, VirtAddr and MemSiz sit at fixed columns
  # ($2 $3 $6) ahead of the flags, which vary in width ("R", "R E", "RW"); Align is last.
  while read -r type off va memsz align; do
    case "$type" in
      LOAD)
        a=$((align))
        { [ "$a" -eq 0 ] || [ $((a % PAGE)) -ne 0 ]; } && align_ok=0
        [ $((off % PAGE)) -ne $((va % PAGE)) ] && congr_ok=0
        ;;
      GNU_RELRO)
        end=$((va + memsz))
        relro_txt=$(printf "%#x" "$end")
        [ $((end % PAGE)) -ne 0 ] && relro_ok=0
        ;;
    esac
  done < <("$READELF" -l -W "$f" 2>/dev/null | awk '$1=="LOAD" || $1=="GNU_RELRO" {print $1, $2, $3, $6, $NF}')

  verdict="ok"
  if [ $align_ok -eq 0 ] || [ $congr_ok -eq 0 ] || [ $relro_ok -eq 0 ]; then
    verdict="FAIL"; fail=1
  fi
  relro_note=""
  [ $relro_ok -eq 0 ] && relro_note=$(printf "(+%#x)" $(( $relro_txt % PAGE )))
  printf "%-8s %-8s %-10s %-14s %s  %s\n" \
    "$([ $align_ok -eq 1 ] && echo ok || echo BAD)" \
    "$([ $congr_ok -eq 1 ] && echo ok || echo BAD)" \
    "$relro_txt" "$relro_note" "$verdict" "$(basename "$f")"
done

echo "---"
if [ $checked -eq 0 ]; then
  echo "FAIL: no native libraries found in $APK"; exit 2
elif [ $fail -eq 0 ]; then
  echo "PASS: all $checked libraries in $(basename "$APK") are 16 KB compatible"
else
  echo "FAIL: libraries marked FAIL above will force page-size compat mode on 16 KB devices"
fi
exit $fail
