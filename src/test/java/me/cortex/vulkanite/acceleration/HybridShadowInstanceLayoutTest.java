package me.cortex.vulkanite.acceleration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HybridShadowInstanceLayoutTest {
    @Test
    void preservesMixedInstanceOrderAndCountsProceduralTerrain() {
        HybridShadowInstanceLayout layout = new HybridShadowInstanceLayout();
        HybridShadowInstanceLayout.Instance terrain = instance(
                HybridShadowInstanceLayout.Role.TERRAIN_SPECIAL_TRIANGLES,
                HybridSbtLayout.GeometryType.TRIANGLES,
                HybridSbtLayout.SHADOW_TRIANGLE_TERRAIN_HIT_GROUP,
                HybridSbtLayout.TERRAIN_TRIANGLE_INSTANCE_MASK,
                11L);
        HybridShadowInstanceLayout.Instance entity = instance(
                HybridShadowInstanceLayout.Role.ENTITY_TRIANGLES,
                HybridSbtLayout.GeometryType.TRIANGLES,
                HybridSbtLayout.SHADOW_ENTITY_TRIANGLE_HIT_GROUP,
                HybridSbtLayout.ENTITY_TRIANGLE_INSTANCE_MASK,
                22L);
        HybridShadowInstanceLayout.Instance procedural = instance(
                HybridShadowInstanceLayout.Role.TERRAIN_PROCEDURAL,
                HybridSbtLayout.GeometryType.AABBS,
                HybridSbtLayout.SHADOW_PROCEDURAL_TERRAIN_HIT_GROUP,
                HybridSbtLayout.PROCEDURAL_INSTANCE_MASK,
                33L);

        layout.add(terrain);
        layout.add(entity);
        layout.add(procedural);

        assertEquals(3, layout.instances().size());
        assertEquals(terrain, layout.instances().get(0));
        assertEquals(entity, layout.instances().get(1));
        assertEquals(procedural, layout.instances().get(2));
        assertEquals(1, layout.proceduralCount());
        assertThrows(UnsupportedOperationException.class,
                () -> layout.instances().add(terrain));
    }

    @Test
    void rejectsRoleRecordGeometryAndAddressMismatches() {
        assertThrows(IllegalArgumentException.class, () -> instance(
                HybridShadowInstanceLayout.Role.TERRAIN_SPECIAL_TRIANGLES,
                HybridSbtLayout.GeometryType.TRIANGLES,
                HybridSbtLayout.SHADOW_ENTITY_TRIANGLE_HIT_GROUP,
                HybridSbtLayout.TERRAIN_TRIANGLE_INSTANCE_MASK,
                1L));
        assertThrows(IllegalArgumentException.class, () -> instance(
                HybridShadowInstanceLayout.Role.ENTITY_TRIANGLES,
                HybridSbtLayout.GeometryType.TRIANGLES,
                HybridSbtLayout.SHADOW_TRIANGLE_TERRAIN_HIT_GROUP,
                HybridSbtLayout.ENTITY_TRIANGLE_INSTANCE_MASK,
                1L));
        assertThrows(IllegalArgumentException.class, () -> instance(
                HybridShadowInstanceLayout.Role.TERRAIN_PROCEDURAL,
                HybridSbtLayout.GeometryType.AABBS,
                HybridSbtLayout.PROCEDURAL_TERRAIN_HIT_GROUP,
                HybridSbtLayout.PROCEDURAL_INSTANCE_MASK,
                1L));
        assertThrows(IllegalArgumentException.class, () -> instance(
                HybridShadowInstanceLayout.Role.TERRAIN_PROCEDURAL,
                HybridSbtLayout.GeometryType.TRIANGLES,
                HybridSbtLayout.SHADOW_PROCEDURAL_TERRAIN_HIT_GROUP,
                HybridSbtLayout.PROCEDURAL_INSTANCE_MASK,
                1L));
        assertThrows(IllegalArgumentException.class, () -> instance(
                HybridShadowInstanceLayout.Role.TERRAIN_PROCEDURAL,
                HybridSbtLayout.GeometryType.AABBS,
                HybridSbtLayout.SHADOW_PROCEDURAL_TERRAIN_HIT_GROUP,
                HybridSbtLayout.TERRAIN_TRIANGLE_INSTANCE_MASK,
                1L));
        assertThrows(IllegalArgumentException.class, () -> instance(
                HybridShadowInstanceLayout.Role.TERRAIN_PROCEDURAL,
                HybridSbtLayout.GeometryType.AABBS,
                HybridSbtLayout.SHADOW_PROCEDURAL_TERRAIN_HIT_GROUP,
                HybridSbtLayout.PROCEDURAL_INSTANCE_MASK,
                0L));
        assertThrows(NullPointerException.class, () -> instance(
                null,
                HybridSbtLayout.GeometryType.AABBS,
                HybridSbtLayout.SHADOW_PROCEDURAL_TERRAIN_HIT_GROUP,
                HybridSbtLayout.PROCEDURAL_INSTANCE_MASK,
                1L));
    }

    private static HybridShadowInstanceLayout.Instance instance(
            HybridShadowInstanceLayout.Role role,
            HybridSbtLayout.GeometryType geometryType,
            int hitGroup,
            int mask,
            long address) {
        return new HybridShadowInstanceLayout.Instance(
                role, geometryType, hitGroup, mask, address);
    }
}
