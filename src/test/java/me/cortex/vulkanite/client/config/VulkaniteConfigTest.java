package me.cortex.vulkanite.client.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VulkaniteConfigTest {
    @Test
    void configuredValueIsUsedWithoutRuntimeOverride() {
        assertTrue(VulkaniteConfig.resolveVulkanValidationEnabled(true, null, null));
        assertFalse(VulkaniteConfig.resolveVulkanValidationEnabled(false, null, null));
    }

    @Test
    void environmentOverridesConfiguredValue() {
        assertTrue(VulkaniteConfig.resolveVulkanValidationEnabled(false, null, "true"));
        assertFalse(VulkaniteConfig.resolveVulkanValidationEnabled(true, null, "FALSE"));
    }

    @Test
    void systemPropertyHasHighestPrecedence() {
        assertTrue(VulkaniteConfig.resolveVulkanValidationEnabled(false, "TRUE", "false"));
        assertFalse(VulkaniteConfig.resolveVulkanValidationEnabled(true, "false", "true"));
    }

    @Test
    void malformedRuntimeOverrideFailsFast() {
        assertThrows(IllegalArgumentException.class,
                () -> VulkaniteConfig.resolveVulkanValidationEnabled(false, "yes", null));
        assertThrows(IllegalArgumentException.class,
                () -> VulkaniteConfig.resolveVulkanValidationEnabled(false, null, "1"));
    }
}
