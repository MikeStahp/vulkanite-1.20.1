package me.cortex.vulkanite.client.rendering;

import me.cortex.vulkanite.client.config.DLSSConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VulkanPipelineDebugModeTest {
    @Test
    void doubleTraceReferenceHasDistinctModeAndRequiresProceduralTlas() {
        DLSSConfig.DebugType reference = DLSSConfig.DebugType.SHADOW_DOUBLE_TRACE_REFERENCE;

        assertEquals(10, VulkanPipeline.mapDebugMode(reference));
        assertTrue(VulkanPipeline.isProceduralDebug(reference));
        assertFalse(VulkanPipeline.isShadowComparison(reference));
    }

    @Test
    void comparisonModesKeepTheirExistingMappingsAndReadbackClassification() {
        assertComparisonMode(DLSSConfig.DebugType.SHADOW_COMPARISON, 7);
        assertComparisonMode(DLSSConfig.DebugType.SHADOW_DANGEROUS_MISSES, 8);
        assertComparisonMode(DLSSConfig.DebugType.SHADOW_EXTRA_HITS, 9);
    }

    @Test
    void proceduralReflectionComparisonHasDistinctModeAndRequirements() {
        DLSSConfig.DebugType comparison = DLSSConfig.DebugType.PROCEDURAL_REFLECTION_COMPARISON;

        assertEquals(11, VulkanPipeline.mapDebugMode(comparison));
        assertTrue(VulkanPipeline.isProceduralDebug(comparison));
        assertTrue(VulkanPipeline.isProceduralReflectionComparison(comparison));
        assertFalse(VulkanPipeline.isShadowComparison(comparison));
        assertFalse(VulkanPipeline.requiresProceduralShadowHitGroup(comparison));
    }

    private static void assertComparisonMode(DLSSConfig.DebugType debugType, int mode) {
        assertEquals(mode, VulkanPipeline.mapDebugMode(debugType));
        assertTrue(VulkanPipeline.isProceduralDebug(debugType));
        assertTrue(VulkanPipeline.isShadowComparison(debugType));
    }
}
