package me.cortex.vulkanite.acceleration.voxel;

import org.junit.jupiter.api.Test;

import java.nio.ByteOrder;
import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProceduralMaterialPayloadTest {
    @Test
    void packsAlignedVersionTwoTablesAndCompleteFaceCoverage() {
        long[] occupancy = new long[64];
        set(occupancy, 2, 3, 4);
        ProceduralMaterialPayload payload = ProceduralMaterialPayload.builder()
                .setCell(2, 3, 4, cellMaterial(true))
                .build();

        VoxelBrickGeometry geometry = VoxelBrickGeometry.fromOpacityMask(
                occupancy, 8, payload);
        var packed = geometry.pack().order(ByteOrder.nativeOrder());

        assertEquals(VoxelBrickGeometry.MATERIAL_PACKED_VERSION, geometry.packedVersion());
        assertEquals(VoxelBrickGeometry.MATERIAL_PACKED_VERSION, packed.getInt(4));
        assertEquals(0, geometry.cellMaterialReferenceOffsetBytes() & 15);
        assertEquals(0, geometry.cellMaterialRecordOffsetBytes() & 15);
        assertEquals(0, geometry.faceMaterialOffsetBytes() & 15);
        assertEquals(0, geometry.faceLayerOffsetBytes() & 15);
        assertEquals(geometry.packedBytes(), packed.getInt(60));
        assertTrue(geometry.hasMaterialPayload());
        assertTrue(geometry.hasCompleteMaterialPayload());
    }

    @Test
    void incompleteCellMaterialKeepsProductionReflectionFallback() {
        long[] occupancy = new long[64];
        set(occupancy, 0, 0, 0);
        ProceduralMaterialPayload payload = ProceduralMaterialPayload.builder()
                .setCell(0, 0, 0, cellMaterial(false))
                .build();

        VoxelBrickGeometry geometry = VoxelBrickGeometry.fromOpacityMask(
                occupancy, 4, payload);

        assertTrue(geometry.hasMaterialPayload());
        assertFalse(geometry.hasCompleteMaterialPayload());
    }

    private static ProceduralMaterialPayload.CellMaterial cellMaterial(boolean everyFace) {
        var uv00 = new ProceduralMaterialPayload.Uv(0.1f, 0.2f);
        var uv10 = new ProceduralMaterialPayload.Uv(0.3f, 0.2f);
        var uv11 = new ProceduralMaterialPayload.Uv(0.3f, 0.4f);
        var uv01 = new ProceduralMaterialPayload.Uv(0.1f, 0.4f);
        var layer = new ProceduralMaterialPayload.FaceLayer(
                uv00, uv10, uv11, uv01, 0xff7f3fff, 1);
        var face = new ProceduralMaterialPayload.FaceMaterial(0x7f00007f, 0, layer);
        Map<ProceduralMaterialPayload.Face, ProceduralMaterialPayload.FaceMaterial> faces =
                new EnumMap<>(ProceduralMaterialPayload.Face.class);
        faces.put(ProceduralMaterialPayload.Face.POSITIVE_X, face);
        if (everyFace) {
            for (ProceduralMaterialPayload.Face direction : ProceduralMaterialPayload.Face.values()) {
                faces.put(direction, face);
            }
        }
        return ProceduralMaterialPayload.CellMaterial.of(7, 11, 0.0f, 0, faces);
    }

    private static void set(long[] mask, int x, int y, int z) {
        int bit = ((y * 16 + z) * 16) + x;
        mask[bit >>> 6] |= 1L << (bit & 63);
    }
}
