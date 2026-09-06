package me.cortex.vulkanite.acceleration.blas;

import me.cortex.vulkanite.acceleration.voxel.ProceduralMaterialPayload;

import java.util.Arrays;
import java.util.Objects;

/**
 * Tracks the last submitted and installed opaque occupancy for one section.
 *
 * <p>An identical update is retained only after the preceding update has been
 * published. While a build is pending, identical updates are submitted again
 * so a newer Sodium result cannot supersede the only result that owns the
 * required procedural resource.</p>
 */
final class ProceduralOccupancyState {
    private long[] occupancy;
    private long buildTime;
    private boolean installed;
    private int brickSize;
    private ProceduralMaterialPayload materialPayload = ProceduralMaterialPayload.empty();

    public ProceduralBLASDisposition record(long[] newOccupancy, long newBuildTime, int newBrickSize) {
        return record(newOccupancy, newBuildTime, newBrickSize, ProceduralMaterialPayload.empty());
    }

    public ProceduralBLASDisposition record(
            long[] newOccupancy,
            long newBuildTime,
            int newBrickSize,
            ProceduralMaterialPayload newMaterialPayload) {
        Objects.requireNonNull(newOccupancy, "newOccupancy");
        Objects.requireNonNull(newMaterialPayload, "newMaterialPayload");
        boolean unchanged = occupancy != null && brickSize == newBrickSize
                && Arrays.equals(occupancy, newOccupancy)
                && materialPayload.equals(newMaterialPayload);
        if (unchanged && installed) {
            return ProceduralBLASDisposition.RETAIN;
        }

        occupancy = newOccupancy.clone();
        buildTime = newBuildTime;
        brickSize = newBrickSize;
        materialPayload = newMaterialPayload;
        installed = false;
        return isEmpty(newOccupancy)
                ? ProceduralBLASDisposition.CLEAR
                : ProceduralBLASDisposition.REPLACE;
    }

    public int brickSize() { return brickSize; }

    public void markInstalled(long installedBuildTime, ProceduralBLASDisposition disposition) {
        if (disposition != ProceduralBLASDisposition.RETAIN && buildTime == installedBuildTime) {
            installed = true;
        }
    }

    private static boolean isEmpty(long[] occupancy) {
        for (long word : occupancy) {
            if (word != 0L) {
                return false;
            }
        }
        return true;
    }
}
