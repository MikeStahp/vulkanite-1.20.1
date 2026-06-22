package me.cortex.vulkanite.client.rendering;

import me.cortex.vulkanite.client.config.VulkaniteConfig.RtxCacheMode;

/** Per-frame decision for the cache-first renderer. */
enum RtxFrameDecision {
    NO_RT(false, true),
    CACHE_FILL_ONLY(true, true),
    FULL_RT_REFERENCE(true, false),
    DISABLED(false, false);

    private final boolean usesRayTracing;
    private final boolean usesCacheResolve;

    RtxFrameDecision(boolean usesRayTracing, boolean usesCacheResolve) {
        this.usesRayTracing = usesRayTracing;
        this.usesCacheResolve = usesCacheResolve;
    }

    boolean usesRayTracing() {
        return usesRayTracing;
    }

    boolean usesCacheResolve() {
        return usesCacheResolve;
    }

    boolean needsTlas() {
        return usesRayTracing;
    }

    boolean isCacheFillOnly() {
        return this == CACHE_FILL_ONLY;
    }

    static RtxFrameDecision decide(RtxCacheMode mode, boolean hasCacheMisses) {
        if (mode == null) {
            return DISABLED;
        }
        return switch (mode) {
            case FULL_RT_REFERENCE -> FULL_RT_REFERENCE;
            case CACHE_ON_HIT -> hasCacheMisses ? CACHE_FILL_ONLY : NO_RT;
            case CACHE_RESOLVE_ONLY -> NO_RT;
        };
    }
}
