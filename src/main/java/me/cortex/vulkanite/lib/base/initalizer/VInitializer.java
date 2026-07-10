package me.cortex.vulkanite.lib.base.initalizer;

import me.cortex.vulkanite.lib.base.VContext;
import me.cortex.vulkanite.lib.base.DeviceCapabilities;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.Struct;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.concurrent.atomic.AtomicInteger;

import static me.cortex.vulkanite.lib.other.VUtil._CHECK_;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.memUTF8;
import static org.lwjgl.vulkan.EXTDebugUtils.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.*;

public class VInitializer {
    private final VkInstance instance;
    private VkPhysicalDevice physicalDevice;
    private VkDevice device;
    private int queueCount;
    private int queueFamilyIndex = -1;
    private DeviceCapabilities capabilities;
    private long debugMessenger = 0;
    private VkDebugUtilsMessengerCallbackEXT debugCallback;
    private final AtomicInteger validationWarningCount = new AtomicInteger();
    private final AtomicInteger validationErrorCount = new AtomicInteger();

    public VInitializer(String appName, String engineName, int major, int minor, String[] extensions, String[] layers) {
        try (MemoryStack stack = stackPush()) {
            VkApplicationInfo appInfo = VkApplicationInfo.calloc(stack)
                    .sType$Default()
                    .apiVersion(VK_MAKE_VERSION(major, minor, 0))
                    .pApplicationName(memUTF8(appName))
                    .pEngineName(memUTF8(engineName));

            VkInstanceCreateInfo instanceCreateInfo = VkInstanceCreateInfo.calloc(stack)
                    .sType$Default()
                    .pApplicationInfo(appInfo)
                    .ppEnabledExtensionNames(
                            stack.pointers(Arrays.stream(extensions).map(stack::UTF8).toArray(ByteBuffer[]::new)))
                    .ppEnabledLayerNames(
                            stack.pointers(Arrays.stream(layers).map(stack::UTF8).toArray(ByteBuffer[]::new)));

            VkDebugUtilsMessengerCreateInfoEXT debugCreateInfo = null;
            if (Arrays.asList(extensions).contains(VK_EXT_DEBUG_UTILS_EXTENSION_NAME)) {
                debugCallback = VkDebugUtilsMessengerCallbackEXT.create(
                        (messageSeverity, messageTypes, pCallbackData, pUserData) -> {
                            boolean validationMessage =
                                    (messageTypes & VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT) != 0;
                            if (validationMessage
                                    && (messageSeverity & VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT) != 0) {
                                validationErrorCount.incrementAndGet();
                            } else if (validationMessage
                                    && (messageSeverity & VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT) != 0) {
                                validationWarningCount.incrementAndGet();
                            }

                            VkDebugUtilsMessengerCallbackDataEXT callbackData =
                                    VkDebugUtilsMessengerCallbackDataEXT.create(pCallbackData);
                            System.err.println("[Vulkanite/Validation]["
                                    + debugSeverityName(messageSeverity) + "]["
                                    + debugTypeNames(messageTypes) + "] "
                                    + callbackData.pMessageString());
                            return VK_FALSE;
                        });
                debugCreateInfo = VkDebugUtilsMessengerCreateInfoEXT.calloc(stack)
                        .sType$Default()
                        .messageSeverity(VK_DEBUG_UTILS_MESSAGE_SEVERITY_VERBOSE_BIT_EXT
                                | VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT
                                | VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT)
                        .messageType(VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT
                                | VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT
                                | VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT)
                        .pfnUserCallback(debugCallback);
            }

            PointerBuffer result = stack.pointers(0);
            _CHECK_(vkCreateInstance(instanceCreateInfo, null, result));

            instance = new VkInstance(result.get(0), instanceCreateInfo);

            if (debugCreateInfo != null) {
                var pDebugMessenger = stack.mallocLong(1);
                _CHECK_(vkCreateDebugUtilsMessengerEXT(instance, debugCreateInfo, null, pDebugMessenger));
                debugMessenger = pDebugMessenger.get(0);
            }
        }
    }

    private static String debugSeverityName(int severity) {
        if ((severity & VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT) != 0) {
            return "ERROR";
        }
        if ((severity & VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT) != 0) {
            return "WARNING";
        }
        if ((severity & VK_DEBUG_UTILS_MESSAGE_SEVERITY_INFO_BIT_EXT) != 0) {
            return "INFO";
        }
        return "VERBOSE";
    }

    private static String debugTypeNames(int types) {
        List<String> names = new ArrayList<>(3);
        if ((types & VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT) != 0) {
            names.add("GENERAL");
        }
        if ((types & VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT) != 0) {
            names.add("VALIDATION");
        }
        if ((types & VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT) != 0) {
            names.add("PERFORMANCE");
        }
        return String.join("|", names);
    }

