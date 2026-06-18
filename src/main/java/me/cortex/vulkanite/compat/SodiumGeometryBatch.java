package me.cortex.vulkanite.compat;

import java.util.List;

public record SodiumGeometryBatch(
        List<SodiumGeometry> geometries,
        long totalSizeBytes) {
    public SodiumGeometryBatch {
        geometries = List.copyOf(geometries);
    }

    public boolean isEmpty() {
        return geometries.isEmpty();
    }
}
