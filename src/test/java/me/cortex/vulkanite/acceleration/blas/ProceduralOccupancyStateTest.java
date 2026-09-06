package me.cortex.vulkanite.acceleration.blas;

import me.cortex.vulkanite.acceleration.voxel.ProceduralMaterialPayload;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProceduralOccupancyStateTest {
    @Test
    void resubmitsIdenticalMaskUntilPriorBuildIsInstalled() {
        ProceduralOccupancyState state = new ProceduralOccupancyState();
        long[] mask = occupiedMask();

        assertEquals(ProceduralBLASDisposition.REPLACE, state.record(mask, 1L, 8));
        assertEquals(ProceduralBLASDisposition.REPLACE, state.record(mask.clone(), 2L, 8));

        state.markInstalled(2L, ProceduralBLASDisposition.REPLACE);

        assertEquals(ProceduralBLASDisposition.RETAIN, state.record(mask.clone(), 3L, 8));
    }

    @Test
    void changedMaskRequestsReplacementAfterInstall() {
        ProceduralOccupancyState state = new ProceduralOccupancyState();
        long[] first = occupiedMask();
        long[] second = occupiedMask();
        second[1] = 1L;

        assertEquals(ProceduralBLASDisposition.REPLACE, state.record(first, 1L, 8));
        state.markInstalled(1L, ProceduralBLASDisposition.REPLACE);

        assertEquals(ProceduralBLASDisposition.REPLACE, state.record(second, 2L, 8));
    }

    @Test
    void emptyMaskClearsAndThenRetainsAfterInstall() {
        ProceduralOccupancyState state = new ProceduralOccupancyState();
        long[] empty = new long[64];

        assertEquals(ProceduralBLASDisposition.CLEAR, state.record(empty, 1L, 8));
        state.markInstalled(1L, ProceduralBLASDisposition.CLEAR);

        assertEquals(ProceduralBLASDisposition.RETAIN, state.record(empty.clone(), 2L, 8));
    }

    @Test
    void staleInstallDoesNotRetainPendingBuild() {
        ProceduralOccupancyState state = new ProceduralOccupancyState();
        long[] mask = occupiedMask();

        assertEquals(ProceduralBLASDisposition.REPLACE, state.record(mask, 10L, 8));
        state.markInstalled(9L, ProceduralBLASDisposition.REPLACE);

        assertEquals(ProceduralBLASDisposition.REPLACE, state.record(mask.clone(), 11L, 8));
    }

    @Test
    void changedBrickSizeRequestsReplacement() {
        ProceduralOccupancyState state = new ProceduralOccupancyState();
        long[] mask = occupiedMask();
        assertEquals(ProceduralBLASDisposition.REPLACE, state.record(mask, 1L, 8));
        state.markInstalled(1L, ProceduralBLASDisposition.REPLACE);
        assertEquals(ProceduralBLASDisposition.REPLACE, state.record(mask.clone(), 2L, 4));
    }

    @Test
    void changedMaterialPayloadRequestsReplacement() {
        ProceduralOccupancyState state = new ProceduralOccupancyState();
        long[] mask = occupiedMask();
        ProceduralMaterialPayload first = materialPayload(3);
        ProceduralMaterialPayload second = materialPayload(4);

        assertEquals(ProceduralBLASDisposition.REPLACE, state.record(mask, 1L, 8, first));
        state.markInstalled(1L, ProceduralBLASDisposition.REPLACE);
        assertEquals(ProceduralBLASDisposition.RETAIN, state.record(mask.clone(), 2L, 8, first));
        assertEquals(ProceduralBLASDisposition.REPLACE, state.record(mask.clone(), 3L, 8, second));
    }

    private static long[] occupiedMask() {
        long[] mask = new long[64];
        mask[0] = 1L;
        return mask;
    }

    private static ProceduralMaterialPayload materialPayload(int shaderBlockId) {
        var layer = new ProceduralMaterialPayload.FaceLayer(
                new ProceduralMaterialPayload.Uv(0.0f, 0.0f),
                new ProceduralMaterialPayload.Uv(1.0f, 0.0f),
                new ProceduralMaterialPayload.Uv(1.0f, 1.0f),
                new ProceduralMaterialPayload.Uv(0.0f, 1.0f),
                -1,
                0);
        var face = new ProceduralMaterialPayload.FaceMaterial(0, 0, layer);
        var material = ProceduralMaterialPayload.CellMaterial.of(
                shaderBlockId,
                0,
                0.0f,
                0,
                Map.of(ProceduralMaterialPayload.Face.POSITIVE_X, face));
        return ProceduralMaterialPayload.builder().setCell(0, 0, 0, material).build();
    }
}
