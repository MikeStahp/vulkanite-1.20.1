package me.cortex.vulkanite.mixin.iris;

import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap.Entry;
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
import net.irisshaders.iris.mixin.LevelRendererAccessor;
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
    private RaytracingShaderSet[] rtShaderPasses = null;
    @Unique
    private VContext ctx;
    @Unique
    private VulkanPipeline pipeline;

    @Unique
    private List<VRef<VGImage>> getCustomTextures() {
        Object2ObjectMap<String, TextureAccess> texturesBinary = customTextureManager.getIrisCustomTextures();
        Object2ObjectMap<String, TextureAccess> texturesPNGs = customTextureManager
                .getCustomTextureIdMap(TextureStage.GBUFFERS_AND_SHADOW);

        List<Entry<String, TextureAccess>> entryList = new ArrayList<>();
        entryList.addAll(texturesBinary.object2ObjectEntrySet());
        entryList.addAll(texturesPNGs.object2ObjectEntrySet());

        entryList.sort(Comparator.comparing(Entry::getKey));

        return entryList.stream()
                .map(entry -> ((IVGImage) entry.getValue()).getVGImage())
                .toList();
    }

    @Inject(method = "<init>", at = @At("TAIL"))
    private void injectRTShader(ProgramSet set, CallbackInfo ci) {
        ctx = Vulkanite.INSTANCE.getCtx();
        var passes = ((IGetRaytracingSource) set).getRaytracingSource();
        LOGGER.info("Creating RT pipeline, passes: {}", passes != null ? passes.length : "null");
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
    }

    // Inject after renderTerrain (when G-Buffer is ready)
    // NOTE: The target method name might vary depending on Iris version/mappings.
    // In some versions it's renderTerrain, in others it might be different or have
    // different arguments.
    // Based on IrisRenderingPipeline source, there is a renderTerrain method.
    // Let's try to match the exact signature or use a broader target if possible.
    // The previous error was: "could not find any targets matching 'renderTerrain'
    // in net.irisshaders.iris.pipeline.IrisRenderingPipeline"
    // This means the method renderTerrain doesn't exist or has a different
    // signature/name in the runtime jar.

    // Fallback: Use composite pass start or similar point.
    // Or check if renderTerrain is private/protected and needs mapping?
    // Iris uses "renderTerrain" in source but it might be obfuscated or changed.

    // Let's try to inject into "renderSolid" or similar if renderTerrain fails.
    // Actually, looking at standard Iris pipeline, it calls:
    // renderShadows -> renderTerrain -> renderTranslucents -> ...

    // If renderTerrain is missing, maybe we can inject at the HEAD of
    // renderTranslucents?
    // That would be effectively after opaque terrain.

    @Inject(method = "beginTranslucents", at = @At("HEAD"))
    private void renderTranslucents(CallbackInfo ci) {
        // Execute Ray Tracing after opaque terrain (before translucents)
        // Use the LevelRendererAccessor and Camera passed from the caller instead of creating new ones
        LevelRendererAccessor lra = (LevelRendererAccessor) MinecraftClient.getInstance().worldRenderer;
        Camera camera = MinecraftClient.getInstance().gameRenderer.getCamera();
        runRayTracing(camera);
    }

    @Inject(method = "renderShadows", at = @At("TAIL"))
    private void renderShadows(CallbackInfo ci) {
        // Remove RT from here, it's too early for Hybrid Rendering!
    }

    @Unique
    private void runRayTracing(Camera camera) {
        if (pipeline == null)
            return;

        var prof = MinecraftClient.getInstance().getProfiler();
        prof.push("vulkanite_render_rt");

        ShaderStorageBuffer[] buffers = new ShaderStorageBuffer[0];

        if (shaderStorageBufferHolder != null) {
            buffers = ((ShaderStorageBufferHolderAccessor) shaderStorageBufferHolder).getBuffers();
        }

        List<VRef<VGImage>> outImgs = new ArrayList<>();
        for (int i = 0; i < renderTargets.getRenderTargetCount(); i++) {
            outImgs.add(((IRenderTargetVkGetter) renderTargets.getOrCreate(i)).getMain());
        }

        // Create G-buffer views for hybrid rendering
        // G-buffer bindings:
        // - Binding 7: colortex1 (Albedo) - render target index 1
        // - Binding 8: colortex2 (Material Properties) - render target index 2
        // - Binding 9: colortex3 (Normal) - render target index 3
        // - Binding 10: colortex4 (World Position) - render target index 4
        // - Binding 11: colortex5 (Additional Properties) - render target index 5
        @SuppressWarnings("unchecked")
        VRef<VImageView>[] gbufferViews = new VRef[5];
        try {
        var ctx = Vulkanite.INSTANCE.getCtx();
        var requirements = pipeline.getPipelineRequirements();
        
        LOGGER.info("G-buffer requirements: albedo={}, material={}, normal={}, worldPos={}, extra={}", 
            requirements.needsAlbedo(), requirements.needsMaterial(), requirements.needsNormal(), 
            requirements.needsWorldPos(), requirements.needsExtra());
        
        // Get colortex1 (Albedo) - render target 1
        if (requirements.needsAlbedo()) {
        var albedoTarget = renderTargets.getOrCreate(1);
        var albedoImg = ((IRenderTargetVkGetter) albedoTarget).getMain();
        if (albedoImg != null && albedoImg.get() != null) {
        gbufferViews[0] = VImageView.create(ctx, new VRef<>(albedoImg.get()));
        LOGGER.debug("G-buffer albedo (colortex1) bound: {}x{}", albedoImg.get().width, albedoImg.get().height);
        } else {
        LOGGER.warn("G-buffer albedo (colortex1) is null or invalid!");
        }
        // Note: Do NOT close albedoImg here - the view holds a reference to it
        }
        // Get colortex2 (Material Properties) - render target 2
        if (requirements.needsMaterial()) {
        var materialTarget = renderTargets.getOrCreate(2);
        var materialImg = ((IRenderTargetVkGetter) materialTarget).getMain();
        if (materialImg != null && materialImg.get() != null) {
        gbufferViews[1] = VImageView.create(ctx, new VRef<>(materialImg.get()));
        LOGGER.debug("G-buffer material (colortex2) bound: {}x{}", materialImg.get().width, materialImg.get().height);
        } else {
        LOGGER.warn("G-buffer material (colortex2) is null or invalid!");
        }
        // Note: Do NOT close materialImg here - the view holds a reference to it
        }
        // Get colortex3 (Normal) - render target 3
        if (requirements.needsNormal()) {
        var normalTarget = renderTargets.getOrCreate(3);
        var normalImg = ((IRenderTargetVkGetter) normalTarget).getMain();
        if (normalImg != null && normalImg.get() != null) {
        gbufferViews[2] = VImageView.create(ctx, new VRef<>(normalImg.get()));
        LOGGER.debug("G-buffer normal (colortex3) bound: {}x{}", normalImg.get().width, normalImg.get().height);
        } else {
        LOGGER.warn("G-buffer normal (colortex3) is null or invalid!");
        }
        // Note: Do NOT close normalImg here - the view holds a reference to it
        }
        // Get colortex4 (World Position) - render target 4
        if (requirements.needsWorldPos()) {
        var positionTarget = renderTargets.getOrCreate(4);
        var positionImg = ((IRenderTargetVkGetter) positionTarget).getMain();
        if (positionImg != null && positionImg.get() != null) {
        gbufferViews[3] = VImageView.create(ctx, new VRef<>(positionImg.get()));
        LOGGER.debug("G-buffer worldPos (colortex4) bound: {}x{}", positionImg.get().width, positionImg.get().height);
        } else {
        LOGGER.warn("G-buffer worldPos (colortex4) is null or invalid!");
        }
        // Note: Do NOT close positionImg here - the view holds a reference to it
        }
        // Get colortex5 (Additional Properties) - render target 5
        if (requirements.needsExtra()) {
        var additionalTarget = renderTargets.getOrCreate(5);
        var additionalImg = ((IRenderTargetVkGetter) additionalTarget).getMain();
        if (additionalImg != null && additionalImg.get() != null) {
        gbufferViews[4] = VImageView.create(ctx, new VRef<>(additionalImg.get()));
        LOGGER.debug("G-buffer extra (colortex5) bound: {}x{}", additionalImg.get().width, additionalImg.get().height);
        } else {
        LOGGER.warn("G-buffer extra (colortex5) is null or invalid!");
        }
        // Note: Do NOT close additionalImg here - the view holds a reference to it
        }
        } catch (Exception e) {
        LOGGER.warn("Could not create G-buffer views: {}", e.getMessage());
        e.printStackTrace();
        }

        MixinCelestialUniforms celestialUniforms = (MixinCelestialUniforms) (Object) new CelestialUniforms(
                this.sunPathRotation);

        // Camera is already available from method parameter
        if (camera == null) {
            camera = MinecraftClient.getInstance().gameRenderer.getCamera();
        }

        try {
            pipeline.renderPostShadows(outImgs, camera, buffers, celestialUniforms, gbufferViews);
            
            // DLSSD processing is now handled inside VulkanPipeline.renderPostShadows()
            // where it has access to the command buffer for proper GPU synchronization
        } catch (DeviceLostException e) {
            // Handle device loss - disable Vulkanite and log the error
            LOGGER.error("Device lost during rendering: {}", e.getMessage());
            Vulkanite.IS_ENABLED = false;
            LOGGER.error("Disabled due to device loss. Falling back to standard rendering.");
        } finally {
            for (var ref : outImgs) {
                ref.close();
            }
            // Clean up G-buffer views
            for (var ref : gbufferViews) {
                if (ref != null) {
                    ref.close();
                }
            }
        }

        prof.pop();
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
        rtShaderPasses = null;
        pipeline = null;

        // Force a GC, collect all dangling resources
        System.gc();
    }
}