    public void findPhysicalDevice() {
        try (MemoryStack stack = stackPush()) {
            PointerBuffer devices = getPhysicalDevices(stack);
            VkPhysicalDevice bestDevice = null;
            int bestScore = -1;

            for (int i = 0; i < devices.capacity(); i++) {
                VkPhysicalDevice dev = new VkPhysicalDevice(devices.get(i), instance);
                VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.calloc(stack);
                vkGetPhysicalDeviceProperties(dev, props);

                int score = 0;
                if (props.deviceType() == VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU) {
                    score += 1000;
                }
                // Check if NVIDIA (Vendor ID 0x10DE)
                if (props.vendorID() == 0x10DE) {
                    score += 100;
                }

                if (score > bestScore) {
                    bestScore = score;
                    bestDevice = dev;
                }
                System.out.println("Found device: " + props.deviceNameString() + " Score: " + score);
            }

            if (bestDevice != null) {
                physicalDevice = bestDevice;
                VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.calloc(stack);
                vkGetPhysicalDeviceProperties(physicalDevice, props);
                System.out.println("Selected device: " + props.deviceNameString());
            } else if (devices.capacity() > 0) {
                physicalDevice = new VkPhysicalDevice(devices.get(0), instance);
            }
        }
    }

    public VkPhysicalDevice getPhysicalDevice() {
        return physicalDevice;
    }

    public VkInstance getInstance() {
        return instance;
    }

    public void createDevice(List<String> extensions, List<String> layers, float[] queuePriorities,
            Consumer<VkPhysicalDeviceFeatures> deviceFeatures, List<Function<MemoryStack, Struct>> applicators,
            List<Consumer<Struct>> postApplicators) {
        var deviceExtensions = new HashSet<>(getDeviceExtensionStrings(physicalDevice));
        for (var extension : extensions) {
            if (!deviceExtensions.contains(extension)) {
                throw new IllegalStateException("Physical device is missing extension: " + extension);
            }
        }

        queueFamilyIndex = selectQueueFamily(queuePriorities.length);
        capabilities = DeviceCapabilities.detect(physicalDevice, deviceExtensions, queueFamilyIndex);

        try (MemoryStack stack = stackPush()) {

            queueCount = queuePriorities.length;
            var queueCreateInfos = VkDeviceQueueCreateInfo.calloc(1, stack)
                    .sType$Default()
                    .pQueuePriorities(stack.floats(queuePriorities))
                    .queueFamilyIndex(queueFamilyIndex);

            VkDeviceCreateInfo createInfo = VkDeviceCreateInfo.calloc(stack)
                    .sType$Default()
                    .ppEnabledExtensionNames(
                            stack.pointers(extensions.stream().map(stack::UTF8).toArray(ByteBuffer[]::new)))
                    .ppEnabledLayerNames(stack.pointers(layers.stream().map(stack::UTF8).toArray(ByteBuffer[]::new)))
                    .pQueueCreateInfos(queueCreateInfos);

            if (deviceFeatures != null) {
                var features = VkPhysicalDeviceFeatures.calloc(stack);
                deviceFeatures.accept(features);
                createInfo.pEnabledFeatures(features);
            } else {
                createInfo.pEnabledFeatures(null);
            }

            long chain = createInfo.address();
            var deviceProperties2 = VkPhysicalDeviceFeatures2.calloc(stack).sType$Default();
            if (postApplicators.size() != applicators.size()) {
                throw new IllegalStateException("Post applicators and applicators must be the same size");
            }
            for (int i = 0; i < applicators.size(); i++) {
                var applicator = applicators.get(i);
                Struct feature = applicator.apply(stack);
                deviceProperties2.pNext(feature.address());
                vkGetPhysicalDeviceFeatures2(physicalDevice, deviceProperties2);
                postApplicators.get(i).accept(feature);
                long next = feature.address();
                MemoryUtil.memPutAddress(chain + 8, next);
                chain = next;
            }

            PointerBuffer pDevice = stack.callocPointer(1);
            _CHECK_(vkCreateDevice(physicalDevice, createInfo, null, pDevice));
            device = new VkDevice(pDevice.get(0), physicalDevice, createInfo);
        }
    }

    private int selectQueueFamily(int requestedQueueCount) {
        if (requestedQueueCount <= 0) {
            throw new IllegalArgumentException("At least one Vulkan queue is required");
        }

        try (MemoryStack stack = stackPush()) {
            int[] count = new int[1];
            vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, count, null);
            VkQueueFamilyProperties.Buffer families = VkQueueFamilyProperties.calloc(count[0], stack);
            vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, count, families);

