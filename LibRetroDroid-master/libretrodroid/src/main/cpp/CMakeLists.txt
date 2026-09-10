cmake_minimum_required(VERSION 3.13.2)

# now build app's shared lib
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -std=c++17 -Wall")

add_definitions("-DVFS_FRONTEND -DHAVE_STRL")

# Let's include oboe
set (OBOE_DIR oboe)
add_subdirectory (${OBOE_DIR} oboe)
include_directories (${OBOE_DIR}/include)
include_directories (${OBOE_DIR}/src)

include_directories("libretro/libretro-common/include")

set (LIBRETRO_COMMON
        libretro/libretro-common/vfs/vfs_implementation.c
        libretro/libretro-common/string/stdstring.c
        libretro/libretro-common/encodings/encoding_utf.c
        libretro/libretro-common/file/file_path.c
        libretro/libretro-common/time/rtime.c
)

# rc_runtime_t only (RetroAchievements' local condition-evaluation engine) -- not the
# higher-level rc_client_t, which bundles its own networking/session model we don't want.
# All actual RetroAchievements networking stays in Kotlin (RetroAchievementsClient.kt);
# this is purely local memory-condition evaluation. File list verified by reading every
# #include in each file directly, not guessed -- nothing here pulls in rc_client*.c,
# rc_version.c, rapi/*, or rhash/* (rc_libretro.c also deliberately excluded -- it pulls
# in the full ROM/disc hashing dispatch table for functionality this app doesn't need;
# see achievementsmemory.h/.cpp, a scoped port of just its memory-region logic).
include_directories("libretro/rcheevos/include")

set (RCHEEVOS_SOURCES
        libretro/rcheevos/src/rcheevos/alloc.c
        libretro/rcheevos/src/rcheevos/condition.c
        libretro/rcheevos/src/rcheevos/condset.c
        libretro/rcheevos/src/rcheevos/consoleinfo.c
        libretro/rcheevos/src/rcheevos/format.c
        libretro/rcheevos/src/rcheevos/lboard.c
        libretro/rcheevos/src/rcheevos/memref.c
        libretro/rcheevos/src/rcheevos/operand.c
        libretro/rcheevos/src/rcheevos/rc_validate.c
        libretro/rcheevos/src/rcheevos/richpresence.c
        libretro/rcheevos/src/rcheevos/runtime.c
        libretro/rcheevos/src/rcheevos/runtime_progress.c
        libretro/rcheevos/src/rcheevos/trigger.c
        libretro/rcheevos/src/rcheevos/value.c
        libretro/rcheevos/src/rc_compat.c
        libretro/rcheevos/src/rc_util.c
        libretro/rcheevos/src/rhash/md5.c
)

add_library(libretrodroid SHARED
        libretrodroidjni.h
        libretrodroidjni.cpp
        libretrodroid.h
        libretrodroid.cpp
        log.h
        core.h
        core.cpp
        video.h
        video.cpp
        immersivemode.h
        immersivemode.cpp
        videolayout.h
        videolayout.cpp
        renderers/renderer.h
        renderers/renderer.cpp
        renderers/es3/es3utils.h
        renderers/es3/es3utils.cpp
        renderers/es3/framebufferrenderer.h
        renderers/es3/framebufferrenderer.cpp
        renderers/es2/imagerendereres2.h
        renderers/es2/imagerendereres2.cpp
        renderers/es3/imagerendereres3.h
        renderers/es3/imagerendereres3.cpp
        audio.h
        audio.cpp
        resamplers/resampler.h
        resamplers/linearresampler.h
        resamplers/linearresampler.cpp
        resamplers/sincresampler.h
        resamplers/sincresampler.cpp
        fpssync.h
        fpssync.cpp
        environment.h
        environment.cpp
        input.h
        input.cpp
        shadermanager.h
        shadermanager.cpp
        rumble.h
        rumble.cpp
        rumblestate.h
        rumblestate.cpp
        achievements.h
        achievements.cpp
        achievementsmemory.h
        achievementsmemory.cpp
        utils/javautils.h
        utils/javautils.cpp
        utils/utils.cpp
        utils/utils.h
        utils/jnistring.h
        utils/jnistring.cpp
        utils/libretrodroidexception.h
        utils/libretrodroidexception.cpp
        utils/rect.h
        utils/rect.cpp
        errorcodes.h
        errorcodes.cpp
        vfs/vfs.h
        vfs/vfs.cpp
        vfs/vfsfile.h
        vfs/vfsfile.cpp
        vfs/fdwrapper.h
        vfs/fdwrapper.cpp
        microphone/microphone.h
        microphone/microphone.cpp
        microphone/microphoneinterface.h
        microphone/microphoneinterface.cpp
        ${LIBRETRO_COMMON}
        ${RCHEEVOS_SOURCES}
)

# 16 KB pages. Two flags, and the second is the one that was missing until 2026-09-10.
#
# max-page-size aligns the LOAD segments, which is the check everyone knows about. It does
# not align the end of PT_GNU_RELRO: LLD pads that to common-page-size, which defaults to
# 4096. Android write-protects RELRO after relocation, and with 16 KB pages a RELRO segment
# ending mid-page cannot be protected without also protecting the writable data after it --
# so the loader rejects the library and runs the whole app in page-size compat mode, with a
# warning dialog on every launch.
#
# Measured with NDK 26.1 / LLD 17 across five .data.rel.ro sizes: max-page-size alone left
# the RELRO end misaligned in four of the five. This library happened to pass anyway, which
# is luck rather than correctness -- any change to its size could have tipped it over.
# common-page-size=16384 aligned all five.
target_link_options(libretrodroid PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-Wl,-z,common-page-size=16384")

# add lib dependencies
target_link_libraries(libretrodroid
                      android
                      log 
                      EGL
                      oboe
                      GLESv3
)
