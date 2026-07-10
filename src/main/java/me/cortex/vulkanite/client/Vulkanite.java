package me.cortex.vulkanite.client;

import me.cortex.vulkanite.acceleration.AccelerationManager;
import me.cortex.vulkanite.client.config.VulkaniteConfig;
import me.cortex.vulkanite.client.lighting.SectionLightManager;
import me.cortex.vulkanite.client.rendering.DLSSBridge;
import me.cortex.vulkanite.lib.base.VContext;

import me.cortex.vulkanite.lib.base.VRef;
import me.cortex.vulkanite.lib.base.VRegistry;
import me.cortex.vulkanite.lib.base.initalizer.VInitializer;
import me.cortex.vulkanite.lib.descriptors.VDescriptorPool;
import me.cortex.vulkanite.lib.descriptors.VDescriptorSet;
import me.cortex.vulkanite.lib.descriptors.VDescriptorSetLayout;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSection;
import me.jellysquid.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import net.minecraft.util.Util;
import org.lwjgl.opengl.GL20;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static org.lwjgl.vulkan.EXTDescriptorIndexing.VK_EXT_DESCRIPTOR_INDEXING_EXTENSION_NAME;
import static org.lwjgl.vulkan.EXTDebugUtils.VK_EXT_DEBUG_UTILS_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_KHR_ACCELERATION_STRUCTURE_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRDeferredHostOperations.VK_KHR_DEFERRED_HOST_OPERATIONS_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRExternalFenceCapabilities.VK_KHR_EXTERNAL_FENCE_CAPABILITIES_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRExternalFenceFd.VK_KHR_EXTERNAL_FENCE_FD_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRExternalFenceWin32.VK_KHR_EXTERNAL_FENCE_WIN32_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRExternalMemory.VK_KHR_EXTERNAL_MEMORY_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRExternalMemoryCapabilities.VK_KHR_EXTERNAL_MEMORY_CAPABILITIES_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRExternalMemoryFd.VK_KHR_EXTERNAL_MEMORY_FD_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRExternalMemoryWin32.VK_KHR_EXTERNAL_MEMORY_WIN32_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRExternalSemaphore.VK_KHR_EXTERNAL_SEMAPHORE_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRExternalSemaphoreCapabilities.VK_KHR_EXTERNAL_SEMAPHORE_CAPABILITIES_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRExternalSemaphoreFd.VK_KHR_EXTERNAL_SEMAPHORE_FD_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRExternalSemaphoreWin32.VK_KHR_EXTERNAL_SEMAPHORE_WIN32_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRGetMemoryRequirements2.VK_KHR_GET_MEMORY_REQUIREMENTS_2_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRGetPhysicalDeviceProperties2.VK_KHR_GET_PHYSICAL_DEVICE_PROPERTIES_2_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.VK_KHR_RAY_TRACING_PIPELINE_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRShaderDrawParameters.VK_KHR_SHADER_DRAW_PARAMETERS_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRSpirv14.VK_KHR_SPIRV_1_4_EXTENSION_NAME;
import static org.lwjgl.vulkan.VK10.vkDeviceWaitIdle;

public class Vulkanite {
    private static final Logger LOGGER = LoggerFactory.getLogger(Vulkanite.class);
    private static final String KHRONOS_VALIDATION_LAYER = "VK_LAYER_KHRONOS_validation";
    public static final boolean IS_WINDOWS = Util.getOperatingSystem() == Util.OperatingSystem.WINDOWS;

    public static boolean IS_ENABLED = true;
    public static Vulkanite INSTANCE = new Vulkanite();

    public final boolean IS_ZINK;
    private final VContext ctx;
    private final ArbitarySyncPointCallback fencedCallback = new ArbitarySyncPointCallback();

    private final AccelerationManager accelerationManager;
    private final SectionLightManager sectionLightManager = new SectionLightManager();
    private final HashMap<VDescriptorSetLayout, VRef<VDescriptorPool>> descriptorPools = new HashMap<>();
    private final HashMap<VDescriptorSetLayout, VRef<VDescriptorSet>> emptyDescriptorSets = new HashMap<>();
    private boolean destroyed;

