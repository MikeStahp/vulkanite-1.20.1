package me.cortex.vulkanite.acceleration.blas;

import me.cortex.vulkanite.acceleration.JobPassThroughData;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Represents a single BLAS build job containing geometry data and pass-through
 * metadata.
 */
public record BLASBuildJob(
        List<BLASTriangleData> geometries,
        JobPassThroughData data,
        Optional<ShadowTriangleBLASInput> shadowTriangleInput,
        boolean filteredShadowGeometry,
        Optional<ProceduralBLASInput> proceduralInput,
        ProceduralBLASDisposition proceduralDisposition) {

    public BLASBuildJob {
        geometries = List.copyOf(geometries);
        Objects.requireNonNull(data, "data");
        shadowTriangleInput = Objects.requireNonNull(shadowTriangleInput, "shadowTriangleInput");
        if (shadowTriangleInput.isPresent() && !filteredShadowGeometry) {
            throw new IllegalArgumentException("A filtered shadow input requires filtered ownership");
        }
        proceduralInput = Objects.requireNonNull(proceduralInput, "proceduralInput");
        Objects.requireNonNull(proceduralDisposition, "proceduralDisposition");
        if ((proceduralDisposition == ProceduralBLASDisposition.REPLACE) != proceduralInput.isPresent()) {
            throw new IllegalArgumentException("REPLACE requires procedural input and other dispositions forbid it");
        }
    }

    public BLASBuildJob(List<BLASTriangleData> geometries, JobPassThroughData data) {
        this(geometries, data, Optional.empty(), false, Optional.empty(), ProceduralBLASDisposition.CLEAR);
    }
}
