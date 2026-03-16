package me.cortex.vulkanite.client.rendering;

import me.cortex.vulkanite.acceleration.AccelerationManager;

import me.cortex.vulkanite.compat.IVGImage;
import me.cortex.vulkanite.compat.RaytracingShaderSet;
import me.cortex.vulkanite.lib.base.VContext;
import me.cortex.vulkanite.lib.base.VRef;
import me.cortex.vulkanite.lib.base.VRegistry;
import me.cortex.vulkanite.lib.memory.PoolLinearAllocator;

import me.cortex.vulkanite.lib.memory.VGImage;
import me.cortex.vulkanite.lib.memory.VImage;
import me.cortex.vulkanite.lib.other.VImageView;
import me.cortex.vulkanite.lib.other.VSampler;

import me.cortex.vulkanite.lib.cmd.VCmdBuff;
import me.cortex.vulkanite.lib.other.VUtil;
import me.cortex.vulkanite.lib.other.DeviceLostException;
import me.cortex.vulkanite.lib.other.sync.VSemaphore;
import me.cortex.vulkanite.lib.pipeline.RaytracePipelineBuilder;
import me.cortex.vulkanite.mixin.iris.MixinCelestialUniforms;
import net.irisshaders.iris.gl.buffer.ShaderStorageBuffer;
import net.irisshaders.iris.texture.pbr.PBRTextureHolder;
import net.irisshaders.iris.texture.pbr.PBRTextureManager;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
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

import static org.lwjgl.opengl.EXTSemaphore.GL_LAYOUT_GENERAL_EXT;
import static org.lwjgl.opengl.GL11C.glFinish;
import static org.lwjgl.util.vma.Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT;
import static org.lwjgl.vulkan.VK10.*;

public class VulkanPipeline {
    private static final Logger LOGGER = LoggerFactory.getLogger(VulkanPipeline.class);
    private final VContext ctx;
    private final AccelerationManager accelerationManager;

    private final ArrayList<RtPipeline> raytracePipelines = new ArrayList<>();

    private final VRef<VSampler> sampler;
    private final VRef<VSampler> ctexSampler;

    private final SharedImageViewTracker[] irisRenderTargetViews;
    private final SharedImageViewTracker[] customTextureViews;
    private final SharedImageViewTracker blockAtlasView;
    private final SharedImageViewTracker blockAtlasNormalView;
    private final SharedImageViewTracker blockAtlasSpecularView;

    private final VRef<VImage> placeholderSpecular;
    private final VRef<VImageView> placeholderSpecularView;
    private final VRef<VImage> placeholderNormals;
    private final VRef<VImageView> placeholderNormalsView;

    private final int maxIrisRenderTargets = 16;

    private final boolean supportsEntities;

    private final PoolLinearAllocator uboAllocator;

    private final RenderPassExecutor renderPassExecutor;

    // VulkaniteRT lightmap render targets
    // These are created on-demand when using VulkaniteRT shader pack
    private VRef<VImage> blockLightImage;
    private VRef<VImage> sunLightImage;
    private VRef<VImage> labpbrImage;
    @SuppressWarnings("unchecked")
    private VRef<VImage>[] reservoirImages = new VRef[2]; // Ping-pong ReSTIR reservoir buffers (binding 6 + 15)
    private VRef<VImage> motionVectorImage; // Motion vectors for DLSS (binding 13)
    private boolean reservoirImagesInitialized = false; // Tracks first-frame UNDEFINED→GENERAL transition
    private boolean motionVectorsInitialized = false; // Tracks first-frame clear
    private boolean depthImageInitialized = false; // Tracks first-frame UNDEFINED→GENERAL for DLSS depth buffer

    // DLSSD (Ray Reconstruction) processor
    private DLSSDProcessor dlssdProcessor;
    private net.minecraft.world.World lastWorld;
    private boolean wasPaused = false;
    private net.minecraft.client.gui.screen.Screen lastScreen;
    private long lastDlssFrameTimeNs = -1L;
    private boolean dlssTemporalPathActive = false;

