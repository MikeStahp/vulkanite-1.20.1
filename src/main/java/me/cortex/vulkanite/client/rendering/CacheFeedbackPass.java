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
import me.cortex.vulkanite.lib.other.VImageView;
import me.cortex.vulkanite.lib.other.VSampler;
import me.cortex.vulkanite.lib.pipeline.ComputePipelineBuilder;
import me.cortex.vulkanite.lib.pipeline.VComputePipeline;
import me.cortex.vulkanite.lib.shader.ShaderModule;
import me.cortex.vulkanite.lib.shader.VShader;
import me.cortex.vulkanite.lib.shader.reflection.ShaderReflection;
import org.lwjgl.system.MemoryUtil;

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
    private static final int LOCAL_SIZE_X = 8;
    private static final int LOCAL_SIZE_Y = 8;
    private static final int PUSH_CONSTANT_BYTES = 16;
    private static final int MAX_FEEDBACK_RECORDS = 4096;
    private static final int HEADER_BYTES = 16;
    private static final int RECORD_BYTES = 56;
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
    private final int[] pushConstants = new int[4];
    private FeedbackSlot encodedSlot;
    private long feedbackEpoch = 1L;

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

    boolean execute(RtxPassGraph.Frame frame) {
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
            updater.apply();

            pushConstants[0] = sampleStep;
            pushConstants[1] = frame.frameIndex();
            pushConstants[2] = frame.renderWidth();
            pushConstants[3] = frame.renderHeight();

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
        for (FeedbackSlot slot : feedbackSlots) {
            if (slot.state == FeedbackState.READY) {
                slot.state = FeedbackState.FREE;
                slot.execution = 0L;
            }
        }
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
                    cellX, cellY, cellZ, normalBucket, roughnessBucket, viewOrMediumBucket, materialBucket);
            case SECTION_PROBE_CELL -> null;
        };
        if (key == null) {
            return null;
        }
        return new CacheRequest(key, source, feedbackFrame, priorityHint, visibility, error, luma, distanceSquared);
    }

    private CacheRequestSource sourceFromOrdinal(int sourceOrdinal) {
        if (sourceOrdinal < 0 || sourceOrdinal >= CacheRequestSource.values().length) {
            return CacheRequestSource.GPU_FEEDBACK;
        }
        return CacheRequestSource.values()[sourceOrdinal];
    }

    private void bindGbuffer(DescriptorUpdateBuilder updater, RtxPassGraph.Frame frame, int binding, int index) {
        updater.imageSampler(binding, frame.gbufferViews()[index], sampler);
    }

    private boolean missingGbuffer(RtxPassGraph.Frame frame) {
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
                        | (Integer.toUnsignedLong(pushConstants[3]) << 32)
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
            };

            layout(std430, binding = 6) buffer CacheFeedback {
                uint feedbackCount;
                uint feedbackFrame;
                uint feedbackCapacity;
                uint feedbackDropped;
                FeedbackRecord records[];
            } feedback;

            layout(push_constant) uniform PushConstants {
                uint sampleStep;
                uint frameIndex;
                uint renderWidth;
                uint renderHeight;
            } pc;

            const int FAMILY_DIFFUSE_RADIANCE = 1;
            const int FAMILY_REFLECTION = 2;
            const int FAMILY_REFRACTION = 3;
            const int SOURCE_VISIBLE_GBUFFER = 1;
            const int SOURCE_REFLECTION_SURFACE = 3;
            const int SOURCE_REFRACTION_SURFACE = 4;
            const int DIFFUSE_INCIDENT_RADIANCE_BUCKET = 0;

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

            int materialBucket(float metallic, float roughness, float blockId) {
                int bucket = int(round(clamp(roughness, 0.0, 1.0) * 31.0));
                if (metallic > 0.5) bucket |= 32;
                if (blockId > 0.0) bucket |= 64;
                return clamp(bucket, 0, 255);
            }

            ivec3 cacheCell(vec3 absWorldPos) {
                return ivec3(floor(absWorldPos * 0.5));
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
                float distanceSquared
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

                ivec2 gbufferMax = textureSize(gbufferWorldPos, 0) - 1;
                pixel = clamp(pixel, ivec2(0), gbufferMax);
                vec4 gPos = texelFetch(gbufferWorldPos, pixel, 0);
                vec4 gNormalRaw = texelFetch(gbufferNormal, clamp(pixel, ivec2(0), textureSize(gbufferNormal, 0) - 1), 0);
                vec4 gAlbedoRaw = texelFetch(gbufferAlbedo, clamp(pixel, ivec2(0), textureSize(gbufferAlbedo, 0) - 1), 0);
                vec4 gMaterialRaw = texelFetch(gbufferMaterial, clamp(pixel, ivec2(0), textureSize(gbufferMaterial, 0) - 1), 0);
                vec4 gExtraRaw = texelFetch(gbufferExtra, clamp(pixel, ivec2(0), textureSize(gbufferExtra, 0) - 1), 0);
                if (!validHybridSample(gPos, gNormalRaw, gAlbedoRaw, gMaterialRaw, gExtraRaw)) {
                    return;
                }

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
                ivec3 cell = cacheCell(absWorldPos);
                int normalBucketValue = axisBucket(normal);
                int roughnessBucket = int(round(roughness * 15.0));
                int materialBucketValue = materialBucket(metallic, roughness, blockId);
                int visiblePriority = luma > 0.45 || blocklight > 0.65 ? 1 : 0;

                emitRequest(
                    FAMILY_DIFFUSE_RADIANCE,
                    SOURCE_VISIBLE_GBUFFER,
                    cell,
                    normalBucketValue,
                    DIFFUSE_INCIDENT_RADIANCE_BUCKET,
                    roughnessBucket,
                    0,
                    visiblePriority,
                    1.0,
                    0.50 + 0.20 * blocklight,
                    luma,
                    distanceSquared);

                vec3 viewDir = normalize(origin - absWorldPos);
                float viewNoV = clamp(dot(normal, viewDir), 0.0, 1.0);
                float fresnel = pow(1.0 - viewNoV, 5.0);
                float f0Luma = dot(f0, vec3(0.2126, 0.7152, 0.0722));
                float surfaceFresnelLuma = mix(f0Luma, 1.0, fresnel);
                bool reflectiveSurface = !isRefractiveBlock(blockId)
                    && roughness < mix(0.82, 0.88, metallic)
                    && (metallic > 0.5 || surfaceFresnelLuma > 0.025);
                if (reflectiveSurface) {
                    vec3 reflectionDir = reflect(-viewDir, normal);
                    emitRequest(
                        FAMILY_REFLECTION,
                        SOURCE_REFLECTION_SURFACE,
                        cell,
                        normalBucketValue,
                        materialBucketValue,
                        roughnessBucket,
                        axisBucket(reflectionDir),
                        roughness < 0.35 || metallic > 0.5 ? 2 : 1,
                        clamp(surfaceFresnelLuma * 8.0 + metallic * 0.35, 0.0, 1.0),
                        clamp(0.25 + (1.0 - roughness) * 0.70, 0.0, 1.0),
                        max(luma, f0Luma * 4.0),
                        distanceSquared);
                }

                if (isRefractiveBlock(blockId)) {
                    emitRequest(
                        FAMILY_REFRACTION,
                        SOURCE_REFRACTION_SURFACE,
                        cell,
                        normalBucketValue,
                        materialBucketValue,
                        roughnessBucket,
                        mediumBucket(blockId),
                        2,
                        1.0,
                        0.85,
                        max(luma, 0.35),
                        distanceSquared);
                }
            }
            """;
}
