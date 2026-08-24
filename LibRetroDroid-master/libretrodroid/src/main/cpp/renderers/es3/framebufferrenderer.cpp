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

#include "framebufferrenderer.h"
#include "es3utils.h"
#include "../../log.h"

namespace libretrodroid {

FramebufferRenderer::FramebufferRenderer(
    unsigned maxWidth,
    unsigned maxHeight,
    bool depth,
    bool stencil,
    ShaderManager::Chain shaders
) {
    this->depth = depth;
    this->stencil = stencil;
    // Never zero: a core that declares no maximum still has to be handed something valid.
    this->allocatedWidth = std::max(maxWidth, 1u);
    this->allocatedHeight = std::max(maxHeight, 1u);
    this->width = this->allocatedWidth;
    this->height = this->allocatedHeight;
    this->shaders = std::move(shaders);

    initializeBuffers();
}

void FramebufferRenderer::onNewFrame(const void *data, unsigned width, unsigned height, size_t pitch) {
    Renderer::onNewFrame(data, width, height, pitch);

    // The core's declared per-frame size (video_refresh_cb's width/height, also cached above
    // as lastFrameSize) tells us which sub-rect of the render target now holds the picture --
    // some cores' very first frame (the real PS1 BIOS splash, before the game itself ever
    // calls SET_GEOMETRY) only ever declares its size this way. The framebuffer itself is no
    // longer resized to match, so unlike before this cannot pull the target out from under a
    // frame the core has already drawn.
    updateRenderedResolution(width, height);
}

void FramebufferRenderer::initializeBuffers() {
    rebuildPasses();
    rebuildFramebuffer();
    passesDirty = false;
    allocationDirty = false;
}

/** Intermediate passes stay frame-sized, so a shader reading `previousPass` still samples it
 *  across a full 0..1, and only the core's own render target carries the sub-rect. */
void FramebufferRenderer::rebuildPasses() {
    // Free the previous chain before replacing it. Assigning over `framebuffers` destroys the
    // vector and its unique_ptrs, but Framebuffer is a plain struct of GL names with no
    // destructor -- so without this every rebuild leaked a framebuffer, a texture and possibly
    // a renderbuffer per pass.
    ES3Utils::deleteFramebuffers(std::move(framebuffers));
    framebuffers = ES3Utils::buildShaderPasses(width, height, shaders);
}

void FramebufferRenderer::rebuildFramebuffer() {
    ES3Utils::deleteFramebuffer(std::move(framebuffer));
    framebuffer = ES3Utils::createFramebuffer(
        allocatedWidth,
        allocatedHeight,
        shaders.linearTexture,
        false,
        depth,
        stencil
    );
}

uintptr_t FramebufferRenderer::getTexture() {
    return framebuffer->texture;
}

uintptr_t FramebufferRenderer::getFramebuffer() {
    // The core calls this mid-frame, via get_current_framebuffer, immediately before it draws.
    // The render target is sized for the core's declared maximum, so an ordinary resolution
    // change needs no reallocation at all here -- which is the point of allocating it that way.
    // The only reasons to rebuild are a core that has exceeded the maximum it declared, or a
    // shader change altering the texture's filtering.
    if (allocationDirty) {
        rebuildFramebuffer();
        allocationDirty = false;
    }
    if (passesDirty) {
        rebuildPasses();
        passesDirty = false;
    }
    return framebuffer->framebuffer;
}

std::pair<unsigned, unsigned> FramebufferRenderer::getTextureAllocationSize() {
    return { allocatedWidth, allocatedHeight };
}

void FramebufferRenderer::setPixelFormat(int pixelFormat) {
    // TODO... Here we should handle 32bit framebuffers.
}

void FramebufferRenderer::updateRenderedResolution(unsigned int width, unsigned int height) {
    if (this->width == width && this->height == height) {
        return;
    }

    this->width = width;
    this->height = height;
    passesDirty = true;

    // Growing only ever happens if a core draws bigger than the max_width/max_height it
    // declared, which it is not supposed to do. Handle it rather than silently clipping the
    // picture to the allocation, and never shrink back -- churning the render target is the
    // behaviour this change exists to remove.
    if (width > allocatedWidth || height > allocatedHeight) {
        allocatedWidth = std::max(allocatedWidth, width);
        allocatedHeight = std::max(allocatedHeight, height);
        allocationDirty = true;
    }
}

bool FramebufferRenderer::rendersInVideoCallback() {
    return true;
}

void FramebufferRenderer::setShaders(ShaderManager::Chain shaders) {
    if (shaders != this->shaders) {
        this->shaders = shaders;
        passesDirty = true;
        // linearTexture is baked into the render target's filtering at creation.
        allocationDirty = true;
    }
}

Renderer::PassData FramebufferRenderer::getPassData(unsigned int layer) {
    PassData result;

    // `layer` is unsigned, so the old `layer >= 0` half of this test was always true.
    if (layer < framebuffers->size()) {
        result.framebuffer = framebuffers->at(layer)->framebuffer;
        result.width = framebuffers->at(layer)->width;
        result.height = framebuffers->at(layer)->height;
    }

    // Pass N reads what pass N-1 wrote, hence the offset -- and hence `size() + 1` rather
    // than `size()`: the final pass has a source texture but writes to the screen, not to a
    // framebuffer of its own.
    if (layer > 0 && layer < framebuffers->size() + 1) {
        result.texture = framebuffers->at(layer - 1)->texture;
    }

    return result;
}

} //namespace libretrodroid
