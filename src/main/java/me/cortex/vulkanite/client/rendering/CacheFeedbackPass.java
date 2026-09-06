package me.cortex.vulkanite.client.rendering;

import me.cortex.vulkanite.client.Vulkanite;
import me.cortex.vulkanite.client.rendering.cache.CacheRequest;
import me.cortex.vulkanite.client.rendering.cache.CacheRequestFamily;
import me.cortex.vulkanite.client.rendering.cache.CacheRequestKey;
import me.cortex.vulkanite.client.rendering.cache.CacheRequestQueue;
import me.cortex.vulkanite.client.rendering.cache.CacheRequestSource;
import me.cortex.vulkanite.lib.base.VContext;
import me.cortex.vulkanite.lib.base.VRef;
import me.cortex.vulkanite.lib.descriptors.DescriptorUpdateBuilder;
import me.cortex.vulkanite.lib.descriptors.VDescriptorPool;
import me.cortex.vulkanite.lib.descriptors.VDescriptorSet;
import me.cortex.vulkanite.lib.descriptors.VDescriptorSetLayout;
import me.cortex.vulkanite.lib.memory.VBuffer;
import me.cortex.vulkanite.lib.memory.VImage;
import me.cortex.vulkanite.lib.other.VImageView;
import me.cortex.vulkanite.lib.other.VSampler;
import me.cortex.vulkanite.lib.pipeline.ComputePipelineBuilder;
import me.cortex.vulkanite.lib.pipeline.VComputePipeline;
import me.cortex.vulkanite.lib.shader.ShaderModule;
import me.cortex.vulkanite.lib.shader.VShader;
import me.cortex.vulkanite.lib.shader.reflection.ShaderReflection;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Coarse visible-surface request collector.
 *
 * <p>The pass samples the hybrid G-buffer into a host-visible feedback buffer.
 * CPU ingestion happens on the next frame, after the previous submission has
 * finished, so request discovery stays asynchronous and avoids blocking readback.</p>
 */
final class CacheFeedbackPass {
    private static final Logger LOGGER = LoggerFactory.getLogger(CacheFeedbackPass.class);
    private static final int LOCAL_SIZE_X = 8;
    private static final int LOCAL_SIZE_Y = 8;
    private static final int PUSH_CONSTANT_BYTES = 40;
    private static final int MAX_FEEDBACK_RECORDS = 4096;
    private static final int HEADER_BYTES = 64;
    private static final int RECORD_BYTES = 72;
    private static final int BUFFER_BYTES = HEADER_BYTES + MAX_FEEDBACK_RECORDS * RECORD_BYTES;
    private static final int MIN_SAMPLE_STEP = 1;
    private static final int MAX_SAMPLE_STEP = 64;
    private static final int FEEDBACK_RING_SIZE = 3;

    private final VContext ctx;
    private final VRef<VSampler> sampler;
    private final VRef<VComputePipeline> pipeline;
    private final VRef<VDescriptorSetLayout> setLayout;
    private final ShaderReflection.Set setReflection;
    private final FeedbackSlot[] feedbackSlots = new FeedbackSlot[FEEDBACK_RING_SIZE];
    private final int[] pushConstants = new int[10];
    private FeedbackSlot encodedSlot;
    private long feedbackEpoch = 1L;
    private long lastInvalidGbufferLogNanos;
    private long cacheQueries;
    private long cacheHits;
    private long fallbackPixels;
    private boolean screenHistoryValid;

    record Metrics(long cacheQueries, long cacheHits, long fallbackPixels) {
    }

