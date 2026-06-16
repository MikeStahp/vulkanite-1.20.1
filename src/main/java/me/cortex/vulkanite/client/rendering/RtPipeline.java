package me.cortex.vulkanite.client.rendering;

import me.cortex.vulkanite.lib.base.VRef;
import me.cortex.vulkanite.lib.pipeline.VRaytracePipeline;

/**
 * Data record holding ray tracing pipeline information and descriptor set
 * indices.
 */
public record RtPipeline(
        VRef<VRaytracePipeline> pipeline,
        int commonSet,
        int geomSet,
        int entityTextureSet,
        int customTexSet,
        int ssboSet) {
}
