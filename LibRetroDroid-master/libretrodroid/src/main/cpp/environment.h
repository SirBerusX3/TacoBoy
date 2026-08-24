/*
 *     Copyright (C) 2020  Filippo Scognamiglio
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

#ifndef LIBRETRODROID_ENVIRONMENT_H
#define LIBRETRODROID_ENVIRONMENT_H

#define MODULE_NAME_CORE "Libretro Core"

#include <vector>
#include <string>
#include <cstring>
#include <cmath>
#include <EGL/egl.h>
#include <unordered_map>
#include <array>

#include "../../libretro-common/include/libretro.h"
#include "log.h"
#include "rumblestate.h"

class Environment {
public:
    static Environment& getInstance()
    {
        static Environment instance;
        return instance;
    }
    Environment(Environment const&) = delete;
    void operator=(Environment const&) = delete;

    static void callback_retro_log(enum retro_log_level level, const char *fmt, ...);

    static bool callback_set_rumble_state(
        unsigned port,
        enum retro_rumble_effect effect,
        uint16_t strength
    );

    static bool callback_environment(unsigned cmd, void *data);

    void setEnableVirtualFileSystem(bool value);
    void setEnableMicrophone(bool value);

    /** Invoked synchronously from within SET_GEOMETRY/SET_SYSTEM_AV_INFO handling, before
     *  the core goes on to render its frame -- see the resize-timing fix in libretrodroid.cpp
     *  (LibretroDroid::handleGeometryChanged) for why this can no longer be polled after
     *  retro_run() returns. */
    void setGeometryChangedCallback(void (*callback)(unsigned width, unsigned height));

    /**
     * Publishes the content the frontend just loaded, so a core asking for it through
     * RETRO_ENVIRONMENT_GET_GAME_INFO_EXT gets a real answer.
     *
     * Needed because a core is free to read its content *only* through that call: Handy
     * (Lynx) does exactly this -- it ignores retro_game_info::data entirely, and without an
     * answer here falls back to opening `retro_game_info::path` off the filesystem. Under
     * this frontend that path is a virtual filename with nothing behind it, so the cart
     * silently failed to load while the core carried on running its BIOS ("INSERT GAME").
     *
     * `data` is not copied. It must outlive retro_load_game, which it does -- LibretroDroid
     * holds the buffer it passed in retro_game_info for the same span -- and is advertised
     * as non-persistent, so a core that wants it for longer takes its own copy.
     */
    void setLoadedContent(
        const std::string &path,
        const void *data,
        size_t size
    );

    void clearLoadedContent();

private:
    Environment() {}

public:
    void initialize(
        const std::string &requiredSystemDirectory,
        const std::string &requiredSavesDirectory,
        retro_hw_get_current_framebuffer_t required_callback_get_current_framebuffer
    );

    void deinitialize();

    void updateVariable(const std::string &key, const std::string &value);

    void setLanguage(const std::string &androidLanguage);

    float retrieveGameSpecificAspectRatio();

    bool handle_callback_set_rumble_state(
        unsigned port,
        enum retro_rumble_effect effect,
        uint16_t strength
    );

    bool handle_callback_environment(unsigned cmd, void *data);

    retro_hw_context_reset_t getHwContextReset() const;
    retro_hw_context_reset_t getHwContextDestroy() const;

    struct retro_disk_control_callback* getRetroDiskControlCallback() const;

    int getPixelFormat() const;
    bool isUseHwAcceleration() const;
    bool isUseDepth() const;
    bool isUseStencil() const;
    bool isBottomLeftOrigin() const;

    float getScreenRotation() const;
    bool isScreenRotationUpdated() const;
    void clearScreenRotationUpdated();

    unsigned int getGameGeometryWidth() const;
    unsigned int getGameGeometryHeight() const;
    float getGameGeometryAspectRatio() const;

    std::array<libretrodroid::RumbleState, 4> & getLastRumbleStates();

    const std::vector<struct Variable> getVariables() const;

    const std::vector<std::vector<struct Controller>> &getControllers() const;

    /** Non-null only if the core called RETRO_ENVIRONMENT_SET_MEMORY_MAPS (mGBA does, since
     *  RetroArch's own achievement support depends on it) -- lets Achievements build the
     *  correct unified address space (e.g. GBA EWRAM+IWRAM) instead of just whatever single
     *  region retro_get_memory_data(RETRO_MEMORY_SYSTEM_RAM) happens to cover. See
     *  achievements.cpp. */
    const struct retro_memory_map* getMemoryMap() const;

private:
    bool environment_handle_set_variables(const struct retro_variable* received);
    bool environment_handle_get_variable(struct retro_variable* requested);
    bool environment_handle_set_controller_info(const struct retro_controller_info* received);
    bool environment_handle_set_hw_render(struct retro_hw_render_callback* hw_render_callback);
    bool environment_handle_get_vfs_interface(struct retro_vfs_interface_info* vfs_interface_info);
    bool environment_handle_get_microphone_interface(struct retro_microphone_interface* microphone_interface);
    bool environment_handle_set_memory_maps(const struct retro_memory_map* received);

private:
    retro_hw_context_reset_t hw_context_reset = nullptr;
    retro_hw_context_reset_t hw_context_destroy = nullptr;
    struct retro_disk_control_callback *retro_disk_control_callback = nullptr;

    std::string savesDirectory;
    std::string systemDirectory;
    retro_hw_get_current_framebuffer_t callback_get_current_framebuffer = nullptr;
    void (*geometryChangedCallback)(unsigned width, unsigned height) = nullptr;
    unsigned language = RETRO_LANGUAGE_ENGLISH;
    bool useVirtualFileSystem = false;
    bool enableMicrophone = false;

    int pixelFormat = RETRO_PIXEL_FORMAT_RGB565;
    bool useHWAcceleration = false;
    bool useDepth = false;
    bool useStencil = false;
    bool bottomLeftOrigin = false;

    float screenRotation = 0;
    bool screenRotationUpdated = false;

    // Backing storage for the retro_game_info_ext handed out by GET_GAME_INFO_EXT. The
    // struct holds bare `const char*`s, so the strings have to outlive it -- hence keeping
    // them as members rather than building them on the stack in the callback.
    struct retro_game_info_ext gameInfoExt {};
    std::string gameInfoExtFullPath;
    std::string gameInfoExtDir;
    std::string gameInfoExtName;
    std::string gameInfoExtExt;
    bool gameInfoExtValid = false;

    unsigned gameGeometryWidth = 0;
    unsigned gameGeometryHeight = 0;
    float gameGeometryAspectRatio = -1.0f;

    std::array<libretrodroid::RumbleState, 4> rumbleStates;

    std::unordered_map<std::string, struct Variable> variables;
    bool dirtyVariables = false;

    std::vector<std::vector<struct Controller>> controllers;

    // Deep copy of whatever the core passed to SET_MEMORY_MAPS -- the core's own pointers
    // aren't guaranteed to stay valid, so both the descriptor array and the retro_memory_map
    // wrapper pointing at it are owned here.
    std::vector<struct retro_memory_descriptor> memoryMapDescriptors;
    struct retro_memory_map memoryMap {};
    bool hasMemoryMap = false;
};

struct Variable {
public:
    std::string key;
    std::string value;
    std::string description;
};

struct Controller {
public:
    unsigned id;
    std::string description;
};

#endif //LIBRETRODROID_ENVIRONMENT_H

