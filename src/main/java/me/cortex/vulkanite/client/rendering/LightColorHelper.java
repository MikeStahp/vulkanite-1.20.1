package me.cortex.vulkanite.client.rendering;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import org.joml.Vector3f;

public class LightColorHelper {
    private static final Vector3f COLOR_DEFAULT = new Vector3f(1.0f, 0.9f, 0.8f); // Warm white
    private static final Vector3f COLOR_SOUL = new Vector3f(0.5f, 0.8f, 1.0f); // Soul fire blue
    private static final Vector3f COLOR_REDSTONE = new Vector3f(1.0f, 0.0f, 0.0f); // Red
    private static final Vector3f COLOR_LAVA = new Vector3f(1.0f, 0.5f, 0.0f); // Orange
    private static final Vector3f COLOR_ENDER = new Vector3f(0.8f, 0.0f, 1.0f); // Purple
    private static final Vector3f COLOR_SCULK = new Vector3f(0.0f, 0.8f, 1.0f); // Cyanish
    private static final Vector3f COLOR_WHITE = new Vector3f(1.0f, 1.0f, 1.0f);

    public static Vector3f getColor(BlockState state) {
        var block = state.getBlock();

        if (block == Blocks.SOUL_TORCH || block == Blocks.SOUL_LANTERN || block == Blocks.SOUL_FIRE || block == Blocks.SOUL_CAMPFIRE) {
            return COLOR_SOUL;
        }
        if (block == Blocks.REDSTONE_TORCH || block == Blocks.REDSTONE_WALL_TORCH) {
            return COLOR_REDSTONE;
        }
        if (block == Blocks.LAVA || block == Blocks.MAGMA_BLOCK) {
            return COLOR_LAVA;
        }
        if (block == Blocks.ENDER_CHEST || block == Blocks.END_ROD) {
            return COLOR_ENDER;
        }
        if (block == Blocks.SCULK_SENSOR || block == Blocks.SCULK_CATALYST || block == Blocks.SCULK_SHRIEKER) {
            return COLOR_SCULK;
        }
        if (block == Blocks.SEA_LANTERN || block == Blocks.BEACON || block == Blocks.GLOWSTONE) {
            return COLOR_WHITE;
        }

        // Default for normal torches, lanterns, campfires, etc.
        return COLOR_DEFAULT;
    }
}
