package me.cortex.vulkanite.compat;

import me.cortex.vulkanite.acceleration.HybridAccelerationConfig;
import me.cortex.vulkanite.acceleration.HybridSbtLayout;
import me.cortex.vulkanite.lib.base.VContext;
import me.cortex.vulkanite.lib.pipeline.RaytracePipelineBuilder;
import me.cortex.vulkanite.lib.shader.ShaderModule;
import me.cortex.vulkanite.lib.shader.VShader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.vulkan.KHRRayTracingPipeline.*;

public class RaytracingShaderSet {
    private static final Logger LOGGER = LoggerFactory.getLogger(RaytracingShaderSet.class);
    public final int maxDepth = 1;

    private record RayHit(ShaderModule close, ShaderModule any, ShaderModule intersection) {}
    private final ShaderModule raygen;
    private final ShaderModule[] raymiss;
    private final RayHit[] rayhits;
    private final boolean proceduralDebugHitGroup;
    private final boolean diagnosticsCompiled;
    private final boolean hybridShadowCompiled;
    private final boolean proceduralShadowHitGroups;
    private final boolean proceduralReflectionHitGroup;

    public RaytracingShaderSet(VContext ctx, RaytracingShaderSource source) {
        var accelerationConfig = HybridAccelerationConfig.fromSystemProperties();
        this.diagnosticsCompiled = accelerationConfig.compileDiagnostics();
        this.hybridShadowCompiled = accelerationConfig.compileHybridShadows();
        var shader = VShader.compileLoad(ctx, source.raygen, VK_SHADER_STAGE_RAYGEN_BIT_KHR);
        this.raygen = shader.get().named();

        int activeMissCount = diagnosticsCompiled
                ? source.raymiss.length
                : Math.min(source.raymiss.length, HybridSbtLayout.PRODUCTION_SHADOW_MISS_INDEX + 1);
        this.raymiss = new ShaderModule[activeMissCount];
        for (int i = 0; i < raymiss.length; i++) {
            shader = VShader.compileLoad(ctx, source.raymiss[i], VK_SHADER_STAGE_MISS_BIT_KHR);
            this.raymiss[i] = shader.get().named();
        }

        boolean proceduralReflectionRequested = accelerationConfig.proceduralReflections();
        boolean extendedLayoutRequested = hybridShadowCompiled || proceduralReflectionRequested;
        int activeHitGroupCount = Math.min(
                source.rayhit.length,
                extendedLayoutRequested
                        ? HybridSbtLayout.requiredHitGroupCount(proceduralReflectionRequested)
                        : HybridSbtLayout.BASE_HIT_GROUP_COUNT);
        if (activeHitGroupCount < source.rayhit.length) {
            if (!extendedLayoutRequested) {
                LOGGER.info("Skipping experimental hybrid SBT groups; enable with -D{}=true",
                        HybridAccelerationConfig.SHADOW_PIPELINE_PROPERTY);
            } else {
                LOGGER.info("Skipping opt-in procedural reflection hit group; enable with -D{}=true",
                        HybridAccelerationConfig.REFLECTION_PROPERTY);
            }
        }
        this.rayhits = new RayHit[activeHitGroupCount];
        boolean[] proceduralGroups = new boolean[activeHitGroupCount];
        for (int i = 0; i < this.rayhits.length; i++) {
            var hit = source.rayhit[i];

            ShaderModule close = null;
            boolean debugOnlyClosestHit = i == HybridSbtLayout.PROCEDURAL_TERRAIN_HIT_GROUP
                    && !diagnosticsCompiled;
            if (hit.close() != null && !debugOnlyClosestHit) {
                shader = VShader.compileLoad(ctx, hit.close(), VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR);
                close = shader.get().named();
            }

            ShaderModule any = null;
            if (hit.any() != null) {
                shader = VShader.compileLoad(ctx, hit.any(), VK_SHADER_STAGE_ANY_HIT_BIT_KHR);
                any = shader.get().named();
            }

            ShaderModule intersection = null;
            if (hit.intersection() != null) {
                shader = VShader.compileLoad(ctx, hit.intersection(), VK_SHADER_STAGE_INTERSECTION_BIT_KHR);
                intersection = shader.get().named();
                proceduralGroups[i] = true;
            }

            this.rayhits[i] = new RayHit(close, any, intersection);
        }

        this.proceduralDebugHitGroup = this.rayhits.length > HybridSbtLayout.PROCEDURAL_TERRAIN_HIT_GROUP
                && proceduralGroups[HybridSbtLayout.PROCEDURAL_TERRAIN_HIT_GROUP];
        this.proceduralShadowHitGroups = hybridShadowCompiled && this.rayhits.length
                >= HybridSbtLayout.REQUIRED_SHADOW_HIT_GROUP_COUNT
                && proceduralGroups[HybridSbtLayout.PROCEDURAL_TERRAIN_HIT_GROUP]
                && proceduralGroups[HybridSbtLayout.SHADOW_PROCEDURAL_TERRAIN_HIT_GROUP];
        this.proceduralReflectionHitGroup = proceduralReflectionRequested
                && hasProceduralReflectionHitGroup(source);
        if (proceduralShadowHitGroups) {
            HybridSbtLayout.assertHitGroupCompatibility(proceduralGroups);
        }
        if (proceduralDebugHitGroup) {
            HybridSbtLayout.assertMissRecordCount(source.raymiss.length);
        }
    }

    public void apply(RaytracePipelineBuilder builder) {
        // Vulkanite's ray-generation push block is 64 bytes through Phase 7.
        // Declare it explicitly so vkCmdPushConstants has a matching layout range.
        builder.addPushConstantRange(64, 0);
        builder.setRayGen(raygen);
        for (var miss : raymiss) {
            builder.addMiss(miss);
        }
        for (var hit : rayhits) {
            builder.addHit(hit.close, hit.any, hit.intersection);
        }
    }

    public int getRayHitCount() {
        return rayhits.length;
    }

    public boolean hasProceduralDebugHitGroup() {
        return diagnosticsCompiled && proceduralDebugHitGroup;
    }

    /** Returns whether records 3-5 form the complete production shadow layout. */
    public boolean hasProceduralShadowHitGroups() {
        return proceduralShadowHitGroups;
    }

    /**
     * Returns whether record 6 can shade material rays against procedural AABBs.
     * An intersection-only marker is deliberately insufficient for production
     * reflection ownership.
     */
    public boolean hasProceduralReflectionHitGroup() {
        return proceduralReflectionHitGroup;
    }

    static boolean hasProceduralReflectionHitGroup(RaytracingShaderSource source) {
        if (source == null
                || source.rayhit.length <= HybridSbtLayout.REFLECTION_PROCEDURAL_TERRAIN_HIT_GROUP) {
            return false;
        }
        RaytracingShaderSource.RayHitSource reflection =
                source.rayhit[HybridSbtLayout.REFLECTION_PROCEDURAL_TERRAIN_HIT_GROUP];
        return reflection != null && reflection.close() != null && reflection.intersection() != null;
    }
}
