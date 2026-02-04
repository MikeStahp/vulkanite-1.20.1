package me.cortex.vulkanite.acceleration.blas;

import me.cortex.vulkanite.acceleration.JobPassThroughData;

import java.util.List;

/**
 * Represents a single BLAS build job containing geometry data and pass-through
 * metadata.
 */
public record BLASBuildJob(List<BLASTriangleData> geometries, JobPassThroughData data) {
}
