package me.cortex.vulkanite.client.rendering.cache;

public record CacheRequestStats(
        int backlog,
        int lastBatchSize,
        long enqueued,
        long merged,
        long drained,
        long dropped) {
}
