package me.cortex.vulkanite.lib.pipeline;

import me.cortex.vulkanite.lib.base.VContext;
import me.cortex.vulkanite.lib.base.VObject;

import static org.lwjgl.vulkan.VK10.vkDestroyPipeline;
import static org.lwjgl.vulkan.VK10.vkDestroyPipelineLayout;

/**
 * Represents a Vulkan compute pipeline with improved resource management.
 */
public class VComputePipeline extends VObject {
    private final VContext context;
    private final long pipeline;
    private final long layout;
    private final String debugName;

    /**
     * Constructs a new compute pipeline.
     *
     * @param context  The Vulkan context
     * @param layout   The pipeline layout handle
     * @param pipeline The pipeline handle
     */
    public VComputePipeline(VContext context, long layout, long pipeline) {
        this(context, layout, pipeline, "ComputePipeline");
    }

    /**
     * Constructs a new compute pipeline with a debug name.
     *
     * @param context   The Vulkan context
     * @param layout    The pipeline layout handle
     * @param pipeline  The pipeline handle
     * @param debugName The debug name for this pipeline
     */
    public VComputePipeline(VContext context, long layout, long pipeline, String debugName) {
        this.context = context;
        this.layout = layout;
        this.pipeline = pipeline;
        this.debugName = debugName;
        
        // Set debug object name if available
        setDebugUtilsObjectName(debugName);
    }

    /**
     * Gets the pipeline layout handle.
     *
     * @return The pipeline layout handle
     */
    public long layout() {
        return layout;
    }

    /**
     * Gets the pipeline handle.
     *
     * @return The pipeline handle
     */
    public long pipeline() {
        return pipeline;
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
    public void free() {
        vkDestroyPipeline(context.device, pipeline, null);
        vkDestroyPipelineLayout(context.device, layout, null);
    }

    @Override
    public String toString() {
        return "VComputePipeline{" +
                "debugName='" + debugName + '\'' +
                ", pipeline=0x" + Long.toHexString(pipeline) +
                ", layout=0x" + Long.toHexString(layout) +
                '}';
    }
}
