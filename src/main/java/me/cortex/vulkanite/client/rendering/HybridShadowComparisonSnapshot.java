package me.cortex.vulkanite.client.rendering;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** Immutable CPU snapshot of the shadow-comparison and Phase 9 DDA diagnostics. */
record HybridShadowComparisonSnapshot(
        long total,
        long bothHit,
        long dangerousMiss,
        long extraHit,
        long bothMiss,
        long distanceAgree,
        long distanceMismatch,
        long distanceSumFixed,
        float distanceMaximum,
        long counterSaturations,
        long distanceSamples,
        long[] brickSizeBuckets,
        long[] directionOctants,
        long insideStartMismatches,
        long[] materialBuckets,
        long sectionTableOverflow,
        long solidInteriorStarts,
        List<SectionMismatch> hottestSections,
        long[] distanceMismatchBuckets,
        long[] ownershipMismatchBuckets,
        long ddaCandidateInvocations,
        long ddaAcceptedHits,
        long ddaAccumulatedSteps,
        long ddaMaximumSteps,
        long[] ddaBrickSizeCandidates,
        long[] ddaBrickSizeSteps) {

    private static final int LOGGED_SECTION_LIMIT = 8;
    private static final int[] DDA_BRICK_SIZES = {4, 8, 16};

    record SectionMismatch(int x, int y, int z, long count) {
        String compact() {
            return x + "," + y + "," + z + ":" + count;
        }
    }

    static HybridShadowComparisonSnapshot read(ByteBuffer source) {
        if (source.remaining() < HybridShadowComparisonLayout.BUFFER_BYTES) {
            throw new IllegalArgumentException("Shadow comparison snapshot is smaller than the SSBO layout");
        }
        ByteBuffer words = source.duplicate().order(ByteOrder.nativeOrder());
        int base = words.position();

        ArrayList<SectionMismatch> sections = new ArrayList<>();
        for (int slot = 0; slot < HybridShadowComparisonLayout.SECTION_RECORD_COUNT; slot++) {
            int word = HybridShadowComparisonLayout.HEADER_WORDS
                    + slot * HybridShadowComparisonLayout.SECTION_RECORD_WORDS;
            if (getInt(words, base, word) == 0) {
                continue;
            }
            sections.add(new SectionMismatch(
                    getInt(words, base, word + 1),
                    getInt(words, base, word + 2),
                    getInt(words, base, word + 3),
                    unsigned(words, base, word + 4)));
        }
        sections.sort(Comparator.comparingLong(SectionMismatch::count).reversed()
                .thenComparingInt(SectionMismatch::x)
                .thenComparingInt(SectionMismatch::y)
                .thenComparingInt(SectionMismatch::z));
        if (sections.size() > LOGGED_SECTION_LIMIT) {
            sections.subList(LOGGED_SECTION_LIMIT, sections.size()).clear();
        }

        return new HybridShadowComparisonSnapshot(
                unsigned(words, base, HybridShadowComparisonLayout.TOTAL),
                unsigned(words, base, HybridShadowComparisonLayout.BOTH_HIT),
                unsigned(words, base, HybridShadowComparisonLayout.DANGEROUS_MISS),
                unsigned(words, base, HybridShadowComparisonLayout.EXTRA_HIT),
                unsigned(words, base, HybridShadowComparisonLayout.BOTH_MISS),
                unsigned(words, base, HybridShadowComparisonLayout.DISTANCE_AGREE),
                unsigned(words, base, HybridShadowComparisonLayout.DISTANCE_MISMATCH),
                unsigned(words, base, HybridShadowComparisonLayout.DISTANCE_SUM_FIXED),
                Float.intBitsToFloat(getInt(words, base, HybridShadowComparisonLayout.DISTANCE_MAX_FLOAT_BITS)),
                unsigned(words, base, HybridShadowComparisonLayout.COUNTER_SATURATIONS),
                unsigned(words, base, HybridShadowComparisonLayout.DISTANCE_SAMPLES),
                readUnsignedRange(words, base, HybridShadowComparisonLayout.BRICK_SIZE_BUCKETS, 4),
                readUnsignedRange(words, base, HybridShadowComparisonLayout.OCTANT_BUCKETS, 8),
                unsigned(words, base, HybridShadowComparisonLayout.INSIDE_START_MISMATCH),
                readUnsignedRange(words, base, HybridShadowComparisonLayout.MATERIAL_BUCKETS, 5),
                unsigned(words, base, HybridShadowComparisonLayout.SECTION_TABLE_OVERFLOW),
                unsigned(words, base, HybridShadowComparisonLayout.SOLID_INTERIOR_START),
                List.copyOf(sections),
                readUnsignedRange(words, base, HybridShadowComparisonLayout.DISTANCE_BUCKETS,
                        HybridShadowComparisonLayout.DISTANCE_BUCKET_COUNT),
                readUnsignedRange(words, base, HybridShadowComparisonLayout.OWNERSHIP_MISMATCH_BUCKETS,
                        HybridShadowComparisonLayout.OWNERSHIP_MISMATCH_BUCKET_COUNT),
                unsigned(words, base, HybridShadowComparisonLayout.DDA_CANDIDATE_INVOCATIONS),
                unsigned(words, base, HybridShadowComparisonLayout.DDA_ACCEPTED_HITS),
                unsigned(words, base, HybridShadowComparisonLayout.DDA_ACCUMULATED_STEPS),
                unsigned(words, base, HybridShadowComparisonLayout.DDA_MAX_STEPS),
                readUnsignedRange(words, base, HybridShadowComparisonLayout.DDA_BRICK_SIZE_COUNTS,
                        HybridShadowComparisonLayout.DDA_BRICK_SIZE_BUCKET_COUNT),
                readUnsignedRange(words, base, HybridShadowComparisonLayout.DDA_BRICK_SIZE_STEPS,
                        HybridShadowComparisonLayout.DDA_BRICK_SIZE_BUCKET_COUNT));
    }

    double averageDistanceDifference() {
        return distanceSamples == 0 ? 0.0 : distanceSumFixed / 64.0 / distanceSamples;
    }

    double averageDdaSteps() {
        return ddaCandidateInvocations == 0
                ? 0.0
                : (double) ddaAccumulatedSteps / ddaCandidateInvocations;
    }

    double ddaAcceptanceRate() {
        return ddaCandidateInvocations == 0
                ? 0.0
                : (double) ddaAcceptedHits / ddaCandidateInvocations;
    }

    double[] averageDdaStepsByBrickSize() {
        double[] averages = new double[HybridShadowComparisonLayout.DDA_BRICK_SIZE_BUCKET_COUNT];
        for (int i = 0; i < averages.length; i++) {
            averages[i] = ddaBrickSizeCandidates[i] == 0
                    ? 0.0
                    : (double) ddaBrickSizeSteps[i] / ddaBrickSizeCandidates[i];
        }
        return averages;
    }

    String ddaByBrickSizeSummary() {
        double[] averages = averageDdaStepsByBrickSize();
        StringBuilder result = new StringBuilder("{");
        for (int i = 0; i < DDA_BRICK_SIZES.length; i++) {
            if (i != 0) {
                result.append(',');
            }
            result.append(DDA_BRICK_SIZES[i]).append("={candidates=")
                    .append(ddaBrickSizeCandidates[i])
                    .append(",steps=").append(ddaBrickSizeSteps[i])
                    .append(",averageSteps=").append(averages[i])
                    .append('}');
        }
        return result.append('}').toString();
    }

    String structuredSummary(int samplingPercent, float tolerance) {
        String sections = hottestSections.stream()
                .map(SectionMismatch::compact)
                .reduce((left, right) -> left + ";" + right)
                .orElse("");
        return "samplingPercent=" + samplingPercent
                + " tolerance=" + tolerance
                + " total=" + total
                + " bothHit=" + bothHit
                + " dangerousMiss=" + dangerousMiss
                + " extraHit=" + extraHit
                + " bothMiss=" + bothMiss
                + " distanceAgree=" + distanceAgree
                + " distanceMismatch=" + distanceMismatch
                + " distanceAverage=" + averageDistanceDifference()
                + " distanceMaximum=" + distanceMaximum
                + " counterSaturations=" + counterSaturations
                + " brickBuckets=" + Arrays.toString(brickSizeBuckets)
                + " directionOctants=" + Arrays.toString(directionOctants)
                + " insideStartMismatches=" + insideStartMismatches
                + " materialBuckets=" + Arrays.toString(materialBuckets)
                + " sectionTableOverflow=" + sectionTableOverflow
                + " solidInteriorStarts=" + solidInteriorStarts
                + " hottestSections=[" + sections + "]"
                + " distanceMismatchBuckets=" + Arrays.toString(distanceMismatchBuckets)
                + " ownershipMismatchBuckets=" + Arrays.toString(ownershipMismatchBuckets)
                + " ddaCandidates=" + ddaCandidateInvocations
                + " ddaAcceptedHits=" + ddaAcceptedHits
                + " ddaAcceptanceRate=" + ddaAcceptanceRate()
                + " ddaAccumulatedSteps=" + ddaAccumulatedSteps
                + " ddaAverageSteps=" + averageDdaSteps()
                + " ddaMaximumSteps=" + ddaMaximumSteps
                + " ddaBrickSizeCandidates=" + Arrays.toString(ddaBrickSizeCandidates)
                + " ddaBrickSizeSteps=" + Arrays.toString(ddaBrickSizeSteps)
                + " ddaBrickSizeAverageSteps=" + Arrays.toString(averageDdaStepsByBrickSize())
                + " ddaByBrickSize=" + ddaByBrickSizeSummary();
    }

    private static long[] readUnsignedRange(ByteBuffer words, int base, int firstWord, int count) {
        long[] result = new long[count];
        for (int i = 0; i < count; i++) {
            result[i] = unsigned(words, base, firstWord + i);
        }
        return result;
    }

    private static int getInt(ByteBuffer words, int base, int word) {
        return words.getInt(base + word * Integer.BYTES);
    }

    private static long unsigned(ByteBuffer words, int base, int word) {
        return Integer.toUnsignedLong(getInt(words, base, word));
    }
}
