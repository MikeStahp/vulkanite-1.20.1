package me.cortex.vulkanite.client.rendering.cache;

import me.cortex.vulkanite.lib.base.VContext;
import me.cortex.vulkanite.lib.base.VRef;
import me.cortex.vulkanite.lib.cmd.VCmdBuff;
import me.cortex.vulkanite.lib.memory.VBuffer;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.lwjgl.vulkan.KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR;
import static org.lwjgl.vulkan.VK10.VK_ACCESS_SHADER_READ_BIT;
import static org.lwjgl.vulkan.VK10.VK_ACCESS_SHADER_WRITE_BIT;
import static org.lwjgl.vulkan.VK10.VK_ACCESS_TRANSFER_WRITE_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_TRANSFER_BIT;

/**
 * Bounded GPU cache for reflection and refraction continuation-ray results.
 *
 * <p>Entries use one-block world cells and store incident transport plus continuation hit distances. Surface
 * Fresnel, tint, and absorption remain resolve-time operations so neighboring
 * materials cannot silently share a final shaded color.</p>
 */
public final class SpecularTransportCache {
    public static final int MAX_ENTRIES = 32_768;
    public static final int MAX_FILL_REQUESTS = 256;

    private static final int HEADER_BYTES = 16;
    private static final int ENTRY_BYTES = 48;
    private static final int FILL_REQUEST_RECORD_BYTES = 48;
    private static final int CACHE_BUFFER_BYTES = HEADER_BYTES + MAX_ENTRIES * ENTRY_BYTES;
    private static final int FILL_REQUEST_BUFFER_BYTES =
            HEADER_BYTES + MAX_FILL_REQUESTS * FILL_REQUEST_RECORD_BYTES;
    private static final int HASH_PROBE_LIMIT = 8;

    private final CacheInvalidationTracker invalidationTracker;
    private VRef<VBuffer> cacheBuffer;
    private VRef<VBuffer> fillRequestBuffer;
    private ByteBuffer cacheClearScratch;
    private ByteBuffer fillRequestScratch;
    private int uploadedGeneration;

    public SpecularTransportCache() {
        this(CacheInvalidationTracker.global());
    }

    SpecularTransportCache(CacheInvalidationTracker invalidationTracker) {
        this.invalidationTracker = invalidationTracker;
    }

    public synchronized VRef<VBuffer> ensureCacheGpuBuffer(VContext ctx, VCmdBuff cmd) {
        ensureCacheCapacity(ctx);

        int generation = currentGeneration();
        if (generation != uploadedGeneration) {
            ByteBuffer data = scratchCache(CACHE_BUFFER_BYTES);
            data.putInt(MAX_ENTRIES);
            data.putInt(generation);
            data.putInt(HASH_PROBE_LIMIT);
            data.putInt(0);
            data.position(CACHE_BUFFER_BYTES);
            data.flip();

            cmd.encodeDataUpload(ctx.memory, MemoryUtil.memAddress(data), cacheBuffer, 0, CACHE_BUFFER_BYTES);
            cmd.encodeBufferBarrier(
                    cacheBuffer,
                    0,
                    CACHE_BUFFER_BYTES,
                    VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK_ACCESS_TRANSFER_WRITE_BIT,
                    VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);
            uploadedGeneration = generation;
        }

        return cacheBuffer.addRef();
    }

    public synchronized VRef<VBuffer> ensureFillRequestGpuBuffer(
            VContext ctx,
            VCmdBuff cmd,
            CacheRequestBatch batch) {
        ensureFillRequestCapacity(ctx);

        int generation = currentGeneration();
        ByteBuffer data = scratchFillRequests(FILL_REQUEST_BUFFER_BYTES);
        data.putInt(0);
        data.putInt(generation);
        data.putInt(MAX_FILL_REQUESTS);
        data.putInt(0);

        int requestCount = 0;
        if (batch != null) {
            for (CacheRequest request : batch.requests()) {
                CacheRequestFamily family = request.key().family();
                if (requestCount >= MAX_FILL_REQUESTS
                        || (family != CacheRequestFamily.REFLECTION
                                && family != CacheRequestFamily.REFRACTION)
                        || !invalidationTracker.isCurrent(request.key(), request.versionStamp())) {
                    continue;
                }

                CacheRequestKey key = request.key();
                data.putInt(key.gridCellX());
                data.putInt(key.gridCellY());
                data.putInt(key.gridCellZ());
                data.putInt(key.variantKey());
                data.putInt(family == CacheRequestFamily.REFLECTION ? 0 : 1);
                data.putInt(key.detailKey());
                data.putInt(0);
                data.putInt(0);
                data.putFloat(request.sampleX());
                data.putFloat(request.sampleY());
                data.putFloat(request.sampleZ());
                data.putFloat(1.0f);
                requestCount++;
            }
        }

        data.putInt(0, requestCount);
        data.position(HEADER_BYTES + requestCount * FILL_REQUEST_RECORD_BYTES);
        data.flip();
        int uploadBytes = data.remaining();
        cmd.encodeDataUpload(ctx.memory, MemoryUtil.memAddress(data), fillRequestBuffer, 0, uploadBytes);
        cmd.encodeBufferBarrier(
                fillRequestBuffer,
                0,
                uploadBytes,
                VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                VK_ACCESS_TRANSFER_WRITE_BIT,
                VK_ACCESS_SHADER_READ_BIT);
        return fillRequestBuffer.addRef();
    }

