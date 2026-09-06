package me.cortex.vulkanite.client.rendering;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HybridShadowComparisonLayoutTest {
    @Test
    void bufferContainsTheCompleteBoundedSectionTable() {
        assertEquals(HybridShadowComparisonLayout.HEADER_WORDS,
                HybridShadowComparisonLayout.SECTION_TABLE_OVERFLOW + 2);
        assertEquals(
                HybridShadowComparisonLayout.OWNERSHIP_MISMATCH_BUCKETS
                        + HybridShadowComparisonLayout.OWNERSHIP_MISMATCH_BUCKET_COUNT,
                HybridShadowComparisonLayout.DDA_CANDIDATE_INVOCATIONS);
        assertEquals(
                HybridShadowComparisonLayout.DDA_BRICK_SIZE_COUNTS
                        + HybridShadowComparisonLayout.DDA_BRICK_SIZE_BUCKET_COUNT,
                HybridShadowComparisonLayout.DDA_BRICK_SIZE_STEPS);
        assertEquals(
                HybridShadowComparisonLayout.DDA_BRICK_SIZE_STEPS
                        + HybridShadowComparisonLayout.DDA_BRICK_SIZE_BUCKET_COUNT,
                HybridShadowComparisonLayout.DDA_TELEMETRY_END);
        assertTrue(HybridShadowComparisonLayout.BUFFER_WORDS
                >= HybridShadowComparisonLayout.DDA_TELEMETRY_END);
        assertTrue(HybridShadowComparisonLayout.BUFFER_WORDS
                - HybridShadowComparisonLayout.DDA_TELEMETRY_END < 4);
        assertEquals(HybridShadowComparisonLayout.BUFFER_WORDS * Integer.BYTES,
                HybridShadowComparisonLayout.BUFFER_BYTES);
        assertTrue(HybridShadowComparisonLayout.BUFFER_BYTES % 16 == 0);
    }

    @Test
    void samplingPercentSupportsLowRateAndFullCapture() {
        assertEquals(10, HybridShadowComparisonLayout.clampSamplingPermille(-5));
        assertEquals(10, HybridShadowComparisonLayout.clampSamplingPermille(1));
        assertEquals(370, HybridShadowComparisonLayout.clampSamplingPermille(37));
        assertEquals(1000, HybridShadowComparisonLayout.clampSamplingPermille(100));
        assertEquals(1000, HybridShadowComparisonLayout.clampSamplingPermille(500));
    }

    @Test
    void distanceToleranceIsFiniteAndBounded() {
        assertEquals(0.0001f, HybridShadowComparisonLayout.clampDistanceTolerance(-1.0f));
        assertEquals(0.025f, HybridShadowComparisonLayout.clampDistanceTolerance(0.025f));
        assertEquals(1.0f, HybridShadowComparisonLayout.clampDistanceTolerance(4.0f));
    }
}
