package me.cortex.vulkanite.lib.other;

import static org.lwjgl.vulkan.VK10.VK_ERROR_DEVICE_LOST;

public class DeviceLostException extends VulkanException {
    public DeviceLostException(String message) {
        super(message, VK_ERROR_DEVICE_LOST);
    }
    
    public DeviceLostException(String message, Throwable cause) {
        super(message, VK_ERROR_DEVICE_LOST, cause);
    }
}