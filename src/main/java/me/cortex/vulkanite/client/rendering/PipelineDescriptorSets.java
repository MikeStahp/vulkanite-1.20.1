package me.cortex.vulkanite.client.rendering;

import me.cortex.vulkanite.lib.shader.reflection.ShaderReflection;

import java.util.ArrayList;
import java.util.Arrays;

import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Factory for creating expected descriptor set layouts for pipeline validation.
 */
public final class PipelineDescriptorSets {

    // Cache for geometry set (never changes)
    private static final ShaderReflection.Set GEOM_SET_EXPECTED = new ShaderReflection.Set(
            new ShaderReflection.Binding[] {
                    new ShaderReflection.Binding("", 0, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 1, true)
            });

    // Cache for single image common set (never changes)
    private static final ShaderReflection.Set COMMON_SET_EXPECTED_SINGLE = new ShaderReflection.Set(
            new ShaderReflection.Binding[] {
                    new ShaderReflection.Binding("", 0, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, 0, false),
                    new ShaderReflection.Binding("", 1, VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR, 0, false),
                    new ShaderReflection.Binding("", 3, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 0, false),
                    new ShaderReflection.Binding("", 4, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 0, false),
                    new ShaderReflection.Binding("", 5, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 0, false),
                    new ShaderReflection.Binding("", 6, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 0, false),
            });

    private PipelineDescriptorSets() {
    } // Prevent instantiation

    /**
     * Creates the expected common descriptor set layout with array support.
     */
    public static ShaderReflection.Set createCommonSetExpected(int maxIrisRenderTargets) {
        return new ShaderReflection.Set(new ShaderReflection.Binding[] {
                new ShaderReflection.Binding("", 0, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, 0, false),
                new ShaderReflection.Binding("", 1, VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR, 0, false),
                new ShaderReflection.Binding("", 3, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 0, false),
                new ShaderReflection.Binding("", 4, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 0, false),
                new ShaderReflection.Binding("", 5, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 0, false),
                new ShaderReflection.Binding("", 6, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, maxIrisRenderTargets, false),
        });
    }

    /**
     * Returns the cached common descriptor set layout for single image binding.
     */
    public static ShaderReflection.Set createCommonSetExpectedSingle() {
        return COMMON_SET_EXPECTED_SINGLE;
    }

    /**
     * Returns the cached geometry descriptor set layout.
     */
    public static ShaderReflection.Set createGeomSetExpected() {
        return GEOM_SET_EXPECTED;
    }

    /**
     * Creates the expected custom texture descriptor set layout.
     */
    public static ShaderReflection.Set createCustomTexSetExpected(int customTextureCount) {
        ShaderReflection.Binding[] bindings = new ShaderReflection.Binding[customTextureCount];
        for (int i = 0; i < customTextureCount; i++) {
            bindings[i] = new ShaderReflection.Binding("", i, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 0, false);
        }
        return new ShaderReflection.Set(new ArrayList<>(Arrays.asList(bindings)));
    }

    /**
     * Creates the expected SSBO descriptor set layout.
     */
    public static ShaderReflection.Set createSsboSetExpected(int[] ssboIds) {
        ShaderReflection.Binding[] bindings = new ShaderReflection.Binding[ssboIds.length];
        for (int i = 0; i < ssboIds.length; i++) {
            bindings[i] = new ShaderReflection.Binding("", ssboIds[i], VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 0, false);
        }
        return new ShaderReflection.Set(new ArrayList<>(Arrays.asList(bindings)));
    }
}
