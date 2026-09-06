package me.cortex.vulkanite.client.config;

import me.cortex.vulkanite.client.ShaderpackSettingsHandler;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;

/**
 * Configuration class for DLSS/FSR upscaling and denoising settings.
 * Uses singleton pattern with file persistence.
 * 
 * Settings include:
 * - Render style (auto-detected: Deferred, RTX, Vanilla)
 * - Denoiser type (DLSS, FSR, DLSS_RR)
 * - Quality presets
 * - ReSTIR toggle
 * - Debug mode
 * - World lighting parameters
 */
public class DLSSConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger(DLSSConfig.class);
    private static final String CONFIG_FILE_NAME = "vulkanite-dlss.properties";
    
    private static volatile DLSSConfig INSTANCE;
    private volatile boolean dirty = false;

    // =====================================================================
    // NESTED ENUMS
    // =====================================================================

    /**
     * Type of denoiser/upscaler to use
     */
    public enum DenoiserType {
        NONE("None", "No upscaling/denoising"),
        DLSS("DLSS", "NVIDIA DLSS upscaling"),
        FSR("FSR", "AMD FSR upscaling"),
        DLSS_RR("DLSS_RR", "NVIDIA DLSS Ray Reconstruction");

        private final String displayName;
        private final String description;

        DenoiserType(String displayName, String description) {
            this.displayName = displayName;
            this.description = description;
        }

        public String getDisplayName() { return displayName; }
        public String getDescription() { return description; }
    }

    /**
     * Quality preset for upscaling - controls render resolution ratio
     * Values match NVSDK_NGX_PerfQuality_Value enum from nvsdk_ngx_defs.h:
     * 0 = MaxPerf (Performance), 1 = Balanced, 2 = MaxQuality (Quality),
     * 3 = UltraPerformance, 4 = UltraQuality, 5 = DLAA
     */
    public enum QualityPreset {
    	PERFORMANCE(0, 0.5f, "Performance", "Higher performance, reduced quality"),
    	BALANCED(1, 0.583f, "Balanced", "Good balance of quality and performance"),
    	QUALITY(2, 0.667f, "Quality", "Best image quality, lower performance"),
    	ULTRA_PERFORMANCE(3, 0.333f, "Ultra Performance", "Maximum performance, lowest quality"),
    	ULTRA_QUALITY(4, 0.77f, "Ultra Quality", "Near-native quality with some upscaling"),
    	DLAA(5, 1.0f, "DLAA", "No upscaling, anti-aliasing only");
   
    	private final int ngxValue;
    	private final float resolutionRatio;
    	private final String displayName;
    	private final String description;
   
    	QualityPreset(int ngxValue, float resolutionRatio, String displayName, String description) {
    		this.ngxValue = ngxValue;
    		this.resolutionRatio = resolutionRatio;
    		this.displayName = displayName;
    		this.description = description;
    	}
   
    	public int getNgxValue() { return ngxValue; }
    	public float getResolutionRatio() { return resolutionRatio; }
    	public float getScale() { return resolutionRatio; }
    	public String getDisplayName() { return displayName; }
    	public String getDescription() { return description; }

        public static QualityPreset fromNgxValue(int ngxValue) {
            for (QualityPreset preset : values()) {
                if (preset.ngxValue == ngxValue) {
                    return preset;
                }
            }
            return null;
        }

        public static QualityPreset fromLegacyFsrQuality(int quality) {
            switch (quality) {
                case 0: return QUALITY;
                case 1: return BALANCED;
                case 2: return PERFORMANCE;
                case 3: return ULTRA_PERFORMANCE;
                default: return BALANCED;
            }
        }

        public int getLegacyFsrQuality() {
            switch (this) {
                case QUALITY: return 0;
                case BALANCED: return 1;
                case PERFORMANCE: return 2;
                case ULTRA_PERFORMANCE: return 3;
                default: return 1;
            }
        }
    }

    /**
     * Debug visualization type
     */
    public enum DebugType {
        NONE("None", "No debug visualization"),
        INPUT("Input", "Show DLSS input texture"),
        OUTPUT("Output", "Show DLSS output texture"),
        MOTION_VECTORS("Motion Vectors", "Show motion vectors"),
        DEPTH("Depth", "Show depth buffer"),
        NORMALS("Normals", "Show normal buffer"),
        PROCEDURAL_DISTANCE("Procedural Distance", "Show procedural voxel hit distance"),
        PROCEDURAL_NORMALS("Procedural Normals", "Show procedural voxel face normals"),
        PROCEDURAL_BRICK_IDS("Procedural Brick IDs", "Color procedural brick primitives"),
        PROCEDURAL_VOXEL_IDS("Procedural Voxel IDs", "Color brick-local voxel cells"),
        SHADOW_COMPARISON("Shadow Comparison", "Overlay triangle/procedural visibility totals"),
        SHADOW_DANGEROUS_MISSES("Shadow Dangerous Misses", "Highlight triangle hits missed procedurally"),
        SHADOW_EXTRA_HITS("Shadow Extra Hits", "Highlight conservative procedural-only hits"),
        SHADOW_DOUBLE_TRACE_REFERENCE(
                "Shadow Double-Trace Reference",
                "Render the logical union of triangle and procedural shadow hits"),
        PROCEDURAL_REFLECTION_COMPARISON(
                "Procedural Reflection Comparison",
                "Compare triangle and procedural primary material hits");

        private final String displayName;
        private final String description;

        DebugType(String displayName, String description) {
            this.displayName = displayName;
            this.description = description;
        }

        public String getDisplayName() { return displayName; }
        public String getDescription() { return description; }
    }

    /**
     * Render style - auto-detected based on shaderpack
     */
    public enum RenderStyle {
        VANILLA("Vanilla", "Standard Minecraft rendering"),
        DEFERRED("Deferred", "Compute-based deferred lighting"),
        RTX("RTX", "Ray tracing enabled");

        private final String displayName;
        private final String description;

        RenderStyle(String displayName, String description) {
            this.displayName = displayName;
            this.description = description;
        }

        public String getDisplayName() { return displayName; }
        public String getDescription() { return description; }
    }

    // =====================================================================
    // CONFIGURATION FIELDS
    // =====================================================================

    // Core DLSS Settings
    private boolean dlssEnabled = true;
    private boolean rayReconstructionEnabled = true;
    private DenoiserType denoiserType = DenoiserType.DLSS_RR;
    private QualityPreset qualityPreset = QualityPreset.QUALITY;
    private DebugType debugType = DebugType.NONE;
    private float sharpness = 0.5f;
    private boolean motionVectorsEnabled = true;
    private boolean jitterEnabled = true;

    // FSR Settings
    private boolean fsrEnabled = false;

    // ReSTIR Settings
    private boolean restirEnabled = false;

    // World/Lighting Parameters
    private float indirectScale = 1.0f;
    private float ambientFactor = 0.1f;
    private float minLighting = 0.01f;
    private float specularIntensity = 1.0f;
    private float gamma = 2.2f;

    // Debug Settings
    private int debugCellIndex = -1;
    private int shadowComparisonSamplingPercent = 100;
    private float shadowComparisonDistanceTolerance = 0.01f;
    private boolean shadowComparisonStructuredLogging = false;

    // =====================================================================
    // SINGLETON ACCESS
    // =====================================================================

    /**
     * Get the singleton instance with double-checked locking for thread safety.
     */
    public static DLSSConfig getInstance() {
        if (INSTANCE == null) {
            synchronized (DLSSConfig.class) {
                if (INSTANCE == null) {
                    INSTANCE = new DLSSConfig();
                    INSTANCE.loadConfig();
                }
            }
        }
        return INSTANCE;
    }

    /**
     * Static load method for convenience - returns singleton instance.
     * Used by: ResolutionScaleManager, VulkanPipeline, MixinProgramSet
     */
    public static DLSSConfig load() {
        return getInstance();
    }

    // =====================================================================
    // PERSISTENCE
    // =====================================================================

    private void loadConfig() {
        File configFile = new File(FabricLoader.getInstance().getConfigDir().toFile(), CONFIG_FILE_NAME);

        if (!configFile.exists()) {
            LOGGER.info("[Vulkanite] DLSS config file not found, creating with defaults");
            saveConfig();
            return;
        }

        Properties props = new Properties();
        try (FileReader reader = new FileReader(configFile)) {
            props.load(reader);
            dirty = false;

            // Core DLSS Settings
            dlssEnabled = parseBoolean(props, "dlssEnabled", true);
            rayReconstructionEnabled = parseBoolean(props, "rayReconstructionEnabled", true);
            boolean hasDenoiserType = props.containsKey("denoiserType");
            denoiserType = parseDenoiserType(props.getProperty("denoiserType"), DenoiserType.DLSS_RR);
            qualityPreset = parseQualityPreset("qualityPreset", props.getProperty("qualityPreset"), QualityPreset.QUALITY);
            debugType = parseDebugType(props.getProperty("debugType"), DebugType.NONE);
            sharpness = parseFloat(props, "sharpness", 0.5f, 0.0f, 1.0f);
            motionVectorsEnabled = parseBoolean(props, "motionVectorsEnabled", true);
            jitterEnabled = parseBoolean(props, "jitterEnabled", true);

            // FSR Settings
            fsrEnabled = parseBoolean(props, "fsrEnabled", false);
            if (!hasDenoiserType && fsrEnabled) {
                denoiserType = DenoiserType.FSR;
                dirty = true;
            }
            boolean expectedFsrEnabled = denoiserType == DenoiserType.FSR;
            if (fsrEnabled != expectedFsrEnabled) {
                fsrEnabled = expectedFsrEnabled;
                dirty = true;
            }
            if (!props.containsKey("qualityPreset")) {
                if (props.containsKey("fsrQualityPreset")) {
                    qualityPreset = parseQualityPreset("fsrQualityPreset", props.getProperty("fsrQualityPreset"), QualityPreset.BALANCED);
                    dirty = true;
                } else if (props.containsKey("fsrQuality")) {
                    int legacyFsrQuality = parseInt(props, "fsrQuality", 1, 0, 3);
                    qualityPreset = QualityPreset.fromLegacyFsrQuality(legacyFsrQuality);
                    dirty = true;
                }
            }

            // ReSTIR Settings
            restirEnabled = parseBoolean(props, "restirEnabled", false);

            // World/Lighting Parameters
            indirectScale = parseFloat(props, "indirectScale", 1.0f, 0.0f, Float.MAX_VALUE);
            ambientFactor = parseFloat(props, "ambientFactor", 0.1f, 0.0f, 1.0f);
            minLighting = parseFloat(props, "minLighting", 0.01f, 0.0f, 1.0f);
            specularIntensity = parseFloat(props, "specularIntensity", 1.0f, 0.0f, Float.MAX_VALUE);
            gamma = parseFloat(props, "gamma", 2.2f, 1.0f, 3.0f);

            // Debug Settings
            debugCellIndex = parseInt(props, "debugCellIndex", -1, -1, Integer.MAX_VALUE);
            shadowComparisonSamplingPercent = parseInt(
                    props, "shadowComparisonSamplingPercent", 100, 1, 100);
            shadowComparisonDistanceTolerance = parseFloat(
                    props, "shadowComparisonDistanceTolerance", 0.01f, 0.0001f, 1.0f);
            shadowComparisonStructuredLogging = parseBoolean(
                    props, "shadowComparisonStructuredLogging", false);

            LOGGER.info("[Vulkanite] DLSS config loaded: denoiser={}, quality={}, restir={}", 
                denoiserType, qualityPreset, restirEnabled);

            if (dirty) {
                LOGGER.info("[Vulkanite] DLSS config contained invalid or legacy values, writing normalized config");
                saveConfig();
            }

        } catch (IOException e) {
            LOGGER.error("[Vulkanite] Failed to load DLSS config: {}", e.getMessage());
            saveConfig(); // Save defaults
        }
    }

    /**
     * Save configuration to file.
     */
    public void save() {
        saveConfig();
    }

    private void saveConfig() {
        File configFile = new File(FabricLoader.getInstance().getConfigDir().toFile(), CONFIG_FILE_NAME);

        Properties props = new Properties();

        // Core DLSS Settings
        props.setProperty("dlssEnabled", String.valueOf(dlssEnabled));
        props.setProperty("rayReconstructionEnabled", String.valueOf(rayReconstructionEnabled));
        props.setProperty("denoiserType", denoiserType.name());
        props.setProperty("qualityPreset", qualityPreset.name());
        props.setProperty("debugType", debugType.name());
        props.setProperty("sharpness", String.valueOf(sharpness));
        props.setProperty("motionVectorsEnabled", String.valueOf(motionVectorsEnabled));
        props.setProperty("jitterEnabled", String.valueOf(jitterEnabled));

        // FSR Settings
        props.setProperty("fsrEnabled", String.valueOf(fsrEnabled));
        props.setProperty("fsrQualityPreset", qualityPreset.name());
        props.setProperty("fsrQuality", String.valueOf(qualityPreset.getLegacyFsrQuality()));

        // ReSTIR Settings
        props.setProperty("restirEnabled", String.valueOf(restirEnabled));

        // World/Lighting Parameters
        props.setProperty("indirectScale", String.valueOf(indirectScale));
        props.setProperty("ambientFactor", String.valueOf(ambientFactor));
        props.setProperty("minLighting", String.valueOf(minLighting));
        props.setProperty("specularIntensity", String.valueOf(specularIntensity));
        props.setProperty("gamma", String.valueOf(gamma));

        // Debug Settings
        props.setProperty("debugCellIndex", String.valueOf(debugCellIndex));
        props.setProperty("shadowComparisonSamplingPercent", String.valueOf(shadowComparisonSamplingPercent));
        props.setProperty("shadowComparisonDistanceTolerance",
                String.valueOf(shadowComparisonDistanceTolerance));
        props.setProperty("shadowComparisonStructuredLogging",
                String.valueOf(shadowComparisonStructuredLogging));

        try (FileWriter writer = new FileWriter(configFile)) {
            props.store(writer, "Vulkanite DLSS/FSR Configuration");
            dirty = false;
            LOGGER.debug("[Vulkanite] DLSS config saved");
        } catch (IOException e) {
            LOGGER.error("[Vulkanite] Failed to save DLSS config: {}", e.getMessage());
        }
    }

    // =====================================================================
    // PARSING HELPERS
    // =====================================================================

    private boolean parseBoolean(Properties props, String key, boolean fallback) {
        String value = props.getProperty(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }

        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if ("true".equals(normalized)) {
            return true;
        }
        if ("false".equals(normalized)) {
            return false;
        }

        logNormalizedValue(key, value, fallback);
        return fallback;
    }

    private int parseInt(Properties props, String key, int fallback, int min, int max) {
        String value = props.getProperty(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }

        try {
            int parsed = Integer.parseInt(value.trim());
            int clamped = Math.max(min, Math.min(max, parsed));
            if (clamped != parsed) {
                logNormalizedValue(key, value, clamped);
            }
            return clamped;
        } catch (NumberFormatException e) {
            logNormalizedValue(key, value, fallback);
            return fallback;
        }
    }

    private float parseFloat(Properties props, String key, float fallback, float min, float max) {
        String value = props.getProperty(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }

        try {
            float parsed = Float.parseFloat(value.trim());
            if (!Float.isFinite(parsed)) {
                logNormalizedValue(key, value, fallback);
                return fallback;
            }

            float clamped = Math.max(min, Math.min(max, parsed));
            if (Float.compare(clamped, parsed) != 0) {
                logNormalizedValue(key, value, clamped);
            }
            return clamped;
        } catch (NumberFormatException e) {
            logNormalizedValue(key, value, fallback);
            return fallback;
        }
    }

    private DenoiserType parseDenoiserType(String value, DenoiserType fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }

        try {
            return DenoiserType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            logNormalizedValue("denoiserType", value, fallback);
            return fallback;
        }
    }

    private QualityPreset parseQualityPreset(String key, String value, QualityPreset fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }

        try {
            return QualityPreset.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            logNormalizedValue(key, value, fallback);
            return fallback;
        }
    }

    private DebugType parseDebugType(String value, DebugType fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }

        try {
            return DebugType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            logNormalizedValue("debugType", value, fallback);
            return fallback;
        }
    }

    private void logNormalizedValue(String key, Object oldValue, Object newValue) {
        LOGGER.warn("[Vulkanite] Invalid DLSS config value for {}='{}'; using {}", key, oldValue, newValue);
        dirty = true;
    }

    // =====================================================================
    // GETTERS - Core DLSS Settings
    // =====================================================================

    public boolean isDLSSEnabled() { return dlssEnabled; }
    
    public boolean isEnabled() { 
        return dlssEnabled && denoiserType != DenoiserType.NONE; 
    }

    public boolean isRayReconstructionEnabled() { 
        return rayReconstructionEnabled && (denoiserType == DenoiserType.DLSS_RR || denoiserType == DenoiserType.DLSS); 
    }

    public boolean usesDLSSBackend() {
        return dlssEnabled && (denoiserType == DenoiserType.DLSS || denoiserType == DenoiserType.DLSS_RR);
    }

    public boolean usesFSRBackend() {
        return dlssEnabled && denoiserType == DenoiserType.FSR;
    }

    public DenoiserType getDenoiserType() { return denoiserType; }
    
    /**
     * Get denoiser type as string for compatibility with existing code.
     */
    public String getDenoiser() { 
        if (rayReconstructionEnabled && denoiserType == DenoiserType.DLSS) {
            return DenoiserType.DLSS_RR.name();
        }
        return denoiserType.name(); 
    }

    public QualityPreset getQualityPreset() { return qualityPreset; }

    public DebugType getDebugType() { return debugType; }

    public boolean isDebugEnabled() { 
        return debugType != DebugType.NONE; 
    }

    public float getSharpness() { return sharpness; }

    public boolean isMotionVectorsEnabled() { return motionVectorsEnabled; }

    public boolean isJitterEnabled() { return jitterEnabled; }

    // =====================================================================
    // GETTERS - FSR Settings
    // =====================================================================

    public boolean isFSREnabled() { return usesFSRBackend(); }

    public int getFSRQuality() { return qualityPreset.getLegacyFsrQuality(); }

    public QualityPreset getFSRQualityPreset() {
        return qualityPreset;
    }

    // =====================================================================
    // GETTERS - ReSTIR Settings
    // =====================================================================

    public boolean isReSTIREnabled() { return restirEnabled; }

    // =====================================================================
    // GETTERS - World/Lighting Parameters
    // =====================================================================

    public float getIndirectScale() { return indirectScale; }
    public float getAmbientFactor() { return ambientFactor; }
    public float getMinLighting() { return minLighting; }
    public float getSpecularIntensity() { return specularIntensity; }
    public float getGamma() { return gamma; }

    // =====================================================================
    // GETTERS - Debug Settings
    // =====================================================================

    public int getDebugCellIndex() { return debugCellIndex; }
    public int getShadowComparisonSamplingPercent() { return shadowComparisonSamplingPercent; }
    public float getShadowComparisonDistanceTolerance() { return shadowComparisonDistanceTolerance; }
    public boolean isShadowComparisonStructuredLogging() { return shadowComparisonStructuredLogging; }

    // =====================================================================
    // SETTERS - Core DLSS Settings
    // =====================================================================

    private void markChanged(String key, Object oldValue, Object newValue) {
        if (!Objects.equals(oldValue, newValue)) {
            LOGGER.info("[Vulkanite] DLSS config changed: {}={} -> {}", key, oldValue, newValue);
            dirty = true;
        }
    }

    public void setDLSSEnabled(boolean enabled) { 
        boolean oldValue = this.dlssEnabled;
        this.dlssEnabled = enabled;
        markChanged("dlssEnabled", oldValue, this.dlssEnabled);
    }

    public void setRayReconstructionEnabled(boolean enabled) { 
        boolean oldValue = this.rayReconstructionEnabled;
        this.rayReconstructionEnabled = enabled;
        markChanged("rayReconstructionEnabled", oldValue, this.rayReconstructionEnabled);
    }

    public void setDenoiserType(DenoiserType type) { 
        DenoiserType oldValue = this.denoiserType;
        boolean oldFsrEnabled = this.fsrEnabled;
        this.denoiserType = type == null ? DenoiserType.NONE : type;
        this.fsrEnabled = this.denoiserType == DenoiserType.FSR;
        markChanged("denoiserType", oldValue, this.denoiserType);
        markChanged("fsrEnabled", oldFsrEnabled, this.fsrEnabled);
    }

    public void setQualityPreset(QualityPreset preset) { 
        QualityPreset oldValue = this.qualityPreset;
        this.qualityPreset = preset == null ? QualityPreset.QUALITY : preset;
        markChanged("qualityPreset", oldValue, this.qualityPreset);
    }

    public void setDebugType(DebugType type) { 
        DebugType oldValue = this.debugType;
        this.debugType = type == null ? DebugType.NONE : type;
        markChanged("debugType", oldValue, this.debugType);
    }

    public void setSharpness(float sharpness) { 
        float oldValue = this.sharpness;
        this.sharpness = Math.max(0.0f, Math.min(1.0f, sharpness));
        markChanged("sharpness", oldValue, this.sharpness);
    }

    public void setMotionVectorsEnabled(boolean enabled) { 
        boolean oldValue = this.motionVectorsEnabled;
        this.motionVectorsEnabled = enabled;
        markChanged("motionVectorsEnabled", oldValue, this.motionVectorsEnabled);
    }

    public void setJitterEnabled(boolean enabled) { 
        boolean oldValue = this.jitterEnabled;
        this.jitterEnabled = enabled;
        markChanged("jitterEnabled", oldValue, this.jitterEnabled);
    }

    // =====================================================================
    // SETTERS - FSR Settings
    // =====================================================================

    public void setFSREnabled(boolean enabled) { 
        boolean oldFsrEnabled = this.fsrEnabled;
        boolean oldDLSSEnabled = this.dlssEnabled;
        DenoiserType oldDenoiserType = this.denoiserType;
        this.fsrEnabled = enabled;
        if (enabled) {
            this.dlssEnabled = true;
            this.denoiserType = DenoiserType.FSR;
        } else if (this.denoiserType == DenoiserType.FSR) {
            this.denoiserType = DenoiserType.NONE;
        }
        markChanged("fsrEnabled", oldFsrEnabled, this.fsrEnabled);
        markChanged("dlssEnabled", oldDLSSEnabled, this.dlssEnabled);
        markChanged("denoiserType", oldDenoiserType, this.denoiserType);
    }

    public void setFSRQuality(int quality) { 
        setQualityPreset(QualityPreset.fromLegacyFsrQuality(quality));
    }

    public void setFSRQualityPreset(QualityPreset preset) {
        setQualityPreset(preset == null ? QualityPreset.BALANCED : preset);
    }

    // =====================================================================
    // SETTERS - ReSTIR Settings
    // =====================================================================

    public void setReSTIREnabled(boolean enabled) { 
        boolean oldValue = this.restirEnabled;
        this.restirEnabled = enabled;
        markChanged("restirEnabled", oldValue, this.restirEnabled);
    }

    // =====================================================================
    // SETTERS - World/Lighting Parameters
    // =====================================================================

    public void setIndirectScale(float scale) { 
        float oldValue = this.indirectScale;
        this.indirectScale = Math.max(0.0f, scale);
        markChanged("indirectScale", oldValue, this.indirectScale);
    }

    public void setAmbientFactor(float factor) { 
        float oldValue = this.ambientFactor;
        this.ambientFactor = Math.max(0.0f, Math.min(1.0f, factor));
        markChanged("ambientFactor", oldValue, this.ambientFactor);
    }

    public void setMinLighting(float min) { 
        float oldValue = this.minLighting;
        this.minLighting = Math.max(0.0f, Math.min(1.0f, min));
        markChanged("minLighting", oldValue, this.minLighting);
    }

    public void setSpecularIntensity(float intensity) { 
        float oldValue = this.specularIntensity;
        this.specularIntensity = Math.max(0.0f, intensity);
        markChanged("specularIntensity", oldValue, this.specularIntensity);
    }

    public void setGamma(float gamma) { 
        float oldValue = this.gamma;
        this.gamma = Math.max(1.0f, Math.min(3.0f, gamma));
        markChanged("gamma", oldValue, this.gamma);
    }

    // =====================================================================
    // SETTERS - Debug Settings
    // =====================================================================

    public void setDebugCellIndex(int index) {
        int oldValue = this.debugCellIndex;
        this.debugCellIndex = index;
        markChanged("debugCellIndex", oldValue, this.debugCellIndex);
    }

    public void setShadowComparisonSamplingPercent(int percent) {
        int oldValue = this.shadowComparisonSamplingPercent;
        this.shadowComparisonSamplingPercent = Math.max(1, Math.min(100, percent));
        markChanged("shadowComparisonSamplingPercent", oldValue, this.shadowComparisonSamplingPercent);
    }

    public void setShadowComparisonDistanceTolerance(float tolerance) {
        float oldValue = this.shadowComparisonDistanceTolerance;
        this.shadowComparisonDistanceTolerance = Math.max(0.0001f, Math.min(1.0f, tolerance));
        markChanged("shadowComparisonDistanceTolerance", oldValue, this.shadowComparisonDistanceTolerance);
    }

    public void setShadowComparisonStructuredLogging(boolean enabled) {
        boolean oldValue = this.shadowComparisonStructuredLogging;
        this.shadowComparisonStructuredLogging = enabled;
        markChanged("shadowComparisonStructuredLogging", oldValue, this.shadowComparisonStructuredLogging);
    }

    // =====================================================================
    // ADDITIONAL GETTERS - Used by MixinProgramSet
    // =====================================================================

    public float getSunIntensity() { return 1.0f; }
    public int getRestirMaxHistory() { return 8; }
    public int getRestirSpatialRadius() { return 15; }
    public int getRestirSpatialSamples() { return 4; }

    // =====================================================================
    // UTILITY METHODS
    // =====================================================================

    /**
     * Check if configuration has unsaved changes.
     */
    public boolean isDirty() { 
        return dirty; 
    }

    /**
     * Get the current render style based on configuration and shaderpack.
     * This is auto-detected based on the current rendering mode.
     */
    public RenderStyle getRenderStyle() {
        // Check the active shaderpack/override for the deferred baseline.
        VulkaniteConfig config = VulkaniteConfig.getInstance();
        if (config.shouldUseDeferredRendering(ShaderpackSettingsHandler.getCurrentShaderpackName())) {
            return RenderStyle.DEFERRED;
        }
        // If DLSS/DLSS_RR is enabled, we're in RTX mode
        if (dlssEnabled && denoiserType != DenoiserType.NONE) {
            return RenderStyle.RTX;
        }
        return RenderStyle.VANILLA;
    }

    /**
     * Get the effective resolution scale based on current settings.
     */
    public float getEffectiveScale() {
        if (!dlssEnabled) {
            return 1.0f;
        }
        
        switch (denoiserType) {
            case DLSS:
            case DLSS_RR:
                return qualityPreset.getScale();
            case FSR:
                return qualityPreset.getScale();
            default:
                return 1.0f;
        }
    }

    /**
     * Reset all settings to defaults.
     */
    public void resetToDefaults() {
        dlssEnabled = true;
        rayReconstructionEnabled = true;
        denoiserType = DenoiserType.DLSS_RR;
        qualityPreset = QualityPreset.QUALITY;
        debugType = DebugType.NONE;
        sharpness = 0.5f;
        motionVectorsEnabled = true;
        jitterEnabled = true;
        fsrEnabled = false;
        restirEnabled = false;
        indirectScale = 1.0f;
        ambientFactor = 0.1f;
        minLighting = 0.01f;
        specularIntensity = 1.0f;
        gamma = 2.2f;
        debugCellIndex = -1;
        shadowComparisonSamplingPercent = 100;
        shadowComparisonDistanceTolerance = 0.01f;
        shadowComparisonStructuredLogging = false;
        dirty = true;
        
        LOGGER.info("[Vulkanite] DLSS config reset to defaults");
    }
}
