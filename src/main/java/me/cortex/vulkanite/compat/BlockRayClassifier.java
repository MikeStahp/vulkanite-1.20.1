package me.cortex.vulkanite.compat;

import static me.cortex.vulkanite.compat.BlockRayClassification.Representation.*;

/** Conservative classifier. Unknown/modded geometry always falls back to triangles. */
public final class BlockRayClassifier {
    private BlockRayClassifier() {}

    public record Traits(boolean air, boolean regularOpaqueFullCube, boolean fluid,
            boolean blockEntity, boolean cutout, boolean translucent, boolean multipart,
            boolean modded, boolean emissive) {}

    public static BlockRayClassification classify(Traits t) {
        if (t.air()) return new BlockRayClassification(NONE, NONE, NONE, false, false, false);
        boolean special = t.fluid() || t.blockEntity() || t.cutout() || t.translucent()
                || t.multipart() || t.modded();
        boolean voxelShadow = t.regularOpaqueFullCube() && !special;
        BlockRayClassification result = new BlockRayClassification(
                voxelShadow ? VOXEL : TRIANGLE,
                TRIANGLE, // Phase 10 owns any future removal of reflection triangles.
                voxelShadow ? VOXEL : TRIANGLE,
                t.emissive(), t.multipart() || t.blockEntity(), t.modded());
        result.validate(false, true);
        return result;
    }
}
