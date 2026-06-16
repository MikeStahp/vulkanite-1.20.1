package me.cortex.vulkanite.acceleration.blas;

/**
 * Represents one quad-backed geometry range in a BLAS build.
 */
public record BLASTriangleData(int quadCount, int geometryFlags, GeometryKind kind) {
    public BLASTriangleData(int quadCount, int geometryFlags) {
        this(quadCount, geometryFlags, GeometryKind.UNKNOWN);
    }

    public enum GeometryKind {
        TERRAIN_SOLID,
        TERRAIN_TRANSLUCENT,
        TERRAIN_WATER,
        ENTITY,
        UNKNOWN
    }
}
