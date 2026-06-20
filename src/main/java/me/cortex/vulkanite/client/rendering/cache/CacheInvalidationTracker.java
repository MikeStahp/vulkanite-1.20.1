package me.cortex.vulkanite.client.rendering.cache;

import me.cortex.vulkanite.client.config.DLSSConfig;
import net.minecraft.util.math.ChunkSectionPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Central source of cache validity generations.
 *
 * <p>Future cache entries should store {@link CacheEntryVersionStamp} when they
 * are filled and reject hits when this tracker no longer matches the stamp.</p>
 */
public final class CacheInvalidationTracker {
    private static final Logger LOGGER = LoggerFactory.getLogger(CacheInvalidationTracker.class);
    private static final CacheInvalidationTracker GLOBAL = new CacheInvalidationTracker();
    private static final long CACHE_LAYOUT_VERSION = 1L;
    private static final long SKY_TIME_BUCKET_TICKS = 100L;

    private final EnumMap<CacheRequestFamily, Long> familyGenerations =
            new EnumMap<>(CacheRequestFamily.class);
    private final Map<Long, SectionVersion> sectionVersions = new HashMap<>();

    private long worldId = 1L;
    private String dimensionId = "";
    private long shaderpackGeneration = 1L;
    private long skyGeneration = 1L;
    private long materialGeneration = 1L;
    private long cacheLayoutGeneration = CACHE_LAYOUT_VERSION;
    private long temporalGeneration = 1L;
    private long guideGeneration = 1L;
    private long sceneGeometryGeneration = 1L;
    private long sceneLightGeneration = 1L;
    private long entityGeneration = 1L;
    private String shaderpackName = "";
    private SkySignature skySignature;
    private FrameSignature frameSignature;
    private DlssSignature dlssSignature;

    private CacheInvalidationTracker() {
        for (CacheRequestFamily family : CacheRequestFamily.values()) {
            familyGenerations.put(family, 1L);
        }
    }

    public static CacheInvalidationTracker global() {
        return GLOBAL;
    }

    public synchronized CacheEntryVersionStamp capture(CacheRequestKey key) {
        long sectionKey = key.primarySectionKey();
        SectionVersion section = sectionVersions.get(sectionKey);
        return new CacheEntryVersionStamp(
                key.family(),
                worldId,
                dimensionId,
                shaderpackGeneration,
                skyGeneration,
                materialGeneration,
                cacheLayoutGeneration,
                temporalGeneration,
                guideGeneration,
                familyGeneration(key.family()),
                sceneGeometryGeneration,
                sceneLightGeneration,
                entityGeneration,
                false,
                sectionKey,
                section == null ? 0 : section.geometryVersion,
                section == null ? 0 : section.lightVersion,
                section != null,
                section != null && section.active);
    }

    public synchronized boolean isCurrent(CacheRequestKey key, CacheEntryVersionStamp stamp) {
        if (key == null || stamp == null || stamp.family() != key.family()) {
            return false;
        }
        if (stamp.worldId() != worldId
                || !Objects.equals(stamp.dimensionId(), dimensionId)
                || stamp.shaderpackGeneration() != shaderpackGeneration
                || stamp.cacheLayoutGeneration() != cacheLayoutGeneration
                || stamp.familyGeneration() != familyGeneration(key.family())) {
            return false;
        }
        if (requiresSkyVersion(key.family()) && stamp.skyGeneration() != skyGeneration) {
            return false;
        }
        if (requiresMaterialVersion(key.family()) && stamp.materialGeneration() != materialGeneration) {
            return false;
        }
        if (requiresTemporalVersion(key.family()) && stamp.temporalGeneration() != temporalGeneration) {
            return false;
        }
        if (requiresGuideVersion(key.family()) && stamp.guideGeneration() != guideGeneration) {
            return false;
        }
        if (requiresSceneGeometryVersion(key.family())
                && stamp.sceneGeometryGeneration() != sceneGeometryGeneration) {
            return false;
        }
        if (requiresSceneLightVersion(key.family())
                && stamp.sceneLightGeneration() != sceneLightGeneration) {
            return false;
        }
        if (stamp.entityDependent() && stamp.entityGeneration() != entityGeneration) {
            return false;
        }

        long sectionKey = key.primarySectionKey();
        if (stamp.sectionKey() != sectionKey) {
            return false;
        }
        if (!requiresSectionVersion(key.family())) {
            return true;
        }

        SectionVersion section = sectionVersions.get(sectionKey);
        if (section == null || !section.active || !stamp.sectionKnown() || !stamp.sectionActive()) {
            return false;
        }
        if (stamp.sectionGeometryVersion() != section.geometryVersion) {
            return false;
        }
        return !requiresLightVersion(key.family()) || stamp.sectionLightVersion() == section.lightVersion;
    }

