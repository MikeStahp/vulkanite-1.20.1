package me.cortex.vulkanite.acceleration.blas;

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

    public ProceduralBLASDisposition record(long[] newOccupancy, long newBuildTime) {
        Objects.requireNonNull(newOccupancy, "newOccupancy");
        boolean unchanged = occupancy != null && Arrays.equals(occupancy, newOccupancy);
        if (unchanged && installed) {
            return ProceduralBLASDisposition.RETAIN;
        }

        occupancy = newOccupancy.clone();
        buildTime = newBuildTime;
        installed = false;
        return isEmpty(newOccupancy)
                ? ProceduralBLASDisposition.CLEAR
                : ProceduralBLASDisposition.REPLACE;
    }

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