    CacheFeedbackPass(VContext ctx, VRef<VSampler> sampler) {
        this.ctx = ctx;
        this.sampler = sampler.addRef();

        VRef<VShader> shader = VShader.compileLoad(ctx, SHADER_SOURCE, VK_SHADER_STAGE_COMPUTE_BIT);
        ShaderModule module = shader.get().named();
        List<VRef<VDescriptorSetLayout>> layouts = List.of();
        try {
            ShaderReflection reflection = shader.get().getReflection();
            layouts = reflection.buildSetLayouts(ctx);
            if (layouts.size() != 1) {
                throw new IllegalStateException("Cache feedback shader expected exactly one descriptor set, got "
                        + layouts.size());
            }

            this.setLayout = layouts.get(0).addRef();
            this.setReflection = reflection.getSet(0);

            ComputePipelineBuilder builder = new ComputePipelineBuilder()
                    .set(module)
                    .addLayout(layouts.get(0))
                    .addPushConstantRange(PUSH_CONSTANT_BYTES, 0);
            this.pipeline = builder.build(ctx);
        } finally {
            module.shader().close();
            shader.close();
            for (VRef<VDescriptorSetLayout> layout : layouts) {
                layout.close();
            }
        }

        for (int i = 0; i < feedbackSlots.length; i++) {
            VRef<VBuffer> buffer = ctx.memory.createBuffer(
                    BUFFER_BYTES,
                    VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
            buffer.get().setDebugUtilsObjectName("Cache request feedback " + i);
            feedbackSlots[i] = new FeedbackSlot(buffer);
        }
    }

    int ingestPendingFeedback(CacheRequestQueue requests) {
        if (requests == null) {
            return 0;
        }
        long completedExecution = ctx.cmd.getQueueCurrentExecution(0);
        int enqueued = 0;
        for (FeedbackSlot slot : feedbackSlots) {
            if (slot.state == FeedbackState.IN_FLIGHT && slot.execution <= completedExecution) {
                slot.state = FeedbackState.READY;
            }
            if (slot.state != FeedbackState.READY) {
                continue;
            }
            if (slot.epoch == feedbackEpoch) {
                enqueued += ingestFeedbackSlot(slot, requests);
            }
            slot.state = FeedbackState.FREE;
            slot.execution = 0L;
        }
        return enqueued;
    }

    private int ingestFeedbackSlot(FeedbackSlot slot, CacheRequestQueue requests) {
        long ptr = slot.buffer.get().map();
        try {
            ByteBuffer data = MemoryUtil.memByteBuffer(ptr, BUFFER_BYTES).order(ByteOrder.nativeOrder());
            int count = Math.min(data.getInt(0), MAX_FEEDBACK_RECORDS);
            int feedbackFrame = data.getInt(4);
            cacheQueries += Integer.toUnsignedLong(data.getInt(48));
            cacheHits += Integer.toUnsignedLong(data.getInt(52));
            fallbackPixels += Integer.toUnsignedLong(data.getInt(56));
            logInvalidGbufferDiagnostics(data);
            int enqueued = 0;
            for (int i = 0; i < count; i++) {
                int offset = HEADER_BYTES + i * RECORD_BYTES;
                CacheRequest request = readRequest(data, offset, feedbackFrame);
                if (request != null && requests.enqueue(request).accepted()) {
                    enqueued++;
                }
            }
            return enqueued;
        } finally {
            slot.buffer.get().unmap();
        }
    }

    private void logInvalidGbufferDiagnostics(ByteBuffer data) {
        int sampled = data.getInt(16);
        int valid = data.getInt(44);
        if (sampled <= 0 || valid > 0) {
            return;
        }
        long now = System.nanoTime();
        if (now - lastInvalidGbufferLogNanos < 2_000_000_000L) {
            return;
        }
        lastInvalidGbufferLogNanos = now;
        LOGGER.warn("[Vulkanite] G-buffer has no valid samples: sampled={}, finite={}, position={}, normal={}, material={}, albedoNonzero={}, extraNonzero={}",
                sampled,
                data.getInt(20),
                data.getInt(24),
                data.getInt(28),
                data.getInt(32),
                data.getInt(36),
                data.getInt(40));
    }

    boolean execute(RtxFrame frame) {
        if (frame.renderWidth() <= 0 || frame.renderHeight() <= 0 || missingGbuffer(frame)) {
            return false;
        }
        FeedbackSlot slot = acquireFreeSlot();
        if (slot == null) {
            return false;
        }

        int sampleStep = chooseSampleStep(frame.renderWidth(), frame.renderHeight());
        clearFeedbackHeader(slot.buffer, frame.frameIndex());
        frame.cmd().encodeBufferBarrier(
                slot.buffer,
                0,
                BUFFER_BYTES,
                VK_PIPELINE_STAGE_HOST_BIT,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK_ACCESS_HOST_WRITE_BIT,
                VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);

        VRef<VDescriptorPool> pool = Vulkanite.INSTANCE.getPoolByLayout(setLayout);
        VRef<VDescriptorSet> set = null;
        try {
            set = pool.get().allocateSet();
            DescriptorUpdateBuilder updater = new DescriptorUpdateBuilder(ctx, setReflection)
                    .set(set)
                    .uniform(0, frame.uboBuffer(), frame.uboOffset(), frame.uboSize());
            bindGbuffer(updater, frame, 1, 0);
            bindGbuffer(updater, frame, 2, 1);
            bindGbuffer(updater, frame, 3, 2);
            bindGbuffer(updater, frame, 4, 3);
            bindGbuffer(updater, frame, 5, 4);
            updater.buffer(6, slot.buffer);
            if (frame.specularTransportCacheBuffer() != null) {
                updater.buffer(7, frame.specularTransportCacheBuffer());
            }
            if (frame.diffuseRadianceCacheBuffer() != null) {
                updater.buffer(10, frame.diffuseRadianceCacheBuffer());
            }
            if (frame.surfaceDirectLightCacheBuffer() != null) {
                updater.buffer(11, frame.surfaceDirectLightCacheBuffer());
            }
            if (frame.sectionLightBuffer() != null) {
                updater.buffer(12, frame.sectionLightBuffer());
            }
            updater.imageStore(8, frame.storageViews().previousSpecularHistory());
            updater.imageStore(9, frame.storageViews().previousSpecularSurfaceHistory());
            updater.apply();

            pushConstants[0] = sampleStep;
            pushConstants[1] = frame.frameIndex();
            pushConstants[2] = frame.renderWidth();
            pushConstants[3] = frame.renderHeight();
            pushConstants[4] = screenHistoryValid ? 1 : 0;
            pushConstants[5] = 0;
            pushConstants[6] = Float.floatToRawIntBits(frame.sunDirectionX());
            pushConstants[7] = Float.floatToRawIntBits(frame.sunDirectionY());
            pushConstants[8] = Float.floatToRawIntBits(frame.sunDirectionZ());
            pushConstants[9] = 0;

            frame.cmd().bindCompute(pipeline);
            frame.cmd().bindDSet(List.of(set));
            frame.cmd().pushConstants(0, packPushConstants(), VK_SHADER_STAGE_COMPUTE_BIT);
            frame.cmd().dispatch(
                    (sampledWidth(frame.renderWidth(), sampleStep) + LOCAL_SIZE_X - 1) / LOCAL_SIZE_X,
                    (sampledWidth(frame.renderHeight(), sampleStep) + LOCAL_SIZE_Y - 1) / LOCAL_SIZE_Y,
                    1);
        } finally {
            if (set != null) {
                set.close();
            }
            pool.close();
        }

        frame.cmd().encodeBufferBarrier(
                slot.buffer,
                0,
                BUFFER_BYTES,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK_PIPELINE_STAGE_HOST_BIT,
                VK_ACCESS_SHADER_WRITE_BIT,
                VK_ACCESS_HOST_READ_BIT);
        slot.state = FeedbackState.ENCODED;
        slot.epoch = feedbackEpoch;
        encodedSlot = slot;
        return true;
    }

    void markScreenHistoryResolved() {
        screenHistoryValid = true;
    }

    Metrics snapshotMetrics() {
        return new Metrics(cacheQueries, cacheHits, fallbackPixels);
    }

    void markEncodedFeedbackSubmitted(long execution) {
        if (encodedSlot == null || encodedSlot.state != FeedbackState.ENCODED) {
            return;
        }
        encodedSlot.execution = execution;
        encodedSlot.state = FeedbackState.IN_FLIGHT;
        encodedSlot = null;
    }

    void discardEncodedFeedback() {
        if (encodedSlot == null) {
            return;
        }
        if (encodedSlot.state == FeedbackState.ENCODED) {
            encodedSlot.state = FeedbackState.FREE;
        }
        encodedSlot = null;
    }

    void clearPendingFeedback() {
        feedbackEpoch++;
        if (feedbackEpoch <= 0L) {
            feedbackEpoch = 1L;
        }
        discardEncodedFeedback();
        screenHistoryValid = false;
        for (FeedbackSlot slot : feedbackSlots) {
            if (slot.state == FeedbackState.READY) {
                slot.state = FeedbackState.FREE;
                slot.execution = 0L;
            }
        }
    }

    void invalidateScreenHistory() {
        screenHistoryValid = false;
    }

    void destroy() {
        for (FeedbackSlot slot : feedbackSlots) {
            slot.buffer.close();
        }
        pipeline.close();
        setLayout.close();
        sampler.close();
    }

    private CacheRequest readRequest(ByteBuffer data, int offset, int feedbackFrame) {
        int familyOrdinal = data.getInt(offset);
        if (familyOrdinal < 0 || familyOrdinal >= CacheRequestFamily.values().length) {
            return null;
        }
        CacheRequestFamily family = CacheRequestFamily.values()[familyOrdinal];
        CacheRequestSource source = sourceFromOrdinal(data.getInt(offset + 4));
        int cellX = data.getInt(offset + 8);
        int cellY = data.getInt(offset + 12);
        int cellZ = data.getInt(offset + 16);
        int normalBucket = data.getInt(offset + 20);
        int materialBucket = data.getInt(offset + 24);
        int roughnessBucket = data.getInt(offset + 28);
        int viewOrMediumBucket = data.getInt(offset + 32);
        int priorityHint = data.getInt(offset + 36);
        float visibility = data.getFloat(offset + 40);
        float error = data.getFloat(offset + 44);
        float luma = data.getFloat(offset + 48);
        float distanceSquared = data.getFloat(offset + 52);
        float sampleX = data.getFloat(offset + 56);
        float sampleY = data.getFloat(offset + 60);
        float sampleZ = data.getFloat(offset + 64);

        CacheRequestKey key = switch (family) {
            case DIFFUSE_RADIANCE -> CacheRequestKey.diffuseRadianceEntry(
                    cellX,
                    cellY,
                    cellZ,
                    normalBucket,
                    CacheRequestKey.DIFFUSE_INCIDENT_RADIANCE_BUCKET);
            case REFLECTION -> CacheRequestKey.reflectionEntry(
                    cellX, cellY, cellZ, normalBucket, roughnessBucket, materialBucket, viewOrMediumBucket);
            case REFRACTION -> CacheRequestKey.refractionEntry(
                    cellX,
                    cellY,
                    cellZ,
                    normalBucket,
                    roughnessBucket,
                    viewOrMediumBucket & 0xFF,
                    materialBucket,
                    (viewOrMediumBucket >>> 8) & 0xFF,
                    (viewOrMediumBucket >>> 16) & 0xFF);
            case SURFACE_DIRECT_LIGHT -> CacheRequestKey.surfaceDirectLightEntry(
                    cellX, cellY, cellZ, normalBucket);
            case SECTION_PROBE_CELL -> null;
        };
        if (key == null) {
            return null;
        }
        return new CacheRequest(
                key,
                source,
                feedbackFrame,
                priorityHint,
                visibility,
                error,
                luma,
                distanceSquared,
                sampleX,
                sampleY,
                sampleZ);
    }

    private CacheRequestSource sourceFromOrdinal(int sourceOrdinal) {
        if (sourceOrdinal < 0 || sourceOrdinal >= CacheRequestSource.values().length) {
            return CacheRequestSource.GPU_FEEDBACK;
        }
        return CacheRequestSource.values()[sourceOrdinal];
    }

    private void bindGbuffer(DescriptorUpdateBuilder updater, RtxFrame frame, int binding, int index) {
        updater.imageSampler(binding, frame.gbufferViews()[index], sampler);
    }

    private boolean missingGbuffer(RtxFrame frame) {
        VRef<VImageView>[] views = frame.gbufferViews();
        if (views == null || views.length < 5) {
            return true;
        }
        for (int i = 0; i < 5; i++) {
            if (views[i] == null || views[i].get() == null) {
                return true;
            }
        }
        return false;
    }

    private FeedbackSlot acquireFreeSlot() {
        if (encodedSlot != null) {
            throw new IllegalStateException("Cache feedback already encoded for this submission");
        }
        long completedExecution = ctx.cmd.getQueueCurrentExecution(0);
        for (FeedbackSlot slot : feedbackSlots) {
            if (slot.state == FeedbackState.IN_FLIGHT && slot.execution <= completedExecution) {
                slot.state = FeedbackState.READY;
            }
        }
        for (FeedbackSlot slot : feedbackSlots) {
            if (slot.state == FeedbackState.FREE) {
                return slot;
            }
        }
        return null;
    }

    private void clearFeedbackHeader(VRef<VBuffer> buffer, int frameIndex) {
        long ptr = buffer.get().map();
        try {
            MemoryUtil.memSet(ptr, 0, HEADER_BYTES);
            ByteBuffer data = MemoryUtil.memByteBuffer(ptr, HEADER_BYTES).order(ByteOrder.nativeOrder());
            data.putInt(0, 0);
            data.putInt(4, frameIndex);
            data.putInt(8, MAX_FEEDBACK_RECORDS);
            data.putInt(12, 0);
        } finally {
            buffer.get().unmap();
        }
    }

    private int chooseSampleStep(int width, int height) {
        int targetSamples = Math.max(1, MAX_FEEDBACK_RECORDS / 3);
        double pixelsPerSample = Math.max(1.0, (double) width * (double) height / targetSamples);
        int step = (int) Math.ceil(Math.sqrt(pixelsPerSample));
        return Math.max(MIN_SAMPLE_STEP, Math.min(MAX_SAMPLE_STEP, step));
    }

    private int sampledWidth(int pixels, int sampleStep) {
        return Math.max(1, (pixels + sampleStep - 1) / sampleStep);
    }

    private long[] packPushConstants() {
        return new long[] {
                Integer.toUnsignedLong(pushConstants[0])
                        | (Integer.toUnsignedLong(pushConstants[1]) << 32),
                Integer.toUnsignedLong(pushConstants[2])
                        | (Integer.toUnsignedLong(pushConstants[3]) << 32),
                Integer.toUnsignedLong(pushConstants[4])
                        | (Integer.toUnsignedLong(pushConstants[5]) << 32),
                Integer.toUnsignedLong(pushConstants[6])
                        | (Integer.toUnsignedLong(pushConstants[7]) << 32),
                Integer.toUnsignedLong(pushConstants[8])
                        | (Integer.toUnsignedLong(pushConstants[9]) << 32)
        };
    }

    private enum FeedbackState {
        FREE,
        ENCODED,
        IN_FLIGHT,
        READY
    }

    private static final class FeedbackSlot {
        private final VRef<VBuffer> buffer;
        private FeedbackState state = FeedbackState.FREE;
        private long execution;
        private long epoch;

        private FeedbackSlot(VRef<VBuffer> buffer) {
            this.buffer = buffer;
        }
    }

    private static final String SHADER_SOURCE = """
            #version 460 core

            layout(local_size_x = 8, local_size_y = 8, local_size_z = 1) in;

            layout(std140, binding = 0) uniform CameraInfo {
                vec3 corners[4];
                mat4 viewInverse;
                vec4 sunPosition;
                vec4 moonPosition;
                uint frameId;
                uint flags;
                vec2 padding1;
                mat4 prevViewProj;
                vec4 jitterData;
                mat4 curViewProj;
            } cam;

            layout(binding = 1) uniform sampler2D gbufferAlbedo;
            layout(binding = 2) uniform sampler2D gbufferMaterial;
            layout(binding = 3) uniform sampler2D gbufferNormal;
            layout(binding = 4) uniform sampler2D gbufferWorldPos;
            layout(binding = 5) uniform sampler2D gbufferExtra;

            struct FeedbackRecord {
                int family;
                int source;
                int cellX;
                int cellY;
                int cellZ;
                int normalBucket;
                int materialBucket;
                int roughnessBucket;
                int viewOrMediumBucket;
                int priorityHint;
                float visibility;
                float error;
                float luma;
                float distanceSquared;
                float sampleX;
                float sampleY;
                float sampleZ;
                float samplePadding;
            };

            layout(std430, binding = 6) buffer CacheFeedback {
                uint feedbackCount;
                uint feedbackFrame;
                uint feedbackCapacity;
                uint feedbackDropped;
                uint sampledCount;
                uint finiteCount;
                uint positionCount;
                uint normalCount;
                uint materialCount;
                uint albedoNonzeroCount;
                uint extraNonzeroCount;
                uint validCount;
                uint cacheQueryCount;
                uint cacheHitCount;
                uint fallbackPixelCount;
                uint counterPadding;
                FeedbackRecord records[];
            } feedback;

            struct SpecularTransportCacheEntry {
                ivec4 key;
                uvec4 metadata;
                uvec4 payload;
            };

            layout(std430, binding = 7) coherent buffer SpecularTransportCacheBuffer {
                uvec4 specularTransportCacheHeader;
                SpecularTransportCacheEntry specularTransportCacheEntries[];
            };

            layout(binding = 8, rgba16f) readonly uniform image2D previousSpecularHistory;
            layout(binding = 9, rgba16f) readonly uniform image2D previousSpecularSurfaceHistory;

            struct DiffuseRadianceCacheEntry {
                ivec4 key;
                uvec4 payload;
            };

            layout(std430, binding = 10) coherent buffer DiffuseRadianceCacheBuffer {
                uvec4 diffuseRadianceCacheHeader;
                DiffuseRadianceCacheEntry diffuseRadianceCacheEntries[];
            };

            struct SurfaceDirectLightCacheEntry {
                ivec4 key;
                uvec4 metadata;
                uvec4 lightRecords[72];
                uvec4 directionalVisibility;
                uvec4 dependencyVersion;
            };

            layout(std430, binding = 11) coherent buffer SurfaceDirectLightCacheBuffer {
                uvec4 surfaceDirectLightCacheHeader;
                SurfaceDirectLightCacheEntry surfaceDirectLightCacheEntries[];
            };

            struct GpuSectionLight {
                ivec4 posRadiusFlags;
                uvec4 colorEmission;
            };

            layout(std430, binding = 12) readonly buffer SectionLightTableBuffer {
                uvec4 sectionLightHeader;
                GpuSectionLight sectionLightRecords[];
            };

            layout(push_constant) uniform PushConstants {
                uint sampleStep;
                uint frameIndex;
                uint renderWidth;
                uint renderHeight;
                uint allowScreenHistory;
                uint padding;
                float sunDirX;
                float sunDirY;
                float sunDirZ;
                uint sunPadding;
            } pc;

            const int FAMILY_DIFFUSE_RADIANCE = 1;
            const int FAMILY_REFLECTION = 2;
            const int FAMILY_REFRACTION = 3;
            const int FAMILY_SURFACE_DIRECT_LIGHT = 4;
            const int SOURCE_VISIBLE_GBUFFER = 1;
            const int SOURCE_REFLECTION_SURFACE = 3;
            const int SOURCE_REFRACTION_SURFACE = 4;
            const int DIFFUSE_INCIDENT_RADIANCE_BUCKET = 0;
            const bool ENABLE_CAMERA_SURFACE_LIGHT_REQUESTS = false;
            const bool ENABLE_CAMERA_DIFFUSE_REQUESTS = false;

            const float BLOCK_ID_WATER = 1000.0;
            const float BLOCK_ID_GLASS = 1001.0;
            const float BLOCK_ID_ICE = 1012.0;
            const float BLOCK_ID_CRYSTAL = 1103.0;

            bool finiteVec4(vec4 value) {
                return !any(isnan(value)) && !any(isinf(value));
            }

            bool validHybridSample(vec4 gPos, vec4 gNormalRaw, vec4 gAlbedoRaw, vec4 gMaterialRaw, vec4 gExtraRaw) {
                if (!finiteVec4(gPos) || !finiteVec4(gNormalRaw) || !finiteVec4(gAlbedoRaw)
                        || !finiteVec4(gMaterialRaw) || !finiteVec4(gExtraRaw)) {
                    return false;
                }
                float normalLen2 = dot(gNormalRaw.xyz, gNormalRaw.xyz);
                bool hasPosition = gPos.w > 0.0 || dot(gPos.xyz, gPos.xyz) > 0.000001;
                bool hasNormal = normalLen2 > 0.25 && normalLen2 < 4.0;
                bool hasMaterial = gMaterialRaw.a >= 0.0 && gMaterialRaw.a <= 1.5;
                return hasPosition && hasNormal && hasMaterial;
            }

            float decodeGbufferBlockId(float materialTag) {
                return materialTag >= 999.5 ? floor(materialTag + 0.5) : 0.0;
            }

            bool isBlockId(float blockId, float expected) {
                return abs(blockId - expected) < 0.5;
            }

            bool isRefractiveBlock(float blockId) {
                return isBlockId(blockId, BLOCK_ID_WATER)
                    || isBlockId(blockId, BLOCK_ID_GLASS)
                    || isBlockId(blockId, BLOCK_ID_ICE)
                    || isBlockId(blockId, BLOCK_ID_CRYSTAL);
            }

            int mediumBucket(float blockId) {
                if (isBlockId(blockId, BLOCK_ID_WATER)) return 1;
                if (isBlockId(blockId, BLOCK_ID_GLASS)) return 2;
                if (isBlockId(blockId, BLOCK_ID_ICE)) return 3;
                if (isBlockId(blockId, BLOCK_ID_CRYSTAL)) return 4;
                return 0;
            }

            int axisBucket(vec3 direction) {
                vec3 a = abs(direction);
                if (a.x >= a.y && a.x >= a.z) {
                    return direction.x >= 0.0 ? 0 : 1;
                }
                if (a.y >= a.z) {
                    return direction.y >= 0.0 ? 2 : 3;
                }
                return direction.z >= 0.0 ? 4 : 5;
            }

            vec3 axisBucketNormal(int bucket) {
                if (bucket == 0) return vec3(1.0, 0.0, 0.0);
                if (bucket == 1) return vec3(-1.0, 0.0, 0.0);
                if (bucket == 2) return vec3(0.0, 1.0, 0.0);
                if (bucket == 3) return vec3(0.0, -1.0, 0.0);
                if (bucket == 4) return vec3(0.0, 0.0, 1.0);
                return vec3(0.0, 0.0, -1.0);
            }

            bool hasEncodedSurfaceFace(float encodedFace) {
                return encodedFace <= -0.5 && encodedFace >= -6.5;
            }

            int decodeSurfaceFaceBucket(float encodedFace) {
                return clamp(int(round(-encodedFace)) - 1, 0, 5);
            }

            vec2 signNotZero(vec2 value) {
                return vec2(value.x >= 0.0 ? 1.0 : -1.0, value.y >= 0.0 ? 1.0 : -1.0);
            }

            int directionBucket(vec3 direction) {
                direction = normalize(direction);
                vec2 oct = direction.xy / max(abs(direction.x) + abs(direction.y) + abs(direction.z), 1e-6);
                if (direction.z < 0.0) {
                    oct = (vec2(1.0) - abs(oct.yx)) * signNotZero(oct);
                }
                ivec2 quantized = clamp(ivec2(round((oct * 0.5 + 0.5) * 7.0)), ivec2(0), ivec2(7));
                return quantized.x | (quantized.y << 3);
            }

            int materialBucket(float metallic, float roughness, float blockId, float surfaceAlpha) {
                int bucket = int(round(clamp(roughness, 0.0, 1.0) * 31.0));
                if (metallic > 0.5) bucket |= 32;
                if (blockId > 0.0) bucket |= 64;
                if (surfaceAlpha < 0.98 && !isBlockId(blockId, 1007.0)) bucket |= 128;
                return clamp(bucket, 0, 255);
            }

            uint hashMix(uint value) {
                value ^= value >> 16u;
                value *= 0x7feb352du;
                value ^= value >> 15u;
                value *= 0x846ca68bu;
                return value ^ (value >> 16u);
            }

            uint specularTransportHash(ivec4 key, uint family, uint detail) {
                uint value = uint(key.x) * 0x9e3779b9u;
                value ^= uint(key.y) * 0x85ebca6bu;
                value ^= uint(key.z) * 0xc2b2ae35u;
                value ^= uint(key.w) * 0x27d4eb2du;
                value ^= family * 0x165667b1u;
                value ^= detail * 0xd3a2646cu;
                return hashMix(value);
            }

            uint diffuseRadianceHash(ivec4 key) {
                uint value = uint(key.x) * 0x9e3779b9u;
                value ^= uint(key.y) * 0x85ebca6bu;
                value ^= uint(key.z) * 0xc2b2ae35u;
                value ^= uint(key.w) * 0x27d4eb2du;
                return hashMix(value);
            }

            uint surfaceDirectLightHash(ivec4 key) {
                uint value = uint(key.x) * 0x9e3779b9u;
                value ^= uint(key.y) * 0x85ebca6bu;
                value ^= uint(key.z) * 0xc2b2ae35u;
                value ^= uint(key.w) * 0x27d4eb2du;
                return hashMix(value ^ 0x6d2b79f5u);
            }

            uint sectionDirectoryHash(ivec3 sectionCoord) {
                uint value = uint(sectionCoord.x) * 0x8da6b343u
                    ^ uint(sectionCoord.y) * 0xd8163841u
                    ^ uint(sectionCoord.z) * 0xcb1ab31fu;
                value ^= value >> 16u;
                value *= 0x7feb352du;
                return value ^ (value >> 15u);
            }

            bool surfaceDependencyVersion(vec3 worldPos, out uvec2 version) {
                version = uvec2(0u);
                int recordCount = int(sectionLightHeader.w);
                if (recordCount <= 0) return false;
                ivec3 sectionOrigin = ivec3(floor(worldPos / 16.0)) * 16;
                ivec3 sectionCoord = sectionOrigin / 16;
                uint mask = uint(recordCount - 1);
                uint hash = sectionDirectoryHash(sectionCoord);
                int directoryBase = int(sectionLightHeader.x);
                for (int probe = 0; probe < 64; probe++) {
                    if (probe >= recordCount) break;
                    int index = int((hash + uint(probe)) & mask);
                    GpuSectionLight record = sectionLightRecords[directoryBase + index];
                    if (record.colorEmission.w == 0u) return false;
                    if (all(equal(record.posRadiusFlags.xyz, sectionOrigin))) {
                        version = record.colorEmission.yz;
                        return true;
                    }
                }
                return false;
            }

            vec3 cacheSunDirection() {
                vec3 sunDirWorld = vec3(pc.sunDirX, pc.sunDirY, pc.sunDirZ);
                if (dot(sunDirWorld, sunDirWorld) < 0.000001) {
                    sunDirWorld = vec3(0.5, 1.0, 0.2);
                }
                sunDirWorld = normalize(sunDirWorld);
                return sunDirWorld;
            }

            uint cacheSunSignature(vec3 direction) {
                ivec3 quantized = ivec3(round(normalize(direction) * 32.0));
                uint value = uint(quantized.x) * 0x9e3779b9u;
                value ^= uint(quantized.y) * 0x85ebca6bu;
                value ^= uint(quantized.z) * 0xc2b2ae35u;
                return hashMix(value ^ 0x51ed270bu);
            }

            bool hasDiffuseRadiance(ivec4 key) {
                uint entryCount = diffuseRadianceCacheHeader.x;
                uint probeCount = min(diffuseRadianceCacheHeader.z, 8u);
                if (entryCount == 0u || probeCount == 0u) return false;
                uint readyState = 0x80000000u | (diffuseRadianceCacheHeader.y & 0x3fffffffu);
                uint slot = diffuseRadianceHash(key) % entryCount;
                for (uint probe = 0u; probe < 8u; probe++) {
                    if (probe >= probeCount) break;
                    uint index = (slot + probe) % entryCount;
                    DiffuseRadianceCacheEntry entry = diffuseRadianceCacheEntries[index];
                    if (entry.payload.y == readyState && all(equal(entry.key, key))) {
                        atomicMax(diffuseRadianceCacheEntries[index].payload.z, pc.frameIndex);
                        return true;
                    }
                }
                return false;
            }

            bool hasSpecularTransport(ivec4 key, uint family, uint detail) {
                uint entryCount = specularTransportCacheHeader.x;
                uint probeCount = min(specularTransportCacheHeader.z, 8u);
                if (entryCount == 0u || probeCount == 0u) return false;
                uint readyState = 0x80000000u | (specularTransportCacheHeader.y & 0x3fffffffu);
                uint slot = specularTransportHash(key, family, detail) % entryCount;
                for (uint probe = 0u; probe < 8u; probe++) {
                    if (probe >= probeCount) break;
                    uint index = (slot + probe) % entryCount;
                    SpecularTransportCacheEntry entry = specularTransportCacheEntries[index];
                    if (entry.metadata.w == readyState
                            && entry.metadata.x == family
                            && entry.metadata.y == detail
                            && all(equal(entry.key, key))) {
                        atomicMax(specularTransportCacheEntries[index].metadata.z, pc.frameIndex);
                        return true;
                    }
                }
                return false;
            }

            bool hasSurfaceDirectLight(
                ivec4 key,
                bool requireLocal,
                bool requireDirectional,
                uint sunSignature,
                uvec2 dependencyVersion
            ) {
                uint entryCount = surfaceDirectLightCacheHeader.x;
                uint probeCount = min(surfaceDirectLightCacheHeader.z, 8u);
                if (entryCount == 0u || probeCount == 0u) return false;
                uint readyState = 0x80000000u | (surfaceDirectLightCacheHeader.y & 0x1fffffffu);
                uint slot = surfaceDirectLightHash(key) % entryCount;
                for (uint probe = 0u; probe < 8u; probe++) {
                    if (probe >= probeCount) break;
                    uint index = (slot + probe) % entryCount;
                    SurfaceDirectLightCacheEntry entry = surfaceDirectLightCacheEntries[index];
                    bool localReady = !requireLocal || entry.metadata.y == readyState;
                    bool directionalReady = !requireDirectional
                        || (entry.directionalVisibility.y == readyState
                            && entry.directionalVisibility.z == sunSignature);
                    bool dependencyReady = all(equal(
                        entry.dependencyVersion.xy, dependencyVersion));
                    if (localReady && directionalReady && dependencyReady
                            && all(equal(entry.key, key))) {
                        atomicMax(surfaceDirectLightCacheEntries[index].metadata.z, pc.frameIndex);
                        return true;
                    }
                }
                return false;
            }

            vec3 decodeHistoryNormal(vec2 encoded) {
                vec2 oct = encoded * 2.0 - 1.0;
                vec3 normal = vec3(oct, 1.0 - abs(oct.x) - abs(oct.y));
                if (normal.z < 0.0) {
                    normal.xy = (vec2(1.0) - abs(normal.yx)) * signNotZero(normal.xy);
                }
                return normalize(normal);
            }

            bool validSpecularScreenHistory(
                ivec2 previousPixel,
                vec3 normal,
                float linearDepth,
                uint keySignature
            ) {
                if (pc.allowScreenHistory == 0u
                        || any(lessThan(previousPixel, ivec2(0)))
                        || any(greaterThanEqual(previousPixel, imageSize(previousSpecularHistory)))) {
                    return false;
                }
                vec4 history = imageLoad(previousSpecularHistory, previousPixel);
                vec4 surface = imageLoad(previousSpecularSurfaceHistory, previousPixel);
                if (!finiteVec4(history) || !finiteVec4(surface)
                        || surface.z <= 0.0
                        || history.a <= 0.0
                        || history.a > 10000.0
                        || uint(round(surface.w)) != (keySignature & 0x000007ffu)) {
                    return false;
                }
                float depthTolerance = max(0.25, linearDepth * 0.025);
                return abs(surface.z - linearDepth) <= depthTolerance
                    && dot(decodeHistoryNormal(surface.xy), normal) >= 0.96;
            }

            void emitRequest(
                int family,
                int source,
                ivec3 cell,
                int normalBucket,
                int materialBucket,
                int roughnessBucket,
                int viewOrMediumBucket,
                int priorityHint,
                float visibility,
                float error,
                float luma,
                float distanceSquared,
                vec3 samplePosition
            ) {
                uint slot = atomicAdd(feedback.feedbackCount, 1u);
                if (slot >= feedback.feedbackCapacity) {
                    atomicAdd(feedback.feedbackDropped, 1u);
                    return;
                }

                feedback.records[slot].family = family;
                feedback.records[slot].source = source;
                feedback.records[slot].cellX = cell.x;
                feedback.records[slot].cellY = cell.y;
                feedback.records[slot].cellZ = cell.z;
                feedback.records[slot].normalBucket = normalBucket;
                feedback.records[slot].materialBucket = materialBucket;
                feedback.records[slot].roughnessBucket = roughnessBucket;
                feedback.records[slot].viewOrMediumBucket = viewOrMediumBucket;
                feedback.records[slot].priorityHint = priorityHint;
                feedback.records[slot].visibility = clamp(visibility, 0.0, 1.0);
                feedback.records[slot].error = clamp(error, 0.0, 1.0);
                feedback.records[slot].luma = max(luma, 0.0);
                feedback.records[slot].distanceSquared = max(distanceSquared, 0.0);
                feedback.records[slot].sampleX = samplePosition.x;
                feedback.records[slot].sampleY = samplePosition.y;
                feedback.records[slot].sampleZ = samplePosition.z;
                feedback.records[slot].samplePadding = 0.0;
            }

            void main() {
                uint sampleStep = max(pc.sampleStep, 1u);
                ivec2 sampleGrid = ivec2(gl_GlobalInvocationID.xy);
                ivec2 renderSize = ivec2(int(max(pc.renderWidth, 1u)), int(max(pc.renderHeight, 1u)));
                ivec2 pixel = sampleGrid * int(sampleStep);
                pixel += ivec2(
                    int((pc.frameIndex * 13u) % sampleStep),
                    int((pc.frameIndex * 29u) % sampleStep));
                if (pixel.x >= renderSize.x || pixel.y >= renderSize.y) {
                    return;
                }

                // Feedback dispatch follows the RTX render resolution, but the
                // Iris attachments remain output-sized. Preserve screen-space
                // correspondence when those resolutions differ.
                vec2 gbufferUv = (vec2(pixel) + vec2(0.5)) / vec2(renderSize);
                ivec2 gPosPixel = clamp(ivec2(gbufferUv * vec2(textureSize(gbufferWorldPos, 0))),
                    ivec2(0), textureSize(gbufferWorldPos, 0) - 1);
                vec4 gPos = texelFetch(gbufferWorldPos, gPosPixel, 0);
                vec4 gNormalRaw = texelFetch(gbufferNormal,
                    clamp(ivec2(gbufferUv * vec2(textureSize(gbufferNormal, 0))), ivec2(0), textureSize(gbufferNormal, 0) - 1), 0);
                vec4 gAlbedoRaw = texelFetch(gbufferAlbedo,
                    clamp(ivec2(gbufferUv * vec2(textureSize(gbufferAlbedo, 0))), ivec2(0), textureSize(gbufferAlbedo, 0) - 1), 0);
                vec4 gMaterialRaw = texelFetch(gbufferMaterial,
                    clamp(ivec2(gbufferUv * vec2(textureSize(gbufferMaterial, 0))), ivec2(0), textureSize(gbufferMaterial, 0) - 1), 0);
                vec4 gExtraRaw = texelFetch(gbufferExtra,
                    clamp(ivec2(gbufferUv * vec2(textureSize(gbufferExtra, 0))), ivec2(0), textureSize(gbufferExtra, 0) - 1), 0);
                atomicAdd(feedback.sampledCount, 1u);
                bool finite = finiteVec4(gPos) && finiteVec4(gNormalRaw) && finiteVec4(gAlbedoRaw)
                    && finiteVec4(gMaterialRaw) && finiteVec4(gExtraRaw);
                float normalLen2 = dot(gNormalRaw.xyz, gNormalRaw.xyz);
                bool hasPosition = finite && (gPos.w > 0.0 || dot(gPos.xyz, gPos.xyz) > 0.000001);
                bool hasNormal = finite && normalLen2 > 0.25 && normalLen2 < 4.0;
                bool hasMaterial = finite && gMaterialRaw.a >= 0.0 && gMaterialRaw.a <= 1.5;
                if (finite) atomicAdd(feedback.finiteCount, 1u);
                if (hasPosition) atomicAdd(feedback.positionCount, 1u);
                if (hasNormal) atomicAdd(feedback.normalCount, 1u);
                if (hasMaterial) atomicAdd(feedback.materialCount, 1u);
                if (finite && any(greaterThan(abs(gAlbedoRaw), vec4(0.000001)))) {
                    atomicAdd(feedback.albedoNonzeroCount, 1u);
                }
                if (finite && any(greaterThan(abs(gExtraRaw), vec4(0.000001)))) {
                    atomicAdd(feedback.extraNonzeroCount, 1u);
                }
                if (!finite || !hasPosition || !hasNormal || !hasMaterial) {
                    return;
                }
                atomicAdd(feedback.validCount, 1u);

                vec3 normal = normalize(gNormalRaw.xyz);
                vec3 albedo = max(gAlbedoRaw.rgb, vec3(0.0));
                vec3 f0 = clamp(gMaterialRaw.rgb, vec3(0.0), vec3(1.0));
                float roughness = clamp(gMaterialRaw.a, 0.04, 1.0);
                float blockId = decodeGbufferBlockId(gPos.w);
                float metallic = blockId > 0.0 ? 0.0 : clamp(gPos.w, 0.0, 1.0);
                float blocklight = clamp(gExtraRaw.r, 0.0, 1.0);
                float skylight = clamp(gExtraRaw.g, 0.0, 1.0);
                float ao = clamp(gExtraRaw.b, 0.0, 1.0);
                float luma = dot(albedo, vec3(0.2126, 0.7152, 0.0722))
                    * (0.25 + blocklight + skylight * 0.35)
                    * (0.35 + ao * 0.65);
                float distanceSquared = dot(gPos.xyz, gPos.xyz);

                vec3 origin = cam.viewInverse[3].xyz;
                vec3 absWorldPos = gPos.xyz + origin;
                vec4 previousClip = cam.prevViewProj * vec4(absWorldPos, 1.0);
                ivec2 previousPixel = ivec2(-1);
                if (previousClip.w > 1e-5 && !any(isnan(previousClip)) && !any(isinf(previousClip))) {
                    vec2 previousNdc = previousClip.xy / previousClip.w;
                    if (all(lessThanEqual(abs(previousNdc), vec2(1.0)))) {
                        previousPixel = ivec2((previousNdc * 0.5 + 0.5) * vec2(renderSize));
                    }
                }
                vec3 cameraForward = -normalize(cam.viewInverse[2].xyz);
                float linearDepth = max(dot(gPos.xyz, cameraForward), 0.0);
                ivec3 transportCell = ivec3(floor(absWorldPos));
                int diffuseNormalBucket = axisBucket(normal);
                vec3 diffuseFaceNormal = axisBucketNormal(diffuseNormalBucket);
                int transportNormalBucket = directionBucket(normal);
                int roughnessBucket = int(round(roughness * 15.0));
                int materialBucketValue = materialBucket(metallic, roughness, blockId, gAlbedoRaw.a);
                ivec4 transportKey = ivec4(
                    transportCell,
                    transportNormalBucket | (roughnessBucket << 8) | (materialBucketValue << 16));
                int visiblePriority = luma > 0.45 || blocklight > 0.65 ? 1 : 0;
                bool hasSurfaceFace = hasEncodedSurfaceFace(gNormalRaw.a);
                int surfaceDirectVariant = hasSurfaceFace
                    ? decodeSurfaceFaceBucket(gNormalRaw.a)
                    : diffuseNormalBucket;
                vec3 surfaceFaceNormal = axisBucketNormal(surfaceDirectVariant);
                // Identify the owning solid block, not whichever side of the
                // mathematical face floating-point interpolation lands on.
                ivec3 surfaceDirectCell = ivec3(floor(
                    absWorldPos - surfaceFaceNormal * 0.01));
                ivec4 surfaceDirectKey = ivec4(surfaceDirectCell, surfaceDirectVariant);
                bool localVisibilityRequired = hasSurfaceFace && blocklight > 0.015;
                bool directionalVisibilityRequired = hasSurfaceFace && skylight > 0.015;
                bool surfaceDirectRequired = ENABLE_CAMERA_SURFACE_LIGHT_REQUESTS
                    && (localVisibilityRequired || directionalVisibilityRequired);
                bool surfaceDirectHit = !surfaceDirectRequired;
                if (surfaceDirectRequired) {
                    uint sunSignature = cacheSunSignature(cacheSunDirection());
                    uvec2 dependencyVersion;
                    bool dependencyKnown = surfaceDependencyVersion(
                        vec3(surfaceDirectCell) + vec3(0.5), dependencyVersion);
                    surfaceDirectHit = dependencyKnown
                        && hasSurfaceDirectLight(
                            surfaceDirectKey,
                            localVisibilityRequired,
                            directionalVisibilityRequired,
                            sunSignature,
                            dependencyVersion);
                    atomicAdd(feedback.cacheQueryCount, 1u);
                    if (surfaceDirectHit) {
                        atomicAdd(feedback.cacheHitCount, 1u);
                    } else {
                        emitRequest(
                            FAMILY_SURFACE_DIRECT_LIGHT,
                            SOURCE_VISIBLE_GBUFFER,
                            surfaceDirectCell,
                            surfaceDirectVariant,
                            0,
                            0,
                            0,
                            max(blocklight, skylight) > 0.35 ? 2 : visiblePriority,
                            1.0,
                            0.65 + 0.25 * max(blocklight, skylight),
                            max(luma, max(blocklight, skylight)),
                            distanceSquared,
                            absWorldPos);
                    }
                }
                bool fallbackPixel = !surfaceDirectHit;
                vec3 viewDir = normalize(origin - absWorldPos);
                float viewNoV = clamp(dot(normal, viewDir), 0.0, 1.0);
                float fresnel = pow(1.0 - viewNoV, 5.0);
                float f0Luma = dot(f0, vec3(0.2126, 0.7152, 0.0722));
                float surfaceFresnelLuma = mix(f0Luma, 1.0, fresnel);
                bool thinTransparentSurface = gAlbedoRaw.a < 0.98
                    && !isBlockId(blockId, 1007.0)
                    && !isRefractiveBlock(blockId);
                bool refractiveSurface = isRefractiveBlock(blockId) || thinTransparentSurface;
                bool reflectiveSurface = !refractiveSurface
                    && (metallic > 0.5 || surfaceFresnelLuma > 0.025)
                    && roughness < mix(0.62, 0.88, metallic);
                if (reflectiveSurface) {
                    vec3 reflectionDir = reflect(-viewDir, normal);
                    int transportDirection = directionBucket(reflectionDir);
                    uint transportSignature = specularTransportHash(
                        transportKey, 0u, uint(transportDirection));
                    bool historyHit = validSpecularScreenHistory(
                        previousPixel, normal, linearDepth, transportSignature);
                    bool transportHit = historyHit
                        || hasSpecularTransport(transportKey, 0u, uint(transportDirection));
                    atomicAdd(feedback.cacheQueryCount, 1u);
                    if (transportHit) {
                        atomicAdd(feedback.cacheHitCount, 1u);
                    } else {
                        fallbackPixel = true;
                        emitRequest(
                            FAMILY_REFLECTION,
                            SOURCE_REFLECTION_SURFACE,
                            transportCell,
                            transportNormalBucket,
                            materialBucketValue,
                            roughnessBucket,
                            transportDirection,
                            roughness < 0.35 || metallic > 0.5 ? 2 : 1,
                            clamp(surfaceFresnelLuma * 8.0 + metallic * 0.35, 0.0, 1.0),
                            clamp(0.25 + (1.0 - roughness) * 0.70, 0.0, 1.0),
                            max(luma, f0Luma * 4.0),
                            distanceSquared,
                            absWorldPos);
                    }
                }

                if (refractiveSurface) {
                    vec3 incident = -viewDir;
                    vec3 reflectionDir = normalize(reflect(incident, normal));
                    float ior = isBlockId(blockId, BLOCK_ID_WATER)
                        ? 1.333
                        : (isBlockId(blockId, BLOCK_ID_ICE)
                            ? 1.31
                            : (isBlockId(blockId, BLOCK_ID_CRYSTAL) ? 2.20 : 1.50));
                    vec3 refractionDir = refract(incident, normal, 1.0 / ior);
                    if (dot(refractionDir, refractionDir) <= 1e-6) {
                        refractionDir = reflectionDir;
                    }
                    int transportDirections = mediumBucket(blockId)
                        | (directionBucket(reflectionDir) << 8)
                        | (directionBucket(normalize(refractionDir)) << 16);
                    uint transportSignature = specularTransportHash(
                        transportKey, 1u, uint(transportDirections));
                    bool historyHit = validSpecularScreenHistory(
                        previousPixel, normal, linearDepth, transportSignature);
                    bool transportHit = historyHit
                        || hasSpecularTransport(transportKey, 1u, uint(transportDirections));
                    atomicAdd(feedback.cacheQueryCount, 1u);
                    if (transportHit) {
                        atomicAdd(feedback.cacheHitCount, 1u);
                    } else {
                        fallbackPixel = true;
                        emitRequest(
                            FAMILY_REFRACTION,
                            SOURCE_REFRACTION_SURFACE,
                            transportCell,
                            transportNormalBucket,
                            materialBucketValue,
                            roughnessBucket,
                            transportDirections,
                            2,
                            1.0,
                            0.85,
                            max(luma, 0.35),
                            distanceSquared,
                            absWorldPos);
                    }
                }

                // Diffuse entries are keyed by world-space surface cell and
                // dominant normal, so discovery may be view-driven without the
                // cached lighting becoming view-dependent. Without these
                // requests CACHE_ON_HIT never acquires indirect RTX radiance.
                if (ENABLE_CAMERA_DIFFUSE_REQUESTS) {
                    ivec3 diffuseCell = ivec3(floor(
                        absWorldPos - diffuseFaceNormal * 0.01));
                    ivec4 diffuseKey = ivec4(diffuseCell, diffuseNormalBucket);
                    bool diffuseHit = hasDiffuseRadiance(diffuseKey);
                    atomicAdd(feedback.cacheQueryCount, 1u);
                    if (diffuseHit) {
                        atomicAdd(feedback.cacheHitCount, 1u);
                    } else {
                        fallbackPixel = true;
                        emitRequest(
                            FAMILY_DIFFUSE_RADIANCE,
                            SOURCE_VISIBLE_GBUFFER,
                            diffuseCell,
                            diffuseNormalBucket,
                            DIFFUSE_INCIDENT_RADIANCE_BUCKET,
                            0,
                            0,
                            max(blocklight, skylight) > 0.35 ? 2 : visiblePriority,
                            1.0,
                            0.75,
                            max(luma, 0.08 + skylight * 0.12),
                            distanceSquared,
                            absWorldPos);
                    }
                }
                if (fallbackPixel) {
                    atomicAdd(feedback.fallbackPixelCount, 1u);
                }
            }
            """;
}
