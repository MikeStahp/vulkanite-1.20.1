package me.cortex.vulkanite.client.rendering.cache;

/**
 * Cache families that can request bounded RTX fill or validation work.
 */
public enum CacheRequestFamily {
    SECTION_PROBE_CELL,
    DIFFUSE_RADIANCE,
    REFLECTION,
    REFRACTION
}
