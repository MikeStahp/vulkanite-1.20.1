package me.cortex.vulkanite.client.rendering.cache;

/**
 * Version data that must travel with a cache entry produced from a request.
 */
public record CacheEntryVersionStamp(
        CacheRequestFamily family,
        long worldId,
        String dimensionId,
        long shaderpackGeneration,
        long skyGeneration,
        long materialGeneration,
        long cacheLayoutGeneration,
        long temporalGeneration,
        long guideGeneration,
        long familyGeneration,
        long sceneGeometryGeneration,
        long sceneLightGeneration,
        long entityGeneration,
        boolean entityDependent,
        long sectionKey,
        int sectionGeometryVersion,
        int sectionLightVersion,
        boolean sectionKnown,
        boolean sectionActive) {
    public CacheEntryVersionStamp {
        if (family == null) {
            throw new IllegalArgumentException("Cache stamp family must not be null");
        }
        dimensionId = dimensionId == null ? "" : dimensionId;
    }

    public boolean isCurrentFor(CacheRequestKey key) {
        return CacheInvalidationTracker.global().isCurrent(key, this);
    }

    /**
     * Marks a filled entry as depending on dynamic entity geometry encountered
     * during tracing. Request stamps remain entity-independent until the fill
     * result proves that dependency.
     */
    public CacheEntryVersionStamp withEntityDependency() {
        if (entityDependent) {
            return this;
        }
        return new CacheEntryVersionStamp(
                family,
                worldId,
                dimensionId,
                shaderpackGeneration,
                skyGeneration,
                materialGeneration,
                cacheLayoutGeneration,
                temporalGeneration,
                guideGeneration,
                familyGeneration,
                sceneGeometryGeneration,
                sceneLightGeneration,
                entityGeneration,
                true,
                sectionKey,
                sectionGeometryVersion,
                sectionLightVersion,
                sectionKnown,
                sectionActive);
    }
}
