package me.cortex.vulkanite.acceleration.blas;

import me.cortex.vulkanite.acceleration.JobPassThroughData;
import me.cortex.vulkanite.lib.base.VRef;
import me.cortex.vulkanite.lib.memory.VAccelerationStructure;

/**
 * Result of a single BLAS build operation.
 * Contains the acceleration structure reference and associated pass-through
 * data.
 */
public record BLASBuildResult(VRef<VAccelerationStructure> structure, JobPassThroughData data) {
}
