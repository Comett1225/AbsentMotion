#include "gpu.h"
#ifdef _WIN32
#include <windows.h>
static void* OpenVulkan() { return LoadLibraryW(L"vulkan-1.dll"); }
static void* VulkanSymbol(void* library, const char* name) { return (void*)GetProcAddress((HMODULE)library, name); }
static void CloseVulkan(void* library) { FreeLibrary((HMODULE)library); }
#else
#include <dlfcn.h>
static void* OpenVulkan() { void* library = dlopen("libvulkan.so.1", RTLD_NOW | RTLD_LOCAL); return library ? library : dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL); }
static void* VulkanSymbol(void* library, const char* name) { return dlsym(library, name); }
static void CloseVulkan(void* library) { dlclose(library); }
#endif
#include <vector>
#include "include/gpu/ganesh/GrDirectContext.h"
#include "include/gpu/ganesh/vk/GrVkDirectContext.h"
#include "include/gpu/vk/VulkanBackendContext.h"
#include "include/gpu/vk/VulkanExtensions.h"
#include "src/gpu/GpuTypesPriv.h"
#include "src/gpu/vk/vulkanmemoryallocator/VulkanMemoryAllocatorPriv.h"

struct VulkanGpu : DesktopGpu {
    void* library = nullptr;
    PFN_vkGetInstanceProcAddr getInstance = nullptr;
    PFN_vkGetDeviceProcAddr getDevice = nullptr;
    VkInstance instance = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
    sk_sp<GrDirectContext> gpu;
    GrDirectContext* context() override { return gpu.get(); }
    ~VulkanGpu() override {
        gpu.reset();
        if (device && getDevice) {
            auto wait = (PFN_vkDeviceWaitIdle)getDevice(device, "vkDeviceWaitIdle");
            auto destroy = (PFN_vkDestroyDevice)getDevice(device, "vkDestroyDevice");
            if (wait) wait(device);
            if (destroy) destroy(device, nullptr);
        }
        if (instance && getInstance) {
            auto destroy = (PFN_vkDestroyInstance)getInstance(instance, "vkDestroyInstance");
            if (destroy) destroy(instance, nullptr);
        }
        if (library) CloseVulkan(library);
    }
    bool init() {
        library = OpenVulkan();
        if (!library) return false;
        getInstance = (PFN_vkGetInstanceProcAddr)VulkanSymbol(library, "vkGetInstanceProcAddr");
        if (!getInstance) return false;
        auto createInstance = (PFN_vkCreateInstance)getInstance(nullptr, "vkCreateInstance");
        if (!createInstance) return false;
        VkApplicationInfo app = {VK_STRUCTURE_TYPE_APPLICATION_INFO};
        app.pApplicationName = "AbsentMotion";
        app.apiVersion = VK_API_VERSION_1_1;
        VkInstanceCreateInfo instanceInfo = {VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
        instanceInfo.pApplicationInfo = &app;
        if (createInstance(&instanceInfo, nullptr, &instance) != VK_SUCCESS) return false;
        auto enumerate = (PFN_vkEnumeratePhysicalDevices)getInstance(instance, "vkEnumeratePhysicalDevices");
        auto properties = (PFN_vkGetPhysicalDeviceProperties)getInstance(instance, "vkGetPhysicalDeviceProperties");
        auto queues = (PFN_vkGetPhysicalDeviceQueueFamilyProperties)getInstance(instance, "vkGetPhysicalDeviceQueueFamilyProperties");
        auto createDevice = (PFN_vkCreateDevice)getInstance(instance, "vkCreateDevice");
        getDevice = (PFN_vkGetDeviceProcAddr)getInstance(instance, "vkGetDeviceProcAddr");
        if (!enumerate || !properties || !queues || !createDevice || !getDevice) return false;
        uint32_t count = 0;
        if (enumerate(instance, &count, nullptr) != VK_SUCCESS || !count) return false;
        std::vector<VkPhysicalDevice> devices(count);
        if (enumerate(instance, &count, devices.data()) != VK_SUCCESS) return false;
        VkPhysicalDevice physical = VK_NULL_HANDLE;
        uint32_t family = UINT32_MAX;
        int best = 4;
        for (auto candidate : devices) {
            VkPhysicalDeviceProperties props{};
            properties(candidate, &props);
            if (props.apiVersion < VK_API_VERSION_1_1) continue;
            const int rank = props.deviceType == VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU ? 0 : props.deviceType == VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU ? 1
                : props.deviceType == VK_PHYSICAL_DEVICE_TYPE_CPU ? 3 : 2;
            if (rank >= best) continue;
            uint32_t n = 0;
            queues(candidate, &n, nullptr);
            std::vector<VkQueueFamilyProperties> families(n);
            queues(candidate, &n, families.data());
            for (uint32_t i = 0; i < n; ++i) {
                if (families[i].queueCount && (families[i].queueFlags & VK_QUEUE_GRAPHICS_BIT)) {
                    physical = candidate;
                    family = i;
                    best = rank;
                    break;
                }
            }
        }
        if (!physical) return false;
        float priority = 1;
        VkDeviceQueueCreateInfo queueInfo = {VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};
        queueInfo.queueFamilyIndex = family;
        queueInfo.queueCount = 1;
        queueInfo.pQueuePriorities = &priority;
        VkDeviceCreateInfo deviceInfo = {VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};
        deviceInfo.queueCreateInfoCount = 1;
        deviceInfo.pQueueCreateInfos = &queueInfo;
        if (createDevice(physical, &deviceInfo, nullptr, &device) != VK_SUCCESS) return false;
        auto getQueue = (PFN_vkGetDeviceQueue)getDevice(device, "vkGetDeviceQueue");
        if (!getQueue) return false;
        VkQueue queue = VK_NULL_HANDLE;
        getQueue(device, family, 0, &queue);
        if (!queue) return false;
        auto proc = [this](const char* name, VkInstance inst, VkDevice dev) -> PFN_vkVoidFunction {
            return dev ? getDevice(dev, name) : getInstance(inst, name);
        };
        skgpu::VulkanExtensions extensions;
        extensions.init(proc, instance, physical, 0, nullptr, 0, nullptr);
        skgpu::VulkanBackendContext backend;
        backend.fInstance = instance;
        backend.fPhysicalDevice = physical;
        backend.fDevice = device;
        backend.fQueue = queue;
        backend.fGraphicsQueueIndex = family;
        backend.fMaxAPIVersion = VK_API_VERSION_1_1;
        backend.fVkExtensions = &extensions;
        backend.fGetProc = proc;
        backend.fMemoryAllocator = skgpu::VulkanMemoryAllocators::Make(backend, skgpu::ThreadSafe::kYes);
        if (!backend.fMemoryAllocator) return false;
        gpu = GrDirectContexts::MakeVulkan(backend);
        return gpu != nullptr;
    }
};

std::unique_ptr<DesktopGpu> CreateDesktopGpu() {
    auto gpu = std::make_unique<VulkanGpu>();
    if (!gpu->init()) return nullptr;
    return gpu;
}
