package me.cortex.vulkanite.client;

import me.cortex.vulkanite.acceleration.AccelerationManager;
import me.cortex.vulkanite.client.rendering.DLSSBridge;
import me.cortex.vulkanite.client.rendering.DLSSLoader;
import me.cortex.vulkanite.lib.base.VContext;

import me.cortex.vulkanite.lib.base.VRef;
import me.cortex.vulkanite.lib.base.VRegistry;
import me.cortex.vulkanite.lib.base.initalizer.VInitializer;
import me.cortex.vulkanite.lib.descriptors.VDescriptorPool;
import me.cortex.vulkanite.lib.descriptors.VDescriptorSetLayout;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSection;
import me.jellysquid.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import net.minecraft.util.Util;
import org.lwjgl.opengl.GL20;
import org.lwjgl.vulkan.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static org.lwjgl.vulkan.EXTDescriptorIndexing.VK_EXT_DESCRIPTOR_INDEXING_EXTENSION_NAME;
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
    public static final boolean IS_WINDOWS = Util.getOperatingSystem() == Util.OperatingSystem.WINDOWS;

    public static boolean IS_ENABLED = true;
    public static Vulkanite INSTANCE = new Vulkanite();

    public final boolean IS_ZINK;
    private final VContext ctx;
    private final ArbitarySyncPointCallback fencedCallback = new ArbitarySyncPointCallback();

    private final AccelerationManager accelerationManager;
    private final HashMap<VDescriptorSetLayout, VRef<VDescriptorPool>> descriptorPools = new HashMap<>();

    public Vulkanite() {
        ctx = createVulkanContext();
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
         * if (((IAccelerationBuildResult)result).getAccelerationGeometryData() == null)
         * return;//TODO: delete the chunk section in this case then or something
         * accelerationManager.chunkBuild(result);
         */

        accelerationManager.chunkBuilds(results);
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

    public void removePoolByLayout(VDescriptorSetLayout layout) {
        synchronized (descriptorPools) {
            descriptorPools.remove(layout);
        }
    }

    public void sectionRemove(RenderSection section) {
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
        vkDeviceWaitIdle(ctx.device);
        descriptorPools.clear();
    }

    private static VContext createVulkanContext() {
        // Create instance extensions list
        List<String> instanceExtensions = new ArrayList<>();
        instanceExtensions.add(VK_KHR_GET_PHYSICAL_DEVICE_PROPERTIES_2_EXTENSION_NAME);
        instanceExtensions.add(VK_KHR_EXTERNAL_MEMORY_CAPABILITIES_EXTENSION_NAME);
        instanceExtensions.add(VK_KHR_EXTERNAL_SEMAPHORE_CAPABILITIES_EXTENSION_NAME);
        instanceExtensions.add(VK_KHR_EXTERNAL_FENCE_CAPABILITIES_EXTENSION_NAME);

        // Add DLSS instance extensions
        DLSSBridge bridge = DLSSLoader.getInstance();
        if (bridge != null) {
            try {
                int count = bridge.getNGXInstanceExtensionCount();
                System.out.println("NGX requires " + count + " instance extensions");
                for (int i = 0; i < count; i++) {
                    String extName = bridge.getNGXInstanceExtension(i);
                    if (!instanceExtensions.contains(extName)) {
                        instanceExtensions.add(extName);
                        System.out.println("Adding NGX instance extension: " + extName);
                    }
                }
            } catch (Exception e) {
                System.err.println("Failed to query NGX instance extensions: " + e.getMessage());
            }
        }

        var init = new VInitializer("Vulkan test", "Vulkanite", 1, 3,
                instanceExtensions.toArray(new String[0]),
                new String[] {
                });

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

        // Add DLSS required extensions if available
        bridge = DLSSLoader.getInstance();
        if (bridge != null) {
            try {
                long instanceAddr = init.getInstance().address();
                long physAddr = init.getPhysicalDevice().address();
                int count = bridge.getNGXDeviceExtensionCount(instanceAddr, physAddr);
                System.out.println("NGX requires " + count + " device extensions");
                for (int i = 0; i < count; i++) {
                    String extName = bridge.getNGXDeviceExtension(instanceAddr, physAddr, i);
                    if (!extensions.contains(extName)) {
                        extensions.add(extName);
                        System.out.println("Enabling NGX device extension: " + extName);
                    }
                }

                // FORCE ENABLE VK_NV_ngx if available (Crucial for DLSS)
                // Sometimes NGX doesn't report it but needs it, or returns 0 extensions
                String[] criticalExtensions = {
                        "VK_NVX_binary_import",
                        "VK_NVX_image_view_handle",
                        "VK_KHR_push_descriptor",
                        "VK_KHR_buffer_device_address",
                        "VK_NV_ngx",
                        "VK_NV_ngx2"
                };
                System.out.println("Available Device Extensions: " + availableExtensions);
                for (String ext : criticalExtensions) {
                    if (availableExtensions.contains(ext)) {
                        if (!extensions.contains(ext)) {
                            extensions.add(ext);
                            System.out.println("Force enabling critical DLSS extension: " + ext);
                        } else {
                            System.out.println("Critical DLSS extension already enabled: " + ext);
                        }
                    } else {
                        System.err.println("WARNING: " + ext + " is NOT available on this device/driver!");
                    }
                }

                System.out.println("Final Enabled Device Extensions: " + extensions);

            } catch (Exception e) {
                System.err.println("Failed to query NGX device extensions: " + e.getMessage());
                // Fallback to manual list if query fails
                List<String> dlssExtensions = List.of(
                        "VK_NVX_binary_import",
                        "VK_NVX_image_view_handle",
                        "VK_EXT_buffer_device_address",
                        "VK_KHR_push_descriptor",
                        "VK_NV_ngx");
                for (var ext : dlssExtensions) {
                    if (availableExtensions.contains(ext)) {
                        extensions.add(ext);
                        System.out.println("Enabling DLSS extension (fallback): " + ext);
                    }
                }
            }
        } else {
            // Fallback if DLSSBridge not loaded
            List<String> dlssExtensions = List.of(
                    "VK_NVX_binary_import",
                    "VK_NVX_image_view_handle",
                    "VK_EXT_buffer_device_address",
                    "VK_KHR_push_descriptor");
            for (var ext : dlssExtensions) {
                if (availableExtensions.contains(ext)) {
                    extensions.add(ext);
                    System.out.println("Enabling DLSS extension: " + ext);
                }
            }
        }

        if (IS_WINDOWS) {
            extensions.addAll(List.of(VK_KHR_EXTERNAL_MEMORY_WIN32_EXTENSION_NAME,
                    VK_KHR_EXTERNAL_SEMAPHORE_WIN32_EXTENSION_NAME,
                    VK_KHR_EXTERNAL_FENCE_WIN32_EXTENSION_NAME));
        } else {
            extensions.addAll(List.of(VK_KHR_EXTERNAL_MEMORY_FD_EXTENSION_NAME,
                    VK_KHR_EXTERNAL_SEMAPHORE_FD_EXTENSION_NAME,
                    VK_KHR_EXTERNAL_FENCE_FD_EXTENSION_NAME));
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

    public AccelerationManager getAccelerationManager() {
        return accelerationManager;
    }

}
