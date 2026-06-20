package me.cortex.vulkanite.client.rendering.cache;

/**
 * Source that discovered a missing or stale cache entry.
 */
public enum CacheRequestSource {
    SECTION_DIRTY_QUEUE,
    VISIBLE_GBUFFER,
    GPU_FEEDBACK,
    REFLECTION_SURFACE,
    REFRACTION_SURFACE,
    VALIDATION
}
