Integrating Vulkan Support

This is a larger undertaking. The libretro Vulkan interface uses a context negotiation pattern rather than the simple get_proc_address model of OpenGL.
Key Components Needed
1. Add Vulkan Headers to CMakeLists.txt

find_package(Vulkan REQUIRED)
target_link_libraries(libretrodroid Vulkan::Vulkan)

2. Implement the Vulkan Negotiation Interface
In environment.h, add support for RETRO_ENVIRONMENT_SET_HW_RENDER_CONTEXT_NEGOTIATION_INTERFACE (cmd 43):

// Forward declare Vulkan types
typedef struct VkInstance_T* VkInstance;
typedef struct VkPhysicalDevice_T* VkPhysicalDevice;
typedef struct VkDevice_T* VkDevice;
typedef uint64_t VkSurfaceKHR;

struct VulkanNegotiationState {
    VkInstance instance = VK_NULL_HANDLE;
    VkPhysicalDevice physicalDevice = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
    VkSurfaceKHR surface = VK_NULL_HANDLE;
    uint32_t queueFamilyIndex = 0;
    uint32_t apiVersion = VK_API_VERSION_1_0;
};

// Add to Environment class
VulkanNegotiationState vulkanState;

3. Handle the Negotiation Interface Callback
In environment.cpp, add a new case:

case RETRO_ENVIRONMENT_SET_HW_RENDER_CONTEXT_NEGOTIATION_INTERFACE: {
    auto* iface = static_cast<struct retro_hw_render_context_negotiation_interface*>(data);
    
    if (iface->interface_type != 
        RETRO_HW_RENDER_CONTEXT_NEGOTIATION_INTERFACE_VULKAN) {
        return false;
    }
    
    auto* vkIface = reinterpret_cast<
        struct retro_hw_render_context_negotiation_interface_vulkan*>(iface);
    
    // The core will call our create_instance callback first
    vkIface->create_instance = &Environment::vulkanCreateInstance;
    vkIface->create_device = &Environment::vulkanCreateDevice;
    vkIface->destroy_device = &Environment::vulkanDestroyDevice;
    vkIface->destroy_instance = &Environment::vulkanDestroyInstance;
    vkIface->get_physical_device = &Environment::vulkanGetPhysicalDevice;
    vkIface->get_queue_index = &Environment::vulkanGetQueueIndex;
    vkIface->get_surface = &Environment::vulkanGetSurface;
    vkIface->set_swapchain = &Environment::vulkanSetSwapchain;
    vkIface->set_image_index = &Environment::vulkanSetImageIndex;
    
    return true;
}

4. Implement Vulkan Negotiation Callbacks

// Static callbacks that delegate to the singleton
VkInstance Environment::vulkanCreateInstance(
    const struct retro_vulkan_context* context,
    PFN_vkGetInstanceProcAddr getInstanceProcAddr,
    void* userData,
    const VkApplicationInfo* appInfo,
    uint32_t instanceExtensionCount,
    const char* const* instanceExtensions,
    uint32_t layerCount,
    const char* const* layers) {
    
    auto& env = getInstance();
    auto& vk = env.vulkanState;
    
    // Store the getInstanceProcAddr for our own use
    PFN_vkCreateInstance vkCreateInstance = 
        reinterpret_cast<PFN_vkCreateInstance>(getInstanceProcAddr(nullptr, "vkCreateInstance"));
    
    VkInstanceCreateInfo createInfo = { VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO };
    createInfo.pApplicationInfo = appInfo;
    
    // Always require VK_KHR_surface + VK_KHR_android_surface
    std::vector<const char*> extensions;
    for (uint32_t i = 0; i < instanceExtensionCount; i++) {
        extensions.push_back(instanceExtensions[i]);
    }
    extensions.push_back(VK_KHR_SURFACE_EXTENSION_NAME);
    extensions.push_back(VK_KHR_ANDROID_SURFACE_EXTENSION_NAME);
    
    createInfo.enabledExtensionCount = extensions.size();
    createInfo.ppEnabledExtensionNames = extensions.data();
    createInfo.enabledLayerCount = layerCount;
    createInfo.ppEnabledLayerNames = layers;
    
    VkInstance instance = VK_NULL_HANDLE;
    VkResult result = vkCreateInstance(&createInfo, nullptr, &instance);
    
    if (result == VK_SUCCESS) {
        vk.instance = instance;
        vk.apiVersion = context->requested_api_version;
        return instance;
    }
    return VK_NULL_HANDLE;
}

// Similarly implement: vulkanCreateDevice, vulkanDestroyDevice, 
// vulkanDestroyInstance, vulkanGetPhysicalDevice, vulkanGetQueueIndex,
// vulkanGetSurface, vulkanSetSwapchain, vulkanSetImageIndex

5. Create Android Surface from Native Window
In libretrodroid.cpp, you need to pass the ANativeWindow* to create the VkSurfaceKHR:

// Store the ANativeWindow from the GLSurfaceView (or use a SurfaceView)
void LibretroDroid::setNativeWindow(ANativeWindow* window) {
    nativeWindow = window;
    if (Environment::getInstance().isUsingVulkan()) {
        Environment::getInstance().createVulkanSurface(window);
    }
}

6. Replace GLSurfaceView with SurfaceView for Vulkan
Vulkan doesn't use GLSurfaceView. You'll need a SurfaceView and handle the SurfaceHolder.Callback:

class VulkanRetroView(context: Context) : SurfaceView(context), 
    SurfaceHolder.Callback, Choreographer.FrameCallback {
    
    init {
        holder.addCallback(this)
    }
    
    override fun surfaceCreated(holder: SurfaceHolder) {
        val window = ANativeWindow_fromSurface(
            context.getSystemService(Context.NATIVE_SERVICE) as NativeService,
            holder.surface
        )
        LibretroDroid.setNativeWindow(window)
        LibretroDroid.onSurfaceCreated()
        Choreographer.getInstance().postFrameCallback(this)
    }
    
    override fun doFrame(frameTimeNanos: Long) {
        LibretroDroid.stepVulkan()
        Choreographer.getInstance().postFrameCallback(this)
    }
}

7. Vulkan Presentation Pipeline
Instead of the OpenGL FramebufferRenderer, you'll need:
A swapchain created from the VkSurfaceKHR
Command buffers that acquire the next swapchain image
A way for the core to render into swapchain images (or into offscreen images that you then blit/copy to the swapchain)
Synchronization using semaphores and fences
The core will render into images you provide via the swapchain, or you can let the core render into its own images and you composite them to the swapchain with your own shaders (similar to how the OpenGL path works).

