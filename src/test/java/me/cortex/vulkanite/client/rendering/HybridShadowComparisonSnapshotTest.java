package me.cortex.vulkanite.client.rendering;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HybridShadowComparisonSnapshotTest {
    @Test
    void readsUnsignedCountersAndSortsTheHottestSections() {
        ByteBuffer data = ByteBuffer.allocate(HybridShadowComparisonLayout.BUFFER_BYTES)
                .order(ByteOrder.nativeOrder());
        put(data, HybridShadowComparisonLayout.TOTAL, -1);
        put(data, HybridShadowComparisonLayout.DISTANCE_SUM_FIXED, 192);
        put(data, HybridShadowComparisonLayout.DISTANCE_SAMPLES, 3);
        put(data, HybridShadowComparisonLayout.DISTANCE_MAX_FLOAT_BITS, Float.floatToRawIntBits(0.5f));
        put(data, HybridShadowComparisonLayout.OWNERSHIP_MISMATCH_BUCKETS, 11);
        put(data, HybridShadowComparisonLayout.DDA_CANDIDATE_INVOCATIONS, 8);
        put(data, HybridShadowComparisonLayout.DDA_ACCEPTED_HITS, 3);
        put(data, HybridShadowComparisonLayout.DDA_ACCUMULATED_STEPS, 36);
        put(data, HybridShadowComparisonLayout.DDA_MAX_STEPS, 12);
        put(data, HybridShadowComparisonLayout.DDA_BRICK_SIZE_COUNTS, 2);
        put(data, HybridShadowComparisonLayout.DDA_BRICK_SIZE_COUNTS + 1, 4);
        put(data, HybridShadowComparisonLayout.DDA_BRICK_SIZE_COUNTS + 2, 2);
        put(data, HybridShadowComparisonLayout.DDA_BRICK_SIZE_STEPS, 4);
        put(data, HybridShadowComparisonLayout.DDA_BRICK_SIZE_STEPS + 1, 24);
        put(data, HybridShadowComparisonLayout.DDA_BRICK_SIZE_STEPS + 2, 8);
        putSection(data, 0, -2, 4, 9, 7);
        putSection(data, 1, 3, -1, 8, 12);

        HybridShadowComparisonSnapshot snapshot = HybridShadowComparisonSnapshot.read(data);

        assertEquals(0xffff_ffffL, snapshot.total());
        assertEquals(1.0, snapshot.averageDistanceDifference());
        assertEquals(0.5f, snapshot.distanceMaximum());
        assertEquals(new HybridShadowComparisonSnapshot.SectionMismatch(3, -1, 8, 12),
                snapshot.hottestSections().get(0));
        assertEquals(new HybridShadowComparisonSnapshot.SectionMismatch(-2, 4, 9, 7),
                snapshot.hottestSections().get(1));
        assertTrue(snapshot.structuredSummary(25, 0.01f).contains("dangerousMiss=0"));
        assertEquals(11, snapshot.ownershipMismatchBuckets()[0]);
        assertTrue(snapshot.structuredSummary(25, 0.01f)
                .contains("ownershipMismatchBuckets=[11, 0, 0, 0, 0, 0, 0, 0]"));
        assertTrue(snapshot.structuredSummary(25, 0.01f).contains("hottestSections=[3,-1,8:12;-2,4,9:7]"));
        assertEquals(4.5, snapshot.averageDdaSteps());
        assertEquals(0.375, snapshot.ddaAcceptanceRate());
        assertEquals(2.0, snapshot.averageDdaStepsByBrickSize()[0]);
        assertEquals(6.0, snapshot.averageDdaStepsByBrickSize()[1]);
        assertEquals(4.0, snapshot.averageDdaStepsByBrickSize()[2]);
        assertEquals(
                "{4={candidates=2,steps=4,averageSteps=2.0},"
                        + "8={candidates=4,steps=24,averageSteps=6.0},"
                        + "16={candidates=2,steps=8,averageSteps=4.0}}",
                snapshot.ddaByBrickSizeSummary());
        assertTrue(snapshot.structuredSummary(25, 0.01f).contains("ddaCandidates=8"));
        assertTrue(snapshot.structuredSummary(25, 0.01f)
                .contains("ddaBrickSizeAverageSteps=[2.0, 6.0, 4.0]"));
        assertTrue(snapshot.structuredSummary(25, 0.01f)
                .contains("ddaByBrickSize={4={candidates=2,steps=4,averageSteps=2.0}"));
    }

    @Test
    void rejectsTruncatedReadback() {
        assertThrows(IllegalArgumentException.class,
                () -> HybridShadowComparisonSnapshot.read(ByteBuffer.allocate(16)));
    }

    private static void putSection(ByteBuffer data, int slot, int x, int y, int z, int count) {
        int base = HybridShadowComparisonLayout.HEADER_WORDS
                + slot * HybridShadowComparisonLayout.SECTION_RECORD_WORDS;
        put(data, base, 2);
        put(data, base + 1, x);
        put(data, base + 2, y);
        put(data, base + 3, z);
        put(data, base + 4, count);
    }

    private static void put(ByteBuffer data, int word, int value) {
        data.putInt(word * Integer.BYTES, value);
    }
}
