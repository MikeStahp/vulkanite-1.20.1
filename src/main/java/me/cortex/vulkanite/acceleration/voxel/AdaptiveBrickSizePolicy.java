package me.cortex.vulkanite.acceleration.voxel;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Deterministic cost model for choosing a section's voxel-brick size. */
public final class AdaptiveBrickSizePolicy {
    public static final int[] SUPPORTED_SIZES = {4, 8, 16};

    public record Weights(double traversal, double dda, double memory, double switchHysteresis) {
        public Weights {
            if (!finiteNonNegative(traversal)
                    || !finiteNonNegative(dda)
                    || !finiteNonNegative(memory)
                    || !finiteNonNegative(switchHysteresis)) {
                throw new IllegalArgumentException(
                        "Adaptive brick weights must be finite and non-negative");
            }
        }

        public static Weights fromSystemProperties() {
            return new Weights(property("vulkanite.brickTraversalWeight", 24.0),
                    property("vulkanite.brickDdaWeight", 1.0),
                    property("vulkanite.brickMemoryWeight", 0.015625),
                    property("vulkanite.brickSwitchHysteresis", 0.10));
        }
    }

    public record Estimate(int brickSize, int occupiedBrickCount, long estimatedDdaSteps,
            long estimatedBlasBytes, double cost) {}

    /**
     * One adaptive decision and the ordered candidate estimates used to make it.
     * Candidate order always matches {@link #SUPPORTED_SIZES}.
     */
    public record Selection(
            int brickSize,
            Estimate estimate,
            boolean retainedByHysteresis,
            List<Estimate> candidates) {
        public Selection {
            Objects.requireNonNull(estimate, "estimate");
            candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
            if (candidates.size() != SUPPORTED_SIZES.length) {
                throw new IllegalArgumentException(
                        "Adaptive selection must expose one estimate per supported brick size");
            }
            for (int i = 0; i < SUPPORTED_SIZES.length; i++) {
                Estimate candidate = Objects.requireNonNull(candidates.get(i), "candidate");
                if (candidate.brickSize() != SUPPORTED_SIZES[i]) {
                    throw new IllegalArgumentException(
                            "Adaptive candidate estimates must be ordered as 4, 8, 16");
                }
            }
            int selectedIndex = supportedSizeIndex(brickSize);
            if (!estimate.equals(candidates.get(selectedIndex))) {
                throw new IllegalArgumentException(
                        "Selected estimate must match the selected candidate");
            }
        }

        public Estimate estimateForSize(int brickSize) {
            return candidates.get(supportedSizeIndex(brickSize));
        }
    }

    private final Weights weights;
    public AdaptiveBrickSizePolicy(Weights weights) { this.weights = Objects.requireNonNull(weights); }

    public Selection select(long[] occupancy, int previousSize) {
        List<Estimate> candidates = new ArrayList<>(SUPPORTED_SIZES.length);
        Estimate best = null;
        Estimate previous = null;
        for (int size : SUPPORTED_SIZES) {
            Estimate estimate = estimate(occupancy, size);
            candidates.add(estimate);
            if (size == previousSize) previous = estimate;
            if (best == null || estimate.cost() < best.cost()
                    || (estimate.cost() == best.cost() && size < best.brickSize())) best = estimate;
        }
        if (previous != null && previous.brickSize() != best.brickSize()
                && previous.cost() <= best.cost() * (1.0 + weights.switchHysteresis())) {
            return new Selection(previous.brickSize(), previous, true, candidates);
        }
        return new Selection(best.brickSize(), best, false, candidates);
    }

    public Estimate estimate(long[] occupancy, int brickSize) {
        VoxelBrickGeometry geometry = VoxelBrickGeometry.fromOpacityMask(occupancy, brickSize);
        long ddaSteps = 0;
        for (VoxelBrickGeometry.Brick brick : geometry.bricks()) {
            int volume = brickSize * brickSize * brickSize;
            ddaSteps += Math.max(1, volume - brick.occupiedCellCount() + brickSize);
        }
        long bytes = geometry.aabbBytes() + geometry.packedBytes();
        double cost = geometry.brickCount() * weights.traversal()
                + ddaSteps * weights.dda() + bytes * weights.memory();
        return new Estimate(brickSize, geometry.brickCount(), ddaSteps, bytes, cost);
    }

    public static int supportedSizeIndex(int brickSize) {
        return switch (brickSize) {
            case 4 -> 0;
            case 8 -> 1;
            case 16 -> 2;
            default -> throw new IllegalArgumentException(
                    "Voxel brick size must be 4, 8, or 16: " + brickSize);
        };
    }

    private static boolean finiteNonNegative(double value) {
        return Double.isFinite(value) && value >= 0.0;
    }

    private static double property(String name, double fallback) {
        try {
            double value = Double.parseDouble(System.getProperty(name, Double.toString(fallback)));
            return Double.isFinite(value) && value >= 0 ? value : fallback;
        } catch (NumberFormatException ignored) { return fallback; }
    }
}
