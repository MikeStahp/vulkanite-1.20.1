package me.cortex.vulkanite.acceleration;

/**
 * Shared shader-binding-table contract for the hybrid terrain pipeline.
 *
 * <p>Every BLAS used by Vulkanite currently contains one ray type. Triangle
 * terrain and entities select their hit records through the TLAS instance
 * offset. Procedural debug rays use a zero trace-time record offset; the
 * Phase 5 reference deliberately adds {@link #PROCEDURAL_SHADOW_REFERENCE_SBT_OFFSET}
 * so the same standalone procedural instance selects production record 5.</p>
 */
public final class HybridSbtLayout {
    public static final int TRIANGLE_TERRAIN_HIT_GROUP = 0;
    public static final int ENTITY_TRIANGLE_HIT_GROUP = 1;
    public static final int BASE_HIT_GROUP_COUNT = ENTITY_TRIANGLE_HIT_GROUP + 1;
    public static final int PROCEDURAL_TERRAIN_HIT_GROUP = 2;
    public static final int SHADOW_TRIANGLE_TERRAIN_HIT_GROUP = 3;
    public static final int SHADOW_ENTITY_TRIANGLE_HIT_GROUP = 4;
    public static final int SHADOW_PROCEDURAL_TERRAIN_HIT_GROUP = 5;
    public static final int REFLECTION_PROCEDURAL_TERRAIN_HIT_GROUP = 6;
    /** Records required by the production shadow backend. */
    public static final int REQUIRED_SHADOW_HIT_GROUP_COUNT = 6;
    /** Records discovered by Vulkanite, including optional Phase 10 reflection. */
    public static final int REQUIRED_HIT_GROUP_COUNT = 7;

    public static final int RAY_SBT_RECORD_OFFSET = 0;
    public static final int RAY_SBT_RECORD_STRIDE = 0;
    public static final int PRODUCTION_SHADOW_MISS_INDEX = 1;
    public static final int PROCEDURAL_DEBUG_MISS_INDEX = 2;
    public static final int PROCEDURAL_SHADOW_REFERENCE_SBT_OFFSET =
            SHADOW_PROCEDURAL_TERRAIN_HIT_GROUP - PROCEDURAL_TERRAIN_HIT_GROUP;
    public static final int PROCEDURAL_REFLECTION_REFERENCE_SBT_OFFSET =
            REFLECTION_PROCEDURAL_TERRAIN_HIT_GROUP - PROCEDURAL_TERRAIN_HIT_GROUP;
    public static final int PROCEDURAL_INSTANCE_MASK = 0x02;
    public static final int TERRAIN_TRIANGLE_INSTANCE_MASK = 0x01;
    public static final int ENTITY_TRIANGLE_INSTANCE_MASK = 0x04;
    public static final int SHADOW_RAY_MASK = TERRAIN_TRIANGLE_INSTANCE_MASK
            | PROCEDURAL_INSTANCE_MASK | ENTITY_TRIANGLE_INSTANCE_MASK;

    private HybridSbtLayout() {
    }

    /** Verifies that the fixed instance offsets select compatible group types. */
    public static void assertHitGroupCompatibility(boolean[] proceduralGroups) {
        if (proceduralGroups == null || proceduralGroups.length < REQUIRED_SHADOW_HIT_GROUP_COUNT) {
            throw new IllegalStateException("Hybrid RT requires terrain, entity, and procedural hit groups");
        }
        if (proceduralGroups[TRIANGLE_TERRAIN_HIT_GROUP]) {
            throw new IllegalStateException("Terrain triangle SBT record cannot be procedural");
        }
        if (proceduralGroups[ENTITY_TRIANGLE_HIT_GROUP]) {
            throw new IllegalStateException("Entity triangle SBT record cannot be procedural");
        }
        if (!proceduralGroups[PROCEDURAL_TERRAIN_HIT_GROUP]) {
            throw new IllegalStateException("Procedural terrain SBT record requires an intersection shader");
        }
        if (proceduralGroups[SHADOW_TRIANGLE_TERRAIN_HIT_GROUP]
                || proceduralGroups[SHADOW_ENTITY_TRIANGLE_HIT_GROUP]
                || !proceduralGroups[SHADOW_PROCEDURAL_TERRAIN_HIT_GROUP]) {
            throw new IllegalStateException("Production shadow SBT records have incompatible geometry types");
        }
        if (proceduralGroups.length > REFLECTION_PROCEDURAL_TERRAIN_HIT_GROUP
                && proceduralGroups[REFLECTION_PROCEDURAL_TERRAIN_HIT_GROUP]
                && !proceduralGroups[PROCEDURAL_TERRAIN_HIT_GROUP]) {
            throw new IllegalStateException("Procedural reflection requires the base procedural hit group");
        }
    }

    public static void assertMissRecordCount(int missRecordCount) {
        if (missRecordCount <= PROCEDURAL_DEBUG_MISS_INDEX) {
            throw new IllegalStateException("Procedural debug rays require miss record 2");
        }
    }

    public static int proceduralShadowReferenceHitGroup() {
        return PROCEDURAL_TERRAIN_HIT_GROUP + PROCEDURAL_SHADOW_REFERENCE_SBT_OFFSET;
    }

    public static int requiredHitGroupCount(boolean proceduralReflectionEnabled) {
        return proceduralReflectionEnabled ? REQUIRED_HIT_GROUP_COUNT : REQUIRED_SHADOW_HIT_GROUP_COUNT;
    }

    public enum GeometryType { TRIANGLES, AABBS }

    public static void assertInstanceCompatibility(GeometryType geometryType, int hitGroup, int mask) {
        if (mask == 0) {
            throw new IllegalArgumentException("A shadow-visible TLAS instance cannot have an empty mask");
        }
        boolean proceduralRecord = hitGroup == PROCEDURAL_TERRAIN_HIT_GROUP
                || hitGroup == SHADOW_PROCEDURAL_TERRAIN_HIT_GROUP
                || hitGroup == REFLECTION_PROCEDURAL_TERRAIN_HIT_GROUP;
        if ((geometryType == GeometryType.AABBS) != proceduralRecord) {
            throw new IllegalArgumentException("TLAS geometry type and SBT hit group are incompatible");
        }
        if (geometryType == GeometryType.TRIANGLES
                && hitGroup != TRIANGLE_TERRAIN_HIT_GROUP
                && hitGroup != ENTITY_TRIANGLE_HIT_GROUP
                && hitGroup != SHADOW_TRIANGLE_TERRAIN_HIT_GROUP
                && hitGroup != SHADOW_ENTITY_TRIANGLE_HIT_GROUP) {
            throw new IllegalArgumentException("Unknown triangle shadow hit group " + hitGroup);
        }
        int requiredMask = switch (hitGroup) {
            case SHADOW_TRIANGLE_TERRAIN_HIT_GROUP -> TERRAIN_TRIANGLE_INSTANCE_MASK;
            case SHADOW_ENTITY_TRIANGLE_HIT_GROUP -> ENTITY_TRIANGLE_INSTANCE_MASK;
            case SHADOW_PROCEDURAL_TERRAIN_HIT_GROUP -> PROCEDURAL_INSTANCE_MASK;
            default -> mask;
        };
        if (mask != requiredMask) {
            throw new IllegalArgumentException(
                    "Production shadow hit group " + hitGroup + " requires instance mask " + requiredMask);
        }
    }
}
