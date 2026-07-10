package me.cortex.vulkanite.client.rendering.cache;

public record CacheRequestStats(
        int backlog,
        int sectionProbeBacklog,
        int diffuseRadianceBacklog,
        int reflectionBacklog,
        int refractionBacklog,
        int surfaceDirectLightBacklog,
        int lastBatchSize,
        long enqueued,
        long merged,
        long drained,
        long dropped,
        long throttled) {
}
