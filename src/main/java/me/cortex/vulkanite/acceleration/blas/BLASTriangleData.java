package me.cortex.vulkanite.acceleration.blas;

/**
 * Represents triangle geometry data for BLAS construction.
 * Contains the number of quads and geometry flags for a single geometry entry.
 */
public record BLASTriangleData(int quadCount, int geometryFlags) {
}
