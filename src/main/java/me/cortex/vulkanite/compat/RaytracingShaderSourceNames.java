package me.cortex.vulkanite.compat;

import me.cortex.vulkanite.acceleration.HybridSbtLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Adds Vulkanite ray-tracing shader entry points to Iris source discovery. */
public final class RaytracingShaderSourceNames {
    private static final int RAY_GENERATION_PROGRAM_COUNT = 3;
    private static final List<String> HIT_STAGE_SUFFIXES = List.of(".rmiss", ".rchit", ".rahit", ".rint");

    private RaytracingShaderSourceNames() {
    }

    /**
     * Returns a new list containing the discovered names followed by every
     * Vulkanite ray-generation and hit-group stage name.
     */
    public static List<String> appendTo(List<String> discoveredNames) {
        Objects.requireNonNull(discoveredNames, "discoveredNames");

        int generatedNameCount = RAY_GENERATION_PROGRAM_COUNT
                * (1 + HybridSbtLayout.REQUIRED_HIT_GROUP_COUNT * HIT_STAGE_SUFFIXES.size());
        List<String> names = new ArrayList<>(discoveredNames.size() + generatedNameCount);
        names.addAll(discoveredNames);
        for (int rayIndex = 0; rayIndex < RAY_GENERATION_PROGRAM_COUNT; rayIndex++) {
            names.add("ray" + rayIndex + ".rgen");
            for (int hitGroup = 0; hitGroup < HybridSbtLayout.REQUIRED_HIT_GROUP_COUNT; hitGroup++) {
                for (String suffix : HIT_STAGE_SUFFIXES) {
                    names.add("ray" + rayIndex + "_" + hitGroup + suffix);
                }
            }
        }
        return names;
    }
}
