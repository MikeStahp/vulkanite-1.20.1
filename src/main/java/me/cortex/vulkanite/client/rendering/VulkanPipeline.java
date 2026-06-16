package me.cortex.vulkanite.client.rendering;

import me.cortex.vulkanite.acceleration.AccelerationManager;
import me.cortex.vulkanite.client.Vulkanite;
import me.cortex.vulkanite.client.config.DLSSConfig;
import me.cortex.vulkanite.client.config.VulkaniteConfig;
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
import net.minecraft.util.Identifier;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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
    private static final int MAX_IRIS_RENDER_TARGETS = 16;
    private static final int UBO_SIZE = 1024;

    public record CustomTexture(String name, VRef<VGImage> image) {
    }

    public enum RenderingMode {
        RTX
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
    private final StableBlocklightPass stableBlocklightPass;
    private final RtxFrameImages frameImages = new RtxFrameImages();
    private final DLSSDProcessor dlssdProcessor;
    private final PipelineRequirements pipelineRequirements;
    private final boolean supportsEntities;
    private final EntityCapture capture = new EntityCapture();

    private RtxPassGraph passGraph;
    private String currentShaderpackName;
    private net.minecraft.world.World lastWorld;
    private net.minecraft.util.math.Vec3d lastCameraPos;
    private boolean dlssTemporalPathActive;
    private long lastDlssFrameTimeNs = -1L;
    private boolean dlssDepthUsableThisFrame;
    private int entityCaptureFrame;

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
            AbstractTexture blockAtlas = MinecraftClient.getInstance().getTextureManager()
                    .getTexture(new Identifier("minecraft", "textures/atlas/blocks.png"));
            return ((IVGImage) blockAtlas).getVGImage();
        });
        blockAtlasNormalView = new SharedImageViewTracker(ctx, () -> {
            AbstractTexture blockAtlas = MinecraftClient.getInstance().getTextureManager()
                    .getTexture(new Identifier("minecraft", "textures/atlas/blocks.png"));
            PBRTextureHolder holder = PBRTextureManager.INSTANCE.getOrLoadHolder(blockAtlas.getGlId());
            var normalTexture = holder.normalTexture();
            return normalTexture == null ? null : ((IVGImage) normalTexture).getVGImage();
        });
        blockAtlasSpecularView = new SharedImageViewTracker(ctx, () -> {
            AbstractTexture blockAtlas = MinecraftClient.getInstance().getTextureManager()
                    .getTexture(new Identifier("minecraft", "textures/atlas/blocks.png"));
            PBRTextureHolder holder = PBRTextureManager.INSTANCE.getOrLoadHolder(blockAtlas.getGlId());
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
        stableBlocklightPass = new StableBlocklightPass(ctx, sampler);
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
            beginCompatibilityFrame(true, camera);
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

            glReady.get().glSignal(new int[0], imageBatch.glIds(), imageBatch.glLayouts());

            var cmdPool = ctx.cmd.createSingleUsePool(0);
            try {
                cmdRef = cmdPool.get().createCommandBuffer();
            } finally {
                cmdPool.close();
            }
            var cmd = cmdRef.get();

            profiler.push("build_tlas");
            tlas = accelerationManager.buildTLAS(0, cmd);
            profiler.pop();

            if (tlas != null) {
                profiler.push("encode_rtx");
                encodeRtxFrame(cmd, tlas, vgOutImgs, outImgs, camera, ssbos, celestialUniforms,
                        gbufferViews, renderWidth, renderHeight);
                profiler.pop();
            } else {
                updateTemporalPathState(false);
            }

            glReadySemaphore = new VRef<>(glReady.get());
            vulkanDoneSemaphore = new VRef<>(vulkanDone.get());
            ctx.cmd.submit(0, cmdRef, Arrays.asList(glReadySemaphore), Arrays.asList(vulkanDoneSemaphore), null);
            vulkanDone.get().glWait(new int[0], imageBatch.glIds(), imageBatch.glLayouts());
        } catch (DeviceLostException e) {
            LOGGER.error("Device lost during hybrid RTX frame", e);
            Vulkanite.IS_ENABLED = false;
            throw e;
        } catch (Exception e) {
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
        boolean captureTransientGeometry = config.rtxEntityCaptureEnabled || config.rtxParticleCaptureEnabled;
        if (captureEntityGeometry && supportsEntities && captureTransientGeometry) {
            int interval = Math.max(1, config.rtxEntityCaptureInterval);
            if ((entityCaptureFrame++ % interval) == 0) {
                accelerationManager.setEntityData(capture.capture(
                        CapturedRenderingState.INSTANCE.getTickDelta(),
                        MinecraftClient.getInstance().world,
                        camera,
                        config.rtxEntityCaptureEnabled ? Math.max(1, config.rtxMaxCapturedEntities) : 0,
                        config.rtxParticleCaptureEnabled ? Math.max(1, config.rtxMaxCapturedParticles) : 0,
                        config.rtxParticleCaptureEnabled));
            }
        } else {
            entityCaptureFrame = 0;
            accelerationManager.setEntityData(null);
        }
        PBRTextureManager.notifyPBRTexturesChanged();
    }

    private int[] chooseRtxRenderSize(VRef<VImageView>[] gbufferViews, int outputWidth, int outputHeight,
            ResolutionScaleManager scaleManager, DLSSConfig config, MinecraftClient mc) {
        if (isRadianceDlssCandidate(mc, config) && scaleManager.getRenderWidth() > 0 && scaleManager.getRenderHeight() > 0) {
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

    private boolean isRadianceDlssCandidate(MinecraftClient mc, DLSSConfig config) {
        return mc != null
                && mc.world != null
                && mc.currentScreen == null
                && config != null
                && config.isEnabled()
                && !config.isDebugEnabled()
                && dlssdProcessor != null
                && dlssdProcessor.isSupported();
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
            int renderHeight) {
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
        int frameIndex = SystemTimeUniforms.COUNTER.getAsInt();
        DLSSConfig dlssConfig = DLSSConfig.load();
        int debugMode = mapDebugMode(dlssConfig.getDebugType());
        prepareRadianceDlssModule(renderWidth, renderHeight, frameImages.outputWidth(), frameImages.outputHeight(),
                dlssConfig);
        // The legacy restoration pass reconstructs lighting from albedo detail and
        // adds it over blocklight that is already present in the ray-traced image.
        // That projects block textures onto nearby geometry and produces long,
        // pixelated streaks around bright emitters. Keep blocklight in the normal
        // radiance path until explicit geometric emitter lights are available.
        boolean separateStableBlocklight = false;

        passGraph.execute(new RtxPassGraph.Frame(
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
                separateStableBlocklight ? 1 : 0,
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
                frameImages.radiance(),
                renderWidth,
                renderHeight));

        transitionImages(cmd, sampledImages, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL);

        compositeToIrisTarget(cmd, outImgs, dlssConfig, gbufferViews, separateStableBlocklight);

        transitionImages(cmd, gbufferImages, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL);
        closeAll(sampledImages);
        closeAll(gbufferImages);
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

    private static boolean hasCompleteGbuffer(VRef<VImageView>[] gbufferViews) {
        if (gbufferViews == null || gbufferViews.length < 5) {
            return false;
        }
        for (int i = 0; i < 5; i++) {
            if (gbufferViews[i] == null || gbufferViews[i].get() == null) {
                return false;
            }
        }
        return true;
    }

    private static void addIfPresent(List<VRef<VImage>> images, VRef<VImage> image) {
        if (image != null) {
            images.add(image);
        }
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

    private void prepareRadianceDlssModule(int renderWidth, int renderHeight,
            int outputWidth, int outputHeight, DLSSConfig config) {
        MinecraftClient mc = MinecraftClient.getInstance();
        dlssDepthUsableThisFrame = false;

        if (!isRadianceDlssCandidate(mc, config)) {
            return;
        }

        if (!dlssdProcessor.isInitialized()
                || !dlssdProcessor.matchesDimensions(renderWidth, renderHeight, outputWidth, outputHeight)) {
            dlssdProcessor.initialize(renderWidth, renderHeight, outputWidth, outputHeight);
        }

        dlssDepthUsableThisFrame = dlssdProcessor.isInitialized()
                && dlssdProcessor.matchesDimensions(renderWidth, renderHeight, outputWidth, outputHeight);
    }

    private void compositeToIrisTarget(me.cortex.vulkanite.lib.cmd.VCmdBuff cmd, List<VRef<VImage>> outImgs,
            DLSSConfig config, VRef<VImageView>[] gbufferViews, boolean separateStableBlocklight) {
        if (outImgs.isEmpty() || frameImages.radiance() == null) {
            updateTemporalPathState(false);
            return;
        }

        MinecraftClient mc = MinecraftClient.getInstance();
        boolean dlssFrameActive = dlssDepthUsableThisFrame
                && shouldProcessDlssFrame(mc, config.isEnabled(), config.isDebugEnabled(), outImgs);
        updateTemporalPathState(dlssFrameActive);

        VRef<VImage> source = frameImages.radiance();
        boolean blocklightDetailReferenceReady = false;
        if (dlssFrameActive) {
            try {
                VRef<VImage> denoised = dlssdProcessor.processFrame(
                        cmd,
                        frameImages,
                        computeDLSSDeltaTimeSeconds());
                if (denoised != null) {
                    source = denoised;
                    frameImages.upscaleSidecars(cmd);
                    blocklightDetailReferenceReady = true;
                }
            } catch (Exception e) {
                LOGGER.warn("DLSS/RR processing failed; using noisy RTX output", e);
                updateTemporalPathState(false);
            }
        }

        if (separateStableBlocklight && blocklightDetailReferenceReady && !stableBlocklightPass.execute(
                cmd,
                source,
                gbufferViews,
                frameImages.upscaledBlocklightDetail(),
                frameImages.upscaledDiffuseAlbedoMetallic())) {
            LOGGER.warn("DLSS blocklight detail restoration was requested without complete inputs");
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

    private boolean shouldProcessDlssFrame(MinecraftClient mc, boolean dlssEnabled, boolean debugModeEnabled,
            List<?> outImgs) {
        return mc != null
                && mc.world != null
                && mc.currentScreen == null
                && !mc.isPaused()
                && outImgs != null
                && !outImgs.isEmpty()
                && dlssEnabled
                && !debugModeEnabled
                && dlssdProcessor != null
                && dlssdProcessor.isSupported()
                && dlssdProcessor.isInitialized();
    }

    private void checkWorldChange() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world != lastWorld) {
            lastWorld = mc.world;
            entityCaptureFrame = 0;
            accelerationManager.setEntityData(null);
            resetTemporalHistory();
        }

        if (mc.world == null || mc.gameRenderer == null) {
            return;
        }

        var cameraPos = mc.gameRenderer.getCamera().getPos();
        if (lastCameraPos != null && cameraPos.squaredDistanceTo(lastCameraPos) > 100.0) {
            resetTemporalHistory();
        }
        lastCameraPos = cameraPos;
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
        if (!java.util.Objects.equals(currentShaderpackName, shaderpackName)) {
            LOGGER.info("Using hybrid RTX overlay for shaderpack '{}'", shaderpackName);
        }
        currentShaderpackName = shaderpackName;
    }

    public boolean isDeferredModeActive() {
        return false;
    }

    public boolean isDeferredComputeActive() {
        return false;
    }

    public RenderingMode getRenderingMode() {
        return RenderingMode.RTX;
    }

    public DeferredLightingPass getDeferredLightingPass() {
        return null;
    }

    public DeferredGBufferManager getDeferredGBufferManager() {
        return null;
    }

    public VRef<VImage> getBlockLightImage() {
        return null;
    }

    public VRef<VImage> getSunLightImage() {
        return null;
    }

    public VRef<VImage> getLabpbrImage() {
        return null;
    }

    public VRef<VImage>[] getReservoirImages() {
        return frameImages.reservoirs();
    }

    public VRef<VImage> getMotionVectorImage() {
        return frameImages.motionVectors();
    }

    public DLSSDProcessor getDLSSDProcessor() {
        return dlssdProcessor;
    }

    public VRef<VImage> processDLSSD(me.cortex.vulkanite.lib.cmd.VCmdBuff cmd, VRef<VImage> noisyOutput,
            VRef<VImage> motionVectors, VRef<VImage> depth, VRef<VImageView>[] gbufferViews, float deltaTime) {
        if (!dlssdProcessor.isInitialized()) {
            MinecraftClient mc = MinecraftClient.getInstance();
            dlssdProcessor.initialize(mc.getWindow().getFramebufferWidth(), mc.getWindow().getFramebufferHeight());
        }
        return dlssdProcessor.processFrame(cmd, noisyOutput, motionVectors, depth, gbufferViews, deltaTime);
    }

    public void destroy() {
        ctx.cmd.waitQueueIdle(0);

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
        stableBlocklightPass.destroy();
        dlssdProcessor.cleanup();
        capture.close();
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
}
