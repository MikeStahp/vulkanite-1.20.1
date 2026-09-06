package me.cortex.vulkanite.client.rendering;

import me.cortex.vulkanite.lib.shader.reflection.ShaderReflection;


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

    // One common binding ABI. ShaderReflection.Set.validate accepts any reflected
    // subset, so full path tracers and G-buffer passes share this contract.
    private static final ShaderReflection.Set COMMON_SET_EXPECTED = new ShaderReflection.Set(
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
                    new ShaderReflection.Binding("", 32, VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR, 0, false),
                    new ShaderReflection.Binding("", 33, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 0, false),
                    new ShaderReflection.Binding("", 34, VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR, 0, false),
            });

    private PipelineDescriptorSets() {
    } // Prevent instantiation

    /** Returns the common ABI; shaders may omit bindings they do not consume. */
    public static ShaderReflection.Set createCommonSetExpected() {
        return COMMON_SET_EXPECTED;
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
        return new ShaderReflection.Set(bindings);
    }

	/**
	 * Creates the expected SSBO descriptor set layout.
	 */
	public static ShaderReflection.Set createSsboSetExpected(int[] ssboIds) {
		ShaderReflection.Binding[] bindings = new ShaderReflection.Binding[ssboIds.length];
		for (int i = 0; i < ssboIds.length; i++) {
			bindings[i] = new ShaderReflection.Binding("", ssboIds[i], VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 0, false);
		}
		return new ShaderReflection.Set(bindings);
	}

}
