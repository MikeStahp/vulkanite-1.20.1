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
import static org.lwjgl.vulkan.VK10.VK_WHOLE_SIZE;

/**
 * GPU-visible cache for coarse diffuse incident radiance entries.
 */
public final class DiffuseRadianceCache {
    private static final int FILL_REQUEST_BUFFER_BYTES =
            CacheLayouts.DiffuseRadiance.HEADER_BYTES + CacheLayouts.MAX_FILL_REQUESTS * CacheLayouts.DiffuseRadiance.FILL_REQUEST_RECORD_BYTES;
    private static final int HASH_PROBE_LIMIT = 8;
    private static final int FRAMES_IN_FLIGHT = 3;

    private final CacheInvalidationTracker invalidationTracker;
    private VRef<VBuffer> cacheBuffer;
    private final VRef<VBuffer>[] fillRequestBuffers = new VRef[FRAMES_IN_FLIGHT];
    private ByteBuffer cacheClearScratch;
    private ByteBuffer fillRequestScratch;
    private int uploadedGeneration;
    private boolean cacheStorageInitialized;
    private boolean cacheNeedsFullClear;

    public DiffuseRadianceCache() {
        this(CacheInvalidationTracker.global());
    }

    public DiffuseRadianceCache(CacheInvalidationTracker invalidationTracker) {
        this.invalidationTracker = invalidationTracker;
    }

    public synchronized VRef<VBuffer> ensureCacheGpuBuffer(VContext ctx, VCmdBuff cmd) {
        ensureCacheCapacity(ctx);

        int generation = currentGeneration();
        if (generation != uploadedGeneration || cacheNeedsFullClear) {
            boolean fullClear = !cacheStorageInitialized || cacheNeedsFullClear;
            int uploadBytes = fullClear ? CacheLayouts.DiffuseRadiance.HEADER_BYTES : CacheLayouts.DiffuseRadiance.CACHE_BUFFER_BYTES;
            ByteBuffer data = cacheClearScratch(fullClear ? CacheLayouts.DiffuseRadiance.HEADER_BYTES : CacheLayouts.DiffuseRadiance.CACHE_BUFFER_BYTES);
            data.putInt(CacheLayouts.DiffuseRadiance.MAX_ENTRIES);
            data.putInt(generation);
            data.putInt(HASH_PROBE_LIMIT);
            data.putInt(0);
            if (!fullClear) {
                data.position(uploadBytes);
            }
            data.flip();

            cmd.encodeDataUpload(ctx.memory, MemoryUtil.memAddress(data), cacheBuffer, 0, uploadBytes);
            
            if (fullClear) {
                cmd.encodeFillBuffer(cacheBuffer, CacheLayouts.DiffuseRadiance.HEADER_BYTES, VK_WHOLE_SIZE, 0);
                cmd.encodeBufferBarrier(
                        cacheBuffer,
                        0,
                        CacheLayouts.DiffuseRadiance.CACHE_BUFFER_BYTES,
                        VK_PIPELINE_STAGE_TRANSFER_BIT,
                        VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                        VK_ACCESS_TRANSFER_WRITE_BIT,
                        VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);
            } else {
                cmd.encodeBufferBarrier(
                        cacheBuffer,
                        0,
                        CacheLayouts.DiffuseRadiance.HEADER_BYTES,
                        VK_PIPELINE_STAGE_TRANSFER_BIT,
                        VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                        VK_ACCESS_TRANSFER_WRITE_BIT,
                        VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);
            }
            
            uploadedGeneration = generation;
            cacheStorageInitialized = true;
            cacheNeedsFullClear = false;
        }

        return cacheBuffer.addRef();
    }

