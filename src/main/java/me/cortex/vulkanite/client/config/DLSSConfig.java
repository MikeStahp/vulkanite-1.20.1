package me.cortex.vulkanite.client.config;

import me.cortex.vulkanite.client.ShaderpackSettingsHandler;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
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
    }

    /**
     * FSR Quality preset - separate enum for FSR-specific scaling
     */
    public enum FSRQualityPreset {
        QUALITY(0.667f, "Quality"),
        BALANCED(0.583f, "Balanced"),
        PERFORMANCE(0.5f, "Performance"),
        ULTRA_PERFORMANCE(0.333f, "Ultra Performance");

        private final float scale;
        private final String displayName;

        FSRQualityPreset(float scale, String displayName) {
            this.scale = scale;
            this.displayName = displayName;
        }

        public float getScale() { return scale; }
        public String getDisplayName() { return displayName; }
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
        NORMALS("Normals", "Show normal buffer");

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
    private int fsrQuality = 1; // 0=Quality, 1=Balanced, 2=Performance, 3=Ultra Performance

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

            // Core DLSS Settings
            dlssEnabled = Boolean.parseBoolean(props.getProperty("dlssEnabled", "true"));
            rayReconstructionEnabled = Boolean.parseBoolean(props.getProperty("rayReconstructionEnabled", "true"));
            denoiserType = parseDenoiserType(props.getProperty("denoiserType", "DLSS_RR"));
            qualityPreset = parseQualityPreset(props.getProperty("qualityPreset", "QUALITY"));
            debugType = parseDebugType(props.getProperty("debugType", "NONE"));
            sharpness = Float.parseFloat(props.getProperty("sharpness", "0.5"));
            motionVectorsEnabled = Boolean.parseBoolean(props.getProperty("motionVectorsEnabled", "true"));
            jitterEnabled = Boolean.parseBoolean(props.getProperty("jitterEnabled", "true"));

            // FSR Settings
            fsrEnabled = Boolean.parseBoolean(props.getProperty("fsrEnabled", "false"));
            fsrQuality = Integer.parseInt(props.getProperty("fsrQuality", "1"));

            // ReSTIR Settings
            restirEnabled = Boolean.parseBoolean(props.getProperty("restirEnabled", "false"));

            // World/Lighting Parameters
            indirectScale = Float.parseFloat(props.getProperty("indirectScale", "1.0"));
            ambientFactor = Float.parseFloat(props.getProperty("ambientFactor", "0.1"));
            minLighting = Float.parseFloat(props.getProperty("minLighting", "0.01"));
            specularIntensity = Float.parseFloat(props.getProperty("specularIntensity", "1.0"));
            gamma = Float.parseFloat(props.getProperty("gamma", "2.2"));

            // Debug Settings
            debugCellIndex = Integer.parseInt(props.getProperty("debugCellIndex", "-1"));

            LOGGER.info("[Vulkanite] DLSS config loaded: denoiser={}, quality={}, restir={}", 
                denoiserType, qualityPreset, restirEnabled);

        } catch (IOException e) {
            LOGGER.error("[Vulkanite] Failed to load DLSS config: {}", e.getMessage());
            saveConfig(); // Save defaults
        } catch (NumberFormatException e) {
            LOGGER.error("[Vulkanite] Invalid config value: {}", e.getMessage());
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
        props.setProperty("fsrQuality", String.valueOf(fsrQuality));

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

    private DenoiserType parseDenoiserType(String value) {
        try {
            return DenoiserType.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            return DenoiserType.DLSS_RR;
        }
    }

    private QualityPreset parseQualityPreset(String value) {
        try {
            return QualityPreset.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            return QualityPreset.QUALITY;
        }
    }

    private DebugType parseDebugType(String value) {
        try {
            return DebugType.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            return DebugType.NONE;
        }
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

    public boolean isFSREnabled() { return fsrEnabled; }

    public int getFSRQuality() { return fsrQuality; }

    public FSRQualityPreset getFSRQualityPreset() {
        switch (fsrQuality) {
            case 0: return FSRQualityPreset.QUALITY;
            case 1: return FSRQualityPreset.BALANCED;
            case 2: return FSRQualityPreset.PERFORMANCE;
            case 3: return FSRQualityPreset.ULTRA_PERFORMANCE;
            default: return FSRQualityPreset.BALANCED;
        }
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

    // =====================================================================
    // SETTERS - Core DLSS Settings
    // =====================================================================

    public void setDLSSEnabled(boolean enabled) { 
        this.dlssEnabled = enabled; 
        this.dirty = true;
    }

    public void setRayReconstructionEnabled(boolean enabled) { 
        this.rayReconstructionEnabled = enabled; 
        this.dirty = true;
    }

    public void setDenoiserType(DenoiserType type) { 
        this.denoiserType = type; 
        this.dirty = true;
    }

    public void setQualityPreset(QualityPreset preset) { 
        this.qualityPreset = preset; 
        this.dirty = true;
    }

    public void setDebugType(DebugType type) { 
        this.debugType = type; 
        this.dirty = true;
    }

    public void setSharpness(float sharpness) { 
        this.sharpness = Math.max(0.0f, Math.min(1.0f, sharpness)); 
        this.dirty = true;
    }

    public void setMotionVectorsEnabled(boolean enabled) { 
        this.motionVectorsEnabled = enabled; 
        this.dirty = true;
    }

    public void setJitterEnabled(boolean enabled) { 
        this.jitterEnabled = enabled; 
        this.dirty = true;
    }

    // =====================================================================
    // SETTERS - FSR Settings
    // =====================================================================

    public void setFSREnabled(boolean enabled) { 
        this.fsrEnabled = enabled; 
        this.dirty = true;
    }

    public void setFSRQuality(int quality) { 
        this.fsrQuality = Math.max(0, Math.min(3, quality)); 
        this.dirty = true;
    }

    // =====================================================================
    // SETTERS - ReSTIR Settings
    // =====================================================================

    public void setReSTIREnabled(boolean enabled) { 
        this.restirEnabled = enabled; 
        this.dirty = true;
    }

    // =====================================================================
    // SETTERS - World/Lighting Parameters
    // =====================================================================

    public void setIndirectScale(float scale) { 
        this.indirectScale = Math.max(0.0f, scale); 
        this.dirty = true;
    }

    public void setAmbientFactor(float factor) { 
        this.ambientFactor = Math.max(0.0f, Math.min(1.0f, factor)); 
        this.dirty = true;
    }

    public void setMinLighting(float min) { 
        this.minLighting = Math.max(0.0f, Math.min(1.0f, min)); 
        this.dirty = true;
    }

    public void setSpecularIntensity(float intensity) { 
        this.specularIntensity = Math.max(0.0f, intensity); 
        this.dirty = true;
    }

    public void setGamma(float gamma) { 
        this.gamma = Math.max(1.0f, Math.min(3.0f, gamma)); 
        this.dirty = true;
    }

    // =====================================================================
    // SETTERS - Debug Settings
    // =====================================================================

    public void setDebugCellIndex(int index) {
        this.debugCellIndex = index;
        this.dirty = true;
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
                return getFSRQualityPreset().getScale();
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
        fsrQuality = 1;
        restirEnabled = false;
        indirectScale = 1.0f;
        ambientFactor = 0.1f;
        minLighting = 0.01f;
        specularIntensity = 1.0f;
        gamma = 2.2f;
        debugCellIndex = -1;
        dirty = true;
        
        LOGGER.info("[Vulkanite] DLSS config reset to defaults");
    }
}
