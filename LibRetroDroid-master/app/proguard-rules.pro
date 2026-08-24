# Referenced by the release buildType, which has minifyEnabled = false -- so nothing here
# is applied today. The file exists because the build script names it, and an absent file
# named in proguardFiles is a trap waiting for whoever first turns shrinking on.
#
# If R8 is ever enabled, the things most likely to break are the paths Java code does not
# visibly call:
#   - JNI entry points in com.swordfish.libretrodroid (LibretroDroid.java is called from C++)
#   - the vendored zstd-jni and xz codecs, reached reflectively during CHD decoding
#   - anything the libretro core calls back into
# Add -keep rules for those before trusting a shrunk build.
