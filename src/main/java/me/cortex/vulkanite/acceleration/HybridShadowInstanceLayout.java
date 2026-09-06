package me.cortex.vulkanite.acceleration;

import java.util.ArrayList;
import java.util.List;

/** CPU-side Phase 7 contract for ordering and validating a mixed shadow TLAS. */
public final class HybridShadowInstanceLayout {
    public enum Role { TERRAIN_SPECIAL_TRIANGLES, TERRAIN_PROCEDURAL, ENTITY_TRIANGLES }

    public record Instance(Role role, HybridSbtLayout.GeometryType geometryType,
            int hitGroup, int mask, long accelerationStructureAddress) {
        public Instance {
            if (role == null || geometryType == null) throw new NullPointerException();
            if (accelerationStructureAddress == 0L) {
                throw new IllegalArgumentException("TLAS instance needs a BLAS device address");
            }
            HybridSbtLayout.assertInstanceCompatibility(geometryType, hitGroup, mask);
            if (role == Role.ENTITY_TRIANGLES
                    && hitGroup != HybridSbtLayout.SHADOW_ENTITY_TRIANGLE_HIT_GROUP) {
                throw new IllegalArgumentException("Entity instance lost its entity SBT record");
            }
            if (role == Role.TERRAIN_SPECIAL_TRIANGLES
                    && hitGroup != HybridSbtLayout.SHADOW_TRIANGLE_TERRAIN_HIT_GROUP) {
                throw new IllegalArgumentException("Terrain triangle selected a non-shadow SBT record");
            }
            if (role == Role.TERRAIN_PROCEDURAL
                    && hitGroup != HybridSbtLayout.SHADOW_PROCEDURAL_TERRAIN_HIT_GROUP) {
                throw new IllegalArgumentException("Procedural terrain selected a non-shadow SBT record");
            }
        }
    }

    private final List<Instance> instances = new ArrayList<>();

    public void add(Instance instance) { instances.add(instance); }
    public List<Instance> instances() { return List.copyOf(instances); }
    public int proceduralCount() {
        return (int) instances.stream().filter(i -> i.role() == Role.TERRAIN_PROCEDURAL).count();
    }
}
