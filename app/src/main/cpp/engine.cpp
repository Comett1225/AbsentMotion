#include "text.h"
#include "engine.h"
#include "preview.h"
#include "effects.h"
#include "drawing.h"
#include "interpolation.h"

#include <android/hardware_buffer.h>
#include <android/log.h>
#include <android/native_window.h>
#include <vulkan/vulkan.h>

#include <algorithm>
#include <chrono>
#include <cinttypes>
#include <cmath>
#include <cstring>
#include <functional>
#include <map>
#include <vector>

#include "include/android/SkImageAndroid.h"
#include "include/android/vk/AndroidVulkanMemoryAllocator.h"
#include "include/core/SkCanvas.h"
#include "include/core/SkColorSpace.h"
#include "include/core/SkColorFilter.h"
#include "include/core/SkImage.h"
#include "include/core/SkPaint.h"
#include "include/core/SkPathBuilder.h"
#include "include/core/SkString.h"
#include "include/core/SkSurface.h"
#include "include/effects/SkImageFilters.h"
#include "include/effects/SkRuntimeEffect.h"
#include "include/gpu/MutableTextureState.h"
#include "include/gpu/ganesh/GrBackendSemaphore.h"
#include "include/gpu/ganesh/GrBackendSurface.h"
#include "include/gpu/ganesh/GrDirectContext.h"
#include "include/gpu/ganesh/SkSurfaceGanesh.h"
#include "include/gpu/ganesh/vk/GrVkBackendSemaphore.h"
#include "include/gpu/ganesh/vk/GrVkBackendSurface.h"
#include "include/gpu/ganesh/vk/GrVkDirectContext.h"
#include "include/gpu/ganesh/vk/GrVkTypes.h"
#include "include/gpu/graphite/BackendSemaphore.h"
#include "src/gpu/graphite/vk/VulkanGraphiteUtils.h"
#include "include/gpu/graphite/BackendTexture.h"
#include "include/gpu/graphite/Context.h"
#include "include/gpu/graphite/ContextOptions.h"
#include "include/gpu/graphite/GraphiteTypes.h"
#include "include/gpu/graphite/Image.h"
#include "include/gpu/graphite/Recorder.h"
#include "include/gpu/graphite/Recording.h"
#include "include/gpu/graphite/Surface.h"
#include "include/gpu/graphite/vk/VulkanGraphiteContext.h"
#include "include/gpu/graphite/vk/VulkanGraphiteTypes.h"
#include "include/gpu/vk/VulkanBackendContext.h"
#include "include/gpu/vk/VulkanExtensions.h"
#include "include/gpu/vk/VulkanMutableTextureState.h"
#include "include/gpu/vk/VulkanPreferredFeatures.h"
#include "include/gpu/vk/VulkanTypes.h"
#include "scene.h"

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "AMEngine", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "AMEngine", __VA_ARGS__)

namespace am {

struct Target {
    ANativeWindow* window = nullptr;
    VkSurfaceKHR surface = VK_NULL_HANDLE;
    VkSwapchainKHR swapchain = VK_NULL_HANDLE;
    int width = 0, height = 0;
    std::vector<sk_sp<SkSurface>> surfaces;
    std::vector<VkSemaphore> renderDone;
    uint32_t nextPresentId = 1;
    std::vector<VkPastPresentationTimingGOOGLE> timings;
};

namespace {

constexpr size_t kImportedPerMedia = 6;

PFN_vkVoidFunction GetProc(const char* name, VkInstance instance, VkDevice device) {
    return device != VK_NULL_HANDLE ? vkGetDeviceProcAddr(device, name)
                                    : vkGetInstanceProcAddr(instance, name);
}

class EngineImpl final : public Engine {
public:
    explicit EngineImpl(Backend backend) : fBackend(backend) {}
    ~EngineImpl() override;

    bool init(std::string* error);

    std::string info() const override { return fInfo; }
    bool failed() const override { return fGpuFailed; }
    Target* attach(ANativeWindow* window, bool forEncoder) override;
    void detach(Target* target) override;
    std::string timing(Target* target) override;
    bool setScene(const std::string& xml, std::string* error) override { return ParseScene(xml, &fScene, error); }
    std::string loadEffect(const std::string& xml) override { return fEffects.load(xml); }
    int maxPictureSize() const override { return fGraphite ? fGraphite->maxTextureSize() : fGanesh->maxTextureSize(); }
    bool setPicture(const std::string& uri, int64_t layerId, AHardwareBuffer* buffer,
                    const void* pixels, int width, int height, size_t rowBytes, bool opaque, int colorStandard, int colorRange, const FrameCrop& crop) override;
    bool setVideoFrame(const std::string& uri, int64_t layerId, int64_t pts, AHardwareBuffer* buffer, int colorStandard, int colorRange, const FrameCrop& crop) override;
    bool interpolateVideo(const std::string& uri, int64_t layerId, int64_t a, int64_t b, float t, bool flow,
                          const InterpolationParams& params) override;
    int renderScene(Target*, double timeMs, float scale, float dx, float dy, uint32_t background,
                    int64_t selectedId, int handles, const PreviewOptions& view) override;
    void setCameraView(bool on) override { fCameraView = on; }
    void setAccurateBlur(bool on) override { fAccurateBlur = on; }
    int renderFrame(Target*, double timeMs, uint32_t background, int64_t presentTimeNs) override;
    int renderBuffer(AHardwareBuffer* buffer, double timeMs, uint32_t background) override;
    void releaseBuffers() override;
    bool layerQuad(int64_t layerId, double timeMs, float xy[8]) override;
    bool layerBounds(int64_t layerId, double timeMs, float ltrb[4]) override;
    int64_t hitTest(float x, float y, double timeMs) override;
    bool still(double timeMs, int width, int height, std::vector<uint8_t>* rgba) override;
    bool shapeIcon(const std::string& xml, int size, uint32_t color, std::vector<uint8_t>* rgba) override;
    bool layerOutline(int64_t layerId, double timeMs, std::string* svg) override;

private:
    bool initVulkan(std::string* error);
    sk_sp<SkSurface> wrapSwapchainImage(VkImage, VkFormat, VkImageUsageFlags, int w, int h);
    using MediaKey = std::pair<std::string, int64_t>;
    sk_sp<SkImage> importFrame(AHardwareBuffer*, bool opaque = true, int colorStandard = 0, int colorRange = 0, bool* rawYuv = nullptr,
                               const MediaKey* key = nullptr);
    skgpu::graphite::BackendTexture importTexture(AHardwareBuffer*, int colorStandard, bool* rawYuv);
    void forgetImported(const MediaKey& key);
    sk_sp<SkImage> copyFrame(AHardwareBuffer*, bool opaque, int colorStandard, int colorRange, const FrameCrop& crop,
                             const MediaKey* key = nullptr);
    sk_sp<SkImage> picture(const std::string& uri, int64_t layerId) const;
    MediaPictures pictures() const {
        return [this](const std::string& uri, int64_t id) { return this->picture(uri, id); };
    }
    int frame(Target*, int64_t presentTimeNs, bool sync, const std::function<void(SkCanvas*)>& paint);
    bool offscreen(int width, int height, bool premultiplied, const std::function<void(SkCanvas*)>& paint,
                   std::vector<uint8_t>* rgba);
    void collectTimings(Target*);
    void syncGpu();
    void deleteDeadTextures(skgpu::graphite::Recorder* cleanup = nullptr);
    int failGpu(Target* target);
    bool fGpuFailed = false;
    bool fCameraView = true;
    bool fAccurateBlur = false;
    std::vector<VkSemaphore> fFailedSemaphores;
    std::vector<VkSemaphore> fSpareSemaphores;

