package me.cortex.vulkanite.lib.pipeline;

import me.cortex.vulkanite.lib.base.VContext;
import me.cortex.vulkanite.lib.base.VRef;
import me.cortex.vulkanite.lib.shader.ShaderModule;
import me.cortex.vulkanite.lib.other.VUtil;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;

import static me.cortex.vulkanite.lib.other.VUtil._CHECK_;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Builder for Vulkan graphics pipelines with improved error handling and debugging capabilities.
 */
public class GraphicsPipelineBuilder extends PipelineBuilder<GraphicsPipelineBuilder> {
    private ShaderModule vertexShader;
    private ShaderModule fragmentShader;
    private ShaderModule geometryShader;
    private ShaderModule tessControlShader;
    private ShaderModule tessEvaluationShader;
    
    private VkPipelineVertexInputStateCreateInfo vertexInputState;
    private VkPipelineInputAssemblyStateCreateInfo inputAssemblyState;
    private VkPipelineViewportStateCreateInfo viewportState;
    private VkPipelineRasterizationStateCreateInfo rasterizationState;
    private VkPipelineMultisampleStateCreateInfo multisampleState;
    private VkPipelineDepthStencilStateCreateInfo depthStencilState;
    private VkPipelineColorBlendStateCreateInfo colorBlendState;
    private VkPipelineDynamicStateCreateInfo dynamicState;
    
    private long renderPass;
    private int subpass;
    
    public GraphicsPipelineBuilder() {
        super();
        // Set default states
        setupDefaultStates();
    }
    
    /**
     * Sets up default pipeline states with reasonable defaults.
     */
    private void setupDefaultStates() {
        // Default vertex input state (no vertex attributes)
        vertexInputState = VkPipelineVertexInputStateCreateInfo.calloc()
                .sType(VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO);
        
        // Default input assembly state (triangle list)
        inputAssemblyState = VkPipelineInputAssemblyStateCreateInfo.calloc()
                .sType(VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO)
                .topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST)
                .primitiveRestartEnable(false);
        
        // Default viewport state (needs to be configured by caller)
        // This is just a placeholder that will be overridden
        viewportState = VkPipelineViewportStateCreateInfo.calloc()
                .sType(VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO)
                .viewportCount(1)
                .scissorCount(1);
        
