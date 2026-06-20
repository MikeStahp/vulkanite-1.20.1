package me.cortex.vulkanite.client.rendering.cache;

import net.minecraft.util.math.ChunkSectionPos;

/**
 * Stable key for one cache entry that may need RTX fill or validation.
 *
 * <p>The payload shape is intentionally small enough to mirror into a future GPU
 * request buffer without changing the cache identity model.</p>
 */
public record CacheRequestKey(
        CacheRequestFamily family,
        long spatialKey,
        int variantKey,
        int detailKey) implements Comparable<CacheRequestKey> {
    private static final int GRID_XZ_BITS = 22;
    private static final int GRID_Y_BITS = 20;
    private static final long GRID_XZ_MASK = (1L << GRID_XZ_BITS) - 1L;
    private static final long GRID_Y_MASK = (1L << GRID_Y_BITS) - 1L;

    public CacheRequestKey {
        if (family == null) {
            throw new IllegalArgumentException("Cache request family must not be null");
        }
    }

    public static CacheRequestKey sectionProbeCell(ChunkSectionPos sectionPos, int probeIndex) {
        if (sectionPos == null) {
            throw new IllegalArgumentException("Section position must not be null");
        }
        return sectionProbeCell(sectionPos.asLong(), probeIndex);
    }

    public static CacheRequestKey sectionProbeCell(long sectionKey, int probeIndex) {
        return new CacheRequestKey(
                CacheRequestFamily.SECTION_PROBE_CELL,
                sectionKey,
                Math.max(0, probeIndex),
                0);
    }

    public static CacheRequestKey diffuseRadianceEntry(
            int cellX,
            int cellY,
            int cellZ,
            int normalBucket,
            int materialBucket) {
        return new CacheRequestKey(
                CacheRequestFamily.DIFFUSE_RADIANCE,
                packGridCell(cellX, cellY, cellZ),
                packVariant(normalBucket, materialBucket, 0),
                0);
    }

    public static CacheRequestKey reflectionEntry(
            int cellX,
            int cellY,
            int cellZ,
            int normalBucket,
            int roughnessBucket,
            int materialBucket,
            int viewBucket) {
        return new CacheRequestKey(
                CacheRequestFamily.REFLECTION,
                packGridCell(cellX, cellY, cellZ),
                packVariant(normalBucket, roughnessBucket, materialBucket),
                viewBucket);
    }

    public static CacheRequestKey refractionEntry(
            int cellX,
            int cellY,
            int cellZ,
            int normalBucket,
            int roughnessBucket,
            int mediumBucket,
            int materialBucket) {
        return new CacheRequestKey(
                CacheRequestFamily.REFRACTION,
                packGridCell(cellX, cellY, cellZ),
                packVariant(normalBucket, roughnessBucket, materialBucket),
                mediumBucket);
    }

    public static long packGridCell(int cellX, int cellY, int cellZ) {
        return (packSigned(cellX, GRID_XZ_MASK) << (GRID_Y_BITS + GRID_XZ_BITS))
                | (packSigned(cellY, GRID_Y_MASK) << GRID_XZ_BITS)
                | packSigned(cellZ, GRID_XZ_MASK);
    }

    public static int packVariant(int a, int b, int c) {
        return (clampByte(a))
                | (clampByte(b) << 8)
                | (clampByte(c) << 16);
    }

    private static long packSigned(int value, long mask) {
        return (long) value & mask;
    }

    private static int clampByte(int value) {
        return Math.max(0, Math.min(255, value));
    }

    @Override
    public int compareTo(CacheRequestKey other) {
        int familyCompare = Integer.compare(family.ordinal(), other.family.ordinal());
        if (familyCompare != 0) {
            return familyCompare;
        }
        int spatialCompare = Long.compare(spatialKey, other.spatialKey);
        if (spatialCompare != 0) {
            return spatialCompare;
        }
        int variantCompare = Integer.compare(variantKey, other.variantKey);
        if (variantCompare != 0) {
            return variantCompare;
        }
        return Integer.compare(detailKey, other.detailKey);
    }
}
