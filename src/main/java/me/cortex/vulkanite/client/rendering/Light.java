package me.cortex.vulkanite.client.rendering;

import org.joml.Vector3f;

public record Light(Vector3f position, float radius, Vector3f color, int type) {
    public static final int TYPE_BLOCK = 0;
    public static final int TYPE_SKY = 1;
}
