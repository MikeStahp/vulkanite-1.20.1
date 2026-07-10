package me.cortex.vulkanite.acceleration.voxel;

import me.cortex.vulkanite.compat.SectionLightTable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Packed section-local voxel geometry for the hybrid RT backend.
 *
 * <p>Each non-empty macro brick contributes one {@code VkAabbPositionsKHR}
 * record for RT-core broad phase traversal and one fixed-stride occupancy
 * record for the intersection shader's short local DDA. Coordinates are local
 * to a Minecraft 16-cubed chunk section so the existing TLAS translation can
 * be reused.</p>
 */
public final class VoxelBrickGeometry {
    public static final int SECTION_SIZE = 16;
    public static final int DEFAULT_BRICK_SIZE = 8;
    public static final int PACKED_VERSION = 1;
    public static final int HEADER_BYTES = 32;
    public static final int AABB_BYTES = Float.BYTES * 6;
    public static final int RECORD_HEADER_BYTES = Integer.BYTES * 4;

    private static final int MAGIC = 0x5642524B; // VBRK

    private final int brickSize;
    private final int occupancyWordsPerBrick;
    private final int solidBlockCount;
    private final List<Brick> bricks;

    private VoxelBrickGeometry(
            int brickSize,
            int occupancyWordsPerBrick,
            int solidBlockCount,
            List<Brick> bricks) {
        this.brickSize = brickSize;
        this.occupancyWordsPerBrick = occupancyWordsPerBrick;
        this.solidBlockCount = solidBlockCount;
        this.bricks = List.copyOf(bricks);
    }

    public static VoxelBrickGeometry from(SectionLightTable table) {
        return from(table, configuredBrickSize());
    }

    public static VoxelBrickGeometry from(SectionLightTable table, int brickSize) {
        Objects.requireNonNull(table, "table");
        return build(brickSize, table::isOpaque);
    }

    /** Builds geometry directly from the section's 64-word occupancy mask. */
    public static VoxelBrickGeometry fromOpacityMask(long[] opacityMask, int brickSize) {
        Objects.requireNonNull(opacityMask, "opacityMask");
        if (opacityMask.length < SECTION_SIZE * SECTION_SIZE * SECTION_SIZE / Long.SIZE) {
            throw new IllegalArgumentException("A section opacity mask must contain at least 64 longs");
        }
        return build(brickSize, (x, y, z) -> {
            int bit = ((y * SECTION_SIZE + z) * SECTION_SIZE) + x;
            return (opacityMask[bit >>> 6] & (1L << (bit & 63))) != 0L;
        });
    }

    private static VoxelBrickGeometry build(int brickSize, OccupancySource source) {
        validateBrickSize(brickSize);
        int cellsPerBrick = brickSize * brickSize * brickSize;
        int wordsPerBrick = (cellsPerBrick + Long.SIZE - 1) / Long.SIZE;
        List<Brick> bricks = new ArrayList<>();
        int solidBlocks = 0;

        for (int brickY = 0; brickY < SECTION_SIZE; brickY += brickSize) {
            for (int brickZ = 0; brickZ < SECTION_SIZE; brickZ += brickSize) {
                for (int brickX = 0; brickX < SECTION_SIZE; brickX += brickSize) {
                    long[] occupancy = new long[wordsPerBrick];
                    int occupiedCells = 0;
                    for (int y = 0; y < brickSize; y++) {
                        for (int z = 0; z < brickSize; z++) {
                            for (int x = 0; x < brickSize; x++) {
                                if (!source.occupied(brickX + x, brickY + y, brickZ + z)) {
                                    continue;
                                }
                                int bit = ((y * brickSize + z) * brickSize) + x;
                                occupancy[bit >>> 6] |= 1L << (bit & 63);
                                occupiedCells++;
                            }
                        }
                    }
                    if (occupiedCells > 0) {
                        bricks.add(new Brick(brickX, brickY, brickZ, occupiedCells, occupancy));
                        solidBlocks += occupiedCells;
                    }
                }
            }
        }

        return new VoxelBrickGeometry(brickSize, wordsPerBrick, solidBlocks, bricks);
    }

    public int brickSize() {
        return brickSize;
    }

    public int occupancyWordsPerBrick() {
        return occupancyWordsPerBrick;
    }

    public int brickCount() {
        return bricks.size();
    }

    public int solidBlockCount() {
        return solidBlockCount;
    }

