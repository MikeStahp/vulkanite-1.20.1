package me.cortex.vulkanite.acceleration.blas;

import me.cortex.vulkanite.lib.base.VObject;
import me.cortex.vulkanite.lib.base.VRef;
import me.cortex.vulkanite.lib.memory.VAccelerationStructure;
import me.cortex.vulkanite.lib.memory.VBuffer;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Section-owned BLAS containing only terrain triangles that are authoritative
 * for shadow rays. Reflection triangles remain in the section's material BLAS.
 */
public final class ShadowTriangleBLAS extends VObject {
    private static final AtomicLong LIVE_COUNT = new AtomicLong();
    private static final AtomicLong LIVE_BYTES = new AtomicLong();
    private static final AtomicLong LIVE_QUADS = new AtomicLong();
    private static final AtomicLong REMOVED_QUADS = new AtomicLong();

    private final VRef<VAccelerationStructure> structure;
    private final VRef<VBuffer> geometryBuffer;
    private final List<BLASTriangleData> geometries;
    private final List<Long> bufferOffsets;
    private final long sourceQuadCount;
    private final long shadowQuadCount;
    private final long accelerationStructureBytes;
    private final long persistentBytes;
    private final String debugName;

    private ShadowTriangleBLAS(
            VRef<VAccelerationStructure> structure,
            VRef<VBuffer> geometryBuffer,
            List<BLASTriangleData> geometries,
            List<Long> bufferOffsets,
            long sourceQuadCount,
            long shadowQuadCount,
            long accelerationStructureBytes,
            String debugName) {
        this.structure = structure;
        this.geometryBuffer = geometryBuffer;
        this.geometries = List.copyOf(geometries);
        this.bufferOffsets = List.copyOf(bufferOffsets);
        this.sourceQuadCount = sourceQuadCount;
        this.shadowQuadCount = shadowQuadCount;
        this.accelerationStructureBytes = accelerationStructureBytes;
        this.persistentBytes = accelerationStructureBytes + geometryBuffer.get().size();
        this.debugName = debugName;
        LIVE_COUNT.incrementAndGet();
        LIVE_BYTES.addAndGet(persistentBytes);
        LIVE_QUADS.addAndGet(shadowQuadCount);
        REMOVED_QUADS.addAndGet(sourceQuadCount - shadowQuadCount);
    }

    public static VRef<ShadowTriangleBLAS> create(
            VRef<VAccelerationStructure> structure,
            VRef<VBuffer> geometryBuffer,
            List<BLASTriangleData> geometries,
            List<Long> bufferOffsets,
            long sourceQuadCount,
            long shadowQuadCount,
            long accelerationStructureBytes,
            String debugName) {
        return new VRef<>(new ShadowTriangleBLAS(structure, geometryBuffer, geometries, bufferOffsets,
                sourceQuadCount, shadowQuadCount, accelerationStructureBytes, debugName));
    }

    public VRef<VAccelerationStructure> structure() { return structure.addRef(); }
    public VRef<VBuffer> geometryBuffer() { return geometryBuffer.addRef(); }
    public List<BLASTriangleData> geometries() { return geometries; }
    public List<Long> bufferOffsets() { return bufferOffsets; }
    public int geometryCount() { return geometries.size(); }
    public long sourceQuadCount() { return sourceQuadCount; }
    public long shadowQuadCount() { return shadowQuadCount; }
    public long removedQuadCount() { return sourceQuadCount - shadowQuadCount; }
    public long accelerationStructureBytes() { return accelerationStructureBytes; }
    public long persistentBytes() { return persistentBytes; }
    public String debugName() { return debugName; }

    public static Statistics statistics() {
        return new Statistics(LIVE_COUNT.get(), LIVE_BYTES.get(), LIVE_QUADS.get(), REMOVED_QUADS.get());
    }

    @Override
    protected void free() {
        structure.close();
        geometryBuffer.close();
        LIVE_COUNT.decrementAndGet();
        LIVE_BYTES.addAndGet(-persistentBytes);
        LIVE_QUADS.addAndGet(-shadowQuadCount);
        REMOVED_QUADS.addAndGet(-(sourceQuadCount - shadowQuadCount));
    }

    public record Statistics(long live, long liveBytes, long liveQuads, long removedQuads) {}
}