    public VulkanPipeline(VContext ctx, AccelerationManager accelerationManager, RaytracingShaderSet[] passes,
            int[] ssboIds, List<VRef<VGImage>> customTextures) {
        this.ctx = ctx;
        this.accelerationManager = accelerationManager;

        // Initialize custom texture views
        this.customTextureViews = new SharedImageViewTracker[customTextures.size()];
        for (int i = 0; i < customTextures.size(); i++) {
            int index = i;
            this.customTextureViews[i] = new SharedImageViewTracker(ctx,
                    () -> new VRef<>(customTextures.get(index).get()));
        }

        // Initialize Iris render target views
        this.irisRenderTargetViews = new SharedImageViewTracker[maxIrisRenderTargets];
        for (int i = 0; i < maxIrisRenderTargets; i++) {
            this.irisRenderTargetViews[i] = new SharedImageViewTracker(ctx, null);
        }

        // Initialize block atlas views
        this.blockAtlasView = new SharedImageViewTracker(ctx, () -> {
            AbstractTexture blockAtlas = MinecraftClient.getInstance().getTextureManager()
                    .getTexture(new Identifier("minecraft", "textures/atlas/blocks.png"));
            return ((IVGImage) blockAtlas).getVGImage();
        });
        this.blockAtlasNormalView = new SharedImageViewTracker(ctx, () -> {
            AbstractTexture blockAtlas = MinecraftClient.getInstance().getTextureManager()
                    .getTexture(new Identifier("minecraft", "textures/atlas/blocks.png"));
            PBRTextureHolder holder = PBRTextureManager.INSTANCE.getOrLoadHolder(blockAtlas.getGlId());
            var normalTex = holder.normalTexture();
            if (normalTex == null) {
                LOGGER.warn("PBR normal texture is null - using placeholder. Resource pack may not have normal maps.");
                return null;
            }
            LOGGER.debug("PBR normal texture loaded: {}", normalTex.getClass().getSimpleName());
            return ((IVGImage) normalTex).getVGImage();
        });
        this.blockAtlasSpecularView = new SharedImageViewTracker(ctx, () -> {
            AbstractTexture blockAtlas = MinecraftClient.getInstance().getTextureManager()
                    .getTexture(new Identifier("minecraft", "textures/atlas/blocks.png"));
            PBRTextureHolder holder = PBRTextureManager.INSTANCE.getOrLoadHolder(blockAtlas.getGlId());
            var specularTex = holder.specularTexture();
            if (specularTex == null) {
                LOGGER.warn("PBR specular texture is null - using placeholder.");
                return null;
            }
            LOGGER.debug("PBR specular texture loaded: {}", specularTex.getClass().getSimpleName());
            return ((IVGImage) specularTex).getVGImage();
        });

        // Create placeholder textures using factory
        var specularPlaceholder = PlaceholderTextureFactory.createPlaceholderSpecular(ctx);
        this.placeholderSpecular = specularPlaceholder.image();
        this.placeholderSpecularView = specularPlaceholder.view();

        var normalsPlaceholder = PlaceholderTextureFactory.createPlaceholderNormals(ctx);
        this.placeholderNormals = normalsPlaceholder.image();
        this.placeholderNormalsView = normalsPlaceholder.view();

        // Create samplers
        this.sampler = VSampler.create(ctx, a -> a.magFilter(VK_FILTER_NEAREST)
                .minFilter(VK_FILTER_NEAREST)
                .mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST)
                .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .compareOp(VK_COMPARE_OP_NEVER)
                .maxLod(1)
                .borderColor(VK_BORDER_COLOR_INT_OPAQUE_BLACK)
                .maxAnisotropy(1.0f));

