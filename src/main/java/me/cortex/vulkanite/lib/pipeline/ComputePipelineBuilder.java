package me.cortex.vulkanite.lib.pipeline;

import me.cortex.vulkanite.lib.base.VContext;
import me.cortex.vulkanite.lib.base.VRef;
import me.cortex.vulkanite.lib.shader.ShaderModule;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;

import static me.cortex.vulkanite.lib.other.VUtil._CHECK_;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Builder for Vulkan compute pipelines with improved error handling and debugging capabilities.
 */
public class ComputePipelineBuilder extends PipelineBuilder<ComputePipelineBuilder> {
    private ShaderModule computeShader;

    public ComputePipelineBuilder() {
        super();
    }

    /**
     * Sets the compute shader module for this pipeline.
     *
     * @param shader The compute shader module
     * @return This builder instance for chaining
     */
    public ComputePipelineBuilder setShader(ShaderModule shader) {
        this.computeShader = shader;
        return this;
    }

    /**
     * Sets the compute shader module for this pipeline (alias for setShader to maintain API compatibility).
     *
     * @param shader The compute shader module
     * @return This builder instance for chaining
     */
    public ComputePipelineBuilder set(ShaderModule shader) {
        return setShader(shader);
    }

    /**
     * Builds the compute pipeline with the configured settings.
     *
     * @param context The Vulkan context
     * @return A reference to the created compute pipeline
     * @throws IllegalStateException if required components are missing
     */
    public VRef<VComputePipeline> build(VContext context) throws IllegalStateException {
        validate();

        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Create pipeline layout
            long pipelineLayout = createPipelineLayout(context, stack);

            // Setup shader stage
            VkPipelineShaderStageCreateInfo shaderStage = VkPipelineShaderStageCreateInfo.calloc(stack);
            computeShader.setupStruct(stack, shaderStage);

            // Create compute pipeline
            LongBuffer pPipeline = stack.mallocLong(1);
            int result = vkCreateComputePipelines(context.device, 0,
                    VkComputePipelineCreateInfo.calloc(1, stack)
                            .sType(VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO)
                            .layout(pipelineLayout)
                            .stage(shaderStage),
                    null, pPipeline);

            _CHECK_(result, "Failed to create compute pipeline");

            return new VRef<>(new VComputePipeline(context, pipelineLayout, pPipeline.get(0)));
        } catch (Exception e) {
            throw new RuntimeException("Failed to build compute pipeline: " + e.getMessage(), e);
        }
    }

    @Override
    protected void validate() throws IllegalStateException {
        if (computeShader == null) {
            throw new IllegalStateException("Compute shader must be set before building pipeline");
        }
    }
}