    public synchronized void recordWorld(String nextDimensionId) {
        nextDimensionId = nextDimensionId == null ? "" : nextDimensionId;
        worldId++;
        dimensionId = nextDimensionId;
        sectionVersions.clear();
        skySignature = null;
        sceneGeometryGeneration++;
        sceneLightGeneration++;
        entityGeneration++;
        bumpAllFamilies();
        bumpTemporalAndGuides();
        LOGGER.info("[Vulkanite] Cache world generation advanced: worldId={}, dimension={}",
                worldId, dimensionId);
    }

    public synchronized void recordSectionBuild(
            ChunkSectionPos sectionPos,
            boolean geometryChanged,
            boolean lightChanged) {
        if (sectionPos == null) {
            return;
        }
        long sectionKey = sectionPos.asLong();
        SectionVersion section = sectionVersions.computeIfAbsent(sectionKey, key -> new SectionVersion());
        if (!section.active) {
            section.active = true;
            geometryChanged = true;
            lightChanged = true;
        }
        if (geometryChanged) {
            section.geometryVersion = incrementInt(section.geometryVersion);
            sceneGeometryGeneration++;
        }
        if (lightChanged) {
            section.lightVersion = incrementInt(section.lightVersion);
            sceneLightGeneration++;
        }
    }

    public synchronized void recordSectionRemoval(ChunkSectionPos sectionPos) {
        if (sectionPos == null) {
            return;
        }
        SectionVersion section = sectionVersions.computeIfAbsent(sectionPos.asLong(), key -> new SectionVersion());
        section.active = false;
        section.geometryVersion = incrementInt(section.geometryVersion);
        section.lightVersion = incrementInt(section.lightVersion);
        sceneGeometryGeneration++;
        sceneLightGeneration++;
    }

    public synchronized void clearSectionVersions() {
        sectionVersions.clear();
        sceneGeometryGeneration++;
        sceneLightGeneration++;
        bumpAllFamilies();
    }

    public synchronized void recordMaterialAtlasChanged(String reason) {
        materialGeneration++;
        bumpFamily(CacheRequestFamily.DIFFUSE_RADIANCE);
        bumpFamily(CacheRequestFamily.REFLECTION);
        bumpFamily(CacheRequestFamily.REFRACTION);
        LOGGER.debug("[Vulkanite] Cache material generation advanced: generation={}, reason={}",
                materialGeneration, reason);
    }

    public synchronized void recordShaderpack(String nextShaderpackName) {
        nextShaderpackName = nextShaderpackName == null ? "" : nextShaderpackName;
        shaderpackName = nextShaderpackName;
        shaderpackGeneration++;
        cacheLayoutGeneration++;
        bumpAllFamilies();
        bumpTemporalAndGuides();
        LOGGER.info("[Vulkanite] Cache shaderpack generation advanced: shaderpack='{}', generation={}, layout={}",
                shaderpackName, shaderpackGeneration, cacheLayoutGeneration);
    }

    public synchronized void recordSky(long timeOfDay, boolean raining, boolean thundering) {
        long dayTime = Math.floorMod(timeOfDay, 24000L);
        SkySignature next = new SkySignature(dayTime / SKY_TIME_BUCKET_TICKS, raining, thundering);
        if (next.equals(skySignature)) {
            return;
        }
        skySignature = next;
        skyGeneration++;
    }

    public synchronized void recordEntityGeometryChanged() {
        entityGeneration++;
    }

    public synchronized void recordCameraCut() {
        bumpTemporalAndGuides();
    }

    public synchronized boolean recordFrameSize(int renderWidth, int renderHeight, int outputWidth, int outputHeight) {
        FrameSignature next = new FrameSignature(renderWidth, renderHeight, outputWidth, outputHeight);
        if (next.equals(frameSignature)) {
            return false;
        }
        frameSignature = next;
        bumpTemporalAndGuides();
        return true;
    }

