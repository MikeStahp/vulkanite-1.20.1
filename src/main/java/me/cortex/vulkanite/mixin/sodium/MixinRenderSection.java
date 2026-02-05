package me.cortex.vulkanite.mixin.sodium;

import me.cortex.vulkanite.client.Vulkanite;
import me.cortex.vulkanite.client.rendering.Light;
import me.cortex.vulkanite.compat.ILightHolder;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(value = RenderSection.class, remap = false)
public class MixinRenderSection implements ILightHolder {
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

    @Inject(method = "delete", at = @At("HEAD"))
    private void onSectionDelete(CallbackInfo ci) {
        Vulkanite.INSTANCE.sectionRemove((RenderSection)(Object)this);
    }
}
