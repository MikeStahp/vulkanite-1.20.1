package me.cortex.vulkanite.compat;

/** Immutable debug counters produced by the section classification pass. */
public record SectionRayClassificationStats(int air, int voxelShadow, int triangleShadow,
        int emissive, int composite, int unknownFallback) {
    public static final SectionRayClassificationStats EMPTY = new SectionRayClassificationStats(0, 0, 0, 0, 0, 0);
}