    public synchronized boolean recordDlssMode(DLSSConfig config, boolean runtimeActive) {
        DlssSignature next = config == null
                ? new DlssSignature(false, null, null, false, false, null)
                : new DlssSignature(
                        runtimeActive && config.isEnabled(),
                        config.getDenoiserType(),
                        config.getQualityPreset(),
                        config.isRayReconstructionEnabled(),
                        config.isReSTIREnabled(),
                        config.getDebugType());
        if (next.equals(dlssSignature)) {
            return false;
        }
        dlssSignature = next;
        bumpTemporalAndGuides();
        return true;
    }

    public synchronized long worldId() {
        return worldId;
    }

    public synchronized String dimensionId() {
        return dimensionId;
    }

    public synchronized long shaderpackGeneration() {
        return shaderpackGeneration;
    }

    public synchronized long skyGeneration() {
        return skyGeneration;
    }

    public synchronized long materialGeneration() {
        return materialGeneration;
    }

    public synchronized long cacheLayoutGeneration() {
        return cacheLayoutGeneration;
    }

    public synchronized long temporalGeneration() {
        return temporalGeneration;
    }

    public synchronized long guideGeneration() {
        return guideGeneration;
    }

    public synchronized long sceneGeometryGeneration() {
        return sceneGeometryGeneration;
    }

    public synchronized long sceneLightGeneration() {
        return sceneLightGeneration;
    }

    public synchronized long familyGeneration(CacheRequestFamily family) {
        return familyGenerations.getOrDefault(family, 1L);
    }

    private void bumpFamily(CacheRequestFamily family) {
        familyGenerations.put(family, familyGeneration(family) + 1L);
    }

    private void bumpAllFamilies() {
        for (CacheRequestFamily family : CacheRequestFamily.values()) {
            bumpFamily(family);
        }
    }

    private void bumpTemporalAndGuides() {
        temporalGeneration++;
        guideGeneration++;
    }

    private static boolean requiresSectionVersion(CacheRequestFamily family) {
        return true;
    }

    private static boolean requiresLightVersion(CacheRequestFamily family) {
        return family == CacheRequestFamily.SECTION_PROBE_CELL
                || family == CacheRequestFamily.DIFFUSE_RADIANCE;
    }

    private static boolean requiresSkyVersion(CacheRequestFamily family) {
        return family == CacheRequestFamily.DIFFUSE_RADIANCE
                || family == CacheRequestFamily.REFLECTION
                || family == CacheRequestFamily.REFRACTION;
    }

    private static boolean requiresMaterialVersion(CacheRequestFamily family) {
        return family == CacheRequestFamily.DIFFUSE_RADIANCE
                || family == CacheRequestFamily.REFLECTION
                || family == CacheRequestFamily.REFRACTION;
    }

    private static boolean requiresTemporalVersion(CacheRequestFamily family) {
        return family == CacheRequestFamily.REFLECTION
                || family == CacheRequestFamily.REFRACTION;
    }

    private static boolean requiresGuideVersion(CacheRequestFamily family) {
        return false;
    }

    private static boolean requiresSceneGeometryVersion(CacheRequestFamily family) {
        return family != CacheRequestFamily.SECTION_PROBE_CELL;
    }

    private static boolean requiresSceneLightVersion(CacheRequestFamily family) {
        return family != CacheRequestFamily.SECTION_PROBE_CELL;
    }

    private static int incrementInt(int value) {
        return value == Integer.MAX_VALUE ? 1 : value + 1;
    }

    private static final class SectionVersion {
        private int geometryVersion;
        private int lightVersion;
        private boolean active;
    }

    private record SkySignature(long timeBucket, boolean raining, boolean thundering) {
    }

    private record FrameSignature(int renderWidth, int renderHeight, int outputWidth, int outputHeight) {
    }

    private record DlssSignature(
            boolean enabled,
            DLSSConfig.DenoiserType denoiserType,
            DLSSConfig.QualityPreset qualityPreset,
            boolean rayReconstruction,
            boolean restir,
            DLSSConfig.DebugType debugType) {
    }
}
