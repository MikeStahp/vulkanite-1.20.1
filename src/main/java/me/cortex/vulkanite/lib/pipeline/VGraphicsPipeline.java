package me.cortex.vulkanite.lib.pipeline;

import me.cortex.vulkanite.lib.base.VContext;
import me.cortex.vulkanite.lib.base.VObject;

import static org.lwjgl.vulkan.VK10.vkDestroyPipeline;
import static org.lwjgl.vulkan.VK10.vkDestroyPipelineLayout;

/**
 * Represents a Vulkan graphics pipeline with improved resource management.
 */
public class VGraphicsPipeline extends VObject {
    private final VContext context;
    private final long pipeline;
    private final long layout;
    private final String debugName;
    
    /**
     * Constructs a new graphics pipeline.
     *
     * @param context  The Vulkan context
     * @param layout   The pipeline layout handle
     * @param pipeline The pipeline handle
     */
    public VGraphicsPipeline(VContext context, long layout, long pipeline) {
        this(context, layout, pipeline, "GraphicsPipeline");
    }
    
    /**
     * Constructs a new graphics pipeline with a debug name.
     *
     * @param context   The Vulkan context
     * @param layout    The pipeline layout handle
     * @param pipeline  The pipeline handle
     * @param debugName The debug name for this pipeline
     */
    public VGraphicsPipeline(VContext context, long layout, long pipeline, String debugName) {
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
        if (context.hasDebugUtils) {
            context.setDebugUtilsObjectName(pipeline, 19 /* VK_OBJECT_TYPE_PIPELINE */, name);
        }
    }
    
    @Override
    public void free() {
        if (pipeline != 0) {
            vkDestroyPipeline(context.device, pipeline, null);
        }
        if (layout != 0) {
            vkDestroyPipelineLayout(context.device, layout, null);
        }
    }
    
    @Override
    public String toString() {
        return "VGraphicsPipeline{" +
                "debugName='" + debugName + '\'' +
                ", pipeline=0x" + Long.toHexString(pipeline) +
                ", layout=0x" + Long.toHexString(layout) +
                '}';
    }
}