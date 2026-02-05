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

import static org.lwjgl.vulkan.VK10.*;

/**
 * Executes ray tracing render passes by building descriptor sets and
 * dispatching rays.
 */
public final class RenderPassExecutor {

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
            ShaderStorageBuffer[] ssbos) {

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
            var normalView = blockAtlasNormalView.getView();
            var specularView = blockAtlasSpecularView.getView();

            var updater = new DescriptorUpdateBuilder(ctx, setReflection)
                    .set(commonSet)
                    .uniform(0, uboBuffer, uboOffset, uboSize)
                    .acceleration(1, tlas)
                    .imageSampler(3, blockAtlasView.getView(), sampler)
                    .imageSampler(4, normalView != null ? normalView : placeholderNormalsView, sampler)
                    .imageSampler(5, specularView != null ? specularView : placeholderSpecularView, sampler);

            // Reuse cached list
            outImgViewListCache.clear();

            // Determine image count from reflection
            var binding6 = setReflection.getBindingAt(6);
            int maxImages = (binding6 != null && binding6.arraySize() > 0) ? binding6.arraySize() : 1;
            int imageCount = Math.min(outImgs.size(), maxImages);

            for (int i = 0; i < imageCount; i++) {
                int index = i;
                outImgViewListCache.add(irisRenderTargetViews[i].getView(() -> vgOutImgs.get(index)));
            }
            updater.imageStore(6, 0, outImgViewListCache);
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
                updater.imageSampler(i, customTextureViews[i].getView(), ctexSampler);
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

        VImage firstOutImg = outImgs.get(0).get();
        cmd.traceRays(firstOutImg.width, firstOutImg.height, 1);

        sets.forEach(VRef::close);

        // Output image barriers
        for (var img : outImgs) {
            cmd.encodeImageTransition(img, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
                    VK_IMAGE_ASPECT_COLOR_BIT, VK_REMAINING_MIP_LEVELS);
        }
    }
}