        this.ctexSampler = VSampler.create(ctx, a -> a.magFilter(VK_FILTER_LINEAR)
                .minFilter(VK_FILTER_LINEAR)
                .mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST)
                .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .compareOp(VK_COMPARE_OP_NEVER)
                .maxLod(1)
                .borderColor(VK_BORDER_COLOR_INT_OPAQUE_BLACK)
                .maxAnisotropy(1.0f));

        this.uboAllocator = new PoolLinearAllocator(ctx,
                VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                32 * 1024,
                0, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT | VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT,
                VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT);

        this.renderPassExecutor = new RenderPassExecutor(ctx, accelerationManager, sampler, ctexSampler);

        // Create VulkaniteRT lightmap render targets
        // These are storage images used for layered lighting output
        createVulkaniteRTRenderTargets();

        // Initialize DLSSD processor for Ray Reconstruction
        this.dlssdProcessor = new DLSSDProcessor(ctx);
        LOGGER.info("DLSSD Processor initialized. Supported: {}", dlssdProcessor.isSupported());

        if (passes == null) {
            supportsEntities = false;
            this.pipelineRequirements = new PipelineRequirements(false, false, false, false, false, false);
            return;
        }

        boolean supportsEntitiesT = true;
        for (var pass : passes) {
            if (pass.getRayHitCount() == 1) {
                supportsEntitiesT = false;
                break;
            }
        }
        supportsEntities = supportsEntitiesT;

        try {
            // Use factory methods for expected descriptor sets
            var commonSetExpected = PipelineDescriptorSets.createCommonSetExpected(maxIrisRenderTargets);
            var commonSetExpectedSingle = PipelineDescriptorSets.createCommonSetExpectedSingle();
            var commonSetExpectedBase = PipelineDescriptorSets.createCommonSetExpectedBase();
            var commonSetExpectedVulkaniteRT = PipelineDescriptorSets.createCommonSetExpectedVulkaniteRT();
            var geomSetExpected = PipelineDescriptorSets.createGeomSetExpected();
            var customTexSetExpected = PipelineDescriptorSets.createCustomTexSetExpected(customTextureViews.length);
            var ssboSetExpected = PipelineDescriptorSets.createSsboSetExpected(ssboIds);

            for (int i = 0; i < passes.length; i++) {
                var builder = new RaytracePipelineBuilder();
                passes[i].apply(builder);
                // Increase recursion depth to 2 to allow ClosestHit shaders to trace shadow
                // rays
                var pipe = builder.build(ctx, 2);

                // Validate the layout
                int commonSet = -1;
                int geomSet = -1;
                int customTexSet = -1;
                int ssboSet = -1;

                for (int setIdx = 0; setIdx < pipe.get().reflection.getNSets(); setIdx++) {
                    var set = pipe.get().reflection.getSet(setIdx);
                    if (set.bindings().isEmpty())
                        continue;
                    if (set.validate(commonSetExpected) || set.validate(commonSetExpectedSingle)
                            || set.validate(commonSetExpectedBase) || set.validate(commonSetExpectedVulkaniteRT)) {
                        commonSet = setIdx;
                    } else if (set.validate(geomSetExpected)) {
                        geomSet = setIdx;
                    } else if (set.validate(customTexSetExpected)) {
                        customTexSet = setIdx;
                    } else if (set.validate(ssboSetExpected)) {
                        ssboSet = setIdx;
                    } else {
                        throw new RuntimeException("Raytracing pipeline " + i
                                + " has an unexpected descriptor set layout at set " + setIdx + ". Actual: " + set);
                    }
                }

                raytracePipelines.add(new RtPipeline(pipe, commonSet, geomSet, customTexSet, ssboSet));
            }

        } catch (Exception e) {
            LOGGER.error("Failed to create raytracing pipeline: {}", e.getMessage());
            e.printStackTrace();
            destroy();
            throw new RuntimeException(e);
        }

        this.pipelineRequirements = computePipelineRequirements();
    }

    private final PipelineRequirements pipelineRequirements;

    public PipelineRequirements getPipelineRequirements() {
        return pipelineRequirements;
    }

    private PipelineRequirements computePipelineRequirements() {
        boolean needsOutput = false;
        boolean needsAlbedo = false;
        boolean needsMaterial = false;
        boolean needsNormal = false;
        boolean needsWorldPos = false;
        boolean needsExtra = false;

        // Check if DLSS Ray Reconstruction is enabled
        // If so, we MUST force G-buffer requirements to ensure Iris provides the
        // textures
        // The shader reflection might not show them if the compiler optimized them out
        // (unlikely with our fix)
        // or if we are using a shader path that doesn't explicitly bind them in the
        // reflection metadata yet.
        boolean dlssRREnabled = false;
        try {
            dlssRREnabled = me.cortex.vulkanite.client.config.DLSSConfig.load().isRayReconstructionEnabled();
        } catch (Exception e) {
            LOGGER.warn("Failed to load DLSS config for pipeline requirements check", e);
        }

        if (dlssRREnabled) {
            LOGGER.info("DLSS Ray Reconstruction enabled - forcing G-buffer requirements");
            // DLSS RR needs: Albedo, Normals, Roughness (Material), and potentially others
            needsAlbedo = true;
            needsMaterial = true;
            needsNormal = true;
            needsWorldPos = true; // Often used for position reconstruction
            needsExtra = true; // Specular/etc
        }

        for (var pipeline : raytracePipelines) {
            int commonSetIdx = pipeline.commonSet();
            if (commonSetIdx == -1)
                continue;
            var reflection = pipeline.pipeline().get().reflection;
            var set = reflection.getSet(commonSetIdx);

            if (set.getBindingAt(6) != null || set.getBindingAt(12) != null)
                needsOutput = true;
            if (set.getBindingAt(7) != null)
                needsAlbedo = true;
            if (set.getBindingAt(8) != null)
                needsMaterial = true;
            if (set.getBindingAt(9) != null)
                needsNormal = true;
            if (set.getBindingAt(10) != null)
                needsWorldPos = true;
            if (set.getBindingAt(11) != null)
                needsExtra = true;
        }

        LOGGER.info(
                "Computed Pipeline Requirements: Output={}, Albedo={}, Material={}, Normal={}, WorldPos={}, Extra={}",
                needsOutput, needsAlbedo, needsMaterial, needsNormal, needsWorldPos, needsExtra);

        return new PipelineRequirements(needsOutput, needsAlbedo, needsMaterial, needsNormal, needsWorldPos,
                needsExtra);
    }

    private final EntityCapture capture = new EntityCapture();

    private void captureEntities() {
        accelerationManager.setEntityData(supportsEntities
                ? capture.capture(CapturedRenderingState.INSTANCE.getTickDelta(), MinecraftClient.getInstance().world)
                : null);
    }

    private void clearColorImage(VCmdBuff cmd, VRef<VImage> image) {
        try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
            var clearColor = org.lwjgl.vulkan.VkClearColorValue.calloc(stack);
            clearColor.float32(0, 0.0f);
            clearColor.float32(1, 0.0f);
            clearColor.float32(2, 0.0f);
            clearColor.float32(3, 0.0f);

            var range = org.lwjgl.vulkan.VkImageSubresourceRange.calloc(stack);
            range.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT);
            range.baseMipLevel(0);
            range.levelCount(1);
            range.baseArrayLayer(0);
            range.layerCount(1);

            vkCmdClearColorImage(cmd.buffer(), image.get().image(), VK_IMAGE_LAYOUT_GENERAL, clearColor, range);
        }
    }

    public void renderPostShadows(List<VRef<VGImage>> vgOutImgs, Camera camera, ShaderStorageBuffer[] ssbos,
            MixinCelestialUniforms celestialUniforms, VRef<VImageView>[] gbufferViews) {
        checkWorldChange();
        var prof = MinecraftClient.getInstance().getProfiler();

        for (int i = 0; i < 15; i++) {
            if (VUtil._REPORT_GL_ERROR_()) {
                break;
            } else if (i == 14) {
                LOGGER.warn("Found OpenGL errors generated outside Vulkanite that can't be cleared");
                VUtil._CHECK_GL_ERROR_();
            }
        }

        // Process any pending queue submissions from BLAS worker threads
        // This ensures all vkQueueSubmit calls happen on the render thread
        ctx.cmd.processPendingSubmissions();

        ctx.cmd.newFrame();

        prof.push("vulkanite_capture_entities");
        captureEntities();
        prof.pop();

        PBRTextureManager.notifyPBRTexturesChanged();

        var in = ctx.sync.createSharedBinarySemaphore();
        var outImgsGlIds = vgOutImgs.stream().mapToInt(i -> i.get().glId).toArray();
        var outImgsGlLayouts = vgOutImgs.stream().mapToInt(i -> GL_LAYOUT_GENERAL_EXT).toArray();

        // Collect G-buffer GL image IDs so the semaphore covers them too.
        // This ensures OpenGL has finished writing the G-buffer before the RT shader
        // reads it.
        int[] gbufferGlIds = new int[0];
        int[] gbufferGlLayouts = new int[0];
        if (gbufferViews != null) {
            var gbufferGlIdList = new java.util.ArrayList<Integer>();
            for (var view : gbufferViews) {
                if (view != null && view.get() != null && view.get().image != null) {
                    var img = view.get().image.get();
                    if (img instanceof me.cortex.vulkanite.lib.memory.VGImage vgImg) {
                        gbufferGlIdList.add(vgImg.glId);
                    }
                }
            }
            gbufferGlIds = gbufferGlIdList.stream().mapToInt(Integer::intValue).toArray();
            gbufferGlLayouts = new int[gbufferGlIds.length];
            java.util.Arrays.fill(gbufferGlLayouts, GL_LAYOUT_GENERAL_EXT);
        }

        // Combine output + G-buffer IDs for the semaphore signal
        int[] allGlIds = java.util.Arrays.copyOf(outImgsGlIds, outImgsGlIds.length + gbufferGlIds.length);
        System.arraycopy(gbufferGlIds, 0, allGlIds, outImgsGlIds.length, gbufferGlIds.length);
        int[] allGlLayouts = java.util.Arrays.copyOf(outImgsGlLayouts,
                outImgsGlLayouts.length + gbufferGlLayouts.length);
        System.arraycopy(gbufferGlLayouts, 0, allGlLayouts, outImgsGlLayouts.length, gbufferGlLayouts.length);

        in.get().glSignal(new int[0], allGlIds, allGlLayouts);

        // Create separate command buffers for TLAS building and ray tracing to enable
        // better overlap
        var tlasCmdPoolRef = ctx.cmd.createSingleUsePool(0); // Use queue family 0
        VRef<VCmdBuff> tlasCmdRef;
        try {
            tlasCmdRef = tlasCmdPoolRef.get().createCommandBuffer();
        } finally {
            tlasCmdPoolRef.close(); // Close the pool reference as we only need it for creating the command buffer
        }
        var tlasCmd = tlasCmdRef.get();

        prof.push("vulkanite_build_tlas");
        var tlas = accelerationManager.buildTLAS(0, tlasCmd);
        prof.pop();

        if (tlas == null) {
            VRegistry.INSTANCE.threadLocalCollect();
            glFinish();
            in.close();
            return;
        }

        var outImgs = vgOutImgs.stream().map(i -> new VRef<VImage>(i.get())).toList();

        var tlasOut = ctx.sync.createSharedBinarySemaphore();
        // var rtIn = ctx.sync.createSharedBinarySemaphore();

        var vref_in = new VRef<VSemaphore>(in.get());
        var vref_tlas_out = new VRef<VSemaphore>(tlasOut.get());
        // var vref_rt_in = new VRef<VSemaphore>(rtIn.get());

        // Submit TLAS building command buffer immediately to overlap with G-buffer
        // generation
        ctx.cmd.submit(0, tlasCmdRef, Arrays.asList(vref_in), Arrays.asList(vref_tlas_out), null);

        // Record ray tracing commands in a separate command buffer
        var rtCmdPoolRef = ctx.cmd.createSingleUsePool(0); // Use queue family 0
        VRef<VCmdBuff> rtCmdRef;
        try {
            rtCmdRef = rtCmdPoolRef.get().createCommandBuffer();
        } finally {
            rtCmdPoolRef.close(); // Close the pool reference as we only need it for creating the command buffer
        }
        var rtCmd = rtCmdRef.get();

        var uboBuffer = uboAllocator.allocate(1024);
        {
            prof.push("vulkanite_encode_rt_passes");
            long ptr = uboBuffer.buffer().get().map();
            MemoryUtil.memSet(ptr, 0, 1024);
            {
                ByteBuffer bb = MemoryUtil.memByteBuffer(ptr, 1024);
                UBODataEncoder.encode(bb, camera, celestialUniforms);
            }
            uboBuffer.buffer().get().unmap();
            uboBuffer.buffer().get().flush();

            // Ensure shared image views are created
            blockAtlasView.getView();
            blockAtlasNormalView.getView();
            blockAtlasSpecularView.getView();
            for (var v : customTextureViews) {
                v.getView();
            }

            // Transition images to optimal layouts for ray tracing
            for (var img : outImgs) {
                rtCmd.encodeImageTransition(img, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
                        VK_IMAGE_ASPECT_COLOR_BIT, VK_REMAINING_MIP_LEVELS);
            }

            // Batch transition all texture views to shader read-only optimal layout
            List<VRef<VImage>> imagesToTransition = new ArrayList<>();

            var blockAtlasImage = blockAtlasView.getImage();
            if (blockAtlasImage != null) {
                imagesToTransition.add(blockAtlasImage);
            }

            var normalImage = blockAtlasNormalView.getImage();
            if (normalImage != null) {
                imagesToTransition.add(normalImage);
            }

            var specularImage = blockAtlasSpecularView.getImage();
            if (specularImage != null) {
                imagesToTransition.add(specularImage);
            }

            for (SharedImageViewTracker customtexView : customTextureViews) {
                var customImage = customtexView.getImage();
                if (customImage != null) {
                    imagesToTransition.add(customImage);
                }
            }

            // Perform batch transition for all images
            for (var image : imagesToTransition) {
                rtCmd.encodeImageTransition(image, VK_IMAGE_LAYOUT_GENERAL,
                        VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_ASPECT_COLOR_BIT, VK_REMAINING_MIP_LEVELS);
            }

            // Transition G-buffer views from GENERAL to SHADER_READ_ONLY_OPTIMAL for RT
            // shader reading
            // G-buffer views are bindings 7-11: colortex1-5 (albedo, material, normals,
            // world pos, extra)
            List<VRef<VImage>> gbufferImagesToTransition = new ArrayList<>();
            if (gbufferViews != null) {
                for (int i = 0; i < gbufferViews.length; i++) {
                    if (gbufferViews[i] != null && gbufferViews[i].get() != null) {
                        gbufferImagesToTransition.add(gbufferViews[i].get().image);
                    }
                }
            }
            for (var image : gbufferImagesToTransition) {
                rtCmd.encodeImageTransition(image, VK_IMAGE_LAYOUT_GENERAL,
                        VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_ASPECT_COLOR_BIT, VK_REMAINING_MIP_LEVELS);
            }

            // Ensure DLSSD is initialized and get depth buffer for writing
            VRef<VImage> dlssDepthImage = null;
            var dlssInitConfig = me.cortex.vulkanite.client.config.DLSSConfig.load();
            if (dlssdProcessor != null && !outImgs.isEmpty() && dlssInitConfig.isEnabled()) {
                int imgWidth = outImgs.get(0).get().width;
                int imgHeight = outImgs.get(0).get().height;
                if (!dlssdProcessor.isInitialized()) {
                    dlssdProcessor.initialize(imgWidth, imgHeight);
                }
                dlssDepthImage = dlssdProcessor.getDepthImage();

                // Transition depth image to GENERAL for writing by the shader — only on first
                // use.
                // Re-transitioning from UNDEFINED every frame would discard the shader's writes
                // before DLSS reads it, causing a black depth → black DLSS output.
                if (dlssDepthImage != null && !depthImageInitialized) {
                    rtCmd.encodeImageTransition(dlssDepthImage, VK_IMAGE_LAYOUT_UNDEFINED,
                            VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_ASPECT_COLOR_BIT, 1);
                    depthImageInitialized = true;
                }
            }

            // One-time transition of reservoir ping-pong images from UNDEFINED to GENERAL.
            // Without this the shader reads uninitialised memory on frame 0, poisoning
            // the ReSTIR history for many frames and causing visible shadow flicker.
            if (!reservoirImagesInitialized) {
                for (VRef<VImage> reservoirImage : reservoirImages) {
                    if (reservoirImage != null) {
                        rtCmd.encodeImageTransition(reservoirImage, VK_IMAGE_LAYOUT_UNDEFINED,
                                VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_ASPECT_COLOR_BIT, 1);
                        // Clear to black to prevent garbage data
                        clearColorImage(rtCmd, reservoirImage);
                    }
                }
                reservoirImagesInitialized = true;
            }
            
            // Initialize Motion Vectors (Clear to black on first frame)
            if (!motionVectorsInitialized && motionVectorImage != null) {
                rtCmd.encodeImageTransition(motionVectorImage, VK_IMAGE_LAYOUT_UNDEFINED,
                        VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_ASPECT_COLOR_BIT, 1);
                clearColorImage(rtCmd, motionVectorImage);
                motionVectorsInitialized = true;
            }

            // Execute render passes
            for (var record : raytracePipelines) {
                // Get sun position from celestial uniforms (returns Vector4f)
                org.joml.Vector4f sunPos = celestialUniforms.invokeGetSunPosition();
                float sunDirX = sunPos.x;
                float sunDirY = sunPos.y;
                float sunDirZ = sunPos.z;
                // Default sun color (warm white)
                float sunColorR = 1.0f;
                float sunColorG = 0.95f;
                float sunColorB = 0.8f;
                // Frame index from system time uniforms
                int frameIndex = net.irisshaders.iris.uniforms.SystemTimeUniforms.COUNTER.getAsInt();
                int sampleIndex = 0; // Could be incremented for temporal accumulation

                // Get ReSTIR setting from config
                var dlssConfig = me.cortex.vulkanite.client.config.DLSSConfig.load();
                int enableReSTIR = dlssConfig.isReSTIREnabled() ? 1 : 0;
                int debugMode = dlssConfig.isDebugMode() ? 1 : 0;
                
                // DEBUG LOG: Log debug mode status every 60 frames to avoid spam
                if (frameIndex % 60 == 0) {
                    LOGGER.info("[Vulkanite Debug] frameIndex={}, debugMode={}, enableReSTIR={}, config.enabled={}, config.debugMode={}",
                        frameIndex, debugMode, enableReSTIR, dlssConfig.isEnabled(), dlssConfig.isDebugMode());
                }

                // Determine ping-pong indices for reservoir double-buffering
                int currentReservoirIdx = frameIndex % 2;
                int prevReservoirIdx = 1 - currentReservoirIdx;

                renderPassExecutor.execute(rtCmd, record, uboBuffer.buffer(), uboBuffer.offset(), uboBuffer.size(),
                        tlas, blockAtlasView, blockAtlasNormalView, blockAtlasSpecularView,
                        placeholderNormalsView, placeholderSpecularView, irisRenderTargetViews,
                        vgOutImgs, outImgs, customTextureViews, ssbos, gbufferViews,
                        frameIndex, sampleIndex, sunDirX, sunDirY, sunDirZ, sunColorR, sunColorG, sunColorB,
                        enableReSTIR, debugMode,
                        reservoirImages[currentReservoirIdx], reservoirImages[prevReservoirIdx],
                        motionVectorImage, dlssDepthImage);
            }

            // Batch transition images back to general layout
            for (var image : imagesToTransition) {
                rtCmd.encodeImageTransition(image, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                        VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_ASPECT_COLOR_BIT, VK_REMAINING_MIP_LEVELS);
            }

            // Transition G-buffer views back to GENERAL layout for subsequent OpenGL
            // operations
            for (var image : gbufferImagesToTransition) {
                rtCmd.encodeImageTransition(image, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                        VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_ASPECT_COLOR_BIT, VK_REMAINING_MIP_LEVELS);
            }

            // Process through DLSSD (Ray Reconstruction) after ray tracing
            // This denoises the ray-traced output using AI
            // Skip DLSSD if debug mode is enabled to show raw shader output (quadrants)
            var dlssConfig = me.cortex.vulkanite.client.config.DLSSConfig.load();
            boolean debugModeEnabled = dlssConfig.isDebugMode();
            boolean dlssEnabled = dlssConfig.isEnabled(); // Remove isRayReconstructionEnabled() requirement to allow
                                                          // standard DLSS fallback

            MinecraftClient mc = MinecraftClient.getInstance();
            boolean dlssFrameActive = shouldProcessDlssFrame(mc, dlssEnabled, debugModeEnabled, outImgs);
            updateTemporalPathState(dlssFrameActive);

            if (dlssFrameActive) {
                prof.push("vulkanite_dlssd_process");
                try {
                    // Get dimensions from the output image
                    int imgWidth = outImgs.get(0).get().width;
                    int imgHeight = outImgs.get(0).get().height;

                    // Auto-initialize DLSSD if needed
                    if (!dlssdProcessor.isInitialized()) {
                        LOGGER.info("Initializing DLSSD with dimensions: {}x{}", imgWidth, imgHeight);
                        dlssdProcessor.initialize(imgWidth, imgHeight);
                    }

                    if (dlssdProcessor.isInitialized() && motionVectorImage != null) {
                        // Get the first output image as the noisy input (ray-traced output)
                        if (outImgs.isEmpty()) {
                            LOGGER.debug("DLSSD: Skipping - no output images available");
                        } else {
                            var noisyOutput = outImgs.get(0);

                            // Check if we have valid G-buffer for DLSSD
                            boolean hasValidGBuffer = gbufferViews != null &&
                                    gbufferViews.length >= 3 &&
                                    gbufferViews[0] != null && // Albedo
                                    gbufferViews[2] != null; // Normals

                            // Use the internal depth buffer from DLSSD processor
                            // DLSSD expects linear depth in D32_SFLOAT format, not world position
                            // The internal depth buffer is properly initialized and cleared each frame
                            VRef<VImage> depthImage = dlssdProcessor.getDepthImage();

                            if (noisyOutput != null && depthImage != null && depthImage.get() != null
                                    && hasValidGBuffer) {
                                // Log G-buffer configuration only occasionally
                                int logFrame = net.irisshaders.iris.uniforms.SystemTimeUniforms.COUNTER.getAsInt();
                                if (logFrame % 300 == 0) {
                                    GBufferDLSSDAdapter.logGBufferConfiguration(gbufferViews);
                                }

                                float deltaTime = computeDLSSDeltaTimeSeconds();
                                var denoisedOutput = dlssdProcessor.processFrame(
                                        rtCmd, noisyOutput, motionVectorImage, depthImage, gbufferViews, deltaTime);

                                if (denoisedOutput != null && denoisedOutput != noisyOutput) {
                                    // Copy denoised output back to the output image
                                    rtCmd.encodeImageTransition(noisyOutput, VK_IMAGE_LAYOUT_GENERAL,
                                            VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_ASPECT_COLOR_BIT, 1);
                                    rtCmd.encodeImageTransition(denoisedOutput, VK_IMAGE_LAYOUT_GENERAL,
                                            VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_ASPECT_COLOR_BIT, 1);

                                    // Copy image using blit (to handle format conversion from FP16 to UNORM8)
                                    rtCmd.blitImage(denoisedOutput, noisyOutput,
                                            VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                                            VK_FILTER_LINEAR);

                                    // Transition back to general
                                    rtCmd.encodeImageTransition(noisyOutput, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                                            VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_ASPECT_COLOR_BIT, 1);
                                    rtCmd.encodeImageTransition(denoisedOutput, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                                            VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_ASPECT_COLOR_BIT, 1);

                                    // Close the denoised output reference
                                    denoisedOutput.close();

                                    LOGGER.debug("DLSSD frame processed successfully");
                                } else {
                                    // DLSS returned the noisy input unchanged — either native evaluation failed
                                    // or DLSS was not initialized. Log periodically so we can diagnose.
                                    int dlssFailLogFrame = net.irisshaders.iris.uniforms.SystemTimeUniforms.COUNTER
                                            .getAsInt();
                                    if (dlssFailLogFrame % 60 == 0) {
                                        LOGGER.warn(
                                                "DLSSD: native evaluation returned noisyInput (black screen possible). denoisedOutput={} noisyOutput={}",
                                                denoisedOutput != null ? Long.toHexString(denoisedOutput.get().image())
                                                        : "null",
                                                noisyOutput != null ? Long.toHexString(noisyOutput.get().image())
                                                        : "null");
                                    }
                                }
                            } else {
                                LOGGER.debug("DLSSD: Skipping - hasValidGBuffer={}, noisyOutput={}, depthImage={}",
                                        hasValidGBuffer, noisyOutput != null,
                                        depthImage != null && depthImage.get() != null);
                            }
                        }
                    } else {
                        LOGGER.debug("DLSSD: Not initialized or no motion vectors");
                    }
                } catch (Exception e) {
                    LOGGER.error("DLSSD processing error: {}", e.getMessage());
                    e.printStackTrace();
                }
                prof.pop();
            }
            prof.pop();

            // Submit ray tracing command buffer after TLAS is built
            var out = ctx.sync.createSharedBinarySemaphore();
            var vref_out = new VRef<VSemaphore>(out.get());
            try {
                ctx.cmd.submit(0, rtCmdRef, Arrays.asList(vref_tlas_out), Arrays.asList(vref_out), null);
            } catch (DeviceLostException e) {
                // Log the device loss error and propagate it
                LOGGER.error("Device lost during ray tracing command submission: {}", e.getMessage());
                throw e;
            }

            for (var ref : outImgs) {
                ref.close();
            }

            out.get().glWait(new int[0], allGlIds, allGlLayouts);
            vref_in.close();
            vref_tlas_out.close();
            vref_out.close();
            in.close();
            tlasOut.close();
            out.close();
        }

        tlas.close();
    }

    private boolean shouldProcessDlssFrame(MinecraftClient mc, boolean dlssEnabled, boolean debugModeEnabled,
            List<?> outImgs) {
        if (mc == null || outImgs == null || outImgs.isEmpty()) {
            return false;
        }
        if (!dlssEnabled || debugModeEnabled) {
            return false;
        }
        if (mc.world == null || mc.currentScreen != null || mc.isPaused()) {
            return false;
        }
        return dlssdProcessor != null && dlssdProcessor.isSupported();
    }

    private void resetTemporalHistory() {
        if (dlssdProcessor != null) {
            dlssdProcessor.resetTemporalState();
        }
        UBODataEncoder.resetTemporalHistory();
        reservoirImagesInitialized = false;
        motionVectorsInitialized = false;
        depthImageInitialized = false;
        lastDlssFrameTimeNs = -1L;
    }

    private void updateTemporalPathState(boolean active) {
        if (active != dlssTemporalPathActive) {
            resetTemporalHistory();
            dlssTemporalPathActive = active;
        }
        JitterManager.setDLSSActive(false);
    }

    private void forceDisableTemporalPath() {
        resetTemporalHistory();
        dlssTemporalPathActive = false;
        JitterManager.setDLSSActive(false);
    }

    private void checkWorldChange() {
        MinecraftClient mc = MinecraftClient.getInstance();
        boolean shouldReset = false;

        // 1. Check for World Change (Dimension/Server/Save)
        if (mc.world != lastWorld) {
            LOGGER.info("World changed from {} to {}, resetting temporal state", lastWorld, mc.world);
            lastWorld = mc.world;
            shouldReset = true;
        }

        // 2. Check for Pause/Unpause
        boolean isPaused = mc.isPaused();
        if (isPaused != wasPaused) {
            LOGGER.info("Pause state changed ({} -> {}), resetting temporal state", wasPaused, isPaused);
            wasPaused = isPaused;
            shouldReset = true;
        }

            // 3. Check for Screen Change (e.g. inventory/menu transitions)
        if (mc.currentScreen != lastScreen) {
                LOGGER.info("Screen changed ({} -> {}), resetting temporal state", lastScreen, mc.currentScreen);
                shouldReset = true;
            lastScreen = mc.currentScreen;
        }

        if (shouldReset) {
            forceDisableTemporalPath();
        }
    }

    private float computeDLSSDeltaTimeSeconds() {
        long now = System.nanoTime();
        if (lastDlssFrameTimeNs <= 0L) {
            lastDlssFrameTimeNs = now;
            return 1.0f / 60.0f;
        }
        float deltaSeconds = (now - lastDlssFrameTimeNs) / 1_000_000_000.0f;
        lastDlssFrameTimeNs = now;
        return Math.max(1.0f / 240.0f, Math.min(0.1f, deltaSeconds));
    }

    public void destroy() {
        // Cleanup DLSSD processor
        if (dlssdProcessor != null) {
            dlssdProcessor.cleanup();
        }

        // Cleanup VulkaniteRT render targets to prevent memory leaks
        if (blockLightImage != null) {
            blockLightImage.close();
            blockLightImage = null;
        }
        if (sunLightImage != null) {
            sunLightImage.close();
            sunLightImage = null;
        }
        if (labpbrImage != null) {
            labpbrImage.close();
            labpbrImage = null;
        }
        for (int i = 0; i < reservoirImages.length; i++) {
            if (reservoirImages[i] != null) {
                reservoirImages[i].close();
                reservoirImages[i] = null;
            }
        }
        if (motionVectorImage != null) {
            motionVectorImage.close();
            motionVectorImage = null;
        }

        vkDeviceWaitIdle(ctx.device);
        ctx.cmd.newFrame();
    }

    /**
     * Creates VulkaniteRT-specific render targets for layered lighting.
     * These storage images are used for:
     * - blockLightImage: Indirect illumination from emissive blocks
     * - sunLightImage: Direct illumination from the sun
     * - labpbrImage: Advanced lighting model data
     * - reservoirImage: ReSTIR reservoir buffer (binding 6)
     */
    private void createVulkaniteRTRenderTargets() {
        // Get the actual game viewport resolution dynamically
        // This ensures render targets match the current rendering resolution
        MinecraftClient mc = MinecraftClient.getInstance();
        int width = mc.getWindow().getFramebufferWidth();
        int height = mc.getWindow().getFramebufferHeight();

        // Ensure we have valid dimensions (fallback to 1920x1080 if invalid)
        if (width <= 0 || height <= 0) {
            width = 1920;
            height = 1080;
        }

        // IMPORTANT: DLSS requires even dimensions (often multiples of 8/16/32 preferred)
        // Odd dimensions like 853x480 cause InvalidParameter errors in NGX
        // We align to 8 pixels to match DLSS requirements
        width = width & ~7;
        height = height & ~7;

        // Create storage images for lightmaps
        // Format: RGBA32F for high dynamic range lighting data
        // Usage: STORAGE_BIT for writing from shaders, SAMPLED_BIT for reading
        blockLightImage = ctx.memory.createImage2D(width, height, 1, VK_FORMAT_R32G32B32A32_SFLOAT,
                VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT,
                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);

        sunLightImage = ctx.memory.createImage2D(width, height, 1, VK_FORMAT_R32G32B32A32_SFLOAT,
                VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT,
                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);

        labpbrImage = ctx.memory.createImage2D(width, height, 1, VK_FORMAT_R32G32B32A32_SFLOAT,
                VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT,
                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);

        // Create reservoir images for ReSTIR ping-pong (bindings 6 + 15)
        // RGBA32F format to store reservoir data (candidate info + weights)
        // Two images enable reading last frame's data while writing the current frame,
        // eliminating the data race that causes shadow flickering.
        for (int i = 0; i < 2; i++) {
            reservoirImages[i] = ctx.memory.createImage2D(width, height, 1, VK_FORMAT_R32G32B32A32_SFLOAT,
                    VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT,
                    VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        }

        // Create motion vector image for DLSS Ray Reconstruction (binding 13)
        // RG16F format for screen-space motion vectors (R = horizontal, G = vertical)
        motionVectorImage = ctx.memory.createImage2D(width, height, 1, VK_FORMAT_R16G16_SFLOAT,
                VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT,
                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);

        LOGGER.info("Created VulkaniteRT lightmap render targets: {}x{} + motion vectors (RG32F)", width, height);
    }

    /**
     * Returns the VulkaniteRT lightmap render targets.
     * Returns null if the targets haven't been created.
     */
    public VRef<VImage> getBlockLightImage() {
        return blockLightImage;
    }

    public VRef<VImage> getSunLightImage() {
        return sunLightImage;
    }

    public VRef<VImage> getLabpbrImage() {
        return labpbrImage;
    }

    public VRef<VImage>[] getReservoirImages() {
        return reservoirImages;
    }

    /**
     * Returns the motion vector image for DLSS Ray Reconstruction.
     * Format: RG32F (R = horizontal motion, G = vertical motion)
     */
    public VRef<VImage> getMotionVectorImage() {
        return motionVectorImage;
    }

    /**
     * Returns the DLSSD processor for Ray Reconstruction.
     */
    public DLSSDProcessor getDLSSDProcessor() {
        return dlssdProcessor;
    }

    /**
     * Process a frame through DLSSD (Ray Reconstruction).
     * This should be called after ray tracing to denoise the output.
     * 
     * @param cmd           Command buffer to record DLSSD commands
     * @param noisyOutput   Noisy ray-traced color output
     * @param motionVectors Screen-space motion vectors
     * @param depth         Depth buffer
     * @param gbufferViews  G-buffer views from Iris
     * @param deltaTime     Frame delta time in seconds
     * @return Denoised output image
     */
    public VRef<VImage> processDLSSD(
            VCmdBuff cmd,
            VRef<VImage> noisyOutput,
            VRef<VImage> motionVectors,
            VRef<VImage> depth,
            VRef<VImageView>[] gbufferViews,
            float deltaTime) {

        // Auto-initialize if needed
        if (!dlssdProcessor.isInitialized()) {
            MinecraftClient mc = MinecraftClient.getInstance();
            int width = mc.getWindow().getFramebufferWidth();
            int height = mc.getWindow().getFramebufferHeight();
            dlssdProcessor.initialize(width, height);
        }

        return dlssdProcessor.processFrame(cmd, noisyOutput, motionVectors, depth, gbufferViews, deltaTime);
    }
}
