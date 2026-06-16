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

    // ReSTIR/DIRT RT settings
    public boolean enableDirtRt = false;
    public int restirReservoirWidth = 1280;
    public int restirReservoirHeight = 720;

    // Legacy deferred rendering preference.
    // Kept for config compatibility, but the active shaderpack/override now decides
    // whether the deferred baseline is selected.
    public boolean deferredRenderingEnabled = false;

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

    // Other settings can be added here
    public boolean rtxEntityCaptureEnabled = true;
    public boolean rtxParticleCaptureEnabled = true;
    public int rtxEntityCaptureInterval = 2;
    public int rtxMaxCapturedEntities = 96;
    public int rtxMaxCapturedParticles = 384;
    public int rtxEntityBlasCacheSize = 384;

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
            
            // Deferred rendering settings
            deferredRenderingEnabled = Boolean.parseBoolean(props.getProperty("deferredRenderingEnabled", "false"));
            deferredComputeEnabled = Boolean.parseBoolean(props.getProperty("deferredComputeEnabled", "false"));
            deferredComputeExperimental = Boolean.parseBoolean(props.getProperty("deferredComputeExperimental", "false"));
            String overrideValue = props.getProperty("deferredRenderingOverride");
            if (overrideValue != null && !overrideValue.isEmpty()) {
                deferredRenderingOverride = Boolean.parseBoolean(overrideValue);
            }
            rtxEntityCaptureEnabled = Boolean.parseBoolean(props.getProperty("rtxEntityCaptureEnabled", "true"));
            rtxParticleCaptureEnabled = Boolean.parseBoolean(props.getProperty("rtxParticleCaptureEnabled", "true"));
            rtxEntityCaptureInterval = parsePositiveInt(
                    props.getProperty("rtxEntityCaptureInterval", "2"), 2, 1, 20);
            rtxMaxCapturedEntities = parsePositiveInt(
                    props.getProperty("rtxMaxCapturedEntities", "96"), 96, 1, 512);
            rtxMaxCapturedParticles = parsePositiveInt(
                    props.getProperty("rtxMaxCapturedParticles", "384"), 384, 1, 4096);
            rtxEntityBlasCacheSize = parsePositiveInt(
                    props.getProperty("rtxEntityBlasCacheSize", "384"), 384, 32, 2048);

        } catch (IOException e) {
            System.err.println("[Vulkanite] Failed to load config: " + e.getMessage());
            saveConfig(); // Save default config
        }
    }

    private static int parsePositiveInt(String value, int fallback, int min, int max) {
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
        props.setProperty("deferredRenderingEnabled", String.valueOf(deferredRenderingEnabled));
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
        props.setProperty("rtxEntityBlasCacheSize", String.valueOf(rtxEntityBlasCacheSize));

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
     * Sets the deferred rendering mode and saves the config.
     * @param enabled Whether deferred rendering should be enabled
     */
    public void setDeferredRenderingEnabled(boolean enabled) {
        this.deferredRenderingEnabled = enabled;
        saveConfig();
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
