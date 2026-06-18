package me.cortex.vulkanite.acceleration.tlas;

import me.cortex.vulkanite.lib.base.VRef;
import me.cortex.vulkanite.lib.memory.VBuffer;

import java.util.List;

/**
 * Record representing a pending descriptor update for a geometry buffer.
 * Used to batch descriptor set updates during TLAS building.
 */
public record DescriptorUpdateJob(
        int element,
        VRef<VBuffer> geometryBuffer,
        List<Long> bufferOffsets,
        VRef<TLASSectionHolder> holder) {
}
