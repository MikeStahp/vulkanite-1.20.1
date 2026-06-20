package me.cortex.vulkanite.client.rendering;

import me.cortex.vulkanite.lib.base.VRef;
import me.cortex.vulkanite.lib.cmd.VCmdBuff;
import me.cortex.vulkanite.lib.memory.VAccelerationStructure;
import me.cortex.vulkanite.lib.memory.VBuffer;
import me.cortex.vulkanite.lib.memory.VGImage;
import me.cortex.vulkanite.lib.memory.VImage;
import me.cortex.vulkanite.lib.other.VImageView;
import net.irisshaders.iris.gl.buffer.ShaderStorageBuffer;

import java.util.List;

/**
 * Minimal explicit RTX pass graph.
 *
 * <p>Radiance's renderer derives barriers and execution from named pass resources.
 * Vulkanite still reflects shaderpack ray stages, but this wrapper keeps frame
 * products explicit instead of hiding them inside the top-level pipeline class.</p>
 */
final class RtxPassGraph {
    private final List<RtPipeline> passes;
    private final RenderPassExecutor executor;

    RtxPassGraph(List<RtPipeline> passes, RenderPassExecutor executor) {
        this.passes = passes;
        this.executor = executor;
    }

    boolean isEmpty() {
        return passes.isEmpty();
    }

    void execute(Frame frame) {
        for (RtPipeline pass : passes) {
            executor.execute(
                    frame.cmd(),
                    pass,
                    frame.uboBuffer(),
                    frame.uboOffset(),
                    frame.uboSize(),
                    frame.tlas(),
                    frame.blockAtlasView(),
                    frame.blockAtlasNormalView(),
                    frame.blockAtlasSpecularView(),
                    frame.placeholderNormalsView(),
                    frame.placeholderSpecularView(),
                    frame.irisRenderTargetViews(),
                    frame.vgOutImgs(),
                    frame.outImgs(),
                    frame.customTextureViews(),
                    frame.ssbos(),
                    frame.gbufferViews(),
                    frame.frameIndex(),
                    frame.sampleIndex(),
                    frame.sunDirectionX(),
                    frame.sunDirectionY(),
                    frame.sunDirectionZ(),
                    frame.sunColorR(),
                    frame.sunColorG(),
                    frame.sunColorB(),
                    frame.enableReSTIR(),
                    frame.debugMode(),
                    frame.debugCellIndex(),
                    frame.currentReservoir(),
                    frame.previousReservoir(),
                    frame.diffuseAlbedoMetallic(),
                    frame.specularAlbedo(),
                    frame.normalRoughness(),
                    frame.motionVectors(),
                    frame.linearDepth(),
                    frame.specularHitDepth(),
                    frame.firstHitDepth(),
                    frame.blocklightDetail(),
                    frame.sectionLightBuffer(),
                    frame.sectionLightProbeBuffer(),
                    frame.sectionLightProbeFeedbackBuffer(),
                    frame.noisyOutput(),
                    frame.renderWidth(),
                    frame.renderHeight());
        }
    }

    record Frame(
            VCmdBuff cmd,
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
            int enableReSTIR,
            int debugMode,
            int debugCellIndex,
            VRef<VImage> currentReservoir,
            VRef<VImage> previousReservoir,
            VRef<VImage> diffuseAlbedoMetallic,
            VRef<VImage> specularAlbedo,
            VRef<VImage> normalRoughness,
            VRef<VImage> motionVectors,
            VRef<VImage> linearDepth,
            VRef<VImage> specularHitDepth,
            VRef<VImage> firstHitDepth,
            VRef<VImage> blocklightDetail,
            VRef<VBuffer> sectionLightBuffer,
            VRef<VBuffer> sectionLightProbeBuffer,
            VRef<VBuffer> sectionLightProbeFeedbackBuffer,
            VRef<VImage> noisyOutput,
            int renderWidth,
            int renderHeight) {
    }
}
