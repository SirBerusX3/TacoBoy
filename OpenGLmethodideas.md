OPENGL Fix Plan:

1. Add EGLConfigChooser (Highest Priority)
File: GLRetroView.kt

Problem: No setEGLConfigChooser() call. Android's default chooser typically returns RGB565, no depth, no stencil. While the FBO has its own depth/stencil, some cores probe the context's capabilities or make GL calls that expect a depth-capable drawable. Some Android drivers also behave oddly when the window surface doesn't match the FBO format.
Fix:

import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay

init {
    openGLESVersion = getGLESVersion(context)
    preserveEGLContextOnPause = true
    setEGLContextClientVersion(openGLESVersion)
    
    // Request RGBA8888 + depth24 + stencil8
    setEGLConfigChooser(object : EGLConfigChooser {
        override fun chooseConfig(egl: EGL10, display: EGLDisplay): EGLConfig {
            val renderableType = if (openGLESVersion >= 3) 
                0x0040 /* EGL_OPENGL_ES3_BIT */ else 0x0004 /* EGL_OPENGL_ES2_BIT */
            
            val attribs = intArrayOf(
                EGL10.EGL_RED_SIZE, 8,
                EGL10.EGL_GREEN_SIZE, 8,
                EGL10.EGL_BLUE_SIZE, 8,
                EGL10.EGL_ALPHA_SIZE, 8,
                EGL10.EGL_DEPTH_SIZE, 24,
                EGL10.EGL_STENCIL_SIZE, 8,
                EGL10.EGL_RENDERABLE_TYPE, renderableType,
                EGL10.EGL_SURFACE_TYPE, EGL10.EGL_WINDOW_BIT,
                EGL10.EGL_NONE
            )
            
            val numConfigs = IntArray(1)
            if (!egl.eglChooseConfig(display, attribs, null, 0, numConfigs)) {
                Log.w("GLRetroView", "eglChooseConfig failed, falling back")
                return chooseFallbackConfig(egl, display)
            }
            
            if (numConfigs[0] <= 0) {
                Log.w("GLRetroView", "No matching config, falling back")
                return chooseFallbackConfig(egl, display)
            }
            
            val configs = arrayOfNulls<EGLConfig>(numConfigs[0])
            egl.eglChooseConfig(display, attribs, configs, numConfigs[0], numConfigs)
            
            // Pick the first config that meets our requirements
            for (config in configs) {
                if (config != null && validateConfig(egl, display, config)) {
                    return config
                }
            }
            
            return chooseFallbackConfig(egl, display)
        }
        
        private fun validateConfig(egl: EGL10, display: EGLDisplay, config: EGLConfig): Boolean {
            val depth = getAttrib(egl, display, config, EGL10.EGL_DEPTH_SIZE)
            val stencil = getAttrib(egl, display, config, EGL10.EGL_STENCIL_SIZE)
            return depth >= 16 && stencil >= 0  // Accept depth>=16, stencil optional
        }
        
        private fun getAttrib(egl: EGL10, display: EGLDisplay, config: EGLConfig, attribute: Int): Int {
            val value = IntArray(1)
            egl.eglGetConfigAttrib(display, config, attribute, value)
            return value[0]
        }
        
        private fun chooseFallbackConfig(egl: EGL10, display: EGLDisplay): EGLConfig {
            // Minimal fallback: just request ES2/ES3 renderable
            val fallbackAttribs = intArrayOf(
                EGL10.EGL_RENDERABLE_TYPE, 
                if (openGLESVersion >= 3) 0x0040 else 0x0004,
                EGL10.EGL_SURFACE_TYPE, EGL10.EGL_WINDOW_BIT,
                EGL10.EGL_NONE
            )
            val numConfigs = IntArray(1)
            egl.eglChooseConfig(display, fallbackAttribs, null, 0, numConfigs)
            val configs = arrayOfNulls<EGLConfig>(numConfigs[0])
            egl.eglChooseConfig(display, fallbackAttribs, configs, numConfigs[0], numConfigs)
            return configs[0] ?: throw RuntimeException("No EGL config available at all")
        }
    })
    
    setRenderer(Renderer())
    keepScreenOn = true
}

Refinements / caveats:

