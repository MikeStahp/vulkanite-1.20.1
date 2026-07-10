package me.cortex.vulkanite.acceleration.blas;

import me.cortex.vulkanite.acceleration.voxel.VoxelBrickGeometry;
import me.cortex.vulkanite.lib.base.VRef;
import me.cortex.vulkanite.lib.memory.VBuffer;

import java.util.Objects;

/** Uploaded input for one section-local procedural AABB BLAS build. */
public record ProceduralBLASInput(
        VoxelBrickGeometry geometry,
        VRef<VBuffer> aabbBuffer,
        VRef<VBuffer> payloadBuffer,
        String debugName) implements AutoCloseable {

    public ProceduralBLASInput {
        Objects.requireNonNull(geometry, "geometry");
        Objects.requireNonNull(aabbBuffer, "aabbBuffer");
        Objects.requireNonNull(payloadBuffer, "payloadBuffer");
        Objects.requireNonNull(debugName, "debugName");
        if (geometry.brickCount() <= 0) {
            throw new IllegalArgumentException("A procedural BLAS input must contain at least one AABB");
        }
    }

    @Override
    public void close() {
        aabbBuffer.close();
        payloadBuffer.close();
    }
}