    EffectRuntime fEffects;
    LayerEffects layerEffects() {
        LayerEffects fx;
        fx.active = [this](const Layer& layer) { return fEffects.active(layer); };
        fx.lifted = [this](const Layer& layer) { return fEffects.lifted(layer); };
        fx.draw = [this](SkCanvas* canvas, const Scene& scene, const Layer& layer, double time, const SkRect& area,
                         const std::function<void(SkCanvas*)>& source) {
            return fEffects.draw(canvas, scene, layer, time, area, source);
        };
        return fx;
    }
    const Backend fBackend;
    std::string fInfo;

    VkInstance fInstance = VK_NULL_HANDLE;
    VkPhysicalDevice fPhysicalDevice = VK_NULL_HANDLE;
    VkDevice fDevice = VK_NULL_HANDLE;
    VkQueue fQueue = VK_NULL_HANDLE;
    uint32_t fQueueFamily = 0;
    uint32_t fApiVersion = 0;
    bool fDisplayTiming = false;
    bool fPassThroughColorSpace = false;
    PFN_vkGetPastPresentationTimingGOOGLE fGetPastPresentationTiming = nullptr;
    PFN_vkGetRefreshCycleDurationGOOGLE fGetRefreshCycleDuration = nullptr;

    skgpu::VulkanPreferredFeatures fSkiaFeatures;
    VkPhysicalDeviceFeatures2 fFeatures = {VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2};
    skgpu::VulkanExtensions fExtensions;

    std::unique_ptr<skgpu::graphite::Context> fGraphite;
    std::unique_ptr<skgpu::graphite::Recorder> fRecorder;
    struct DeadTexture { skgpu::graphite::BackendTexture texture; AHardwareBuffer* buffer; };
    std::vector<DeadTexture> fDeadTextures;
    struct ImportedTexture {
        uint64_t id;
        int colorStandard;
        bool rawYuv;
        VkImageLayout layout;
        uint32_t queueFamily;
        DeadTexture held;
        skgpu::graphite::BackendTexture view;
        sk_sp<SkImage> image;
        SkAlphaType alpha = kUnknown_SkAlphaType;
    };
    std::map<MediaKey, std::vector<ImportedTexture>> fImported;
    sk_sp<GrDirectContext> fGanesh;

