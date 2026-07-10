package me.cortex.vulkanite.lib.base;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceAccelerationStructureFeaturesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures2;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties2;
import org.lwjgl.vulkan.VkPhysicalDeviceRayTracingPipelineFeaturesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceSubgroupProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan12Features;
import org.lwjgl.vulkan.VkQueueFamilyProperties;
import org.slf4j.Logger;

import java.util.Set;

import static org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceFeatures;
import static org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceQueueFamilyProperties;
import static org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceFeatures2;
import static org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceProperties2;

/**
 * Immutable snapshot of the modern GPU features relevant to Vulkanite's
 * renderer. Supported optional features are deliberately kept separate from
 * enabled device features: discovering an extension must never silently put a
 * new rendering path on the critical path.
 */
public record DeviceCapabilities(
        String deviceName,
        int queueFamilyIndex,
        int queueCount,
        boolean multiQueueOverlap,
        boolean accelerationStructure,
        boolean bufferDeviceAddress,
        boolean rayTracingPipeline,
        boolean rayQuery,
        boolean traceRaysIndirect,
        boolean sparseResidency,
        boolean meshShader,
        boolean fragmentShadingRate,
        boolean invocationReorder,
        boolean cooperativeMatrix,
        boolean deviceGeneratedCommands,
        boolean subgroupOperations,
        AccelerationTier supportedAccelerationTier) {

    private static final String KHR_ACCELERATION_STRUCTURE = "VK_KHR_acceleration_structure";
    private static final String KHR_RAY_TRACING_PIPELINE = "VK_KHR_ray_tracing_pipeline";
    private static final String KHR_RAY_QUERY = "VK_KHR_ray_query";
    private static final String EXT_MESH_SHADER = "VK_EXT_mesh_shader";
    private static final String NV_MESH_SHADER = "VK_NV_mesh_shader";
    private static final String KHR_FRAGMENT_SHADING_RATE = "VK_KHR_fragment_shading_rate";
    private static final String EXT_INVOCATION_REORDER = "VK_EXT_ray_tracing_invocation_reorder";
    private static final String NV_INVOCATION_REORDER = "VK_NV_ray_tracing_invocation_reorder";
    private static final String KHR_COOPERATIVE_MATRIX = "VK_KHR_cooperative_matrix";
    private static final String NV_COOPERATIVE_MATRIX = "VK_NV_cooperative_matrix";
    private static final String EXT_DEVICE_GENERATED_COMMANDS = "VK_EXT_device_generated_commands";
    private static final String NV_DEVICE_GENERATED_COMMANDS = "VK_NV_device_generated_commands";

    public enum AccelerationTier {
        TRIANGLE_RT,
        HYBRID_VOXEL_RT,
        GPU_DRIVEN_HYBRID
    }

    public static DeviceCapabilities detect(
            VkPhysicalDevice physicalDevice,
            Set<String> extensions,
            int queueFamilyIndex) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceFeatures features = VkPhysicalDeviceFeatures.calloc(stack);
            vkGetPhysicalDeviceFeatures(physicalDevice, features);

            VkPhysicalDeviceVulkan12Features vulkan12Features =
                    VkPhysicalDeviceVulkan12Features.calloc(stack).sType$Default();
            VkPhysicalDeviceRayTracingPipelineFeaturesKHR rayTracingFeatures =
                    VkPhysicalDeviceRayTracingPipelineFeaturesKHR.calloc(stack).sType$Default();
            VkPhysicalDeviceAccelerationStructureFeaturesKHR accelerationStructureFeatures =
                    VkPhysicalDeviceAccelerationStructureFeaturesKHR.calloc(stack)
                            .sType$Default()
                            .pNext(rayTracingFeatures.address());
            rayTracingFeatures.pNext(vulkan12Features.address());
            VkPhysicalDeviceFeatures2 features2 = VkPhysicalDeviceFeatures2.calloc(stack)
                    .sType$Default()
                    .pNext(accelerationStructureFeatures.address());
            vkGetPhysicalDeviceFeatures2(physicalDevice, features2);

            VkPhysicalDeviceSubgroupProperties subgroupProperties =
                    VkPhysicalDeviceSubgroupProperties.calloc(stack).sType$Default();
            VkPhysicalDeviceProperties2 properties2 = VkPhysicalDeviceProperties2.calloc(stack)
                    .sType$Default()
                    .pNext(subgroupProperties.address());
            vkGetPhysicalDeviceProperties2(physicalDevice, properties2);

            int[] familyCount = new int[1];
            vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, familyCount, null);
            VkQueueFamilyProperties.Buffer families = VkQueueFamilyProperties.calloc(familyCount[0], stack);
            vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, familyCount, families);
            int queueCount = queueFamilyIndex >= 0 && queueFamilyIndex < families.capacity()
                    ? families.get(queueFamilyIndex).queueCount()
                    : 0;

            boolean accelerationStructure = extensions.contains(KHR_ACCELERATION_STRUCTURE)
                    && accelerationStructureFeatures.accelerationStructure();
            boolean bufferDeviceAddress = vulkan12Features.bufferDeviceAddress();
            boolean rayTracingPipeline = accelerationStructure
                    && extensions.contains(KHR_RAY_TRACING_PIPELINE)
                    && rayTracingFeatures.rayTracingPipeline();
            boolean rayQuery = extensions.contains(KHR_RAY_QUERY);
            boolean traceRaysIndirect = rayTracingPipeline
                    && rayTracingFeatures.rayTracingPipelineTraceRaysIndirect();
            boolean sparseResidency = features.sparseBinding()
                    && (features.sparseResidencyBuffer()
                            || features.sparseResidencyImage3D()
                            || features.sparseResidencyImage2D());
            boolean meshShader = containsAny(extensions, EXT_MESH_SHADER, NV_MESH_SHADER);
            boolean fragmentShadingRate = extensions.contains(KHR_FRAGMENT_SHADING_RATE);
            boolean invocationReorder = containsAny(
                    extensions, EXT_INVOCATION_REORDER, NV_INVOCATION_REORDER);
            boolean cooperativeMatrix = containsAny(
                    extensions, KHR_COOPERATIVE_MATRIX, NV_COOPERATIVE_MATRIX);
            boolean deviceGeneratedCommands = containsAny(
                    extensions, EXT_DEVICE_GENERATED_COMMANDS, NV_DEVICE_GENERATED_COMMANDS);
            boolean subgroupOperations = subgroupProperties.supportedOperations() != 0;
            boolean multiQueueOverlap = queueCount >= 2;

            AccelerationTier tier = supportedAccelerationTier(
                    accelerationStructure && bufferDeviceAddress && rayTracingPipeline,
                    traceRaysIndirect,
                    sparseResidency,
                    subgroupOperations,
                    multiQueueOverlap);
            return new DeviceCapabilities(
                    properties2.properties().deviceNameString(),
                    queueFamilyIndex,
                    queueCount,
                    multiQueueOverlap,
                    accelerationStructure,
                    bufferDeviceAddress,
                    rayTracingPipeline,
                    rayQuery,
                    traceRaysIndirect,
                    sparseResidency,
                    meshShader,
                    fragmentShadingRate,
                    invocationReorder,
                    cooperativeMatrix,
                    deviceGeneratedCommands,
                    subgroupOperations,
                    tier);
        }
    }

    private static AccelerationTier supportedAccelerationTier(
            boolean proceduralAabbBlas,
            boolean traceRaysIndirect,
            boolean sparseResidency,
            boolean subgroupOperations,
            boolean multiQueueOverlap) {
        if (proceduralAabbBlas
                && traceRaysIndirect
                && sparseResidency
                && subgroupOperations
                && multiQueueOverlap) {
            return AccelerationTier.GPU_DRIVEN_HYBRID;
        }
        if (proceduralAabbBlas) {
            // Procedural AABBs are part of the required KHR RT pipeline; no
            // vendor-only feature is needed for the BVH-over-bricks backend.
            return AccelerationTier.HYBRID_VOXEL_RT;
        }
        return AccelerationTier.TRIANGLE_RT;
    }

    public boolean proceduralAabbBlas() {
        return accelerationStructure && bufferDeviceAddress && rayTracingPipeline;
    }

    private static boolean containsAny(Set<String> extensions, String first, String second) {
        return extensions.contains(first) || extensions.contains(second);
    }

    public void logReport(Logger logger) {
        logger.info("[Vulkanite] GPU acceleration: device='{}', tier={}, queueFamily={}, queues={}, multiQueue={}",
                deviceName, supportedAccelerationTier, queueFamilyIndex, queueCount, multiQueueOverlap);
        logger.info("[Vulkanite] GPU acceleration support: accelerationStructure={}, bufferDeviceAddress={}, rtPipeline={}, proceduralAABBs={}, rayQuery={}, indirectTrace={}, sparseResidency={}, subgroups={}",
                accelerationStructure, bufferDeviceAddress, rayTracingPipeline, proceduralAabbBlas(),
                rayQuery, traceRaysIndirect, sparseResidency, subgroupOperations);
        logger.info("[Vulkanite] Optional GPU support: meshShader={}, VRS={}, invocationReorder={}, cooperativeMatrix={}, deviceGeneratedCommands={}",
                meshShader, fragmentShadingRate, invocationReorder, cooperativeMatrix, deviceGeneratedCommands);
    }
}
