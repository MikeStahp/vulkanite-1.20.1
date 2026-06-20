package me.cortex.vulkanite.client.rendering;

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
import me.cortex.vulkanite.lib.other.VImageView;
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
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static org.lwjgl.util.vma.Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT;
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
    private static final long CACHE_REQUEST_LOG_INTERVAL_NANOS = 5_000_000_000L;

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
    private final CacheRequestQueue cacheRequestQueue = new CacheRequestQueue(MAX_CACHE_REQUEST_BACKLOG);
    private final RtxFrameImages frameImages = new RtxFrameImages();
    private final DLSSDProcessor dlssdProcessor;
    private final PipelineRequirements pipelineRequirements;
    private final boolean supportsEntities;
    private final EntityCapture capture = new EntityCapture();

    private RtxPassGraph passGraph;
    private String currentShaderpackName;
    private boolean shaderpackInitialized;
    private boolean entityCacheStateActive;
    private net.minecraft.world.World lastWorld;
    private net.minecraft.util.math.Vec3d lastCameraPos;
    private boolean dlssTemporalPathActive;
    private long lastDlssFrameTimeNs = -1L;
    private int entityCaptureFrame;
    private int transientCaptureThrottle = 1;
    private RtxCacheMode lastLoggedRtxCacheMode;
    private long lastCacheRequestLogNanos;
    private boolean destroyed;

    public VulkanPipeline(VContext ctx, AccelerationManager accelerationManager, RaytracingShaderSet[] passes,
            int[] ssboIds, List<CustomTexture> customTextures) {
        this.ctx = ctx;
        this.accelerationManager = accelerationManager;

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
        passGraph = new RtxPassGraph(raytracePipelines, renderPassExecutor);

        boolean hasRt = !raytracePipelines.isEmpty();
        pipelineRequirements = new PipelineRequirements(hasRt, hasRt, hasRt, hasRt, hasRt, hasRt);
        ACTIVE_PIPELINES.add(this);
        LOGGER.info("Hybrid RTX pipeline created: passes={}, entities={}", raytracePipelines.size(), supportsEntities);
    }

    private void buildRayPipelines(RaytracingShaderSet[] passes, int[] ssboIds) {
        var commonSetExpected = PipelineDescriptorSets.createCommonSetExpected(MAX_IRIS_RENDER_TARGETS);
        var commonSetExpectedSingle = PipelineDescriptorSets.createCommonSetExpectedSingle();
        var commonSetExpectedBase = PipelineDescriptorSets.createCommonSetExpectedBase();
        var commonSetExpectedVulkaniteRT = PipelineDescriptorSets.createCommonSetExpectedVulkaniteRT();
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

                    if (set.validate(commonSetExpected)
                            || set.validate(commonSetExpectedSingle)
                            || set.validate(commonSetExpectedBase)
                            || set.validate(commonSetExpectedVulkaniteRT)) {
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
                        pipeline, commonSet, geomSet, entityTextureSet, customTexSet, ssboSet));
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
        if (passGraph == null || passGraph.isEmpty() || vgOutImgs == null || vgOutImgs.isEmpty()) {
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
        VRef<me.cortex.vulkanite.lib.cmd.VCmdBuff> cmdRef = null;

        try {
            RtxCacheMode rtxCacheMode = VulkaniteConfig.getInstance().getRtxCacheMode();
            logRtxCacheMode(rtxCacheMode);
            beginCompatibilityFrame(rtxCacheMode.requiresTlas(), camera);
            List<VRef<VGImage>> entityTextureImages = accelerationManager.getEntityTextureImages();
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

            if (rtxCacheMode.requiresTlas()) {
                profiler.push("build_tlas");
                tlas = accelerationManager.buildTLAS(0, cmd);
                profiler.pop();
            }

            if (tlas != null || !rtxCacheMode.usesFullRtPass()) {
                profiler.push("encode_rtx");
                encodeRtxFrame(cmd, tlas, vgOutImgs, outImgs, camera, ssbos, celestialUniforms,
                        gbufferViews, renderWidth, renderHeight, rtxCacheMode);
                profiler.pop();
            } else {
                updateTemporalPathState(false);
            }

            glReadySemaphore = new VRef<>(glReady.get());
            vulkanDoneSemaphore = new VRef<>(vulkanDone.get());
            long execution = ctx.cmd.submit(
                    0, cmdRef, Arrays.asList(glReadySemaphore), Arrays.asList(vulkanDoneSemaphore), null);
            cacheFeedbackPass.markEncodedFeedbackSubmitted(execution);
            vulkanDone.get().glWait(EMPTY_GL_SEMAPHORE_IDS, imageBatch.glIds(), imageBatch.glLayouts());
        } catch (DeviceLostException e) {
            cacheFeedbackPass.discardEncodedFeedback();
            LOGGER.error("Device lost during hybrid RTX frame", e);
            Vulkanite.IS_ENABLED = false;
            throw e;
        } catch (Exception e) {
            cacheFeedbackPass.discardEncodedFeedback();
            LOGGER.error("Hybrid RTX frame failed", e);
            updateTemporalPathState(false);
        } finally {
            if (tlas != null) {
                tlas.close();
            }
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
        ctx.cmd.processPendingSubmissions();
        ctx.cmd.newFrame();
        VulkaniteConfig config = VulkaniteConfig.getInstance();
        boolean captureTransientGeometry = config.rtxEntityCaptureEnabled;
        if (captureEntityGeometry && supportsEntities && captureTransientGeometry) {
            int interval = Math.max(1, config.rtxEntityCaptureInterval) * transientCaptureThrottle;
            if ((entityCaptureFrame++ % interval) == 0) {
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
                CacheInvalidationTracker.global().recordEntityGeometryChanged();
                long duration = System.nanoTime() - start;
                if (duration > TRANSIENT_CAPTURE_BUDGET_NS) {
                    transientCaptureThrottle = Math.min(MAX_TRANSIENT_CAPTURE_THROTTLE, transientCaptureThrottle + 1);
                } else if (duration < TRANSIENT_CAPTURE_BUDGET_NS / 2 && transientCaptureThrottle > 1) {
                    transientCaptureThrottle--;
                }
            }
        } else {
            entityCaptureFrame = 0;
            transientCaptureThrottle = 1;
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
            List<VRef<VGImage>> vgOutImgs,
            List<VRef<VImage>> outImgs,
            Camera camera,
            ShaderStorageBuffer[] ssbos,
            MixinCelestialUniforms celestialUniforms,
            VRef<VImageView>[] gbufferViews,
            int renderWidth,
            int renderHeight,
            RtxCacheMode rtxCacheMode) {
        DLSSConfig dlssConfig = DLSSConfig.load();
        boolean dlssRuntimeEligible = dlssdProcessor.canUseConfiguredRenderScale(MinecraftClient.getInstance(), dlssConfig);
        if (CacheInvalidationTracker.global().recordDlssMode(dlssConfig, dlssRuntimeEligible)) {
            resetTemporalHistory();
        }
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

        List<VRef<VImage>> sampledImages = collectSampledImages();
        List<VRef<VImage>> gbufferImages = collectGbufferImages(gbufferViews);
        transitionImages(cmd, sampledImages, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        transitionImages(cmd, gbufferImages, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);

        var sunPos = celestialUniforms.invokeGetSunPosition();
        recordSkyGeneration();
        int frameIndex = SystemTimeUniforms.COUNTER.getAsInt();
        int debugMode = mapDebugMode(dlssConfig.getDebugType());
        CacheRequestBatch cacheRequestBatch = collectCacheRequests(camera, frameIndex, rtxCacheMode);
        VRef<VBuffer> sectionLightBuffer = null;
        VRef<VBuffer> sectionLightProbeBuffer = null;
        VRef<VBuffer> sectionLightProbeFeedbackBuffer = null;
        VRef<VBuffer> sectionLightProbeFillRequestBuffer = null;
        VRef<VBuffer> diffuseRadianceCacheBuffer = null;
        VRef<VBuffer> diffuseRadianceFillRequestBuffer = null;
        try {
            if (rtxCacheMode.usesFullRtPass() || rtxCacheMode.usesCacheResolvePass()) {
                diffuseRadianceCacheBuffer = diffuseRadianceCache.ensureCacheGpuBuffer(ctx, cmd);
            }
            if (rtxCacheMode.usesFullRtPass()) {
                var sectionLightManager = Vulkanite.INSTANCE.getSectionLightManager();
                sectionLightBuffer = sectionLightManager.ensureGpuBuffer(ctx, cmd);
                sectionLightProbeBuffer = sectionLightManager.ensureProbeGpuBuffer(ctx, cmd);
                sectionLightProbeFeedbackBuffer =
                        sectionLightManager.ensureProbeFeedbackGpuBuffer(ctx, cmd);
                sectionLightProbeFillRequestBuffer =
                        sectionLightManager.ensureProbeFillRequestGpuBuffer(ctx, cmd, cacheRequestBatch);
                diffuseRadianceFillRequestBuffer =
                        diffuseRadianceCache.ensureFillRequestGpuBuffer(ctx, cmd, cacheRequestBatch);
            }

            RtxPassGraph.Frame frame = new RtxPassGraph.Frame(
                    cmd,
                    ubo.buffer(),
                    ubo.offset(),
                    ubo.size(),
                    tlas,
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
                    frameImages.radiance(),
                    renderWidth,
                    renderHeight);
            if (rtxCacheMode.usesFullRtPass()) {
                passGraph.execute(frame);
            }
            if (rtxCacheMode.collectsCacheRequests()) {
                cacheFeedbackPass.execute(frame);
            }
            if (rtxCacheMode.usesCacheResolvePass()) {
                cacheResolvePass.execute(frame, rtxCacheMode == RtxCacheMode.CACHE_RESOLVE_ONLY);
            }
        } finally {
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

    private CacheRequestBatch collectCacheRequests(Camera camera, int frameIndex, RtxCacheMode rtxCacheMode) {
        if (!rtxCacheMode.collectsCacheRequests()) {
            cacheFeedbackPass.clearPendingFeedback();
            CacheRequestStats stats = cacheRequestQueue.snapshot();
            return new CacheRequestBatch(List.of(), stats.backlog(), stats);
        }

        CacheInvalidationTracker invalidationTracker = CacheInvalidationTracker.global();
        cacheRequestQueue.discardIf(request ->
                !invalidationTracker.isCurrent(request.key(), request.versionStamp()));
        int feedbackRequests = cacheFeedbackPass.ingestPendingFeedback(cacheRequestQueue);
        int sectionRequests = Vulkanite.INSTANCE.getSectionLightManager().drainPendingCacheRequests(
                cacheRequestQueue,
                cameraSection(camera),
                frameIndex,
                MAX_CACHE_REQUESTS_PER_FRAME);
        CacheRequestBatch batch;
        if (rtxCacheMode == RtxCacheMode.CACHE_FILL) {
            batch = cacheRequestQueue.drainBatch(
                    MAX_CACHE_REQUESTS_PER_FRAME,
                    frameIndex,
                    request -> request.key().family() == CacheRequestFamily.SECTION_PROBE_CELL
                            || request.key().family() == CacheRequestFamily.DIFFUSE_RADIANCE);
        } else {
            CacheRequestStats stats = cacheRequestQueue.snapshot();
            batch = new CacheRequestBatch(List.of(), stats.backlog(), stats);
        }
        logCacheRequestBatch(rtxCacheMode, batch, sectionRequests, feedbackRequests);
        return batch;
    }

    private void logCacheRequestBatch(
            RtxCacheMode mode,
            CacheRequestBatch batch,
            int sectionRequests,
            int feedbackRequests) {
        CacheRequestStats stats = batch.stats();
        boolean hasActivity = batch.hasWork()
                || sectionRequests > 0
                || feedbackRequests > 0
                || stats.backlog() > 0;
        if (!hasActivity) {
            return;
        }

        long now = System.nanoTime();
        if (LOGGER.isTraceEnabled()) {
            LOGGER.trace("[Vulkanite] Cache requests: mode={}, batch={}, section={}, feedback={}, backlog={}, enqueued={}, merged={}, drained={}, dropped={}",
                    mode.configValue(), batch.size(), sectionRequests, feedbackRequests, stats.backlog(),
                    stats.enqueued(), stats.merged(), stats.drained(), stats.dropped());
        } else if (now - lastCacheRequestLogNanos >= CACHE_REQUEST_LOG_INTERVAL_NANOS) {
            lastCacheRequestLogNanos = now;
            LOGGER.info("[Vulkanite] Cache requests: mode={}, batch={}, section={}, feedback={}, backlog={}, enqueued={}, merged={}, drained={}, dropped={}",
                    mode.configValue(), batch.size(), sectionRequests, feedbackRequests, stats.backlog(),
                    stats.enqueued(), stats.merged(), stats.drained(), stats.dropped());
        }
    }

    private static ChunkSectionPos cameraSection(Camera camera) {
        if (camera == null) {
            return null;
        }
        Vec3d pos = camera.getPos();
        return ChunkSectionPos.from(
                floorToSection(pos.x),
                floorToSection(pos.y),
                floorToSection(pos.z));
    }

    private static int floorToSection(double coordinate) {
        return (int) Math.floor(coordinate) >> 4;
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
            Vulkanite.INSTANCE.getSectionLightManager().clear();
            resetTemporalHistory();
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

    private static int mapDebugMode(DLSSConfig.DebugType debugType) {
        return switch (debugType) {
            case NONE -> 0;
            case INPUT, OUTPUT -> 1;
            case MOTION_VECTORS, DEPTH, NORMALS -> 2;
        };
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
            resetTemporalHistory();
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

        cacheFeedbackPass.clearPendingFeedback();
        dlssdProcessor.cleanup();
        diffuseRadianceCache.destroy();
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
