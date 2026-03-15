package me.cortex.vulkanite.client.config;

import com.google.gson.*;
import me.cortex.vulkanite.client.rendering.DLSSRayReconstruction.DLSSQualityPreset;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * DLSS Configuration System
 *
 * This class manages upscaling/denoising settings including:
 * - Denoiser selection (DLSS, FSR, Basic)
 * - DLSS settings (quality preset, ray reconstruction, sharpening)
 * - FSR settings (quality preset, sharpening)
 *
 * Configuration is stored in a JSON file that can be edited by users.
 * Default location: .minecraft/vulkanite/dlss_config.json
 *
 * Example configuration:
 * {
 * "denoiser": "DLSS",
 * "enabled": true,
 * "qualityPreset": "QUALITY",
 * "rayReconstructionEnabled": true,
 * "sharpening": 0.5,
 * "fsrQualityPreset": "QUALITY",
 * "fsrSharpeningStrength": 0.5
 * }
 */
public class DLSSConfig {

    // Singleton instance - ensures all code uses the same configuration
    private static DLSSConfig INSTANCE = null;
    
    // Configuration file path
    private static final Path CONFIG_PATH = Paths.get("vulkanite", "dlss_config.json");

    // Denoiser selection
    private String denoiser; // DLSS, FSR, BASIC

    // Rendering Pipeline
    private String pipeline; // RASTER, DEFERRED, RTX

    // Master enable/disable
    private boolean enabled;

    // DLSS settings
    private String qualityPreset;
    private boolean rayReconstructionEnabled;
    private float sharpening;

    // FSR settings
    private String fsrQualityPreset;
    private float fsrSharpeningStrength;

    // Advanced settings
    private boolean debugMode;
    private boolean showPerformanceMetrics;

    // RT Quality Settings
    private float sunIntensity;
    private float indirectScale;
    private float ambientFactor;
    private float minLighting;
    private float specularIntensity;
    private float gamma;

    // ReSTIR Settings
    private boolean enableReSTIR;
    private int restirMaxHistory;
    private float restirSpatialRadius;
    private int restirSpatialSamples;

    public DLSSConfig() {
        // Default values
        this.denoiser = "DLSS";
        this.pipeline = "RASTER";
        this.enabled = false; // Disabled by default until SDK is implemented
        this.qualityPreset = "QUALITY";
        this.rayReconstructionEnabled = true;
        this.sharpening = 0.5f;

        // FSR defaults
        this.fsrQualityPreset = "QUALITY";
        this.fsrSharpeningStrength = 0.5f;

        // Advanced defaults
        this.debugMode = false;
        this.showPerformanceMetrics = false;

        // RT Quality defaults
        this.sunIntensity = 1.8f;
        this.indirectScale = 0.6f;
        this.ambientFactor = 0.05f;
        this.minLighting = 0.05f;
        this.specularIntensity = 1.0f;
        this.gamma = 1.0f;

        // ReSTIR defaults
        this.enableReSTIR = true;
        this.restirMaxHistory = 3;
        this.restirSpatialRadius = 4.0f;
        this.restirSpatialSamples = 2;
    }

    /**
     * Get the singleton instance of the configuration.
     * This ensures all code uses the same configuration object.
     */
    public static DLSSConfig getInstance() {
        if (INSTANCE == null) {
            INSTANCE = loadFromFile();
        }
        return INSTANCE;
    }
    
    /**
     * Reload configuration from file (useful for GUI changes)
     */
    public static void reload() {
        INSTANCE = loadFromFile();
    }
    
    /**
     * Load configuration - returns the singleton instance.
     * This method exists for backward compatibility.
     */
    public static DLSSConfig load() {
        return getInstance();
    }

