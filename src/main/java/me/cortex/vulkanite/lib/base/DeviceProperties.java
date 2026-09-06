package me.cortex.vulkanite.lib.base;

import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties2;
import org.lwjgl.vulkan.VkPhysicalDeviceRayTracingPipelinePropertiesKHR;
import org.lwjgl.vulkan.VkQueueFamilyProperties;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceProperties2;
import static org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceQueueFamilyProperties;

public class DeviceProperties {
    //Allocates with a calloc, TODO: add a destroy function for cleanup

    public final VkPhysicalDeviceRayTracingPipelinePropertiesKHR rtPipelineProperties;
    public final float timestampPeriodNanos;
    public final int timestampValidBits;

    public DeviceProperties(VkDevice device, int queueFamilyIndex) {
        rtPipelineProperties = VkPhysicalDeviceRayTracingPipelinePropertiesKHR.calloc().sType$Default();
        try (var stack = stackPush()) {
            VkPhysicalDeviceProperties2 properties = VkPhysicalDeviceProperties2.calloc(stack)
                    .sType$Default()
                    .pNext(rtPipelineProperties);
            vkGetPhysicalDeviceProperties2(device.getPhysicalDevice(), properties);
            timestampPeriodNanos = properties.properties().limits().timestampPeriod();

            int[] familyCount = new int[1];
            vkGetPhysicalDeviceQueueFamilyProperties(device.getPhysicalDevice(), familyCount, null);
            VkQueueFamilyProperties.Buffer families = VkQueueFamilyProperties.calloc(familyCount[0], stack);
            vkGetPhysicalDeviceQueueFamilyProperties(device.getPhysicalDevice(), familyCount, families);
            timestampValidBits = queueFamilyIndex >= 0 && queueFamilyIndex < families.capacity()
                    ? families.get(queueFamilyIndex).timestampValidBits()
                    : 0;
        }
    }
}
