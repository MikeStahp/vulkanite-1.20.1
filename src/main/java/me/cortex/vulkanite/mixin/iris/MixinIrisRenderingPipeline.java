package me.cortex.vulkanite.mixin.iris;

import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap.Entry;
import me.cortex.vulkanite.client.ShaderpackSettingsHandler;
import me.cortex.vulkanite.client.Vulkanite;
import me.cortex.vulkanite.client.rendering.VulkanPipeline;
import me.cortex.vulkanite.compat.*;
import me.cortex.vulkanite.lib.base.VContext;
import me.cortex.vulkanite.lib.base.VRef;
import me.cortex.vulkanite.lib.memory.VGImage;
import me.cortex.vulkanite.lib.memory.VImage;
import me.cortex.vulkanite.lib.other.VImageView;
import me.cortex.vulkanite.lib.other.DeviceLostException;
import net.irisshaders.iris.gl.buffer.ShaderStorageBuffer;
import net.irisshaders.iris.gl.texture.TextureAccess;
import net.irisshaders.iris.gl.buffer.ShaderStorageBufferHolder;
import net.irisshaders.iris.pipeline.CustomTextureManager;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import net.irisshaders.iris.targets.RenderTargets;
import net.irisshaders.iris.uniforms.CelestialUniforms;
import net.minecraft.client.render.Camera;
import net.minecraft.client.MinecraftClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Mixin(value = IrisRenderingPipeline.class, remap = false)
public class MixinIrisRenderingPipeline {
    private static final Logger LOGGER = LoggerFactory.getLogger(MixinIrisRenderingPipeline.class);

    @Shadow
    @Final
    private RenderTargets renderTargets;
    @Shadow
    @Final
    private CustomTextureManager customTextureManager;
    @Shadow
    private ShaderStorageBufferHolder shaderStorageBufferHolder;

    @Shadow
    @Final
    private float sunPathRotation;
    @Unique
    private VContext ctx;
    @Unique
    private VulkanPipeline pipeline;
    @Unique
    private boolean loggedGbufferFlipState;

    @Unique
    private List<VulkanPipeline.CustomTexture> getCustomTextures() {
        Object2ObjectMap<String, TextureAccess> texturesBinary = customTextureManager.getIrisCustomTextures();
        Object2ObjectMap<String, TextureAccess> texturesPNGs = customTextureManager
                .getCustomTextureIdMap(TextureStage.GBUFFERS_AND_SHADOW);

        List<Entry<String, TextureAccess>> entryList = new ArrayList<>();
        entryList.addAll(texturesBinary.object2ObjectEntrySet());
        entryList.addAll(texturesPNGs.object2ObjectEntrySet());

        entryList.sort(Comparator.comparing(Entry::getKey));

        return entryList.stream()
                .map(entry -> new VulkanPipeline.CustomTexture(entry.getKey(), ((IVGImage) entry.getValue()).getVGImage()))
                .toList();
    }

    @Inject(method = "<init>", at = @At("TAIL"))
    private void injectRTShader(ProgramSet set, CallbackInfo ci) {
        ctx = Vulkanite.INSTANCE.getCtx();
        var passes = ((IGetRaytracingSource) set).getRaytracingSource();
        LOGGER.info("Creating RT pipeline, passes: {}", passes != null ? passes.length : "null");
        RaytracingShaderSet[] rtShaderPasses = null;
        if (passes != null) {
            rtShaderPasses = new RaytracingShaderSet[passes.length];
            for (int i = 0; i < passes.length; i++) {
                rtShaderPasses[i] = new RaytracingShaderSet(ctx, passes[i]);
                LOGGER.debug("Created RT shader set {}", i);
            }
        } else {
            LOGGER.info("No ray tracing shaders available, skipping RT pipeline creation");
        }
        // Still create this, later down the line we might add Vulkan compute pipelines
        // or mesh shading, etc.
        pipeline = new VulkanPipeline(ctx, Vulkanite.INSTANCE.getAccelerationManager(), rtShaderPasses,
                set.getPackDirectives().getBufferObjects().keySet().toArray(new int[0]), getCustomTextures());
        ShaderpackSettingsHandler.applyShaderpackSettings((IrisRenderingPipeline) (Object) this);
        ShaderpackSettingsHandler.detectAndApplyShaderpack(pipeline);
    }