            int requiredFlags = VK_QUEUE_GRAPHICS_BIT | VK_QUEUE_COMPUTE_BIT | VK_QUEUE_TRANSFER_BIT;
            int bestFamily = -1;
            int bestQueueCount = -1;
            for (int family = 0; family < families.capacity(); family++) {
                VkQueueFamilyProperties properties = families.get(family);
                if ((properties.queueFlags() & requiredFlags) != requiredFlags
                        || properties.queueCount() < requestedQueueCount) {
                    continue;
                }
                if (properties.queueCount() > bestQueueCount) {
                    bestFamily = family;
                    bestQueueCount = properties.queueCount();
                }
            }

            if (bestFamily < 0) {
                throw new IllegalStateException("No graphics/compute/transfer queue family exposes "
                        + requestedQueueCount + " queues");
            }
            System.out.println("[Vulkanite] Selected Vulkan queue family " + bestFamily
                    + " with " + bestQueueCount + " queues");
            return bestFamily;
        }
    }

    private static VkLayerProperties.Buffer getInstanceLayers(MemoryStack stack) {
        int[] res = new int[1];
        _CHECK_(vkEnumerateInstanceLayerProperties(res, null));
        VkLayerProperties.Buffer layerProperties = VkLayerProperties.calloc(res[0], stack);
        _CHECK_(vkEnumerateInstanceLayerProperties(res, layerProperties));
        if (res[0] != layerProperties.capacity())
            throw new IllegalStateException();
        return layerProperties;
    }

    public static boolean isInstanceLayerAvailable(String requestedLayer) {
        try (MemoryStack stack = stackPush()) {
            for (VkLayerProperties layer : getInstanceLayers(stack)) {
                if (requestedLayer.equals(layer.layerNameString())) {
                    return true;
                }
            }
            return false;
        }
    }

    private static VkExtensionProperties.Buffer getInstanceExtensions(MemoryStack stack) {
        int[] res = new int[1];
        _CHECK_(vkEnumerateInstanceExtensionProperties((String) null, res, null));
        VkExtensionProperties.Buffer extensionProperties = VkExtensionProperties.calloc(res[0], stack);
        _CHECK_(vkEnumerateInstanceExtensionProperties((String) null, res, extensionProperties));
        if (res[0] != extensionProperties.capacity())
            throw new IllegalStateException();
        return extensionProperties;
    }

    public static boolean isInstanceExtensionAvailable(String requestedExtension) {
        try (MemoryStack stack = stackPush()) {
            for (VkExtensionProperties extension : getInstanceExtensions(stack)) {
                if (requestedExtension.equals(extension.extensionNameString())) {
                    return true;
                }
            }
            return false;
        }
    }

    private PointerBuffer getPhysicalDevices(MemoryStack stack) {
        int[] res = new int[1];
        _CHECK_(vkEnumeratePhysicalDevices(instance, res, null));
        PointerBuffer devices = stack.callocPointer(res[0]);
        _CHECK_(vkEnumeratePhysicalDevices(instance, res, devices));
        if (res[0] != devices.capacity())
            throw new IllegalStateException();
        return devices;
    }

    public List<String> getDeviceExtensionStrings(VkPhysicalDevice device) {
        List<String> extensions = new ArrayList<>();
        try (var stack = stackPush()) {
            var eb = getDeviceExtensions(stack, device);
            for (var extension : eb) {
                extensions.add(extension.extensionNameString());
            }
        }
        return extensions;
    }

    private VkExtensionProperties.Buffer getDeviceExtensions(MemoryStack stack, long device) {
        return getDeviceExtensions(stack, new VkPhysicalDevice(device, instance));
    }

    private VkExtensionProperties.Buffer getDeviceExtensions(MemoryStack stack, VkPhysicalDevice device) {
        int[] res = new int[1];
        _CHECK_(vkEnumerateDeviceExtensionProperties(device, (String) null, res, null));
        VkExtensionProperties.Buffer extensionProperties = VkExtensionProperties.calloc(res[0], stack);
        _CHECK_(vkEnumerateDeviceExtensionProperties(device, (String) null, res, extensionProperties));
        if (res[0] != extensionProperties.capacity())
            throw new IllegalStateException();
        return extensionProperties;
    }

    public VContext createContext() {
        // TODO:FIXME: DONT HARDCODE THE FACT IT HAS DEVICE ADDRESSES
        if (queueFamilyIndex < 0 || capabilities == null) {
            throw new IllegalStateException("Vulkan device capabilities were not initialized");
        }
        return new VContext(device, physicalDevice, instance, queueCount, queueFamilyIndex,
                true, debugMessenger != 0, capabilities, debugCallback,
                validationWarningCount, validationErrorCount);
    }
}
