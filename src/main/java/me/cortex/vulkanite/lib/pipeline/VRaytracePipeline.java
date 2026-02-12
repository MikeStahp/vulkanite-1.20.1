package me.cortex.vulkanite.lib.pipeline;

import me.cortex.vulkanite.lib.base.VContext;
import me.cortex.vulkanite.lib.base.VObject;
import me.cortex.vulkanite.lib.base.VRef;
import me.cortex.vulkanite.lib.memory.VBuffer;
import me.cortex.vulkanite.lib.shader.ShaderModule;
import me.cortex.vulkanite.lib.shader.reflection.ShaderReflection;
import org.lwjgl.vulkan.VkStridedDeviceAddressRegionKHR;

import java.util.Set;

import static org.lwjgl.vulkan.KHRRayTracingPipeline.VK_PIPELINE_BIND_POINT_RAY_TRACING_KHR;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.vkCmdTraceRaysKHR;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Represents a Vulkan ray tracing pipeline with improved resource management.
 */
public class VRaytracePipeline extends VObject {
    private final VContext context;
    public final long pipeline;
    public final long layout;
    @SuppressWarnings("FieldCanBeLocal")
    private final VRef<VBuffer> shader_binding_table;
    public final VkStridedDeviceAddressRegionKHR gen;
    public final VkStridedDeviceAddressRegionKHR miss;
    public final VkStridedDeviceAddressRegionKHR hit;
    public final VkStridedDeviceAddressRegionKHR callable;
    private final Set<ShaderModule> shadersUsed;
    public final ShaderReflection reflection;
    private final String debugName;

    /**
     * Constructs a new ray tracing pipeline.
     *
     * @param context             The Vulkan context
     * @param pipeline            The pipeline handle
     * @param layout              The pipeline layout handle
     * @param sbtMap              The shader binding table buffer reference
     * @param raygen              The ray generation shader region
     * @param miss                The miss shaders region
     * @param hit                 The hit shaders region
     * @param callable            The callable shaders region
     * @param shadersUsed         The set of shaders used in this pipeline
     * @param reflection          The shader reflection information
     */
    VRaytracePipeline(VContext context, long pipeline, long layout, final VRef<VBuffer> sbtMap,
                      VkStridedDeviceAddressRegionKHR raygen,
                      VkStridedDeviceAddressRegionKHR miss,
                      VkStridedDeviceAddressRegionKHR hit,
                      VkStridedDeviceAddressRegionKHR callable,
                      Set<ShaderModule> shadersUsed,
                      ShaderReflection reflection) {
        this(context, pipeline, layout, sbtMap, raygen, miss, hit, callable, shadersUsed, reflection, "RaytracePipeline");
    }

    /**
     * Constructs a new ray tracing pipeline with a debug name.
     *
     * @param context             The Vulkan context
     * @param pipeline            The pipeline handle
     * @param layout              The pipeline layout handle
     * @param sbtMap              The shader binding table buffer reference
     * @param raygen              The ray generation shader region
     * @param miss                The miss shaders region
     * @param hit                 The hit shaders region
     * @param callable            The callable shaders region
     * @param shadersUsed         The set of shaders used in this pipeline
     * @param reflection          The shader reflection information
     * @param debugName           The debug name for this pipeline
     */
    VRaytracePipeline(VContext context, long pipeline, long layout, final VRef<VBuffer> sbtMap,
                      VkStridedDeviceAddressRegionKHR raygen,
                      VkStridedDeviceAddressRegionKHR miss,
                      VkStridedDeviceAddressRegionKHR hit,
                      VkStridedDeviceAddressRegionKHR callable,
                      Set<ShaderModule> shadersUsed,
                      ShaderReflection reflection,
                      String debugName) {
        this.context = context;
        this.pipeline = pipeline;
        this.layout = layout;
        this.shader_binding_table = sbtMap.addRef();
        this.gen = raygen;
        this.miss = miss;
        this.hit = hit;
        this.callable = callable;
        this.shadersUsed = shadersUsed;
        this.reflection = reflection;
        this.debugName = debugName;
    }

    /**
     * Gets the debug name of this pipeline.
     *
     * @return The debug name
     */
    public String getDebugName() {
        return debugName;
    }

    /**
     * Sets the debug object name for this pipeline.
     *
     * @param name The debug name
     */
    public void setDebugUtilsObjectName(String name) {
        // Debug utils naming is handled elsewhere in the pipeline creation process
    }

    @Override
    protected void free() {
        vkDestroyPipeline(context.device, pipeline, null);
        gen.free();
        miss.free();
        hit.free();
        callable.free();
        reflection.freeLayouts();
    }

    @Override
    public String toString() {
        return "VRaytracePipeline{" +
                "debugName='" + debugName + '\'' +
                ", pipeline=0x" + Long.toHexString(pipeline) +
                ", layout=0x" + Long.toHexString(layout) +
                '}';
    }
}
