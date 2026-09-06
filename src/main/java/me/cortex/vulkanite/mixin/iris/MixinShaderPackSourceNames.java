package me.cortex.vulkanite.mixin.iris;

import com.google.common.collect.ImmutableList;
import me.cortex.vulkanite.compat.RaytracingShaderSourceNames;
import net.irisshaders.iris.shaderpack.include.ShaderPackSourceNames;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = ShaderPackSourceNames.class, remap = false)
public class MixinShaderPackSourceNames {
    @Inject(method = "findPotentialStarts", at = @At("RETURN"), cancellable = true)
    private static void injectRaytraceShaderNames(CallbackInfoReturnable<ImmutableList<String>> cir) {
        cir.setReturnValue(ImmutableList.copyOf(
                RaytracingShaderSourceNames.appendTo(cir.getReturnValue())));
    }
}
