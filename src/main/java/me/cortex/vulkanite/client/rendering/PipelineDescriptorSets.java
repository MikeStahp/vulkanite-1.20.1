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
    // Matches shader Set 1 binding: Quads SSBO array (bindless)
    private static final ShaderReflection.Set GEOM_SET_EXPECTED = new ShaderReflection.Set(
            new ShaderReflection.Binding[] {
                    new ShaderReflection.Binding("", 0, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 1, true)
            });

    private static final ShaderReflection.Set ENTITY_TEXTURE_SET_EXPECTED = new ShaderReflection.Set(
            new ShaderReflection.Binding[] {
                    new ShaderReflection.Binding("", 0, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
                            EntityCapture.MAX_TEXTURES, false)
            });

    // Cache for single image common set (never changes)
    // Includes G-buffer bindings for hybrid rendering
    private static final ShaderReflection.Set COMMON_SET_EXPECTED_SINGLE = new ShaderReflection.Set(
            new ShaderReflection.Binding[] {
                    new ShaderReflection.Binding("", 0, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, 0, false),
                    new ShaderReflection.Binding("", 1, VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR, 0, false),
                    // Binding 2 removed/reserved
                    new ShaderReflection.Binding("", 3, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 0, false),
                    new ShaderReflection.Binding("", 4, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 0, false),
                    new ShaderReflection.Binding("", 5, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 0, false),
                    new ShaderReflection.Binding("", 6, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 0, false),
                    // G-buffer bindings for hybrid rendering
                    new ShaderReflection.Binding("", 7, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 0, false),
                    new ShaderReflection.Binding("", 8, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 0, false),
                    new ShaderReflection.Binding("", 9, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 0, false),
                    new ShaderReflection.Binding("", 10, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 0, false),
                    new ShaderReflection.Binding("", 11, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 0, false),
                    // Binding 12: Final output target
                    new ShaderReflection.Binding("", 12, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 0, false),
                    // Binding 13: Motion vectors for DLSS Ray Reconstruction
                    new ShaderReflection.Binding("", 13, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 0, false),
                    // Binding 14: Linear Depth for DLSS Ray Reconstruction
                    new ShaderReflection.Binding("", 14, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 0, false),
                    // Binding 15: Previous frame reservoir for ReSTIR ping-pong
                    new ShaderReflection.Binding("", 15, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 0, false),
                    new ShaderReflection.Binding("", 16, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 0, false),
                    new ShaderReflection.Binding("", 17, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 0, false),
                    new ShaderReflection.Binding("", 18, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 0, false),
                    new ShaderReflection.Binding("", 19, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 0, false),
                    new ShaderReflection.Binding("", 20, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 0, false),
                    new ShaderReflection.Binding("", 21, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 0, false),
                    new ShaderReflection.Binding("", 22, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 0, false),
                    new ShaderReflection.Binding("", 23, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 0, false),
                    new ShaderReflection.Binding("", 24, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 0, false),
                    new ShaderReflection.Binding("", 25, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 0, false),
                    new ShaderReflection.Binding("", 26, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 0, false),
                    new ShaderReflection.Binding("", 27, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 0, false),
                    new ShaderReflection.Binding("", 28, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 0, false),
                    new ShaderReflection.Binding("", 29, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 0, false),
                    new ShaderReflection.Binding("", 30, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 0, false),
                    new ShaderReflection.Binding("", 31, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 0, false),
            });

    // Cache for base common set without G-buffer bindings (for full path tracers)
    // Matches shader Set 0 bindings: base bindings only (0,1,3,4,5,6,12 - WITHOUT
    // G-buffer 7-11)
    private static final ShaderReflection.Set COMMON_SET_EXPECTED_BASE = new ShaderReflection.Set(
            new ShaderReflection.Binding[] {
                    new ShaderReflection.Binding("", 0, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, 0, false),
                    new ShaderReflection.Binding("", 1, VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR, 0, false),
                    // Binding 2 removed/reserved
                    new ShaderReflection.Binding("", 3, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 0, false),
                    new ShaderReflection.Binding("", 4, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 0, false),
                    new ShaderReflection.Binding("", 5, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 0, false),
                    new ShaderReflection.Binding("", 6, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 0, false),
                    // No G-buffer bindings (7-11) for full path tracers
                    // Binding 12: Final output target
                    new ShaderReflection.Binding("", 12, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 0, false),
                    new ShaderReflection.Binding("", 24, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 0, false),
                    new ShaderReflection.Binding("", 25, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 0, false),
                    new ShaderReflection.Binding("", 26, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 0, false),
                    new ShaderReflection.Binding("", 27, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 0, false),
                    new ShaderReflection.Binding("", 28, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 0, false),
                    new ShaderReflection.Binding("", 29, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 0, false),
                    new ShaderReflection.Binding("", 30, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 0, false),
                    new ShaderReflection.Binding("", 31, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 0, false),
            });

    // Cache for VulkaniteRT hybrid set - matches actual bindings used by PBR
    // shaders
    // Includes VulkaniteRT's section, diffuse, and specular cache buffers at 24-29.
    private static final ShaderReflection.Set COMMON_SET_EXPECTED_VULKANITE_RT = new ShaderReflection.Set(
            new ShaderReflection.Binding[] {
                    new ShaderReflection.Binding("", 0, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, 0, false),
                    new ShaderReflection.Binding("", 1, VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR, 0, false),
                    new ShaderReflection.Binding("", 2, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, 0, false),
                    new ShaderReflection.Binding("", 3, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 0, false),
                    new ShaderReflection.Binding("", 4, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 0, false),
                    new ShaderReflection.Binding("", 5, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 0, false),
                    new ShaderReflection.Binding("", 6, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 0, false),
                    new ShaderReflection.Binding("", 7, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 0, false),
                    new ShaderReflection.Binding("", 8, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 0, false),
                    new ShaderReflection.Binding("", 9, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 0, false),
                    new ShaderReflection.Binding("", 10, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 0, false),
                    new ShaderReflection.Binding("", 11, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 0, false),
                    // Binding 12: Final output target
                    new ShaderReflection.Binding("", 12, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 0, false),
                    // Binding 13: Motion vectors for DLSS Ray Reconstruction
                    new ShaderReflection.Binding("", 13, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 0, false),
                    // Binding 14: Linear Depth for DLSS Ray Reconstruction
                    new ShaderReflection.Binding("", 14, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 0, false),
                    // Binding 15: Previous frame reservoir for ReSTIR ping-pong
                    new ShaderReflection.Binding("", 15, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 0, false),
                    new ShaderReflection.Binding("", 16, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 0, false),
                    new ShaderReflection.Binding("", 17, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 0, false),
                    new ShaderReflection.Binding("", 18, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 0, false),
                    new ShaderReflection.Binding("", 19, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 0, false),
                    new ShaderReflection.Binding("", 20, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 0, false),
                    new ShaderReflection.Binding("", 21, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 0, false),
                    new ShaderReflection.Binding("", 22, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 0, false),
                    new ShaderReflection.Binding("", 23, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 0, false),
                    new ShaderReflection.Binding("", 24, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 0, false),
                    new ShaderReflection.Binding("", 25, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 0, false),
                    new ShaderReflection.Binding("", 26, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 0, false),
                    new ShaderReflection.Binding("", 27, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 0, false),
                    new ShaderReflection.Binding("", 28, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 0, false),
                    new ShaderReflection.Binding("", 29, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 0, false),
                    new ShaderReflection.Binding("", 30, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 0, false),
                    new ShaderReflection.Binding("", 31, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 0, false),
            });

    private PipelineDescriptorSets() {
    } // Prevent instantiation

    /**
     * Creates the expected common descriptor set layout with array support.
     * Includes G-buffer bindings for hybrid rendering:
     * - Binding 7: colortex1 (Albedo)
     * - Binding 8: colortex2 (Material Properties)
     * - Binding 9: colortex3 (Normal)
     * - Binding 10: colortex4 (World Position)
     * - Binding 11: colortex5 (Additional Properties)
     */
    public static ShaderReflection.Set createCommonSetExpected(int maxIrisRenderTargets) {
        return new ShaderReflection.Set(new ShaderReflection.Binding[] {
                new ShaderReflection.Binding("", 0, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, 0, false),
                new ShaderReflection.Binding("", 1, VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR, 0, false),
                // Binding 2 removed/reserved
                new ShaderReflection.Binding("", 3, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 0, false),
                new ShaderReflection.Binding("", 4, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 0, false),
                new ShaderReflection.Binding("", 5, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 0, false),
                new ShaderReflection.Binding("", 6, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 0, false),
                // G-buffer bindings for hybrid rendering
                new ShaderReflection.Binding("", 7, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 0, false),
                new ShaderReflection.Binding("", 8, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 0, false),
                new ShaderReflection.Binding("", 9, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 0, false),
                new ShaderReflection.Binding("", 10, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 0, false),
                new ShaderReflection.Binding("", 11, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 0, false),
                // Binding 12: Final output target
                new ShaderReflection.Binding("", 12, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 0, false),
                // Binding 13: Motion vectors for DLSS Ray Reconstruction
                new ShaderReflection.Binding("", 13, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 0, false),
                // Binding 14: Linear Depth for DLSS Ray Reconstruction
                new ShaderReflection.Binding("", 14, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 0, false),
                // Binding 15: Previous frame reservoir for ReSTIR ping-pong
                new ShaderReflection.Binding("", 15, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 0, false),
                new ShaderReflection.Binding("", 16, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 0, false),
                new ShaderReflection.Binding("", 17, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 0, false),
                new ShaderReflection.Binding("", 18, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 0, false),
                new ShaderReflection.Binding("", 19, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 0, false),
                new ShaderReflection.Binding("", 20, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 0, false),
                new ShaderReflection.Binding("", 21, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 0, false),
                new ShaderReflection.Binding("", 22, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 0, false),
                new ShaderReflection.Binding("", 23, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 0, false),
                new ShaderReflection.Binding("", 24, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 0, false),
                new ShaderReflection.Binding("", 25, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 0, false),
                new ShaderReflection.Binding("", 26, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 0, false),
                new ShaderReflection.Binding("", 27, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 0, false),
                new ShaderReflection.Binding("", 28, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 0, false),
                new ShaderReflection.Binding("", 29, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 0, false),
                new ShaderReflection.Binding("", 30, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 0, false),
                new ShaderReflection.Binding("", 31, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 0, false),
        });
    }

    /**
     * Returns the cached common descriptor set layout for single image binding.
     */
    public static ShaderReflection.Set createCommonSetExpectedSingle() {
        return COMMON_SET_EXPECTED_SINGLE;
    }

    /**
     * Returns the cached base common descriptor set layout without G-buffer
     * bindings.
     * This is for full path tracers that don't use the hybrid G-buffer approach.
     * Contains bindings: 0,1,3,4,5,6,12 (WITHOUT G-buffer bindings 7-11)
     */
    public static ShaderReflection.Set createCommonSetExpectedBase() {
        return COMMON_SET_EXPECTED_BASE;
    }

    /**
     * Returns the cached VulkaniteRT hybrid descriptor set layout.
     * This matches the actual bindings used by VulkaniteRT (bindings that are
     * sampled).
     * Includes the complete reflected VulkaniteRT set, including cache bindings 24
     * and 25.
     */
    public static ShaderReflection.Set createCommonSetExpectedVulkaniteRT() {
        return COMMON_SET_EXPECTED_VULKANITE_RT;
    }

    /**
     * Returns the cached geometry descriptor set layout.
     */
    public static ShaderReflection.Set createGeomSetExpected() {
        return GEOM_SET_EXPECTED;
    }

    public static ShaderReflection.Set createEntityTextureSetExpected() {
        return ENTITY_TEXTURE_SET_EXPECTED;
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
