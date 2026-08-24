/*
 *     Copyright (C) 2025  Filippo Scognamiglio
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

#ifndef LIBRETRODROID_VIDEOLAYOUT_H
#define LIBRETRODROID_VIDEOLAYOUT_H

#include <array>

#include "utils/rect.h"

#define V_ALIGN_CENTER  0
#define V_ALIGN_TOP     1
#define V_ALIGN_BOTTOM  2

namespace libretrodroid {

class VideoLayout {
public:
    VideoLayout(bool bottomLeftOrigin, float rotation, Rect viewportRect, unsigned int viewportAlignment);

    void updateAspectRatio(float aspectRatio);

    void updateScreenSize(unsigned screenWidth, unsigned screenHeight);

    void updateViewportSize(Rect viewportRect);

    void updateViewportAlignment(unsigned int viewportAlignment);

    void updateRotation(float rotation);

    /** The current frame's size in core pixels -- the grid integer scaling snaps to. Cheap to
     *  call every frame: it returns immediately unless the size actually changed, which for
     *  most systems is once per game and for PS1 is once per video mode. */
    void updateFrameSize(unsigned frameWidth, unsigned frameHeight);

    /** Snap the picture to a whole multiple of the frame's own pixel grid instead of scaling
     *  it by whatever fraction fills the viewport.
     *
     *  Only the vertical axis is snapped; the horizontal follows it by the same factor, which
     *  keeps the aspect ratio the core asked for. Snapping both axes independently would mean
     *  square source pixels, and that is wrong for a PS1 running 256- or 384-wide modes (which
     *  the hardware stretches to 4:3) and would narrow SNES from 4:3 to 8:7. Vertical-only
     *  still buys the thing this exists for: a CRT shader's scanlines are horizontal, so even
     *  spacing depends on the vertical ratio alone. And where a core reports its native square
     *  aspect -- Game Boy at 160x144, GBA at 240x160 -- the derived width lands on a whole
     *  multiple too, so an LCD grid comes out uniform in both directions for free.
     *
     *  Ignored when a whole multiple would not fit at all (a frame taller than the space it is
     *  drawn into, e.g. PS1's 480-line modes in a small viewport): the fractional fit is kept
     *  rather than overflowing the viewport. */
    void updateIntegerScale(bool integerScale);

    std::array<float, 12>& getForegroundVertices() { return foregroundVertices; }
    std::array<float, 12>& getBackgroundVertices() { return backgroundVertices; }
    std::array<float, 12>& getFramebufferVertices() { return framebufferVertices; }
    std::array<float, 12>& getTextureCoordinates() { return textureCoordinates; }
    std::array<float, 4>& getRelativeForegroundBounds() { return relativeForegroundBounds; }

    int getScreenWidth() { return screenWidth; }

    int getScreenHeight() { return screenHeight; }

    std::pair<float, float> getRelativePosition(float touchX, float touchY);

private:
    void updateBuffers();

    void updateForegroundVertices();

    void updateBackgroundVertices();

    void updateRelativeForegroundBounds();

private:
    std::array<float, 12> foregroundVertices = {
        -1.0F,
        -1.0F,

        -1.0F,
        +1.0F,

        +1.0F,
        -1.0F,

        +1.0F,
        -1.0F,

        -1.0F,
        +1.0F,

        +1.0F,
        +1.0F,
    };

    std::array<float, 12> textureCoordinates {
        0.0F,
        0.0F,

        0.0F,
        1.0F,

        1.0F,
        0.0F,

        1.0F,
        0.0F,

        0.0F,
        1.0F,

        1.0F,
        1.0F,
    };

    std::array<float, 12> backgroundVertices = {
        -1.0F,
        -1.0F,

        -1.0F,
        +1.0F,

        +1.0F,
        -1.0F,

        +1.0F,
        -1.0F,

        -1.0F,
        +1.0F,

        +1.0F,
        +1.0F,
    };

    std::array<float, 12> framebufferVertices = {
        -1.0F,
        -1.0F,

        -1.0F,
        +1.0F,

        +1.0F,
        -1.0F,

        +1.0F,
        -1.0F,

        -1.0F,
        +1.0F,

        +1.0F,
        +1.0F,
    };

    std::array<float, 4> relativeForegroundBounds = {
        +0.0F,
        +0.0F,
        +1.0F,
        +1.0F,
    };

    bool bottomLeftOrigin = false;
    float rotation = 0.0F;
    float aspectRatio = 1;
    Rect viewportRect = Rect(0.0F, 0.0F, 1.0F, 1.0F);

    unsigned screenWidth = 0;
    unsigned screenHeight = 0;

    unsigned frameWidth = 0;
    unsigned frameHeight = 0;
    bool integerScale = false;

    unsigned viewportAlignment = V_ALIGN_CENTER;
};

} // namespace libretrodroid

#endif //LIBRETRODROID_VIDEOLAYOUT_H
