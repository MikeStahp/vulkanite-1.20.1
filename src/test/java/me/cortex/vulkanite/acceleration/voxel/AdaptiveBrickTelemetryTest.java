package me.cortex.vulkanite.acceleration.voxel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdaptiveBrickTelemetryTest {
    @Test
    void recordsCandidateAndSelectedTotalsWithoutRecomputation() {
        long[] mask = occupiedMask();
        var policy = new AdaptiveBrickSizePolicy(
                new AdaptiveBrickSizePolicy.Weights(24, 1, 0.01, 0.1));
        var selection = policy.select(mask, 0);
        var telemetry = new AdaptiveBrickTelemetry();

        telemetry.record(selection, 0);
        var snapshot = telemetry.snapshot();

        assertEquals(1, snapshot.evaluations());
        assertEquals(1, snapshot.initialSelections());
        for (int size : AdaptiveBrickSizePolicy.SUPPORTED_SIZES) {
            var candidate = selection.estimateForSize(size);
            var totals = snapshot.forSize(size);
            assertEquals(1, totals.candidateEvaluations());
            assertEquals(candidate.occupiedBrickCount(), totals.candidateOccupiedBricks());
            assertEquals(candidate.estimatedDdaSteps(), totals.candidateEstimatedDdaSteps());
            assertEquals(candidate.estimatedBlasBytes(), totals.candidateEstimatedBlasBytes());
            assertEquals(size == selection.brickSize() ? 1 : 0, totals.selections());
        }
        assertThrows(UnsupportedOperationException.class, () -> snapshot.sizes().clear());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.switchMatrix().get(0).clear());
        assertTrue(snapshot.structuredSummary().contains("evaluations=1"));
        assertTrue(snapshot.structuredSummary().contains("size4={"));
        assertTrue(snapshot.structuredSummary().contains("switchMatrix=["));
    }

    @Test
    void recordsStableHysteresisAndCrossSizeSwitches() {
        long[] mask = occupiedMask();
        var telemetry = new AdaptiveBrickTelemetry();

        var stickyPolicy = new AdaptiveBrickSizePolicy(
                new AdaptiveBrickSizePolicy.Weights(1, 0, 0, 100));
        var retained = stickyPolicy.select(mask, 16);
        telemetry.record(retained, 16);

        var switchingPolicy = new AdaptiveBrickSizePolicy(
                new AdaptiveBrickSizePolicy.Weights(1, 1, 0, 0));
        var initial = switchingPolicy.select(mask, 0);
        var stable = switchingPolicy.select(mask, initial.brickSize());
        telemetry.record(stable, stable.brickSize());

        var switched = switchingPolicy.select(mask, 16);
        telemetry.record(switched, 16);

        var snapshot = telemetry.snapshot();
        assertEquals(3, snapshot.evaluations());
        assertEquals(2, snapshot.stableSelections());
        assertEquals(1, snapshot.hysteresisRetentions());
        assertEquals(1, snapshot.switches());
        assertEquals(1, snapshot.switchCount(16, switched.brickSize()));
        assertEquals(0, snapshot.switchCount(16, 16));
        assertEquals(1, snapshot.forSize(16).hysteresisRetentions());
    }

    @Test
    void snapshotAndResetStartsANewWindow() {
        var policy = new AdaptiveBrickSizePolicy(
                new AdaptiveBrickSizePolicy.Weights(1, 1, 0, 0));
        var telemetry = new AdaptiveBrickTelemetry();
        telemetry.record(policy.select(occupiedMask(), 0), 0);

        assertEquals(1, telemetry.snapshotAndReset().evaluations());
        assertEquals(0, telemetry.snapshot().evaluations());
        for (int size : AdaptiveBrickSizePolicy.SUPPORTED_SIZES) {
            assertEquals(0, telemetry.snapshot().forSize(size).candidateEvaluations());
        }
    }

    @Test
    void rejectsInvalidPreviousSizesWithoutMutatingTotals() {
        var policy = new AdaptiveBrickSizePolicy(
                new AdaptiveBrickSizePolicy.Weights(1, 1, 0, 0));
        var telemetry = new AdaptiveBrickTelemetry();
        var selection = policy.select(occupiedMask(), 0);

        assertThrows(IllegalArgumentException.class, () -> telemetry.record(selection, 6));
        assertEquals(0, telemetry.snapshot().evaluations());
    }

    private static long[] occupiedMask() {
        long[] mask = new long[64];
        mask[0] = 1L;
        return mask;
    }
}