    /**
     * Load configuration from file (internal method)
     */
    private static DLSSConfig loadFromFile() {
        DLSSConfig config = new DLSSConfig();

        try {
            File configFile = CONFIG_PATH.toFile();
            if (configFile.exists()) {
                String json = Files.readString(CONFIG_PATH);
                if (json == null || json.trim().isEmpty()) {
                    System.err.println("[Vulkanite] DLSS configuration file is empty, using defaults");
                    config.save(); // Overwrite with defaults
                    return config;
                }

                JsonElement element = JsonParser.parseString(json);
                if (element == null || !element.isJsonObject()) {
                    System.err.println("[Vulkanite] DLSS configuration file contains invalid JSON, using defaults");
                    config.save(); // Overwrite with defaults
                    return config;
                }

                JsonObject jsonObject = element.getAsJsonObject();

                // Parse denoiser selection
                if (jsonObject.has("denoiser")) {
                    config.denoiser = jsonObject.get("denoiser").getAsString();
                }

                // Parse pipeline selection
                if (jsonObject.has("pipeline")) {
                    config.pipeline = jsonObject.get("pipeline").getAsString();
                }

                // Parse master enable
                if (jsonObject.has("enabled")) {
                    config.enabled = jsonObject.get("enabled").getAsBoolean();
                }

                // Parse DLSS settings
                if (jsonObject.has("qualityPreset")) {
                    config.qualityPreset = jsonObject.get("qualityPreset").getAsString();
                }
                if (jsonObject.has("rayReconstructionEnabled")) {
                    config.rayReconstructionEnabled = jsonObject.get("rayReconstructionEnabled").getAsBoolean();
                }
                if (jsonObject.has("sharpening")) {
                    config.sharpening = jsonObject.get("sharpening").getAsFloat();
                }

                // Parse FSR settings
                if (jsonObject.has("fsrQualityPreset")) {
                    config.fsrQualityPreset = jsonObject.get("fsrQualityPreset").getAsString();
                }
                if (jsonObject.has("fsrSharpeningStrength")) {
                    config.fsrSharpeningStrength = jsonObject.get("fsrSharpeningStrength").getAsFloat();
                }

                // Parse advanced settings
                if (jsonObject.has("debugMode")) {
                    config.debugMode = jsonObject.get("debugMode").getAsBoolean();
                }
                if (jsonObject.has("showPerformanceMetrics")) {
                    config.showPerformanceMetrics = jsonObject.get("showPerformanceMetrics").getAsBoolean();
                }

                // Parse RT Quality Settings
                if (jsonObject.has("sunIntensity")) {
                    config.sunIntensity = jsonObject.get("sunIntensity").getAsFloat();
                }
                if (jsonObject.has("indirectScale")) {
                    config.indirectScale = jsonObject.get("indirectScale").getAsFloat();
                }
                if (jsonObject.has("ambientFactor")) {
                    config.ambientFactor = jsonObject.get("ambientFactor").getAsFloat();
                }
                if (jsonObject.has("minLighting")) {
                    config.minLighting = jsonObject.get("minLighting").getAsFloat();
                }
                if (jsonObject.has("specularIntensity")) {
                    config.specularIntensity = jsonObject.get("specularIntensity").getAsFloat();
                }
                if (jsonObject.has("gamma")) {
                    config.gamma = jsonObject.get("gamma").getAsFloat();
                }

                // Parse ReSTIR Settings
                if (jsonObject.has("enableReSTIR")) {
                    config.enableReSTIR = jsonObject.get("enableReSTIR").getAsBoolean();
                }
                if (jsonObject.has("restirMaxHistory")) {
                    config.restirMaxHistory = jsonObject.get("restirMaxHistory").getAsInt();
                }
                if (jsonObject.has("restirSpatialRadius")) {
                    config.restirSpatialRadius = jsonObject.get("restirSpatialRadius").getAsFloat();
                }
                if (jsonObject.has("restirSpatialSamples")) {
                    config.restirSpatialSamples = jsonObject.get("restirSpatialSamples").getAsInt();
                }
    
                System.out.println("[Vulkanite] Loaded DLSS configuration from " + CONFIG_PATH + " [debugMode=" + config.debugMode + "]");
            } else {
                // Create default config file
                config.save();
                System.out.println("[Vulkanite] Created default DLSS configuration file");
            }
        } catch (IOException e) {
            System.err.println("[Vulkanite] Failed to load DLSS configuration: " + e.getMessage());
            System.err.println("[Vulkanite] Using default configuration");
        }

        return config;
    }

