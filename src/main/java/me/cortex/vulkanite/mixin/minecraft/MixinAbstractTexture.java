package me.cortex.vulkanite.mixin.minecraft;

import me.cortex.vulkanite.compat.IVGImage;
import me.cortex.vulkanite.lib.base.VRef;
import me.cortex.vulkanite.lib.memory.VGImage;
import net.minecraft.client.texture.AbstractTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AbstractTexture.class)
public class MixinAbstractTexture implements IVGImage {
    @Shadow protected int glId;
    @Unique private VRef<VGImage> vgImage;

    @Override
    public void setVGImage(VRef<VGImage> image) {
        if (this.vgImage == image) {
            return;
        }
        VRef<VGImage> oldImage = this.vgImage;
        this.vgImage = image;
        safeClose(oldImage);
    }

    @Override
    public VRef<VGImage> getVGImage() {
        if (vgImage == null) {
            return null;
        }
        try {
            return vgImage.addRef();
        } catch (NullPointerException e) {
            vgImage = null;
            glId = -1;
            return null;
        }
    }

    @Inject(method = "getGlId", at = @At("HEAD"), cancellable = true)
    private void redirectGetId(CallbackInfoReturnable<Integer> cir) {
        if (vgImage != null) {
            if (glId != -1) {
                throw new IllegalStateException("glId != -1 while VGImage is set");
            }
            try {
                cir.setReturnValue(vgImage.get().glId);
                cir.cancel();
            } catch (NullPointerException e) {
                safeClose(vgImage);
                vgImage = null;
                glId = -1;
            }
        }
    }

    @Inject(method = "clearGlId", at = @At("HEAD"), cancellable = true)
    private void redirectClear(CallbackInfo ci) {
        if (vgImage != null) {
            setVGImage(null);
            glId = -1;
            ci.cancel();
        }
    }

    @Unique
    private static void safeClose(VRef<?> ref) {
        if (ref == null) {
            return;
        }
        try {
            ref.close();
        } catch (NullPointerException ignored) {
            // The referenced image may already have been collected during reload.
        }
    }
}