    public Vulkanite() {
        ctx = createVulkanContext();
        ctx.capabilities.logReport(LOGGER);
        // Hack: so that AccelerationManager can access Vulkanite.INSTANCE
        INSTANCE = this;

        // Check GL_VENDOR to determine if Zink is being used
        final var gl_vendor = GL20.glGetString(GL20.GL_VENDOR);
        final var gl_renderer = GL20.glGetString(GL20.GL_RENDERER);
        if (gl_vendor == null || gl_renderer == null) {
            IS_ZINK = false;
        } else {
            IS_ZINK = gl_vendor.contains("Mesa") && gl_renderer.contains("zink");
            if (IS_ZINK) {
                System.out.println("Zink GL detected");
            }
        }

        // Fill in the shared index buffer with a large count so we (hopefully) dont
        // have to worry about it anymore
        // SharedQuadVkIndexBuffer.getIndexBuffer(ctx, 30000);

        accelerationManager = new AccelerationManager(ctx, 1);
    }

    public void upload(List<ChunkBuildOutput> results) {
        /*
         * if (((IAccelerationBuildResult)result).getAccelerationGeometry() == null)
         * return;//TODO: delete the chunk section in this case then or something
         * accelerationManager.chunkBuild(result);
         */

        accelerationManager.chunkBuilds(results);
    }

    public void updateSectionLights(List<ChunkBuildOutput> results) {
        sectionLightManager.updateFromBuildResults(results);
    }

    public VRef<VDescriptorPool> getPoolByLayout(VRef<VDescriptorSetLayout> layout) {
        var key = layout.get();
        synchronized (descriptorPools) {
            if (!descriptorPools.containsKey(key)) {
                descriptorPools.put(key, VDescriptorPool.create(ctx, layout, 0));
            }
            return descriptorPools.get(key).addRef();
        }
    }

    public VRef<VDescriptorSet> getEmptySet(VRef<VDescriptorSetLayout> layout) {
        var key = layout.get();
        synchronized (emptyDescriptorSets) {
            VRef<VDescriptorSet> cached = emptyDescriptorSets.get(key);
            if (cached != null) {
                return cached.addRef();
            }
        }

        VRef<VDescriptorPool> pool = getPoolByLayout(layout);
        VRef<VDescriptorSet> created;
        try {
            created = pool.get().allocateSet();
        } finally {
            pool.close();
        }

        synchronized (emptyDescriptorSets) {
            VRef<VDescriptorSet> cached = emptyDescriptorSets.get(key);
            if (cached == null) {
                emptyDescriptorSets.put(key, created);
                return created.addRef();
            }
            created.close();
            return cached.addRef();
        }
    }

    public void removePoolByLayout(VDescriptorSetLayout layout) {
        VRef<VDescriptorSet> emptySet;
        synchronized (emptyDescriptorSets) {
            emptySet = emptyDescriptorSets.remove(layout);
        }
        if (emptySet != null) {
            emptySet.close();
        }

        synchronized (descriptorPools) {
            descriptorPools.remove(layout);
        }
    }

    public void sectionRemove(RenderSection section) {
        sectionLightManager.removeSection(section);
        accelerationManager.sectionRemove(section);
    }

    public void renderTick() {
        VRegistry.INSTANCE.threadLocalCollect();
        ctx.sync.checkFences();
        accelerationManager.updateTick();
    }

    public void fenceTick() {
        fencedCallback.tick();
    }

    public VContext getCtx() {
        return ctx;
    }

    public void addSyncedCallback(Runnable callback) {
        fencedCallback.enqueue(callback);
    }

    public void destroy() {
        if (destroyed) {
            return;
        }
        destroyed = true;

        vkDeviceWaitIdle(ctx.device);
        DLSSBridge.shutdownNGX();
        sectionLightManager.destroy();
        accelerationManager.destroy();
        synchronized (emptyDescriptorSets) {
            for (VRef<VDescriptorSet> emptySet : emptyDescriptorSets.values()) {
                emptySet.close();
            }
            emptyDescriptorSets.clear();
        }
        descriptorPools.clear();
        if (ctx.validationEnabled) {
            LOGGER.info("Vulkan validation summary: {} error(s), {} warning(s)",
                    ctx.validationErrorCount(), ctx.validationWarningCount());
        }
    }

