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
    public boolean enableRestir = true;
    public boolean enableDirtRt = false;
    public int restirReservoirWidth = 1280;
    public int restirReservoirHeight = 720;
    
    // Other settings can be added here
    
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
            
            enableRestir = Boolean.parseBoolean(props.getProperty("enableRestir", "true"));
            enableDirtRt = Boolean.parseBoolean(props.getProperty("enableDirtRt", "false"));
            restirReservoirWidth = Integer.parseInt(props.getProperty("restirReservoirWidth", "1920"));
            restirReservoirHeight = Integer.parseInt(props.getProperty("restirReservoirHeight", "1080"));
            
        } catch (IOException e) {
            System.err.println("[Vulkanite] Failed to load config: " + e.getMessage());
            saveConfig(); // Save default config
        }
    }
    
    private void saveConfig() {
        File configFile = new File(FabricLoader.getInstance().getConfigDir().toFile(), CONFIG_FILE_NAME);
        
        Properties props = new Properties();
        props.setProperty("enableRestir", String.valueOf(enableRestir));
        props.setProperty("enableDirtRt", String.valueOf(enableDirtRt));
        props.setProperty("restirReservoirWidth", String.valueOf(restirReservoirWidth));
        props.setProperty("restirReservoirHeight", String.valueOf(restirReservoirHeight));
        
        try (FileWriter writer = new FileWriter(configFile)) {
            props.store(writer, "Vulkanite Configuration");
        } catch (IOException e) {
            System.err.println("[Vulkanite] Failed to save config: " + e.getMessage());
        }
    }
}