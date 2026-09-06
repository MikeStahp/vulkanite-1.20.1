package me.cortex.vulkanite.compat;

import net.minecraft.util.math.ChunkSectionPos;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public record SectionLightTable(
        ChunkSectionPos sectionPos,
        List<SectionLight> lights,
        long[] opaqueBlocks,
        long[] opaqueMipWords,
        boolean hasOpaqueBlocks,
        long[] proceduralBlocks,
        SectionRayClassificationStats rayClassificationStats) {
    private static final int SECTION_SIZE = 16;
    private static final int BLOCK_COUNT = SECTION_SIZE * SECTION_SIZE * SECTION_SIZE;
    private static final int OPAQUE_WORD_COUNT = BLOCK_COUNT / Long.SIZE;
    private static final int OPAQUE_MIP_LEVELS = 4;
    private static final int OPAQUE_MIP_WORD_COUNT = 10;
    private static final int[] OPAQUE_MIP_BIT_OFFSETS = {0, 0, 512, 576, 584};

    public SectionLightTable {
        Objects.requireNonNull(sectionPos, "sectionPos");
        lights = List.copyOf(lights);
        opaqueBlocks = normalizeOpaqueBlocks(opaqueBlocks);
        hasOpaqueBlocks = hasOpaqueBlocks || containsOpaqueBlocks(opaqueBlocks);
        opaqueMipWords = normalizeOpaqueMipWords(opaqueMipWords, opaqueBlocks, hasOpaqueBlocks);
        proceduralBlocks = normalizeOpaqueBlocks(proceduralBlocks);
        rayClassificationStats = rayClassificationStats == null ? SectionRayClassificationStats.EMPTY : rayClassificationStats;
    }

    public SectionLightTable(ChunkSectionPos sectionPos, List<SectionLight> lights, long[] opaqueBlocks,
            long[] opaqueMipWords, boolean hasOpaqueBlocks, long[] proceduralBlocks) {
        this(sectionPos, lights, opaqueBlocks, opaqueMipWords, hasOpaqueBlocks, proceduralBlocks,
                SectionRayClassificationStats.EMPTY);
    }

    public SectionLightTable(ChunkSectionPos sectionPos, List<SectionLight> lights, long[] opaqueBlocks,
            long[] opaqueMipWords, boolean hasOpaqueBlocks) {
        this(sectionPos, lights, opaqueBlocks, opaqueMipWords, hasOpaqueBlocks, opaqueBlocks);
    }

    public SectionLightTable(ChunkSectionPos sectionPos, List<SectionLight> lights, long[] opaqueBlocks,
            boolean hasOpaqueBlocks) {
        this(sectionPos, lights, opaqueBlocks, null, hasOpaqueBlocks);
    }

    public SectionLightTable(ChunkSectionPos sectionPos, List<SectionLight> lights, long[] opaqueBlocks) {
        this(sectionPos, lights, opaqueBlocks, null, false);
    }

    public SectionLightTable(ChunkSectionPos sectionPos, List<SectionLight> lights) {
        this(sectionPos, lights, null, null, false);
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

    /** Returns whether this cell is a regular opaque full cube suitable for procedural RT. */
    public boolean isProceduralFullCube(int localX, int localY, int localZ) {
        int index = blockIndex(localX, localY, localZ);
        return (proceduralBlocks[index >>> 6] & (1L << (index & 63))) != 0L;
    }

    public boolean hasOpaqueInBox(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        if (!hasOpaqueBlocks) {
            return false;
        }

        minX = clampLocal(minX);
        minY = clampLocal(minY);
        minZ = clampLocal(minZ);
        maxX = clampLocal(maxX);
        maxY = clampLocal(maxY);
        maxZ = clampLocal(maxZ);
        if (minX > maxX || minY > maxY || minZ > maxZ) {
            return false;
        }
        return hasOpaqueInBox(minX, minY, minZ, maxX, maxY, maxZ, OPAQUE_MIP_LEVELS, 0, 0, 0);
    }

    public int largestEmptyMipLevel(int localX, int localY, int localZ) {
        if (!hasOpaqueBlocks) {
            return OPAQUE_MIP_LEVELS;
        }

        localX = clampLocal(localX);
        localY = clampLocal(localY);
        localZ = clampLocal(localZ);
        for (int level = OPAQUE_MIP_LEVELS; level >= 1; level--) {
            if (!isOpaqueMipSet(level, localX >> level, localY >> level, localZ >> level)) {
                return level;
            }
        }
        return 0;
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
                && Arrays.equals(opaqueBlocks, other.opaqueBlocks)
                && Arrays.equals(opaqueMipWords, other.opaqueMipWords)
                && Arrays.equals(proceduralBlocks, other.proceduralBlocks)
                && rayClassificationStats.equals(other.rayClassificationStats);
    }

    @Override
    public int hashCode() {
        int result = sectionPos.hashCode();
        result = 31 * result + lights.hashCode();
        result = 31 * result + Arrays.hashCode(opaqueBlocks);
        result = 31 * result + Arrays.hashCode(opaqueMipWords);
        result = 31 * result + Boolean.hashCode(hasOpaqueBlocks);
        result = 31 * result + Arrays.hashCode(proceduralBlocks);
        result = 31 * result + rayClassificationStats.hashCode();
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

    private static long[] normalizeOpaqueMipWords(long[] opaqueMipWords, long[] opaqueBlocks, boolean hasOpaqueBlocks) {
        if (opaqueMipWords != null && opaqueMipWords.length == OPAQUE_MIP_WORD_COUNT) {
            return opaqueMipWords.clone();
        }
        return buildOpaqueMipWords(opaqueBlocks, hasOpaqueBlocks);
    }

    private static long[] buildOpaqueMipWords(long[] opaqueBlocks, boolean hasOpaqueBlocks) {
        long[] mipWords = new long[OPAQUE_MIP_WORD_COUNT];
        if (!hasOpaqueBlocks) {
            return mipWords;
        }

        for (int y = 0; y < SECTION_SIZE; y++) {
            for (int z = 0; z < SECTION_SIZE; z++) {
                for (int x = 0; x < SECTION_SIZE; x++) {
                    int index = blockIndex(x, y, z);
                    if ((opaqueBlocks[index >>> 6] & (1L << (index & 63))) == 0L) {
                        continue;
                    }
                    for (int level = 1; level <= OPAQUE_MIP_LEVELS; level++) {
                        setOpaqueMip(mipWords, level, x >> level, y >> level, z >> level);
                    }
                }
            }
        }
        return mipWords;
    }

    private boolean hasOpaqueInBox(int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
            int level, int cellX, int cellY, int cellZ) {
        int cellSize = 1 << level;
        int cellMinX = cellX * cellSize;
        int cellMinY = cellY * cellSize;
        int cellMinZ = cellZ * cellSize;
        int cellMaxX = cellMinX + cellSize - 1;
        int cellMaxY = cellMinY + cellSize - 1;
        int cellMaxZ = cellMinZ + cellSize - 1;
        if (cellMaxX < minX || cellMaxY < minY || cellMaxZ < minZ
                || cellMinX > maxX || cellMinY > maxY || cellMinZ > maxZ) {
            return false;
        }

        if (level == 0) {
            return isOpaque(cellX, cellY, cellZ);
        }
        if (!isOpaqueMipSet(level, cellX, cellY, cellZ)) {
            return false;
        }
        if (cellMinX >= minX && cellMinY >= minY && cellMinZ >= minZ
                && cellMaxX <= maxX && cellMaxY <= maxY && cellMaxZ <= maxZ) {
            return true;
        }

        int childLevel = level - 1;
        int childBaseX = cellX << 1;
        int childBaseY = cellY << 1;
        int childBaseZ = cellZ << 1;
        for (int childY = 0; childY < 2; childY++) {
            for (int childZ = 0; childZ < 2; childZ++) {
                for (int childX = 0; childX < 2; childX++) {
                    if (hasOpaqueInBox(minX, minY, minZ, maxX, maxY, maxZ,
                            childLevel, childBaseX + childX, childBaseY + childY, childBaseZ + childZ)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean isOpaqueMipSet(int level, int x, int y, int z) {
        int bit = opaqueMipBit(level, x, y, z);
        return (opaqueMipWords[bit >>> 6] & (1L << (bit & 63))) != 0L;
    }

    private static void setOpaqueMip(long[] mipWords, int level, int x, int y, int z) {
        int bit = opaqueMipBit(level, x, y, z);
        mipWords[bit >>> 6] |= 1L << (bit & 63);
    }

    private static int opaqueMipBit(int level, int x, int y, int z) {
        int dim = SECTION_SIZE >> level;
        return OPAQUE_MIP_BIT_OFFSETS[level] + ((y & (dim - 1)) * dim + (z & (dim - 1))) * dim
                + (x & (dim - 1));
    }

    private static int clampLocal(int value) {
        return Math.max(0, Math.min(SECTION_SIZE - 1, value));
    }

    private static int blockIndex(int localX, int localY, int localZ) {
        return ((localY & 15) * SECTION_SIZE + (localZ & 15)) * SECTION_SIZE + (localX & 15);
    }
}
