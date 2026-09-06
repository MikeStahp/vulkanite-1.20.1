package me.cortex.vulkanite.compat;

import org.junit.jupiter.api.Test;
import static me.cortex.vulkanite.compat.BlockRayClassification.Representation.*;
import static org.junit.jupiter.api.Assertions.*;

class BlockRayClassifierTest {
    private static BlockRayClassifier.Traits traits(boolean air, boolean cube, boolean fluid,
            boolean entity, boolean cutout, boolean translucent, boolean multipart, boolean modded, boolean emissive) {
        return new BlockRayClassifier.Traits(air, cube, fluid, entity, cutout, translucent, multipart, modded, emissive);
    }

    @Test void regularCubeUsesVoxelOnlyForShadowAndGi() {
        var c = BlockRayClassifier.classify(traits(false, true, false, false, false, false, false, false, false));
        assertEquals(VOXEL, c.shadowRepresentation());
        assertEquals(VOXEL, c.giRepresentation());
        assertEquals(TRIANGLE, c.reflectionRepresentation());
    }

    @Test void specialAndUnknownGeometrySafelyFallsBackToTriangles() {
        for (var t : new BlockRayClassifier.Traits[] {
                traits(false, false, false, false, true, false, false, false, false),
                traits(false, true, true, false, false, true, false, false, false),
                traits(false, true, false, true, false, false, true, false, false),
                traits(false, true, false, false, false, false, false, true, false) }) {
            var c = BlockRayClassifier.classify(t);
            assertEquals(TRIANGLE, c.shadowRepresentation());
            assertEquals(TRIANGLE, c.reflectionRepresentation());
        }
    }

    @Test void airHasNoRepresentationAndEmissionIsIndependent() {
        var air = BlockRayClassifier.classify(traits(true, false, false, false, false, false, false, false, false));
        assertEquals(NONE, air.shadowRepresentation());
        assertEquals(NONE, air.reflectionRepresentation());
        var lamp = BlockRayClassifier.classify(traits(false, true, false, false, false, false, false, false, true));
        assertTrue(lamp.emissive());
        assertEquals(VOXEL, lamp.shadowRepresentation());
    }
}
