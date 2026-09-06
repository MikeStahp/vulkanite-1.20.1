package me.cortex.vulkanite.client.rendering;

import me.cortex.vulkanite.acceleration.HybridAccelerationConfig;
import me.cortex.vulkanite.acceleration.AccelerationManager;
import me.cortex.vulkanite.client.Vulkanite;
import me.cortex.vulkanite.client.config.DLSSConfig;
import me.cortex.vulkanite.client.config.VulkaniteConfig;
import me.cortex.vulkanite.client.config.VulkaniteConfig.RtxCacheMode;
import me.cortex.vulkanite.client.rendering.cache.CacheInvalidationTracker;
import me.cortex.vulkanite.client.rendering.cache.CacheRequestBatch;
import me.cortex.vulkanite.client.rendering.cache.CacheRequestFamily;
import me.cortex.vulkanite.client.rendering.cache.CacheRequestQueue;
import me.cortex.vulkanite.client.rendering.cache.CacheRequestStats;
import me.cortex.vulkanite.client.rendering.cache.DiffuseRadianceCache;
import me.cortex.vulkanite.client.rendering.cache.SpecularTransportCache;
import me.cortex.vulkanite.client.rendering.cache.SurfaceDirectLightCache;
import me.cortex.vulkanite.compat.IVGImage;
import me.cortex.vulkanite.compat.RaytracingShaderSet;
import me.cortex.vulkanite.lib.base.VContext;
import me.cortex.vulkanite.lib.base.VRef;
import me.cortex.vulkanite.lib.memory.PoolLinearAllocator;
import me.cortex.vulkanite.lib.memory.VAccelerationStructure;
import me.cortex.vulkanite.lib.memory.VBuffer;
import me.cortex.vulkanite.lib.memory.VGImage;
import me.cortex.vulkanite.lib.memory.VImage;
import me.cortex.vulkanite.lib.other.DeviceLostException;
import me.cortex.vulkanite.lib.other.GpuTimestampMath;
import me.cortex.vulkanite.lib.other.VImageView;
import me.cortex.vulkanite.lib.other.VQueryPool;
import me.cortex.vulkanite.lib.other.VSampler;
import me.cortex.vulkanite.lib.other.VUtil;
import me.cortex.vulkanite.lib.other.sync.VSemaphore;
import me.cortex.vulkanite.lib.pipeline.RaytracePipelineBuilder;
import me.cortex.vulkanite.mixin.iris.MixinCelestialUniforms;
import net.irisshaders.iris.gl.buffer.ShaderStorageBuffer;
import net.irisshaders.iris.texture.pbr.PBRTextureHolder;
import net.irisshaders.iris.texture.pbr.PBRTextureManager;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.irisshaders.iris.uniforms.SystemTimeUniforms;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.texture.AbstractTexture;
import net.minecraft.client.texture.SpriteAtlasTexture;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkSectionPos;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static org.lwjgl.util.vma.Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Hybrid RTX renderer.
 *
 * <p>OpenGL/Iris remains the compatibility renderer: it creates the surface
 * records in colortex attachments and consumes the final colortex0 target.
 * Vulkan owns only the RTX overlay: TLAS build, ray passes, temporal images,
 * optional DLSS/RR, and the final blit back into Iris.</p>
 */
