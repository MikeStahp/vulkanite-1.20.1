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
 *
 * <p>The legacy version-1 header is eight 32-bit words: magic, version, brick
 * size, brick count, 64-bit occupancy words per brick, AABB byte offset,
 * brick-record byte offset, and brick-record byte stride. Version 2 appends
 * eight words: cell-reference, cell-material, face-material, and face-layer
 * byte offsets; the three corresponding record counts; and total blob bytes.
 * Every version-2 table begins at a 16-byte boundary. The AABB and brick-record
 * layouts themselves are unchanged.</p>
 */
public final class VoxelBrickGeometry {
    public static final int SECTION_SIZE = 16;
    public static final int DEFAULT_BRICK_SIZE = 8;
    /** Occupancy-only layout consumed by the existing shadow/debug shaders. */
    public static final int PACKED_VERSION = 1;
    /** Occupancy plus procedural reflection material tables. */
    public static final int MATERIAL_PACKED_VERSION = 2;
    public static final int HEADER_BYTES = 32;
    public static final int MATERIAL_HEADER_BYTES = 64;
    public static final int AABB_BYTES = Float.BYTES * 6;
    public static final int RECORD_HEADER_BYTES = Integer.BYTES * 4;

    private static final int MAGIC = 0x5642524B; // VBRK

    private final int brickSize;
    private final int occupancyWordsPerBrick;
    private final int solidBlockCount;
    private final List<Brick> bricks;
    private final ProceduralMaterialPayload materialPayload;

    private VoxelBrickGeometry(
            int brickSize,
            int occupancyWordsPerBrick,
            int solidBlockCount,
            List<Brick> bricks,
            ProceduralMaterialPayload materialPayload) {
        this.brickSize = brickSize;
        this.occupancyWordsPerBrick = occupancyWordsPerBrick;
        this.solidBlockCount = solidBlockCount;
        this.bricks = List.copyOf(bricks);
        this.materialPayload = Objects.requireNonNull(materialPayload, "materialPayload");
    }

    public static VoxelBrickGeometry from(SectionLightTable table) {
        return from(table, configuredBrickSize());
    }

    public static VoxelBrickGeometry from(SectionLightTable table, int brickSize) {
        Objects.requireNonNull(table, "table");
        return build(brickSize, table::isOpaque, ProceduralMaterialPayload.empty());
    }

    /** Builds regular-full-cube geometry with an optional reflection material payload. */
    public static VoxelBrickGeometry from(
            SectionLightTable table,
            int brickSize,
            ProceduralMaterialPayload materialPayload) {
        Objects.requireNonNull(table, "table");
        return build(brickSize, table::isProceduralFullCube, materialPayload);
    }

    /** Builds geometry directly from the section's 64-word occupancy mask. */
    public static VoxelBrickGeometry fromOpacityMask(long[] opacityMask, int brickSize) {
        return fromOpacityMask(opacityMask, brickSize, ProceduralMaterialPayload.empty());
    }

    /**
     * Builds geometry directly from occupancy and optional per-cell reflection
     * metadata. Material cells outside the occupancy mask are rejected.
     */
    public static VoxelBrickGeometry fromOpacityMask(
            long[] opacityMask,
            int brickSize,
            ProceduralMaterialPayload materialPayload) {
        Objects.requireNonNull(opacityMask, "opacityMask");
        if (opacityMask.length < SECTION_SIZE * SECTION_SIZE * SECTION_SIZE / Long.SIZE) {
            throw new IllegalArgumentException("A section opacity mask must contain at least 64 longs");
        }
        return build(brickSize, (x, y, z) -> {
            int bit = ((y * SECTION_SIZE + z) * SECTION_SIZE) + x;
            return (opacityMask[bit >>> 6] & (1L << (bit & 63))) != 0L;
        }, materialPayload);
    }

