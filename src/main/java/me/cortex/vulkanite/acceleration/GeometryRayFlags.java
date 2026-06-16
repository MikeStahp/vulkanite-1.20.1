package me.cortex.vulkanite.acceleration;

public enum GeometryRayFlags {
    OPAQUE(1),
    TRANSPARENT(1 << 1),
    ENTITY(1 << 2),
    WATER(1 << 3),
    PLAYER(1 << 7),
    ;
    private final int flag;

    GeometryRayFlags(int i) {
        flag = i;
    }

    public int mask() {
        return flag;
    }
}
