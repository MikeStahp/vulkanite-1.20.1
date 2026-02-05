package me.cortex.vulkanite.mixin.sodium.chunk;

import me.cortex.vulkanite.client.rendering.Light;
import me.cortex.vulkanite.compat.ILightHolder;
import me.jellysquid.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import java.util.List;

@Mixin(value = ChunkBuildOutput.class, remap = false)
public class MixinChunkBuildOutput implements ILightHolder {
    @Unique
    private List<Light> lights;

    @Override
    public void setLights(List<Light> lights) {
        this.lights = lights;
    }

    @Override
    public List<Light> getLights() {
        return lights;
    }
}
