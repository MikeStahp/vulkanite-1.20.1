package me.cortex.vulkanite.acceleration.voxel;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AdaptiveBrickSizePolicyTest {
    @Test
    void selectionIsDeterministic() {
        long[] mask = new long[64];
        mask[0] = 0x0101010101010101L;
        var policy = new AdaptiveBrickSizePolicy(new AdaptiveBrickSizePolicy.Weights(24, 1, 0.01, 0.1));
        var selection = policy.select(mask, 0);
        assertEquals(selection, policy.select(mask.clone(), 0));
        assertEquals(List.of(4, 8, 16),
                selection.candidates().stream().map(AdaptiveBrickSizePolicy.Estimate::brickSize).toList());
        assertSame(selection.estimate(), selection.estimateForSize(selection.brickSize()));
        assertThrows(UnsupportedOperationException.class,
                () -> selection.candidates().add(selection.estimate()));
    }

    @Test
    void estimatesAllSupportedSizes() {
        long[] full = new long[64];
        java.util.Arrays.fill(full, -1L);
        var policy = new AdaptiveBrickSizePolicy(new AdaptiveBrickSizePolicy.Weights(1, 1, 1, 0));
        for (int size : AdaptiveBrickSizePolicy.SUPPORTED_SIZES) {
            var estimate = policy.estimate(full, size);
            assertEquals(size, estimate.brickSize());
            assertTrue(estimate.occupiedBrickCount() > 0);
            assertTrue(estimate.estimatedBlasBytes() > 0);
        }
    }

    @Test
    void hysteresisRetainsNearlyEquivalentPreviousChoice() {
        long[] mask = new long[64];
        mask[0] = 1L;
        var sticky = new AdaptiveBrickSizePolicy(new AdaptiveBrickSizePolicy.Weights(1, 0, 0, 100));
        var selection = sticky.select(mask, 16);
        assertEquals(16, selection.brickSize());
        assertTrue(selection.retainedByHysteresis());
    }

    @Test
    void selectionDefensivelyCopiesCandidateEstimates() {
        long[] mask = new long[64];
        mask[0] = 1L;
        var policy = new AdaptiveBrickSizePolicy(new AdaptiveBrickSizePolicy.Weights(1, 1, 1, 0));
        var original = policy.select(mask, 0);
        var candidates = new ArrayList<>(original.candidates());

        var copied = new AdaptiveBrickSizePolicy.Selection(
                original.brickSize(), original.estimate(),
                original.retainedByHysteresis(), candidates);
        candidates.clear();

        assertEquals(3, copied.candidates().size());
    }

    @Test
    void rejectsNonFiniteAndNegativeWeights() {
        assertThrows(IllegalArgumentException.class,
                () -> new AdaptiveBrickSizePolicy.Weights(Double.NaN, 1, 1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new AdaptiveBrickSizePolicy.Weights(1, Double.POSITIVE_INFINITY, 1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new AdaptiveBrickSizePolicy.Weights(1, 1, -0.01, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new AdaptiveBrickSizePolicy.Weights(1, 1, 1, Double.NEGATIVE_INFINITY));
    }
}
