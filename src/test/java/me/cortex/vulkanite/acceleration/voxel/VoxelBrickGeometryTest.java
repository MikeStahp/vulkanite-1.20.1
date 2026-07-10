package me.cortex.vulkanite.acceleration.voxel;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoxelBrickGeometryTest {
    @Test
    void separatesDistantOccupiedCellsIntoEightCellBricks() {
        long[] mask = new long[64];
        set(mask, 0, 0, 0);
        set(mask, 15, 15, 15);

        VoxelBrickGeometry geometry = VoxelBrickGeometry.fromOpacityMask(mask, 8);

        assertEquals(2, geometry.brickCount());
        assertEquals(2, geometry.solidBlockCount());
        assertEquals(8, geometry.occupancyWordsPerBrick());
        assertTrue(geometry.isOccupied(0, 0, 0, 0));
        assertTrue(geometry.isOccupied(1, 7, 7, 7));
        assertFalse(geometry.isOccupied(0, 7, 7, 7));
    }

    @Test
    void emitsVulkanAabbsAndAlignedPayloadRecords() {
        long[] mask = new long[64];
        set(mask, 9, 2, 12);
        VoxelBrickGeometry geometry = VoxelBrickGeometry.fromOpacityMask(mask, 8);

        ByteBuffer packed = geometry.pack().order(ByteOrder.nativeOrder());

        assertEquals(geometry.packedBytes(), packed.remaining());
        assertEquals(0, geometry.recordOffsetBytes() & 15);
        int aabb = geometry.aabbOffsetBytes();
        assertEquals(8.0f, packed.getFloat(aabb));
        assertEquals(0.0f, packed.getFloat(aabb + 4));
        assertEquals(8.0f, packed.getFloat(aabb + 8));
        assertEquals(16.0f, packed.getFloat(aabb + 12));
        assertEquals(8.0f, packed.getFloat(aabb + 16));
        assertEquals(16.0f, packed.getFloat(aabb + 20));
    }

    @Test
    void rejectsUnsupportedBrickSizes() {
        assertThrows(IllegalArgumentException.class,
                () -> VoxelBrickGeometry.fromOpacityMask(new long[64], 6));
    }

    @Test
    void supportsFourEightAndSixteenCellBrickLayouts() {
        long[] fullMask = new long[64];
        java.util.Arrays.fill(fullMask, -1L);

        VoxelBrickGeometry four = VoxelBrickGeometry.fromOpacityMask(fullMask, 4);
        VoxelBrickGeometry eight = VoxelBrickGeometry.fromOpacityMask(fullMask, 8);
        VoxelBrickGeometry sixteen = VoxelBrickGeometry.fromOpacityMask(fullMask, 16);

        assertEquals(64, four.brickCount());
        assertEquals(1, four.occupancyWordsPerBrick());
        assertEquals(8, eight.brickCount());
        assertEquals(8, eight.occupancyWordsPerBrick());
        assertEquals(1, sixteen.brickCount());
        assertEquals(64, sixteen.occupancyWordsPerBrick());
        assertEquals(4096, four.solidBlockCount());
        assertEquals(4096, eight.solidBlockCount());
        assertEquals(4096, sixteen.solidBlockCount());
    }

    private static void set(long[] mask, int x, int y, int z) {
        int bit = ((y * 16 + z) * 16) + x;
        mask[bit >>> 6] |= 1L << (bit & 63);
    }
}