    public synchronized void reset() {
        uploadedGeneration = 0;
    }

    public synchronized void destroy() {
        if (cacheBuffer != null) {
            cacheBuffer.close();
            cacheBuffer = null;
        }
        if (fillRequestBuffer != null) {
            fillRequestBuffer.close();
            fillRequestBuffer = null;
        }
        cacheClearScratch = freeScratch(cacheClearScratch);
        fillRequestScratch = freeScratch(fillRequestScratch);
        uploadedGeneration = 0;
    }

    private void ensureCacheCapacity(VContext ctx) {
        if (cacheBuffer != null) {
            return;
        }
        cacheBuffer = ctx.memory.createBuffer(
                CACHE_BUFFER_BYTES,
                VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        cacheBuffer.get().setDebugUtilsObjectName("Specular transport cache");
        uploadedGeneration = 0;
    }

    private void ensureFillRequestCapacity(VContext ctx) {
        if (fillRequestBuffer != null) {
            return;
        }
        fillRequestBuffer = ctx.memory.createBuffer(
                FILL_REQUEST_BUFFER_BYTES,
                VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        fillRequestBuffer.get().setDebugUtilsObjectName("Specular transport fill requests");
    }

    private int currentGeneration() {
        long hash = 0x9e3779b97f4a7c15L;
        hash = mix(hash, invalidationTracker.worldId());
        hash = mix(hash, invalidationTracker.shaderpackGeneration());
        hash = mix(hash, invalidationTracker.skyGeneration());
        hash = mix(hash, invalidationTracker.materialGeneration());
        hash = mix(hash, invalidationTracker.cacheLayoutGeneration());
        hash = mix(hash, invalidationTracker.familyGeneration(CacheRequestFamily.REFLECTION));
        hash = mix(hash, invalidationTracker.familyGeneration(CacheRequestFamily.REFRACTION));
        hash = mix(hash, invalidationTracker.sceneGeometryGeneration());
        hash = mix(hash, invalidationTracker.sceneLightGeneration());
        int generation = (int) (hash ^ (hash >>> 32));
        return generation == 0 ? 1 : generation;
    }

    private static long mix(long hash, long value) {
        hash ^= value + 0x9e3779b97f4a7c15L + (hash << 6) + (hash >>> 2);
        return hash;
    }

    private ByteBuffer scratchCache(int requiredBytes) {
        cacheClearScratch = scratch(cacheClearScratch, requiredBytes);
        return cacheClearScratch;
    }

    private ByteBuffer scratchFillRequests(int requiredBytes) {
        fillRequestScratch = scratch(fillRequestScratch, requiredBytes);
        return fillRequestScratch;
    }

    private static ByteBuffer scratch(ByteBuffer scratch, int requiredBytes) {
        if (scratch == null || scratch.capacity() < requiredBytes) {
            scratch = freeScratch(scratch);
            scratch = MemoryUtil.memAlloc(requiredBytes);
        }
        scratch.order(ByteOrder.nativeOrder());
        MemoryUtil.memSet(MemoryUtil.memAddress(scratch), 0, requiredBytes);
        scratch.clear();
        scratch.limit(requiredBytes);
        return scratch;
    }

    private static ByteBuffer freeScratch(ByteBuffer scratch) {
        if (scratch != null) {
            MemoryUtil.memFree(scratch);
        }
        return null;
    }
}