        // Default rasterization state
        rasterizationState = VkPipelineRasterizationStateCreateInfo.calloc()
                .sType(VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO)
                .depthClampEnable(false)
                .rasterizerDiscardEnable(false)
                .polygonMode(VK_POLYGON_MODE_FILL)
                .lineWidth(1.0f)
                .cullMode(VK_CULL_MODE_BACK_BIT)
                .frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE)
                .depthBiasEnable(false);
        
        // Default multisample state
        multisampleState = VkPipelineMultisampleStateCreateInfo.calloc()
                .sType(VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO)
                .sampleShadingEnable(false)
                .rasterizationSamples(VK_SAMPLE_COUNT_1_BIT);
        
        // Default depth stencil state (disabled)
        depthStencilState = VkPipelineDepthStencilStateCreateInfo.calloc()
                .sType(VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO)
                .depthTestEnable(false)
                .depthWriteEnable(false)
                .depthCompareOp(VK_COMPARE_OP_ALWAYS)
                .depthBoundsTestEnable(false)
                .stencilTestEnable(false);
        
        // Default color blend state (no blending)
        VkPipelineColorBlendAttachmentState.Buffer attachments = VkPipelineColorBlendAttachmentState.calloc(1)
                .blendEnable(false)
                .colorWriteMask(VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT);
        
        colorBlendState = VkPipelineColorBlendStateCreateInfo.calloc()
                .sType(VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO)
                .logicOpEnable(false)
                .logicOp(VK_LOGIC_OP_COPY)
                .pAttachments(attachments)
                .blendConstants(0, 0.0f)
                .blendConstants(1, 0.0f)
                .blendConstants(2, 0.0f)
                .blendConstants(3, 0.0f);
    }
    
    /**
     * Sets the vertex shader module for this pipeline.
     *
     * @param shader The vertex shader module
     * @return This builder instance for chaining
     */
    public GraphicsPipelineBuilder setVertexShader(ShaderModule shader) {
        this.vertexShader = shader;
        return this;
    }
    
    /**
     * Sets the fragment shader module for this pipeline.
     *
     * @param shader The fragment shader module
     * @return This builder instance for chaining
     */
    public GraphicsPipelineBuilder setFragmentShader(ShaderModule shader) {
        this.fragmentShader = shader;
        return this;
    }
    
    /**
     * Sets the geometry shader module for this pipeline.
     *
     * @param shader The geometry shader module
     * @return This builder instance for chaining
     */
    public GraphicsPipelineBuilder setGeometryShader(ShaderModule shader) {
        this.geometryShader = shader;
        return this;
    }
    
    /**
     * Sets the tessellation control shader module for this pipeline.
     *
     * @param shader The tessellation control shader module
     * @return This builder instance for chaining
     */
    public GraphicsPipelineBuilder setTessControlShader(ShaderModule shader) {
        this.tessControlShader = shader;
        return this;
    }
    
    /**
     * Sets the tessellation evaluation shader module for this pipeline.
     *
     * @param shader The tessellation evaluation shader module
     * @return This builder instance for chaining
     */
    public GraphicsPipelineBuilder setTessEvaluationShader(ShaderModule shader) {
        this.tessEvaluationShader = shader;
        return this;
    }
    
    /**
     * Sets the vertex input state for this pipeline.
     *
     * @param vertexInputState The vertex input state
     * @return This builder instance for chaining
     */
    public GraphicsPipelineBuilder setVertexInputState(VkPipelineVertexInputStateCreateInfo vertexInputState) {
        this.vertexInputState = vertexInputState;
        return this;
    }
    
    /**
     * Sets the input assembly state for this pipeline.
     *
     * @param inputAssemblyState The input assembly state
     * @return This builder instance for chaining
     */
    public GraphicsPipelineBuilder setInputAssemblyState(VkPipelineInputAssemblyStateCreateInfo inputAssemblyState) {
        this.inputAssemblyState = inputAssemblyState;
        return this;
    }
    
    /**
     * Sets the viewport state for this pipeline.
     *
     * @param viewportState The viewport state
     * @return This builder instance for chaining
     */
    public GraphicsPipelineBuilder setViewportState(VkPipelineViewportStateCreateInfo viewportState) {
        this.viewportState = viewportState;
        return this;
    }
    
    /**
     * Sets the rasterization state for this pipeline.
     *
     * @param rasterizationState The rasterization state
     * @return This builder instance for chaining
     */
    public GraphicsPipelineBuilder setRasterizationState(VkPipelineRasterizationStateCreateInfo rasterizationState) {
        this.rasterizationState = rasterizationState;
        return this;
    }
    
    /**
     * Sets the multisample state for this pipeline.
     *
     * @param multisampleState The multisample state
     * @return This builder instance for chaining
     */
    public GraphicsPipelineBuilder setMultisampleState(VkPipelineMultisampleStateCreateInfo multisampleState) {
        this.multisampleState = multisampleState;
        return this;
    }
    
    /**
     * Sets the depth stencil state for this pipeline.
     *
     * @param depthStencilState The depth stencil state
     * @return This builder instance for chaining
     */
    public GraphicsPipelineBuilder setDepthStencilState(VkPipelineDepthStencilStateCreateInfo depthStencilState) {
        this.depthStencilState = depthStencilState;
        return this;
    }
    
    /**
     * Sets the color blend state for this pipeline.
     *
     * @param colorBlendState The color blend state
     * @return This builder instance for chaining
     */
    public GraphicsPipelineBuilder setColorBlendState(VkPipelineColorBlendStateCreateInfo colorBlendState) {
        this.colorBlendState = colorBlendState;
        return this;
    }
    
    /**
     * Sets the dynamic state for this pipeline.
     *
     * @param dynamicState The dynamic state
     * @return This builder instance for chaining
     */
    public GraphicsPipelineBuilder setDynamicState(VkPipelineDynamicStateCreateInfo dynamicState) {
        this.dynamicState = dynamicState;
        return this;
    }
    
    /**
     * Sets the render pass and subpass for this pipeline.
     *
     * @param renderPass The render pass
     * @param subpass    The subpass index
     * @return This builder instance for chaining
     */
    public GraphicsPipelineBuilder setRenderPass(long renderPass, int subpass) {
        this.renderPass = renderPass;
        this.subpass = subpass;
        return this;
    }
    
    /**
     * Builds the graphics pipeline with the configured settings.
     *
     * @param context The Vulkan context
     * @return A reference to the created graphics pipeline
     * @throws IllegalStateException if required components are missing
     */
    public VRef<VGraphicsPipeline> build(VContext context) throws IllegalStateException {
        validate();
        
        List<ShaderModule> shaders = new ArrayList<>();
        if (vertexShader != null) shaders.add(vertexShader);
        if (fragmentShader != null) shaders.add(fragmentShader);
        if (geometryShader != null) shaders.add(geometryShader);
        if (tessControlShader != null) shaders.add(tessControlShader);
        if (tessEvaluationShader != null) shaders.add(tessEvaluationShader);
        
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Create pipeline layout
            long pipelineLayout = createPipelineLayout(context, stack);
            
            // Setup shader stages
            VkPipelineShaderStageCreateInfo.Buffer shaderStages = createShaderStages(stack, shaders);
            
            // Create graphics pipeline
            LongBuffer pPipeline = stack.mallocLong(1);
            
            VkGraphicsPipelineCreateInfo pipelineCreateInfo = VkGraphicsPipelineCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO)
                    .layout(pipelineLayout)
                    .renderPass(renderPass)
                    .subpass(subpass)
                    .pStages(shaderStages)
                    .pVertexInputState(vertexInputState)
                    .pInputAssemblyState(inputAssemblyState)
                    .pViewportState(viewportState)
                    .pRasterizationState(rasterizationState)
                    .pMultisampleState(multisampleState)
                    .pDepthStencilState(depthStencilState)
                    .pColorBlendState(colorBlendState);
            
            if (dynamicState != null) {
                pipelineCreateInfo.pDynamicState(dynamicState);
            }
            
            int result = vkCreateGraphicsPipelines(context.device, VK_NULL_HANDLE,
                    VkGraphicsPipelineCreateInfo.create(pipelineCreateInfo.address(), 1),
                    null, pPipeline);
            
            _CHECK_(result, "Failed to create graphics pipeline");
            
            // Clean up allocated state objects
            if (colorBlendState != null && colorBlendState.pAttachments() != null) {
                colorBlendState.pAttachments().free();
            }
            
            return new VRef<>(new VGraphicsPipeline(context, pipelineLayout, pPipeline.get(0)));
        } catch (Exception e) {
            // Clean up allocated state objects in case of exception
            if (colorBlendState != null && colorBlendState.pAttachments() != null) {
                colorBlendState.pAttachments().free();
            }
            throw new RuntimeException("Failed to build graphics pipeline: " + e.getMessage(), e);
        }
    }
    
    /**
     * Creates shader stage info structures for all shaders in this pipeline.
     *
     * @param stack   The memory stack for allocations
     * @param shaders The list of shader modules
     * @return Buffer containing shader stage info structures
     */
    private VkPipelineShaderStageCreateInfo.Buffer createShaderStages(MemoryStack stack, List<ShaderModule> shaders) {
        VkPipelineShaderStageCreateInfo.Buffer shaderStages = VkPipelineShaderStageCreateInfo.calloc(shaders.size(), stack);
        for (int i = 0; i < shaders.size(); i++) {
            shaders.get(i).setupStruct(stack, shaderStages.get(i));
        }
        return shaderStages.rewind();
    }
    
    @Override
    protected void validate() throws IllegalStateException {
        if (renderPass == VK_NULL_HANDLE) {
            throw new IllegalStateException("Render pass must be set before building pipeline");
        }
        
        if (vertexShader == null) {
            throw new IllegalStateException("Vertex shader must be set before building pipeline");
        }
        
        if (fragmentShader == null) {
            throw new IllegalStateException("Fragment shader must be set before building pipeline");
        }
        
        if (vertexInputState == null) {
            throw new IllegalStateException("Vertex input state must be set before building pipeline");
        }
        
        if (inputAssemblyState == null) {
            throw new IllegalStateException("Input assembly state must be set before building pipeline");
        }
        
        if (viewportState == null) {
            throw new IllegalStateException("Viewport state must be set before building pipeline");
        }
        
        if (rasterizationState == null) {
            throw new IllegalStateException("Rasterization state must be set before building pipeline");
        }
        
        if (multisampleState == null) {
            throw new IllegalStateException("Multisample state must be set before building pipeline");
        }
        
        if (colorBlendState == null) {
            throw new IllegalStateException("Color blend state must be set before building pipeline");
        }
    }
}