package me.cortex.vulkanite.compat;

import me.jellysquid.mods.sodium.client.util.NativeBuffer;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A collection of Sodium terrain geometry ranges.
 *
 * <p>Normal capture batches only borrow the native buffers owned by Sodium's
 * chunk-build result. Derived batches, such as the compact shadow and
 * procedural-face streams, own their native buffers and release them when the
 * batch is closed. Keeping that distinction here prevents temporary filtered
 * geometry from leaking without changing the lifetime of Sodium-owned mesh
 * data.</p>
 */
public final class SodiumGeometryBatch implements AutoCloseable {
    private final List<SodiumGeometry> geometries;
    private final long totalSizeBytes;
    private final boolean ownsVertexData;
    private final AtomicBoolean closed = new AtomicBoolean();

    /** Creates a batch that borrows its vertex buffers. */
    public SodiumGeometryBatch(List<SodiumGeometry> geometries, long totalSizeBytes) {
        this(geometries, totalSizeBytes, false);
    }

    private SodiumGeometryBatch(
            List<SodiumGeometry> geometries,
            long totalSizeBytes,
            boolean ownsVertexData) {
        this.geometries = List.copyOf(Objects.requireNonNull(geometries, "geometries"));
        if (totalSizeBytes < 0L) {
            throw new IllegalArgumentException("totalSizeBytes cannot be negative");
        }
        long actualSize = 0L;
        for (SodiumGeometry geometry : this.geometries) {
            actualSize = Math.addExact(actualSize,
                    Objects.requireNonNull(geometry, "geometry").sizeBytes());
        }
        if (actualSize != totalSizeBytes) {
            throw new IllegalArgumentException(
                    "Geometry byte total mismatch: declared=" + totalSizeBytes + ", actual=" + actualSize);
        }
        this.totalSizeBytes = totalSizeBytes;
        this.ownsVertexData = ownsVertexData;
    }

    /** Creates a batch that owns every distinct native buffer in its ranges. */
    public static SodiumGeometryBatch owned(List<SodiumGeometry> geometries, long totalSizeBytes) {
        return new SodiumGeometryBatch(geometries, totalSizeBytes, true);
    }

    public List<SodiumGeometry> geometries() {
        return geometries;
    }

    public long totalSizeBytes() {
        return totalSizeBytes;
    }

    public boolean isEmpty() {
        return geometries.isEmpty();
    }

    public boolean ownsVertexData() {
        return ownsVertexData;
    }

    public boolean isClosed() {
        return closed.get();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true) || !ownsVertexData) {
            return;
        }

        Set<NativeBuffer> released = Collections.newSetFromMap(new IdentityHashMap<>());
        for (SodiumGeometry geometry : geometries) {
            NativeBuffer vertexData = geometry.vertexData();
            if (released.add(vertexData)) {
                vertexData.free();
            }
        }
    }
}
