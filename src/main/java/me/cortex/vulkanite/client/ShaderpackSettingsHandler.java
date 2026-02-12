package me.cortex.vulkanite.client;

import me.cortex.vulkanite.client.config.VulkaniteConfig;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.shaderpack.properties.ShaderProperties;

import java.lang.reflect.Field;
import java.util.Map;

public class ShaderpackSettingsHandler {
    
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
        } catch (Exception e) {
            System.err.println("[Vulkanite] Failed to access shaderpack settings through reflection: " + e.getMessage());
        }
    }
    
    /**
     * Applies settings from ShaderProperties to Vulkanite configuration
     */
    private static void applySettingsFromShaderProperties(ShaderProperties shaderProperties) {
        VulkaniteConfig config = VulkaniteConfig.getInstance();
        
        try {
            // Access the variables map through reflection
            Field variablesField = ShaderProperties.class.getDeclaredField("variables");
            variablesField.setAccessible(true);
            
            Map<String, String> variables = (Map<String, String>) variablesField.get(shaderProperties);
            
            // Check for DIRT RT setting
            if (variables.containsKey("enableDirtRt")) {
                String value = variables.get("enableDirtRt");
                if ("true".equals(value)) {
                    config.enableDirtRt = true;
                    System.out.println("[Vulkanite] Enabled DIRT RT from shaderpack settings");
                } else if ("false".equals(value)) {
                    config.enableDirtRt = false;
                    System.out.println("[Vulkanite] Disabled DIRT RT from shaderpack settings");
                }
            }
            
            // Check for ReSTIR enable setting (if it exists in shaderpack)
            if (variables.containsKey("enableRestir")) {
                String value = variables.get("enableRestir");
                if ("true".equals(value)) {
                    config.enableRestir = true;
                    System.out.println("[Vulkanite] Enabled ReSTIR from shaderpack settings");
                } else if ("false".equals(value)) {
                    config.enableRestir = false;
                    System.out.println("[Vulkanite] Disabled ReSTIR from shaderpack settings");
                }
            }
            
            // Check for ReSTIR reservoir dimensions
            if (variables.containsKey("restirReservoirWidth")) {
                try {
                    String value = variables.get("restirReservoirWidth");
                    int width = Integer.parseInt(value);
                    config.restirReservoirWidth = width;
                    System.out.println("[Vulkanite] Set ReSTIR reservoir width to " + width + " from shaderpack settings");
                } catch (NumberFormatException e) {
                    System.err.println("[Vulkanite] Invalid restirReservoirWidth value in shaderpack");
                }
            }
            
            if (variables.containsKey("restirReservoirHeight")) {
                try {
                    String value = variables.get("restirReservoirHeight");
                    int height = Integer.parseInt(value);
                    config.restirReservoirHeight = height;
                    System.out.println("[Vulkanite] Set ReSTIR reservoir height to " + height + " from shaderpack settings");
                } catch (NumberFormatException e) {
                    System.err.println("[Vulkanite] Invalid restirReservoirHeight value in shaderpack");
                }
            }
        } catch (Exception e) {
            System.err.println("[Vulkanite] Failed to access shaderpack variables: " + e.getMessage());
        }
    }
}