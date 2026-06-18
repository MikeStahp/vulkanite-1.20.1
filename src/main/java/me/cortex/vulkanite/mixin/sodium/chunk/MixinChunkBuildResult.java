package me.cortex.vulkanite.mixin.sodium.chunk;

import me.cortex.vulkanite.compat.IAccelerationBuildResult;
import me.cortex.vulkanite.compat.ISectionLightBuildResult;
import me.cortex.vulkanite.compat.SectionLightTable;
import me.cortex.vulkanite.compat.SodiumGeometryBatch;
import me.jellysquid.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(value = ChunkBuildOutput.class, remap = false)
public class MixinChunkBuildResult implements IAccelerationBuildResult, ISectionLightBuildResult {
    @Unique private SodiumGeometryBatch vulkanite$geometry;
    @Unique private SectionLightTable vulkanite$sectionLights;

    @Override
    public void setAccelerationGeometry(SodiumGeometryBatch geometry) {
        this.vulkanite$geometry = geometry;
    }

    @Override
    public SodiumGeometryBatch getAccelerationGeometry() {
        return vulkanite$geometry;
    }

    @Override
    public void setSectionLights(SectionLightTable lights) {
        this.vulkanite$sectionLights = lights;
    }

    @Override
    public SectionLightTable getSectionLights() {
        return vulkanite$sectionLights;
    }
}
