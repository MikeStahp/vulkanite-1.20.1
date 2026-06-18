package me.cortex.vulkanite.compat;

import net.minecraft.util.math.ChunkSectionPos;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public record SectionLightTable(
        ChunkSectionPos sectionPos,
        List<SectionLight> lights,
        long[] opaqueBlocks,
        boolean hasOpaqueBlocks) {
    private static final int SECTION_SIZE = 16;
    private static final int BLOCK_COUNT = SECTION_SIZE * SECTION_SIZE * SECTION_SIZE;
    private static final int OPAQUE_WORD_COUNT = BLOCK_COUNT / Long.SIZE;

    public SectionLightTable {
        Objects.requireNonNull(sectionPos, "sectionPos");
        lights = List.copyOf(lights);
        hasOpaqueBlocks = hasOpaqueBlocks || containsOpaqueBlocks(opaqueBlocks);
        opaqueBlocks = normalizeOpaqueBlocks(opaqueBlocks);
    }

    public SectionLightTable(ChunkSectionPos sectionPos, List<SectionLight> lights, long[] opaqueBlocks) {
        this(sectionPos, lights, opaqueBlocks, false);
    }

    public SectionLightTable(ChunkSectionPos sectionPos, List<SectionLight> lights) {
        this(sectionPos, lights, null, false);
    }

    public static SectionLightTable empty(ChunkSectionPos sectionPos) {
        return new SectionLightTable(sectionPos, List.of(), null, false);
    }

    public boolean isEmpty() {
        return lights.isEmpty() && !hasOpaqueBlocks;
    }

    public int size() {
        return lights.size();
    }

    public boolean hasLights() {
        return !lights.isEmpty();
    }

    public boolean isOpaque(int localX, int localY, int localZ) {
        int index = blockIndex(localX, localY, localZ);
        return (opaqueBlocks[index >>> 6] & (1L << (index & 63))) != 0L;
    }

    public static void setOpaque(long[] opaqueBlocks, int localX, int localY, int localZ) {
        int index = blockIndex(localX, localY, localZ);
        opaqueBlocks[index >>> 6] |= 1L << (index & 63);
    }

    public static long[] newOpacityMask() {
        return new long[OPAQUE_WORD_COUNT];
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (!(object instanceof SectionLightTable other)) {
            return false;
        }
        return sectionPos.equals(other.sectionPos)
                && lights.equals(other.lights)
                && hasOpaqueBlocks == other.hasOpaqueBlocks
                && Arrays.equals(opaqueBlocks, other.opaqueBlocks);
    }

    @Override
    public int hashCode() {
        int result = sectionPos.hashCode();
        result = 31 * result + lights.hashCode();
        result = 31 * result + Arrays.hashCode(opaqueBlocks);
        result = 31 * result + Boolean.hashCode(hasOpaqueBlocks);
        return result;
    }

    private static long[] normalizeOpaqueBlocks(long[] opaqueBlocks) {
        if (opaqueBlocks == null) {
            return new long[OPAQUE_WORD_COUNT];
        }
        if (opaqueBlocks.length == OPAQUE_WORD_COUNT) {
            return opaqueBlocks.clone();
        }
        long[] normalized = new long[OPAQUE_WORD_COUNT];
        System.arraycopy(opaqueBlocks, 0, normalized, 0, Math.min(opaqueBlocks.length, OPAQUE_WORD_COUNT));
        return normalized;
    }

    private static boolean containsOpaqueBlocks(long[] opaqueBlocks) {
        if (opaqueBlocks == null) {
            return false;
        }
        for (long word : opaqueBlocks) {
            if (word != 0L) {
                return true;
            }
        }
        return false;
    }

    private static int blockIndex(int localX, int localY, int localZ) {
        return ((localY & 15) * SECTION_SIZE + (localZ & 15)) * SECTION_SIZE + (localX & 15);
    }
}
