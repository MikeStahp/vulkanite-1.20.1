package me.cortex.vulkanite.lib.pipeline;

import me.cortex.vulkanite.lib.base.VContext;
import me.cortex.vulkanite.lib.base.VRef;
import me.cortex.vulkanite.lib.memory.VBuffer;
import me.cortex.vulkanite.lib.shader.ShaderModule;
import me.cortex.vulkanite.lib.shader.reflection.ShaderReflection;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.*;

import static me.cortex.vulkanite.lib.other.VUtil._CHECK_;
import static me.cortex.vulkanite.lib.other.VUtil.alignUp;
import static org.lwjgl.util.vma.Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT;
import static org.lwjgl.vulkan.KHRBufferDeviceAddress.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT_KHR;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.*;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Builder for Vulkan ray tracing pipelines with improved error handling and debugging capabilities.
 */
public class RaytracePipelineBuilder extends PipelineBuilder<RaytracePipelineBuilder> {
    private final Set<ShaderModule> shaders = new LinkedHashSet<>();
    private ShaderModule rayGenShader;
    private final List<ShaderModule> missShaders = new ArrayList<>();
    private final List<HitGroup> hitGroups = new ArrayList<>();
    private final List<ShaderModule> callableShaders = new ArrayList<>();

    public RaytracePipelineBuilder() {
        super();
    }

    /**
     * Sets the ray generation shader for this pipeline.
     *
     * @param gen The ray generation shader module
     * @return This builder instance for chaining
     */
    public RaytracePipelineBuilder setRayGen(ShaderModule gen) {
        this.rayGenShader = gen;
        return this;
    }

    /**
     * Adds a miss shader to this pipeline.
     *
     * @param miss The miss shader module
     * @return This builder instance for chaining
     */
    public RaytracePipelineBuilder addMiss(ShaderModule miss) {
        shaders.add(miss);
        missShaders.add(miss);
        return this;
    }

    /**
     * Adds a hit group to this pipeline.
     *
     * @param closestHit    The closest hit shader module (can be null)
     * @param anyHit        The any hit shader module (can be null)
     * @param intersection  The intersection shader module (can be null)
     * @return This builder instance for chaining
     */
    public RaytracePipelineBuilder addHit(ShaderModule closestHit, ShaderModule anyHit, ShaderModule intersection) {
        if (closestHit != null) {
            shaders.add(closestHit);
        }
        if (anyHit != null) {
            shaders.add(anyHit);
        }
        if (intersection != null) {
            shaders.add(intersection);
        }
        hitGroups.add(new HitGroup(closestHit, anyHit, intersection));
        return this;
    }

    /**
     * Adds a callable shader to this pipeline.
     *
     * @param callable The callable shader module
     * @return This builder instance for chaining
     */
    public RaytracePipelineBuilder addCallable(ShaderModule callable) {
        shaders.add(callable);
        callableShaders.add(callable);
        return this;
    }

