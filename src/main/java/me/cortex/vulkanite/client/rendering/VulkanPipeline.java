package me.cortex.vulkanite.client.rendering;

import me.cortex.vulkanite.acceleration.AccelerationManager;
import me.cortex.vulkanite.client.Vulkanite;
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
import me.cortex.vulkanite.lib.other.VUtil;
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

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.lwjgl.opengl.EXTSemaphore.GL_LAYOUT_GENERAL_EXT;
import static org.lwjgl.opengl.GL11C.glFinish;
import static org.lwjgl.util.vma.Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT;
import static org.lwjgl.vulkan.VK10.*;

public class VulkanPipeline {
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
                System.err.println(
                        "[Vulkanite] WARNING: PBR normal texture is null - using placeholder. Resource pack may not have normal maps.");
                return null;
            }
            System.out.println("[Vulkanite] PBR normal texture loaded: " + normalTex.getClass().getSimpleName());
            return ((IVGImage) normalTex).getVGImage();
        });
        this.blockAtlasSpecularView = new SharedImageViewTracker(ctx, () -> {
            AbstractTexture blockAtlas = MinecraftClient.getInstance().getTextureManager()
                    .getTexture(new Identifier("minecraft", "textures/atlas/blocks.png"));
            PBRTextureHolder holder = PBRTextureManager.INSTANCE.getOrLoadHolder(blockAtlas.getGlId());
            var specularTex = holder.specularTexture();
            if (specularTex == null) {
                System.err.println("[Vulkanite] WARNING: PBR specular texture is null - using placeholder.");
                return null;
            }
            System.out.println("[Vulkanite] PBR specular texture loaded: " + specularTex.getClass().getSimpleName());
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

        if (passes == null) {
            supportsEntities = false;
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
            var geomSetExpected = PipelineDescriptorSets.createGeomSetExpected();
            var customTexSetExpected = PipelineDescriptorSets.createCustomTexSetExpected(customTextureViews.length);
            var ssboSetExpected = PipelineDescriptorSets.createSsboSetExpected(ssboIds);

            for (int i = 0; i < passes.length; i++) {
                var builder = new RaytracePipelineBuilder();
                passes[i].apply(builder);
                var pipe = builder.build(ctx, 1);

                // Validate the layout
                int commonSet = -1;
                int geomSet = -1;
                int customTexSet = -1;
                int ssboSet = -1;

                for (int setIdx = 0; setIdx < pipe.get().reflection.getNSets(); setIdx++) {
                    var set = pipe.get().reflection.getSet(setIdx);
                    if (set.bindings().isEmpty())
                        continue;
                    if (set.validate(commonSetExpected) || set.validate(commonSetExpectedSingle)) {
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
            System.err.println(e.getMessage());
            e.printStackTrace();
            destory();
            throw new RuntimeException(e);
        }
    }

    private final EntityCapture capture = new EntityCapture();

    private void captureEntities() {
        accelerationManager.setEntityData(supportsEntities
                ? capture.capture(CapturedRenderingState.INSTANCE.getTickDelta(), MinecraftClient.getInstance().world)
                : null);
    }

    public void renderPostShadows(List<VRef<VGImage>> vgOutImgs, Camera camera, ShaderStorageBuffer[] ssbos,
            MixinCelestialUniforms celestialUniforms) {
        var prof = MinecraftClient.getInstance().getProfiler();

        for (int i = 0; i < 15; i++) {
            if (VUtil._REPORT_GL_ERROR_()) {
                break;
            } else if (i == 14) {
                System.err.println("Found OpenGL errors generated outside Vulkanite that can't be cleared");
                VUtil._CHECK_GL_ERROR_();
            }
        }

        ctx.cmd.newFrame();
        VRegistry.INSTANCE.threadLocalCollect();

        prof.push("vulkanite_capture_entities");
        captureEntities();
        prof.pop();

        PBRTextureManager.notifyPBRTexturesChanged();

        var in = ctx.sync.createSharedBinarySemaphore();
        int outImgsSize = vgOutImgs.size();
        int[] outImgsGlIds = new int[outImgsSize];
        int[] outImgsGlLayouts = new int[outImgsSize];

        for (int i = 0; i < outImgsSize; i++) {
            outImgsGlIds[i] = vgOutImgs.get(i).get().glId;
            outImgsGlLayouts[i] = GL_LAYOUT_GENERAL_EXT;
        }
        in.get().glSignal(new int[0], outImgsGlIds, outImgsGlLayouts);

        var cmdRef = ctx.cmd.getSingleUsePool().createCommandBuffer();
        var cmd = cmdRef.get();

        prof.push("vulkanite_build_tlas");
        var tlas = accelerationManager.buildTLAS(0, cmd);
        prof.pop();

        if (tlas == null) {
            VRegistry.INSTANCE.threadLocalCollect();
            glFinish();
            return;
        }

        List<VRef<VImage>> outImgs = new ArrayList<>(outImgsSize);
        for (int i = 0; i < outImgsSize; i++) {
            outImgs.add(new VRef<VImage>(vgOutImgs.get(i).get()));
        }

        var out = ctx.sync.createSharedBinarySemaphore();

        var vref_in = new VRef<VSemaphore>(in.get());
        var vref_out = new VRef<VSemaphore>(out.get());

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

            // Transition images to optimal layouts
            for (var img : outImgs) {
                cmd.encodeImageTransition(img, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
                        VK_IMAGE_ASPECT_COLOR_BIT, VK_REMAINING_MIP_LEVELS);
            }
            cmd.encodeImageTransition(blockAtlasView.getImage(), VK_IMAGE_LAYOUT_GENERAL,
                    VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_ASPECT_COLOR_BIT, VK_REMAINING_MIP_LEVELS);

            var image = blockAtlasNormalView.getImage();
            if (image != null)
                cmd.encodeImageTransition(image, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                        VK_IMAGE_ASPECT_COLOR_BIT, VK_REMAINING_MIP_LEVELS);
            image = blockAtlasSpecularView.getImage();
            if (image != null)
                cmd.encodeImageTransition(image, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                        VK_IMAGE_ASPECT_COLOR_BIT, VK_REMAINING_MIP_LEVELS);

            for (SharedImageViewTracker customtexView : customTextureViews) {
                cmd.encodeImageTransition(customtexView.getImage(), VK_IMAGE_LAYOUT_GENERAL,
                        VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_ASPECT_COLOR_BIT,
                        VK_REMAINING_MIP_LEVELS);
            }

            // Execute render passes
            for (var record : raytracePipelines) {
                renderPassExecutor.execute(cmd, record, uboBuffer.buffer(), uboBuffer.offset(), uboBuffer.size(),
                        tlas, blockAtlasView, blockAtlasNormalView, blockAtlasSpecularView,
                        placeholderNormalsView, placeholderSpecularView, irisRenderTargetViews,
                        vgOutImgs, outImgs, customTextureViews, ssbos);
            }

            // Transition images back to general layout
            cmd.encodeImageTransition(blockAtlasView.getImage(), VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                    VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_ASPECT_COLOR_BIT, VK_REMAINING_MIP_LEVELS);

            image = blockAtlasNormalView.getImage();
            if (image != null)
                cmd.encodeImageTransition(image, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL,
                        VK_IMAGE_ASPECT_COLOR_BIT, VK_REMAINING_MIP_LEVELS);
            image = blockAtlasSpecularView.getImage();
            if (image != null)
                cmd.encodeImageTransition(image, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL,
                        VK_IMAGE_ASPECT_COLOR_BIT, VK_REMAINING_MIP_LEVELS);

            for (SharedImageViewTracker customtexView : customTextureViews) {
                cmd.encodeImageTransition(customtexView.getImage(), VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                        VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_ASPECT_COLOR_BIT, VK_REMAINING_MIP_LEVELS);
            }

            prof.pop();
            ctx.cmd.submit(0, cmdRef, Arrays.asList(vref_in), Arrays.asList(vref_out), null);
        }

        cmdRef.close();
        tlas.close();

        out.get().glWait(new int[0], outImgsGlIds, outImgsGlLayouts);
        vref_in.close();
        vref_out.close();
        in.close();
        out.close();
    }

    public void destory() {
        vkDeviceWaitIdle(ctx.device);
        ctx.cmd.newFrame();
    }
}
