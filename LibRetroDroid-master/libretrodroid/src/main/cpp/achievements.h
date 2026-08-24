#ifndef LIBRETRODROID_ACHIEVEMENTS_H
#define LIBRETRODROID_ACHIEVEMENTS_H

#include <cstdint>
#include <string>
#include <utility>
#include <vector>

#include "core.h"
#include "rc_runtime.h"
#include "achievementsmemory.h"

namespace libretrodroid {

/**
 * Wraps RetroAchievements' rc_runtime_t -- pure local achievement-condition evaluation,
 * no networking. Kotlin fetches each active achievement's trigger definition ("MemAddr")
 * over HTTP itself (RetroAchievementsClient.kt) and hands the (id, definition) pairs down
 * via loadAchievements(); doFrame() evaluates them once per step() against the emulated
 * core's live memory and collects any that just triggered for Kotlin to drain and submit
 * as unlocks.
 *
 * Memory access goes through AchievementsMemory (a scoped port of rcheevos' own
 * rc_libretro_memory_* reference logic, see achievementsmemory.h for why it's a port
 * rather than the vendored file directly) rather than a single raw
 * retro_get_memory_data(RETRO_MEMORY_SYSTEM_RAM) buffer -- confirmed live (real device,
 * real account) that the naive single-buffer approach silently returns 0 for any address
 * outside whatever one region that call happens to cover, causing mass false-positive
 * achievement triggers. See CHANGELOG.md.
 *
 * reset() vs resetProgress(): reset() fully deactivates every achievement (used when a new
 * game loads and today's activation list no longer applies at all). resetProgress() keeps
 * every currently-active achievement activated but clears their hit-count/delta state via
 * rc_runtime_reset -- used after a save-state load, since memory can jump discontinuously
 * relative to whatever the runtime last evaluated (e.g. loading an earlier point mid-way
 * through a hit-count condition), but the achievements themselves are still the right ones
 * to keep watching from here on. Not resetting at all would risk spurious triggers or
 * corrupted hit counts on the next frame; a full reset() would silently stop tracking
 * altogether for the rest of the session, since nothing currently re-activates achievements
 * after the initial per-game-load fetch (Kotlin's AchievementsSession only runs once, on
 * FrameRendered).
 */
class Achievements {
public:
    Achievements();
    ~Achievements();

    void reset();
    void resetProgress();
    void loadAchievements(
        Core* core,
        uint32_t consoleId,
        const std::vector<std::pair<uint32_t, std::string>>& idsAndMemAddrs
    );
    void doFrame();
    std::vector<uint32_t> consumeTriggeredAchievements();

private:
    static uint32_t peekMemory(uint32_t address, uint32_t numBytes, void* ud);
    static void eventHandler(const rc_runtime_event_t* event);
    static void getCoreMemoryInfo(uint32_t id, uint8_t** data, size_t* size);

    rc_runtime_t runtime{};
    AchievementsMemory memory;

    // Both callback types rc_runtime/AchievementsMemory invoke lack a void* userdata
    // parameter (rc_runtime_event_handler_t and GetCoreMemoryInfoFunc), so they can't be
    // routed via a closure. Everything that needs one runs synchronously and
    // non-reentrantly (loadAchievements from the JNI/Kotlin side, doFrame once per step()
    // under LibretroDroid's coreLock), so a plain static "current instance" pointer set
    // just before the call is enough to route the static callbacks back to `this`.
    static Achievements* activeInstance;
    Core* activeCore = nullptr;

    std::vector<uint32_t> pendingTriggers;
};

}

#endif //LIBRETRODROID_ACHIEVEMENTS_H
