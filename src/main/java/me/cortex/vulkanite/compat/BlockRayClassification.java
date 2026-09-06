package me.cortex.vulkanite.compat;

import java.util.EnumSet;

/** Per-ray-role ownership selected while a chunk section is scanned. */
public record BlockRayClassification(
        Representation shadowRepresentation,
        Representation reflectionRepresentation,
        Representation giRepresentation,
        boolean emissive,
        boolean composite,
        boolean unknownFallback) {

    public enum Representation { NONE, VOXEL, TRIANGLE }

    public BlockRayClassification {
        if (shadowRepresentation == null || reflectionRepresentation == null || giRepresentation == null) {
            throw new NullPointerException("Ray representations cannot be null");
        }
        if (shadowRepresentation == Representation.NONE
                && (reflectionRepresentation == Representation.VOXEL || giRepresentation == Representation.VOXEL)) {
            throw new IllegalArgumentException("Voxel ownership requires voxel shadow ownership");
        }
    }

    public void validate(boolean air, boolean visible) {
        if (air && shadowRepresentation != Representation.NONE) {
            throw new IllegalStateException("Air cannot own shadow geometry");
        }
        if (visible && reflectionRepresentation == Representation.NONE) {
            throw new IllegalStateException("Visible geometry lost its reflection representation");
        }
    }

    public EnumSet<Flag> flags() {
        EnumSet<Flag> result = EnumSet.noneOf(Flag.class);
        add(result, shadowRepresentation, Flag.VOXEL_SHADOW, Flag.TRIANGLE_SHADOW);
        add(result, reflectionRepresentation, Flag.VOXEL_REFLECTION, Flag.TRIANGLE_REFLECTION);
        add(result, giRepresentation, Flag.VOXEL_GI, Flag.TRIANGLE_GI);
        if (emissive) result.add(Flag.EMISSIVE);
        if (composite) result.add(Flag.COMPOSITE_GEOMETRY);
        if (shadowRepresentation == Representation.NONE) result.add(Flag.NON_OCCLUDING);
        return result;
    }

    private static void add(EnumSet<Flag> flags, Representation value, Flag voxel, Flag triangle) {
        if (value == Representation.VOXEL) flags.add(voxel);
        if (value == Representation.TRIANGLE) flags.add(triangle);
    }

    public enum Flag {
        VOXEL_SHADOW, TRIANGLE_SHADOW, VOXEL_REFLECTION, TRIANGLE_REFLECTION,
        VOXEL_GI, TRIANGLE_GI, EMISSIVE, NON_OCCLUDING, COMPOSITE_GEOMETRY
    }
}
