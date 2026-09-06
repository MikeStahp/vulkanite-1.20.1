package me.cortex.vulkanite.compat;

import me.cortex.vulkanite.acceleration.HybridSbtLayout;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RaytracingShaderSourceNamesTest {
    private static final int RAY_GENERATION_PROGRAM_COUNT = 3;
    private static final List<String> HIT_STAGE_SUFFIXES = List.of(".rmiss", ".rchit", ".rahit", ".rint");

    @Test
    void preservesDiscoveredNamesWithoutMutatingTheInput() {
        List<String> discovered = new ArrayList<>(List.of("gbuffers_terrain.vsh", "composite.fsh"));

        List<String> names = RaytracingShaderSourceNames.appendTo(discovered);

        assertEquals(List.of("gbuffers_terrain.vsh", "composite.fsh"), discovered);
        assertEquals(discovered, names.subList(0, discovered.size()));
    }

    @Test
    void generatesTheExactNumberOfUniqueRaytracingNames() {
        List<String> names = RaytracingShaderSourceNames.appendTo(List.of());
        int expectedCount = RAY_GENERATION_PROGRAM_COUNT
                * (1 + HybridSbtLayout.REQUIRED_HIT_GROUP_COUNT * HIT_STAGE_SUFFIXES.size());

        assertEquals(expectedCount, names.size());
        assertEquals(expectedCount, new HashSet<>(names).size());
    }

    @Test
    void generatesEveryStageForEveryRequiredHitGroup() {
        Set<String> names = new HashSet<>(RaytracingShaderSourceNames.appendTo(List.of()));

        for (int rayIndex = 0; rayIndex < RAY_GENERATION_PROGRAM_COUNT; rayIndex++) {
            assertTrue(names.contains("ray" + rayIndex + ".rgen"));
            for (int hitGroup = 0; hitGroup < HybridSbtLayout.REQUIRED_HIT_GROUP_COUNT; hitGroup++) {
                for (String suffix : HIT_STAGE_SUFFIXES) {
                    assertTrue(names.contains("ray" + rayIndex + "_" + hitGroup + suffix));
                }
            }
        }
        assertTrue(names.contains("ray2_5.rint"));
    }
}
