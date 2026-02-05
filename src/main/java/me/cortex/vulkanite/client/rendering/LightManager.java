package me.cortex.vulkanite.client.rendering;

import me.cortex.vulkanite.client.Vulkanite;
import me.cortex.vulkanite.client.rendering.Light;
import me.cortex.vulkanite.compat.ILightHolder;
import me.cortex.vulkanite.lib.base.VContext;
import me.cortex.vulkanite.lib.base.VRef;
import me.cortex.vulkanite.lib.memory.VBuffer;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSection;
import net.minecraft.client.MinecraftClient;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryUtil;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.lwjgl.vulkan.VK10.*;

public class LightManager {
    public static final int LIGHT_SSBO_BINDING = 10;
    private static final int MAX_LIGHTS = 65536; // 64k lights
    private static final int LIGHT_SIZE = 32; // 32 bytes per light
    private static final int HEADER_SIZE = 16; // 16 bytes header
    private static final long BUFFER_SIZE = HEADER_SIZE + (long)MAX_LIGHTS * LIGHT_SIZE;

    private final VContext ctx;
    private VRef<VBuffer> lightBuffer;
    private boolean isDestroyed = false;

    public LightManager(VContext ctx) {
        this.ctx = ctx;
        this.lightBuffer = ctx.memory.createBuffer(
            BUFFER_SIZE,
            VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
            VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT
        );
        this.lightBuffer.get().setDebugUtilsObjectName("LightData SSBO");
    }

    public VRef<VBuffer> getBuffer() {
        if (isDestroyed || lightBuffer == null) return null;
        return lightBuffer.addRef();
    }

    public void destroy() {
        if (isDestroyed) return;
        isDestroyed = true;
        lightBuffer.close();
        lightBuffer = null;
    }

    public void update(Collection<RenderSection> visibleSections) {
        if (isDestroyed || lightBuffer == null) return;

        List<Light> allLights = new ArrayList<>();

        // 1. Sun/Sky Light
        addSkyLight(allLights);

        // 2. Block Lights
        if (visibleSections != null) {
            for (RenderSection section : visibleSections) {
                List<Light> sectionLights = ((ILightHolder) section).getLights();
                if (sectionLights != null) {
                    allLights.addAll(sectionLights);
                }
            }
        }

        // Limit
        if (allLights.size() > MAX_LIGHTS) {
            allLights = allLights.subList(0, MAX_LIGHTS);
        }

        // Upload
        long ptr = lightBuffer.get().map();
        // Write count
        MemoryUtil.memPutInt(ptr, allLights.size());
        // Padding
        MemoryUtil.memPutInt(ptr + 4, 0);
        MemoryUtil.memPutInt(ptr + 8, 0);
        MemoryUtil.memPutInt(ptr + 12, 0);

        long offset = ptr + HEADER_SIZE;
        for (Light light : allLights) {
            MemoryUtil.memPutFloat(offset + 0, light.position().x);
            MemoryUtil.memPutFloat(offset + 4, light.position().y);
            MemoryUtil.memPutFloat(offset + 8, light.position().z);

            MemoryUtil.memPutFloat(offset + 12, light.radius());

            MemoryUtil.memPutFloat(offset + 16, light.color().x);
            MemoryUtil.memPutFloat(offset + 20, light.color().y);
            MemoryUtil.memPutFloat(offset + 24, light.color().z);

            MemoryUtil.memPutInt(offset + 28, light.type());

            offset += LIGHT_SIZE;
        }

        lightBuffer.get().unmap();
    }

    private void addSkyLight(List<Light> lights) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null) return;

        float angle = mc.world.getSkyAngleRadians(mc.getTickDelta());
        // Simple sun position logic
        float x = (float) -Math.sin(angle);
        float y = (float) Math.cos(angle);

        Vector3f sunPos = new Vector3f(x * 1000.0f, y * 1000.0f, 0.0f);

        // Basic day/night intensity
        float intensity = Math.max(0.0f, y);

        lights.add(new Light(sunPos, 10000.0f * intensity, new Vector3f(1.0f, 1.0f, 1.0f), Light.TYPE_SKY));
    }
}
