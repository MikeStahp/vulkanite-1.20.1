package me.cortex.vulkanite.acceleration.blas;

import me.cortex.vulkanite.acceleration.voxel.AdaptiveBrickSizePolicy;
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
    private static final AtomicLong[] LIVE_BY_SIZE = counters();
    private static final AtomicLong[] LIVE_AABBS_BY_SIZE = counters();
    private static final AtomicLong[] LIVE_AS_BYTES_BY_SIZE = counters();
    private static final AtomicLong[] LIVE_PAYLOAD_BYTES_BY_SIZE = counters();

    private final VRef<VAccelerationStructure> structure;
    private final VRef<VBuffer> payloadBuffer;
    private final int aabbCount;
    private final int brickSize;
    private final boolean materialPayload;
    private final boolean completeReflectionMaterialPayload;
    private final int materialCellCount;
    private final int materialFaceCount;
    private final int materialLayerCount;
    private final long accelerationStructureBytes;
    private final long persistentBytes;
    private final String debugName;

    private ProceduralBLAS(
            VRef<VAccelerationStructure> structure,
            VRef<VBuffer> payloadBuffer,
            int aabbCount,
            int brickSize,
            boolean materialPayload,
            boolean completeReflectionMaterialPayload,
            int materialCellCount,
            int materialFaceCount,
            int materialLayerCount,
            long accelerationStructureBytes,
            String debugName) {
        this.structure = structure;
        this.payloadBuffer = payloadBuffer;
        this.aabbCount = aabbCount;
        this.brickSize = brickSize;
        this.materialPayload = materialPayload;
        this.completeReflectionMaterialPayload = completeReflectionMaterialPayload;
        this.materialCellCount = materialCellCount;
        this.materialFaceCount = materialFaceCount;
        this.materialLayerCount = materialLayerCount;
        this.accelerationStructureBytes = accelerationStructureBytes;
        this.persistentBytes = accelerationStructureBytes + payloadBuffer.get().size();
        this.debugName = debugName;

        long created = CREATED_COUNT.incrementAndGet();
        long live = LIVE_COUNT.incrementAndGet();
        long liveAabbs = LIVE_AABB_COUNT.addAndGet(aabbCount);
        long liveBytes = LIVE_BYTES.addAndGet(persistentBytes);
        int sizeIndex = AdaptiveBrickSizePolicy.supportedSizeIndex(brickSize);
        LIVE_BY_SIZE[sizeIndex].incrementAndGet();
        LIVE_AABBS_BY_SIZE[sizeIndex].addAndGet(aabbCount);
        LIVE_AS_BYTES_BY_SIZE[sizeIndex].addAndGet(accelerationStructureBytes);
        LIVE_PAYLOAD_BYTES_BY_SIZE[sizeIndex].addAndGet(payloadBuffer.get().size());
        LOGGER.debug("[Procedural BLAS] Created {}: AABBs={}, AS bytes={}, payload bytes={}, persistent bytes={}, created={}, live={}, live AABBs={}, live bytes={}",
                debugName, aabbCount, accelerationStructureBytes, payloadBuffer.get().size(), persistentBytes,
                created, live, liveAabbs, liveBytes);
    }

    public static VRef<ProceduralBLAS> create(
            VRef<VAccelerationStructure> structure,
            VRef<VBuffer> payloadBuffer,
            int aabbCount,
            int brickSize,
            boolean materialPayload,
            boolean completeReflectionMaterialPayload,
            int materialCellCount,
            int materialFaceCount,
            int materialLayerCount,
            long accelerationStructureBytes,
            String debugName) {
        return new VRef<>(new ProceduralBLAS(
                structure, payloadBuffer, aabbCount, brickSize, materialPayload,
                completeReflectionMaterialPayload, materialCellCount, materialFaceCount,
                materialLayerCount, accelerationStructureBytes, debugName));
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

    public int brickSize() { return brickSize; }

    public boolean hasMaterialPayload() { return materialPayload; }

    public boolean hasCompleteReflectionMaterialPayload() {
        return completeReflectionMaterialPayload;
    }

    public int materialCellCount() { return materialCellCount; }

    public int materialFaceCount() { return materialFaceCount; }

    public int materialLayerCount() { return materialLayerCount; }

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

    public static SizeStatistics sizeStatistics(int brickSize) {
        int index = AdaptiveBrickSizePolicy.supportedSizeIndex(brickSize);
        return new SizeStatistics(brickSize, LIVE_BY_SIZE[index].get(), LIVE_AABBS_BY_SIZE[index].get(),
                LIVE_AS_BYTES_BY_SIZE[index].get(), LIVE_PAYLOAD_BYTES_BY_SIZE[index].get());
    }

    @Override
    protected void free() {
        // The acceleration structure must be destroyed before its backing pool can
        // be reclaimed. VRef ownership preserves that ordering here and in-flight
        // command buffers retain the enclosing section holder separately.
        long payloadBytes = payloadBuffer.get().size();
        structure.close();
        payloadBuffer.close();
        long live = LIVE_COUNT.decrementAndGet();
        long liveAabbs = LIVE_AABB_COUNT.addAndGet(-aabbCount);
        long liveBytes = LIVE_BYTES.addAndGet(-persistentBytes);
        int sizeIndex = AdaptiveBrickSizePolicy.supportedSizeIndex(brickSize);
        LIVE_BY_SIZE[sizeIndex].decrementAndGet();
        LIVE_AABBS_BY_SIZE[sizeIndex].addAndGet(-aabbCount);
        LIVE_AS_BYTES_BY_SIZE[sizeIndex].addAndGet(-accelerationStructureBytes);
        LIVE_PAYLOAD_BYTES_BY_SIZE[sizeIndex].addAndGet(-payloadBytes);
        LOGGER.debug("[Procedural BLAS] Destroyed {}: AABBs={}, persistent bytes={}, live={}, live AABBs={}, live bytes={}",
                debugName, aabbCount, persistentBytes, live, liveAabbs, liveBytes);
    }

    public record Statistics(long created, long live, long liveAabbs, long liveBytes) {
    }

    public record SizeStatistics(int brickSize, long live, long liveAabbs,
            long liveAccelerationStructureBytes, long livePayloadBytes) {}

    private static AtomicLong[] counters() {
        AtomicLong[] result = new AtomicLong[AdaptiveBrickSizePolicy.SUPPORTED_SIZES.length];
        for (int i = 0; i < result.length; i++) result[i] = new AtomicLong();
        return result;
    }
}
