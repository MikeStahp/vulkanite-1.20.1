package me.cortex.vulkanite.acceleration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HybridSbtLayoutTest {
    @Test
    void optionalReflectionGroupIsExcludedUnlessRequested() {
        assertEquals(HybridSbtLayout.REQUIRED_SHADOW_HIT_GROUP_COUNT,
                HybridSbtLayout.requiredHitGroupCount(false));
        assertEquals(HybridSbtLayout.REQUIRED_HIT_GROUP_COUNT,
                HybridSbtLayout.requiredHitGroupCount(true));
    }

    @Test
    void fixesProceduralTerrainAtThirdHitRecord() {
        assertEquals(0, HybridSbtLayout.TRIANGLE_TERRAIN_HIT_GROUP);
        assertEquals(1, HybridSbtLayout.ENTITY_TRIANGLE_HIT_GROUP);
        assertEquals(2, HybridSbtLayout.PROCEDURAL_TERRAIN_HIT_GROUP);
        assertEquals(3, HybridSbtLayout.SHADOW_TRIANGLE_TERRAIN_HIT_GROUP);
        assertEquals(4, HybridSbtLayout.SHADOW_ENTITY_TRIANGLE_HIT_GROUP);
        assertEquals(5, HybridSbtLayout.SHADOW_PROCEDURAL_TERRAIN_HIT_GROUP);
        assertEquals(6, HybridSbtLayout.REFLECTION_PROCEDURAL_TERRAIN_HIT_GROUP);
        assertEquals(6, HybridSbtLayout.REQUIRED_SHADOW_HIT_GROUP_COUNT);
        assertEquals(7, HybridSbtLayout.REQUIRED_HIT_GROUP_COUNT);
        assertEquals(0, HybridSbtLayout.RAY_SBT_RECORD_OFFSET);
        assertEquals(0, HybridSbtLayout.RAY_SBT_RECORD_STRIDE);
        assertEquals(1, HybridSbtLayout.PRODUCTION_SHADOW_MISS_INDEX);
        assertEquals(2, HybridSbtLayout.PROCEDURAL_DEBUG_MISS_INDEX);
        assertEquals(3, HybridSbtLayout.PROCEDURAL_SHADOW_REFERENCE_SBT_OFFSET);
        assertEquals(4, HybridSbtLayout.PROCEDURAL_REFLECTION_REFERENCE_SBT_OFFSET);
        assertEquals(HybridSbtLayout.SHADOW_PROCEDURAL_TERRAIN_HIT_GROUP,
                HybridSbtLayout.proceduralShadowReferenceHitGroup());
        assertEquals(0x01, HybridSbtLayout.TERRAIN_TRIANGLE_INSTANCE_MASK);
        assertEquals(0x02, HybridSbtLayout.PROCEDURAL_INSTANCE_MASK);
        assertEquals(0x04, HybridSbtLayout.ENTITY_TRIANGLE_INSTANCE_MASK);
        assertEquals(0x07, HybridSbtLayout.SHADOW_RAY_MASK);
        assertDoesNotThrow(() -> HybridSbtLayout.assertMissRecordCount(3));
        assertDoesNotThrow(() -> HybridSbtLayout.assertHitGroupCompatibility(
                new boolean[] { false, false, true, false, false, true, true }));
        assertDoesNotThrow(() -> HybridSbtLayout.assertHitGroupCompatibility(
                new boolean[] { false, false, true, false, false, true }));
        assertDoesNotThrow(() -> HybridSbtLayout.assertHitGroupCompatibility(
                new boolean[] { false, false, true, false, false, true, false }));
    }

    @Test
    void rejectsGeometryTypeMismatchAtFixedRecords() {
        assertThrows(IllegalStateException.class,
                () -> HybridSbtLayout.assertHitGroupCompatibility(
                        new boolean[] { false, true, true, false, false, true, true }));
        assertThrows(IllegalStateException.class,
                () -> HybridSbtLayout.assertHitGroupCompatibility(
                        new boolean[] { false, false, false, false, false, true, true }));
        assertThrows(IllegalStateException.class,
                () -> HybridSbtLayout.assertHitGroupCompatibility(new boolean[] { false, false }));
        assertThrows(IllegalStateException.class,
                () -> HybridSbtLayout.assertMissRecordCount(2));
        assertDoesNotThrow(() -> HybridSbtLayout.assertInstanceCompatibility(
                HybridSbtLayout.GeometryType.AABBS, 2, 1));
        assertDoesNotThrow(() -> HybridSbtLayout.assertInstanceCompatibility(
                HybridSbtLayout.GeometryType.TRIANGLES, 1, 1));
        assertDoesNotThrow(() -> HybridSbtLayout.assertInstanceCompatibility(
                HybridSbtLayout.GeometryType.AABBS, 5,
                HybridSbtLayout.PROCEDURAL_INSTANCE_MASK));
        assertDoesNotThrow(() -> HybridSbtLayout.assertInstanceCompatibility(
                HybridSbtLayout.GeometryType.AABBS, 6,
                HybridSbtLayout.PROCEDURAL_INSTANCE_MASK));
        assertThrows(IllegalArgumentException.class,
                () -> HybridSbtLayout.assertInstanceCompatibility(
                        HybridSbtLayout.GeometryType.AABBS, 5, 0));
        assertThrows(IllegalArgumentException.class,
                () -> HybridSbtLayout.assertInstanceCompatibility(
                        HybridSbtLayout.GeometryType.TRIANGLES, 3,
                        HybridSbtLayout.ENTITY_TRIANGLE_INSTANCE_MASK));
        assertThrows(IllegalArgumentException.class,
                () -> HybridSbtLayout.assertInstanceCompatibility(
                        HybridSbtLayout.GeometryType.TRIANGLES, 4,
                        HybridSbtLayout.TERRAIN_TRIANGLE_INSTANCE_MASK));
        assertThrows(IllegalArgumentException.class,
                () -> HybridSbtLayout.assertInstanceCompatibility(
                        HybridSbtLayout.GeometryType.AABBS, 0, 1));
        assertThrows(IllegalArgumentException.class,
                () -> HybridSbtLayout.assertInstanceCompatibility(
                        HybridSbtLayout.GeometryType.TRIANGLES, 2, 1));
        assertThrows(IllegalArgumentException.class,
                () -> HybridSbtLayout.assertInstanceCompatibility(
                        HybridSbtLayout.GeometryType.TRIANGLES, 5, 1));
    }
}