    @Inject(
            method = "finalizeLevelRendering",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/irisshaders/iris/pipeline/CompositeRenderer;renderAll()V",
                    shift = At.Shift.AFTER))
    private void finalizeLevelRendering(CallbackInfo ci) {
        // Keep OpenGL responsible for the complete compatibility frame, including
        // entities, fluids, particles, and modded translucent render layers. Run
        // after Iris composites so they cannot overwrite the Vulkan result before
        // Iris copies colortex0 into the Minecraft framebuffer.
        Camera camera = MinecraftClient.getInstance().gameRenderer.getCamera();
        runRayTracing(camera);
    }

    @Unique
    private void runRayTracing(Camera camera) {
        if (pipeline == null) {
            return;
        }
        ShaderpackSettingsHandler.detectAndApplyShaderpack(pipeline);

        var prof = MinecraftClient.getInstance().getProfiler();
        prof.push("vulkanite_render_rt");

        ShaderStorageBuffer[] buffers = new ShaderStorageBuffer[0];

        if (shaderStorageBufferHolder != null) {
            buffers = ((ShaderStorageBufferHolderAccessor) shaderStorageBufferHolder).getBuffers();
        }

        var flippedTargets = ((IrisRenderingPipeline) (Object) this).getFlippedAfterTranslucent();
        List<VRef<VGImage>> outImgs = new ArrayList<>();
        for (int i = 0; i < renderTargets.getRenderTargetCount(); i++) {
            IRenderTargetVkGetter target = (IRenderTargetVkGetter) renderTargets.getOrCreate(i);
            outImgs.add(flippedTargets.contains(i) ? target.getAlt() : target.getMain());
        }

        VRef<VImageView>[] gbufferViews = createGbufferViews();
        MixinCelestialUniforms celestialUniforms = (MixinCelestialUniforms) (Object) new CelestialUniforms(
                this.sunPathRotation);

        if (camera == null) {
            camera = MinecraftClient.getInstance().gameRenderer.getCamera();
        }

        try {
            pipeline.renderPostShadows(outImgs, camera, buffers, celestialUniforms, gbufferViews);
        } catch (DeviceLostException e) {
            LOGGER.error("Device lost during rendering: {}", e.getMessage());
            Vulkanite.IS_ENABLED = false;
            LOGGER.error("Disabled due to device loss. Falling back to standard rendering.");
        } finally {
            for (var ref : outImgs) {
                ref.close();
            }
            for (var ref : gbufferViews) {
                if (ref != null) {
                    ref.close();
                }
            }
        }

        prof.pop();
    }

    @Unique
    @SuppressWarnings("unchecked")
    private VRef<VImageView>[] createGbufferViews() {
        VRef<VImageView>[] views = new VRef[5];
        try {
            var requirements = pipeline.getPipelineRequirements();
            if (requirements.needsAlbedo()) {
                views[0] = createRenderTargetView(1, "albedo");
            }
            if (requirements.needsMaterial()) {
                views[1] = createRenderTargetView(2, "material");
            }
            if (requirements.needsNormal()) {
                views[2] = createRenderTargetView(3, "normal");
            }
            if (requirements.needsWorldPos()) {
                views[3] = createRenderTargetView(4, "world position");
            }
            if (requirements.needsExtra()) {
                views[4] = createRenderTargetView(5, "extra");
            }
        } catch (Exception e) {
            LOGGER.warn("Could not create G-buffer views: {}", e.getMessage());
        }
        return views;
    }

    @Unique
    private VRef<VImageView> createRenderTargetView(int targetIndex, String label) {
        IRenderTargetVkGetter target = (IRenderTargetVkGetter) renderTargets.getOrCreate(targetIndex);
        var flippedTargets = ((IrisRenderingPipeline) (Object) this).getFlippedAfterTranslucent();
        if (!loggedGbufferFlipState) {
            loggedGbufferFlipState = true;
            LOGGER.info("[Vulkanite] Iris G-buffer flipped-after-translucent targets: {}", flippedTargets);
        }
        boolean flipped = flippedTargets.contains(targetIndex);
        VRef<VGImage> image = flipped ? target.getAlt() : target.getMain();
        try {
            if (image == null || image.get() == null) {
                LOGGER.warn("G-buffer {} colortex{} is null or invalid", label, targetIndex);
                return null;
            }

            LOGGER.trace("G-buffer {} colortex{} bound from {}: {}x{}",
                    label, targetIndex, flipped ? "alt" : "main", image.get().width, image.get().height);
            try (VRef<VImage> imageRef = new VRef<>(image.get())) {
                return VImageView.create(Vulkanite.INSTANCE.getCtx(), imageRef);
            }
        } finally {
            if (image != null) {
                image.close();
            }
        }
    }

    @Inject(method = "shouldDisableVanillaEntityShadows", at = @At("HEAD"), cancellable = true)
    private void shouldDisableVanillaEntityShadows(CallbackInfoReturnable<Boolean> ci) {
        if (pipeline != null) {
            ci.setReturnValue(true);
        }
    }

    @Inject(method = "destroyShaders", at = @At("TAIL"))
    private void destroy(CallbackInfo ci) {
        if (ctx == null)
            return;

        ctx.cmd.waitQueueIdle(0);
        pipeline.destroy();
        pipeline = null;
    }
}
