package me.cortex.vulkanite.acceleration.voxel;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Thread-safe aggregate telemetry for adaptive voxel-brick decisions.
 *
 * <p>This class records CPU-side estimates and policy behavior only. Vulkan
 * acceleration-structure sizes, GPU build timings, and shader DDA counters are
 * intentionally tracked by their owning subsystems.</p>
 */
public final class AdaptiveBrickTelemetry {
    private static final int SIZE_COUNT = AdaptiveBrickSizePolicy.SUPPORTED_SIZES.length;

    private final MutableSizeTotals[] sizeTotals = new MutableSizeTotals[SIZE_COUNT];
    private final long[][] switchCounts = new long[SIZE_COUNT][SIZE_COUNT];
    private long evaluations;
    private long initialSelections;

    public AdaptiveBrickTelemetry() {
        for (int i = 0; i < SIZE_COUNT; i++) {
            sizeTotals[i] = new MutableSizeTotals(AdaptiveBrickSizePolicy.SUPPORTED_SIZES[i]);
        }
    }

    /** Records one policy result without recalculating any candidate geometry. */
    public synchronized void record(
            AdaptiveBrickSizePolicy.Selection selection,
            int previousBrickSize) {
        Objects.requireNonNull(selection, "selection");
        int selectedIndex = AdaptiveBrickSizePolicy.supportedSizeIndex(selection.brickSize());
        int previousIndex = previousBrickSize == 0
                ? -1
                : AdaptiveBrickSizePolicy.supportedSizeIndex(previousBrickSize);
        if (selection.retainedByHysteresis() && previousIndex < 0) {
            throw new IllegalArgumentException(
                    "Hysteresis cannot retain a missing previous brick size");
        }
        if (selection.retainedByHysteresis() && previousIndex != selectedIndex) {
            throw new IllegalArgumentException(
                    "A hysteresis-retained selection must equal the previous brick size");
        }

        evaluations++;
        for (AdaptiveBrickSizePolicy.Estimate candidate : selection.candidates()) {
            sizeTotals[AdaptiveBrickSizePolicy.supportedSizeIndex(candidate.brickSize())]
                    .recordCandidate(candidate);
        }

        MutableSizeTotals selected = sizeTotals[selectedIndex];
        selected.recordSelection(selection.estimate());

        if (previousIndex < 0) {
            initialSelections++;
        } else if (previousIndex == selectedIndex) {
            selected.stableSelections++;
            if (selection.retainedByHysteresis()) {
                selected.hysteresisRetentions++;
            }
        } else {
            switchCounts[previousIndex][selectedIndex]++;
        }
    }

    public synchronized Snapshot snapshot() {
        return createSnapshot();
    }

    public synchronized Snapshot snapshotAndReset() {
        Snapshot snapshot = createSnapshot();
        resetInternal();
        return snapshot;
    }

    public synchronized void reset() {
        resetInternal();
    }

    private Snapshot createSnapshot() {
        List<SizeTotals> sizes = new ArrayList<>(SIZE_COUNT);
        for (MutableSizeTotals totals : sizeTotals) {
            sizes.add(totals.snapshot());
        }

        List<List<Long>> switches = new ArrayList<>(SIZE_COUNT);
        for (long[] row : switchCounts) {
            List<Long> immutableRow = new ArrayList<>(SIZE_COUNT);
            for (long value : row) {
                immutableRow.add(value);
            }
            switches.add(List.copyOf(immutableRow));
        }
        return new Snapshot(evaluations, initialSelections, sizes, switches);
    }

    private void resetInternal() {
        evaluations = 0L;
        initialSelections = 0L;
        for (MutableSizeTotals totals : sizeTotals) {
            totals.reset();
        }
        for (long[] row : switchCounts) {
            java.util.Arrays.fill(row, 0L);
        }
    }

    public record SizeTotals(
            int brickSize,
            long candidateEvaluations,
            long candidateOccupiedBricks,
            long candidateEstimatedDdaSteps,
            long candidateEstimatedBlasBytes,
            double candidateCost,
            long selections,
            long selectedOccupiedBricks,
            long selectedEstimatedDdaSteps,
            long selectedEstimatedBlasBytes,
            double selectedCost,
            long hysteresisRetentions,
            long stableSelections) {
    }