    private static VContext createVulkanContext() {
        // Create instance extensions list
        List<String> instanceExtensions = new ArrayList<>();
        instanceExtensions.add(VK_KHR_GET_PHYSICAL_DEVICE_PROPERTIES_2_EXTENSION_NAME);
        instanceExtensions.add(VK_KHR_EXTERNAL_MEMORY_CAPABILITIES_EXTENSION_NAME);
        instanceExtensions.add(VK_KHR_EXTERNAL_SEMAPHORE_CAPABILITIES_EXTENSION_NAME);
        instanceExtensions.add(VK_KHR_EXTERNAL_FENCE_CAPABILITIES_EXTENSION_NAME);

        List<String> instanceLayers = new ArrayList<>();
        boolean validationEnabled = VulkaniteConfig.getInstance().isVulkanValidationEnabled();
        if (validationEnabled) {
            requireValidationSupport();
            instanceExtensions.add(VK_EXT_DEBUG_UTILS_EXTENSION_NAME);
            instanceLayers.add(KHRONOS_VALIDATION_LAYER);
            LOGGER.info("Vulkan validation enabled (layer {}, extension {})",
                    KHRONOS_VALIDATION_LAYER, VK_EXT_DEBUG_UTILS_EXTENSION_NAME);
        } else {
            LOGGER.info("Vulkan validation disabled; enable with -D{}=true, {}=true, or vulkanValidationEnabled=true",
                    VulkaniteConfig.VULKAN_VALIDATION_SYSTEM_PROPERTY,
                    VulkaniteConfig.VULKAN_VALIDATION_ENVIRONMENT_VARIABLE);
        }

        // Add NGX-required instance extensions for DLSS support
        // These MUST be enabled at instance creation time or NGX will report FeatureNotSupported
        try {
            List<String> ngxInstanceExts = me.cortex.vulkanite.client.rendering.DLSSBridge.getRequiredInstanceExtensions();
            for (String ext : ngxInstanceExts) {
                if (!instanceExtensions.contains(ext)) {
                    instanceExtensions.add(ext);
                    System.out.println("[Vulkanite] Adding NGX instance extension: " + ext);
                }
            }
        } catch (Exception e) {
            System.out.println("[Vulkanite] Could not query NGX instance extensions: " + e.getMessage());
        }

        var init = new VInitializer("Vulkan test", "Vulkanite", 1, 3,
                instanceExtensions.toArray(new String[0]),
                instanceLayers.toArray(new String[0]));

        // This copies whatever gpu the opengl context is on
        init.findPhysicalDevice();// glGetString(GL_RENDERER).split("/")[0]

        var availableExtensions = init.getDeviceExtensionStrings(init.getPhysicalDevice());

        List<String> extensions = new ArrayList<>(List.of(
                VK_KHR_GET_MEMORY_REQUIREMENTS_2_EXTENSION_NAME,
                VK_KHR_EXTERNAL_MEMORY_EXTENSION_NAME,
                VK_KHR_EXTERNAL_SEMAPHORE_EXTENSION_NAME,
                VK_EXT_DESCRIPTOR_INDEXING_EXTENSION_NAME,
                VK_KHR_SPIRV_1_4_EXTENSION_NAME,
                VK_KHR_SHADER_DRAW_PARAMETERS_EXTENSION_NAME,

                // VK_KHR_RAY_QUERY_EXTENSION_NAME,

                VK_KHR_RAY_TRACING_PIPELINE_EXTENSION_NAME,
                VK_KHR_ACCELERATION_STRUCTURE_EXTENSION_NAME,

                // VK_EXT_MEMORY_BUDGET_EXTENSION_NAME,

                VK_KHR_DEFERRED_HOST_OPERATIONS_EXTENSION_NAME));

        if (IS_WINDOWS) {
            extensions.addAll(List.of(VK_KHR_EXTERNAL_MEMORY_WIN32_EXTENSION_NAME,
                    VK_KHR_EXTERNAL_SEMAPHORE_WIN32_EXTENSION_NAME,
                    VK_KHR_EXTERNAL_FENCE_WIN32_EXTENSION_NAME));
        } else {
            extensions.addAll(List.of(VK_KHR_EXTERNAL_MEMORY_FD_EXTENSION_NAME,
                    VK_KHR_EXTERNAL_SEMAPHORE_FD_EXTENSION_NAME,
                    VK_KHR_EXTERNAL_FENCE_FD_EXTENSION_NAME));
        }

        // Add NGX-required device extensions for DLSS support
        // Only add extensions that the physical device actually supports
        try {
            List<String> ngxDeviceExts = me.cortex.vulkanite.client.rendering.DLSSBridge.getRequiredDeviceExtensions(
                    init.getInstance().address(), init.getPhysicalDevice().address());
            for (String ext : ngxDeviceExts) {
                if (!extensions.contains(ext)) {
                    if (availableExtensions.contains(ext)) {
                        extensions.add(ext);
                        System.out.println("[Vulkanite] Adding NGX device extension: " + ext);
                    } else {
                        System.out.println("[Vulkanite] WARNING: NGX requires device extension " + ext + " but it is not available!");
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("[Vulkanite] Could not query NGX device extensions: " + e.getMessage());
        }

        init.createDevice(extensions,
                List.of(),
                new float[] { 1.0f, 1.0f },
                features -> features.shaderInt16(true).shaderInt64(true).multiDrawIndirect(true), List.of(
                        stack -> VkPhysicalDeviceAccelerationStructureFeaturesKHR.calloc(stack)
                                .sType$Default(),
                        stack -> VkPhysicalDeviceRayTracingPipelineFeaturesKHR.calloc(stack)
                                .sType$Default(),
                        stack -> VkPhysicalDeviceVulkan11Features.calloc(stack)
                                .sType$Default(),

                        stack -> VkPhysicalDeviceVulkan12Features.calloc(stack)
                                .sType$Default()),
                List.of(
                        features -> {
                            var asFeatures = (VkPhysicalDeviceAccelerationStructureFeaturesKHR) features;
                            asFeatures.accelerationStructure(true);
                        },
                        features -> {
                            var rtFeatures = (VkPhysicalDeviceRayTracingPipelineFeaturesKHR) features;
                            rtFeatures.rayTracingPipeline(true);
                        },
                        features -> {
                            var vulkan11Features = (VkPhysicalDeviceVulkan11Features) features;
                            vulkan11Features.protectedMemory(false);
                        },
                        features -> {
                            var vulkan12Features = (VkPhysicalDeviceVulkan12Features) features;
                            vulkan12Features.bufferDeviceAddress(true);
                            vulkan12Features.timelineSemaphore(true);
                            vulkan12Features.descriptorIndexing(true);
                            vulkan12Features.runtimeDescriptorArray(true);
                            vulkan12Features.descriptorBindingVariableDescriptorCount(true);
                            vulkan12Features.descriptorBindingPartiallyBound(true);
                            vulkan12Features.bufferDeviceAddressMultiDevice(false);
                        }));

        return init.createContext();
    }

    private static void requireValidationSupport() {
        if (!VInitializer.isInstanceLayerAvailable(KHRONOS_VALIDATION_LAYER)) {
            throw new IllegalStateException("Vulkan validation was requested, but instance layer "
                    + KHRONOS_VALIDATION_LAYER + " is unavailable. Install the Vulkan SDK validation layers "
                    + "or disable Vulkanite validation.");
        }
        if (!VInitializer.isInstanceExtensionAvailable(VK_EXT_DEBUG_UTILS_EXTENSION_NAME)) {
            throw new IllegalStateException("Vulkan validation was requested, but instance extension "
                    + VK_EXT_DEBUG_UTILS_EXTENSION_NAME + " is unavailable. Update the Vulkan loader "
                    + "or disable Vulkanite validation.");
        }
    }

    public AccelerationManager getAccelerationManager() {
        return accelerationManager;
    }

    public SectionLightManager getSectionLightManager() {
        return sectionLightManager;
    }

}
