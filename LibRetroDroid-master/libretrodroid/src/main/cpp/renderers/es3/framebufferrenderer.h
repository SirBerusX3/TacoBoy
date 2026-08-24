/*
 *     Copyright (C) 2019  Filippo Scognamiglio
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

#ifndef LIBRETRODROID_FRAMEBUFFERRENDERER_H
#define LIBRETRODROID_FRAMEBUFFERRENDERER_H

#include "GLES3/gl3.h"
#include "GLES3/gl3ext.h"

#include "../renderer.h"
#include "es3utils.h"

namespace libretrodroid {

class FramebufferRenderer: public Renderer {
public:
    FramebufferRenderer(
        unsigned maxWidth,
        unsigned maxHeight,
        bool depth,
        bool stencil,
        ShaderManager::Chain shaders
    );
    uintptr_t getTexture() override;
    uintptr_t getFramebuffer() override;
    void onNewFrame(const void *data, unsigned width, unsigned height, size_t pitch) override;
    void setPixelFormat(int pixelFormat) override;
    void updateRenderedResolution(unsigned int width, unsigned int height) override;

    bool rendersInVideoCallback() override;

    void setShaders(ShaderManager::Chain shaders) override;
    PassData getPassData(unsigned int layer) override;
    std::pair<unsigned, unsigned> getTextureAllocationSize() override;

private:
    bool depth = false;
    bool stencil = false;

    // Size of the frame the core last produced. Drives the shader-pass chain and the
    // sub-rect the display samples -- no longer the size of the framebuffer itself.
    unsigned int width = 0;
    unsigned int height = 0;

    // Size the core's render target is actually allocated at: max_width/max_height from
    // retro_get_system_av_info, which is the most the core is allowed to draw.
    unsigned int allocatedWidth = 0;
    unsigned int allocatedHeight = 0;

    bool passesDirty = true;
    bool allocationDirty = true;

    std::unique_ptr<ES3Utils::Framebuffer> framebuffer = std::make_unique<ES3Utils::Framebuffer>();

    ShaderManager::Chain shaders;
    std::unique_ptr<ES3Utils::Framebuffers> framebuffers = std::make_unique<ES3Utils::Framebuffers>();

    void initializeBuffers();
    void rebuildPasses();
    void rebuildFramebuffer();
};

}

#endif //LIBRETRODROID_FRAMEBUFFERRENDERER_H
