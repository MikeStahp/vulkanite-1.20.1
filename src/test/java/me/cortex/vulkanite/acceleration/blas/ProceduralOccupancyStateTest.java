package me.cortex.vulkanite.acceleration.blas;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProceduralOccupancyStateTest {
    @Test
    void resubmitsIdenticalMaskUntilPriorBuildIsInstalled() {
        ProceduralOccupancyState state = new ProceduralOccupancyState();
        long[] mask = occupiedMask();

        assertEquals(ProceduralBLASDisposition.REPLACE, state.record(mask, 1L));
        assertEquals(ProceduralBLASDisposition.REPLACE, state.record(mask.clone(), 2L));

        state.markInstalled(2L, ProceduralBLASDisposition.REPLACE);

        assertEquals(ProceduralBLASDisposition.RETAIN, state.record(mask.clone(), 3L));
    }

    @Test
    void changedMaskRequestsReplacementAfterInstall() {
        ProceduralOccupancyState state = new ProceduralOccupancyState();
        long[] first = occupiedMask();
        long[] second = occupiedMask();
        second[1] = 1L;

        assertEquals(ProceduralBLASDisposition.REPLACE, state.record(first, 1L));
        state.markInstalled(1L, ProceduralBLASDisposition.REPLACE);

        assertEquals(ProceduralBLASDisposition.REPLACE, state.record(second, 2L));
    }

    @Test
    void emptyMaskClearsAndThenRetainsAfterInstall() {
        ProceduralOccupancyState state = new ProceduralOccupancyState();
        long[] empty = new long[64];

        assertEquals(ProceduralBLASDisposition.CLEAR, state.record(empty, 1L));
        state.markInstalled(1L, ProceduralBLASDisposition.CLEAR);

        assertEquals(ProceduralBLASDisposition.RETAIN, state.record(empty.clone(), 2L));
    }

    @Test
    void staleInstallDoesNotRetainPendingBuild() {
        ProceduralOccupancyState state = new ProceduralOccupancyState();
        long[] mask = occupiedMask();

        assertEquals(ProceduralBLASDisposition.REPLACE, state.record(mask, 10L));
        state.markInstalled(9L, ProceduralBLASDisposition.REPLACE);

        assertEquals(ProceduralBLASDisposition.REPLACE, state.record(mask.clone(), 11L));
    }

    private static long[] occupiedMask() {
        long[] mask = new long[64];
        mask[0] = 1L;
        return mask;
    }
}
