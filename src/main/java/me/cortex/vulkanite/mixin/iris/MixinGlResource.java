package me.cortex.vulkanite.mixin.iris;

import net.irisshaders.iris.gl.GlResource;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = GlResource.class, remap = false)
public abstract class MixinGlResource {
    @Shadow private boolean isValid;

    @Shadow protected abstract void assertValid();

    @Unique private int newId;

    @Inject(method = "<init>", at=@At("TAIL"))
    private void onInit(int id, CallbackInfo ci) {
        this.newId = id;
        if (id == -1) {
            isValid = false;
        }
    }

    protected void setGlId(int id) {
        if (this.newId == -1) {
            this.newId = id;
            isValid = true;
        }
    }

    /**
     * Gets the OpenGL ID of this resource.
     * @author Cortex
     * @reason Intercept for Vulkanite
     * @return the OpenGL ID
     */
    @Overwrite
    protected int getGlId() {
        assertValid();
        return this.newId;
    }
}
