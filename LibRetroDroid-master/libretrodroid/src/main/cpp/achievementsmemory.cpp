#include "achievementsmemory.h"

#include <cstring>

namespace libretrodroid {

void AchievementsMemory::reset() {
    for (uint32_t i = 0; i < count; i++) {
        data[i] = nullptr;
        size[i] = 0;
    }
    count = 0;
}

void AchievementsMemory::registerRegion(uint8_t* regionData, size_t regionSize) {
    if (regionSize == 0) return;
    if (count == ACHIEVEMENTS_MEMORY_MAX_REGIONS) return;

    if (!regionData && count > 0 && !data[count - 1]) {
        // extend the previous null (unmapped) region
        size[count - 1] += regionSize;
    } else if (regionData && count > 0 && regionData == (data[count - 1] + size[count - 1])) {
        // extend the previous region -- it happens to be contiguous with this one
        size[count - 1] += regionSize;
    } else {
        data[count] = regionData;
        size[count] = regionSize;
        count++;
    }
}

void AchievementsMemory::initWithoutRegions(GetCoreMemoryInfoFunc getCoreMemoryInfo) {
    // No console-specific region layout known -- assume system RAM followed by save RAM,
    // matching rc_libretro_memory_init_without_regions.
    uint8_t* regionData = nullptr;
    size_t regionSize = 0;

    getCoreMemoryInfo(RETRO_MEMORY_SYSTEM_RAM, &regionData, &regionSize);
    registerRegion(regionData, regionSize);

    getCoreMemoryInfo(RETRO_MEMORY_SAVE_RAM, &regionData, &regionSize);
    registerRegion(regionData, regionSize);
}

/** Same disconnect-bit-collapsing algorithm as rc_libretro_memory_get_descriptor -- ported
 *  verbatim (the comment there says it was itself copied from RetroArch's mmap_reduce). */
static const struct retro_memory_descriptor* findDescriptor(
    const struct retro_memory_map* mmap, uint32_t realAddress, size_t* offset
) {
    const struct retro_memory_descriptor* desc = mmap->descriptors;
    const struct retro_memory_descriptor* end = desc + mmap->num_descriptors;

    for (; desc < end; desc++) {
        if (desc->select == 0) {
            if (realAddress >= desc->start && realAddress < desc->start + desc->len) {
                *offset = realAddress - desc->start;
                return desc;
            }
        } else if (((desc->start ^ realAddress) & desc->select) == 0) {
            uint32_t reducedAddress = realAddress - (unsigned) desc->start;

            uint32_t disconnectMask = (unsigned) desc->disconnect;
            while (disconnectMask) {
                const uint32_t tmp = (disconnectMask - 1) & ~disconnectMask;
                reducedAddress = (reducedAddress & tmp) | ((reducedAddress >> 1) & ~tmp);
                disconnectMask = (disconnectMask & (disconnectMask - 1)) >> 1;
            }

            *offset = reducedAddress;
            if (reducedAddress < desc->len) return desc;
        }
    }

    *offset = 0;
    return nullptr;
}

void AchievementsMemory::initFromMemoryMap(
    const struct retro_memory_map* mmap, const rc_memory_regions_t* consoleRegions
) {
    for (uint32_t i = 0; i < consoleRegions->num_regions; i++) {
        const rc_memory_region_t* consoleRegion = &consoleRegions->region[i];
        size_t consoleRegionSize = consoleRegion->end_address - consoleRegion->start_address + 1;
        uint32_t realAddress = consoleRegion->real_address;
        uint32_t disconnectSize = 0;

        while (consoleRegionSize > 0) {
            size_t offset;
            const struct retro_memory_descriptor* desc = findDescriptor(mmap, realAddress, &offset);
            if (!desc) {
                if (disconnectSize && consoleRegionSize > disconnectSize) {
                    registerRegion(nullptr, disconnectSize);
                    consoleRegionSize -= disconnectSize;
                    realAddress += disconnectSize;
                    disconnectSize = 0;
                    continue;
                }

                registerRegion(nullptr, consoleRegionSize);
                break;
            }

            uint8_t* regionStart = nullptr;
            if (desc->ptr) {
                regionStart = static_cast<uint8_t*>(desc->ptr) + desc->offset + offset;
            }

            size_t descSize = desc->len - offset;
            if (desc->disconnect && descSize > desc->disconnect) {
                // largest block we can read is up to the next time the disconnect bit flips
                disconnectSize = (uint32_t) (desc->disconnect & -((int) desc->disconnect));
                descSize = disconnectSize - (realAddress & (disconnectSize - 1));
            }

            if (consoleRegionSize > descSize) {
                if (descSize == 0) {
                    registerRegion(nullptr, consoleRegionSize);
                    consoleRegionSize = 0;
                } else {
                    registerRegion(regionStart, descSize);
                    consoleRegionSize -= descSize;
                    realAddress += (unsigned) descSize;
                }
            } else {
                registerRegion(regionStart, consoleRegionSize);
                consoleRegionSize = 0;
            }
        }
    }
}

static uint32_t consoleRegionTypeToRamType(uint8_t regionType) {
    switch (regionType) {
        case RC_MEMORY_TYPE_SAVE_RAM: return RETRO_MEMORY_SAVE_RAM;
        case RC_MEMORY_TYPE_VIDEO_RAM: return RETRO_MEMORY_VIDEO_RAM;
        default: return RETRO_MEMORY_SYSTEM_RAM;
    }
}

void AchievementsMemory::initFromUnmappedMemory(
    GetCoreMemoryInfoFunc getCoreMemoryInfo, const rc_memory_regions_t* consoleRegions
) {
    for (uint32_t i = 0; i < consoleRegions->num_regions; i++) {
        const rc_memory_region_t* consoleRegion = &consoleRegions->region[i];
        const size_t consoleRegionSize = consoleRegion->end_address - consoleRegion->start_address + 1;
        const uint32_t type = consoleRegionTypeToRamType(consoleRegion->type);
        uint32_t baseAddress = 0;

        for (uint32_t j = 0; j <= i; j++) {
            const rc_memory_region_t* consoleRegion2 = &consoleRegions->region[j];
            if (consoleRegionTypeToRamType(consoleRegion2->type) == type) {
                baseAddress = consoleRegion2->start_address;
                break;
            }
        }
        size_t offset = consoleRegion->start_address - baseAddress;

        uint8_t* regionData = nullptr;
        size_t regionSize = 0;
        getCoreMemoryInfo(type, &regionData, &regionSize);

        if (offset < regionSize) {
            regionSize -= offset;
            if (regionData) regionData += offset;
        } else {
            regionData = nullptr;
            regionSize = 0;
        }

        if (consoleRegionSize > regionSize) {
            registerRegion(regionData, regionSize);
            registerRegion(nullptr, consoleRegionSize - regionSize);
        } else {
            registerRegion(regionData, consoleRegionSize);
        }
    }
}

void AchievementsMemory::init(
    const struct retro_memory_map* mmap, GetCoreMemoryInfoFunc getCoreMemoryInfo, uint32_t consoleId
) {
    reset();

    const rc_memory_regions_t* consoleRegions = rc_console_memory_regions(consoleId);

    if (consoleRegions == nullptr || consoleRegions->num_regions == 0) {
        initWithoutRegions(getCoreMemoryInfo);
    } else if (mmap && mmap->num_descriptors != 0) {
        initFromMemoryMap(mmap, consoleRegions);
    } else {
        initFromUnmappedMemory(getCoreMemoryInfo, consoleRegions);
    }
}

uint32_t AchievementsMemory::read(uint32_t address, uint8_t* buffer, uint32_t numBytes) const {
    uint32_t bytesRead = 0;

    for (uint32_t i = 0; i < count; i++) {
        if (address >= size[i]) {
            address -= (uint32_t) size[i];
            continue;
        }

        if (data[i] == nullptr) break;

        uint32_t avail = (uint32_t) (size[i] - address);
        if (avail >= numBytes) {
            memcpy(buffer, &data[i][address], numBytes);
            return bytesRead + numBytes;
        }

        memcpy(buffer, &data[i][address], avail);
        buffer += avail;
        bytesRead += avail;
        numBytes -= avail;
        address = 0;
    }

    return bytesRead;
}

}