public class VulkanPipeline {
    private static final Logger LOGGER = LoggerFactory.getLogger(VulkanPipeline.class);
    private static final Set<VulkanPipeline> ACTIVE_PIPELINES =
            Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));
    private static final int MAX_IRIS_RENDER_TARGETS = 16;
    private static final int UBO_SIZE = 1024;
    private static final int RUNTIME_ENTITY_CAPTURE_CAP = 48;
    private static final long TRANSIENT_CAPTURE_BUDGET_NS = 4_000_000L;
    private static final int MAX_TRANSIENT_CAPTURE_THROTTLE = 8;
    private static final int MIN_ENTITY_CAPTURE_RADIUS = 8;
    private static final int[] EMPTY_GL_SEMAPHORE_IDS = new int[0];
    private static final int MAX_CACHE_REQUEST_BACKLOG = 16_384;
    private static final int MAX_CACHE_REQUESTS_PER_FRAME = 256;
    private static final long FRAME_PATH_LOG_INTERVAL_NANOS = 5_000_000_000L;
    private static final long SHADOW_COMPARISON_LOG_INTERVAL_NANOS = 5_000_000_000L;
    private static final boolean PHASE7_GPU_TIMING = Boolean.getBoolean("vulkanite.phase7GpuTiming");
    private static final int RAY_TIMING_SLOTS = 16;
    private static final int RAY_TIMING_QUERIES_PER_SLOT = 2;
    private static final int RAY_TIMING_START = 0;
    private static final int RAY_TIMING_END = 1;
    private static final int RAY_TIMING_WARMUP_SAMPLES = 30;
    private static final int RAY_TIMING_WINDOW_SAMPLES = 120;

    public record CustomTexture(String name, VRef<VGImage> image) {
    }

    private final VContext ctx;
    private final AccelerationManager accelerationManager;
    private final ArrayList<RtPipeline> raytracePipelines = new ArrayList<>();
    private final SharedImageViewTracker[] irisRenderTargetViews = new SharedImageViewTracker[MAX_IRIS_RENDER_TARGETS];
    private final SharedImageViewTracker[] customTextureViews;
    private final String[] customTextureNames;
    private final List<CustomTexture> customTextures;
    private final SharedImageViewTracker blockAtlasView;
    private final SharedImageViewTracker blockAtlasNormalView;
    private final SharedImageViewTracker blockAtlasSpecularView;
    private final VRef<VSampler> sampler;
    private final VRef<VSampler> customTextureSampler;
    private final VRef<VImage> placeholderSpecular;
    private final VRef<VImageView> placeholderSpecularView;
    private final VRef<VImage> placeholderNormals;
    private final VRef<VImageView> placeholderNormalsView;
    private final PoolLinearAllocator uboAllocator;
    private final RenderPassExecutor renderPassExecutor;
    private final CacheResolvePass cacheResolvePass;
    private final CacheFeedbackPass cacheFeedbackPass;
    private final DiffuseRadianceCache diffuseRadianceCache = new DiffuseRadianceCache();
    private final SpecularTransportCache specularTransportCache = new SpecularTransportCache();
    private final SurfaceDirectLightCache surfaceDirectLightCache = new SurfaceDirectLightCache();
    private final CacheRequestQueue cacheRequestQueue = new CacheRequestQueue(MAX_CACHE_REQUEST_BACKLOG);
    private final RtxFrameImages frameImages = new RtxFrameImages();
    private final DLSSDProcessor dlssdProcessor;
    private final PipelineRequirements pipelineRequirements;
    private final boolean supportsEntities;
    private final boolean supportsProceduralDebug;
    private final boolean supportsHybridShadow;
    private final boolean supportsProceduralReflection;
    private final boolean proceduralReflectionRequested;
    private final VRef<VBuffer> shadowComparisonBuffer;
    private final VRef<VBuffer> shadowComparisonReadbackBuffer;
    private final VRef<VQueryPool> rayTimestampQueryPool;
    private final RayTimingSlot[] rayTimingSlots = new RayTimingSlot[RAY_TIMING_SLOTS];
    private final Map<RayTimingKey, RayTimingWindow> rayTimingWindows = new HashMap<>();
    private final EntityCapture capture = new EntityCapture();

    private String currentShaderpackName;
    private boolean shaderpackInitialized;
    private boolean entityCacheStateActive;
    private boolean entityCaptureInitialized;
    private net.minecraft.world.World lastWorld;
    private net.minecraft.util.math.Vec3d lastCameraPos;
    private boolean dlssTemporalPathActive;
    private long lastDlssFrameTimeNs = -1L;
    private int entityCaptureFrame;
    private int transientCaptureThrottle = 1;
    private RtxCacheMode lastLoggedRtxCacheMode;
    private long lastFrameOrchestrationLogNanos;
    private long referenceRtDispatches;
    private long cacheFillDispatches;
    private long rtDispatchesSkipped;
    private boolean destroyed;
    private boolean proceduralDebugUnavailableLogged;
    private boolean shadowComparisonActive;
    private boolean shadowComparisonReadbackPending;
    private long nextShadowComparisonLogNanos;
    private int rayTimingCursor;
    private long rayTimingSequence;
    private long rayTimingDrops;
    private RayTimingSlot rayTimingAwaitingSubmission;

    public VulkanPipeline(VContext ctx, AccelerationManager accelerationManager, RaytracingShaderSet[] passes,
            int[] ssboIds, List<CustomTexture> customTextures) {
        this.ctx = ctx;
        this.accelerationManager = accelerationManager;
        for (int i = 0; i < rayTimingSlots.length; i++) {
            rayTimingSlots[i] = new RayTimingSlot(i);
        }
        if (PHASE7_GPU_TIMING && ctx.properties.timestampValidBits > 0) {
            rayTimestampQueryPool = VQueryPool.create(
                    ctx.device,
                    RAY_TIMING_SLOTS * RAY_TIMING_QUERIES_PER_SLOT,
                    VK_QUERY_TYPE_TIMESTAMP);
            ctx.setDebugUtilsObjectName(
                    rayTimestampQueryPool.get().pool,
                    VK_OBJECT_TYPE_QUERY_POOL,
                    "Phase 7 Ray Dispatch GPU Timing");
            LOGGER.info("[Vulkanite][Phase7GPU] event=ray_timing_enabled slots={} warmupSamples={} windowSamples={} validBits={} periodNs={}",
                    RAY_TIMING_SLOTS,
                    RAY_TIMING_WARMUP_SAMPLES,
                    RAY_TIMING_WINDOW_SAMPLES,
                    ctx.properties.timestampValidBits,
                    ctx.properties.timestampPeriodNanos);
        } else {
            rayTimestampQueryPool = null;
            if (PHASE7_GPU_TIMING) {
                LOGGER.warn("[Vulkanite][Phase7GPU] event=ray_timing_unavailable reason=no_timestamp_bits");
            }
        }

        for (int i = 0; i < irisRenderTargetViews.length; i++) {
            irisRenderTargetViews[i] = new SharedImageViewTracker(ctx, null);
        }

        this.customTextures = List.copyOf(customTextures);
        this.customTextureViews = new SharedImageViewTracker[this.customTextures.size()];
        this.customTextureNames = new String[this.customTextures.size()];
        for (int i = 0; i < this.customTextures.size(); i++) {
            int index = i;
            customTextureNames[i] = this.customTextures.get(i).name();
            customTextureViews[i] = new SharedImageViewTracker(ctx,
                    () -> retainCustomTexture(index));
        }

        blockAtlasView = new SharedImageViewTracker(ctx, () -> {
            AbstractTexture blockAtlas = getBlockAtlasTexture();
            return ((IVGImage) blockAtlas).getVGImage();
        });
        blockAtlasNormalView = new SharedImageViewTracker(ctx, () -> {
            PBRTextureHolder holder = getBlockAtlasPbrHolder();
            var normalTexture = holder.normalTexture();
            return normalTexture == null ? null : ((IVGImage) normalTexture).getVGImage();
        });
        blockAtlasSpecularView = new SharedImageViewTracker(ctx, () -> {
            PBRTextureHolder holder = getBlockAtlasPbrHolder();
            var specularTexture = holder.specularTexture();
            return specularTexture == null ? null : ((IVGImage) specularTexture).getVGImage();
        });

        sampler = VSampler.create(ctx, info -> info.magFilter(VK_FILTER_NEAREST)
                .minFilter(VK_FILTER_NEAREST)
                .mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST)
                .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .compareOp(VK_COMPARE_OP_NEVER)
                .maxLod(1)
                .borderColor(VK_BORDER_COLOR_INT_OPAQUE_BLACK)
                .maxAnisotropy(1.0f));
        customTextureSampler = VSampler.create(ctx, info -> info.magFilter(VK_FILTER_LINEAR)
                .minFilter(VK_FILTER_LINEAR)
                .mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST)
                .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .compareOp(VK_COMPARE_OP_NEVER)
                .maxLod(1)
                .borderColor(VK_BORDER_COLOR_INT_OPAQUE_BLACK)
                .maxAnisotropy(1.0f));

        var specularPlaceholder = PlaceholderTextureFactory.createPlaceholderSpecular(ctx);
        placeholderSpecular = specularPlaceholder.image();
        placeholderSpecularView = specularPlaceholder.view();
        var normalPlaceholder = PlaceholderTextureFactory.createPlaceholderNormals(ctx);
        placeholderNormals = normalPlaceholder.image();
        placeholderNormalsView = normalPlaceholder.view();

        uboAllocator = new PoolLinearAllocator(ctx,
                VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                32 * 1024,
                0,
                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT | VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT,
                VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT);
        renderPassExecutor = new RenderPassExecutor(ctx, accelerationManager, sampler, customTextureSampler);
        cacheResolvePass = new CacheResolvePass(ctx, sampler);
        cacheFeedbackPass = new CacheFeedbackPass(ctx, sampler);
        dlssdProcessor = new DLSSDProcessor(ctx);

        if (passes != null) {
            buildRayPipelines(passes, ssboIds);
        }
        supportsEntities = passes != null
                && passes.length > 0
                && Arrays.stream(passes).allMatch(pass -> pass.getRayHitCount() > 1);
        supportsProceduralDebug = ctx.capabilities.proceduralAabbBlas()
                && passes != null
                && passes.length > 0
                && Arrays.stream(passes).allMatch(RaytracingShaderSet::hasProceduralDebugHitGroup);
        supportsHybridShadow = ctx.capabilities.proceduralAabbBlas()
                && passes != null
                && passes.length > 0
                && Arrays.stream(passes).allMatch(RaytracingShaderSet::hasProceduralShadowHitGroups);
        supportsProceduralReflection = ctx.capabilities.proceduralAabbBlas()
                && passes != null
                && passes.length > 0
                && Arrays.stream(passes).allMatch(RaytracingShaderSet::hasProceduralReflectionHitGroup);
        proceduralReflectionRequested = HybridAccelerationConfig.fromSystemProperties().proceduralReflections();
        accelerationManager.setProceduralReflectionEnabled(
                proceduralReflectionRequested && supportsProceduralReflection);
        if (proceduralReflectionRequested && !supportsProceduralReflection) {
            LOGGER.warn("Procedural reflections requested with -D{}=true, but the device or SBT record 6 is incompatible; retaining triangle reflections",
                    HybridAccelerationConfig.REFLECTION_PROPERTY);
        }
        if (supportsProceduralDebug) {
            shadowComparisonBuffer = ctx.memory.createBuffer(
                    HybridShadowComparisonLayout.BUFFER_BYTES,
                    VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT
                            | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                    VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
            shadowComparisonBuffer.get().setDebugUtilsObjectName("Hybrid Shadow Comparison Diagnostics");
            shadowComparisonReadbackBuffer = ctx.memory.createBuffer(
                    HybridShadowComparisonLayout.BUFFER_BYTES,
                    VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
            shadowComparisonReadbackBuffer.get().setDebugUtilsObjectName(
                    "Hybrid Shadow Comparison Readback");
        } else {
            shadowComparisonBuffer = null;
            shadowComparisonReadbackBuffer = null;
        }

        boolean hasRt = !raytracePipelines.isEmpty();
        pipelineRequirements = new PipelineRequirements(hasRt, hasRt, hasRt, hasRt, hasRt, hasRt);
        ACTIVE_PIPELINES.add(this);
        LOGGER.info("Hybrid RTX pipeline created: passes={}, entities={}", raytracePipelines.size(), supportsEntities);
    }

    private void buildRayPipelines(RaytracingShaderSet[] passes, int[] ssboIds) {
        var commonSetExpected = PipelineDescriptorSets.createCommonSetExpected();
        var geomSetExpected = PipelineDescriptorSets.createGeomSetExpected();
        var entityTextureSetExpected = PipelineDescriptorSets.createEntityTextureSetExpected();
        var customTexSetExpected = PipelineDescriptorSets.createCustomTexSetExpected(customTextureViews.length);
        var ssboSetExpected = PipelineDescriptorSets.createSsboSetExpected(ssboIds);

        try {
            for (int i = 0; i < passes.length; i++) {
                RaytracePipelineBuilder builder = new RaytracePipelineBuilder();
                passes[i].apply(builder);
                var pipeline = builder.build(ctx, 2);

                int commonSet = -1;
                int geomSet = -1;
                int entityTextureSet = -1;
                int customTexSet = -1;
                int ssboSet = -1;

                for (int setIdx = 0; setIdx < pipeline.get().reflection.getNSets(); setIdx++) {
                    var set = pipeline.get().reflection.getSet(setIdx);
                    if (set.bindings().isEmpty()) {
                        continue;
                    }

                    if (set.validate(commonSetExpected)) {
                        commonSet = setIdx;
                    } else if (set.validate(geomSetExpected)) {
                        geomSet = setIdx;
                    } else if (set.validate(entityTextureSetExpected)) {
                        entityTextureSet = setIdx;
                    } else if (set.validate(customTexSetExpected)) {
                        customTexSet = setIdx;
                    } else if (set.validate(ssboSetExpected)) {
                        ssboSet = setIdx;
                    } else {
                        throw new IllegalStateException("Ray tracing pipeline " + i
                                + " has an unexpected descriptor set layout at set " + setIdx + ": " + set);
                    }
                }

                raytracePipelines.add(new RtPipeline(
                        pipeline, commonSet, geomSet, entityTextureSet, customTexSet, ssboSet,
                        passes[i].hasProceduralDebugHitGroup(),
                        passes[i].hasProceduralShadowHitGroups(),
                        passes[i].hasProceduralReflectionHitGroup()));
            }
        } catch (Exception e) {
            destroy();
            throw new RuntimeException("Failed to create ray tracing pipeline", e);
        }
    }

    public PipelineRequirements getPipelineRequirements() {
        return pipelineRequirements;
    }

    public void renderPostShadows(List<VRef<VGImage>> vgOutImgs, Camera camera, ShaderStorageBuffer[] ssbos,
            MixinCelestialUniforms celestialUniforms, VRef<VImageView>[] gbufferViews) {
        if (raytracePipelines.isEmpty() || vgOutImgs == null || vgOutImgs.isEmpty()) {
            beginCompatibilityFrame(false, camera);
            return;
        }

        checkWorldChange();
        clearForeignGlErrors();

        MinecraftClient mc = MinecraftClient.getInstance();
        ResolutionScaleManager scaleManager = ResolutionScaleManager.getInstance();
        int outputWidth = mc.getWindow().getFramebufferWidth();
        int outputHeight = mc.getWindow().getFramebufferHeight();
        scaleManager.update(outputWidth, outputHeight);
        DLSSConfig dlssConfig = DLSSConfig.load();
        outputWidth = scaleManager.getOutputWidth();
        outputHeight = scaleManager.getOutputHeight();
        int[] rtxSize = chooseRtxRenderSize(gbufferViews, outputWidth, outputHeight,
                scaleManager, dlssConfig, mc);
        int renderWidth = rtxSize[0];
        int renderHeight = rtxSize[1];
        if (CacheInvalidationTracker.global().recordFrameSize(
                renderWidth, renderHeight, outputWidth, outputHeight)) {
            cacheFeedbackPass.clearPendingFeedback();
            resetTemporalHistory();
        }
        frameImages.ensureAllocated(ctx, renderWidth, renderHeight, outputWidth, outputHeight);

        var profiler = mc.getProfiler();
        profiler.push("vulkanite_hybrid_rtx");

        var outImgs = new ArrayList<VRef<VImage>>(vgOutImgs.size());
        var glReady = ctx.sync.createSharedBinarySemaphore();
        var vulkanDone = ctx.sync.createSharedBinarySemaphore();

        VRef<VSemaphore> glReadySemaphore = null;
        VRef<VSemaphore> vulkanDoneSemaphore = null;
        VRef<VAccelerationStructure> tlas = null;
        VRef<VAccelerationStructure> proceduralTlas = null;
        VRef<VAccelerationStructure> hybridShadowTlas = null;
        VRef<me.cortex.vulkanite.lib.cmd.VCmdBuff> cmdRef = null;
        CacheRequestBatch frameRequestBatch = null;
        RtxFrameDecision frameDecision = RtxFrameDecision.DISABLED;

        try {
            RtxCacheMode rtxCacheMode = VulkaniteConfig.getInstance().getRtxCacheMode();
            logRtxCacheMode(rtxCacheMode);
            beginCommandFrame();
            boolean dlssRuntimeEligible = dlssdProcessor.canUseConfiguredRenderScale(mc, dlssConfig);
            if (CacheInvalidationTracker.global().recordDlssMode(dlssConfig, dlssRuntimeEligible)) {
                resetTemporalHistory();
            }
            recordSkyGeneration();
            int frameIndex = SystemTimeUniforms.COUNTER.getAsInt();
            frameRequestBatch = collectCacheRequests(camera, frameIndex, rtxCacheMode);
            frameDecision = RtxFrameDecision.decide(rtxCacheMode, frameRequestBatch.hasWork());
            DLSSConfig.DebugType debugType = dlssConfig.getDebugType();
            boolean reflectionComparisonRequested = isProceduralReflectionComparison(debugType);
            boolean proceduralDebugRequested = isProceduralDebug(debugType);
            boolean proceduralDebugSupported = supportsProceduralDebug
                    && (!reflectionComparisonRequested || supportsProceduralReflection);
            // Keep acc as the complete triangle reference while mode 11 compares
            // it against the standalone procedural TLAS.
            accelerationManager.setProceduralReflectionEnabled(
                    proceduralReflectionRequested
                            && supportsProceduralReflection
                            && !reflectionComparisonRequested);
            if (proceduralDebugRequested && proceduralDebugSupported) {
                // A visualization must run a full-screen ray dispatch even when
                // the cache-first renderer would otherwise resolve without RT.
                frameDecision = RtxFrameDecision.FULL_RT_REFERENCE;
            }
            updateTransientEntityGeometry(
                    frameDecision.needsTlas(),
                    frameDecision == RtxFrameDecision.NO_RT,
                    camera);
            List<VRef<VGImage>> entityTextureImages = frameDecision.usesRayTracing()
                    ? accelerationManager.getEntityTextureImages()
                    : List.of();
            HybridInterop.ImageBatch imageBatch;
            try {
                imageBatch = HybridInterop.collect(vgOutImgs, gbufferViews, entityTextureImages);
            } finally {
                closeAll(entityTextureImages);
            }
            for (VRef<VGImage> image : vgOutImgs) {
                outImgs.add(new VRef<>(image.get()));
            }

            glReady.get().glSignal(EMPTY_GL_SEMAPHORE_IDS, imageBatch.glIds(), imageBatch.glLayouts());

            var cmdPool = ctx.cmd.createSingleUsePool(0);
            try {
                cmdRef = cmdPool.get().createCommandBuffer();
            } finally {
                cmdPool.close();
            }
            var cmd = cmdRef.get();

            if (frameDecision.needsTlas()) {
                profiler.push("build_tlas");
                tlas = accelerationManager.buildTLAS(0, cmd);
                if (supportsHybridShadow) {
                    hybridShadowTlas = accelerationManager.buildHybridShadowTLAS(0, cmd);
                } else {
                    accelerationManager.discardHybridShadowInstanceSnapshot();
                }
                if (proceduralDebugRequested && proceduralDebugSupported) {
                    proceduralTlas = accelerationManager.buildProceduralTLAS(0, cmd);
                }
                profiler.pop();
            }

            if (frameDecision.needsTlas() && tlas == null) {
                frameDecision = RtxFrameDecision.DISABLED;
            }

            if (frameDecision != RtxFrameDecision.DISABLED) {
                profiler.push("encode_rtx");
                encodeRtxFrame(cmd, tlas, proceduralTlas, hybridShadowTlas, vgOutImgs, outImgs, camera, ssbos, celestialUniforms,
                        gbufferViews, renderWidth, renderHeight,
                        frameDecision, frameRequestBatch, frameIndex);
                profiler.pop();
            } else {
                updateTemporalPathState(false);
            }

            glReadySemaphore = new VRef<>(glReady.get());
            vulkanDoneSemaphore = new VRef<>(vulkanDone.get());
            long execution = ctx.cmd.submit(
                    0, cmdRef, Arrays.asList(glReadySemaphore), Arrays.asList(vulkanDoneSemaphore), null);
            accelerationManager.markHybridTlasTimingSubmitted(execution);
            markRayTimingSubmitted(execution);
            cacheFeedbackPass.markEncodedFeedbackSubmitted(execution);
            recordFrameDecision(frameDecision);
            vulkanDone.get().glWait(EMPTY_GL_SEMAPHORE_IDS, imageBatch.glIds(), imageBatch.glLayouts());
        } catch (DeviceLostException e) {
            accelerationManager.cancelUnsubmittedHybridTlasTiming();
            cancelUnsubmittedRayTiming();
            cacheFeedbackPass.discardEncodedFeedback();
            cacheFeedbackPass.invalidateScreenHistory();
            requeueFailedCacheFill(frameDecision, frameRequestBatch);
            LOGGER.error("Device lost during hybrid RTX frame", e);
            Vulkanite.IS_ENABLED = false;
            throw e;
        } catch (Exception e) {
            accelerationManager.cancelUnsubmittedHybridTlasTiming();
            cancelUnsubmittedRayTiming();
            cacheFeedbackPass.discardEncodedFeedback();
            cacheFeedbackPass.invalidateScreenHistory();
            requeueFailedCacheFill(frameDecision, frameRequestBatch);
            LOGGER.error("Hybrid RTX frame failed", e);
            updateTemporalPathState(false);
        } finally {
            accelerationManager.cancelUnsubmittedHybridTlasTiming();
            cancelUnsubmittedRayTiming();
            if (tlas != null) {
                tlas.close();
            }
            if (proceduralTlas != null) {
                proceduralTlas.close();
            }
            if (hybridShadowTlas != null) hybridShadowTlas.close();
            if (glReadySemaphore != null) {
                glReadySemaphore.close();
            }
            if (vulkanDoneSemaphore != null) {
                vulkanDoneSemaphore.close();
            }
            if (cmdRef != null) {
                cmdRef.close();
            }
            for (VRef<VImage> image : outImgs) {
                image.close();
            }
            glReady.close();
            vulkanDone.close();
            profiler.pop();
        }
    }

    private void beginCompatibilityFrame(boolean captureEntityGeometry, Camera camera) {
        beginCommandFrame();
        updateTransientEntityGeometry(captureEntityGeometry, false, camera);
    }

    private void beginCommandFrame() {
        ctx.cmd.processPendingSubmissions();
        ctx.cmd.newFrame();
        pollCompletedRayTimings();
    }

    private void updateTransientEntityGeometry(
            boolean captureEntityGeometry,
            boolean retainWhenRayTracingIsSkipped,
            Camera camera) {
        VulkaniteConfig config = VulkaniteConfig.getInstance();
        boolean captureTransientGeometry = config.rtxEntityCaptureEnabled;
        if (captureEntityGeometry && supportsEntities && captureTransientGeometry) {
            int interval = Math.max(1, config.rtxEntityCaptureInterval) * transientCaptureThrottle;
            entityCaptureFrame = Math.min(Integer.MAX_VALUE, entityCaptureFrame + 1);
            if (!entityCaptureInitialized || entityCaptureFrame >= interval) {
                entityCaptureFrame = 0;
                long start = System.nanoTime();
                EntityCapture.Frame capturedEntities = capture.capture(
                        CapturedRenderingState.INSTANCE.getTickDelta(),
                        MinecraftClient.getInstance().world,
                        camera,
                        config.rtxEntityCaptureEnabled ? captureLimit(config.rtxMaxCapturedEntities,
                                RUNTIME_ENTITY_CAPTURE_CAP) : 0,
                        0,
                        false,
                        Math.max(MIN_ENTITY_CAPTURE_RADIUS, config.rtxEntityCaptureRadius));
                accelerationManager.setEntityData(capturedEntities);
                entityCacheStateActive = capturedEntities != null;
                entityCaptureInitialized = true;
                CacheInvalidationTracker.global().recordEntityGeometryChanged();
                long duration = System.nanoTime() - start;
                if (duration > TRANSIENT_CAPTURE_BUDGET_NS) {
                    transientCaptureThrottle = Math.min(MAX_TRANSIENT_CAPTURE_THROTTLE, transientCaptureThrottle + 1);
                } else if (duration < TRANSIENT_CAPTURE_BUDGET_NS / 2 && transientCaptureThrottle > 1) {
                    transientCaptureThrottle--;
                }
            }
        } else if (retainWhenRayTracingIsSkipped && supportsEntities && captureTransientGeometry) {
            // Cache-hit frames do not need fresh transient BLAS data. Age the capture
            // interval so the next bounded fill refreshes it, but keep the last capture
            // resident instead of invalidating and rebuilding it on every RT/no-RT edge.
            entityCaptureFrame = Math.min(Integer.MAX_VALUE, entityCaptureFrame + 1);
        } else {
            entityCaptureFrame = 0;
            transientCaptureThrottle = 1;
            entityCaptureInitialized = false;
            if (entityCacheStateActive) {
                accelerationManager.setEntityData(null);
                entityCacheStateActive = false;
                CacheInvalidationTracker.global().recordEntityGeometryChanged();
            }
        }
    }

    private int[] chooseRtxRenderSize(VRef<VImageView>[] gbufferViews, int outputWidth, int outputHeight,
            ResolutionScaleManager scaleManager, DLSSConfig config, MinecraftClient mc) {
        if (dlssdProcessor.canUseConfiguredRenderScale(mc, config)
                && scaleManager.getRenderWidth() > 0
                && scaleManager.getRenderHeight() > 0) {
            return new int[] { scaleManager.getRenderWidth(), scaleManager.getRenderHeight() };
        }

        if (gbufferViews != null) {
            for (VRef<VImageView> view : gbufferViews) {
                if (view != null && view.get() != null && view.get().image != null) {
                    VImage image = view.get().image.get();
                    if (image.width > 0 && image.height > 0) {
                        return new int[] { image.width, image.height };
                    }
                }
            }
        }

        int[] aligned = ResolutionScaleManager.alignDimensions(outputWidth, outputHeight);
        return new int[] { aligned[0], aligned[1] };
    }

    private void encodeRtxFrame(
            me.cortex.vulkanite.lib.cmd.VCmdBuff cmd,
            VRef<VAccelerationStructure> tlas,
            VRef<VAccelerationStructure> proceduralTlas,
            VRef<VAccelerationStructure> hybridShadowTlas,
            List<VRef<VGImage>> vgOutImgs,
            List<VRef<VImage>> outImgs,
            Camera camera,
            ShaderStorageBuffer[] ssbos,
            MixinCelestialUniforms celestialUniforms,
            VRef<VImageView>[] gbufferViews,
            int renderWidth,
            int renderHeight,
            RtxFrameDecision frameDecision,
            CacheRequestBatch cacheRequestBatch,
            int frameIndex) {
        DLSSConfig dlssConfig = DLSSConfig.load();
        boolean dlssFrameActive = dlssdProcessor.prepareForFrame(
                MinecraftClient.getInstance(), dlssConfig, frameImages, outImgs);
        updateTemporalPathState(dlssFrameActive);

        PoolLinearAllocator.BufferRegion ubo = uboAllocator.allocate(UBO_SIZE);
        long ptr = ubo.buffer().get().map();
        try {
            MemoryUtil.memSet(ptr, 0, UBO_SIZE);
            ByteBuffer data = MemoryUtil.memByteBuffer(ptr, UBO_SIZE);
            UBODataEncoder.encode(data, camera, celestialUniforms);
        } finally {
            ubo.buffer().get().unmap();
            ubo.buffer().get().flush();
        }

        frameImages.initializeLayouts(cmd);

        List<VRef<VImage>> sampledImages = frameDecision.usesRayTracing()
                ? collectSampledImages()
                : List.of();
        List<VRef<VImage>> gbufferImages = collectGbufferImages(gbufferViews);
        transitionImages(cmd, sampledImages, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        transitionImages(cmd, gbufferImages, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);

        // Iris sunPosition is camera/view dependent. Ask its celestial model
        // for the raw world-space sun vector; shaders flip only the shadow ray
        // at night, matching Photonics without corrupting day/night ambience.
        var sunPos = celestialUniforms.invokeGetCelestialPositionInWorldSpace(100.0f);
        DLSSConfig.DebugType debugType = dlssConfig.getDebugType();
        int debugMode = mapDebugMode(debugType);
        boolean proceduralDebugSupported = supportsProceduralDebug
                && (!isProceduralReflectionComparison(debugType) || supportsProceduralReflection);
        if (isProceduralDebug(debugType)
                && (!proceduralDebugSupported || proceduralTlas == null)) {
            debugMode = 0;
            if (!proceduralDebugUnavailableLogged) {
                proceduralDebugUnavailableLogged = true;
                LOGGER.warn("Procedural debug view requested, but no compatible procedural TLAS is available");
            }
        } else {
            proceduralDebugUnavailableLogged = false;
        }
        if (requiresProceduralShadowHitGroup(dlssConfig.getDebugType()) && !supportsHybridShadow) {
            debugMode = 0;
        }
        boolean comparisonRequested = isShadowComparison(dlssConfig.getDebugType())
                && proceduralTlas != null && shadowComparisonBuffer != null;
        boolean comparisonReadbackRequested = prepareShadowComparisonReadback(
                dlssConfig, comparisonRequested);
        if (comparisonRequested && !shadowComparisonActive) {
            cmd.encodeFillBuffer(shadowComparisonBuffer, 0,
                    HybridShadowComparisonLayout.BUFFER_BYTES, 0);
            cmd.encodeMemoryBarrier(
                    VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                    VK_ACCESS_TRANSFER_WRITE_BIT,
                    VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);
        }
        shadowComparisonActive = comparisonRequested;
        VRef<VBuffer> sectionLightBuffer = null;
        VRef<VBuffer> sectionLightProbeBuffer = null;
        VRef<VBuffer> sectionLightProbeFeedbackBuffer = null;
        VRef<VBuffer> sectionLightProbeFillRequestBuffer = null;
        VRef<VBuffer> diffuseRadianceCacheBuffer = null;
        VRef<VBuffer> diffuseRadianceFillRequestBuffer = null;
        VRef<VBuffer> specularTransportCacheBuffer = null;
        VRef<VBuffer> specularTransportFillRequestBuffer = null;
        VRef<VBuffer> surfaceDirectLightCacheBuffer = null;
        VRef<VBuffer> surfaceDirectLightFillRequestBuffer = null;
        try {
            if (frameDecision.usesRayTracing() || frameDecision.usesCacheResolve()) {
                diffuseRadianceCacheBuffer = diffuseRadianceCache.ensureCacheGpuBuffer(ctx, cmd);
                specularTransportCacheBuffer = specularTransportCache.ensureCacheGpuBuffer(ctx, cmd);
                surfaceDirectLightCacheBuffer = surfaceDirectLightCache.ensureCacheGpuBuffer(ctx, cmd);
                var sectionLightManager = Vulkanite.INSTANCE.getSectionLightManager();
                sectionLightBuffer = sectionLightManager.ensureGpuBuffer(ctx, cmd);
                // The compute resolve consumes the same persistent, world-space
                // six-face voxel cache that request-only RTX dispatches fill.
                sectionLightProbeBuffer = sectionLightManager.ensureProbeGpuBuffer(ctx, cmd);
                sectionLightProbeFeedbackBuffer =
                        sectionLightManager.ensureProbeFeedbackGpuBuffer(ctx, cmd);
            }
            if (frameDecision.usesRayTracing()) {
                var sectionLightManager = Vulkanite.INSTANCE.getSectionLightManager();
                sectionLightProbeFillRequestBuffer =
                        sectionLightManager.ensureProbeFillRequestGpuBuffer(ctx, cmd, cacheRequestBatch);
                diffuseRadianceFillRequestBuffer =
                        diffuseRadianceCache.ensureFillRequestGpuBuffer(ctx, cmd, cacheRequestBatch, frameIndex);
                specularTransportFillRequestBuffer =
                        specularTransportCache.ensureFillRequestGpuBuffer(ctx, cmd, cacheRequestBatch, frameIndex);
                surfaceDirectLightFillRequestBuffer =
                        surfaceDirectLightCache.ensureFillRequestGpuBuffer(ctx, cmd, cacheRequestBatch, frameIndex);
            }

            RtxFrame frame = new RtxFrame(
                    cmd,
                    ubo.buffer(),
                    ubo.offset(),
                    ubo.size(),
                    tlas,
                    proceduralTlas,
                    hybridShadowTlas,
                    shadowComparisonBuffer,
                    blockAtlasView,
                    blockAtlasNormalView,
                    blockAtlasSpecularView,
                    placeholderNormalsView,
                    placeholderSpecularView,
                    irisRenderTargetViews,
                    vgOutImgs,
                    outImgs,
                    customTextureViews,
                    ssbos,
                    gbufferViews,
                    frameIndex,
                    0,
                    sunPos.x,
                    sunPos.y,
                    sunPos.z,
                    1.0f,
                    0.95f,
                    0.8f,
                    dlssConfig.isReSTIREnabled() ? 1 : 0,
                    debugMode,
                    dlssConfig.getDebugCellIndex(),
                    HybridShadowComparisonLayout.clampSamplingPermille(
                            dlssConfig.getShadowComparisonSamplingPercent()),
                    HybridShadowComparisonLayout.clampDistanceTolerance(
                            dlssConfig.getShadowComparisonDistanceTolerance()),
                    frameDecision.isCacheFillOnly() ? -1 : 0,
                    frameImages.storageViews(frameIndex),
                    frameImages.currentReservoir(frameIndex),
                    frameImages.previousReservoir(frameIndex),
                    frameImages.diffuseAlbedoMetallic(),
                    frameImages.specularAlbedo(),
                    frameImages.normalRoughness(),
                    frameImages.motionVector(),
                    frameImages.linearDepth(),
                    frameImages.specularHitDepth(),
                    frameImages.firstHitDepth(),
                    frameImages.blocklightDetail(),
                    sectionLightBuffer,
                    sectionLightProbeBuffer,
                    sectionLightProbeFeedbackBuffer,
                    sectionLightProbeFillRequestBuffer,
                    diffuseRadianceCacheBuffer,
                    diffuseRadianceFillRequestBuffer,
                    specularTransportCacheBuffer,
                    specularTransportFillRequestBuffer,
                    surfaceDirectLightCacheBuffer,
                    surfaceDirectLightFillRequestBuffer,
                    frameImages.radiance(),
                    renderWidth,
                    renderHeight,
                    frameDecision.isCacheFillOnly()
                            ? Math.max(1, cacheRequestBatch.parallelDispatchWidth())
                            : renderWidth,
                    frameDecision.isCacheFillOnly() ? 1 : renderHeight);
            if (frameDecision.usesRayTracing()) {
                RayTimingSlot timingSlot = beginRayTiming(cmd, frame, frameDecision);
                try {
                    for (RtPipeline pass : raytracePipelines) {
                        renderPassExecutor.execute(pass, frame);
                    }
                    finishRayTiming(cmd, timingSlot);
                } catch (RuntimeException | Error exception) {
                    cancelRayTiming(timingSlot);
                    throw exception;
                }
                if (comparisonReadbackRequested) {
                    cmd.encodeMemoryBarrier(
                            VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                            VK_PIPELINE_STAGE_TRANSFER_BIT,
                            VK_ACCESS_SHADER_WRITE_BIT,
                            VK_ACCESS_TRANSFER_READ_BIT);
                    cmd.encodeBufferCopy(
                            shadowComparisonBuffer, 0,
                            shadowComparisonReadbackBuffer, 0,
                            HybridShadowComparisonLayout.BUFFER_BYTES);
                    shadowComparisonReadbackPending = true;
                }
                // Visible rays publish transport payloads and then mark entries ready.
                // Make those writes visible to later compute consumers.
                cmd.encodeMemoryBarrier(
                        VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                        VK_ACCESS_SHADER_WRITE_BIT,
                        VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);
            }
            if (frameDecision.usesCacheResolve()) {
                cacheFeedbackPass.execute(frame);
                cacheResolvePass.execute(frame, true);
                cacheFeedbackPass.markScreenHistoryResolved();
            }
        } finally {
            if (surfaceDirectLightFillRequestBuffer != null) {
                surfaceDirectLightFillRequestBuffer.close();
            }
            if (surfaceDirectLightCacheBuffer != null) {
                surfaceDirectLightCacheBuffer.close();
            }
            if (specularTransportFillRequestBuffer != null) {
                specularTransportFillRequestBuffer.close();
            }
            if (specularTransportCacheBuffer != null) {
                specularTransportCacheBuffer.close();
            }
            if (diffuseRadianceFillRequestBuffer != null) {
                diffuseRadianceFillRequestBuffer.close();
            }
            if (diffuseRadianceCacheBuffer != null) {
                diffuseRadianceCacheBuffer.close();
            }
            if (sectionLightProbeFillRequestBuffer != null) {
                sectionLightProbeFillRequestBuffer.close();
            }
            if (sectionLightProbeFeedbackBuffer != null) {
                sectionLightProbeFeedbackBuffer.close();
            }
            if (sectionLightProbeBuffer != null) {
                sectionLightProbeBuffer.close();
            }
            if (sectionLightBuffer != null) {
                sectionLightBuffer.close();
            }
        }

        transitionImages(cmd, sampledImages, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL);

        compositeToIrisTarget(cmd, outImgs, dlssFrameActive);

        transitionImages(cmd, gbufferImages, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL);
        closeAll(sampledImages);
        closeAll(gbufferImages);
    }

    private CacheRequestBatch collectCacheRequests(Camera camera, int frameIndex, RtxCacheMode mode) {
        if (!mode.collectsCacheRequests()) {
            cacheFeedbackPass.clearPendingFeedback();
            CacheRequestStats stats = cacheRequestQueue.snapshot();
            return new CacheRequestBatch(List.of(), stats.backlog(), stats);
        }

        CacheInvalidationTracker invalidationTracker = CacheInvalidationTracker.global();
        cacheRequestQueue.discardIf(request ->
                !invalidationTracker.isCurrent(request.key(), request.versionStamp()));
        int feedbackRequests = cacheFeedbackPass.ingestPendingFeedback(cacheRequestQueue);
        // Diffuse/local RTX lighting is a world-space 2-block voxel volume.
        // Populate it from section dirtiness, not from whichever surfaces the
        // camera happens to expose this frame.
        ChunkSectionPos cameraSection = ChunkSectionPos.from(
                BlockPos.ofFloored(camera.getPos()));
        int sectionRequests = Vulkanite.INSTANCE.getSectionLightManager().drainPendingCacheRequests(
                cacheRequestQueue,
                cameraSection,
                frameIndex,
                MAX_CACHE_REQUESTS_PER_FRAME);

        CacheRequestBatch batch;
        if (mode == RtxCacheMode.CACHE_ON_HIT) {
            batch = cacheRequestQueue.drainBatch(
                    MAX_CACHE_REQUESTS_PER_FRAME,
                    frameIndex,
                    request -> request.key().family() == CacheRequestFamily.SECTION_PROBE_CELL
                            || request.key().family() == CacheRequestFamily.REFLECTION
                            || request.key().family() == CacheRequestFamily.REFRACTION
                            );
        } else {
            CacheRequestStats stats = cacheRequestQueue.snapshot();
            batch = new CacheRequestBatch(List.of(), stats.backlog(), stats);
        }
        logCacheRequestBatch(mode, batch, sectionRequests, feedbackRequests);
        return batch;
    }

    private void requeueFailedCacheFill(RtxFrameDecision decision, CacheRequestBatch batch) {
        if (decision != RtxFrameDecision.CACHE_FILL_ONLY || batch == null) {
            return;
        }
        for (var request : batch.requests()) {
            cacheRequestQueue.requeue(request);
        }
    }

    private void logCacheRequestBatch(
            RtxCacheMode mode,
            CacheRequestBatch batch,
            int sectionRequests,
            int feedbackRequests) {
        CacheRequestStats stats = batch.stats();
        long now = System.nanoTime();
        if (now - lastFrameOrchestrationLogNanos < FRAME_PATH_LOG_INTERVAL_NANOS) {
            return;
        }
        LOGGER.info("[Vulkanite] Cache requests: mode={}, batch={}, section={}, feedback={}, backlog={} (probe={}, diffuse={}, reflection={}, refraction={}, surfaceDirect={}), enqueued={}, merged={}, drained={}, dropped={}, throttled={}",
                mode.configValue(), batch.size(), sectionRequests, feedbackRequests, stats.backlog(),
                stats.sectionProbeBacklog(), stats.diffuseRadianceBacklog(),
                stats.reflectionBacklog(), stats.refractionBacklog(), stats.surfaceDirectLightBacklog(),
                stats.enqueued(), stats.merged(), stats.drained(), stats.dropped(), stats.throttled());
    }

    private void recordFrameDecision(RtxFrameDecision decision) {
        switch (decision) {
            case FULL_RT_REFERENCE -> referenceRtDispatches++;
            case CACHE_FILL_ONLY -> cacheFillDispatches++;
            case NO_RT -> rtDispatchesSkipped++;
            case DISABLED -> {
            }
        }

        long now = System.nanoTime();
        if (now - lastFrameOrchestrationLogNanos < FRAME_PATH_LOG_INTERVAL_NANOS) {
            return;
        }
        lastFrameOrchestrationLogNanos = now;
        String rtDispatch = switch (decision) {
            case CACHE_FILL_ONLY -> "BOUNDED_REQUESTS";
            case FULL_RT_REFERENCE -> "FULL_FRAME";
            case NO_RT -> "SKIPPED";
            case DISABLED -> "DISABLED";
        };
        CacheRequestStats requests = cacheRequestQueue.snapshot();
        CacheFeedbackPass.Metrics metrics = cacheFeedbackPass.snapshotMetrics();
        LOGGER.info("[Vulkanite] Frame RT path: decision={}, fillFrames={}, referenceFrames={}, noRtFrames={}, rtDispatch={}, backlog={}, cacheHits={}/{}, fallbackSamples={}",
                decision,
                cacheFillDispatches,
                referenceRtDispatches,
                rtDispatchesSkipped,
                rtDispatch,
                requests.backlog(),
                metrics.cacheHits(),
                metrics.cacheQueries(),
                metrics.fallbackPixels());
    }

    private static AbstractTexture getBlockAtlasTexture() {
        return MinecraftClient.getInstance().getTextureManager().getTexture(SpriteAtlasTexture.BLOCK_ATLAS_TEXTURE);
    }

    private static PBRTextureHolder getBlockAtlasPbrHolder() {
        return PBRTextureManager.INSTANCE.getOrLoadHolder(getBlockAtlasTexture().getGlId());
    }

    private List<VRef<VImage>> collectSampledImages() {
        ArrayList<VRef<VImage>> images = new ArrayList<>();
        addIfPresent(images, blockAtlasView.getImage());
        addIfPresent(images, blockAtlasNormalView.getImage());
        addIfPresent(images, blockAtlasSpecularView.getImage());
        for (SharedImageViewTracker customTextureView : customTextureViews) {
            addIfPresent(images, customTextureView.getImage());
        }
        for (VRef<VGImage> entityTexture : accelerationManager.getEntityTextureImages()) {
            @SuppressWarnings({"rawtypes", "unchecked"})
            VRef<VImage> image = (VRef) entityTexture;
            images.add(image);
        }
        return images;
    }

    private List<VRef<VImage>> collectGbufferImages(VRef<VImageView>[] gbufferViews) {
        ArrayList<VRef<VImage>> images = new ArrayList<>();
        if (gbufferViews == null) {
            return images;
        }

        for (VRef<VImageView> view : gbufferViews) {
            if (view != null && view.get() != null && view.get().image != null) {
                images.add(view.get().image.addRef());
            }
        }
        return images;
    }

    private static void addIfPresent(List<VRef<VImage>> images, VRef<VImage> image) {
        if (image != null) {
            images.add(image);
        }
    }

    private void logRtxCacheMode(RtxCacheMode mode) {
        if (mode == lastLoggedRtxCacheMode) {
            return;
        }
        lastLoggedRtxCacheMode = mode;
        LOGGER.info("RTX cache mode: {}", mode.configValue());
    }

    private static int captureLimit(int configuredLimit, int runtimeCap) {
        return Math.max(1, Math.min(configuredLimit, runtimeCap));
    }

    private static void closeAll(List<? extends VRef<?>> refs) {
        for (VRef<?> ref : refs) {
            safeClose(ref);
        }
    }

    private VRef<VGImage> retainCustomTexture(int index) {
        VRef<VGImage> image = customTextures.get(index).image();
        if (image == null) {
            return null;
        }
        try {
            return image.addRef();
        } catch (NullPointerException e) {
            return null;
        }
    }

    private static void safeClose(VRef<?> ref) {
        if (ref == null) {
            return;
        }
        try {
            ref.close();
        } catch (NullPointerException ignored) {
            // Stale weak refs can appear during shader/resource reload. Treat as already closed.
        }
    }

    private static void transitionImages(me.cortex.vulkanite.lib.cmd.VCmdBuff cmd, List<VRef<VImage>> images,
            int oldLayout, int newLayout) {
        for (VRef<VImage> image : images) {
            cmd.encodeImageTransition(image, oldLayout, newLayout,
                    VK_IMAGE_ASPECT_COLOR_BIT, VK_REMAINING_MIP_LEVELS);
        }
    }

    private void compositeToIrisTarget(me.cortex.vulkanite.lib.cmd.VCmdBuff cmd, List<VRef<VImage>> outImgs,
            boolean dlssFrameActive) {
        if (outImgs.isEmpty() || frameImages.radiance() == null) {
            updateTemporalPathState(false);
            return;
        }

        VRef<VImage> source = frameImages.radiance();
        if (dlssFrameActive) {
            try {
                VRef<VImage> denoised = dlssdProcessor.processFrame(
                        cmd,
                        frameImages,
                        computeDLSSDeltaTimeSeconds());
                if (denoised != null) {
                    source = denoised;
                }
            } catch (Exception e) {
                LOGGER.warn("DLSS/RR processing failed; using noisy RTX output", e);
                updateTemporalPathState(false);
            }
        }

        blitToTarget(cmd, source, outImgs.get(0));
    }

    private static void blitToTarget(me.cortex.vulkanite.lib.cmd.VCmdBuff cmd, VRef<VImage> source, VRef<VImage> target) {
        cmd.encodeImageTransition(source, VK_IMAGE_LAYOUT_GENERAL,
                VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_ASPECT_COLOR_BIT, 1);
        cmd.encodeImageTransition(target, VK_IMAGE_LAYOUT_GENERAL,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_ASPECT_COLOR_BIT, 1);
        cmd.blitImage(source, target,
                VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                VK_FILTER_LINEAR);
        cmd.encodeImageTransition(target, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_ASPECT_COLOR_BIT, 1);
        cmd.encodeImageTransition(source, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_ASPECT_COLOR_BIT, 1);
    }

    private void checkWorldChange() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world != lastWorld) {
            lastWorld = mc.world;
            CacheInvalidationTracker.global().recordWorld(dimensionId(mc.world));
            entityCaptureFrame = 0;
            accelerationManager.setEntityData(null);
            entityCacheStateActive = false;
            cacheRequestQueue.clear();
            cacheFeedbackPass.clearPendingFeedback();
            diffuseRadianceCache.reset();
            specularTransportCache.reset();
            surfaceDirectLightCache.reset();
            Vulkanite.INSTANCE.getSectionLightManager().clear();
            resetTemporalHistory();
            shadowComparisonActive = false;
        }

        if (mc.world == null || mc.gameRenderer == null) {
            return;
        }

        var cameraPos = mc.gameRenderer.getCamera().getPos();
        if (lastCameraPos != null && cameraPos.squaredDistanceTo(lastCameraPos) > 100.0) {
            CacheInvalidationTracker.global().recordCameraCut();
            resetTemporalHistory();
        }
        lastCameraPos = cameraPos;
    }

    private static void recordSkyGeneration() {
        var world = MinecraftClient.getInstance().world;
        if (world == null) {
            return;
        }
        CacheInvalidationTracker.global().recordSky(
                world.getTimeOfDay(),
                world.isRaining(),
                world.isThundering());
    }

    private static String dimensionId(net.minecraft.world.World world) {
        if (world == null) {
            return "";
        }
        return world.getRegistryKey().getValue().toString();
    }

    private void resetTemporalHistory() {
        UBODataEncoder.resetTemporalHistory();
        if (cacheFeedbackPass != null) {
            cacheFeedbackPass.invalidateScreenHistory();
        }
        JitterManager.reset();
        JitterManager.setDLSSActive(false);
        if (dlssdProcessor != null) {
            dlssdProcessor.resetTemporalState();
        }
        dlssTemporalPathActive = false;
        lastDlssFrameTimeNs = -1L;
    }

    private void updateTemporalPathState(boolean active) {
        if (dlssTemporalPathActive == active) {
            return;
        }
        dlssTemporalPathActive = active;
        JitterManager.setDLSSActive(active);
        if (!active) {
            UBODataEncoder.resetTemporalHistory();
            lastDlssFrameTimeNs = -1L;
        }
    }

    private float computeDLSSDeltaTimeSeconds() {
        long now = System.nanoTime();
        if (lastDlssFrameTimeNs < 0L) {
            lastDlssFrameTimeNs = now;
            return 1.0f / 60.0f;
        }
        float delta = (now - lastDlssFrameTimeNs) / 1_000_000_000.0f;
        lastDlssFrameTimeNs = now;
        return Math.max(1.0f / 240.0f, Math.min(delta, 0.25f));
    }

    static int mapDebugMode(DLSSConfig.DebugType debugType) {
        return switch (debugType) {
            case NONE -> 0;
            case INPUT, OUTPUT -> 1;
            case MOTION_VECTORS, DEPTH, NORMALS -> 2;
            case PROCEDURAL_DISTANCE -> 3;
            case PROCEDURAL_NORMALS -> 4;
            case PROCEDURAL_BRICK_IDS -> 5;
            case PROCEDURAL_VOXEL_IDS -> 6;
            case SHADOW_COMPARISON -> 7;
            case SHADOW_DANGEROUS_MISSES -> 8;
            case SHADOW_EXTRA_HITS -> 9;
            case SHADOW_DOUBLE_TRACE_REFERENCE -> 10;
            case PROCEDURAL_REFLECTION_COMPARISON -> 11;
        };
    }

    static boolean isProceduralDebug(DLSSConfig.DebugType debugType) {
        return switch (debugType) {
            case PROCEDURAL_DISTANCE, PROCEDURAL_NORMALS,
                    PROCEDURAL_BRICK_IDS, PROCEDURAL_VOXEL_IDS,
                    SHADOW_COMPARISON, SHADOW_DANGEROUS_MISSES, SHADOW_EXTRA_HITS,
                    SHADOW_DOUBLE_TRACE_REFERENCE, PROCEDURAL_REFLECTION_COMPARISON -> true;
            default -> false;
        };
    }

    static boolean isProceduralReflectionComparison(DLSSConfig.DebugType debugType) {
        return debugType == DLSSConfig.DebugType.PROCEDURAL_REFLECTION_COMPARISON;
    }

    static boolean isShadowComparison(DLSSConfig.DebugType debugType) {
        return switch (debugType) {
            case SHADOW_COMPARISON, SHADOW_DANGEROUS_MISSES, SHADOW_EXTRA_HITS -> true;
            default -> false;
        };
    }

    static boolean requiresProceduralShadowHitGroup(DLSSConfig.DebugType debugType) {
        return isShadowComparison(debugType)
                || debugType == DLSSConfig.DebugType.SHADOW_DOUBLE_TRACE_REFERENCE;
    }

    private boolean prepareShadowComparisonReadback(DLSSConfig config, boolean comparisonRequested) {
        boolean loggingEnabled = comparisonRequested
                && config.isShadowComparisonStructuredLogging()
                && shadowComparisonReadbackBuffer != null;
        if (!loggingEnabled) {
            shadowComparisonReadbackPending = false;
            nextShadowComparisonLogNanos = 0L;
            return false;
        }

        long now = System.nanoTime();
        if (shadowComparisonReadbackPending) {
            // This opt-in diagnostic favors exact evidence over frame pacing. The
            // queue wait occurs at most once per interval and never in normal play.
            ctx.cmd.waitQueueIdle(0);
            logShadowComparisonSnapshot(config);
            shadowComparisonReadbackPending = false;
            nextShadowComparisonLogNanos = now + SHADOW_COMPARISON_LOG_INTERVAL_NANOS;
            // Begin a fresh bounded sample window in the command buffer being
            // recorded now, after the completed snapshot has been consumed.
            shadowComparisonActive = false;
            return false;
        }
        if (nextShadowComparisonLogNanos == 0L) {
            nextShadowComparisonLogNanos = now + SHADOW_COMPARISON_LOG_INTERVAL_NANOS;
            return false;
        }
        return now >= nextShadowComparisonLogNanos;
    }

    private void logShadowComparisonSnapshot(DLSSConfig config) {
        long pointer = shadowComparisonReadbackBuffer.get().map();
        HybridShadowComparisonSnapshot snapshot;
        try {
            snapshot = HybridShadowComparisonSnapshot.read(
                    MemoryUtil.memByteBuffer(pointer, HybridShadowComparisonLayout.BUFFER_BYTES));
        } finally {
            shadowComparisonReadbackBuffer.get().unmap();
        }
        LOGGER.info("[Vulkanite] Hybrid shadow comparison sample: {}",
                snapshot.structuredSummary(
                        config.getShadowComparisonSamplingPercent(),
                        HybridShadowComparisonLayout.clampDistanceTolerance(
                                config.getShadowComparisonDistanceTolerance())));
    }

    private RayTimingSlot beginRayTiming(
            me.cortex.vulkanite.lib.cmd.VCmdBuff cmd,
            RtxFrame frame,
            RtxFrameDecision frameDecision) {
        if (rayTimestampQueryPool == null || frameDecision != RtxFrameDecision.FULL_RT_REFERENCE) {
            return null;
        }

        String path;
        if (frame.debugMode() == 0 && frame.hybridShadowTlas() != null) {
            path = "hybrid";
        } else if (frame.debugMode() == 10 && frame.proceduralTlas() != null) {
            // Mode 10 is the unadorned Phase 5 triangle || procedural reference.
            // Modes 7-9 include diagnostic counters/overlays and are intentionally
            // excluded from the performance comparison.
            path = "double_trace_reference";
        } else {
            return null;
        }

        if (rayTimingAwaitingSubmission != null) {
            rayTimingDrops++;
            LOGGER.warn("[Vulkanite][Phase7GPU] event=ray_timing_drop reason=uncommitted_recording queryDrops={}",
                    rayTimingDrops);
            return null;
        }

        RayTimingSlot slot = null;
        for (int i = 0; i < rayTimingSlots.length; i++) {
            int index = (rayTimingCursor + i) % rayTimingSlots.length;
            if (rayTimingSlots[index].state == RayTimingState.FREE) {
                slot = rayTimingSlots[index];
                rayTimingCursor = (index + 1) % rayTimingSlots.length;
                break;
            }
        }
        if (slot == null) {
            rayTimingDrops++;
            LOGGER.warn("[Vulkanite][Phase7GPU] event=ray_timing_drop reason=query_ring_full queryDrops={}",
                    rayTimingDrops);
            return null;
        }

        slot.sequence = ++rayTimingSequence;
        slot.path = path;
        slot.frameIndex = frame.frameIndex();
        slot.decision = frameDecision.name();
        slot.width = frame.rayDispatchWidth();
        slot.height = frame.rayDispatchHeight();
        slot.passCount = raytracePipelines.size();
        slot.state = RayTimingState.RECORDING;

        int queryBase = slot.index * RAY_TIMING_QUERIES_PER_SLOT;
        cmd.resetQueryPool(rayTimestampQueryPool, queryBase, RAY_TIMING_QUERIES_PER_SLOT);
        cmd.writeTimestamp(
                rayTimestampQueryPool,
                queryBase + RAY_TIMING_START,
                VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR);
        return slot;
    }

    private void finishRayTiming(me.cortex.vulkanite.lib.cmd.VCmdBuff cmd, RayTimingSlot slot) {
        if (slot == null) {
            return;
        }
        if (slot.state != RayTimingState.RECORDING) {
            throw new IllegalStateException("Ray timing slot is not recording");
        }
        int queryBase = slot.index * RAY_TIMING_QUERIES_PER_SLOT;
        cmd.writeTimestamp(
                rayTimestampQueryPool,
                queryBase + RAY_TIMING_END,
                VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR);
        slot.state = RayTimingState.RECORDED;
        rayTimingAwaitingSubmission = slot;
    }

    private void markRayTimingSubmitted(long execution) {
        RayTimingSlot slot = rayTimingAwaitingSubmission;
        if (slot == null) {
            return;
        }
        if (slot.state != RayTimingState.RECORDED) {
            throw new IllegalStateException("Ray timing slot was not recorded before submission");
        }
        slot.execution = execution;
        slot.state = RayTimingState.SUBMITTED;
        rayTimingAwaitingSubmission = null;
    }

    private void cancelUnsubmittedRayTiming() {
        RayTimingSlot slot = rayTimingAwaitingSubmission;
        if (slot != null) {
            cancelRayTiming(slot);
        }
    }

    private void cancelRayTiming(RayTimingSlot slot) {
        if (slot == null) {
            return;
        }
        if (rayTimingAwaitingSubmission == slot) {
            rayTimingAwaitingSubmission = null;
        }
        slot.reset();
    }

    private void pollCompletedRayTimings() {
        if (rayTimestampQueryPool == null) {
            return;
        }
        long completedExecution = ctx.cmd.getQueueCurrentExecution(0);
        for (RayTimingSlot slot : rayTimingSlots) {
            if (slot.state != RayTimingState.SUBMITTED || slot.execution > completedExecution) {
                continue;
            }
            int queryBase = slot.index * RAY_TIMING_QUERIES_PER_SLOT;
            long[] timestamps = rayTimestampQueryPool.get().getResultsLongIfAvailable(
                    queryBase,
                    RAY_TIMING_QUERIES_PER_SLOT);
            if (timestamps == null) {
                continue;
            }
            long gpuTicks = GpuTimestampMath.deltaTicks(
                    timestamps[RAY_TIMING_START],
                    timestamps[RAY_TIMING_END],
                    ctx.properties.timestampValidBits);
            LOGGER.debug("[Vulkanite][Phase7GPU] event=ray_sample seq={} execution={} path={} frame={} decision={} width={} height={} passes={} gpuTicks={} gpuMs={}",
                    slot.sequence,
                    slot.execution,
                    slot.path,
                    slot.frameIndex,
                    slot.decision,
                    slot.width,
                    slot.height,
                    slot.passCount,
                    gpuTicks,
                    formatGpuMillis(ticksToMillis(gpuTicks)));
            recordRayTimingSample(slot, gpuTicks);
            slot.reset();
        }
    }

    private void recordRayTimingSample(RayTimingSlot slot, long gpuTicks) {
        RayTimingKey key = new RayTimingKey(
                slot.path,
                slot.decision,
                slot.width,
                slot.height,
                slot.passCount);
        RayTimingWindow window = rayTimingWindows.computeIfAbsent(
                key,
                ignored -> new RayTimingWindow());
        if (window.warmupRemaining > 0) {
            window.warmupRemaining--;
            window.warmupDiscarded++;
            return;
        }
        window.samples[window.sampleCount++] = gpuTicks;
        if (window.sampleCount == window.samples.length) {
            logRayTimingWindow(key, window, false);
        }
    }

    private void flushRayTimingWindows() {
        for (Map.Entry<RayTimingKey, RayTimingWindow> entry : rayTimingWindows.entrySet()) {
            if (entry.getValue().sampleCount > 0) {
                logRayTimingWindow(entry.getKey(), entry.getValue(), true);
            }
        }
    }

    private void logRayTimingWindow(RayTimingKey key, RayTimingWindow window, boolean partial) {
        long[] sorted = Arrays.copyOf(window.samples, window.sampleCount);
        Arrays.sort(sorted);
        double totalTicks = 0.0;
        for (long sample : sorted) {
            totalTicks += sample;
        }
        long minTicks = sorted[0];
        long maxTicks = sorted[sorted.length - 1];
        long p50Ticks = percentileNearestRank(sorted, 0.50);
        long p95Ticks = percentileNearestRank(sorted, 0.95);
        double averageTicks = totalTicks / sorted.length;
        window.windowIndex++;
        LOGGER.info("[Vulkanite][Phase7GPU] event=ray_window path={} decision={} width={} height={} passes={} window={} partial={} samples={} warmupDiscarded={} minMs={} avgMs={} p50Ms={} p95Ms={} maxMs={} queryDrops={}",
                key.path,
                key.decision,
                key.width,
                key.height,
                key.passCount,
                window.windowIndex,
                partial,
                sorted.length,
                window.warmupDiscarded,
                formatGpuMillis(ticksToMillis(minTicks)),
                formatGpuMillis(ticksToMillis(averageTicks)),
                formatGpuMillis(ticksToMillis(p50Ticks)),
                formatGpuMillis(ticksToMillis(p95Ticks)),
                formatGpuMillis(ticksToMillis(maxTicks)),
                rayTimingDrops);
        window.sampleCount = 0;
    }

    private static long percentileNearestRank(long[] sorted, double percentile) {
        int index = Math.max(0, (int) Math.ceil(percentile * sorted.length) - 1);
        return sorted[index];
    }

    private double ticksToMillis(double ticks) {
        return ticks * (double) ctx.properties.timestampPeriodNanos / 1_000_000.0;
    }

    private static String formatGpuMillis(double millis) {
        return String.format(Locale.ROOT, "%.6f", millis);
    }

    private static void clearForeignGlErrors() {
        for (int i = 0; i < 15; i++) {
            if (VUtil._REPORT_GL_ERROR_()) {
                return;
            }
        }
        LOGGER.warn("OpenGL errors generated outside Vulkanite could not be fully cleared");
        VUtil._CHECK_GL_ERROR_();
    }

    public void updateShaderpack(String shaderpackName) {
        if (!shaderpackInitialized || !Objects.equals(currentShaderpackName, shaderpackName)) {
            LOGGER.info("Using hybrid RTX overlay for shaderpack '{}'", shaderpackName);
            CacheInvalidationTracker.global().recordShaderpack(shaderpackName);
            cacheRequestQueue.clear();
            cacheFeedbackPass.clearPendingFeedback();
            diffuseRadianceCache.reset();
            specularTransportCache.reset();
            surfaceDirectLightCache.reset();
            resetTemporalHistory();
            shadowComparisonActive = false;
        }
        shaderpackInitialized = true;
        currentShaderpackName = shaderpackName;
    }

    public void destroy() {
        if (destroyed) {
            return;
        }
        destroyed = true;
        ACTIVE_PIPELINES.remove(this);

        ctx.cmd.waitQueueIdle(0);
        cancelUnsubmittedRayTiming();
        pollCompletedRayTimings();
        flushRayTimingWindows();
        if (rayTimestampQueryPool != null) {
            int pendingTimings = 0;
            for (RayTimingSlot slot : rayTimingSlots) {
                if (slot.state != RayTimingState.FREE) {
                    pendingTimings++;
                }
            }
            LOGGER.info("[Vulkanite][Phase7GPU] event=ray_timing_closed pending={} queryDrops={}",
                    pendingTimings, rayTimingDrops);
            rayTimestampQueryPool.close();
        }

        cacheFeedbackPass.clearPendingFeedback();
        dlssdProcessor.cleanup();
        diffuseRadianceCache.destroy();
        specularTransportCache.destroy();
        surfaceDirectLightCache.destroy();
        if (shadowComparisonBuffer != null) {
            shadowComparisonBuffer.close();
        }
        if (shadowComparisonReadbackBuffer != null) {
            shadowComparisonReadbackBuffer.close();
        }
        cacheFeedbackPass.destroy();
        cacheResolvePass.destroy();
        renderPassExecutor.destroy();
        for (RtPipeline pipeline : raytracePipelines) {
            pipeline.pipeline().close();
        }
        raytracePipelines.clear();

        for (SharedImageViewTracker tracker : irisRenderTargetViews) {
            tracker.destroy();
        }
        for (SharedImageViewTracker tracker : customTextureViews) {
            tracker.destroy();
        }
        for (CustomTexture texture : customTextures) {
            safeClose(texture.image());
        }
        blockAtlasView.destroy();
        blockAtlasNormalView.destroy();
        blockAtlasSpecularView.destroy();

        frameImages.destroy();
        capture.close();
        cacheRequestQueue.clear();
        uboAllocator.reset();
        uboAllocator.clearPool();

        placeholderSpecularView.close();
        placeholderSpecular.close();
        placeholderNormalsView.close();
        placeholderNormals.close();
        sampler.close();
        customTextureSampler.close();

        ctx.cmd.newFrame();
    }

    private enum RayTimingState {
        FREE,
        RECORDING,
        RECORDED,
        SUBMITTED
    }

    private static final class RayTimingSlot {
        private final int index;
        private RayTimingState state = RayTimingState.FREE;
        private long sequence;
        private long execution;
        private String path;
        private int frameIndex;
        private String decision;
        private int width;
        private int height;
        private int passCount;

        private RayTimingSlot(int index) {
            this.index = index;
        }

        private void reset() {
            state = RayTimingState.FREE;
            sequence = 0L;
            execution = 0L;
            path = null;
            frameIndex = 0;
            decision = null;
            width = 0;
            height = 0;
            passCount = 0;
        }
    }

    private record RayTimingKey(String path, String decision, int width, int height, int passCount) {
    }

    private static final class RayTimingWindow {
        private final long[] samples = new long[RAY_TIMING_WINDOW_SAMPLES];
        private int warmupRemaining = RAY_TIMING_WARMUP_SAMPLES;
        private int warmupDiscarded;
        private int sampleCount;
        private long windowIndex;
    }

    public static void destroyActivePipelines() {
        VulkanPipeline[] pipelines;
        synchronized (ACTIVE_PIPELINES) {
            pipelines = ACTIVE_PIPELINES.toArray(VulkanPipeline[]::new);
        }

        for (VulkanPipeline pipeline : pipelines) {
            try {
                pipeline.destroy();
            } catch (Exception e) {
                LOGGER.warn("Failed to destroy active Vulkan pipeline during shutdown", e);
            }
        }
    }
}