Fallback strategy is good; you might want to log what you actually got (depth/stencil sizes) so you can debug device‑specific quirks.

Some devices/drivers don’t support stencil at all; our fallback that accepts stencil >= 0 is appropriate.

On a few devices, EGL_OPENGL_ES3_BIT may not be advertised even though ES3 is supported; if we see odd failures, we can try:

First query with ES3 bit.
If that fails, retry with ES2 bit but still request ES3 context via setEGLContextClientVersion(3) (some drivers tolerate this).

2. Validate Context Type in SET_HW_RENDER
File: environment.cpp

Problem: If a core requests RETRO_HW_CONTEXT_VULKAN, the frontend returns true and sets up EGL anyway. The core then tries to use Vulkan through EGL → crash.
Fix:

bool Environment::environment_handle_set_hw_render(struct retro_hw_render_callback* hw_render_callback) {
    // Validate context type BEFORE accepting
    switch (hw_render_callback->context_type) {
        case RETRO_HW_CONTEXT_OPENGLES2:
        case RETRO_HW_CONTEXT_OPENGLES3:
        case RETRO_HW_CONTEXT_OPENGL:
        case RETRO_HW_CONTEXT_OPENGLES_VERSION:
            useHWAcceleration = true;
            useVulkan = false;
            break;
            
        case RETRO_HW_CONTEXT_VULKAN:
            // We don't support Vulkan yet (or we do via separate path)
            // Returning false tells the core to try a different context type or fail gracefully
            LOGE("Vulkan context requested but not supported in this build");
            return false;
            
        default:
            LOGE("Unsupported HW context type: %u", hw_render_callback->context_type);
            return false;
    }
    
    useDepth = hw_render_callback->depth;
    useStencil = hw_render_callback->stencil;
    bottomLeftOrigin = hw_render_callback->bottom_left_origin;
    hw_context_destroy = hw_render_callback->context_destroy;
    hw_context_reset = hw_render_callback->context_reset;
    hw_render_callback->get_current_framebuffer = callback_get_current_framebuffer;
    hw_render_callback->get_proc_address = &eglGetProcAddress;
    
    LOGI("HW render accepted: type=%u depth=%d stencil=%d bottomLeft=%d",
         hw_render_callback->context_type, useDepth, useStencil, bottomLeftOrigin);
    
    return true;
}

Refinements:

RETRO_HW_CONTEXT_OPENGLES_VERSION is used by some newer cores to request “any ES version”; your handling is fine as long as you’ve already negotiated a specific version via GET_PREFERRED_HW_RENDER.

Make sure useVulkan is consistently used elsewhere (e.g. in video init) so you don’t accidentally mix GL and Vulkan logic.

3. Respond to GET_HW_RENDER_CONTEXT_NEGOTIATION_INTERFACE_SUPPORT
File: environment.cpp

Problem: Vulkan-capable cores first query RETRO_ENVIRONMENT_GET_HW_RENDER_CONTEXT_NEGOTIATION_INTERFACE_SUPPORT (cmd 42). If we don't handle it, they may still proceed to request Vulkan via SET_HW_RENDER. If we handle it and return false for Vulkan, they know to fall back to OpenGL.
Fix:

// In handle_callback_environment, add this case BEFORE SET_HW_RENDER:

case RETRO_ENVIRONMENT_GET_HW_RENDER_CONTEXT_NEGOTIATION_INTERFACE_SUPPORT: {
    auto* support = static_cast<struct retro_hw_render_context_negotiation_interface_support*>(data);
    if (support->interface_type == RETRO_HW_RENDER_CONTEXT_NEGOTIATION_INTERFACE_VULKAN) {
        // Tell the core: we do NOT support Vulkan negotiation interface
        // This encourages Vulkan cores to fall back to OpenGL
        support->interface_version = 0;
        return false;
    }
    return false;
}

case RETRO_ENVIRONMENT_SET_HW_RENDER_CONTEXT_NEGOTIATION_INTERFACE: {
    // If a core tries to set Vulkan negotiation interface, reject it
    auto* iface = static_cast<struct retro_hw_render_context_negotiation_interface*>(data);
    if (iface->interface_type == RETRO_HW_RENDER_CONTEXT_NEGOTIATION_INTERFACE_VULKAN) {
        LOGW("Vulkan negotiation interface rejected - not supported");
        return false;
    }
    return false;
}

