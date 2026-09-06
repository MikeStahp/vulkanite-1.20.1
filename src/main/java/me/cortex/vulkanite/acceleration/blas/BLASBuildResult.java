package me.cortex.vulkanite.acceleration.blas;

import me.cortex.vulkanite.acceleration.JobPassThroughData;
import me.cortex.vulkanite.lib.base.VRef;
import me.cortex.vulkanite.lib.memory.VAccelerationStructure;

import java.util.Objects;
import java.util.Optional;

/**
 * Result of a single BLAS build operation.
 * Contains the acceleration structure reference and associated pass-through
 * data.
 */
public record BLASBuildResult(
        VRef<VAccelerationStructure> structure,
        JobPassThroughData data,
        Optional<VRef<ShadowTriangleBLAS>> shadowTriangleBlas,
        boolean filteredShadowGeometry,
        Optional<VRef<ProceduralBLAS>> proceduralBlas,
        ProceduralBLASDisposition proceduralDisposition) implements AutoCloseable {

    public BLASBuildResult {
        Objects.requireNonNull(structure, "structure");
        Objects.requireNonNull(data, "data");
        shadowTriangleBlas = Objects.requireNonNull(shadowTriangleBlas, "shadowTriangleBlas");
        if (shadowTriangleBlas.isPresent() && !filteredShadowGeometry) {
            throw new IllegalArgumentException("A filtered shadow BLAS requires filtered ownership");
        }
        proceduralBlas = Objects.requireNonNull(proceduralBlas, "proceduralBlas");
        Objects.requireNonNull(proceduralDisposition, "proceduralDisposition");
        if ((proceduralDisposition == ProceduralBLASDisposition.REPLACE) != proceduralBlas.isPresent()) {
            throw new IllegalArgumentException("REPLACE requires a procedural BLAS and other dispositions forbid it");
        }
    }

    public BLASBuildResult(VRef<VAccelerationStructure> structure, JobPassThroughData data) {
        this(structure, data, Optional.empty(), false, Optional.empty(), ProceduralBLASDisposition.CLEAR);
    }

    @Override
    public void close() {
        structure.close();
        shadowTriangleBlas.ifPresent(VRef::close);
        proceduralBlas.ifPresent(VRef::close);
        data.geometryBuffer().close();
    }
}