    /**
     * Save configuration to file
     */
    public void save() {
        try {
            // Create parent directories if they don't exist
            Files.createDirectories(CONFIG_PATH.getParent());

            JsonObject jsonObject = new JsonObject();

            // Denoiser selection
            jsonObject.addProperty("denoiser", denoiser);

            // Pipeline selection
            jsonObject.addProperty("pipeline", pipeline);

            // Master enable
            jsonObject.addProperty("enabled", enabled);

            // DLSS settings
            jsonObject.addProperty("qualityPreset", qualityPreset);
            jsonObject.addProperty("rayReconstructionEnabled", rayReconstructionEnabled);
            jsonObject.addProperty("sharpening", sharpening);

            // FSR settings
            jsonObject.addProperty("fsrQualityPreset", fsrQualityPreset);
            jsonObject.addProperty("fsrSharpeningStrength", fsrSharpeningStrength);

            // Advanced settings
            jsonObject.addProperty("debugMode", debugMode);
            jsonObject.addProperty("showPerformanceMetrics", showPerformanceMetrics);

            // RT Quality Settings
            jsonObject.addProperty("sunIntensity", sunIntensity);
            jsonObject.addProperty("indirectScale", indirectScale);
            jsonObject.addProperty("ambientFactor", ambientFactor);
            jsonObject.addProperty("minLighting", minLighting);
            jsonObject.addProperty("specularIntensity", specularIntensity);
            jsonObject.addProperty("gamma", gamma);

            // ReSTIR Settings
            jsonObject.addProperty("enableReSTIR", enableReSTIR);
            jsonObject.addProperty("restirMaxHistory", restirMaxHistory);
            jsonObject.addProperty("restirSpatialRadius", restirSpatialRadius);
            jsonObject.addProperty("restirSpatialSamples", restirSpatialSamples);

            // Write to file
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            Files.writeString(CONFIG_PATH, gson.toJson(jsonObject));

            System.out.println("[Vulkanite] Saved DLSS configuration to " + CONFIG_PATH + " [debugMode=" + debugMode + "]");
        } catch (IOException e) {
            System.err.println("[Vulkanite] Failed to save DLSS configuration: " + e.getMessage());
        }
    }

    // Getters and Setters

    public String getDenoiser() {
        return denoiser;
    }

    public void setDenoiser(String denoiser) {
        this.denoiser = denoiser;
    }

    public DenoiserType getDenoiserType() {
        try {
            return DenoiserType.valueOf(denoiser.toUpperCase());
        } catch (IllegalArgumentException e) {
            return DenoiserType.DLSS;
        }
    }

    public void setDenoiserType(DenoiserType type) {
        this.denoiser = type.name();
    }

    public String getPipeline() {
        return pipeline;
    }

    public void setPipeline(String pipeline) {
        this.pipeline = pipeline;
    }

    public RenderingPipeline getRenderingPipeline() {
        try {
            return RenderingPipeline.valueOf(pipeline.toUpperCase());
        } catch (IllegalArgumentException e) {
            return RenderingPipeline.RASTER;
        }
    }

    public void setRenderingPipeline(RenderingPipeline pipeline) {
        this.pipeline = pipeline.name();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        me.cortex.vulkanite.client.rendering.JitterManager.setEnabled(enabled);
    }

    public DLSSQualityPreset getQualityPreset() {
        try {
            return DLSSQualityPreset.valueOf(qualityPreset.toUpperCase());
        } catch (IllegalArgumentException e) {
            return DLSSQualityPreset.QUALITY;
        }
    }

    public void setQualityPreset(DLSSQualityPreset preset) {
        this.qualityPreset = preset.name();
    }

    public boolean isRayReconstructionEnabled() {
        return rayReconstructionEnabled;
    }

    public void setRayReconstructionEnabled(boolean enabled) {
        this.rayReconstructionEnabled = enabled;
    }

    public float getSharpening() {
        return sharpening;
    }

    public void setSharpening(float sharpening) {
        this.sharpening = Math.max(0.0f, Math.min(1.0f, sharpening));
    }

    public FSRQualityPreset getFsrQualityPreset() {
        try {
            return FSRQualityPreset.valueOf(fsrQualityPreset.toUpperCase());
        } catch (IllegalArgumentException e) {
            return FSRQualityPreset.QUALITY;
        }
    }

    public void setFsrQualityPreset(FSRQualityPreset preset) {
        this.fsrQualityPreset = preset.name();
    }

    public float getFsrSharpeningStrength() {
        return fsrSharpeningStrength;
    }

    public void setFsrSharpeningStrength(float strength) {
        this.fsrSharpeningStrength = Math.max(0.0f, Math.min(1.0f, strength));
    }

    public boolean isDebugMode() {
        // DEBUG LOG: Track when debugMode is read
        return debugMode;
    }