This correctly tells Vulkan‑capable cores: “We don’t support the Vulkan negotiation interface,” so they should fall back to GL if possible.

Refinements:

For GET_HW_RENDER_CONTEXT_NEGOTIATION_INTERFACE_SUPPORT, the usual pattern is:

If the core asks about Vulkan and you don’t support it: return false.

If in the future you add Vulkan support: return true and set interface_version appropriately.

Our current implementation always returns false for everything except Vulkan queries (which also get false). That’s fine now, but we might want this version instead:

case RETRO_ENVIRONMENT_GET_HW_RENDER_CONTEXT_NEGOTIATION_INTERFACE_SUPPORT: {
    auto* support = static_cast<
        struct retro_hw_render_context_negotiation_interface_support*>(data);
    if (support->interface_type == 
        RETRO_HW_RENDER_CONTEXT_NEGOTIATION_INTERFACE_VULKAN) {
        support->interface_version = 0;
        return false;  // No Vulkan support
    }
    // For other interface types we don't support either
    support->interface_version = 0;
    return false;
}

For SET_HW_RENDER_CONTEXT_NEGOTIATION_INTERFACE, you can log and return false for Vulkan, as you do. For future GL negotiation interfaces (if any), you’d handle them similarly to your current SET_HW_RENDER.

4. Fix updateRelativeForegroundBounds Skipping Vertex 0
File: videolayout.cpp

Problem: Loop starts at i = 2, skipping vertex 0 (bottom-left corner). Bounds for immersive mode background are slightly wrong when rotation or viewport offsets cause vertex 0 to extend beyond the other three vertices.
Fix:

void VideoLayout::updateRelativeForegroundBounds() {
    float xMin = std::numeric_limits<float>::max();
    float xMax = std::numeric_limits<float>::lowest();
    float yMin = std::numeric_limits<float>::max();
    float yMax = std::numeric_limits<float>::lowest();
    
    // FIX: Start at i = 0, not i = 2. Include ALL vertices.
    for (size_t i = 0; i < foregroundVertices.size(); i += 2) {
        float x = foregroundVertices[i];
        float y = (bottomLeftOrigin ? 1.0F : -1.0F) * foregroundVertices[i + 1];
        xMin = std::min(xMin, x);
        xMax = std::max(xMax, x);
        yMin = std::min(yMin, y);
        yMax = std::max(yMax, y);
    }
    
    relativeForegroundBounds[0] = (xMin + 1.0F) / 2.0F;
    relativeForegroundBounds[1] = (yMin + 1.0F) / 2.0F;
    relativeForegroundBounds[2] = (xMax + 1.0F) / 2.0F;
    relativeForegroundBounds[3] = (yMax + 1.0F) / 2.0F;
}

5. Handle Context Loss Properly
File: GLRetroView.kt + libretrodroid.cpp

Problem: preserveEGLContextOnPause = true is unreliable on many Android devices. When the context is lost, all core GL resources (textures, FBOs, shader programs) become invalid, but the core is never told to reinitialize them.
Fix (Kotlin side):

inner class Renderer : GLSurfaceView.Renderer {
    private var contextLost = false
    
    override fun onDrawFrame(gl: GL10) = catchExceptions {
        // Check for GL errors that indicate context loss
        val error = gl.glGetError()
        if (error == 0x0506 /* GL_INVALID_OPERATION */ && contextLost) {
            // Context was lost and is now restored - need to reinit
            LibretroDroid.onContextLostAndRestored()
            contextLost = false
        }
        if (isEmulationReady) {
            LibretroDroid.step(this@GLRetroView)
        }
    }
    
    override fun onSurfaceCreated(gl: GL10, config: EGLConfig) = catchExceptions {
        // If surface is recreated after we already loaded a game,
        // the GL context was destroyed and recreated
        if (isGameLoaded) {
            contextLost = true
            LOGW("GL context recreated - core resources need reinitialization")
        }
        Thread.currentThread().priority = Thread.MAX_PRIORITY
        initializeCore()
        lifecycle?.coroutineScope?.launch {
            retroGLEventsSubject.emit(GLRetroEvents.SurfaceCreated)
        }
    }
}

