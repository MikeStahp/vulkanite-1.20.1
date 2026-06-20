package me.cortex.vulkanite.client;

import me.cortex.vulkanite.client.config.VulkaniteConfig;
import net.fabricmc.loader.api.FabricLoader;
import me.cortex.vulkanite.client.rendering.VulkanPipeline;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.shaderpack.properties.ShaderProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileReader;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

public class ShaderpackSettingsHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(ShaderpackSettingsHandler.class);
    
    private static String lastDetectedShaderpack = null;

    /**
     * Attempts to extract shaderpack settings from the IrisRenderingPipeline
     * and apply them to Vulkanite configuration
     */
    public static void applyShaderpackSettings(IrisRenderingPipeline pipeline) {
        try {
            // Try to access the shaderProperties field through reflection
            Field shaderPropertiesField = IrisRenderingPipeline.class.getDeclaredField("shaderProperties");
            shaderPropertiesField.setAccessible(true);

            ShaderProperties shaderProperties = (ShaderProperties) shaderPropertiesField.get(pipeline);
            if (shaderProperties != null) {
                applySettingsFromShaderProperties(shaderProperties);
            }
        } catch (NoSuchFieldException e) {
            LOGGER.debug("[Vulkanite] Iris pipeline does not expose shaderProperties directly");
        } catch (Exception e) {
            LOGGER.error("[Vulkanite] Failed to access shaderpack settings through reflection: {}", e.getMessage());
        }

        applySettingsFromActiveShaderpack();
    }
    
    /**
     * Detects and handles shaderpack changes for deferred rendering mode.
     * Should be called when Iris loads a new shaderpack.
     * 
     * @param pipeline The VulkanPipeline instance to update
     */
    public static void detectAndApplyShaderpack(VulkanPipeline pipeline) {
        String shaderpackName = getCurrentShaderpackName();

        if (pipeline != null) {
            pipeline.updateShaderpack(shaderpackName);
        }
        
        // Only process if shaderpack changed. Null is meaningful: Iris may have
        // disabled shaderpacks, so the Vulkan pipeline must leave deferred mode.
        if (!java.util.Objects.equals(shaderpackName, lastDetectedShaderpack)) {
            LOGGER.info("[Vulkanite] Shaderpack changed: {} -> {}", lastDetectedShaderpack, shaderpackName);
            lastDetectedShaderpack = shaderpackName;
            
            // Log deferred mode detection
            VulkaniteConfig config = VulkaniteConfig.getInstance();
            if (config.shouldUseDeferredRendering(shaderpackName)) {
                LOGGER.info("[Vulkanite] Deferred baseline selected for shaderpack '{}'", shaderpackName);
                if (config.shouldUseDeferredCompute(shaderpackName)) {
                    LOGGER.info("[Vulkanite] Optional Vulkan deferred compute pass is enabled");
                } else if (config.deferredComputeEnabled) {
                    LOGGER.warn("[Vulkanite] Deferred compute was requested but is not armed. Set deferredComputeExperimental=true to test it.");
                }
            } else {
                LOGGER.info("[Vulkanite] Standard shaderpack detected - using RTX path");
            }
        }
    }
    
    /**
     * Gets the current shaderpack name from Iris.
     * Uses reflection to access Iris's internal shaderpack reference.
     * @return The shaderpack name, or null if none is loaded
     */
    public static String getCurrentShaderpackName() {
        try {
            // Try to access Iris's current shaderpack through reflection
            Field irisField = Iris.class.getDeclaredField("currentPack");
            irisField.setAccessible(true);
            Object pack = irisField.get(null);
            if (pack != null) {
                // Try to get the name from the pack
                try {
                    Field nameField = pack.getClass().getDeclaredField("name");
                    nameField.setAccessible(true);
                    return (String) nameField.get(pack);
                } catch (NoSuchFieldException e) {
                    // Try alternative field names
                    try {
                        Field fileField = pack.getClass().getDeclaredField("pack");
                        fileField.setAccessible(true);
                        File file = (File) fileField.get(pack);
                        if (file != null) {
                            return file.getName();
                        }
                    } catch (Exception ex) {
                        // Fallback to toString
                        return pack.toString();
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.debug("[Vulkanite] Could not get shaderpack name from Iris: {}", e.getMessage());
        }
        
        // Fallback: read Iris' persisted active shaderpack selection.
        try {
            File irisProperties = FabricLoader.getInstance().getConfigDir().resolve("iris.properties").toFile();
            if (irisProperties.exists()) {
                Properties props = new Properties();
                try (FileReader reader = new FileReader(irisProperties)) {
                    props.load(reader);
                }
                String shaderpackName = props.getProperty("shaderPack");
                if (shaderpackName != null && !shaderpackName.isBlank()) {
                    return shaderpackName;
                }
            }
        } catch (Exception e) {
            LOGGER.debug("[Vulkanite] Could not read Iris shaderpack config: {}", e.getMessage());
        }
        
        return null;
    }
    
    /**
     * Checks if the current shaderpack is VulkaniteDeferred.
     * @return true if VulkaniteDeferred is loaded
     */
    public static boolean isVulkaniteDeferredActive() {
        String name = getCurrentShaderpackName();
        return name != null && name.contains("VulkaniteDeferred");
    }

    /**
     * Checks whether Vulkanite should use its own deferred-specific path.
     * This is stricter than checking whether VulkaniteDeferred exists on disk:
     * compatibility-sensitive render target changes must only apply to the
     * active Vulkanite deferred pack or an explicit user override.
     *
     * @return true if the active configuration should use Vulkanite deferred rendering
     */
    public static boolean shouldUseDeferredRenderingPath() {
        VulkaniteConfig config = VulkaniteConfig.getInstance();
        if (config.deferredRenderingOverride != null) {
            return config.deferredRenderingOverride;
        }

        return isVulkaniteDeferredActive();
    }

    /**
     * Applies settings from ShaderProperties to Vulkanite configuration
     */
    private static void applySettingsFromShaderProperties(ShaderProperties shaderProperties) {
        try {
            // Access the variables map through reflection
            Field variablesField = ShaderProperties.class.getDeclaredField("variables");
            variablesField.setAccessible(true);

            Map<String, String> variables = (Map<String, String>) variablesField.get(shaderProperties);
            applySettingsFromMap(variables, "shaderpack variables");
        } catch (NoSuchFieldException e) {
            LOGGER.debug("[Vulkanite] Iris ShaderProperties does not expose legacy variables");
        } catch (Exception e) {
            LOGGER.error("[Vulkanite] Failed to access shaderpack variables: {}", e.getMessage());
        }
    }

    private static void applySettingsFromActiveShaderpack() {
        Map<String, String> settings = new HashMap<>();

        try {
            Iris.getCurrentPack().ifPresent(pack -> {
                var optionValues = pack.getShaderPackOptions().getOptionValues().mutableCopy();
                optionValues.getStringValues().forEach(settings::put);
                optionValues.getBooleanValues().forEach((key, value) -> settings.put(key, String.valueOf(value)));
            });
        } catch (Exception e) {
            LOGGER.debug("[Vulkanite] Could not read Iris shaderpack option values: {}", e.getMessage());
        }

        settings.putAll(readPersistedShaderOptions());
        applySettingsFromMap(settings, "shaderpack options");
    }

    private static Map<String, String> readPersistedShaderOptions() {
        Map<String, String> settings = new HashMap<>();
        Path optionsFile = FabricLoader.getInstance().getGameDir().resolve("optionsshaders.txt");
        if (!Files.isRegularFile(optionsFile)) {
            return settings;
        }

        try (FileReader reader = new FileReader(optionsFile.toFile())) {
            Properties properties = new Properties();
            properties.load(reader);
            for (String name : properties.stringPropertyNames()) {
                settings.put(name, properties.getProperty(name));
            }
        } catch (Exception e) {
            LOGGER.debug("[Vulkanite] Could not read persisted shader options: {}", e.getMessage());
        }

        return settings;
    }

    private static void applySettingsFromMap(Map<String, String> settings, String source) {
        if (settings == null || settings.isEmpty()) {
            return;
        }

        VulkaniteConfig config = VulkaniteConfig.getInstance();

        applyBoolean(settings, source, "enableDirtRt", "DIRT_RT", value -> {
            config.enableDirtRt = value;
            LOGGER.info("[Vulkanite] {} DIRT RT from {}", value ? "Enabled" : "Disabled", source);
        });

        applyRestirToggle(settings, source);

        applyInt(settings, source, "restirReservoirWidth", "RESTIR_RESERVOIR_WIDTH", 64, 7680,
                value -> {
                    config.restirReservoirWidth = value;
                    LOGGER.info("[Vulkanite] Set ReSTIR reservoir width to {} from {}", value, source);
                });
        applyInt(settings, source, "restirReservoirHeight", "RESTIR_RESERVOIR_HEIGHT", 64, 4320,
                value -> {
                    config.restirReservoirHeight = value;
                    LOGGER.info("[Vulkanite] Set ReSTIR reservoir height to {} from {}", value, source);
                });

        applyBoolean(settings, source, "rtxEntityCaptureEnabled", "RTX_ENTITY_CAPTURE", value -> {
            config.rtxEntityCaptureEnabled = value;
            LOGGER.info("[Vulkanite] {} RTX entity capture from {}", value ? "Enabled" : "Disabled", source);
        });
        applyBoolean(settings, source, "rtxParticleCaptureEnabled", "RTX_PARTICLE_CAPTURE", value -> {
            config.rtxParticleCaptureEnabled = false;
            if (value) {
                LOGGER.info("[Vulkanite] Ignoring RTX particle capture from {}; using raster particles on top", source);
            } else {
                LOGGER.info("[Vulkanite] Disabled RTX particle capture from {}", source);
            }
        });
        applyInt(settings, source, "rtxEntityCaptureInterval", "RTX_CAPTURE_INTERVAL", 1, 20,
                value -> {
                    config.rtxEntityCaptureInterval = value;
                    LOGGER.info("[Vulkanite] Set RTX transient capture interval to {} from {}", value, source);
                });
        applyInt(settings, source, "rtxMaxCapturedEntities", "RTX_MAX_CAPTURED_ENTITIES", 1, 512,
                value -> {
                    config.rtxMaxCapturedEntities = value;
                    LOGGER.info("[Vulkanite] Set RTX entity capture cap to {} from {}", value, source);
                });
        applyInt(settings, source, "rtxMaxCapturedParticles", "RTX_MAX_CAPTURED_PARTICLES", 1, 4096,
                value -> {
                    config.rtxMaxCapturedParticles = value;
                    LOGGER.info("[Vulkanite] Set RTX particle capture cap to {} from {}", value, source);
                });
        applyInt(settings, source, "rtxEntityCaptureRadius", "RTX_ENTITY_CAPTURE_RADIUS", 8, 256,
                value -> {
                    config.rtxEntityCaptureRadius = value;
                    LOGGER.info("[Vulkanite] Set RTX entity capture radius to {} from {}", value, source);
                });
        applyInt(settings, source, "rtxEntityBlasCacheSize", "RTX_ENTITY_BLAS_CACHE", 32, 2048,
                value -> {
                    config.rtxEntityBlasCacheSize = value;
                    LOGGER.info("[Vulkanite] Set RTX entity BLAS cache size to {} from {}", value, source);
                });

        String deferredComputeValue = getSetting(settings, "deferred.compute.enabled", "deferred.lighting.enabled",
                "DEFERRED_COMPUTE_ENABLED");
        Boolean deferredCompute = parseBoolean(deferredComputeValue);
        if (deferredCompute != null) {
            config.setDeferredComputeEnabled(deferredCompute);
            LOGGER.info("[Vulkanite] {} deferred compute from {}",
                    deferredCompute ? "Enabled" : "Disabled", source);
        }
    }

    private static void applyRestirToggle(Map<String, String> settings, String source) {
        String value = getSetting(settings, "enableRestir", "ENABLE_RESTIR");
        Boolean enabled = parseBoolean(value);
        if (enabled == null) {
            return;
        }

        me.cortex.vulkanite.client.config.DLSSConfig dlssConfig =
                me.cortex.vulkanite.client.config.DLSSConfig.load();
        dlssConfig.setReSTIREnabled(enabled);
        dlssConfig.save();
        LOGGER.info("[Vulkanite] {} ReSTIR from {}", enabled ? "Enabled" : "Disabled", source);
    }

    private static void applyBoolean(Map<String, String> settings, String source, String primary,
            String alias, java.util.function.Consumer<Boolean> consumer) {
        String raw = getSetting(settings, primary, alias);
        Boolean value = parseBoolean(raw);
        if (value == null) {
            if (raw != null) {
                LOGGER.warn("[Vulkanite] Invalid {} value '{}' in {}", primary, raw, source);
            }
            return;
        }

        consumer.accept(value);
    }

    private static void applyInt(Map<String, String> settings, String source, String primary,
            String alias, int min, int max, java.util.function.IntConsumer consumer) {
        String raw = getSetting(settings, primary, alias);
        if (raw == null) {
            return;
        }

        try {
            int value = Math.max(min, Math.min(max, Integer.parseInt(raw.trim())));
            consumer.accept(value);
        } catch (NumberFormatException e) {
            LOGGER.warn("[Vulkanite] Invalid {} value '{}' in {}", primary, raw, source);
        }
    }

    private static String getSetting(Map<String, String> settings, String... names) {
        for (String name : names) {
            String value = settings.get(name);
            if (value != null) {
                return value;
            }
        }

        return null;
    }

    private static Boolean parseBoolean(String value) {
        if (value == null) {
            return null;
        }

        String normalized = value.trim();
        if ("true".equalsIgnoreCase(normalized) || "1".equals(normalized)) {
            return true;
        }
        if ("false".equalsIgnoreCase(normalized) || "0".equals(normalized)) {
            return false;
        }

        return null;
    }
}