    std::vector<std::unique_ptr<Target>> fTargets;
    Scene fScene;
    std::map<std::pair<std::string, int64_t>, sk_sp<SkImage>> fPictures;
    struct VideoFrames {
        std::map<int64_t, sk_sp<SkImage>> frames;
        std::map<std::pair<int64_t, int64_t>, sk_sp<SkImage>> flows;
        InterpolationParams params;
    };
    double heldMegabytes() const;
    sk_sp<SkImage> flowFor(VideoFrames& video, int64_t a, int64_t b, const InterpolationParams& params);
    std::map<std::pair<std::string, int64_t>, VideoFrames> fVideoFrames;
    std::map<uint64_t, sk_sp<SkSurface>> fBufferSurfaces;
    SkSurface* bufferSurface(AHardwareBuffer* buffer);
    bool fLoggedFrame = false;
};

bool EngineImpl::initVulkan(std::string* error) {
    auto fail = [&](const char* what, VkResult r) {
        *error = std::string(what) + " failed (" + std::to_string(r) + ")";
        LOGE("%s", error->c_str());
        return false;
    };

    uint32_t loaderVersion = VK_API_VERSION_1_0;
    vkEnumerateInstanceVersion(&loaderVersion);
    fApiVersion = std::min(loaderVersion, (uint32_t)VK_API_VERSION_1_4);
    fApiVersion = VK_MAKE_API_VERSION(0, VK_API_VERSION_MAJOR(fApiVersion),
                                      VK_API_VERSION_MINOR(fApiVersion), 0);
    if (fApiVersion < VK_API_VERSION_1_2) return fail("Vulkan 1.2 check", VK_ERROR_INCOMPATIBLE_DRIVER);
    fSkiaFeatures.init(fApiVersion);

    uint32_t n = 0;
    vkEnumerateInstanceExtensionProperties(nullptr, &n, nullptr);
    std::vector<VkExtensionProperties> instanceProps(n);
    vkEnumerateInstanceExtensionProperties(nullptr, &n, instanceProps.data());
    std::vector<const char*> instanceExts = {VK_KHR_SURFACE_EXTENSION_NAME,
                                             VK_KHR_ANDROID_SURFACE_EXTENSION_NAME};
    for (const auto& p : instanceProps) {
        if (!strcmp(p.extensionName, VK_EXT_SWAPCHAIN_COLOR_SPACE_EXTENSION_NAME)) {
            instanceExts.push_back(VK_EXT_SWAPCHAIN_COLOR_SPACE_EXTENSION_NAME);
            fPassThroughColorSpace = true;
        }
    }
    fSkiaFeatures.addToInstanceExtensions(instanceProps.data(), instanceProps.size(), instanceExts);

    VkApplicationInfo app = {VK_STRUCTURE_TYPE_APPLICATION_INFO};
    app.pApplicationName = "AbsentMotion";
    app.pEngineName = "amengine";
    app.apiVersion = fApiVersion;
    VkInstanceCreateInfo ici = {VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
    ici.pApplicationInfo = &app;
    ici.enabledExtensionCount = (uint32_t)instanceExts.size();
    ici.ppEnabledExtensionNames = instanceExts.data();
    VkResult r = vkCreateInstance(&ici, nullptr, &fInstance);
    if (r != VK_SUCCESS) return fail("vkCreateInstance", r);

    n = 1;
    r = vkEnumeratePhysicalDevices(fInstance, &n, &fPhysicalDevice);
    if ((r != VK_SUCCESS && r != VK_INCOMPLETE) || n == 0) return fail("vkEnumeratePhysicalDevices", r);
    VkPhysicalDeviceProperties props;
    vkGetPhysicalDeviceProperties(fPhysicalDevice, &props);

    vkGetPhysicalDeviceQueueFamilyProperties(fPhysicalDevice, &n, nullptr);
    std::vector<VkQueueFamilyProperties> families(n);
    vkGetPhysicalDeviceQueueFamilyProperties(fPhysicalDevice, &n, families.data());
    fQueueFamily = UINT32_MAX;
    for (uint32_t i = 0; i < n; i++) {
        if (families[i].queueFlags & VK_QUEUE_GRAPHICS_BIT) { fQueueFamily = i; break; }
    }
    if (fQueueFamily == UINT32_MAX) return fail("graphics queue lookup", VK_ERROR_FEATURE_NOT_PRESENT);

    vkEnumerateDeviceExtensionProperties(fPhysicalDevice, nullptr, &n, nullptr);
    std::vector<VkExtensionProperties> deviceProps(n);
    vkEnumerateDeviceExtensionProperties(fPhysicalDevice, nullptr, &n, deviceProps.data());
    std::vector<const char*> deviceExts;
    auto want = [&](const char* name) {
        for (const auto& p : deviceProps) {
            if (!strcmp(p.extensionName, name)) { deviceExts.push_back(name); return true; }
        }
        return false;
    };
    if (!want(VK_KHR_SWAPCHAIN_EXTENSION_NAME) ||
        !want(VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME) ||
        !want(VK_EXT_QUEUE_FAMILY_FOREIGN_EXTENSION_NAME)) {
        return fail("required device extensions", VK_ERROR_EXTENSION_NOT_PRESENT);
    }
    fDisplayTiming = want(VK_GOOGLE_DISPLAY_TIMING_EXTENSION_NAME);

    fSkiaFeatures.addFeaturesToQuery(deviceProps.data(), deviceProps.size(), fFeatures);
    vkGetPhysicalDeviceFeatures2(fPhysicalDevice, &fFeatures);
    fFeatures.features.robustBufferAccess = VK_FALSE;
    fSkiaFeatures.addFeaturesToEnable(deviceExts, fFeatures);
    for (auto* p = (VkBaseOutStructure*)&fFeatures; p->pNext;) {
        if (p->pNext->sType == 0) p->pNext = p->pNext->pNext; else p = p->pNext;
    }

    float priority = 1.0f;
    VkDeviceQueueCreateInfo qci = {VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};
    qci.queueFamilyIndex = fQueueFamily;
    qci.queueCount = 1;
    qci.pQueuePriorities = &priority;
    VkDeviceCreateInfo dci = {VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};
    dci.pNext = &fFeatures;
    dci.queueCreateInfoCount = 1;
    dci.pQueueCreateInfos = &qci;
    dci.enabledExtensionCount = (uint32_t)deviceExts.size();
    dci.ppEnabledExtensionNames = deviceExts.data();
    r = vkCreateDevice(fPhysicalDevice, &dci, nullptr, &fDevice);
    if (r != VK_SUCCESS) return fail("vkCreateDevice", r);
    vkGetDeviceQueue(fDevice, fQueueFamily, 0, &fQueue);
    if (fDisplayTiming) {
        fGetPastPresentationTiming = (PFN_vkGetPastPresentationTimingGOOGLE)vkGetDeviceProcAddr(
                fDevice, "vkGetPastPresentationTimingGOOGLE");
        fGetRefreshCycleDuration = (PFN_vkGetRefreshCycleDurationGOOGLE)vkGetDeviceProcAddr(
                fDevice, "vkGetRefreshCycleDurationGOOGLE");
        fDisplayTiming = fGetPastPresentationTiming && fGetRefreshCycleDuration;
    }

    fExtensions.init(GetProc, fInstance, fPhysicalDevice, (uint32_t)instanceExts.size(),
                     instanceExts.data(), (uint32_t)deviceExts.size(), deviceExts.data());

    char buf[512];
    snprintf(buf, sizeof(buf),
             "{\"backend\":\"%s\",\"device\":\"%s\",\"api\":\"%u.%u.%u\",\"driverVersion\":%u,"
             "\"displayTiming\":%s,\"deviceExtensions\":[",
             fBackend == Backend::kGraphite ? "graphite" : "ganesh", props.deviceName,
             VK_API_VERSION_MAJOR(props.apiVersion), VK_API_VERSION_MINOR(props.apiVersion),
             VK_API_VERSION_PATCH(props.apiVersion), props.driverVersion,
             fDisplayTiming ? "true" : "false");
    fInfo = buf;
    for (size_t i = 0; i < deviceExts.size(); i++) {
        fInfo += std::string(i ? ",\"" : "\"") + deviceExts[i] + "\"";
    }
    fInfo += "]}";
    return true;
}

bool EngineImpl::init(std::string* error) {
    if (!this->initVulkan(error)) return false;

    skgpu::VulkanBackendContext bc;
    bc.fInstance = fInstance;
    bc.fPhysicalDevice = fPhysicalDevice;
    bc.fDevice = fDevice;
    bc.fQueue = fQueue;
    bc.fGraphicsQueueIndex = fQueueFamily;
    bc.fMaxAPIVersion = fApiVersion;
    bc.fVkExtensions = &fExtensions;
    bc.fDeviceFeatures2 = &fFeatures;
    bc.fGetProc = GetProc;
    bc.fMemoryAllocator = SkiaVMA::Make(bc, {});
    if (!bc.fMemoryAllocator) { *error = "SkiaVMA::Make failed"; return false; }

    if (fBackend == Backend::kGraphite) {
        skgpu::graphite::ContextOptions options;
        fGraphite = skgpu::graphite::ContextFactory::MakeVulkan(bc, options);
        if (fGraphite) fRecorder = fGraphite->makeRecorder();
        if (!fRecorder) { *error = "Graphite context creation failed"; return false; }
    } else {
        fGanesh = GrDirectContexts::MakeVulkan(bc);
        if (!fGanesh) { *error = "Ganesh context creation failed"; return false; }
    }

    LOGI("engine ready: %s", fInfo.c_str());
    return true;
}

EngineImpl::~EngineImpl() {
    while (!fTargets.empty()) this->detach(fTargets.back().get());
    fBufferSurfaces.clear();
    fPictures.clear();
    fVideoFrames.clear();
    while (!fImported.empty()) this->forgetImported(fImported.begin()->first);
    this->syncGpu();
    if (fGraphite) {
        auto cleanup = fGraphite->makeRecorder();
        fRecorder.reset();
        this->syncGpu();
        fGraphite.reset();
        this->deleteDeadTextures(cleanup.get());
    }
    for (VkSemaphore sem : fFailedSemaphores) vkDestroySemaphore(fDevice, sem, nullptr);
    for (VkSemaphore sem : fSpareSemaphores) vkDestroySemaphore(fDevice, sem, nullptr);
    if (fGanesh) {
        this->syncGpu();
        if (fGpuFailed) fGanesh->abandonContext();
        fGanesh.reset();
    }
    if (fDevice) {
        vkDeviceWaitIdle(fDevice);
        vkDestroyDevice(fDevice, nullptr);
    }
    if (fInstance) vkDestroyInstance(fInstance, nullptr);
}

void EngineImpl::deleteDeadTextures(skgpu::graphite::Recorder* cleanup) {
    for (const auto& tex : fDeadTextures) {
        if (cleanup) cleanup->deleteBackendTexture(tex.texture);
        else fGraphite->deleteBackendTexture(tex.texture);
        AHardwareBuffer_release(tex.buffer);
    }
    fDeadTextures.clear();
}

void EngineImpl::syncGpu() {
    if (fGraphite) {
        if (!fGpuFailed && !fGraphite->submit(skgpu::graphite::SyncToCpu::kYes)) fGpuFailed = true;
        this->deleteDeadTextures();
    } else if (fGanesh && !fGpuFailed) {
        fGanesh->flushAndSubmit(GrSyncCpu::kYes);
    }
}

sk_sp<SkSurface> EngineImpl::wrapSwapchainImage(VkImage image, VkFormat format,
                                                VkImageUsageFlags usage, int w, int h) {
    SkSurfaceProps props(0, kUnknown_SkPixelGeometry);
    if (fBackend == Backend::kGraphite) {
        skgpu::graphite::VulkanTextureInfo info;
        info.fFormat = format;
        info.fImageUsageFlags = usage;
        auto tex = skgpu::graphite::BackendTextures::MakeVulkan(
                {w, h}, info, VK_IMAGE_LAYOUT_UNDEFINED, fQueueFamily, image, skgpu::VulkanAlloc());
        return SkSurfaces::WrapBackendTexture(fRecorder.get(), tex, SkColorSpace::MakeSRGB(), &props);
    }
    GrVkImageInfo info;
    info.fImage = image;
    info.fFormat = format;
    info.fImageUsageFlags = usage;
    info.fLevelCount = 1;
    info.fCurrentQueueFamily = fQueueFamily;
    return SkSurfaces::WrapBackendRenderTarget(fGanesh.get(), GrBackendRenderTargets::MakeVk(w, h, info),
                                               kTopLeft_GrSurfaceOrigin, kRGBA_8888_SkColorType,
                                               SkColorSpace::MakeSRGB(), &props);
}

Target* EngineImpl::attach(ANativeWindow* window, bool forEncoder) {
    if (fGpuFailed) return nullptr;
    auto t = std::make_unique<Target>();
    ANativeWindow_acquire(window);
    t->window = window;
    fTargets.push_back(std::move(t));
    Target* target = fTargets.back().get();
    auto fail = [&](const char* what, VkResult r) {
        LOGE("attach: %s failed (%d)", what, r);
        this->detach(target);
        return nullptr;
    };

    VkAndroidSurfaceCreateInfoKHR sci = {VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR};
    sci.window = window;
    VkResult r = vkCreateAndroidSurfaceKHR(fInstance, &sci, nullptr, &target->surface);
    if (r != VK_SUCCESS) return fail("vkCreateAndroidSurfaceKHR", r);

    VkBool32 supported = VK_FALSE;
    vkGetPhysicalDeviceSurfaceSupportKHR(fPhysicalDevice, fQueueFamily, target->surface, &supported);
    VkSurfaceCapabilitiesKHR caps;
    r = vkGetPhysicalDeviceSurfaceCapabilitiesKHR(fPhysicalDevice, target->surface, &caps);
    if (r != VK_SUCCESS || !supported) return fail("surface capabilities", r);

    VkExtent2D extent = caps.currentExtent;
    if (extent.width == UINT32_MAX) {
        extent = {(uint32_t)ANativeWindow_getWidth(window), (uint32_t)ANativeWindow_getHeight(window)};
    }
    target->width = (int)extent.width;
    target->height = (int)extent.height;

    VkImageUsageFlags usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT |
                              VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    usage |= caps.supportedUsageFlags & (VK_IMAGE_USAGE_INPUT_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT);

    VkColorSpaceKHR colorSpace = VK_COLOR_SPACE_SRGB_NONLINEAR_KHR;
    if (forEncoder && fPassThroughColorSpace) {
        uint32_t formatCount = 0;
        vkGetPhysicalDeviceSurfaceFormatsKHR(fPhysicalDevice, target->surface, &formatCount, nullptr);
        std::vector<VkSurfaceFormatKHR> formats(formatCount);
        vkGetPhysicalDeviceSurfaceFormatsKHR(fPhysicalDevice, target->surface, &formatCount, formats.data());
        for (const auto& f : formats) {
            if (f.format == VK_FORMAT_R8G8B8A8_UNORM && f.colorSpace == VK_COLOR_SPACE_PASS_THROUGH_EXT) {
                colorSpace = VK_COLOR_SPACE_PASS_THROUGH_EXT;
            }
        }
    }

    VkSwapchainCreateInfoKHR ci = {VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR};
    ci.surface = target->surface;
    ci.minImageCount = std::max(caps.minImageCount + 1, 3u);
    if (caps.maxImageCount > 0) ci.minImageCount = std::min(ci.minImageCount, caps.maxImageCount);
    ci.imageFormat = VK_FORMAT_R8G8B8A8_UNORM;
    ci.imageColorSpace = colorSpace;
    ci.imageExtent = extent;
    ci.imageArrayLayers = 1;
    ci.imageUsage = usage;
    ci.imageSharingMode = VK_SHARING_MODE_EXCLUSIVE;
    ci.preTransform = (caps.supportedTransforms & VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR)
                              ? VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR : caps.currentTransform;
    ci.compositeAlpha = (caps.supportedCompositeAlpha & VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR)
                                ? VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR : VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;
    ci.presentMode = VK_PRESENT_MODE_FIFO_KHR;
    ci.clipped = VK_TRUE;
    r = vkCreateSwapchainKHR(fDevice, &ci, nullptr, &target->swapchain);
    if (r != VK_SUCCESS) return fail("vkCreateSwapchainKHR", r);

    uint32_t count = 0;
    vkGetSwapchainImagesKHR(fDevice, target->swapchain, &count, nullptr);
    std::vector<VkImage> images(count);
    vkGetSwapchainImagesKHR(fDevice, target->swapchain, &count, images.data());
    for (VkImage image : images) {
        VkSemaphoreCreateInfo semInfo = {VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
        VkSemaphore sem = VK_NULL_HANDLE;
        r = vkCreateSemaphore(fDevice, &semInfo, nullptr, &sem);
        if (r != VK_SUCCESS) return fail("vkCreateSemaphore", r);
        target->renderDone.push_back(sem);
        auto surface = this->wrapSwapchainImage(image, ci.imageFormat, usage, target->width, target->height);
        if (!surface) return fail("wrap swapchain image", VK_ERROR_INITIALIZATION_FAILED);
        target->surfaces.push_back(std::move(surface));
    }
    LOGI("attached %dx%d, %u images, transform 0x%x, color space %d", target->width, target->height,
         count, caps.currentTransform, colorSpace);
    return target;
}

void EngineImpl::detach(Target* target) {
    auto it = std::find_if(fTargets.begin(), fTargets.end(),
                           [&](const auto& t) { return t.get() == target; });
    if (it == fTargets.end()) return;
    if (fRecorder && !fGpuFailed) {
        auto recording = fRecorder->snap();
        if (recording && !fGraphite->insertRecording({recording.get()})) fGpuFailed = true;
    }
    this->syncGpu();
    target->surfaces.clear();
    this->syncGpu();
    if (fDevice) vkDeviceWaitIdle(fDevice);
    for (VkSemaphore sem : target->renderDone) vkDestroySemaphore(fDevice, sem, nullptr);
    if (target->swapchain) vkDestroySwapchainKHR(fDevice, target->swapchain, nullptr);
    if (target->surface) vkDestroySurfaceKHR(fInstance, target->surface, nullptr);
    ANativeWindow_release(target->window);
    fTargets.erase(it);
    if (fTargets.empty() && fGraphite && !fGpuFailed) {
        if (fRecorder) fRecorder->freeGpuResources();
        fGraphite->freeGpuResources();
    }
}

skgpu::graphite::BackendTexture EngineImpl::importTexture(AHardwareBuffer* buffer, int colorStandard, bool* rawYuv) {
    AHardwareBuffer_Desc desc;
    AHardwareBuffer_describe(buffer, &desc);
    auto tex = fRecorder->createBackendTexture(buffer, false,
                                               false,
                                               {(int)desc.width, (int)desc.height});
    if (!tex.isValid()) return tex;
    skgpu::graphite::VulkanTextureInfo textureInfo;
    if ((colorStandard == 1 || colorStandard == 2 || colorStandard == 4 || colorStandard == 6) && skgpu::graphite::TextureInfos::GetVulkanTextureInfo(tex.info(), &textureInfo) && textureInfo.fYcbcrConversionInfo.isValid()) {
        const auto& conversion = textureInfo.fYcbcrConversionInfo;
        const auto model = VK_SAMPLER_YCBCR_MODEL_CONVERSION_YCBCR_IDENTITY;
        const auto range = VK_SAMPLER_YCBCR_RANGE_ITU_FULL;
        VkAndroidHardwareBufferFormatPropertiesANDROID format{VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_FORMAT_PROPERTIES_ANDROID};
        VkAndroidHardwareBufferPropertiesANDROID properties{VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID};
        properties.pNext = &format;
        const auto getProperties = reinterpret_cast<PFN_vkGetAndroidHardwareBufferPropertiesANDROID>(vkGetDeviceProcAddr(fDevice, "vkGetAndroidHardwareBufferPropertiesANDROID"));
        if (getProperties && getProperties(fDevice, buffer, &properties) == VK_SUCCESS) {
            if (conversion.hasExternalFormat()) {
                textureInfo.fYcbcrConversionInfo = skgpu::VulkanYcbcrConversionInfo(conversion.externalFormat(), model, range,
                    conversion.xChromaOffset(), conversion.yChromaOffset(), conversion.chromaFilter(), conversion.forceExplicitReconstruction(), conversion.components(), format.formatFeatures);
            } else {
                textureInfo.fYcbcrConversionInfo = skgpu::VulkanYcbcrConversionInfo(conversion.format(), model, range,
                    conversion.xChromaOffset(), conversion.yChromaOffset(), conversion.chromaFilter(), conversion.forceExplicitReconstruction(), conversion.components(), format.formatFeatures);
            }
            if (rawYuv) *rawYuv = true;
            const auto original = tex;
            tex = skgpu::graphite::BackendTextures::MakeVulkan(original.dimensions(), textureInfo,
                skgpu::graphite::BackendTextures::GetVkImageLayout(original), skgpu::graphite::BackendTextures::GetVkQueueFamilyIndex(original),
                skgpu::graphite::BackendTextures::GetVkImage(original), skgpu::graphite::BackendTextures::GetMemoryAlloc(original));
        }
    }
    return tex;
}

void EngineImpl::forgetImported(const MediaKey& key) {
    auto found = fImported.find(key);
    if (found == fImported.end()) return;
    auto held = std::move(found->second);
    fImported.erase(found);
    for (auto& imported : held) {
        imported.image.reset();
        fDeadTextures.push_back(imported.held);
    }
}

sk_sp<SkImage> EngineImpl::importFrame(AHardwareBuffer* buffer, bool opaque, int colorStandard, int colorRange, bool* rawYuv,
                                       const MediaKey* key) {
    if (!fLoggedFrame) {
        fLoggedFrame = true;
        AHardwareBuffer_Desc desc;
        AHardwareBuffer_describe(buffer, &desc);
        LOGI("first frame buffer: %ux%u format 0x%x usage 0x%" PRIx64, desc.width, desc.height,
             desc.format, desc.usage);
    }
    const SkAlphaType alpha = opaque ? kOpaque_SkAlphaType : kPremul_SkAlphaType;
    if (fBackend == Backend::kGanesh) {
        return SkImages::DeferredFromAHardwareBuffer(buffer, alpha, SkColorSpace::MakeSRGB());
    }
    uint64_t id = 0;
    if (key && AHardwareBuffer_getId(buffer, &id) == 0) {
        auto& cache = fImported[*key];
        auto hit = std::find_if(cache.begin(), cache.end(), [&](const ImportedTexture& t) {
            return t.id == id && t.colorStandard == colorStandard;
        });
        if (hit == cache.end()) {
            bool yuv = false;
            auto tex = this->importTexture(buffer, colorStandard, &yuv);
            if (!tex.isValid()) return nullptr;
            AHardwareBuffer_acquire(buffer);
            if (cache.size() >= kImportedPerMedia) {
                cache.front().image.reset();
                fDeadTextures.push_back(cache.front().held);
                cache.erase(cache.begin());
            }
            cache.push_back({id, colorStandard, yuv, skgpu::graphite::BackendTextures::GetVkImageLayout(tex),
                             skgpu::graphite::BackendTextures::GetVkQueueFamilyIndex(tex), {tex, buffer}});
            hit = cache.end() - 1;
        }
        if (rawYuv) *rawYuv = hit->rawYuv;
        if (hit->image && hit->alpha == alpha) {
            skgpu::graphite::BackendTextures::SetMutableState(&hit->view, skgpu::MutableTextureStates::MakeVulkan(hit->layout, hit->queueFamily));
            return hit->image;
        }
        const auto& held = hit->held.texture;
        skgpu::graphite::VulkanTextureInfo info;
        if (!skgpu::graphite::TextureInfos::GetVulkanTextureInfo(held.info(), &info)) return nullptr;
        hit->view = skgpu::graphite::BackendTextures::MakeVulkan(held.dimensions(), info, hit->layout, hit->queueFamily,
            skgpu::graphite::BackendTextures::GetVkImage(held), skgpu::graphite::BackendTextures::GetMemoryAlloc(held));
        hit->image = SkImages::WrapTexture(fRecorder.get(), hit->view, alpha, SkColorSpace::MakeSRGB());
        hit->alpha = alpha;
        return hit->image;
    }
    auto tex = this->importTexture(buffer, colorStandard, rawYuv);
    if (!tex.isValid()) return nullptr;
    struct Release {
        EngineImpl* engine;
        skgpu::graphite::BackendTexture tex;
        AHardwareBuffer* buffer;
    };
    AHardwareBuffer_acquire(buffer);
    return SkImages::WrapTexture(
            fRecorder.get(), tex, alpha, SkColorSpace::MakeSRGB(),
            [](void* context) {
                auto* release = static_cast<Release*>(context);
                release->engine->fDeadTextures.push_back({release->tex, release->buffer});
                delete release;
            },
            new Release{this, tex, buffer});
}

sk_sp<SkImage> EngineImpl::picture(const std::string& uri, int64_t layerId) const {
    auto found = fPictures.find({uri, layerId});
    if (found == fPictures.end()) found = fPictures.find({uri, 0});
    return found == fPictures.end() ? nullptr : found->second;
}

sk_sp<SkImage> EngineImpl::copyFrame(AHardwareBuffer* buffer, bool opaque, int colorStandard, int colorRange, const FrameCrop& crop,
                                     const MediaKey* key) {
    bool rawYuv = false;
    auto imported = this->importFrame(buffer, opaque, colorStandard, colorRange, &rawYuv, key);
    if (!imported) return nullptr;
    auto bounds = SkIRect::MakeWH(imported->width(), imported->height());
    if (crop.right > crop.left && crop.bottom > crop.top &&
        !bounds.intersect(SkIRect::MakeLTRB(crop.left, crop.top, crop.right, crop.bottom))) return nullptr;
    const auto info = SkImageInfo::Make(bounds.width(), bounds.height(),
            kRGBA_8888_SkColorType, opaque ? kOpaque_SkAlphaType : kPremul_SkAlphaType, SkColorSpace::MakeSRGB());
    auto surface = fGraphite ? SkSurfaces::RenderTarget(fRecorder.get(), info)
                            : SkSurfaces::RenderTarget(fGanesh.get(), skgpu::Budgeted::kNo, info);
    if (!surface) return nullptr;
    SkPaint paint;
    if (rawYuv) {
        const float kr = colorStandard == 1 ? 0.2126f : colorStandard == 6 ? 0.2627f : 0.299f;
        const float kb = colorStandard == 1 ? 0.0722f : colorStandard == 6 ? 0.0593f : 0.114f;
        const float kg = 1 - kr - kb;
        const float ys = colorRange == 2 ? 255.f / 219 : 1.f;
        const float cs = colorRange == 2 ? 255.f / 224 : 1.f;
        const float yo = colorRange == 2 ? -16.f / 219 : 0.f;
        const float ro = 2 * (1 - kr) * cs;
        const float bo = 2 * (1 - kb) * cs;
        const float gr = -2 * kr * (1 - kr) / kg * cs;
        const float gb = -2 * kb * (1 - kb) / kg * cs;
        const float matrix[20] = {ro, ys, 0, 0, yo, gr, ys, gb, 0, yo, 0, ys, bo, 0, yo, 0, 0, 0, 1, 0};
        paint.setColorFilter(SkColorFilters::Matrix(matrix));
    }
    surface->getCanvas()->drawImage(imported, -bounds.left(), -bounds.top(), SkSamplingOptions(), &paint);
    sk_sp<SkImage> image = surface->makeImageSnapshot();
    if (fGraphite) {
        const bool ok = fGraphite->insertRecording({fRecorder->snap().get()}) &&
                        fGraphite->submit(skgpu::graphite::SyncToCpu::kYes);
        if (!ok) image = nullptr;
    } else {
        fGanesh->flushAndSubmit(surface.get(), GrSyncCpu::kYes);
    }
    return image;
}

bool EngineImpl::setPicture(const std::string& uri, int64_t layerId, AHardwareBuffer* buffer,
                            const void* pixels, int width, int height, size_t rowBytes, bool opaque, int colorStandard, int colorRange, const FrameCrop& crop) {
    const auto key = std::make_pair(uri, layerId);
    if (!buffer && !pixels) { fPictures.erase(key); fVideoFrames.erase(key); this->forgetImported(key); return true; }
    sk_sp<SkImage> image;
    if (buffer) {
        image = this->copyFrame(buffer, opaque, colorStandard, colorRange, crop, &key);
    }
    else if (width > 0 && height > 0 && width <= maxPictureSize() && height <= maxPictureSize()) {
        const auto info = SkImageInfo::Make(width, height, kRGBA_8888_SkColorType,
                opaque ? kOpaque_SkAlphaType : kPremul_SkAlphaType, SkColorSpace::MakeSRGB());
        image = SkImages::RasterFromPixmapCopy(SkPixmap(info, pixels, rowBytes));
        if (image && fRecorder) image = SkImages::TextureFromImage(fRecorder.get(), image.get(), {});
    }
    if (!image && buffer) return false;
    fVideoFrames.erase(key);
    if (!image) { fPictures.erase(key); return false; }
    fPictures[key] = std::move(image);
    return true;
}

bool EngineImpl::setVideoFrame(const std::string& uri, int64_t layerId, int64_t pts, AHardwareBuffer* buffer, int colorStandard, int colorRange, const FrameCrop& crop) {
    const auto start = std::chrono::steady_clock::now();
    const auto key = std::make_pair(uri, layerId);
    auto image = buffer ? this->copyFrame(buffer, true, colorStandard, colorRange, crop, &key) : nullptr;
    if (!image) return false;
    __android_log_print(ANDROID_LOG_INFO, "AMInterp", "import pts=%" PRId64 " size=%dx%d ms=%.2f", pts, image->width(),
                        image->height(), std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - start).count());
    fVideoFrames[{uri, layerId}].frames[pts] = std::move(image);
    return true;
}

double EngineImpl::heldMegabytes() const {
    double bytes = 0;
    for (const auto& [key, video] : fVideoFrames) {
        for (const auto& [pts, image] : video.frames) bytes += 4.0 * image->width() * image->height();
        for (const auto& [pair, image] : video.flows) bytes += 8.0 * image->width() * image->height();
    }
    for (const auto& [key, image] : fPictures) bytes += 4.0 * image->width() * image->height();
    return bytes / (1 << 20);
}

sk_sp<SkImage> EngineImpl::flowFor(VideoFrames& video, int64_t a, int64_t b, const InterpolationParams& params) {
    if (!(video.params == params)) {
        video.flows.clear();
        video.params = params;
    }
    auto cached = video.flows.find({a, b});
    if (cached != video.flows.end()) return cached->second;
    FlowStats stats;
    auto flow = EstimateFlow(fGraphite.get(), fRecorder.get(), video.frames[a], video.frames[b], params, &stats);
    if (!flow) return nullptr;
    video.flows[{a, b}] = flow;
    __android_log_print(ANDROID_LOG_INFO, "AMInterp",
                        "flow a=%" PRId64 " b=%" PRId64 " size=%dx%d field=%dx%d levels=%d fuse=%d passes=%d recordings=1 estimate=%.2f "
                        "held=%.1fMB cache=%.1fMB",
                        a, b, video.frames[a]->width(), video.frames[a]->height(), flow->width(), flow->height(), stats.levels,
                        params.fuse, stats.passes, stats.ms, this->heldMegabytes(), fRecorder->currentBudgetedBytes() / 1048576.0);
    return flow;
}

bool EngineImpl::interpolateVideo(const std::string& uri, int64_t layerId, int64_t a, int64_t b, float t, bool flow,
                                  const InterpolationParams& params) {
    const auto key = std::make_pair(uri, layerId);
    auto found = fVideoFrames.find(key);
    if (found == fVideoFrames.end()) return false;
    VideoFrames& video = found->second;
    auto first = video.frames.find(a), second = video.frames.find(b);
    if (first == video.frames.end() || second == video.frames.end()) return false;
    sk_sp<SkImage> image = first->second;
    if (a != b && t > 0) {
        if (!fGraphite || fGpuFailed) {
            LOGE("interpolation failed: GPU is unavailable");
            return false;
        }
        const sk_sp<SkImage> field = flow ? this->flowFor(video, a, b, params) : nullptr;
        if (flow && !field) {
            LOGE("interpolation failed: optical flow could not be estimated");
            return false;
        }
        double ms = 0;
        auto mixed = SynthesizeFrame(fGraphite.get(), fRecorder.get(), first->second, second->second, field, t,
                                     params.divisor, &ms);
        __android_log_print(ANDROID_LOG_INFO, "AMInterp", "frame a=%" PRId64 " b=%" PRId64 " t=%.4f mode=%s synthesis=%.2f",
                            a, b, t, field ? "flow" : "blend", ms);
        if (!mixed) {
            LOGE("interpolation failed: frame synthesis returned no image");
            return false;
        }
        image = std::move(mixed);
    }
    std::erase_if(video.frames, [&](const auto& frame) { return frame.first != a && frame.first != b; });
    std::erase_if(video.flows, [&](const auto& pair) {
        return !video.frames.count(pair.first.first) || !video.frames.count(pair.first.second);
    });
    fPictures[key] = std::move(image);
    return true;
}

int EngineImpl::renderScene(Target* target, double timeMs, float scale, float dx, float dy,
                            uint32_t background, int64_t selectedId, int handles, const PreviewOptions& view) {
    return this->frame(target, 0, false, [&](SkCanvas* canvas) {
        const LayerEffects fx = this->layerEffects();
        SetFastShutterBlur(!fAccurateBlur);
        RenderPreview(canvas, target->width, target->height, fScene, timeMs, scale, dx, dy, background,
                      selectedId, handles, view, fCameraView, this->pictures(), fx);
        SetFastShutterBlur(false);
    });
}

int EngineImpl::renderFrame(Target* target, double timeMs, uint32_t background, int64_t presentTimeNs) {
    if (fScene.width <= 0 || fScene.height <= 0) return -1;
    SetCameraView(true);
    return this->frame(target, presentTimeNs, false, [&](SkCanvas* canvas) {
        canvas->clear(background);
        canvas->scale((float)target->width / fScene.width, (float)target->height / fScene.height);
        const LayerEffects fx = this->layerEffects();
        DrawScene(canvas, fScene, timeMs, this->pictures(), &fx);
    });
}

SkSurface* EngineImpl::bufferSurface(AHardwareBuffer* buffer) {
    uint64_t id = 0;
    if (!buffer || AHardwareBuffer_getId(buffer, &id) != 0) return nullptr;
    auto found = fBufferSurfaces.find(id);
    if (found != fBufferSurfaces.end()) return found->second.get();
    AHardwareBuffer_Desc desc;
    AHardwareBuffer_describe(buffer, &desc);
    auto tex = fRecorder->createBackendTexture(buffer, true, false, {(int)desc.width, (int)desc.height});
    if (!tex.isValid()) {
        LOGE("encoder buffer %ux%u format 0x%x usage 0x%" PRIx64 " cannot be rendered to", desc.width, desc.height,
             desc.format, desc.usage);
        return nullptr;
    }
    struct Release {
        EngineImpl* engine;
        skgpu::graphite::BackendTexture tex;
        AHardwareBuffer* buffer;
    };
    AHardwareBuffer_acquire(buffer);
    SkSurfaceProps props(0, kUnknown_SkPixelGeometry);
    auto surface = SkSurfaces::WrapBackendTexture(
            fRecorder.get(), tex, SkColorSpace::MakeSRGB(), &props,
            [](void* context) {
                auto* release = static_cast<Release*>(context);
                release->engine->fDeadTextures.push_back({release->tex, release->buffer});
                delete release;
            },
            new Release{this, tex, buffer});
    if (!surface) return nullptr;
    LOGI("encoder buffer %ux%u format 0x%x", desc.width, desc.height, desc.format);
    return (fBufferSurfaces[id] = std::move(surface)).get();
}

int EngineImpl::renderBuffer(AHardwareBuffer* buffer, double timeMs, uint32_t background) {
    if (fGpuFailed) return -1000;
    if (!fGraphite) return -3;
    if (fScene.width <= 0 || fScene.height <= 0) return -1;
    this->deleteDeadTextures();
    SkSurface* surface = this->bufferSurface(buffer);
    if (!surface) return -2;
    SetCameraView(true);
    {
        SkCanvas* canvas = surface->getCanvas();
        SkAutoCanvasRestore restore(canvas, true);
        canvas->clear(background);
        canvas->scale((float)surface->width() / fScene.width, (float)surface->height() / fScene.height);
        const LayerEffects fx = this->layerEffects();
        DrawScene(canvas, fScene, timeMs, this->pictures(), &fx);
    }
    auto foreign = skgpu::MutableTextureStates::MakeVulkan(VK_IMAGE_LAYOUT_GENERAL, VK_QUEUE_FAMILY_FOREIGN_EXT);
    std::unique_ptr<skgpu::graphite::Recording> recording = fRecorder->snap();
    skgpu::graphite::InsertRecordingInfo info;
    info.fRecording = recording.get();
    info.fTargetSurface = surface;
    info.fTargetTextureState = &foreign;
    auto status = fGraphite->insertRecording(info);
    if (!status) {
        LOGE("buffer insertRecording failed: %s", status.message().c_str());
        recording.reset();
        return this->failGpu(nullptr);
    }
    if (!fGraphite->submit(skgpu::graphite::SyncToCpu::kYes)) {
        LOGE("buffer submit failed");
        recording.reset();
        return this->failGpu(nullptr);
    }
    return 0;
}

void EngineImpl::releaseBuffers() {
    if (fBufferSurfaces.empty()) return;
    this->syncGpu();
    fBufferSurfaces.clear();
    this->syncGpu();
}

bool EngineImpl::layerQuad(int64_t layerId, double timeMs, float xy[8]) {
    SetCameraView(fCameraView);
    const Layer* layer = fScene.layer(layerId);
    if (!layer || !layer->activeAt(timeMs)) return false;
    const float t = layer->localTime(timeMs);
    SkPoint corners[4];
    ShapePath(*layer, t).getBounds().toQuad(corners);
    WorldMatrix(fScene, *layer, timeMs).mapPoints(corners);
    for (int i = 0; i < 4; i++) {
        xy[2 * i] = corners[i].fX;
        xy[2 * i + 1] = corners[i].fY;
    }
    return true;
}

bool EngineImpl::layerBounds(int64_t layerId, double timeMs, float ltrb[4]) {
    SetCameraView(fCameraView);
    const Layer* layer = fScene.layer(layerId);
    if (!layer) return false;
    const float t = layer->localTime(timeMs);
    auto path = ShapePath(*layer, t).makeTransform(WorldMatrix(fScene, *layer, timeMs));
    const SkRect bounds = path.getBounds();
    ltrb[0] = bounds.left(); ltrb[1] = bounds.top();
    ltrb[2] = bounds.right(); ltrb[3] = bounds.bottom();
    return !bounds.isEmpty();
}

int64_t EngineImpl::hitTest(float x, float y, double timeMs) {
    SetCameraView(fCameraView);
    return TopLayerAt(fScene, timeMs, x, y);
}

bool EngineImpl::layerOutline(int64_t layerId, double timeMs, std::string* svg) {
    const Layer* layer = fScene.layer(layerId);
    if (!layer) return false;
    const SkPath path = layer->text ? TextGlyphPath(*layer) : ShapePath(*layer, layer->localTime(timeMs));
    if (path.isEmpty()) return false;
    *svg = OutlineSvg(path);
    return true;
}

bool EngineImpl::still(double timeMs, int width, int height, std::vector<uint8_t>* rgba) {
    if (fScene.width <= 0) return false;
    SetCameraView(true);
    return this->offscreen(width, height, false, [&](SkCanvas* canvas) {
        canvas->scale((float)width / fScene.width, (float)height / fScene.height);
        const LayerEffects fx = this->layerEffects();
        DrawScene(canvas, fScene, timeMs, this->pictures(), &fx);
    }, rgba);
}

bool EngineImpl::shapeIcon(const std::string& xml, int size, uint32_t color, std::vector<uint8_t>* rgba) {
    Scene scene;
    std::string error;
    if (!ParseScene(xml, &scene, &error) || scene.layers.empty()) return false;
    const Layer& layer = scene.layers.front();
    const SkPath path = ShapePath(layer, 0);
    const SkPath stroke = StrokePath(layer, path, 0);
    SkMatrix orientation = SkMatrix::I();
    if (layer.fillType == "media") {
        auto info = fScene.mediaOrientation.find(layer.mediaUri);
        if (info != fScene.mediaOrientation.end()) orientation.setRotate(info->second);
    }
    SkRect bounds = layer.fillType == "none" ? stroke.getBounds() : path.getBounds();
    if (layer.fillType != "none" && !stroke.isEmpty()) bounds.join(stroke.getBounds());
    bounds = orientation.mapRect(bounds);
    if (bounds.isEmpty()) return false;
    return this->offscreen(size, size, true, [&](SkCanvas* canvas) {
        const float scale = size * 0.9f / std::max(bounds.width(), bounds.height());
        canvas->translate(size / 2.0f, size / 2.0f);
        canvas->scale(scale, scale);
        canvas->translate(-bounds.centerX(), -bounds.centerY());
        canvas->concat(orientation);
        SkPaint paint;
        paint.setAntiAlias(true);
        paint.setColor(color);
        if (layer.fillType == "media" && !this->picture(layer.mediaUri, layer.id)) return;
        if (layer.fillType == "media") DrawMedia(canvas, layer, path, 1, this->pictures());
        else if (layer.text && layer.fillType != "none") DrawText(canvas, layer, 0, paint);
        else if (layer.fillType != "none") canvas->drawPath(path, paint);
        canvas->drawPath(stroke, paint);
    }, rgba);
}

bool EngineImpl::offscreen(int width, int height, bool premultiplied,
                           const std::function<void(SkCanvas*)>& paint, std::vector<uint8_t>* rgba) {
    if (fGpuFailed || width <= 0 || height <= 0) return false;
    if (fGraphite) this->deleteDeadTextures();
    const SkImageInfo info = SkImageInfo::Make(width, height, kRGBA_8888_SkColorType, kPremul_SkAlphaType,
                                               SkColorSpace::MakeSRGB());
    const SkImageInfo out = premultiplied ? info : info.makeAlphaType(kUnpremul_SkAlphaType);
    sk_sp<SkSurface> surface = fGraphite ? SkSurfaces::RenderTarget(fRecorder.get(), info)
                                         : SkSurfaces::RenderTarget(fGanesh.get(), skgpu::Budgeted::kNo, info);
    if (!surface) return false;
    SkCanvas* canvas = surface->getCanvas();
    canvas->clear(SK_ColorTRANSPARENT);
    paint(canvas);
    rgba->resize((size_t)width * height * 4);
    if (fGanesh) return surface->readPixels(out, rgba->data(), (size_t)width * 4, 0, 0);

    auto recording = fRecorder->snap();
    auto status = fGraphite->insertRecording({recording.get()});
    if (!status) {
        LOGE("offscreen insertRecording failed: %s", status.message().c_str());
        recording.reset();
        this->failGpu(nullptr);
        return false;
    }
    struct Read {
        std::vector<uint8_t>* rgba;
        int width, height;
        bool done = false, ok = false;
    };
    auto read = std::make_shared<Read>(Read{rgba, width, height});
    fGraphite->asyncRescaleAndReadPixels(
            surface.get(), out, SkIRect::MakeWH(width, height), SkImage::RescaleGamma::kSrc,
            SkImage::RescaleMode::kNearest,
            [](void* context, std::unique_ptr<const SkImage::AsyncReadResult> result) {
                std::unique_ptr<std::shared_ptr<Read>> holder(static_cast<std::shared_ptr<Read>*>(context));
                Read* r = holder->get();
                r->done = true;
                if (!result || !r->rgba) return;
                const auto* src = static_cast<const uint8_t*>(result->data(0));
                for (int y = 0; y < r->height; y++) {
                    memcpy(r->rgba->data() + (size_t)y * r->width * 4, src + y * result->rowBytes(0), (size_t)r->width * 4);
                }
                r->ok = true;
            },
            new std::shared_ptr<Read>(read));
    if (!fGraphite->submit(skgpu::graphite::SyncToCpu::kYes)) {
        LOGE("offscreen submit failed");
        read->rgba = nullptr;
        recording.reset();
        this->failGpu(nullptr);
        return false;
    }
    this->deleteDeadTextures();
    fGraphite->checkAsyncWorkCompletion();
    read->rgba = nullptr;
    return read->done && read->ok;
}

int EngineImpl::failGpu(Target* target) {
    fGpuFailed = true;
    vkDeviceWaitIdle(fDevice);
    if (target && target->swapchain) {
        target->surfaces.clear();
        vkDestroySwapchainKHR(fDevice, target->swapchain, nullptr);
        target->swapchain = VK_NULL_HANDLE;
    }
    return -1000;
}

int EngineImpl::frame(Target* target, int64_t presentTimeNs, bool sync,
                      const std::function<void(SkCanvas*)>& paint) {
    if (fGpuFailed || !target || std::none_of(fTargets.begin(), fTargets.end(),
            [&](const auto& t) { return t.get() == target; }) || !target->swapchain) return -1000;
    if (presentTimeNs > 0 && !fDisplayTiming) {
        LOGE("encoder timestamps require VK_GOOGLE_display_timing");
        return -1000;
    }
    if (fGraphite) this->deleteDeadTextures();

    VkSemaphore acquired = VK_NULL_HANDLE;
    VkResult r = VK_SUCCESS;
    if (fBackend == Backend::kGraphite && !fSpareSemaphores.empty()) {
        acquired = fSpareSemaphores.back();
        fSpareSemaphores.pop_back();
    } else {
        VkSemaphoreCreateInfo semInfo = {VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
        r = vkCreateSemaphore(fDevice, &semInfo, nullptr, &acquired);
        if (r != VK_SUCCESS) return r;
    }
    uint32_t index = 0;
    r = vkAcquireNextImageKHR(fDevice, target->swapchain, UINT64_MAX, acquired, VK_NULL_HANDLE, &index);
    if (r != VK_SUCCESS && r != VK_SUBOPTIMAL_KHR) {
        vkDestroySemaphore(fDevice, acquired, nullptr);
        LOGE("vkAcquireNextImageKHR failed (%d)", r);
        return r;
    }

    SkSurface* surface = target->surfaces[index].get();
    VkSemaphore renderDone = target->renderDone[index];
    auto presentState = skgpu::MutableTextureStates::MakeVulkan(VK_IMAGE_LAYOUT_PRESENT_SRC_KHR, fQueueFamily);

    if (fBackend == Backend::kGraphite) {
        {
            SkAutoCanvasRestore restore(surface->getCanvas(), true);
            paint(surface->getCanvas());
        }
        std::unique_ptr<skgpu::graphite::Recording> recording = fRecorder->snap();
        auto waitSem = skgpu::graphite::BackendSemaphores::MakeVulkan(acquired);
        auto signalSem = skgpu::graphite::BackendSemaphores::MakeVulkan(renderDone);
        struct Finish {
            EngineImpl* engine;
            VkDevice device;
            VkSemaphore semaphore;
        };
        skgpu::graphite::InsertRecordingInfo info;
        info.fRecording = recording.get();
        info.fTargetSurface = surface;
        info.fTargetTextureState = &presentState;
        info.fNumWaitSemaphores = 1;
        info.fWaitSemaphores = &waitSem;
        info.fNumSignalSemaphores = 1;
        info.fSignalSemaphores = &signalSem;
        info.fFinishedContext = new Finish{this, fDevice, acquired};
        info.fFinishedProc = [](skgpu::graphite::GpuFinishedContext context, skgpu::CallbackResult result) {
            auto* finish = static_cast<Finish*>(context);
            if (result == skgpu::CallbackResult::kSuccess) finish->engine->fSpareSemaphores.push_back(finish->semaphore);
            else finish->engine->fFailedSemaphores.push_back(finish->semaphore);
            delete finish;
        };
        auto status = fGraphite->insertRecording(info);
        if (!status) {
            LOGE("insertRecording failed: %s", status.message().c_str());
            recording.reset();
            return this->failGpu(target);
        }
        if (!fGraphite->submit(sync ? skgpu::graphite::SyncToCpu::kYes : skgpu::graphite::SyncToCpu::kNo)) {
            LOGE("Graphite submit failed");
            recording.reset();
            return this->failGpu(target);
        }
    } else {
        GrBackendSemaphore waitSem = GrBackendSemaphores::MakeVk(acquired);
        surface->wait(1, &waitSem);
        {
            SkAutoCanvasRestore restore(surface->getCanvas(), true);
            paint(surface->getCanvas());
        }
        GrBackendSemaphore signalSem = GrBackendSemaphores::MakeVk(renderDone);
        GrFlushInfo info;
        info.fNumSemaphores = 1;
        info.fSignalSemaphores = &signalSem;
        if (fGanesh->flush(surface, info, &presentState) != GrSemaphoresSubmitted::kYes) {
            LOGE("Ganesh flush did not submit the present semaphore");
            return this->failGpu(target);
        }
        if (!fGanesh->submit(sync ? GrSyncCpu::kYes : GrSyncCpu::kNo)) return this->failGpu(target);
    }

    VkPresentTimeGOOGLE time = {target->nextPresentId++, presentTimeNs > 0 ? (uint64_t)presentTimeNs : 0};
    VkPresentTimesInfoGOOGLE times = {VK_STRUCTURE_TYPE_PRESENT_TIMES_INFO_GOOGLE, nullptr, 1, &time};
    VkPresentInfoKHR present = {VK_STRUCTURE_TYPE_PRESENT_INFO_KHR};
    present.pNext = fDisplayTiming ? &times : nullptr;
    present.waitSemaphoreCount = 1;
    present.pWaitSemaphores = &renderDone;
    present.swapchainCount = 1;
    present.pSwapchains = &target->swapchain;
    present.pImageIndices = &index;
    r = vkQueuePresentKHR(fQueue, &present);
    if (r != VK_SUCCESS && r != VK_SUBOPTIMAL_KHR) {
        LOGE("vkQueuePresentKHR failed (%d)", r);
        this->failGpu(target);
        return r;
    }
    if (presentTimeNs <= 0 && target->nextPresentId % 4 == 0) this->collectTimings(target);
    return 0;
}

void EngineImpl::collectTimings(Target* target) {
    if (!fDisplayTiming) return;
    uint32_t n = 0;
    fGetPastPresentationTiming(fDevice, target->swapchain, &n, nullptr);
    if (!n) return;
    if (target->timings.size() > 32768) target->timings.erase(target->timings.begin(), target->timings.begin() + 16384);
    size_t old = target->timings.size();
    target->timings.resize(old + n);
    fGetPastPresentationTiming(fDevice, target->swapchain, &n, target->timings.data() + old);
    target->timings.resize(old + n);
}

std::string EngineImpl::timing(Target* target) {
    this->collectTimings(target);
    VkRefreshCycleDurationGOOGLE refresh = {0};
    if (fDisplayTiming) fGetRefreshCycleDuration(fDevice, target->swapchain, &refresh);

    auto& v = target->timings;
    std::sort(v.begin(), v.end(), [](const auto& a, const auto& b) { return a.presentID < b.presentID; });
    const size_t skip = std::min<size_t>(120, v.size() / 5);
    std::vector<uint64_t> gaps;
    for (size_t i = skip + 1; i < v.size(); i++) {
        if (v[i].presentID != v[i - 1].presentID + 1) continue;
        gaps.push_back(v[i].actualPresentTime - v[i - 1].actualPresentTime);
    }
    std::sort(gaps.begin(), gaps.end());
    uint64_t sum = 0;
    int missed = 0;
    const uint64_t median = gaps.empty() ? 0 : gaps[gaps.size() / 2];
    for (uint64_t d : gaps) {
        sum += d;
        if (d > median * 3 / 2) missed++;
    }
    char buf[256];
    snprintf(buf, sizeof(buf),
             "{\"refreshNs\":%" PRIu64 ",\"reported\":%zu,\"samples\":%zu,\"meanNs\":%" PRIu64
             ",\"minNs\":%" PRIu64 ",\"maxNs\":%" PRIu64 ",\"missed\":%d}",
             (uint64_t)refresh.refreshDuration, v.size(), gaps.size(), gaps.empty() ? 0 : sum / gaps.size(),
             gaps.empty() ? 0 : gaps.front(), gaps.empty() ? 0 : gaps.back(), missed);
    v.clear();
    return buf;
}

}

std::unique_ptr<Engine> Engine::Make(Backend backend, std::string* error) {
    auto engine = std::make_unique<EngineImpl>(backend);
    if (!engine->init(error)) return nullptr;
    return engine;
}

}