    public void setDebugMode(boolean debugMode) {
        // DEBUG LOG: Track when debugMode is changed
        System.out.println("[Vulkanite DLSSConfig] setDebugMode called: " + this.debugMode + " -> " + debugMode);
        this.debugMode = debugMode;
    }

    public boolean isShowPerformanceMetrics() {
        return showPerformanceMetrics;
    }

    public void setShowPerformanceMetrics(boolean showPerformanceMetrics) {
        this.showPerformanceMetrics = showPerformanceMetrics;
    }

    public boolean isReSTIREnabled() {
        return enableReSTIR;
    }

    public void setReSTIREnabled(boolean enableReSTIR) {
        this.enableReSTIR = enableReSTIR;
    }

    // RT Quality Getters and Setters
    public float getSunIntensity() {
        return sunIntensity;
    }

    public void setSunIntensity(float v) {
        this.sunIntensity = Math.max(0.0f, Math.min(5.0f, v));
    }

    public float getIndirectScale() {
        return indirectScale;
    }

    public void setIndirectScale(float v) {
        this.indirectScale = Math.max(0.0f, Math.min(2.0f, v));
    }

    public float getAmbientFactor() {
        return ambientFactor;
    }

    public void setAmbientFactor(float v) {
        this.ambientFactor = Math.max(0.0f, Math.min(1.0f, v));
    }

    public float getMinLighting() {
        return minLighting;
    }

    public void setMinLighting(float v) {
        this.minLighting = Math.max(0.0f, Math.min(1.0f, v));
    }

    public float getSpecularIntensity() {
        return specularIntensity;
    }

    public void setSpecularIntensity(float v) {
        this.specularIntensity = Math.max(0.0f, Math.min(3.0f, v));
    }

    public float getGamma() {
        return gamma;
    }

    public void setGamma(float v) {
        this.gamma = Math.max(0.1f, Math.min(3.0f, v));
    }

    // ReSTIR Advanced Getters and Setters
    public int getRestirMaxHistory() {
        return restirMaxHistory;
    }

    public void setRestirMaxHistory(int v) {
        this.restirMaxHistory = Math.max(1, Math.min(20, v));
    }

    public float getRestirSpatialRadius() {
        return restirSpatialRadius;
    }

    public void setRestirSpatialRadius(float v) {
        this.restirSpatialRadius = Math.max(1.0f, Math.min(16.0f, v));
    }

    public int getRestirSpatialSamples() {
        return restirSpatialSamples;
    }

    public void setRestirSpatialSamples(int v) {
        this.restirSpatialSamples = Math.max(0, Math.min(8, v));
    }

    /**
     * FSR Quality Presets
     */
    public enum FSRQualityPreset {
        QUALITY(0.667f),
        BALANCED(0.59f),
        PERFORMANCE(0.5f),
        ULTRA_PERFORMANCE(0.36f);

        private final float scale;

        FSRQualityPreset(float scale) {
            this.scale = scale;
        }

        public float getScale() {
            return scale;
        }
    }

    /**
     * Rendering Pipeline
     */
    public enum RenderingPipeline {
        RASTER,
        DEFERRED,
        RTX
    }

    /**
     * Denoiser Type enumeration
     */
    public enum DenoiserType {
        DLSS, // NVIDIA DLSS Ray Reconstruction (RTX only)
        FSR, // AMD FidelityFX Super Resolution (all GPUs)
        BASIC // Basic temporal accumulation (fallback)
    }

    /**
     * Get a human-readable description of the current configuration
     */
    public String getDescription() {
        StringBuilder sb = new StringBuilder();
        sb.append("Vulkanite Upscaler/Denoiser Configuration:\n");
        sb.append(" Denoiser: ").append(denoiser).append("\n");
        sb.append(" Enabled: ").append(enabled).append("\n");
        sb.append("\nDLSS Settings:\n");
        sb.append(" Quality Preset: ").append(qualityPreset).append("\n");
        sb.append(" Ray Reconstruction: ").append(rayReconstructionEnabled).append("\n");
        sb.append(" Sharpening: ").append(sharpening).append("\n");
        sb.append("\nFSR Settings:\n");
        sb.append(" Quality Preset: ").append(fsrQualityPreset).append("\n");
        sb.append(" Sharpening: ").append(fsrSharpeningStrength).append("\n");
        return sb.toString();
    }
}
