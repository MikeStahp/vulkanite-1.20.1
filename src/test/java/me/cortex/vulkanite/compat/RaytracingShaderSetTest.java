package me.cortex.vulkanite.compat;

import me.cortex.vulkanite.acceleration.HybridSbtLayout;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RaytracingShaderSetTest {
    @Test
    void reflectionRecordRequiresBothClosestHitAndIntersectionStages() {
        assertTrue(RaytracingShaderSet.hasProceduralReflectionHitGroup(source("close", "intersection")));
        assertFalse(RaytracingShaderSet.hasProceduralReflectionHitGroup(source(null, "intersection")));
        assertFalse(RaytracingShaderSet.hasProceduralReflectionHitGroup(source("close", null)));
        assertFalse(RaytracingShaderSet.hasProceduralReflectionHitGroup(source(null, null)));
    }

    @Test
    void missingReflectionRecordRetainsTriangleFallback() {
        RaytracingShaderSource.RayHitSource[] hitGroups = new RaytracingShaderSource.RayHitSource[
                HybridSbtLayout.REFLECTION_PROCEDURAL_TERRAIN_HIT_GROUP];
        assertFalse(RaytracingShaderSet.hasProceduralReflectionHitGroup(
                new RaytracingShaderSource("test", "raygen", new String[0], hitGroups)));
    }

    private static RaytracingShaderSource source(String closestHit, String intersection) {
        RaytracingShaderSource.RayHitSource[] hitGroups = new RaytracingShaderSource.RayHitSource[
                HybridSbtLayout.REQUIRED_HIT_GROUP_COUNT];
        hitGroups[HybridSbtLayout.REFLECTION_PROCEDURAL_TERRAIN_HIT_GROUP] =
                new RaytracingShaderSource.RayHitSource(closestHit, null, intersection);
        return new RaytracingShaderSource("test", "raygen", new String[0], hitGroups);
    }
}