    /**
     * Builds the ray tracing pipeline with the configured settings.
     *
     * @param context   The Vulkan context
     * @param maxDepth  Maximum recursion depth for ray tracing
     * @return A reference to the created ray tracing pipeline
     * @throws IllegalStateException if required components are missing
     */
    public VRef<VRaytracePipeline> build(VContext context, int maxDepth) throws IllegalStateException {
        validate();

        shaders.add(rayGenShader);

        // Merge shader reflections
        ShaderReflection reflection = mergeShaderReflections();

        // Build layouts if none were provided
        if (layouts.isEmpty()) {
            layouts.addAll(reflection.buildSetLayouts(context));
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Create shader stages
            VkPipelineShaderStageCreateInfo.Buffer shaderStages = createShaderStages(stack);
            Map<ShaderModule, Integer> shaderToId = createShaderToIdMap();

            // Create shader groups
            VkRayTracingShaderGroupCreateInfoKHR.Buffer groups = createShaderGroups(stack, shaderToId);

            // Create pipeline layout
            long pipelineLayout = createPipelineLayout(context, stack);

            // Create ray tracing pipeline
            LongBuffer pPipeline = stack.mallocLong(1);
            VkRayTracingPipelineCreateInfoKHR pipelineCreateInfo = VkRayTracingPipelineCreateInfoKHR.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_RAY_TRACING_PIPELINE_CREATE_INFO_KHR)
                    .layout(pipelineLayout)
                    .pStages(shaderStages)
                    .pGroups(groups)
                    .maxPipelineRayRecursionDepth(maxDepth);
            
            int result = vkCreateRayTracingPipelinesKHR(context.device, 0, 0,
                    VkRayTracingPipelineCreateInfoKHR.create(pipelineCreateInfo.address(), 1),
                    null, pPipeline);

            _CHECK_(result, "Failed to create ray tracing pipeline");

            // Generate shader binding table
            ShaderBindingTable sbt = generateShaderBindingTable(context, stack, pPipeline.get(0));

            return new VRef<>(new VRaytracePipeline(context, pPipeline.get(0), pipelineLayout, sbt.bufferRef,
                    sbt.rayGenRegion, sbt.missRegion, sbt.hitRegion, sbt.callableRegion,
                    shaders, reflection));
        } catch (Exception e) {
            throw new RuntimeException("Failed to build ray tracing pipeline: " + e.getMessage(), e);
        }
    }

    @Override
    protected void validate() throws IllegalStateException {
        if (rayGenShader == null) {
            throw new IllegalStateException("Ray generation shader must be set before building pipeline");
        }
    }

    /**
     * Merges shader reflections from all shaders in this pipeline.
     *
     * @return The merged shader reflection
     */
    private ShaderReflection mergeShaderReflections() {
        List<ShaderReflection> reflections = new ArrayList<>();
        for (ShaderModule shader : shaders) {
            reflections.add(shader.shader().get().getReflection());
        }

        try {
            return ShaderReflection.mergeStages(reflections.toArray(ShaderReflection[]::new));
        } catch (Exception e) {
            System.err.println("Failed to merge shader reflections, this is likely due to a mismatch in descriptor sets");
            throw e;
        }
    }

    /**
     * Creates shader stage info structures for all shaders in this pipeline.
     *
     * @param stack The memory stack for allocations
     * @return Buffer containing shader stage info structures
     */
    private VkPipelineShaderStageCreateInfo.Buffer createShaderStages(MemoryStack stack) {
        VkPipelineShaderStageCreateInfo.Buffer shaderStages = VkPipelineShaderStageCreateInfo.calloc(shaders.size(), stack);
        int index = 0;
        for (ShaderModule shader : shaders) {
            shader.setupStruct(stack, shaderStages.get(index++));
        }
        return shaderStages.rewind();
    }

    /**
     * Creates a mapping from shader modules to their indices.
     *
     * @return Map from shader modules to indices
     */
    private Map<ShaderModule, Integer> createShaderToIdMap() {
        Map<ShaderModule, Integer> shaderToId = new HashMap<>();
        int index = 0;
        for (ShaderModule shader : shaders) {
            shaderToId.put(shader, index++);
        }
        return shaderToId;
    }

    /**
     * Creates shader group info structures for all shader groups in this pipeline.
     *
     * @param stack      The memory stack for allocations
     * @param shaderToId Mapping from shader modules to indices
     * @return Buffer containing shader group info structures
     */
    private VkRayTracingShaderGroupCreateInfoKHR.Buffer createShaderGroups(MemoryStack stack, Map<ShaderModule, Integer> shaderToId) {
        int totalGroups = 1 + missShaders.size() + hitGroups.size() + callableShaders.size();
        VkRayTracingShaderGroupCreateInfoKHR.Buffer groups = VkRayTracingShaderGroupCreateInfoKHR
                .calloc(totalGroups, stack);

        // Initialize all groups with default values
        for (int i = 0; i < totalGroups; i++) {
            groups.get(i)
                    .sType(VK_STRUCTURE_TYPE_RAY_TRACING_SHADER_GROUP_CREATE_INFO_KHR)
                    .type(VK_SHADER_GROUP_SHADER_GENERAL_KHR)
                    .generalShader(VK_SHADER_UNUSED_KHR)
                    .closestHitShader(VK_SHADER_UNUSED_KHR)
                    .anyHitShader(VK_SHADER_UNUSED_KHR)
                    .intersectionShader(VK_SHADER_UNUSED_KHR);
        }
        groups.rewind();

        int groupIndex = 0;

        // Set ray generation shader group
        groups.get(groupIndex++)
                .type(VK_SHADER_GROUP_SHADER_GENERAL_KHR)
                .generalShader(shaderToId.get(rayGenShader));

        // Set miss shader groups
        for (ShaderModule miss : missShaders) {
            groups.get(groupIndex++)
                    .type(VK_SHADER_GROUP_SHADER_GENERAL_KHR)
                    .generalShader(shaderToId.get(miss));
        }

        // Set hit shader groups
        for (HitGroup hit : hitGroups) {
            groups.get(groupIndex++)
                    .type(hit.intersection == null ?
                            VK_RAY_TRACING_SHADER_GROUP_TYPE_TRIANGLES_HIT_GROUP_KHR :
                            VK_RAY_TRACING_SHADER_GROUP_TYPE_PROCEDURAL_HIT_GROUP_KHR)
                    .closestHitShader(hit.closestHit == null ? VK_SHADER_UNUSED_KHR : shaderToId.get(hit.closestHit))
                    .anyHitShader(hit.anyHit == null ? VK_SHADER_UNUSED_KHR : shaderToId.get(hit.anyHit))
                    .intersectionShader(hit.intersection == null ? VK_SHADER_UNUSED_KHR : shaderToId.get(hit.intersection));
        }

        // Set callable shader groups
        for (ShaderModule callable : callableShaders) {
            groups.get(groupIndex++)
                    .type(VK_SHADER_GROUP_SHADER_GENERAL_KHR)
                    .generalShader(shaderToId.get(callable));
        }

        return groups.rewind();
    }

    /**
     * Generates the shader binding table for this pipeline.
     *
     * @param context   The Vulkan context
     * @param stack     The memory stack for allocations
     * @param pipeline  The pipeline handle
     * @return The generated shader binding table
     */
    private ShaderBindingTable generateShaderBindingTable(VContext context, MemoryStack stack, long pipeline) {
        var props = context.properties.rtPipelineProperties;
        long groupBaseAlignment = props.shaderGroupBaseAlignment();
        long handleSize = props.shaderGroupHandleSize();
        long handleSizeAligned = alignUp(handleSize, props.shaderGroupHandleAlignment());

        int totalGroups = 1 + missShaders.size() + hitGroups.size() + callableShaders.size();

        long rgenBase = 0;
        long missGroupBase = alignUp(rgenBase + handleSizeAligned, groupBaseAlignment);
        long missGroupCount = missShaders.size();
        long hitGroupsBase = alignUp(missGroupBase + handleSizeAligned * missGroupCount, groupBaseAlignment);
        long hitGroupsCount = hitGroups.size();
        long callGroupBase = alignUp(hitGroupsBase + handleSizeAligned * hitGroupsCount, groupBaseAlignment);
        long callGroupCount = callableShaders.size();

        // Pad an extra handle size at the end, so that even if we don't have call
        // groups, the address is still in bounds
        long sbtSize = alignUp(callGroupBase + callGroupCount * handleSizeAligned + handleSizeAligned,
                groupBaseAlignment);

        ByteBuffer handles = stack.malloc(totalGroups * (int) handleSize);
        _CHECK_(vkGetRayTracingShaderGroupHandlesKHR(context.device, pipeline, 0, totalGroups, handles),
                "Failed to obtain ray tracing group handles");

        long aHandles = MemoryUtil.memAddress(handles);

        // Create shader binding table buffer
        VRef<VBuffer> sbtBufferRef = context.memory.createBuffer(sbtSize,
                VK_BUFFER_USAGE_TRANSFER_DST_BIT |
                        VK_BUFFER_USAGE_SHADER_BINDING_TABLE_BIT_KHR |
                        VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT_KHR,
                VK_MEMORY_HEAP_DEVICE_LOCAL_BIT | VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT,
                0, VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT);
        VBuffer sbtBuffer = sbtBufferRef.get();
        sbtBuffer.setDebugUtilsObjectName("SBT");
        long ptr = sbtBuffer.map();

        // Copy ray generation group handle
        MemoryUtil.memCopy(aHandles, ptr + rgenBase, handleSize);
        aHandles += handleSize;

        // Copy miss group handles
        for (int i = 0; i < missGroupCount; i++) {
            MemoryUtil.memCopy(aHandles, ptr + missGroupBase + handleSizeAligned * i, handleSize);
            aHandles += handleSize;
        }

        // Copy hit group handles
        for (int i = 0; i < hitGroupsCount; i++) {
            MemoryUtil.memCopy(aHandles, ptr + hitGroupsBase + handleSizeAligned * i, handleSize);
            aHandles += handleSize;
        }

        // Copy callable group handles
        for (int i = 0; i < callGroupCount; i++) {
            MemoryUtil.memCopy(aHandles, ptr + callGroupBase + handleSizeAligned * i, handleSize);
            aHandles += handleSize;
        }

        sbtBuffer.unmap();
        sbtBuffer.flush();

        // Create strided device address regions
        VkStridedDeviceAddressRegionKHR rayGenRegion = VkStridedDeviceAddressRegionKHR.calloc()
                .set(sbtBuffer.deviceAddress() + rgenBase, handleSizeAligned, handleSizeAligned);
        VkStridedDeviceAddressRegionKHR missRegion = VkStridedDeviceAddressRegionKHR.calloc()
                .set(sbtBuffer.deviceAddress() + missGroupBase, handleSizeAligned, handleSizeAligned * missGroupCount);
        VkStridedDeviceAddressRegionKHR hitRegion = VkStridedDeviceAddressRegionKHR.calloc()
                .set(sbtBuffer.deviceAddress() + hitGroupsBase, handleSizeAligned, handleSizeAligned * hitGroupsCount);
        VkStridedDeviceAddressRegionKHR callableRegion = VkStridedDeviceAddressRegionKHR.calloc()
                .set(sbtBuffer.deviceAddress() + callGroupBase, handleSizeAligned, handleSizeAligned * callGroupCount);

        return new ShaderBindingTable(sbtBufferRef, rayGenRegion, missRegion, hitRegion, callableRegion);
    }

    /**
     * Internal representation of a hit group.
     */
    private static class HitGroup {
        final ShaderModule closestHit;
        final ShaderModule anyHit;
        final ShaderModule intersection;

        HitGroup(ShaderModule closestHit, ShaderModule anyHit, ShaderModule intersection) {
            this.closestHit = closestHit;
            this.anyHit = anyHit;
            this.intersection = intersection;
        }
    }

    /**
     * Container for shader binding table data.
     */
    private static class ShaderBindingTable {
        final VRef<VBuffer> bufferRef;
        final VkStridedDeviceAddressRegionKHR rayGenRegion;
        final VkStridedDeviceAddressRegionKHR missRegion;
        final VkStridedDeviceAddressRegionKHR hitRegion;
        final VkStridedDeviceAddressRegionKHR callableRegion;

        ShaderBindingTable(VRef<VBuffer> bufferRef,
                          VkStridedDeviceAddressRegionKHR rayGenRegion,
                          VkStridedDeviceAddressRegionKHR missRegion,
                          VkStridedDeviceAddressRegionKHR hitRegion,
                          VkStridedDeviceAddressRegionKHR callableRegion) {
            this.bufferRef = bufferRef;
            this.rayGenRegion = rayGenRegion;
            this.missRegion = missRegion;
            this.hitRegion = hitRegion;
            this.callableRegion = callableRegion;
        }
    }
}
