#ifndef LIBRETRODROID_ACHIEVEMENTSMEMORY_H
#define LIBRETRODROID_ACHIEVEMENTSMEMORY_H

#include <cstdint>
#include <cstddef>

#include "rc_consoles.h"
#include "libretro/libretro-common/include/libretro.h"

namespace libretrodroid {

#define ACHIEVEMENTS_MEMORY_MAX_REGIONS 32

/**
 * Builds the correct unified memory address space RetroAchievements' condition strings
 * expect (e.g. GBA EWRAM followed by IWRAM), from whichever of the two ways a libretro
 * core exposes its memory: a real RETRO_ENVIRONMENT_SET_MEMORY_MAPS descriptor list
 * (walked and stitched together per console-specific region layout), or -- if the core
 * never provides one -- a plain fallback onto whatever single retro_get_memory_data
 * buffer is available.
 *
 * This is a scoped, directly-ported copy of the region-resolution logic in vendored
 * rcheevos' `rc_libretro.c` (rc_libretro_memory_init/_read/_find/_destroy and their
 * static helpers) -- not the vendored file itself, deliberately: that file also pulls in
 * the full ROM/disc hashing dispatch table (`rhash/hash.c`, which alone references every
 * per-console hash function across dozens of consoles) for functionality this app never
 * calls, since PS1 hashing is already implemented independently in Kotlin (Ps1Hasher.kt).
 * Same "port the specific algorithm, don't vendor the whole library for one small piece"
 * call already made there. Confirmed live (real device, real account) that getting this
 * right matters: mGBA's RETRO_MEMORY_SYSTEM_RAM alone is EWRAM only, and achievement
 * conditions referencing IWRAM silently read 0 without a real memory map, causing mass
 * false-positive triggers -- see CHANGELOG.md.
 */
class AchievementsMemory {
public:
    typedef void (*GetCoreMemoryInfoFunc)(uint32_t id, uint8_t** data, size_t* size);

    void init(const struct retro_memory_map* mmap, GetCoreMemoryInfoFunc getCoreMemoryInfo, uint32_t consoleId);
    void reset();
    uint32_t read(uint32_t address, uint8_t* buffer, uint32_t numBytes) const;

private:
    void registerRegion(uint8_t* data, size_t size);
    void initFromMemoryMap(const struct retro_memory_map* mmap, const rc_memory_regions_t* consoleRegions);
    void initFromUnmappedMemory(GetCoreMemoryInfoFunc getCoreMemoryInfo, const rc_memory_regions_t* consoleRegions);
    void initWithoutRegions(GetCoreMemoryInfoFunc getCoreMemoryInfo);

    uint8_t* data[ACHIEVEMENTS_MEMORY_MAX_REGIONS] {};
    size_t size[ACHIEVEMENTS_MEMORY_MAX_REGIONS] {};
    uint32_t count = 0;
};

}

#endif //LIBRETRODROID_ACHIEVEMENTSMEMORY_H
