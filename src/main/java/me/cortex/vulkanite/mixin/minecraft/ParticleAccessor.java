package me.cortex.vulkanite.mixin.minecraft;

import net.minecraft.client.particle.Particle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Particle.class)
public interface ParticleAccessor {
    @Accessor("x")
    double vulkanite$getX();

    @Accessor("y")
    double vulkanite$getY();

    @Accessor("z")
    double vulkanite$getZ();
}
