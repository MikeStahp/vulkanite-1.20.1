package me.cortex.vulkanite.lib.pipeline;

import me.cortex.vulkanite.lib.base.VContext;
import me.cortex.vulkanite.lib.base.VRef;
import me.cortex.vulkanite.lib.descriptors.DescriptorSetLayoutBuilder;
import me.cortex.vulkanite.lib.descriptors.VDescriptorSetLayout;
import me.cortex.vulkanite.lib.shader.ShaderModule;
import me.cortex.vulkanite.lib.shader.reflection.ResourceType;
import me.cortex.vulkanite.lib.shader.reflection.ShaderReflection;

import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.vulkan.KHRAccelerationStructure.*;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.*;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Utility class for common pipeline operations.
 */
public class PipelineUtils {
    
    /**
     * Creates a descriptor set layout based on shader reflection information.
     *
     * @param context    The Vulkan context
     * @param reflection The shader reflection information
     * @param setIndex   The descriptor set index
     * @return A reference to the created descriptor set layout
     */
    public static VRef<VDescriptorSetLayout> createDescriptorSetLayout(VContext context, ShaderReflection reflection, int setIndex) {
        DescriptorSetLayoutBuilder builder = new DescriptorSetLayoutBuilder();
        
        // Process each resource type
        if (setIndex < reflection.getNSets()) {
            var bindings = reflection.getBindings(setIndex);
            for (var binding : bindings) {
                builder.binding(binding.binding(), binding.descriptorType(), VK_SHADER_STAGE_ALL);
            }
        }
        
        return builder.build(context);
    }
    
    /**
     * Validates that all required shader stages are present.
     *
     * @param shaders Array of shader modules to validate
     * @throws IllegalArgumentException if validation fails
     */
    public static void validateShaders(ShaderModule... shaders) throws IllegalArgumentException {
        for (ShaderModule shader : shaders) {
            if (shader == null) {
                throw new IllegalArgumentException("Shader module cannot be null");
            }
        }
    }
    
    /**
     * Gets a human-readable name for a shader stage.
     *
     * @param stage The shader stage flag
     * @return Human-readable name
     */
    public static String getShaderStageName(int stage) {
        switch (stage) {
            case VK_SHADER_STAGE_VERTEX_BIT:
                return "Vertex";
            case VK_SHADER_STAGE_TESSELLATION_CONTROL_BIT:
                return "Tessellation Control";
            case VK_SHADER_STAGE_TESSELLATION_EVALUATION_BIT:
                return "Tessellation Evaluation";
            case VK_SHADER_STAGE_GEOMETRY_BIT:
                return "Geometry";
            case VK_SHADER_STAGE_FRAGMENT_BIT:
                return "Fragment";
            case VK_SHADER_STAGE_COMPUTE_BIT:
                return "Compute";
            case VK_SHADER_STAGE_RAYGEN_BIT_KHR:
                return "Ray Generation";
            case VK_SHADER_STAGE_ANY_HIT_BIT_KHR:
                return "Any Hit";
            case VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR:
                return "Closest Hit";
            case VK_SHADER_STAGE_MISS_BIT_KHR:
                return "Miss";
            case VK_SHADER_STAGE_INTERSECTION_BIT_KHR:
                return "Intersection";
            case VK_SHADER_STAGE_CALLABLE_BIT_KHR:
                return "Callable";
            default:
                return "Unknown";
        }
    }
}