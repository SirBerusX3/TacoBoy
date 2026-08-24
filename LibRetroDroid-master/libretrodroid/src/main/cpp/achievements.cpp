#include "achievements.h"

#include <cstring>

#include "log.h"
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
}

void Achievements::resetProgress() {
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
    activeInstance = this;
    rc_runtime_do_frame(&runtime, &Achievements::eventHandler, &Achievements::peekMemory, this, nullptr);
    activeInstance = nullptr;
}

std::vector<uint32_t> Achievements::consumeTriggeredAchievements() {
    std::vector<uint32_t> result = std::move(pendingTriggers);
    pendingTriggers.clear();
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

void Achievements::eventHandler(const rc_runtime_event_t* event) {
    if (event->type == RC_RUNTIME_EVENT_ACHIEVEMENT_TRIGGERED && activeInstance != nullptr) {
        activeInstance->pendingTriggers.push_back(event->id);
    }
}

}
