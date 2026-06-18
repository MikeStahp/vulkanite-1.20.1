package me.cortex.vulkanite.compat;

import me.jellysquid.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import me.jellysquid.mods.sodium.client.util.NativeBuffer;

/**
 * One validated Sodium terrain mesh range ready to be copied into a Vulkan
 * geometry buffer.
 */
public record SodiumGeometry(
        TerrainRenderPass pass,
        NativeBuffer vertexData,
        int quadCount,
        long sizeBytes) {
}
