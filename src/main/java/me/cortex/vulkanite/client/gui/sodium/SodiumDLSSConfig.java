package me.cortex.vulkanite.client.gui.sodium;

import me.cortex.vulkanite.client.config.DLSSConfig;
import me.jellysquid.mods.sodium.client.gui.options.storage.OptionStorage;

/**
 * Storage backend for Sodium options that uses the DLSSConfig singleton.
 * All changes are immediately reflected across the application since
 * DLSSConfig uses a singleton pattern.
 */
public class SodiumDLSSConfig implements OptionStorage<Void> {

    public SodiumDLSSConfig() {
        // No need to store a reference - we use the singleton
    }

    @Override
    public void save() {
        // Save the singleton instance to file
        DLSSConfig.getInstance().save();
    }

    public DLSSConfig getConfig() {
        // Always return the singleton instance
        return DLSSConfig.getInstance();
    }

    @Override
    public Void getData() {
        return null;
    }
}
