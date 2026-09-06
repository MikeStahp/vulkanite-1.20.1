package me.cortex.vulkanite.acceleration.blas;

import me.cortex.vulkanite.lib.base.VRef;
import me.cortex.vulkanite.lib.memory.VBuffer;

import java.util.List;
import java.util.Objects;

/**
 * GPU input retained while building the triangle-only portion of a section's
 * hybrid shadow representation.
 */
public record ShadowTriangleBLASInput(
        List<BLASTriangleData> geometries,
        VRef<VBuffer> geometryBuffer,
        List<Long> bufferOffsets,
        long sourceQuadCount,
        long shadowQuadCount,
        String debugName) implements AutoCloseable {

    public ShadowTriangleBLASInput {
        geometries = List.copyOf(geometries);
        bufferOffsets = List.copyOf(bufferOffsets);
        Objects.requireNonNull(geometryBuffer, "geometryBuffer");
        Objects.requireNonNull(debugName, "debugName");
        if (geometries.isEmpty() || geometries.size() != bufferOffsets.size()) {
            throw new IllegalArgumentException("Shadow triangle geometry and offsets must be non-empty and aligned");
        }
        if (sourceQuadCount < shadowQuadCount || shadowQuadCount <= 0L) {
            throw new IllegalArgumentException("Invalid shadow quad counts");
        }
    }

    public long removedQuadCount() {
        return sourceQuadCount - shadowQuadCount;
    }

    @Override
    public void close() {
        geometryBuffer.close();
    }
}
