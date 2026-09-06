package me.cortex.vulkanite.lib.base;

import me.cortex.vulkanite.lib.cmd.CommandManager;
import me.cortex.vulkanite.lib.other.sync.SyncManager;
import me.cortex.vulkanite.lib.memory.MemoryManager;
import org.lwjgl.vulkan.VkDebugUtilsObjectNameInfoEXT;
import org.lwjgl.vulkan.VkDebugUtilsMessengerCallbackEXT;
import org.lwjgl.vulkan.VkDevice;

import java.util.concurrent.atomic.AtomicInteger;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.memUTF8;
import static org.lwjgl.vulkan.EXTDebugUtils.vkSetDebugUtilsObjectNameEXT;
import static org.lwjgl.vulkan.VK10.VK_OBJECT_TYPE_UNKNOWN;

public class VContext {
    public final VkDevice device;
    public final org.lwjgl.vulkan.VkPhysicalDevice physicalDevice;
    public final org.lwjgl.vulkan.VkInstance instance;

    public final MemoryManager memory;
    public final SyncManager sync;
    public final CommandManager cmd;
    public final DeviceProperties properties;
    public final DeviceCapabilities capabilities;
    public final int queueFamilyIndex;
    public final boolean hasDebugUtils;
    public final boolean validationEnabled;
    // Keeps the native callback alive for the lifetime of the Vulkan context.
    @SuppressWarnings("unused")
    private final VkDebugUtilsMessengerCallbackEXT debugCallback;
    private final AtomicInteger validationWarningCount;
    private final AtomicInteger validationErrorCount;

    public VContext(VkDevice device, org.lwjgl.vulkan.VkPhysicalDevice physicalDevice,
            org.lwjgl.vulkan.VkInstance instance, int queueCount, int queueFamilyIndex,
            boolean hasDeviceAddresses, boolean hasDebugUtils, DeviceCapabilities capabilities,
            VkDebugUtilsMessengerCallbackEXT debugCallback, AtomicInteger validationWarningCount,
            AtomicInteger validationErrorCount) {
        this.device = device;
        this.physicalDevice = physicalDevice;
        this.instance = instance;
        this.queueFamilyIndex = queueFamilyIndex;
        this.capabilities = capabilities;
        memory = new MemoryManager(device, hasDeviceAddresses);
        sync = new SyncManager(device);
        cmd = new CommandManager(device, queueCount, queueFamilyIndex);
        properties = new DeviceProperties(device, queueFamilyIndex);
        this.hasDebugUtils = hasDebugUtils;
        this.validationEnabled = debugCallback != null;
        this.debugCallback = debugCallback;
        this.validationWarningCount = validationWarningCount;
        this.validationErrorCount = validationErrorCount;
    }

    public void setDebugUtilsObjectName(long handle, int objectType, String name) {
        if (hasDebugUtils) {
            try (var stack = stackPush()) {
                vkSetDebugUtilsObjectNameEXT(device, VkDebugUtilsObjectNameInfoEXT.calloc(stack)
                        .sType$Default()
                        .objectType(objectType)
                        .objectHandle(handle)
                        .pObjectName(memUTF8(name)));
            }
        }
    }

    public int validationWarningCount() {
        return validationWarningCount.get();
    }

    public int validationErrorCount() {
        return validationErrorCount.get();
    }
}
