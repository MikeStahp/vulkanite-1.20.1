package me.cortex.vulkanite.client.rendering;

import me.cortex.vulkanite.acceleration.AccelerationManager;
import me.cortex.vulkanite.client.Vulkanite;
import me.cortex.vulkanite.compat.IVGBuffer;
import me.cortex.vulkanite.lib.base.VContext;
import me.cortex.vulkanite.lib.base.VRef;
import me.cortex.vulkanite.lib.cmd.VCmdBuff;
import me.cortex.vulkanite.lib.descriptors.DescriptorUpdateBuilder;
import me.cortex.vulkanite.lib.descriptors.VDescriptorSet;
import me.cortex.vulkanite.lib.memory.VAccelerationStructure;
import me.cortex.vulkanite.lib.memory.VBuffer;
import me.cortex.vulkanite.lib.memory.VGImage;
import me.cortex.vulkanite.lib.memory.VImage;
import me.cortex.vulkanite.lib.other.VImageView;
import me.cortex.vulkanite.lib.other.VSampler;
import net.irisshaders.iris.gl.buffer.ShaderStorageBuffer;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Executes ray tracing render passes by building descriptor sets and
 * dispatching rays.
 */
public final class RenderPassExecutor {
    private static final Logger LOGGER = LoggerFactory.getLogger(RenderPassExecutor.class);

    private final VContext ctx;
    private final AccelerationManager accelerationManager;
    private final VRef<VSampler> sampler;
    private final VRef<VSampler> ctexSampler;

    // Reusable list for output image views to avoid per-frame allocations
    private final List<VRef<VImageView>> outImgViewListCache = new ArrayList<>(16);
    // Reusable list for descriptor sets to avoid per-frame allocations
    private final List<VRef<VDescriptorSet>> setsCache = new ArrayList<>();

    public RenderPassExecutor(VContext ctx, AccelerationManager accelerationManager,
            VRef<VSampler> sampler, VRef<VSampler> ctexSampler) {
        this.ctx = ctx;
        this.accelerationManager = accelerationManager;
        this.sampler = sampler;
        this.ctexSampler = ctexSampler;
    }

