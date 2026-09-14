#include "achievements.h"

#include <cstring>
#include <cstdint>

#include "log.h"
#include "rc_runtime_types.h"
#include "environment.h"

namespace libretrodroid {

Achievements* Achievements::activeInstance = nullptr;

Achievements::Achievements() {
    rc_runtime_init(&runtime);
}

Achievements::~Achievements() {
    rc_runtime_destroy(&runtime);
    memory.reset();
}

void Achievements::reset() {
    rc_runtime_destroy(&runtime);
    rc_runtime_init(&runtime);
    memory.reset();
    pendingTriggers.clear();
    pendingIndicatorEvents.clear();
}

void Achievements::resetProgress() {
    // rc_runtime_reset puts every trigger back to waiting without raising events, so a challenge
    // that was active before a save state loaded would otherwise stay on screen indefinitely.
    pendingIndicatorEvents.clear();
    for (uint32_t i = 0; i < runtime.trigger_count; ++i) {
        const rc_trigger_t* trigger = runtime.triggers[i].trigger;
        if (trigger && trigger->state == RC_TRIGGER_STATE_PRIMED) {
            pendingIndicatorEvents.push_back({IndicatorEvent::Type::CHALLENGE_ENDED, runtime.triggers[i].id, "", 0});
        }
    }
    rc_runtime_reset(&runtime);
    pendingTriggers.clear();
}

void Achievements::loadAchievements(
    Core* core,
    uint32_t consoleId,
    const std::vector<std::pair<uint32_t, std::string>>& idsAndMemAddrs
) {
    reset();

    activeInstance = this;
    activeCore = core;
    memory.init(Environment::getInstance().getMemoryMap(), &Achievements::getCoreMemoryInfo, consoleId);
    activeCore = nullptr;
    activeInstance = nullptr;

    for (const auto& [id, memAddr] : idsAndMemAddrs) {
        int result = rc_runtime_activate_achievement(&runtime, id, memAddr.c_str(), nullptr, 0);
        if (result != RC_OK) {
            LOGE("Failed to activate achievement %u: %s", id, rc_error_str(result));
        }
    }
}

void Achievements::doFrame() {
    size_t firstNewEvent = pendingIndicatorEvents.size();
    activeInstance = this;
    rc_runtime_do_frame(&runtime, &Achievements::eventHandler, &Achievements::peekMemory, this, nullptr);
    activeInstance = nullptr;

    // rc_client shows one progress popup per frame, for whichever achievement that frame moved
    // closest to done (rc_client_do_frame_process_achievements). Keep only that one of this
    // frame's progress events, so several counters ticking together do not flicker between.
    size_t best = SIZE_MAX;
    for (size_t i = firstNewEvent; i < pendingIndicatorEvents.size(); ++i) {
        const auto& event = pendingIndicatorEvents[i];
        if (event.type != IndicatorEvent::Type::PROGRESS) continue;
        if (best == SIZE_MAX || event.fraction > pendingIndicatorEvents[best].fraction) best = i;
    }
    if (best == SIZE_MAX) return;
    std::vector<IndicatorEvent> kept(pendingIndicatorEvents.begin(), pendingIndicatorEvents.begin() + firstNewEvent);
    for (size_t i = firstNewEvent; i < pendingIndicatorEvents.size(); ++i) {
        if (pendingIndicatorEvents[i].type != IndicatorEvent::Type::PROGRESS || i == best) {
            kept.push_back(std::move(pendingIndicatorEvents[i]));
        }
    }
    pendingIndicatorEvents = std::move(kept);
}

std::vector<uint32_t> Achievements::consumeTriggeredAchievements() {
    std::vector<uint32_t> result = std::move(pendingTriggers);
    pendingTriggers.clear();
    return result;
}

std::vector<Achievements::IndicatorEvent> Achievements::consumeIndicatorEvents() {
    std::vector<IndicatorEvent> result = std::move(pendingIndicatorEvents);
    pendingIndicatorEvents.clear();
    return result;
}

std::vector<Achievements::Snapshot> Achievements::snapshot() {
    std::vector<Snapshot> result;
    char buffer[32];
    for (uint32_t i = 0; i < runtime.trigger_count; ++i) {
        const rc_trigger_t* trigger = runtime.triggers[i].trigger;
        if (!trigger) continue;
        uint32_t id = runtime.triggers[i].id;
        rc_runtime_format_achievement_measured(&runtime, id, buffer, sizeof(buffer));
        result.push_back({id, buffer, trigger->state == RC_TRIGGER_STATE_PRIMED});
    }
    return result;
}

uint32_t Achievements::peekMemory(uint32_t address, uint32_t numBytes, void* ud) {
    auto* self = static_cast<Achievements*>(ud);

    uint32_t value = 0;
    self->memory.read(address, reinterpret_cast<uint8_t*>(&value), numBytes);
    return value;
}

void Achievements::getCoreMemoryInfo(uint32_t id, uint8_t** data, size_t* size) {
    if (activeInstance == nullptr || activeInstance->activeCore == nullptr) {
        *data = nullptr;
        *size = 0;
        return;
    }

    Core* core = activeInstance->activeCore;
    *size = core->retro_get_memory_size(id);
    *data = static_cast<uint8_t*>(core->retro_get_memory_data(id));
}

/**
 * Beyond triggers, keeps the three events rcheevos' own client (rc_client.c) turns into on-screen
 * indicators: PROGRESS_UPDATED for its progress tracker, and PRIMED / UNPRIMED for its challenge
 * indicator. rc_runtime already applies rc_client's filtering before raising PROGRESS_UPDATED --
 * only while active, never past the target, and for a percentage only when the whole percent
 * changes -- so every one that arrives here is worth showing.
 */
void Achievements::eventHandler(const rc_runtime_event_t* event) {
    if (activeInstance == nullptr) return;
    switch (event->type) {
        case RC_RUNTIME_EVENT_ACHIEVEMENT_TRIGGERED:
            activeInstance->pendingTriggers.push_back(event->id);
            break;
        case RC_RUNTIME_EVENT_ACHIEVEMENT_PROGRESS_UPDATED: {
            char buffer[32];
            rc_runtime_format_achievement_measured(&activeInstance->runtime, event->id, buffer, sizeof(buffer));
            unsigned value = 0;
            unsigned target = 0;
            rc_runtime_get_achievement_measured(&activeInstance->runtime, event->id, &value, &target);
            float fraction = target > 0 ? static_cast<float>(value) / static_cast<float>(target) : 0;
            activeInstance->pendingIndicatorEvents.push_back(
                {IndicatorEvent::Type::PROGRESS, event->id, buffer, fraction});
            break;
        }
        case RC_RUNTIME_EVENT_ACHIEVEMENT_PRIMED:
            activeInstance->pendingIndicatorEvents.push_back({IndicatorEvent::Type::CHALLENGE_STARTED, event->id, "", 0});
            break;
        case RC_RUNTIME_EVENT_ACHIEVEMENT_UNPRIMED:
            activeInstance->pendingIndicatorEvents.push_back({IndicatorEvent::Type::CHALLENGE_ENDED, event->id, "", 0});
            break;
        default:
            break;
    }
}

}
