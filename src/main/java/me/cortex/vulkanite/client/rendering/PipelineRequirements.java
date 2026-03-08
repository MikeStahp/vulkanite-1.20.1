package me.cortex.vulkanite.client.rendering;

public record PipelineRequirements(boolean needsOutput, boolean needsAlbedo, boolean needsMaterial, boolean needsNormal,
        boolean needsWorldPos, boolean needsExtra) {
}
