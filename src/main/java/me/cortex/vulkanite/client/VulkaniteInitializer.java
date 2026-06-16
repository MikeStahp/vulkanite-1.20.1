package me.cortex.vulkanite.client;

import net.fabricmc.api.ClientModInitializer;

/**
 * Client-side mod initializer for Vulkanite.
 * Registers key bindings and other client-side event handlers.
 */
public class VulkaniteInitializer implements ClientModInitializer {
    
    @Override
    public void onInitializeClient() {
        // Initialize debug key handler
        DebugKeyHandler.init();
    }
}