    public List<Brick> bricks() {
        return bricks;
    }

    public int recordStrideBytes() {
        return RECORD_HEADER_BYTES + occupancyWordsPerBrick * Long.BYTES;
    }

    public int aabbOffsetBytes() {
        return HEADER_BYTES;
    }

    public int recordOffsetBytes() {
        return align16(aabbOffsetBytes() + brickCount() * AABB_BYTES);
    }

    public int packedBytes() {
        return recordOffsetBytes() + brickCount() * recordStrideBytes();
    }

    public int aabbBytes() {
        return brickCount() * AABB_BYTES;
    }

    /** Creates only the tightly packed {@code VkAabbPositionsKHR} array. */
    public ByteBuffer packAabbs() {
        ByteBuffer data = ByteBuffer.allocateDirect(aabbBytes()).order(ByteOrder.nativeOrder());
        writeAabbs(data);
        data.flip();
        return data;
    }

    /**
     * Creates a native-order blob containing a header, tightly packed Vulkan
     * AABBs, and fixed-stride shader records.
     */
    public ByteBuffer pack() {
        ByteBuffer data = ByteBuffer.allocateDirect(packedBytes()).order(ByteOrder.nativeOrder());
        data.putInt(MAGIC);
        data.putInt(PACKED_VERSION);
        data.putInt(brickSize);
        data.putInt(brickCount());
        data.putInt(occupancyWordsPerBrick);
        data.putInt(aabbOffsetBytes());
        data.putInt(recordOffsetBytes());
        data.putInt(recordStrideBytes());

        data.position(aabbOffsetBytes());
        writeAabbs(data);

        data.position(recordOffsetBytes());
        for (Brick brick : bricks) {
            data.putInt(brick.localX());
            data.putInt(brick.localY());
            data.putInt(brick.localZ());
            data.putInt(brick.occupiedCellCount());
            for (long word : brick.occupancyWords) {
                data.putLong(word);
            }
        }
        data.flip();
        return data;
    }

    private void writeAabbs(ByteBuffer data) {
        for (Brick brick : bricks) {
            data.putFloat(brick.localX());
            data.putFloat(brick.localY());
            data.putFloat(brick.localZ());
            data.putFloat(brick.localX() + brickSize);
            data.putFloat(brick.localY() + brickSize);
            data.putFloat(brick.localZ() + brickSize);
        }
    }

    public boolean isOccupied(int brickIndex, int localX, int localY, int localZ) {
        if (brickIndex < 0 || brickIndex >= bricks.size()) {
            return false;
        }
        if (localX < 0 || localY < 0 || localZ < 0
                || localX >= brickSize || localY >= brickSize || localZ >= brickSize) {
            return false;
        }
        int bit = ((localY * brickSize + localZ) * brickSize) + localX;
        long[] occupancy = bricks.get(brickIndex).occupancyWords;
        return (occupancy[bit >>> 6] & (1L << (bit & 63))) != 0L;
    }

    private static int configuredBrickSize() {
        int configured = Integer.getInteger("vulkanite.voxelBrickSize", DEFAULT_BRICK_SIZE);
        return configured == 4 || configured == 8 || configured == 16
                ? configured
                : DEFAULT_BRICK_SIZE;
    }

    private static void validateBrickSize(int brickSize) {
        if (brickSize != 4 && brickSize != 8 && brickSize != 16) {
            throw new IllegalArgumentException("Voxel brick size must be 4, 8, or 16");
        }
    }

    private static int align16(int value) {
        return (value + 15) & ~15;
    }

    @FunctionalInterface
    private interface OccupancySource {
        boolean occupied(int x, int y, int z);
    }

    public static final class Brick {
        private final int localX;
        private final int localY;
        private final int localZ;
        private final int occupiedCellCount;
        private final long[] occupancyWords;

        private Brick(
                int localX,
                int localY,
                int localZ,
                int occupiedCellCount,
                long[] occupancyWords) {
            this.localX = localX;
            this.localY = localY;
            this.localZ = localZ;
            this.occupiedCellCount = occupiedCellCount;
            this.occupancyWords = occupancyWords.clone();
        }

        public int localX() {
            return localX;
        }

        public int localY() {
            return localY;
        }

        public int localZ() {
            return localZ;
        }

        public int occupiedCellCount() {
            return occupiedCellCount;
        }

        public long[] occupancyWords() {
            return occupancyWords.clone();
        }
    }
}
