package me.cortex.vulkanite.client.rendering;

import me.cortex.vulkanite.lib.shader.reflection.ShaderReflection;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR;
import static org.lwjgl.vulkan.VK10.*;

class PipelineDescriptorSetsTest {
    @Test
    void commonAbiAcceptsTriangleAndGbufferPassesWithOptionalHybridResources() {
        var expected = PipelineDescriptorSets.createCommonSetExpected();
        var triangle = new ShaderReflection.Set(
                binding(0, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER),
                binding(1, VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR),
                binding(3, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER),
                binding(6, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE),
                binding(12, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE));
        assertTrue(triangle.validate(expected));
        var hybrid = new ShaderReflection.Set(
                binding(2, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER),
                binding(7, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER),
                binding(15, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE),
                binding(24, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER),
                binding(32, VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR),
                binding(33, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER),
                binding(34, VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR));
        assertTrue(hybrid.validate(expected));
    }

    @Test
    void commonAbiRejectsUnknownBindingsWrongTypesAndArrayShapes() {
        var expected = PipelineDescriptorSets.createCommonSetExpected();
        assertFalse(new ShaderReflection.Set(binding(35, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)).validate(expected));
        assertFalse(new ShaderReflection.Set(binding(32, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)).validate(expected));
        assertFalse(new ShaderReflection.Set(
                new ShaderReflection.Binding("", 6, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 16, false)).validate(expected));
        assertFalse(new ShaderReflection.Set(
                new ShaderReflection.Binding("", 6, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 1, true)).validate(expected));
    }

    @Test
    void geometryAndEntityTextureArraysRemainDistinctFromTheCommonSet() {
        var common = PipelineDescriptorSets.createCommonSetExpected();
        var geometry = PipelineDescriptorSets.createGeomSetExpected();
        var entities = PipelineDescriptorSets.createEntityTextureSetExpected();
        assertFalse(geometry.validate(common));
        assertFalse(entities.validate(common));
        assertTrue(geometry.getBindingAt(0).runtimeSized());
        assertFalse(entities.getBindingAt(0).runtimeSized());
        assertEquals(EntityCapture.MAX_TEXTURES, entities.getBindingAt(0).arraySize());
    }

    @Test
    void customTexturesAndSparseSsboIdsRetainTheirOwnBindingContracts() {
        var textures = PipelineDescriptorSets.createCustomTexSetExpected(2);
        assertEquals(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, textures.getBindingAt(1).descriptorType());
        var ssbos = PipelineDescriptorSets.createSsboSetExpected(new int[] {9, 2});
        assertEquals(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, ssbos.getBindingAt(9).descriptorType());
        assertNull(ssbos.getBindingAt(0));
        assertEquals(2, ssbos.bindings().getFirst().binding());
    }

    private static ShaderReflection.Binding binding(int index, int type) {
        return new ShaderReflection.Binding("", index, type, 0, false);
    }
}