    /** Immutable aggregate suitable for structured diagnostic logging. */
    public record Snapshot(
            long evaluations,
            long initialSelections,
            List<SizeTotals> sizes,
            List<List<Long>> switchMatrix) {
        public Snapshot {
            sizes = List.copyOf(Objects.requireNonNull(sizes, "sizes"));
            if (sizes.size() != SIZE_COUNT) {
                throw new IllegalArgumentException("Telemetry snapshot must contain 4, 8, and 16 sizes");
            }
            for (int i = 0; i < SIZE_COUNT; i++) {
                SizeTotals size = Objects.requireNonNull(sizes.get(i), "size totals");
                if (size.brickSize() != AdaptiveBrickSizePolicy.SUPPORTED_SIZES[i]) {
                    throw new IllegalArgumentException("Telemetry sizes must be ordered as 4, 8, 16");
                }
            }

            Objects.requireNonNull(switchMatrix, "switchMatrix");
            if (switchMatrix.size() != SIZE_COUNT) {
                throw new IllegalArgumentException("Telemetry switch matrix must be 3x3");
            }
            List<List<Long>> copiedMatrix = new ArrayList<>(SIZE_COUNT);
            for (List<Long> sourceRow : switchMatrix) {
                List<Long> row = List.copyOf(Objects.requireNonNull(sourceRow, "switch row"));
                if (row.size() != SIZE_COUNT) {
                    throw new IllegalArgumentException("Telemetry switch matrix must be 3x3");
                }
                copiedMatrix.add(row);
            }
            switchMatrix = List.copyOf(copiedMatrix);
        }

        public SizeTotals forSize(int brickSize) {
            return sizes.get(AdaptiveBrickSizePolicy.supportedSizeIndex(brickSize));
        }

        public long switchCount(int fromBrickSize, int toBrickSize) {
            return switchMatrix
                    .get(AdaptiveBrickSizePolicy.supportedSizeIndex(fromBrickSize))
                    .get(AdaptiveBrickSizePolicy.supportedSizeIndex(toBrickSize));
        }

        public long stableSelections() {
            return sizes.stream().mapToLong(SizeTotals::stableSelections).sum();
        }

        public long hysteresisRetentions() {
            return sizes.stream().mapToLong(SizeTotals::hysteresisRetentions).sum();
        }

        public long switches() {
            long total = 0L;
            for (int from = 0; from < SIZE_COUNT; from++) {
                for (int to = 0; to < SIZE_COUNT; to++) {
                    total += switchMatrix.get(from).get(to);
                }
            }
            return total;
        }

        public String structuredSummary() {
            StringBuilder result = new StringBuilder(512)
                    .append("evaluations=").append(evaluations)
                    .append(" initialSelections=").append(initialSelections)
                    .append(" stableSelections=").append(stableSelections())
                    .append(" hysteresisRetentions=").append(hysteresisRetentions())
                    .append(" switches=").append(switches());
            for (SizeTotals size : sizes) {
                result.append(" size").append(size.brickSize()).append("={")
                        .append("candidateEvaluations=").append(size.candidateEvaluations())
                        .append(",candidateOccupiedBricks=").append(size.candidateOccupiedBricks())
                        .append(",candidateEstimatedDdaSteps=").append(size.candidateEstimatedDdaSteps())
                        .append(",candidateEstimatedBlasBytes=").append(size.candidateEstimatedBlasBytes())
                        .append(",candidateCost=").append(formatCost(size.candidateCost()))
                        .append(",selections=").append(size.selections())
                        .append(",selectedOccupiedBricks=").append(size.selectedOccupiedBricks())
                        .append(",selectedEstimatedDdaSteps=").append(size.selectedEstimatedDdaSteps())
                        .append(",selectedEstimatedBlasBytes=").append(size.selectedEstimatedBlasBytes())
                        .append(",selectedCost=").append(formatCost(size.selectedCost()))
                        .append(",stableSelections=").append(size.stableSelections())
                        .append(",hysteresisRetentions=").append(size.hysteresisRetentions())
                        .append('}');
            }
            result.append(" switchMatrix=[");
            for (int from = 0; from < SIZE_COUNT; from++) {
                if (from > 0) {
                    result.append(';');
                }
                result.append(switchMatrix.get(from).get(0)).append(',')
                        .append(switchMatrix.get(from).get(1)).append(',')
                        .append(switchMatrix.get(from).get(2));
            }
            return result.append(']').toString();
        }

        private static String formatCost(double value) {
            return String.format(Locale.ROOT, "%.6f", value);
        }
    }

    private static final class MutableSizeTotals {
        private final int brickSize;
        private long candidateEvaluations;
        private long candidateOccupiedBricks;
        private long candidateEstimatedDdaSteps;
        private long candidateEstimatedBlasBytes;
        private double candidateCost;
        private long selections;
        private long selectedOccupiedBricks;
        private long selectedEstimatedDdaSteps;
        private long selectedEstimatedBlasBytes;
        private double selectedCost;
        private long hysteresisRetentions;
        private long stableSelections;

        private MutableSizeTotals(int brickSize) {
            this.brickSize = brickSize;
        }

        private void recordCandidate(AdaptiveBrickSizePolicy.Estimate estimate) {
            candidateEvaluations++;
            candidateOccupiedBricks += estimate.occupiedBrickCount();
            candidateEstimatedDdaSteps += estimate.estimatedDdaSteps();
            candidateEstimatedBlasBytes += estimate.estimatedBlasBytes();
            candidateCost += estimate.cost();
        }

        private void recordSelection(AdaptiveBrickSizePolicy.Estimate estimate) {
            selections++;
            selectedOccupiedBricks += estimate.occupiedBrickCount();
            selectedEstimatedDdaSteps += estimate.estimatedDdaSteps();
            selectedEstimatedBlasBytes += estimate.estimatedBlasBytes();
            selectedCost += estimate.cost();
        }

        private SizeTotals snapshot() {
            return new SizeTotals(
                    brickSize,
                    candidateEvaluations,
                    candidateOccupiedBricks,
                    candidateEstimatedDdaSteps,
                    candidateEstimatedBlasBytes,
                    candidateCost,
                    selections,
                    selectedOccupiedBricks,
                    selectedEstimatedDdaSteps,
                    selectedEstimatedBlasBytes,
                    selectedCost,
                    hysteresisRetentions,
                    stableSelections);
        }

        private void reset() {
            candidateEvaluations = 0L;
            candidateOccupiedBricks = 0L;
            candidateEstimatedDdaSteps = 0L;
            candidateEstimatedBlasBytes = 0L;
            candidateCost = 0.0;
            selections = 0L;
            selectedOccupiedBricks = 0L;
            selectedEstimatedDdaSteps = 0L;
            selectedEstimatedBlasBytes = 0L;
            selectedCost = 0.0;
            hysteresisRetentions = 0L;
            stableSelections = 0L;
        }
    }
}