Fix (C++ side, libretrodroid.cpp):

void LibretroDroid::onContextLostAndRestored() {
    std::lock_guard<std::mutex> lock(coreLock);
    LOGI("GL context restored - calling hw_context_reset");
    
    // Tell the core to reinitialize all its GL resources
    if (Environment::getInstance().getHwContextReset() != nullptr) {
        Environment::getInstance().getHwContextReset()();
    }
    
    // Recreate our own renderer resources too
    if (video) {
        // Force reinitialization of FBOs and shaders
        // The Video class needs a method to reinit its GL resources
        video->reinitializeGLResources();
    }
}

And add to video.h:

void reinitializeGLResources() {
    loadedShaderType.reset();  // Force shader recreation
    updateProgram();
    if (renderer) {
        renderer->reinitialize();  // Add this to Renderer interface
    }
}

Conceptually correct; just to refine the detection strategy a little.

Our approach:

Kotlin side:
1. Track contextLost flag.
2. In onSurfaceCreated, if a game is already loaded, treat this as a context recreation → mark contextLost = true.
3. In onDrawFrame, detect GL_INVALID_OPERATION combined with contextLost and call LibretroDroid.onContextLostAndRestored().

C++ side:
1. In onContextLostAndRestored(), call the stored hw_context_reset callback so the core can rebuild GL resources.
2. Reinitialize your own GL resources (shaders, FBOs, etc.) via video->reinitializeGLResources().

Refinements / caveats:

Don’t rely solely on glGetError():

GL_INVALID_OPERATION can occur for reasons other than context loss.

A more robust signal is the lifecycle:
Treat any onSurfaceCreated() after the first (while a game is loaded) as a context reset event.

Optionally combine with EGL error checks (eglGetError()) if you suspect driver issues.

You can simplify:
In onSurfaceCreated():

if (isGameLoaded) {
    LibretroDroid.onContextRecreated()
}

Call context_destroy before losing the context:

In onSurfaceDestroyed() (or before you tear down GL), call hw_context_destroy() if available, so the core can clean up.

Ensure ordering:

context_destroy → tear down GL → recreate GL → context_reset.

Your current design effectively does context_reset on recreation; just ensure you also call context_destroy when appropriate.

6. Request 24-bit Depth Instead of 16-bit
File: es3utils.cpp

Problem: When a core requests depth but NOT stencil, it gets GL_DEPTH_COMPONENT16 (only 16 bits). Cores like Beetle PSX HW and Mupen64Plus expect at least 24-bit depth buffer precision.
Fix:

if (includeDepth) {
    glBindRenderbuffer(GL_RENDERBUFFER, result->depth.value());
    glRenderbufferStorage(
        GL_RENDERBUFFER,
        includeStencil ? GL_DEPTH24_STENCIL8 : GL_DEPTH_COMPONENT24,  // Was GL_DEPTH_COMPONENT16
        width,
        height
    );
    // ...
}

Many cores (Mupen64Plus, Beetle PSX HW, etc.) expect at least 24‑bit depth precision.

Using GL_DEPTH_COMPONENT16 can cause z‑fighting, incorrect depth tests, or subtle rendering bugs.

Refinements:
Always check FBO completeness after attaching depth/stencil:

GLenum status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
if (status != GL_FRAMEBUFFER_COMPLETE) {
    // Fallback: try depth only, or no depth/stencil
}

Some devices/drivers don’t support GL_DEPTH24_STENCIL8; if you see incomplete FBOs, add a fallback:

Try GL_DEPTH_COMPONENT24 without stencil.
Then GL_DEPTH_COMPONENT16.
Then no depth.

After each fix, test with:
1. Software core first (e.g., Gambatte, Snes9x) — verify no regression
2. Simple HW core (e.g., PCSXReARMed with NEON) — verify basic HW rendering works
3. Complex HW core (e.g., Mupen64Plus-Next GLES3) — verify depth testing works
4. Beetle PSX HW — the acid test for depth/stencil and precision
5. Pause/resume cycle — test context loss handling