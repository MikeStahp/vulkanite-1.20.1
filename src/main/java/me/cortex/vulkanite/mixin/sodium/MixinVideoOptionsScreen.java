package me.cortex.vulkanite.mixin.sodium;

import me.cortex.vulkanite.client.gui.sodium.SodiumDLSSPage;
import me.jellysquid.mods.sodium.client.gui.SodiumOptionsGUI;
import me.jellysquid.mods.sodium.client.gui.options.OptionPage;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Mixin to integrate DLSS options into Sodium's video options menu.
 * 
 * This mixin injects the DLSS option page into Sodium's options GUI.
 */
@Mixin(value = SodiumOptionsGUI.class, remap = false)
public abstract class MixinVideoOptionsScreen extends Screen {
    
    @Shadow @Final private List<OptionPage> pages;

    protected MixinVideoOptionsScreen(Text title) {
        super(title);
    }
    
    @Inject(method = "<init>", at = @At("RETURN"))
    private void onConstruct(Screen parent, CallbackInfo ci) {
        // Add our DLSS page to the list
        this.pages.add(SodiumDLSSPage.create());
    }
}