    /**
     * Executes a single ray tracing pipeline pass.
     *
     * @param gbufferViews       Array of G-buffer image views for hybrid rendering:
     *                           [0] = colortex1 (Albedo)
     *                           [1] = colortex2 (Material Properties)
     *                           [2] = colortex3 (Normal)
     *                           [3] = colortex4 (World Position)
     *                           [4] = colortex5 (Additional Properties)
     * @param reservoirImageView Optional reservoir image view for ReSTIR (binding
     *                           6)
     */
    public void execute(
            VCmdBuff cmd,
            RtPipeline record,
            VRef<VBuffer> uboBuffer,
            long uboOffset,
            long uboSize,
            VRef<VAccelerationStructure> tlas,
            SharedImageViewTracker blockAtlasView,
            SharedImageViewTracker blockAtlasNormalView,
            SharedImageViewTracker blockAtlasSpecularView,
            VRef<VImageView> placeholderNormalsView,
            VRef<VImageView> placeholderSpecularView,
            SharedImageViewTracker[] irisRenderTargetViews,
            List<VRef<VGImage>> vgOutImgs,
            List<VRef<VImage>> outImgs,
            SharedImageViewTracker[] customTextureViews,
            ShaderStorageBuffer[] ssbos,
            VRef<VImageView>[] gbufferViews,
            int frameIndex,
            int sampleIndex,
            float sunDirectionX,
            float sunDirectionY,
            float sunDirectionZ,
            float sunColorR,
            float sunColorG,
            float sunColorB,
            int enableReSTIR, // New parameter for ReSTIR toggle (0/1)
            int debugMode, // New parameter for Debug Mode (0/1)
            VRef<VImage> currentReservoirImage,
            VRef<VImage> prevReservoirImage,
            VRef<VImage> motionVectorImage,
            VRef<VImage> linearDepthImage) {

        // Early empty check to avoid unnecessary encoding work
        if (outImgs.isEmpty()) {
            LOGGER.warn("No output images found for ray tracing. Skipping pass.");
            return;
        }

        List<VRef<?>> resourcesToClose = new ArrayList<>();
        try {
            var pipeline = record.pipeline();
            var reflection = pipeline.get().reflection;
            cmd.bindRT(pipeline);

            var layouts = reflection.getLayouts();
            int layoutCount = layouts.size();

            setsCache.clear();
            for (int i = 0; i < layoutCount; i++) {
                setsCache.add(null);
            }
            var sets = setsCache;

            int commonSetIdx = record.commonSet();
            if (commonSetIdx != -1) {
                var commonSet = Vulkanite.INSTANCE.getPoolByLayout(layouts.get(commonSetIdx)).get().allocateSet();
                var setReflection = reflection.getSet(commonSetIdx);

                // Get view references, using placeholders as fallback
                var blockView = blockAtlasView.getView();
                if (blockView != null)
                    resourcesToClose.add(blockView);

                var normalView = blockAtlasNormalView.getView();
                if (normalView != null)
                    resourcesToClose.add(normalView);

                var specularView = blockAtlasSpecularView.getView();
                if (specularView != null)
                    resourcesToClose.add(specularView);

                var updater = new DescriptorUpdateBuilder(ctx, setReflection)
                        .set(commonSet)
                        .uniform(0, uboBuffer, uboOffset, uboSize)
                        .acceleration(1, tlas)
                        .imageSampler(3, blockView, sampler)
                        .imageSampler(4, normalView != null ? normalView : placeholderNormalsView, sampler)
                        .imageSampler(5, specularView != null ? specularView : placeholderSpecularView, sampler);

                // Bind G-buffer textures for hybrid rendering (bindings 7-11)
                // Binding 7: colortex1 (Albedo) - gbufferViews[0]
                // Binding 8: colortex2 (Material) - gbufferViews[1]
                // Binding 9: colortex3 (Normal) - gbufferViews[2]
                // Binding 10: colortex4 (WorldPos) - gbufferViews[3]
                // Binding 11: colortex5 (Extra) - gbufferViews[4]
                if (gbufferViews != null && gbufferViews.length >= 5) {
                    if (gbufferViews[0] != null) {
                        LOGGER.info("Binding 7: gbufferAlbedo OK");
                        updater.imageSampler(7, gbufferViews[0], sampler);
                    } else {
                        LOGGER.warn("Binding 7: gbufferAlbedo is NULL! Binding placeholder.");
                        updater.imageSampler(7, placeholderNormalsView, sampler); // Fallback
                    }

                    if (gbufferViews[1] != null)
                        updater.imageSampler(8, gbufferViews[1], sampler);
                    else
                        updater.imageSampler(8, placeholderNormalsView, sampler);

                    if (gbufferViews[2] != null)
                        updater.imageSampler(9, gbufferViews[2], sampler);
                    else
                        updater.imageSampler(9, placeholderNormalsView, sampler);

                    if (gbufferViews[3] != null)
                        updater.imageSampler(10, gbufferViews[3], sampler);
                    else
                        updater.imageSampler(10, placeholderNormalsView, sampler);

                    if (gbufferViews[4] != null)
                        updater.imageSampler(11, gbufferViews[4], sampler);
                    else
                        updater.imageSampler(11, placeholderNormalsView, sampler);
                } else {
                    LOGGER.error("G-Buffer views array is NULL or too small!");
                }

                // Reuse cached list
                outImgViewListCache.clear();

                // Check if binding 12 exists for final output
                var binding12 = setReflection.getBindingAt(12);

                // Check if binding 6 is a storage image (ReSTIR reservoir) or an image array
                var binding6 = setReflection.getBindingAt(6);
                if (binding6 != null) {
                    // Check for single image (arraySize == 0 in SpirvParser)
                    if (binding6.descriptorType() == VK_DESCRIPTOR_TYPE_STORAGE_IMAGE && binding6.arraySize() == 0) {
                        if (binding12 != null) {
                            // VulkaniteRT/Dirt-RT-Optimized: binding 6 is reservoir/intermediate
                            if (currentReservoirImage != null) {
                                LOGGER.info(
                                        "Binding 6: Binding ReSTIR reservoir image (VulkaniteRT/Dirt-RT-Optimized path)");
                                var reservoirView = VImageView.create(ctx, currentReservoirImage);
                                resourcesToClose.add(reservoirView);
                                updater.imageStore(6, reservoirView);
                            } else {
                                LOGGER.warn("Binding 6: Reservoir image is null!");
                            }
                        } else {
                            // dirtrtold: binding 6 is the output
                            LOGGER.info("Binding 6: Binding final output as fallback (dirtrtold path)");
                            if (outImgViewListCache.isEmpty() && !outImgs.isEmpty()) {
                                var view = irisRenderTargetViews[0].getView(() -> vgOutImgs.get(0));
                                if (view != null)
                                    resourcesToClose.add(view);
                                outImgViewListCache.add(view);
                            }
                            if (!outImgViewListCache.isEmpty()) {
                                updater.imageStore(6, outImgViewListCache.get(0));
                            } else {
                                LOGGER.warn("Binding 6: No output images available for fallback!");
                            }
                        }
                    } else if (binding6.arraySize() > 0) {
                        LOGGER.info("Binding 6: Binding array of " + binding6.arraySize() + " images (Legacy path)");
                        // Traditional use: binding 6 as an array of render targets
                        int maxImages = binding6.arraySize();
                        int imageCount = Math.min(outImgs.size(), maxImages);

                        for (int i = 0; i < imageCount; i++) {
                            int index = i;
                            var view = irisRenderTargetViews[i].getView(() -> vgOutImgs.get(index));
                            if (view != null)
                                resourcesToClose.add(view);
                            outImgViewListCache.add(view);
                        }

                        updater.imageStore(6, 0, outImgViewListCache); // Intermediate buffer array
                    }
                }
                if (binding12 != null) {
                    // Use the first output image for final output if no dedicated intermediate
                    // buffer
                    if (outImgViewListCache.isEmpty() && !outImgs.isEmpty()) {
                        var view = irisRenderTargetViews[0].getView(() -> vgOutImgs.get(0));
                        if (view != null)
                            resourcesToClose.add(view);
                        outImgViewListCache.add(view);
                    }
                    updater.imageStore(12, 0, outImgViewListCache); // Final output
                }

                // Add Binding 13 for Motion Vectors
                var binding13 = setReflection.getBindingAt(13);
                if (binding13 != null) {
                    if (motionVectorImage != null) {
                        var mvView = VImageView.create(ctx, motionVectorImage);
                        resourcesToClose.add(mvView);
                        updater.imageStore(13, mvView);
                    } else if (outImgViewListCache.size() > 0) {
                        // Fallback: use first output image as dummy target if MV image is null
                        // This prevents validation errors when DLSS is disabled but shader expects
                        // binding
                        updater.imageStore(13, outImgViewListCache.get(0));
                    }
                }

                // Add Binding 14 for Linear Depth
                var binding14 = setReflection.getBindingAt(14);
                if (binding14 != null) {
                    if (linearDepthImage != null) {
                        var depthView = VImageView.create(ctx, linearDepthImage);
                        resourcesToClose.add(depthView);
                        updater.imageStore(14, depthView);
                    } else if (outImgViewListCache.size() > 0) {
                        // Fallback: use first output image as dummy target if depth image is null
                        // This prevents validation errors when DLSS is disabled but shader expects
                        // binding
                        updater.imageStore(14, outImgViewListCache.get(0));
                    }
                }

                // Add Binding 15 for Previous Frame Reservoir (ReSTIR ping-pong read)
                var binding15 = setReflection.getBindingAt(15);
                if (binding15 != null) {
                    if (prevReservoirImage != null) {
                        var prevResView = VImageView.create(ctx, prevReservoirImage);
                        resourcesToClose.add(prevResView);
                        updater.imageStore(15, prevResView);
                    } else if (outImgViewListCache.size() > 0) {
                        // Fallback: use first output image as dummy target if prev reservoir is null
                        updater.imageStore(15, outImgViewListCache.get(0));
                    }
                }

                updater.apply();

                sets.set(commonSetIdx, commonSet);
            }

            int geomSetIdx = record.geomSet();
            if (geomSetIdx != -1) {
                sets.set(geomSetIdx, accelerationManager.getGeometrySet());
            }

            int customTexSetIdx = record.customTexSet();
            if (customTexSetIdx != -1) {
                var ctexSet = Vulkanite.INSTANCE.getPoolByLayout(layouts.get(customTexSetIdx)).get().allocateSet();
                var updater = new DescriptorUpdateBuilder(ctx, reflection.getSet(customTexSetIdx)).set(ctexSet);

                for (int i = 0, len = customTextureViews.length; i < len; i++) {
                    var view = customTextureViews[i].getView();
                    if (view != null)
                        resourcesToClose.add(view);
                    updater.imageSampler(i, view, ctexSampler);
                }
                updater.apply();
                sets.set(customTexSetIdx, ctexSet);
            }

            int ssboSetIdx = record.ssboSet();
            if (ssboSetIdx != -1) {
                var ssboSet = Vulkanite.INSTANCE.getPoolByLayout(layouts.get(ssboSetIdx)).get().allocateSet();
                var updater = new DescriptorUpdateBuilder(ctx, reflection.getSet(ssboSetIdx)).set(ssboSet);

                for (ShaderStorageBuffer ssbo : ssbos) {
                    updater.buffer(ssbo.getIndex(), new VRef<>(((IVGBuffer) ssbo).getBuffer().get()));
                }
                updater.apply();
                sets.set(ssboSetIdx, ssboSet);
            }

            // Fill gaps with empty descriptor sets
            for (int i = 0; i < layoutCount; i++) {
                if (sets.get(i) == null) {
                    sets.set(i, Vulkanite.INSTANCE.getPoolByLayout(layouts.get(i)).get().allocateSet());
                }
            }

            cmd.bindDSet(sets);

            // Memory barrier: ensure descriptor set updates (including geometry buffers and
            // TLAS)
            // are visible before ray tracing dispatch reads them
            cmd.encodeMemoryBarrier();

            // Push constants for ray tracing shader
            // Layout: uint frameIndex, uint sampleIndex, vec3 sunDirection, vec3 sunColor,
            // int enableReSTIR
            // Total: 4 + 4 + 12 + 12 + 4 = 36 bytes (9 longs)
            long[] pushConstants = new long[9];
            // Pack frameIndex (uint) and sampleIndex (uint) into first long
            pushConstants[0] = (long) frameIndex & 0xFFFFFFFFL | (((long) sampleIndex & 0xFFFFFFFFL) << 32);
            // Pack sunDirection (vec3)
            pushConstants[1] = (long) Float.floatToRawIntBits(sunDirectionX) & 0xFFFFFFFFL |
                    (((long) Float.floatToRawIntBits(sunDirectionY) & 0xFFFFFFFFL) << 32);
            pushConstants[2] = (long) Float.floatToRawIntBits(sunDirectionZ) & 0xFFFFFFFFL |
                    (((long) Float.floatToRawIntBits(sunColorR) & 0xFFFFFFFFL) << 32);
            pushConstants[3] = (long) Float.floatToRawIntBits(sunColorG) & 0xFFFFFFFFL |
                    (((long) Float.floatToRawIntBits(sunColorB) & 0xFFFFFFFFL) << 32);
            // Pack enableReSTIR (int) and debugMode (int)
            pushConstants[4] = ((long) enableReSTIR & 0xFFFFFFFFL) | (((long) debugMode & 0xFFFFFFFFL) << 32);

            VImage firstOutImg = outImgs.get(0).get();
            cmd.pushConstants(0, pushConstants, VK_SHADER_STAGE_ALL);
            cmd.traceRays(firstOutImg.width, firstOutImg.height, 1);

            sets.forEach(VRef::close);

            // No need for explicit barriers here since we're transitioning to the same
            // layout
            // The synchronization is handled by the semaphore signaling between Vulkan and
            // OpenGL
        } finally {
            for (var ref : resourcesToClose) {
                if (ref != null)
                    ref.close();
            }
            outImgViewListCache.clear();
        }
    }
}