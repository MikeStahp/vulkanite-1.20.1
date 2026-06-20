package me.cortex.vulkanite.client.config;

import me.cortex.vulkanite.client.Vulkanite;
import net.fabricmc.loader.api.FabricLoader;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Properties;

/**
 * Configuration class for Vulkanite settings
 */
public class VulkaniteConfig {
    private static final String CONFIG_FILE_NAME = "vulkanite.properties";
    private static final boolean DEFAULT_RTX_ENTITY_CAPTURE_ENABLED = true;
    private static final boolean DEFAULT_RTX_PARTICLE_CAPTURE_ENABLED = false;
    private static final int DEFAULT_RTX_ENTITY_CAPTURE_INTERVAL = 6;
    private static final int DEFAULT_RTX_MAX_CAPTURED_ENTITIES = 32;
    private static final int DEFAULT_RTX_MAX_CAPTURED_PARTICLES = 128;
    private static final int DEFAULT_RTX_ENTITY_CAPTURE_RADIUS = 48;
    private static final int DEFAULT_RTX_ENTITY_BLAS_CACHE_SIZE = 128;
    private static final int MIN_RTX_ENTITY_CAPTURE_INTERVAL = 1;
    private static final int MAX_RTX_ENTITY_CAPTURE_INTERVAL = 20;
    private static final int MIN_RTX_MAX_CAPTURED_ENTITIES = 1;
    private static final int MAX_RTX_MAX_CAPTURED_ENTITIES = 512;
    private static final int MIN_RTX_MAX_CAPTURED_PARTICLES = 1;
    private static final int MAX_RTX_MAX_CAPTURED_PARTICLES = 4096;
    private static final int MIN_RTX_ENTITY_CAPTURE_RADIUS = 8;
    private static final int MAX_RTX_ENTITY_CAPTURE_RADIUS = 256;
    private static final int MIN_RTX_ENTITY_BLAS_CACHE_SIZE = 32;
    private static final int MAX_RTX_ENTITY_BLAS_CACHE_SIZE = 2048;

    public enum RtxCacheMode {
        FULL_RT_REFERENCE("full_rt_reference"),
        CACHE_FILL("cache_fill"),
        CACHE_RESOLVE_ONLY("cache_resolve_only");

        private final String configValue;

        RtxCacheMode(String configValue) {
            this.configValue = configValue;
        }

        public String configValue() {
            return configValue;
        }

        public static RtxCacheMode parse(String value) {
            if (value == null || value.isBlank()) {
                return FULL_RT_REFERENCE;
            }
            for (RtxCacheMode mode : values()) {
                if (mode.configValue.equalsIgnoreCase(value) || mode.name().equalsIgnoreCase(value)) {
                    return mode;
                }
            }
            return FULL_RT_REFERENCE;
        }

        public boolean usesFullRtPass() {
            return this == FULL_RT_REFERENCE || this == CACHE_FILL;
        }

        public boolean usesCacheResolvePass() {
            return this == CACHE_FILL || this == CACHE_RESOLVE_ONLY;
        }

        public boolean requiresTlas() {
            return usesFullRtPass();
        }
    }

    // ReSTIR/DIRT RT settings
    public boolean enableDirtRt = false;
    public int restirReservoirWidth = 1280;
    public int restirReservoirHeight = 720;

    // Optional Vulkan compute enhancement for the deferred baseline.
    // The first-party VulkaniteDeferred shaderpack must still render correctly when
    // this is false, so non-RTX cards have a stable GL/Iris deferred path.
    public boolean deferredComputeEnabled = false;

    // Extra safety latch for the current experimental GL/Vulkan custom-image
    // compute path. This prevents a shaderpack setting or old persisted config
    // from re-enabling a known-hanging interop path by accident.
    public boolean deferredComputeExperimental = false;
    
    // Manual override for deferred rendering detection
    // If true, forces deferred rendering even if shaderpack isn't detected
    // If false, forces ray tracing even if VulkaniteDeferred is detected
    public Boolean deferredRenderingOverride = null;

