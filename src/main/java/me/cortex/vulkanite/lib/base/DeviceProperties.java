package me.cortex.vulkanite.lib.base;

import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties2;
import org.lwjgl.vulkan.VkPhysicalDeviceRayTracingPipelinePropertiesKHR;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceProperties2;

public class DeviceProperties {
    //Allocates with a calloc, TODO: add a destroy function for cleanup

    public final VkPhysicalDeviceRayTracingPipelinePropertiesKHR rtPipelineProperties;
    public final float timestampPeriodNanos;

    public DeviceProperties(VkDevice device) {
        rtPipelineProperties = VkPhysicalDeviceRayTracingPipelinePropertiesKHR.calloc().sType$Default();
        try (var stack = stackPush()) {
            VkPhysicalDeviceProperties2 properties = VkPhysicalDeviceProperties2.calloc(stack)
                    .sType$Default()
                    .pNext(rtPipelineProperties);
            vkGetPhysicalDeviceProperties2(device.getPhysicalDevice(), properties);
            timestampPeriodNanos = properties.properties().limits().timestampPeriod();
        }
    }
}
