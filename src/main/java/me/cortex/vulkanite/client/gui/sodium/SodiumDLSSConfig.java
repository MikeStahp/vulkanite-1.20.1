package me.cortex.vulkanite.client.gui.sodium;

import me.cortex.vulkanite.client.config.DLSSConfig;
import me.jellysquid.mods.sodium.client.gui.options.storage.OptionStorage;

public class SodiumDLSSConfig implements OptionStorage<Void> {
    private final DLSSConfig config;

    public SodiumDLSSConfig() {
        this.config = DLSSConfig.load();
    }

    @Override
    public void save() {
        config.save();
    }

    public DLSSConfig getConfig() {
        return config;
    }

    @Override
    public Void getData() {
        return null;
    }
}