    // RTX transient capture settings.
    public boolean rtxEntityCaptureEnabled = DEFAULT_RTX_ENTITY_CAPTURE_ENABLED;
    public boolean rtxParticleCaptureEnabled = DEFAULT_RTX_PARTICLE_CAPTURE_ENABLED;
    public int rtxEntityCaptureInterval = DEFAULT_RTX_ENTITY_CAPTURE_INTERVAL;
    public int rtxMaxCapturedEntities = DEFAULT_RTX_MAX_CAPTURED_ENTITIES;
    public int rtxMaxCapturedParticles = DEFAULT_RTX_MAX_CAPTURED_PARTICLES;
    public int rtxEntityCaptureRadius = DEFAULT_RTX_ENTITY_CAPTURE_RADIUS;
    public int rtxEntityBlasCacheSize = DEFAULT_RTX_ENTITY_BLAS_CACHE_SIZE;
    public RtxCacheMode rtxCacheMode = RtxCacheMode.FULL_RT_REFERENCE;

    private static VulkaniteConfig INSTANCE;

    public static VulkaniteConfig getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new VulkaniteConfig();
            INSTANCE.loadConfig();
        }
        return INSTANCE;
    }

    private void loadConfig() {
        File configFile = new File(FabricLoader.getInstance().getConfigDir().toFile(), CONFIG_FILE_NAME);

        if (!configFile.exists()) {
            saveConfig();
            return;
        }

        Properties props = new Properties();
        try (FileReader reader = new FileReader(configFile)) {
            props.load(reader);

            enableDirtRt = Boolean.parseBoolean(props.getProperty("enableDirtRt", "false"));
            restirReservoirWidth = Integer.parseInt(props.getProperty("restirReservoirWidth", "1920"));
            restirReservoirHeight = Integer.parseInt(props.getProperty("restirReservoirHeight", "1080"));
            
            deferredComputeEnabled = Boolean.parseBoolean(props.getProperty("deferredComputeEnabled", "false"));
            deferredComputeExperimental = Boolean.parseBoolean(props.getProperty("deferredComputeExperimental", "false"));
            String overrideValue = props.getProperty("deferredRenderingOverride");
            if (overrideValue != null && !overrideValue.isEmpty()) {
                deferredRenderingOverride = Boolean.parseBoolean(overrideValue);
            }
            rtxEntityCaptureEnabled = Boolean.parseBoolean(props.getProperty("rtxEntityCaptureEnabled",
                    String.valueOf(DEFAULT_RTX_ENTITY_CAPTURE_ENABLED)));
            rtxParticleCaptureEnabled = Boolean.parseBoolean(props.getProperty("rtxParticleCaptureEnabled",
                    String.valueOf(DEFAULT_RTX_PARTICLE_CAPTURE_ENABLED)));
            rtxEntityCaptureInterval = parseBoundedInt(props.getProperty("rtxEntityCaptureInterval",
                    String.valueOf(DEFAULT_RTX_ENTITY_CAPTURE_INTERVAL)), DEFAULT_RTX_ENTITY_CAPTURE_INTERVAL,
                    MIN_RTX_ENTITY_CAPTURE_INTERVAL, MAX_RTX_ENTITY_CAPTURE_INTERVAL);
            rtxMaxCapturedEntities = parseBoundedInt(props.getProperty("rtxMaxCapturedEntities",
                    String.valueOf(DEFAULT_RTX_MAX_CAPTURED_ENTITIES)), DEFAULT_RTX_MAX_CAPTURED_ENTITIES,
                    MIN_RTX_MAX_CAPTURED_ENTITIES, MAX_RTX_MAX_CAPTURED_ENTITIES);
            rtxMaxCapturedParticles = parseBoundedInt(props.getProperty("rtxMaxCapturedParticles",
                    String.valueOf(DEFAULT_RTX_MAX_CAPTURED_PARTICLES)), DEFAULT_RTX_MAX_CAPTURED_PARTICLES,
                    MIN_RTX_MAX_CAPTURED_PARTICLES, MAX_RTX_MAX_CAPTURED_PARTICLES);
            rtxEntityCaptureRadius = parseBoundedInt(props.getProperty("rtxEntityCaptureRadius",
                    String.valueOf(DEFAULT_RTX_ENTITY_CAPTURE_RADIUS)), DEFAULT_RTX_ENTITY_CAPTURE_RADIUS,
                    MIN_RTX_ENTITY_CAPTURE_RADIUS, MAX_RTX_ENTITY_CAPTURE_RADIUS);
            rtxEntityBlasCacheSize = parseBoundedInt(props.getProperty("rtxEntityBlasCacheSize",
                    String.valueOf(DEFAULT_RTX_ENTITY_BLAS_CACHE_SIZE)), DEFAULT_RTX_ENTITY_BLAS_CACHE_SIZE,
                    MIN_RTX_ENTITY_BLAS_CACHE_SIZE, MAX_RTX_ENTITY_BLAS_CACHE_SIZE);
            rtxCacheMode = RtxCacheMode.parse(props.getProperty("rtxCacheMode",
                    RtxCacheMode.FULL_RT_REFERENCE.configValue()));

        } catch (IOException e) {
            System.err.println("[Vulkanite] Failed to load config: " + e.getMessage());
            saveConfig(); // Save default config
        }
    }

    private static int parseBoundedInt(String value, int fallback, int min, int max) {
        try {
            return Math.max(min, Math.min(max, Integer.parseInt(value)));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private void saveConfig() {
        File configFile = new File(FabricLoader.getInstance().getConfigDir().toFile(), CONFIG_FILE_NAME);

        Properties props = new Properties();
        props.setProperty("enableDirtRt", String.valueOf(enableDirtRt));
        props.setProperty("restirReservoirWidth", String.valueOf(restirReservoirWidth));
        props.setProperty("restirReservoirHeight", String.valueOf(restirReservoirHeight));
        props.setProperty("deferredComputeEnabled", String.valueOf(deferredComputeEnabled));
        props.setProperty("deferredComputeExperimental", String.valueOf(deferredComputeExperimental));
        if (deferredRenderingOverride != null) {
            props.setProperty("deferredRenderingOverride", String.valueOf(deferredRenderingOverride));
        }
        props.setProperty("rtxEntityCaptureEnabled", String.valueOf(rtxEntityCaptureEnabled));
        props.setProperty("rtxParticleCaptureEnabled", String.valueOf(rtxParticleCaptureEnabled));
        props.setProperty("rtxEntityCaptureInterval", String.valueOf(rtxEntityCaptureInterval));
        props.setProperty("rtxMaxCapturedEntities", String.valueOf(rtxMaxCapturedEntities));
        props.setProperty("rtxMaxCapturedParticles", String.valueOf(rtxMaxCapturedParticles));
        props.setProperty("rtxEntityCaptureRadius", String.valueOf(rtxEntityCaptureRadius));
        props.setProperty("rtxEntityBlasCacheSize", String.valueOf(rtxEntityBlasCacheSize));
        props.setProperty("rtxCacheMode", rtxCacheMode.configValue());

        try (FileWriter writer = new FileWriter(configFile)) {
            props.store(writer, "Vulkanite Configuration");
        } catch (IOException e) {
            System.err.println("[Vulkanite] Failed to save config: " + e.getMessage());
        }
    }
    
    /**
     * Checks if deferred rendering should be used based on config and shaderpack detection.
     * @param shaderpackName The name of the current shaderpack (can be null)
     * @return true if deferred rendering should be used
     */
    public boolean shouldUseDeferredRendering(String shaderpackName) {
        // Manual override takes precedence
        if (deferredRenderingOverride != null) {
            return deferredRenderingOverride;
        }
        
        // Auto-detect based on shaderpack name
        if (shaderpackName != null && shaderpackName.contains("VulkaniteDeferred")) {
            return true;
        }
        
        // Do not let a persisted legacy flag change unrelated shaderpacks.
        return false;
    }

    /**
     * Checks whether Vulkan compute should augment the deferred baseline.
     * The deferred shaderpack itself remains the non-RTX source of truth.
     *
     * @param shaderpackName The name of the current shaderpack (can be null)
     * @return true if deferred compute should be dispatched
     */
    public boolean shouldUseDeferredCompute(String shaderpackName) {
        return shouldUseDeferredRendering(shaderpackName)
                && deferredComputeEnabled
                && deferredComputeExperimental;
    }

    /**
     * Temporary RTX/cache split switch for validating the cache-first render flow.
     * Can be overridden at launch with -Dvulkanite.rtxCacheMode=cache_resolve_only.
     */
    public RtxCacheMode getRtxCacheMode() {
        return RtxCacheMode.parse(System.getProperty("vulkanite.rtxCacheMode", rtxCacheMode.configValue()));
    }
    
    /**
     * Enables or disables the optional Vulkan compute lighting pass.
     *
     * @param enabled Whether deferred compute should be enabled
     */
    public void setDeferredComputeEnabled(boolean enabled) {
        this.deferredComputeEnabled = enabled;
        saveConfig();
    }
}
