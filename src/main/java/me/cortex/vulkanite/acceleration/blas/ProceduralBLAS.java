package me.cortex.vulkanite.acceleration.blas;

import me.cortex.vulkanite.lib.base.VObject;
import me.cortex.vulkanite.lib.base.VRef;
import me.cortex.vulkanite.lib.memory.VAccelerationStructure;
import me.cortex.vulkanite.lib.memory.VBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Section-owned procedural acceleration structure and its shader payload.
 * The AABB build-input buffer is transient and is released after the build
 * submission completes; the payload remains resident for the future debug TLAS.
 */
public final class ProceduralBLAS extends VObject {
    private static final Logger LOGGER = LoggerFactory.getLogger(ProceduralBLAS.class);
    private static final AtomicLong CREATED_COUNT = new AtomicLong();
    private static final AtomicLong LIVE_COUNT = new AtomicLong();
    private static final AtomicLong LIVE_AABB_COUNT = new AtomicLong();
    private static final AtomicLong LIVE_BYTES = new AtomicLong();

    private final VRef<VAccelerationStructure> structure;
    private final VRef<VBuffer> payloadBuffer;
    private final int aabbCount;
    private final long accelerationStructureBytes;
    private final long persistentBytes;
    private final String debugName;

    private ProceduralBLAS(
            VRef<VAccelerationStructure> structure,
            VRef<VBuffer> payloadBuffer,
            int aabbCount,
            long accelerationStructureBytes,
            String debugName) {
        this.structure = structure;
        this.payloadBuffer = payloadBuffer;
        this.aabbCount = aabbCount;
        this.accelerationStructureBytes = accelerationStructureBytes;
        this.persistentBytes = accelerationStructureBytes + payloadBuffer.get().size();
        this.debugName = debugName;

        long created = CREATED_COUNT.incrementAndGet();
        long live = LIVE_COUNT.incrementAndGet();
        long liveAabbs = LIVE_AABB_COUNT.addAndGet(aabbCount);
        long liveBytes = LIVE_BYTES.addAndGet(persistentBytes);
        LOGGER.debug("[Procedural BLAS] Created {}: AABBs={}, AS bytes={}, payload bytes={}, persistent bytes={}, created={}, live={}, live AABBs={}, live bytes={}",
                debugName, aabbCount, accelerationStructureBytes, payloadBuffer.get().size(), persistentBytes,
                created, live, liveAabbs, liveBytes);
    }

    public static VRef<ProceduralBLAS> create(
            VRef<VAccelerationStructure> structure,
            VRef<VBuffer> payloadBuffer,
            int aabbCount,
            long accelerationStructureBytes,
            String debugName) {
        return new VRef<>(new ProceduralBLAS(
                structure, payloadBuffer, aabbCount, accelerationStructureBytes, debugName));
    }

    public VRef<VAccelerationStructure> structure() {
        return structure.addRef();
    }

    public VRef<VBuffer> payloadBuffer() {
        return payloadBuffer.addRef();
    }

    public int aabbCount() {
        return aabbCount;
    }

    public long accelerationStructureBytes() {
        return accelerationStructureBytes;
    }

    public long persistentBytes() {
        return persistentBytes;
    }

    public String debugName() {
        return debugName;
    }

    public static Statistics statistics() {
        return new Statistics(
                CREATED_COUNT.get(), LIVE_COUNT.get(), LIVE_AABB_COUNT.get(), LIVE_BYTES.get());
    }

    @Override
    protected void free() {
        // The acceleration structure must be destroyed before its backing pool can
        // be reclaimed. VRef ownership preserves that ordering here and in-flight
        // command buffers retain the enclosing section holder separately.
        structure.close();
        payloadBuffer.close();
        long live = LIVE_COUNT.decrementAndGet();
        long liveAabbs = LIVE_AABB_COUNT.addAndGet(-aabbCount);
        long liveBytes = LIVE_BYTES.addAndGet(-persistentBytes);
        LOGGER.debug("[Procedural BLAS] Destroyed {}: AABBs={}, persistent bytes={}, live={}, live AABBs={}, live bytes={}",
                debugName, aabbCount, persistentBytes, live, liveAabbs, liveBytes);
    }

    public record Statistics(long created, long live, long liveAabbs, long liveBytes) {
    }
}
