package me.cortex.vulkanite.client.rendering;

/**
 * Shared CPU-side contract for the shadow-comparison and Phase 9 DDA telemetry SSBO.
 *
 * <p>The shader owns the individual word meanings. Java owns allocation,
 * clearing, descriptor binding, and the bounded section table capacity.</p>
 */
final class HybridShadowComparisonLayout {
    static final int DESCRIPTOR_BINDING = 33;

    // Header word contract mirrored by ray0.rgen. Hit-distance sums use 1/64
    // block units; average distance is DISTANCE_SUM_FIXED / 64 / DISTANCE_SAMPLES.
    static final int TOTAL = 0;
    static final int BOTH_HIT = 1;
    static final int DANGEROUS_MISS = 2;
    static final int EXTRA_HIT = 3;
    static final int BOTH_MISS = 4;
    static final int DISTANCE_AGREE = 5;
    static final int DISTANCE_MISMATCH = 6;
    static final int DISTANCE_SUM_FIXED = 7;
    static final int DISTANCE_MAX_FLOAT_BITS = 8;
    static final int COUNTER_SATURATIONS = 9;
    static final int DISTANCE_SAMPLES = 10;
    static final int BRICK_SIZE_BUCKETS = 12;
    static final int OCTANT_BUCKETS = 16;
    static final int INSIDE_START_MISMATCH = 24;
    static final int MATERIAL_BUCKETS = 25;
    static final int SECTION_TABLE_OVERFLOW = 30;
    static final int SOLID_INTERIOR_START = 31;
    static final int HEADER_WORDS = 32;
    static final int SECTION_RECORD_WORDS = 5;
    static final int SECTION_RECORD_COUNT = 64;
    static final int DISTANCE_BUCKETS = HEADER_WORDS + SECTION_RECORD_WORDS * SECTION_RECORD_COUNT;
    static final int DISTANCE_BUCKET_COUNT = 8;
    // dangerous: procedural-owned, triangle-only, unknown; distance mismatch:
    // procedural-owned, triangle-only, unknown; two reserved words.
    static final int OWNERSHIP_MISMATCH_BUCKETS = DISTANCE_BUCKETS + DISTANCE_BUCKET_COUNT;
    static final int OWNERSHIP_MISMATCH_BUCKET_COUNT = 8;
    // Phase 9 DDA work telemetry. A step is one occupied-cell probe, including
    // the extra adjacent-cell probes used for conservative edge/corner hits.
    static final int DDA_CANDIDATE_INVOCATIONS =
            OWNERSHIP_MISMATCH_BUCKETS + OWNERSHIP_MISMATCH_BUCKET_COUNT;
    static final int DDA_ACCEPTED_HITS = DDA_CANDIDATE_INVOCATIONS + 1;
    static final int DDA_ACCUMULATED_STEPS = DDA_ACCEPTED_HITS + 1;
    static final int DDA_MAX_STEPS = DDA_ACCUMULATED_STEPS + 1;
    static final int DDA_BRICK_SIZE_COUNTS = DDA_MAX_STEPS + 1;
    static final int DDA_BRICK_SIZE_BUCKET_COUNT = 3;
    static final int DDA_BRICK_SIZE_STEPS =
            DDA_BRICK_SIZE_COUNTS + DDA_BRICK_SIZE_BUCKET_COUNT;
    static final int DDA_TELEMETRY_END =
            DDA_BRICK_SIZE_STEPS + DDA_BRICK_SIZE_BUCKET_COUNT;
    // Storage-buffer clears and copies are simplest when the total remains
    // vec4-aligned. The final two words are intentionally reserved padding.
    static final int BUFFER_WORDS = (DDA_TELEMETRY_END + 3) & ~3;
    static final int BUFFER_BYTES = BUFFER_WORDS * Integer.BYTES;

    private HybridShadowComparisonLayout() {
    }

    static int clampSamplingPermille(int percent) {
        return Math.max(1, Math.min(100, percent)) * 10;
    }

    static float clampDistanceTolerance(float tolerance) {
        return Math.max(0.0001f, Math.min(1.0f, tolerance));
    }
}