    public synchronized VRef<VBuffer> ensureFillRequestGpuBuffer(
            VContext ctx,
            VCmdBuff cmd,
            CacheRequestBatch batch,
            int frameIndex) {
        ensureFillRequestCapacity(ctx);

        int generation = currentGeneration();
        ByteBuffer data = fillRequestScratch(FILL_REQUEST_BUFFER_BYTES);
        data.putInt(0);
        data.putInt(generation);
        data.putInt(CacheLayouts.MAX_FILL_REQUESTS);
        data.putInt(0);

        int requestCount = 0;
        if (batch != null) {
            for (CacheRequest request : batch.requests()) {
                if (requestCount >= CacheLayouts.MAX_FILL_REQUESTS
                        || request.key().family() != CacheRequestFamily.DIFFUSE_RADIANCE
                        || !invalidationTracker.isCurrent(request.key(), request.versionStamp())) {
                    continue;
                }
                CacheRequestKey key = request.key();
                data.putInt(key.gridCellX());
                data.putInt(key.gridCellY());
                data.putInt(key.gridCellZ());
                data.putInt(key.variantKey() & 0xFF);
                data.putFloat(request.sampleX());
                data.putFloat(request.sampleY());
                data.putFloat(request.sampleZ());
                data.putFloat(1.0f);
                requestCount++;
            }
        }

        data.putInt(0, requestCount);
        data.position(CacheLayouts.DiffuseRadiance.HEADER_BYTES + requestCount * CacheLayouts.DiffuseRadiance.FILL_REQUEST_RECORD_BYTES);
        data.flip();
        int uploadBytes = data.remaining();
        VRef<VBuffer> fillRequestBuffer = fillRequestBuffers[frameIndex % FRAMES_IN_FLIGHT];
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
        cacheNeedsFullClear = true;
    }

    public synchronized void destroy() {
        if (cacheBuffer != null) {
            cacheBuffer.close();
            cacheBuffer = null;
        }
        for (int i = 0; i < fillRequestBuffers.length; i++) {
            if (fillRequestBuffers[i] != null) {
                fillRequestBuffers[i].close();
                fillRequestBuffers[i] = null;
            }
        }
        cacheClearScratch = freeScratch(cacheClearScratch);
        fillRequestScratch = freeScratch(fillRequestScratch);
        uploadedGeneration = 0;
        cacheStorageInitialized = false;
        cacheNeedsFullClear = false;
    }

    private void ensureCacheCapacity(VContext ctx) {
        if (cacheBuffer != null) {
            return;
        }
        cacheBuffer = ctx.memory.createBuffer(
                CacheLayouts.DiffuseRadiance.CACHE_BUFFER_BYTES,
                VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        cacheBuffer.get().setDebugUtilsObjectName("Diffuse radiance cache");
        uploadedGeneration = 0;
        cacheStorageInitialized = false;
        cacheNeedsFullClear = true;
    }

    private void ensureFillRequestCapacity(VContext ctx) {
        for (int i = 0; i < fillRequestBuffers.length; i++) {
            if (fillRequestBuffers[i] == null) {
                fillRequestBuffers[i] = ctx.memory.createBuffer(
                        FILL_REQUEST_BUFFER_BYTES,
                        VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                        VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
                fillRequestBuffers[i].get().setDebugUtilsObjectName("Diffuse radiance fill requests " + i);
            }
        }
    }

    private int currentGeneration() {
        long hash = 0x9e3779b97f4a7c15L;
        hash = mix(hash, invalidationTracker.worldId());
        hash = mix(hash, invalidationTracker.shaderpackGeneration());
        hash = mix(hash, invalidationTracker.skyGeneration());
        hash = mix(hash, invalidationTracker.materialGeneration());
        hash = mix(hash, invalidationTracker.cacheLayoutGeneration());
        hash = mix(hash, invalidationTracker.familyGeneration(CacheRequestFamily.DIFFUSE_RADIANCE));
        hash = mix(hash, invalidationTracker.sceneGeometryGeneration());
        hash = mix(hash, invalidationTracker.sceneLightGeneration());
        int generation = (int) (hash ^ (hash >>> 32));
        return generation == 0 ? 1 : generation;
    }

    private static long mix(long hash, long value) {
        hash ^= value + 0x9e3779b97f4a7c15L + (hash << 6) + (hash >>> 2);
        return hash;
    }

    private ByteBuffer cacheClearScratch(int requiredBytes) {
        cacheClearScratch = scratch(cacheClearScratch, requiredBytes);
        return cacheClearScratch;
    }

    private ByteBuffer fillRequestScratch(int requiredBytes) {
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