    private static VoxelBrickGeometry build(
            int brickSize,
            OccupancySource source,
            ProceduralMaterialPayload materialPayload) {
        validateBrickSize(brickSize);
        Objects.requireNonNull(materialPayload, "materialPayload");
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

        validateMaterialCells(source, materialPayload);
        return new VoxelBrickGeometry(
                brickSize, wordsPerBrick, solidBlocks, bricks, materialPayload);
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

    public ProceduralMaterialPayload materialPayload() {
        return materialPayload;
    }

    public boolean hasMaterialPayload() {
        return !materialPayload.isEmpty();
    }

    /**
     * Returns whether every occupied voxel has all six material faces.
     *
     * <p>This deliberately strict predicate is the production-reflection
     * safety gate. Normal chunk meshing culls hidden faces, so most sections
     * will remain triangle-owned until comparison testing proves that a less
     * conservative, hit-face-specific gate is safe.</p>
     */
    public boolean hasCompleteMaterialPayload() {
        if (!hasMaterialPayload()) {
            return false;
        }
        for (Brick brick : bricks) {
            for (int y = 0; y < brickSize; y++) {
                for (int z = 0; z < brickSize; z++) {
                    for (int x = 0; x < brickSize; x++) {
                        int bit = ((y * brickSize + z) * brickSize) + x;
                        if ((brick.occupancyWords[bit >>> 6] & (1L << (bit & 63))) == 0L) {
                            continue;
                        }
                        ProceduralMaterialPayload.CellMaterial material = materialPayload.cellMaterial(
                                brick.localX + x, brick.localY + y, brick.localZ + z);
                        if (material == null || !material.hasEveryFace()) {
                            return false;
                        }
                    }
                }
            }
        }
        return true;
    }

    public int packedVersion() {
        return hasMaterialPayload() ? MATERIAL_PACKED_VERSION : PACKED_VERSION;
    }

    public int recordStrideBytes() {
        return RECORD_HEADER_BYTES + occupancyWordsPerBrick * Long.BYTES;
    }

    public int aabbOffsetBytes() {
        return hasMaterialPayload() ? MATERIAL_HEADER_BYTES : HEADER_BYTES;
    }

    public int recordOffsetBytes() {
        return align16(aabbOffsetBytes() + brickCount() * AABB_BYTES);
    }

    public int packedBytes() {
        int brickEnd = brickRecordEndBytes();
        if (!hasMaterialPayload()) {
            return brickEnd;
        }
        return faceLayerOffsetBytes()
                + materialPayload.layerCount() * ProceduralMaterialPayload.FACE_LAYER_RECORD_BYTES;
    }

    public int cellMaterialReferenceOffsetBytes() {
        return hasMaterialPayload() ? align16(brickRecordEndBytes()) : 0;
    }

    public int cellMaterialRecordOffsetBytes() {
        return hasMaterialPayload()
                ? align16(cellMaterialReferenceOffsetBytes()
                        + ProceduralMaterialPayload.CELL_REFERENCE_BYTES)
                : 0;
    }

    public int faceMaterialOffsetBytes() {
        return hasMaterialPayload()
                ? align16(cellMaterialRecordOffsetBytes()
                        + materialPayload.cellMaterialCount()
                                * ProceduralMaterialPayload.CELL_MATERIAL_RECORD_BYTES)
                : 0;
    }

    public int faceLayerOffsetBytes() {
        return hasMaterialPayload()
                ? align16(faceMaterialOffsetBytes()
                        + materialPayload.faceMaterialCount()
                                * ProceduralMaterialPayload.FACE_MATERIAL_RECORD_BYTES)
                : 0;
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
        data.putInt(packedVersion());
        data.putInt(brickSize);
        data.putInt(brickCount());
        data.putInt(occupancyWordsPerBrick);
        data.putInt(aabbOffsetBytes());
        data.putInt(recordOffsetBytes());
        data.putInt(recordStrideBytes());
        if (hasMaterialPayload()) {
            data.putInt(cellMaterialReferenceOffsetBytes());
            data.putInt(cellMaterialRecordOffsetBytes());
            data.putInt(faceMaterialOffsetBytes());
            data.putInt(faceLayerOffsetBytes());
            data.putInt(materialPayload.cellMaterialCount());
            data.putInt(materialPayload.faceMaterialCount());
            data.putInt(materialPayload.layerCount());
            data.putInt(packedBytes());
        }

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
        if (hasMaterialPayload()) {
            materialPayload.writeTables(
                    data,
                    cellMaterialReferenceOffsetBytes(),
                    cellMaterialRecordOffsetBytes(),
                    faceMaterialOffsetBytes(),
                    faceLayerOffsetBytes());
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

    private int brickRecordEndBytes() {
        return recordOffsetBytes() + brickCount() * recordStrideBytes();
    }

    private static void validateMaterialCells(
            OccupancySource source,
            ProceduralMaterialPayload materialPayload) {
        if (materialPayload.isEmpty()) {
            return;
        }
        for (int y = 0; y < SECTION_SIZE; y++) {
            for (int z = 0; z < SECTION_SIZE; z++) {
                for (int x = 0; x < SECTION_SIZE; x++) {
                    if (materialPayload.hasCell(x, y, z) && !source.occupied(x, y, z)) {
                        throw new IllegalArgumentException(
                                "Procedural material metadata exists for an unoccupied cell at "
                                        + x + "," + y + "," + z);
                    }
                }
            }
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
