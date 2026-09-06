package me.cortex.vulkanite.client.rendering;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityCaptureTest {
    @Test
    void excludesOnlyAwakeFocusedEntityInFirstPerson() {
        assertTrue(EntityCapture.shouldExcludeFocusedCameraEntity(true, false, false));

        assertFalse(EntityCapture.shouldExcludeFocusedCameraEntity(false, false, false));
        assertFalse(EntityCapture.shouldExcludeFocusedCameraEntity(true, true, false));
        assertFalse(EntityCapture.shouldExcludeFocusedCameraEntity(true, false, true));
        assertFalse(EntityCapture.shouldExcludeFocusedCameraEntity(false, true, true));
    }
}
