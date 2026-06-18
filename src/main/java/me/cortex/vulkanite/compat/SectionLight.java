package me.cortex.vulkanite.compat;

public record SectionLight(
        int packedBlockPos,
        int packedRgbEmission,
        short radius,
        short flags) {
    public static final short FLAG_FLUID = 1 << 0;
    public static final short FLAG_BLOCK_ENTITY = 1 << 1;
    public static final short FLAG_MODDED = 1 << 2;
    public static final short FLAG_REDSTONE = 1 << 3;
    public static final short FLAG_SOUL = 1 << 4;

    public static int packLocalBlockPos(int x, int y, int z) {
        return (x & 15) | ((y & 15) << 4) | ((z & 15) << 8);
    }

    public static int packRgbEmission(int red, int green, int blue, int emission) {
        return (clampByte(emission) << 24)
                | (clampByte(red) << 16)
                | (clampByte(green) << 8)
                | clampByte(blue);
    }

    public int localX() {
        return packedBlockPos & 15;
    }

    public int localY() {
        return (packedBlockPos >> 4) & 15;
    }

    public int localZ() {
        return (packedBlockPos >> 8) & 15;
    }

    public int emission() {
        return (packedRgbEmission >>> 24) & 0xFF;
    }

    private static int clampByte(int value) {
        return Math.max(0, Math.min(255, value));
    }
}
