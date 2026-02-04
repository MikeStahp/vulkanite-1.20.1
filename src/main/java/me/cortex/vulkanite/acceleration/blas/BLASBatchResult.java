package me.cortex.vulkanite.acceleration.blas;

import java.util.List;

/**
 * Result of a batched BLAS build operation.
 * Contains a list of individual build results and the execution ID for
 * synchronization.
 */
public record BLASBatchResult(List<BLASBuildResult> results, long execution) {
}
