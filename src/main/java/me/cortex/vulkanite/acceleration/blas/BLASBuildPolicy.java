package me.cortex.vulkanite.acceleration.blas;

import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_COMPACTION_BIT_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_BUILD_BIT_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR;

public final class BLASBuildPolicy {
    private BLASBuildPolicy() {
    }

    public static boolean compactStaticTerrainBlas() {
        return Boolean.parseBoolean(System.getProperty("vulkanite.staticTerrainBlasCompaction",
                System.getProperty("vulkanite.blasCompaction", "false")));
    }

    public static int staticTerrainBuildFlags(boolean compact) {
        int flags = preferFastTraceForStaticTerrain()
                ? VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR
                : VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_BUILD_BIT_KHR;
        return compact ? flags | VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_COMPACTION_BIT_KHR : flags;
    }

    public static int dynamicEntityBuildFlags() {
        return VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_BUILD_BIT_KHR;
    }

    public static String describeStaticTerrainPolicy(boolean compact) {
        return (preferFastTraceForStaticTerrain() ? "fast-trace" : "fast-build")
                + (compact ? "+compaction" : "");
    }

    private static boolean preferFastTraceForStaticTerrain() {
        return Boolean.parseBoolean(System.getProperty("vulkanite.staticTerrainBlasFastTrace",
                System.getProperty("vulkanite.blasFastTrace", "true")));
    }
}
