package me.cortex.vulkanite.client.rendering;

import me.cortex.vulkanite.client.Vulkanite;
import me.cortex.vulkanite.client.config.DLSSConfig;
import me.cortex.vulkanite.lib.base.VContext;
import me.cortex.vulkanite.lib.base.VRef;
import me.cortex.vulkanite.lib.descriptors.DescriptorUpdateBuilder;
import me.cortex.vulkanite.lib.descriptors.VDescriptorPool;
import me.cortex.vulkanite.lib.descriptors.VDescriptorSet;
import me.cortex.vulkanite.lib.descriptors.VDescriptorSetLayout;
import me.cortex.vulkanite.lib.memory.VBuffer;
import me.cortex.vulkanite.lib.memory.VImage;
import me.cortex.vulkanite.lib.other.VImageView;
import me.cortex.vulkanite.lib.other.VSampler;
import me.cortex.vulkanite.lib.pipeline.ComputePipelineBuilder;
import me.cortex.vulkanite.lib.pipeline.VComputePipeline;
import me.cortex.vulkanite.lib.shader.ShaderModule;
import me.cortex.vulkanite.lib.shader.VShader;
import me.cortex.vulkanite.lib.shader.reflection.ShaderReflection;

import java.util.List;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Transitional non-RT cache resolve pass.
 *
 * <p>This pass intentionally does not trace rays. It resolves a frame from the
 * hybrid G-buffer and fallback lighting while writing the same frame products
 * that the full RT path currently feeds into DLSS/RR and final composition.</p>
 */
final class CacheResolvePass {
    private static final int LOCAL_SIZE_X = 8;
    private static final int LOCAL_SIZE_Y = 8;
    private static final int PUSH_CONSTANT_BYTES = 72;

    private final VContext ctx;
    private final VRef<VSampler> sampler;
    private final VRef<VComputePipeline> pipeline;
    private final VRef<VDescriptorSetLayout> setLayout;
    private final ShaderReflection.Set setReflection;
    private final long[] pushConstants = new long[9];

    CacheResolvePass(VContext ctx, VRef<VSampler> sampler) {
        this.ctx = ctx;
        this.sampler = sampler.addRef();

        VRef<VShader> shader = VShader.compileLoad(ctx, SHADER_SOURCE, VK_SHADER_STAGE_COMPUTE_BIT);
        ShaderModule module = shader.get().named();
        List<VRef<VDescriptorSetLayout>> layouts = List.of();
        try {
            ShaderReflection reflection = shader.get().getReflection();
            layouts = reflection.buildSetLayouts(ctx);
            if (layouts.size() != 1) {
                throw new IllegalStateException("Cache resolve shader expected exactly one descriptor set, got "
                        + layouts.size());
            }

            this.setLayout = layouts.get(0).addRef();
            this.setReflection = reflection.getSet(0);

            ComputePipelineBuilder builder = new ComputePipelineBuilder()
                    .set(module)
                    .addLayout(layouts.get(0))
                    .addPushConstantRange(PUSH_CONSTANT_BYTES, 0);
            this.pipeline = builder.build(ctx);
        } finally {
            module.shader().close();
            shader.close();
            for (VRef<VDescriptorSetLayout> layout : layouts) {
                layout.close();
            }
        }
    }

    void execute(RtxPassGraph.Frame frame, boolean clearReservoir) {
        if (frame.renderWidth() <= 0 || frame.renderHeight() <= 0 || frame.noisyOutput() == null) {
            return;
        }

        VRef<VDescriptorPool> pool = Vulkanite.INSTANCE.getPoolByLayout(setLayout);
        VRef<VDescriptorSet> set = null;
        try {
            RtxFrameImages.StorageViews views = frame.storageViews();
            set = pool.get().allocateSet();
            DescriptorUpdateBuilder updater = new DescriptorUpdateBuilder(ctx, setReflection)
                    .set(set)
                    .uniform(0, frame.uboBuffer(), frame.uboOffset(), frame.uboSize());

            updater.imageStore(6, views.currentReservoir());
            bindGbuffer(updater, frame, 7, 0);
            bindGbuffer(updater, frame, 8, 1);
            bindGbuffer(updater, frame, 9, 2);
            bindGbuffer(updater, frame, 10, 3);
            bindGbuffer(updater, frame, 11, 4);
            updater.imageStore(12, views.radiance());
            updater.imageStore(13, views.motionVector());
            updater.imageStore(14, views.linearDepth());
            updater.imageStore(16, views.diffuseAlbedoMetallic());
            updater.imageStore(17, views.specularAlbedo());
            updater.imageStore(18, views.normalRoughness());
            updater.imageStore(19, views.specularHitDepth());
            updater.imageStore(20, views.firstHitDepth());
            updater.imageStore(21, views.blocklightDetail());
            bindOptionalStorageBuffer(updater, frame.sectionLightProbeBuffer(), 23);
            bindOptionalStorageBuffer(updater, frame.sectionLightProbeFeedbackBuffer(), 24);
            bindOptionalStorageBuffer(updater, frame.diffuseRadianceCacheBuffer(), 26);
            bindOptionalStorageBuffer(updater, frame.specularTransportCacheBuffer(), 28);
            bindOptionalStorageBuffer(updater, frame.surfaceDirectLightCacheBuffer(), 34);
            bindOptionalStorageBuffer(updater, frame.sectionLightBuffer(), 35);
            updater.imageStore(30, views.previousSpecularHistory());
            updater.imageStore(31, views.previousSpecularSurfaceHistory());
            updater.imageStore(32, views.currentSpecularHistory());
            updater.imageStore(33, views.currentSpecularSurfaceHistory());
            updater.apply();

            cmdPushConstants(frame, clearReservoir);
            // Cache-fill raygen can publish transport entries earlier in this
            // command buffer. Make those writes visible before compute lookup.
            frame.cmd().encodeMemoryBarrier();
            frame.cmd().bindCompute(pipeline);
            frame.cmd().bindDSet(List.of(set));
            frame.cmd().pushConstants(0, pushConstants, VK_SHADER_STAGE_COMPUTE_BIT);
            frame.cmd().dispatch(
                    (frame.renderWidth() + LOCAL_SIZE_X - 1) / LOCAL_SIZE_X,
                    (frame.renderHeight() + LOCAL_SIZE_Y - 1) / LOCAL_SIZE_Y,
                    1);
            frame.cmd().encodeMemoryBarrier();
        } finally {
            if (set != null) {
                set.close();
            }
            pool.close();
        }
    }

    private void cmdPushConstants(RtxPassGraph.Frame frame, boolean clearReservoir) {
        DLSSConfig lighting = DLSSConfig.getInstance();
        pushConstants[0] = (long) frame.frameIndex() & 0xFFFFFFFFL
                | (((long) frame.sampleIndex() & 0xFFFFFFFFL) << 32);
        pushConstants[1] = (long) Float.floatToRawIntBits(frame.sunDirectionX()) & 0xFFFFFFFFL
                | (((long) Float.floatToRawIntBits(frame.sunDirectionY()) & 0xFFFFFFFFL) << 32);
        pushConstants[2] = (long) Float.floatToRawIntBits(frame.sunDirectionZ()) & 0xFFFFFFFFL
                | (((long) Float.floatToRawIntBits(frame.sunColorR()) & 0xFFFFFFFFL) << 32);
        pushConstants[3] = (long) Float.floatToRawIntBits(frame.sunColorG()) & 0xFFFFFFFFL
                | (((long) Float.floatToRawIntBits(frame.sunColorB()) & 0xFFFFFFFFL) << 32);
        pushConstants[4] = ((long) frame.enableReSTIR() & 0xFFFFFFFFL)
                | (((long) frame.debugMode() & 0xFFFFFFFFL) << 32);
        pushConstants[5] = (long) frame.debugCellIndex() & 0xFFFFFFFFL
                | ((clearReservoir ? 1L : 0L) << 32);
        pushConstants[6] = (long) Float.floatToRawIntBits(lighting.getSunIntensity()) & 0xFFFFFFFFL
                | (((long) Float.floatToRawIntBits(lighting.getIndirectScale()) & 0xFFFFFFFFL) << 32);
        pushConstants[7] = (long) Float.floatToRawIntBits(lighting.getAmbientFactor()) & 0xFFFFFFFFL
                | (((long) Float.floatToRawIntBits(lighting.getMinLighting()) & 0xFFFFFFFFL) << 32);
        pushConstants[8] = (long) Float.floatToRawIntBits(lighting.getSpecularIntensity()) & 0xFFFFFFFFL;
    }

    private void bindGbuffer(DescriptorUpdateBuilder updater, RtxPassGraph.Frame frame, int binding, int index) {
        VRef<VImageView> view = frame.placeholderNormalsView();
        VRef<VImageView>[] gbufferViews = frame.gbufferViews();
        if (gbufferViews != null && index < gbufferViews.length && gbufferViews[index] != null) {
            view = gbufferViews[index];
        }
        updater.imageSampler(binding, view, sampler);
    }

    private void bindOptionalStorageBuffer(DescriptorUpdateBuilder updater, VRef<VBuffer> buffer, int binding) {
        if (setReflection.getBindingAt(binding) != null && buffer != null) {
            updater.buffer(binding, buffer);
        }
    }

    void destroy() {
        pipeline.close();
        setLayout.close();
        sampler.close();
    }

    private static final String SHADER_SOURCE = """
            #version 460 core

            layout(local_size_x = 8, local_size_y = 8, local_size_z = 1) in;

            layout(std140, binding = 0) uniform CameraInfo {
                vec3 corners[4];
                mat4 viewInverse;
                vec4 sunPosition;
                vec4 moonPosition;
                uint frameId;
                uint flags;
                vec2 padding1;
                mat4 prevViewProj;
                vec4 jitterData;
                mat4 curViewProj;
            } cam;

            layout(push_constant) uniform PushConstants {
                uint frameIndex;
                uint sampleIndex;
                float sunDirX;
                float sunDirY;
                float sunDirZ;
                float sunColorR;
                float sunColorG;
                float sunColorB;
                int enableReSTIR;
                int debugMode;
                int debugCellIndex;
                int clearReservoir;
                float sunIntensity;
                float indirectScale;
                float ambientFactor;
                float minLighting;
                float specularIntensity;
            } pc;

            layout(binding = 6, rgba32f) uniform image2D reservoirImage;
            layout(binding = 7) uniform sampler2D gbufferAlbedo;
            layout(binding = 8) uniform sampler2D gbufferMaterial;
            layout(binding = 9) uniform sampler2D gbufferNormal;
            layout(binding = 10) uniform sampler2D gbufferWorldPos;
            layout(binding = 11) uniform sampler2D gbufferExtra;
            layout(binding = 12, rgba16f) uniform image2D outputImage;
            layout(binding = 13, rg16f) uniform image2D motionVectors;
            layout(binding = 14, r16f) uniform image2D linearDepthImage;
            layout(binding = 16, rgba8) uniform image2D DiffuseAlbedoMetallic;
            layout(binding = 17, rgba8) uniform image2D SpecularAlbedo;
            layout(binding = 18, rgba16f) uniform image2D NormalRoughness;
            layout(binding = 19, r16f) uniform image2D SpecularHitDepth;
            layout(binding = 20, r16f) uniform image2D FirstHitDepth;
            layout(binding = 21, rgba16f) uniform image2D blocklightDetailImage;

            layout(binding = 23, std430) readonly buffer SectionLightProbeBuffer {
                ivec4 sectionLightProbeHeader;
                ivec4 sectionLightProbeRecords[];
            };

            struct SectionLightProbeRtCacheCell {
                uvec4 packedRadiance0123;
                uvec4 packedRadiance45State;
            };

            layout(binding = 24, std430) readonly buffer SectionLightProbeRtCacheBuffer {
                uvec4 sectionLightProbeRtCacheHeader;
                SectionLightProbeRtCacheCell sectionLightProbeRtCacheCells[];
            };

            struct DiffuseRadianceCacheEntry {
                ivec4 key;
                uvec4 payload;
            };

            layout(binding = 26, std430) readonly buffer DiffuseRadianceCacheBuffer {
                uvec4 diffuseRadianceCacheHeader;
                DiffuseRadianceCacheEntry diffuseRadianceCacheEntries[];
            };

            struct SpecularTransportCacheEntry {
                ivec4 key;
                uvec4 metadata;
                uvec4 payload;
            };

            layout(binding = 28, std430) readonly buffer SpecularTransportCacheBuffer {
                uvec4 specularTransportCacheHeader;
                SpecularTransportCacheEntry specularTransportCacheEntries[];
            };

            struct SurfaceDirectLightCacheEntry {
                ivec4 key;
                uvec4 metadata;
                uvec4 lightRecords[72];
                uvec4 directionalVisibility;
                uvec4 dependencyVersion;
            };

            layout(binding = 34, std430) readonly buffer SurfaceDirectLightCacheBuffer {
                uvec4 surfaceDirectLightCacheHeader;
                SurfaceDirectLightCacheEntry surfaceDirectLightCacheEntries[];
            };

            struct GpuSectionLight {
                ivec4 posRadiusFlags;
                uvec4 colorEmission;
            };

            layout(binding = 35, std430) readonly buffer SectionLightTableBuffer {
                uvec4 sectionLightHeader;
                GpuSectionLight sectionLightRecords[];
            };

            layout(binding = 30, rgba16f) readonly uniform image2D previousSpecularHistory;
            layout(binding = 31, rgba16f) readonly uniform image2D previousSpecularSurfaceHistory;
            layout(binding = 32, rgba16f) writeonly uniform image2D currentSpecularHistory;
            layout(binding = 33, rgba16f) writeonly uniform image2D currentSpecularSurfaceHistory;

            const float UNKNOWN_SPECULAR_HIT_DISTANCE = 10000.0;
            const float BLOCK_ID_WATER = 1000.0;
            const float BLOCK_ID_GLASS = 1001.0;
            const float BLOCK_ID_ICE = 1012.0;
            const float BLOCK_ID_CRYSTAL = 1103.0;
            const float PI = 3.14159265;
            const float EMISSION_INTENSITY = 2.0;
            const float EMISSIVE_SURFACE_INTENSITY = 12.0;
            const float EMISSIVE_SURFACE_VISIBLE_INTENSITY = 1.6;
            const int VOXEL_PROBE_GRID_SIZE = 8;
            const int VOXEL_PROBES_PER_SECTION = 512;
            const int VOXEL_PROBE_MAX_PAGES = 1024;
            const int VOXEL_PROBE_DIRECTORY_RECORDS = 2048;
            const int VOXEL_PROBE_HASH_LIMIT = 64;
            const float VOXEL_PROBE_INTENSITY = 0.45;
            const float LOCAL_BLOCKLIGHT_DIRECT_SCALE = 2.20;
            const float LOCAL_BLOCKLIGHT_FALLBACK_SCALE = 1.45;

            bool finiteVec4(vec4 value) {
                return !any(isnan(value)) && !any(isinf(value));
            }

            bool validHybridSample(vec4 gPos, vec4 gNormalRaw, vec4 gAlbedoRaw, vec4 gMaterialRaw, vec4 gExtraRaw) {
                if (!finiteVec4(gPos) || !finiteVec4(gNormalRaw) || !finiteVec4(gAlbedoRaw)
                        || !finiteVec4(gMaterialRaw) || !finiteVec4(gExtraRaw)) {
                    return false;
                }
                float normalLen2 = dot(gNormalRaw.xyz, gNormalRaw.xyz);
                bool hasPosition = gPos.w > 0.0 || dot(gPos.xyz, gPos.xyz) > 0.000001;
                bool hasNormal = normalLen2 > 0.25 && normalLen2 < 4.0;
                bool hasMaterial = gMaterialRaw.a >= 0.0 && gMaterialRaw.a <= 1.5;
                return hasPosition && hasNormal && hasMaterial;
            }

            vec3 linearColorGrade(vec3 color) {
                color = max(color, vec3(0.0)) * 0.92;
                float luma = dot(color, vec3(0.2126, 0.7152, 0.0722));
                color = mix(vec3(luma), color, 1.06);
                color = (color - vec3(0.18)) * 1.03 + vec3(0.18);
                color = max(color, vec3(0.0));
                float gradedLuma = dot(color, vec3(0.2126, 0.7152, 0.0722));
                if (gradedLuma > 10.0) {
                    color *= 10.0 / max(gradedLuma, 0.00001);
                }
                return color;
            }

            float rayleighPhase(float cosTheta) {
                return 0.75 * (1.0 + cosTheta * cosTheta);
            }

            float miePhase(float cosTheta, float g) {
                float g2 = g * g;
                float denominator = max(1.0 + g2 - 2.0 * g * cosTheta, 0.0001);
                return (1.0 - g2) / (4.0 * PI * denominator * sqrt(denominator));
            }

            float skyHash(vec3 point) {
                point = fract(point * 0.1031);
                point += dot(point, point.yzx + 33.33);
                return fract((point.x + point.y) * point.z);
            }

            float skyNoise(vec2 point) {
                vec2 cell = floor(point);
                vec2 fraction = fract(point);
                fraction = fraction * fraction * (3.0 - 2.0 * fraction);
                float a = skyHash(vec3(cell, 0.0));
                float b = skyHash(vec3(cell + vec2(1.0, 0.0), 0.0));
                float c = skyHash(vec3(cell + vec2(0.0, 1.0), 0.0));
                float d = skyHash(vec3(cell + vec2(1.0), 0.0));
                return mix(mix(a, b, fraction.x), mix(c, d, fraction.x), fraction.y);
            }

            float skyFbm(vec2 point) {
                float value = 0.0;
                float weight = 0.55;
                mat2 rotation = mat2(0.80, -0.60, 0.60, 0.80);
                for (int octave = 0; octave < 4; octave++) {
                    value += skyNoise(point) * weight;
                    point = rotation * point * 2.03 + vec2(17.1, 9.2);
                    weight *= 0.5;
                }
                return value;
            }

            vec3 sunColorFromElevation(vec3 sunDirection, vec3 baseSunColor) {
                float sunsetFactor = 1.0 - smoothstep(0.0, 0.3, sunDirection.y);
                vec3 sunsetTint = vec3(1.0, 0.5, 0.2);
                vec3 daylightTint = vec3(1.0, 0.98, 0.95);
                vec3 colorTint = mix(
                    daylightTint,
                    sunsetTint,
                    sunsetFactor * sunsetFactor * 0.72);
                float extinction = smoothstep(-0.05, 0.15, sunDirection.y);
                return baseSunColor * colorTint * max(extinction, 0.05);
            }

            vec3 skyColor(
                vec3 direction,
                vec3 sunDirection,
                vec3 moonDirection,
                vec3 sunColor,
                float time
            ) {
                float sunElevation = sunDirection.y;
                float viewElevation = direction.y;
                float dayFactor = smoothstep(-0.1, 0.3, sunElevation);
                float duskFactor = smoothstep(-0.15, 0.05, sunElevation)
                    * (1.0 - smoothstep(0.05, 0.25, sunElevation));

                vec3 zenithDay = vec3(0.12, 0.32, 0.85);
                vec3 zenithDusk = vec3(0.08, 0.12, 0.35);
                vec3 zenithNight = vec3(0.005, 0.008, 0.02);
                vec3 zenith = mix(zenithNight, zenithDay, dayFactor)
                    + zenithDusk * duskFactor * 0.35;
                vec3 horizonDay = vec3(0.5, 0.7, 0.95);
                vec3 horizonDusk = vec3(0.8, 0.4, 0.15);
                vec3 horizonNight = vec3(0.01, 0.015, 0.03);
                vec3 horizon = mix(horizonNight, horizonDay, dayFactor)
                    + horizonDusk * duskFactor * 1.5;
                vec3 ground = mix(vec3(0.005, 0.005, 0.008), vec3(0.12, 0.12, 0.1), dayFactor);

                float gradient = max(viewElevation * 0.5 + 0.5, 0.0);
                vec3 skyBase = viewElevation < 0.0
                    ? mix(ground, horizon, max(1.0 + viewElevation * 4.0, 0.0))
                    : mix(horizon, zenith, pow(gradient, 0.5));
                float cosTheta = dot(direction, sunDirection);
                vec3 rayleighColor = vec3(0.15, 0.35, 0.8)
                    * rayleighPhase(cosTheta) * dayFactor * 0.08 * 0.9;
                vec3 mieColor = sunColor * miePhase(cosTheta, 0.76) * 0.015;
                float sunDisk = pow(max(cosTheta, 0.0), 512.0) * 2.0;
                float sunGlow = pow(max(cosTheta, 0.0), 8.0) * 0.15;
                float horizonGlow = pow(max(cosTheta, 0.0), 3.0)
                    * duskFactor * 0.3 * max(1.0 - abs(viewElevation) * 3.0, 0.0);
                vec3 sky = skyBase + rayleighColor + mieColor
                    + sunColor * (sunDisk + sunGlow + horizonGlow);

                if (viewElevation > 0.025) {
                    vec2 cloudUv = direction.xz / max(viewElevation, 0.06);
                    cloudUv = cloudUv * 0.075 + vec2(time * 0.012, time * 0.012 * 0.37);
                    float cloudNoise = skyFbm(cloudUv);
                    float cloud = smoothstep(0.56, 0.72, cloudNoise)
                        * smoothstep(0.025, 0.16, viewElevation);
                    float cloudSun = clamp(
                        dot(normalize(vec3(direction.x, 0.28, direction.z)), sunDirection)
                            * 0.5 + 0.5,
                        0.0,
                        1.0);
                    vec3 cloudDay = mix(vec3(0.22, 0.25, 0.31), vec3(1.0, 0.95, 0.86), cloudSun);
                    vec3 cloudColor = mix(vec3(0.018, 0.022, 0.035), cloudDay, dayFactor);
                    float silverLining = smoothstep(0.55, 0.9, cloudNoise)
                        * pow(max(cosTheta, 0.0), 12.0) * dayFactor;
                    sky = mix(sky, cloudColor + sunColor * silverLining * 0.18, cloud * 0.82);
                }

                float nightFactor = 1.0 - smoothstep(-0.12, 0.12, sunElevation);
                if (nightFactor > 0.001 && viewElevation > 0.0) {
                    vec3 starCell = floor(normalize(direction) * 720.0);
                    float starSeed = skyHash(starCell);
                    float star = smoothstep(0.9965, 0.9998, starSeed);
                    star *= 0.75 + 0.25 * sin(time * 1.7 + starSeed * 41.0);
                    star *= smoothstep(0.02, 0.22, viewElevation) * nightFactor;
                    sky += vec3(0.72, 0.82, 1.0) * star * 1.8;
                }

                float moonDot = dot(direction, normalize(moonDirection));
                float moonDisk = smoothstep(cos(0.010), cos(0.006), moonDot);
                float moonHalo = pow(max(moonDot, 0.0), 192.0) * 0.08;
                sky += vec3(0.62, 0.70, 0.92) * (moonDisk * 1.6 + moonHalo)
                    * (1.0 - dayFactor);
                return max(sky, vec3(0.0));
            }

            vec4 cachedVolumetrics(
                vec3 rayDirection,
                float rayDistance,
                vec3 sunDirection,
                vec3 sunRadiance,
                float sunVisibility
            ) {
                float marchDistance = min(max(rayDistance, 0.0), 96.0);
                float transmittance = exp(-0.008 * marchDistance);
                float phase = miePhase(dot(rayDirection, sunDirection), 0.65);
                vec3 ambientScatter = mix(
                    vec3(0.025, 0.035, 0.055),
                    sunRadiance * 0.06,
                    smoothstep(-0.1, 0.4, sunDirection.y));
                vec3 incident = ambientScatter
                    + sunRadiance * clamp(sunVisibility, 0.0, 1.0) * phase;
                vec3 scattering = (1.0 - transmittance) * incident * 0.65;
                return vec4(max(scattering, vec3(0.0)), clamp(transmittance, 0.0, 1.0));
            }

            vec3 fresnelSchlickPbr(float cosTheta, vec3 f0) {
                return f0 + (vec3(1.0) - f0) * pow(max(1.0 - cosTheta, 0.0), 5.0);
            }

            void evaluatePbrSplit(
                vec3 albedo,
                vec3 normal,
                vec3 viewDir,
                vec3 lightDir,
                vec3 lightColor,
                vec3 f0,
                float roughness,
                float metallic,
                float specularIntensity,
                out vec3 diffuseTerm,
                out vec3 specularTerm
            ) {
                float nDotV = max(dot(normal, viewDir), 0.001);
                float nDotL = max(dot(normal, lightDir), 0.0);
                if (nDotL <= 0.0) {
                    diffuseTerm = vec3(0.0);
                    specularTerm = vec3(0.0);
                    return;
                }
                vec3 halfway = normalize(viewDir + lightDir);
                float nDotH = max(dot(normal, halfway), 0.0);
                float hDotV = max(dot(halfway, viewDir), 0.0);
                float clampedRoughness = max(roughness, 0.04);
                float alpha = clampedRoughness * clampedRoughness;
                float alpha2 = alpha * alpha;
                float distributionDenominator = nDotH * nDotH * (alpha2 - 1.0) + 1.0;
                float distribution = alpha2
                    / (PI * distributionDenominator * distributionDenominator + 0.00001);
                float geometryK = (clampedRoughness + 1.0)
                    * (clampedRoughness + 1.0) * 0.125;
                float geometryV = nDotV / (nDotV * (1.0 - geometryK) + geometryK);
                float geometryL = nDotL / (nDotL * (1.0 - geometryK) + geometryK);
                vec3 fresnel = fresnelSchlickPbr(hDotV, f0);
                vec3 specularBrdf = distribution * geometryV * geometryL * fresnel
                    / (4.0 * nDotV * nDotL + 0.001);
                vec3 diffuseBrdf = (vec3(1.0) - fresnel) * (1.0 - metallic) * albedo;
                diffuseTerm = diffuseBrdf * lightColor * nDotL;
                specularTerm = specularBrdf * specularIntensity * lightColor * nDotL;
            }

            uint diffuseRadianceHashMix(uint value) {
                value ^= value >> 16u;
                value *= 0x7feb352du;
                value ^= value >> 15u;
                value *= 0x846ca68bu;
                return value ^ (value >> 16u);
            }

            uint diffuseRadianceCacheHash(ivec4 key) {
                uint value = uint(key.x) * 0x9e3779b9u;
                value ^= uint(key.y) * 0x85ebca6bu;
                value ^= uint(key.z) * 0xc2b2ae35u;
                value ^= uint(key.w) * 0x27d4eb2du;
                return diffuseRadianceHashMix(value);
            }

            int diffuseRadianceNormalBucket(vec3 normal) {
                vec3 axis = abs(normal);
                if (axis.x >= axis.y && axis.x >= axis.z) return normal.x >= 0.0 ? 0 : 1;
                if (axis.y >= axis.z) return normal.y >= 0.0 ? 2 : 3;
                return normal.z >= 0.0 ? 4 : 5;
            }

            vec3 diffuseRadianceBucketNormal(int bucket) {
                if (bucket == 0) return vec3(1.0, 0.0, 0.0);
                if (bucket == 1) return vec3(-1.0, 0.0, 0.0);
                if (bucket == 2) return vec3(0.0, 1.0, 0.0);
                if (bucket == 3) return vec3(0.0, -1.0, 0.0);
                if (bucket == 4) return vec3(0.0, 0.0, 1.0);
                return vec3(0.0, 0.0, -1.0);
            }

            bool hasEncodedSurfaceFace(float encodedFace) {
                return encodedFace <= -0.5 && encodedFace >= -6.5;
            }

            int decodeSurfaceFaceBucket(float encodedFace) {
                return clamp(int(round(-encodedFace)) - 1, 0, 5);
            }

            vec3 unpackDiffuseRadiance(uint packed) {
                if (packed == 0u) return vec3(0.0);
                uvec3 mantissa = uvec3(
                    packed & 511u,
                    (packed >> 9u) & 511u,
                    (packed >> 18u) & 511u);
                uint exponent = (packed >> 27u) & 31u;
                float scale = exp2(float(exponent) - 24.0);
                return vec3(mantissa) * scale;
            }

            int findVoxelProbeSlot(ivec3 sectionOrigin) {
                if (sectionLightProbeHeader.x <= 0) return -1;
                ivec3 sectionCoord = sectionOrigin / 16;
                uint hash = uint(sectionCoord.x) * 0x8da6b343u
                    ^ uint(sectionCoord.y) * 0xd8163841u
                    ^ uint(sectionCoord.z) * 0xcb1ab31fu;
                hash ^= hash >> 16u;
                hash *= 0x7feb352du;
                hash ^= hash >> 15u;
                uint mask = uint(VOXEL_PROBE_DIRECTORY_RECORDS - 1);
                for (int probe = 0; probe < VOXEL_PROBE_HASH_LIMIT; probe++) {
                    int index = int((hash + uint(probe)) & mask);
                    ivec4 record = sectionLightProbeRecords[index];
                    if (record.w == 0) return -1;
                    if (all(equal(record.xyz, sectionOrigin))) {
                        int slot = record.w - 1;
                        int slotLimit = min(sectionLightProbeHeader.y, VOXEL_PROBE_MAX_PAGES);
                        return slot >= 0 && slot < slotLimit ? slot : -1;
                    }
                }
                return -1;
            }

            bool readVoxelProbeRadiance(vec3 worldPos, vec3 normal, out vec3 radiance) {
                radiance = vec3(0.0);
                ivec3 sectionOrigin = ivec3(floor(worldPos / 16.0)) * 16;
                int slot = findVoxelProbeSlot(sectionOrigin);
                if (slot < 0 || sectionLightProbeRtCacheHeader.x == 0u) return false;

                vec3 localPosition = clamp(
                    worldPos - vec3(sectionOrigin),
                    vec3(0.0),
                    vec3(15.999));
                ivec3 probeCoord = clamp(
                    ivec3(floor(localPosition * 0.5)),
                    ivec3(0),
                    ivec3(VOXEL_PROBE_GRID_SIZE - 1));
                int probeIndex = (probeCoord.z * VOXEL_PROBE_GRID_SIZE + probeCoord.y)
                    * VOXEL_PROBE_GRID_SIZE + probeCoord.x;
                int cellIndex = slot * VOXEL_PROBES_PER_SECTION + probeIndex;
                if (cellIndex < 0 || cellIndex >= int(sectionLightProbeRtCacheHeader.w)) return false;

                SectionLightProbeRtCacheCell cell = sectionLightProbeRtCacheCells[cellIndex];
                if ((cell.packedRadiance45State.z & 0x80000000u) == 0u
                        || (cell.packedRadiance45State.w & 0x3fu) != 0x3fu) {
                    return false;
                }

                vec3 face0 = unpackDiffuseRadiance(cell.packedRadiance0123.x);
                vec3 face1 = unpackDiffuseRadiance(cell.packedRadiance0123.y);
                vec3 face2 = unpackDiffuseRadiance(cell.packedRadiance0123.z);
                vec3 face3 = unpackDiffuseRadiance(cell.packedRadiance0123.w);
                vec3 face4 = unpackDiffuseRadiance(cell.packedRadiance45State.x);
                vec3 face5 = unpackDiffuseRadiance(cell.packedRadiance45State.y);
                vec3 axisWeight = abs(normalize(normal));
                float weightSum = max(axisWeight.x + axisWeight.y + axisWeight.z, 0.0001);
                radiance = (
                    (normal.x >= 0.0 ? face0 : face1) * axisWeight.x
                    + (normal.y >= 0.0 ? face2 : face3) * axisWeight.y
                    + (normal.z >= 0.0 ? face4 : face5) * axisWeight.z)
                    * (VOXEL_PROBE_INTENSITY / weightSum);
                return true;
            }

            bool readDiffuseRadianceCache(ivec4 key, out vec3 radiance, out float confidence) {
                radiance = vec3(0.0);
                confidence = 0.0;
                uint entryCount = diffuseRadianceCacheHeader.x;
                uint probeCount = min(diffuseRadianceCacheHeader.z, 8u);
                if (entryCount == 0u || probeCount == 0u) return false;

                uint readyState = 0x80000000u | (diffuseRadianceCacheHeader.y & 0x3fffffffu);
                uint slot = diffuseRadianceCacheHash(key) % entryCount;
                for (uint probe = 0u; probe < 8u; probe++) {
                    if (probe >= probeCount) break;
                    uint index = (slot + probe) % entryCount;
                    DiffuseRadianceCacheEntry entry = diffuseRadianceCacheEntries[index];
                    if (entry.payload.y == readyState && all(equal(entry.key, key))) {
                        radiance = unpackDiffuseRadiance(entry.payload.x);
                        float hitDistance = uintBitsToFloat(entry.payload.w);
                        float distanceConfidence = hitDistance < 0.08 ? 0.15 : 1.0;
                        confidence = 0.85 * distanceConfidence;
                        return true;
                    }
                }
                return false;
            }

            bool readInterpolatedDiffuseRadiance(
                vec3 worldPos,
                int normalBucket,
                out vec3 radiance,
                out float confidence
            ) {
                vec3 faceNormal = diffuseRadianceBucketNormal(normalBucket);
                ivec3 ownerCell = ivec3(floor(worldPos - faceNormal * 0.01));
                int normalAxis = normalBucket < 2 ? 0 : (normalBucket < 4 ? 1 : 2);
                int uAxis = normalAxis == 0 ? 1 : 0;
                int vAxis = normalAxis == 2 ? 1 : 2;

                vec3 centerGrid = worldPos - vec3(0.5);
                ivec3 baseCell = ownerCell;
                baseCell[uAxis] = int(floor(centerGrid[uAxis]));
                baseCell[vAxis] = int(floor(centerGrid[vAxis]));
                float u = fract(centerGrid[uAxis]);
                float v = fract(centerGrid[vAxis]);

                ivec3 cell00 = baseCell;
                ivec3 cell10 = baseCell;
                ivec3 cell01 = baseCell;
                ivec3 cell11 = baseCell;
                cell10[uAxis] += 1;
                cell01[vAxis] += 1;
                cell11[uAxis] += 1;
                cell11[vAxis] += 1;

                vec3 samples[4];
                float confidences[4];
                bool hits[4];
                hits[0] = readDiffuseRadianceCache(
                    ivec4(cell00, normalBucket), samples[0], confidences[0]);
                hits[1] = readDiffuseRadianceCache(
                    ivec4(cell10, normalBucket), samples[1], confidences[1]);
                hits[2] = readDiffuseRadianceCache(
                    ivec4(cell01, normalBucket), samples[2], confidences[2]);
                hits[3] = readDiffuseRadianceCache(
                    ivec4(cell11, normalBucket), samples[3], confidences[3]);

                float weights[4] = float[4](
                    (1.0 - u) * (1.0 - v),
                    u * (1.0 - v),
                    (1.0 - u) * v,
                    u * v);
                radiance = vec3(0.0);
                confidence = 0.0;
                float validWeight = 0.0;
                for (int i = 0; i < 4; i++) {
                    if (!hits[i]) continue;
                    radiance += samples[i] * weights[i];
                    confidence += confidences[i] * weights[i];
                    validWeight += weights[i];
                }
                if (validWeight <= 0.0001) return false;
                radiance /= validWeight;
                // Keep missing interpolation corners visible in confidence
                // instead of renormalizing sparse coverage to a full-strength hit.
                confidence = clamp(confidence, 0.0, 1.0);
                return true;
            }

            uint surfaceDirectLightHash(ivec4 key) {
                uint value = uint(key.x) * 0x9e3779b9u;
                value ^= uint(key.y) * 0x85ebca6bu;
                value ^= uint(key.z) * 0xc2b2ae35u;
                value ^= uint(key.w) * 0x27d4eb2du;
                return diffuseRadianceHashMix(value ^ 0x6d2b79f5u);
            }

            uint surfaceSectionDirectoryHash(ivec3 sectionCoord) {
                uint value = uint(sectionCoord.x) * 0x8da6b343u
                    ^ uint(sectionCoord.y) * 0xd8163841u
                    ^ uint(sectionCoord.z) * 0xcb1ab31fu;
                value ^= value >> 16u;
                value *= 0x7feb352du;
                return value ^ (value >> 15u);
            }

            bool surfaceDependencyVersion(vec3 worldPos, out uvec2 version) {
                version = uvec2(0u);
                int recordCount = int(sectionLightHeader.w);
                if (recordCount <= 0) return false;
                ivec3 sectionOrigin = ivec3(floor(worldPos / 16.0)) * 16;
                ivec3 sectionCoord = sectionOrigin / 16;
                uint mask = uint(recordCount - 1);
                uint hash = surfaceSectionDirectoryHash(sectionCoord);
                int directoryBase = int(sectionLightHeader.x);
                for (int probe = 0; probe < 64; probe++) {
                    if (probe >= recordCount) break;
                    int index = int((hash + uint(probe)) & mask);
                    GpuSectionLight record = sectionLightRecords[directoryBase + index];
                    if (record.colorEmission.w == 0u) return false;
                    if (all(equal(record.posRadiusFlags.xyz, sectionOrigin))) {
                        version = record.colorEmission.yz;
                        return true;
                    }
                }
                return false;
            }

            bool findSurfaceSectionLightGridRange(ivec3 sectionOrigin, out int firstLight, out int lightCount) {
                firstLight = 0;
                lightCount = 0;
                int gridRecordCount = int(sectionLightHeader.w);
                if (gridRecordCount <= 0) return false;
                uint directoryMask = uint(gridRecordCount - 1);
                uint hash = surfaceSectionDirectoryHash(sectionOrigin / 16);
                int directoryBase = int(sectionLightHeader.x);
                for (int probe = 0; probe < 64; probe++) {
                    if (probe >= gridRecordCount) break;
                    int directoryIndex = int((hash + uint(probe)) & directoryMask);
                    GpuSectionLight record = sectionLightRecords[directoryBase + directoryIndex];
                    if (record.colorEmission.w == 0u) return false;
                    if (all(equal(record.posRadiusFlags.xyz, sectionOrigin))) {
                        firstLight = record.posRadiusFlags.w;
                        lightCount = int(record.colorEmission.x);
                        return lightCount > 0;
                    }
                }
                return false;
            }

            float sectionLightNormalizedEmission(GpuSectionLight light) {
                return clamp(float(light.colorEmission.a) * (1.0 / 15.0), 0.0, 1.0);
            }

            float sectionLightDistanceAttenuation(float distance, float radius) {
                float normalizedDistance = clamp(distance / max(radius, 0.001), 0.0, 1.0);
                float distance2 = normalizedDistance * normalizedDistance;
                float distance4 = distance2 * distance2;
                float falloff = max(1.0 - distance4, 0.0);
                return falloff * falloff;
            }

            vec3 evaluateLocalBlocklightRecord(
                GpuSectionLight light,
                vec3 worldPos,
                vec3 normal,
                vec3 viewDir,
                vec3 diffuseResponse,
                vec3 f0,
                float roughness,
                float visibility,
                float intensityScale,
                out vec3 viewDependentSpecular
            ) {
                viewDependentSpecular = vec3(0.0);
                vec3 lightColor = vec3(light.colorEmission.rgb) * (1.0 / 255.0);
                float emission = sectionLightNormalizedEmission(light);
                if (emission <= 0.0
                        || dot(lightColor, vec3(0.2126, 0.7152, 0.0722)) <= 0.0001) {
                    return vec3(0.0);
                }

                float radius = max(float(light.posRadiusFlags.w & 0xFFFF), 1.0);
                vec3 lightPos = vec3(light.posRadiusFlags.xyz) + vec3(0.5);
                vec3 toLight = lightPos - worldPos;
                float dist2 = dot(toLight, toLight);
                if (dist2 <= 0.0001 || dist2 >= radius * radius) return vec3(0.0);

                float distance = sqrt(dist2);
                vec3 lightDir = toLight / distance;
                float nDotL = max(dot(normal, lightDir), 0.0);
                if (nDotL <= 0.0001) return vec3(0.0);

                float attenuation = sectionLightDistanceAttenuation(distance, radius);
                vec3 incident = lightColor * emission * attenuation
                    * intensityScale * clamp(visibility, 0.0, 1.0);
                vec3 diffuse = diffuseResponse * incident * nDotL;

                vec3 halfwayVector = lightDir + viewDir;
                if (dot(halfwayVector, halfwayVector) > 0.0001) {
                    vec3 halfway = normalize(halfwayVector);
                    float nDotV = max(dot(normal, viewDir), 0.0001);
                    float nDotH = max(dot(normal, halfway), 0.0);
                    float vDotH = max(dot(viewDir, halfway), 0.0);
                    float alpha = roughness * roughness;
                    float alpha2 = alpha * alpha;
                    float distributionDenominator = nDotH * nDotH
                        * (alpha2 - 1.0) + 1.0;
                    float distribution = alpha2 / max(
                        3.14159265 * distributionDenominator
                            * distributionDenominator,
                        0.0001);
                    float geometryK = (roughness + 1.0) * (roughness + 1.0) * 0.125;
                    float geometryV = nDotV / mix(nDotV, 1.0, geometryK);
                    float geometryL = nDotL / mix(nDotL, 1.0, geometryK);
                    vec3 fresnel = f0 + (vec3(1.0) - f0)
                        * pow(1.0 - vDotH, 5.0);
                    vec3 specularBrdf = distribution * geometryV * geometryL
                        * fresnel / max(4.0 * nDotV * nDotL, 0.0001);
                    viewDependentSpecular = specularBrdf * incident * nDotL;
                }
                return diffuse;
            }

            void accumulateLocalBlocklightFallbackRange(
                int firstLight,
                int rangeCount,
                vec3 worldPos,
                vec3 normal,
                vec3 viewDir,
                vec3 diffuseResponse,
                vec3 f0,
                float roughness,
                inout int contributorCount,
                inout vec3 diffuse,
                inout vec3 specular
            ) {
                const int fallbackContributorLimit = 24;
                const int fallbackRangeScanLimit = 128;
                for (int rangeIndex = 0; rangeIndex < fallbackRangeScanLimit; rangeIndex++) {
                    if (rangeIndex >= rangeCount || contributorCount >= fallbackContributorLimit) break;
                    vec3 recordSpecular;
                    vec3 recordDiffuse = evaluateLocalBlocklightRecord(
                        sectionLightRecords[firstLight + rangeIndex],
                        worldPos,
                        normal,
                        viewDir,
                        diffuseResponse,
                        f0,
                        roughness,
                        1.0,
                        LOCAL_BLOCKLIGHT_FALLBACK_SCALE,
                        recordSpecular);
                    if (dot(recordDiffuse + recordSpecular, vec3(0.2126, 0.7152, 0.0722)) <= 0.0001) {
                        continue;
                    }
                    diffuse += recordDiffuse;
                    specular += recordSpecular;
                    contributorCount++;
                }
            }

            vec3 sampleLocalBlocklightFallback(
                vec3 worldPos,
                vec3 normal,
                vec3 viewDir,
                vec3 diffuseResponse,
                vec3 f0,
                float roughness,
                out vec3 viewDependentSpecular
            ) {
                vec3 diffuse = vec3(0.0);
                viewDependentSpecular = vec3(0.0);
                int contributorCount = 0;
                if (sectionLightHeader.w > 0u) {
                    ivec3 baseSectionOrigin = ivec3(floor(worldPos / 16.0)) * 16;
                    for (int manhattan = 0; manhattan <= 6; manhattan++) {
                        for (int dz = -2; dz <= 2; dz++) {
                            for (int dy = -2; dy <= 2; dy++) {
                                for (int dx = -2; dx <= 2; dx++) {
                                    if (abs(dx) + abs(dy) + abs(dz) != manhattan
                                            || contributorCount >= 24) {
                                        continue;
                                    }
                                    int firstLight;
                                    int lightCount;
                                    if (!findSurfaceSectionLightGridRange(
                                            baseSectionOrigin + ivec3(dx, dy, dz) * 16,
                                            firstLight,
                                            lightCount)) {
                                        continue;
                                    }
                                    accumulateLocalBlocklightFallbackRange(
                                        firstLight,
                                        lightCount,
                                        worldPos,
                                        normal,
                                        viewDir,
                                        diffuseResponse,
                                        f0,
                                        roughness,
                                        contributorCount,
                                        diffuse,
                                        viewDependentSpecular);
                                }
                            }
                        }
                    }
                } else {
                    int totalLightCount = min(int(sectionLightHeader.x), 96);
                    accumulateLocalBlocklightFallbackRange(
                        0,
                        totalLightCount,
                        worldPos,
                        normal,
                        viewDir,
                        diffuseResponse,
                        f0,
                        roughness,
                        contributorCount,
                        diffuse,
                        viewDependentSpecular);
                }
                return max(diffuse, vec3(0.0));
            }

            uint surfaceSunSignature(vec3 direction) {
                ivec3 quantized = ivec3(round(normalize(direction) * 32.0));
                uint value = uint(quantized.x) * 0x9e3779b9u;
                value ^= uint(quantized.y) * 0x85ebca6bu;
                value ^= uint(quantized.z) * 0xc2b2ae35u;
                return diffuseRadianceHashMix(value ^ 0x51ed270bu);
            }

            bool findSurfaceDirectLightCache(
                ivec4 key,
                uvec2 dependencyVersion,
                out uint entryIndex,
                out int coveredLightCount,
                out bool localReady
            ) {
                entryIndex = 0u;
                coveredLightCount = 0;
                localReady = false;
                uint entryCount = surfaceDirectLightCacheHeader.x;
                uint probeCount = min(surfaceDirectLightCacheHeader.z, 8u);
                if (entryCount == 0u || probeCount == 0u) return false;
                uint readyState = 0x80000000u | (surfaceDirectLightCacheHeader.y & 0x1fffffffu);
                uint slot = surfaceDirectLightHash(key) % entryCount;
                for (uint probe = 0u; probe < 8u; probe++) {
                    if (probe >= probeCount) break;
                    uint index = (slot + probe) % entryCount;
                    SurfaceDirectLightCacheEntry entry = surfaceDirectLightCacheEntries[index];
                    if (all(equal(entry.key, key))
                            && all(equal(entry.dependencyVersion.xy, dependencyVersion))) {
                        entryIndex = index;
                        localReady = entry.metadata.y == readyState;
                        coveredLightCount = localReady
                            ? int(min(entry.metadata.x, 96u))
                            : 0;
                        return true;
                    }
                }
                return false;
            }

            bool readCachedSurfaceDirectionalVisibility(
                uint entryIndex,
                uint sunSignature,
                vec3 worldPos,
                out float visibility
            ) {
                visibility = 0.0;
                uint readyState = 0x80000000u
                    | (surfaceDirectLightCacheHeader.y & 0x1fffffffu);
                SurfaceDirectLightCacheEntry entry =
                    surfaceDirectLightCacheEntries[entryIndex];
                if (entry.directionalVisibility.y != readyState
                        || entry.directionalVisibility.z != sunSignature) {
                    return false;
                }

                int normalAxis = entry.key.w < 2 ? 0 : (entry.key.w < 4 ? 1 : 2);
                int uAxis = normalAxis == 0 ? 1 : 0;
                int vAxis = normalAxis == 2 ? 1 : 2;
                vec3 localPosition = worldPos - vec3(entry.key.xyz);
                float u = clamp((localPosition[uAxis] - 0.25) * 2.0, 0.0, 1.0);
                float v = clamp((localPosition[vAxis] - 0.25) * 2.0, 0.0, 1.0);
                uint packed = entry.directionalVisibility.x;
                vec4 samples = vec4(
                    float(packed & 0xffu),
                    float((packed >> 8u) & 0xffu),
                    float((packed >> 16u) & 0xffu),
                    float((packed >> 24u) & 0xffu)) * (1.0 / 255.0);
                visibility = mix(
                    mix(samples.x, samples.y, u),
                    mix(samples.z, samples.w, u),
                    v);
                return true;
            }

            float unpackSurfaceDirectVisibility(uint packedVisibility, int x, int y) {
                int sampleIndex = clamp(x, 0, 3) + clamp(y, 0, 3) * 4;
                return float((packedVisibility >> (uint(sampleIndex) * 2u)) & 3u)
                    * (1.0 / 3.0);
            }

            float interpolateSurfaceDirectVisibility(
                uint packedVisibility,
                vec3 localPosition,
                int uAxis,
                int vAxis
            ) {
                vec2 uv = clamp(
                    vec2(localPosition[uAxis], localPosition[vAxis]),
                    vec2(0.0),
                    vec2(1.0));
                vec2 grid = clamp(uv * 4.0 - vec2(0.5), vec2(0.0), vec2(3.0));
                ivec2 baseCell = min(ivec2(floor(grid)), ivec2(2));
                vec2 fraction = grid - vec2(baseCell);
                float v00 = unpackSurfaceDirectVisibility(packedVisibility, baseCell.x, baseCell.y);
                float v10 = unpackSurfaceDirectVisibility(packedVisibility, baseCell.x + 1, baseCell.y);
                float v01 = unpackSurfaceDirectVisibility(packedVisibility, baseCell.x, baseCell.y + 1);
                float v11 = unpackSurfaceDirectVisibility(packedVisibility, baseCell.x + 1, baseCell.y + 1);
                return mix(mix(v00, v10, fraction.x), mix(v01, v11, fraction.x), fraction.y);
            }

            vec3 evaluateCachedSurfaceDirectLight(
                uint entryIndex,
                int coveredLightCount,
                vec3 worldPos,
                vec3 normal,
                vec3 viewDir,
                vec3 diffuseResponse,
                vec3 f0,
                float roughness,
                out vec3 viewDependentSpecular
            ) {
                vec3 result = vec3(0.0);
                viewDependentSpecular = vec3(0.0);
                ivec4 surfaceKey = surfaceDirectLightCacheEntries[entryIndex].key;
                int normalAxis = surfaceKey.w < 2 ? 0 : (surfaceKey.w < 4 ? 1 : 2);
                int uAxis = normalAxis == 0 ? 1 : 0;
                int vAxis = normalAxis == 2 ? 1 : 2;
                vec3 localPosition = worldPos - vec3(surfaceKey.xyz);
                for (int coverageIndex = 0; coverageIndex < 96; coverageIndex++) {
                    if (coverageIndex >= coveredLightCount) break;
                    int identityScalar = coverageIndex * 3;
                    int identityVector = identityScalar >> 2;
                    int identityComponent = identityScalar & 3;
                    int payloadScalar = identityScalar + 1;
                    int payloadVector = payloadScalar >> 2;
                    int payloadComponent = payloadScalar & 3;
                    int visibilityScalar = identityScalar + 2;
                    int visibilityVector = visibilityScalar >> 2;
                    int visibilityComponent = visibilityScalar & 3;
                    uint identity = surfaceDirectLightCacheEntries[entryIndex]
                        .lightRecords[identityVector][identityComponent];
                    uint packed = surfaceDirectLightCacheEntries[entryIndex]
                        .lightRecords[payloadVector][payloadComponent];
                    uint packedVisibility = surfaceDirectLightCacheEntries[entryIndex]
                        .lightRecords[visibilityVector][visibilityComponent];
                    float visibility = interpolateSurfaceDirectVisibility(
                        packedVisibility,
                        localPosition,
                        uAxis,
                        vAxis);
                    if (visibility <= 0.0) continue;

                    ivec3 relativePosition = ivec3(
                        int(identity & 127u) - 64,
                        int((identity >> 7u) & 127u) - 64,
                        int((identity >> 14u) & 127u) - 64);
                    uint packedEmission = (identity >> 21u) & 15u;
                    uint packedRadius = (identity >> 25u) & 127u;
                    GpuSectionLight cachedLight = GpuSectionLight(
                        ivec4(surfaceKey.xyz + relativePosition, int(packedRadius)),
                        uvec4(
                            packed & 255u,
                            (packed >> 8u) & 255u,
                            (packed >> 16u) & 255u,
                            packedEmission));
                    vec3 localSpecular;
                    vec3 diffuse = evaluateLocalBlocklightRecord(
                        cachedLight,
                        worldPos,
                        normal,
                        viewDir,
                        diffuseResponse,
                        f0,
                        roughness,
                        visibility,
                        LOCAL_BLOCKLIGHT_DIRECT_SCALE,
                        localSpecular);
                    if (dot(diffuse + localSpecular, vec3(0.2126, 0.7152, 0.0722)) <= 0.0001) {
                        continue;
                    }
                    result += diffuse;
                    viewDependentSpecular += localSpecular;
                }
                return max(result, vec3(0.0));
            }

            bool isBlockId(float blockId, float expected) {
                return abs(blockId - expected) < 0.5;
            }

            bool isRefractiveBlock(float blockId) {
                return isBlockId(blockId, BLOCK_ID_WATER)
                    || isBlockId(blockId, BLOCK_ID_GLASS)
                    || isBlockId(blockId, BLOCK_ID_ICE)
                    || isBlockId(blockId, BLOCK_ID_CRYSTAL);
            }

            bool hasEncodedEmission(float encodedEmission) {
                return encodedEmission < -0.5;
            }

            float decodeEncodedEmission(float encodedEmission) {
                return clamp(-encodedEmission - 1.0, 0.0, 1.0);
            }

            vec3 visibleEmissionRadiance(vec3 radiance) {
                vec3 safeRadiance = max(radiance, vec3(0.0));
                float luma = dot(safeRadiance, vec3(0.299, 0.587, 0.114));
                if (luma <= 0.0001) return vec3(0.0);
                float visibleStrength = (1.0 - exp(-luma))
                    * EMISSIVE_SURFACE_VISIBLE_INTENSITY;
                return safeRadiance * (visibleStrength / luma);
            }

            int specularTransportMedium(float blockId) {
                if (isBlockId(blockId, BLOCK_ID_WATER)) return 1;
                if (isBlockId(blockId, BLOCK_ID_GLASS)) return 2;
                if (isBlockId(blockId, BLOCK_ID_ICE)) return 3;
                if (isBlockId(blockId, BLOCK_ID_CRYSTAL)) return 4;
                return 0;
            }

            vec2 signNotZero(vec2 value) {
                return vec2(value.x >= 0.0 ? 1.0 : -1.0, value.y >= 0.0 ? 1.0 : -1.0);
            }

            int specularTransportDirectionBucket(vec3 direction) {
                direction = normalize(direction);
                vec2 oct = direction.xy / max(abs(direction.x) + abs(direction.y) + abs(direction.z), 1e-6);
                if (direction.z < 0.0) {
                    oct = (vec2(1.0) - abs(oct.yx)) * signNotZero(oct);
                }
                ivec2 quantized = clamp(ivec2(round((oct * 0.5 + 0.5) * 7.0)), ivec2(0), ivec2(7));
                return quantized.x | (quantized.y << 3);
            }

            int specularTransportMaterialBucket(float metallic, float roughness, float blockId, float surfaceAlpha) {
                int bucket = int(round(clamp(roughness, 0.0, 1.0) * 31.0));
                if (metallic > 0.5) bucket |= 32;
                if (blockId > 0.0) bucket |= 64;
                if (surfaceAlpha < 0.98 && !isBlockId(blockId, 1007.0)) bucket |= 128;
                return clamp(bucket, 0, 255);
            }

            ivec4 specularTransportKey(
                vec3 worldPosition,
                vec3 normal,
                float roughness,
                float metallic,
                float blockId,
                float surfaceAlpha
            ) {
                int variant = specularTransportDirectionBucket(normal)
                    | (int(round(clamp(roughness, 0.0, 1.0) * 15.0)) << 8)
                    | (specularTransportMaterialBucket(metallic, roughness, blockId, surfaceAlpha) << 16);
                return ivec4(ivec3(floor(worldPosition)), variant);
            }

            uint specularTransportCacheHash(ivec4 key, uint family, uint detail) {
                uint value = uint(key.x) * 0x9e3779b9u;
                value ^= uint(key.y) * 0x85ebca6bu;
                value ^= uint(key.z) * 0xc2b2ae35u;
                value ^= uint(key.w) * 0x27d4eb2du;
                value ^= family * 0x165667b1u;
                value ^= detail * 0xd3a2646cu;
                return diffuseRadianceHashMix(value);
            }

            bool readSpecularTransportCache(
                ivec4 key,
                uint family,
                uint detail,
                out vec3 reflectedRadiance,
                out vec3 refractedRadiance,
                out float reflectionDistance,
                out float refractionDistance
            ) {
                reflectedRadiance = vec3(0.0);
                refractedRadiance = vec3(0.0);
                reflectionDistance = UNKNOWN_SPECULAR_HIT_DISTANCE;
                refractionDistance = UNKNOWN_SPECULAR_HIT_DISTANCE;
                uint entryCount = specularTransportCacheHeader.x;
                uint probeCount = min(specularTransportCacheHeader.z, 8u);
                if (entryCount == 0u || probeCount == 0u) return false;

                uint readyState = 0x80000000u | (specularTransportCacheHeader.y & 0x3fffffffu);
                uint slot = specularTransportCacheHash(key, family, detail) % entryCount;
                for (uint probe = 0u; probe < 8u; probe++) {
                    if (probe >= probeCount) break;
                    uint index = (slot + probe) % entryCount;
                    SpecularTransportCacheEntry entry = specularTransportCacheEntries[index];
                    if (entry.metadata.w == readyState
                            && entry.metadata.x == family
                            && entry.metadata.y == detail
                            && all(equal(entry.key, key))) {
                        reflectedRadiance = unpackDiffuseRadiance(entry.payload.x);
                        refractedRadiance = unpackDiffuseRadiance(entry.payload.y);
                        reflectionDistance = uintBitsToFloat(entry.payload.z);
                        refractionDistance = uintBitsToFloat(entry.payload.w);
                        return true;
                    }
                }
                return false;
            }

            vec3 fresnelSchlickResolve(float cosTheta, vec3 f0) {
                return f0 + (vec3(1.0) - f0) * pow(1.0 - clamp(cosTheta, 0.0, 1.0), 5.0);
            }

            vec3 waterTintResolve(vec3 albedo) {
                return mix(vec3(0.02, 0.48, 0.65), max(albedo, vec3(0.02)), 0.18);
            }

            vec3 waterAbsorptionResolve(float distance) {
                return exp(-(vec3(1.0) - vec3(0.02, 0.48, 0.65))
                    * min(max(distance, 0.0), 48.0) * 0.14 * 0.22);
            }

            vec3 iceTintResolve(vec3 albedo) {
                return mix(vec3(0.72, 0.90, 1.0), max(albedo, vec3(0.35)), 0.35);
            }

            vec3 iceAbsorptionResolve(float distance) {
                return exp(-vec3(0.10, 0.035, 0.015)
                    * min(max(distance, 0.0), 32.0) * 0.38);
            }

            vec2 encodeHistoryNormal(vec3 normal) {
                normal = normalize(normal);
                vec2 oct = normal.xy / max(abs(normal.x) + abs(normal.y) + abs(normal.z), 1e-6);
                if (normal.z < 0.0) {
                    oct = (vec2(1.0) - abs(oct.yx)) * signNotZero(oct);
                }
                return oct * 0.5 + 0.5;
            }

            vec3 decodeHistoryNormal(vec2 encoded) {
                vec2 oct = encoded * 2.0 - 1.0;
                vec3 normal = vec3(oct, 1.0 - abs(oct.x) - abs(oct.y));
                if (normal.z < 0.0) {
                    normal.xy = (vec2(1.0) - abs(normal.yx)) * signNotZero(normal.xy);
                }
                return normalize(normal);
            }

            bool readSpecularScreenHistory(
                ivec2 previousPixel,
                vec3 normal,
                float linearDepth,
                uint keySignature,
                out vec3 radiance,
                out float hitDistance
            ) {
                radiance = vec3(0.0);
                hitDistance = UNKNOWN_SPECULAR_HIT_DISTANCE;
                if (any(lessThan(previousPixel, ivec2(0)))
                        || any(greaterThanEqual(previousPixel, imageSize(previousSpecularHistory)))) {
                    return false;
                }
                vec4 history = imageLoad(previousSpecularHistory, previousPixel);
                vec4 surface = imageLoad(previousSpecularSurfaceHistory, previousPixel);
                if (!finiteVec4(history) || !finiteVec4(surface)
                        || surface.z <= 0.0
                        || history.a <= 0.0
                        || history.a > UNKNOWN_SPECULAR_HIT_DISTANCE
                        || uint(round(surface.w)) != (keySignature & 0x000007ffu)) {
                    return false;
                }
                float depthTolerance = max(0.25, linearDepth * 0.025);
                if (abs(surface.z - linearDepth) > depthTolerance
                        || dot(decodeHistoryNormal(surface.xy), normal) < 0.96) {
                    return false;
                }
                radiance = max(history.rgb, vec3(0.0));
                hitDistance = history.a;
                return true;
            }

            // Match ray0.rgen: normalized world-space normals use [0, 1] RGB.
            vec3 encodeDlssNormalGuide(vec3 worldNormal) {
                return normalize(worldNormal) * 0.5 + 0.5;
            }

            vec4 debugCell(
                int cell,
                bool hit,
                vec3 albedo,
                vec3 normal,
                float linearDepth,
                vec2 motion,
                float roughness,
                vec3 f0,
                vec3 finalColor,
                vec4 extraRaw
            ) {
                if (cell == 0) {
                    return vec4(hit ? albedo : vec3(0.0), 1.0);
                } else if (cell == 1) {
                    return vec4(hit ? normal * 0.5 + 0.5 : vec3(0.5, 1.0, 0.5), 1.0);
                } else if (cell == 2) {
                    float depthVis = hit ? 1.0 - clamp(linearDepth / 100.0, 0.0, 1.0) : 0.0;
                    return vec4(vec3(depthVis), 1.0);
                } else if (cell == 3) {
                    vec2 size = vec2(imageSize(outputImage));
                    vec2 motionVis = motion / max(max(size.x, size.y), 1.0);
                    return vec4(motionVis * 0.5 + 0.5, length(motionVis) * 8.0, 1.0);
                } else if (cell == 4) {
                    return vec4(vec3((roughness - 0.04) / 0.96), 1.0);
                } else if (cell == 5) {
                    return vec4(clamp(f0 * 2.0, vec3(0.0), vec3(1.0)), 1.0);
                } else if (cell == 8) {
                    return vec4(clamp(extraRaw.rgb, vec3(0.0), vec3(1.0)), 1.0);
                }
                return vec4(finalColor, 1.0);
            }

            void writeSkySidecars(ivec2 pixel) {
                imageStore(motionVectors, pixel, vec4(0.0));
                imageStore(linearDepthImage, pixel, vec4(10000.0));
                imageStore(DiffuseAlbedoMetallic, pixel, vec4(0.0));
                imageStore(SpecularAlbedo, pixel, vec4(0.0));
                imageStore(NormalRoughness, pixel, vec4(0.5, 1.0, 0.5, 1.0));
                imageStore(FirstHitDepth, pixel, vec4(10000.0));
                imageStore(SpecularHitDepth, pixel, vec4(10000.0));
                imageStore(blocklightDetailImage, pixel, vec4(0.0));
                imageStore(currentSpecularHistory, pixel, vec4(0.0));
                imageStore(currentSpecularSurfaceHistory, pixel, vec4(0.0));
            }

            void writeAlbedoFallbackSidecars(ivec2 pixel, vec3 albedo, vec4 materialRaw) {
                writeSkySidecars(pixel);
                vec3 f0 = finiteVec4(materialRaw)
                    ? clamp(materialRaw.rgb, vec3(0.0), vec3(1.0))
                    : vec3(0.04);
                float roughness = finiteVec4(materialRaw)
                    ? clamp(materialRaw.a, 0.04, 1.0)
                    : 1.0;
                imageStore(DiffuseAlbedoMetallic, pixel, vec4(albedo, 0.0));
                imageStore(SpecularAlbedo, pixel, vec4(f0, 1.0));
                imageStore(NormalRoughness, pixel, vec4(0.5, 1.0, 0.5, roughness));
            }

            void main() {
                ivec2 pixel = ivec2(gl_GlobalInvocationID.xy);
                ivec2 size = imageSize(outputImage);
                if (pixel.x >= size.x || pixel.y >= size.y) {
                    return;
                }

                vec2 launchSize = vec2(size);
                vec2 pixelCenter = vec2(pixel) + vec2(0.5) + cam.jitterData.xy;
                vec2 uv = pixelCenter / launchSize;
                vec3 target = mix(
                    mix(cam.corners[0], cam.corners[2], uv.y),
                    mix(cam.corners[1], cam.corners[3], uv.y),
                    uv.x);
                vec3 direction = normalize((cam.viewInverse * vec4(target, 0.0)).xyz);
                vec3 origin = cam.viewInverse[3].xyz;

                // Iris G-buffers stay at output resolution while DLSS/RR may
                // run this resolve at a lower render resolution. Map by screen
                // UV instead of reading the upper-left render-sized rectangle.
                vec2 gbufferUv = (vec2(pixel) + vec2(0.5)) / launchSize;
                ivec2 g0 = clamp(ivec2(gbufferUv * vec2(textureSize(gbufferWorldPos, 0))),
                    ivec2(0), textureSize(gbufferWorldPos, 0) - 1);
                vec4 gPos = texelFetch(gbufferWorldPos, g0, 0);
                vec4 gNormalRaw = texelFetch(gbufferNormal,
                    clamp(ivec2(gbufferUv * vec2(textureSize(gbufferNormal, 0))), ivec2(0), textureSize(gbufferNormal, 0) - 1), 0);
                vec4 gAlbedoRaw = texelFetch(gbufferAlbedo,
                    clamp(ivec2(gbufferUv * vec2(textureSize(gbufferAlbedo, 0))), ivec2(0), textureSize(gbufferAlbedo, 0) - 1), 0);
                vec4 gMaterialRaw = texelFetch(gbufferMaterial,
                    clamp(ivec2(gbufferUv * vec2(textureSize(gbufferMaterial, 0))), ivec2(0), textureSize(gbufferMaterial, 0) - 1), 0);
                vec4 gExtraRaw = texelFetch(gbufferExtra,
                    clamp(ivec2(gbufferUv * vec2(textureSize(gbufferExtra, 0))), ivec2(0), textureSize(gbufferExtra, 0) - 1), 0);

                if (pc.clearReservoir != 0) {
                    imageStore(reservoirImage, pixel, vec4(0.0));
                }

                bool hit = validHybridSample(gPos, gNormalRaw, gAlbedoRaw, gMaterialRaw, gExtraRaw);
                vec3 sunDirWorld = vec3(pc.sunDirX, pc.sunDirY, pc.sunDirZ);
                if (dot(sunDirWorld, sunDirWorld) < 0.000001) {
                    sunDirWorld = vec3(0.5, 1.0, 0.2);
                }
                sunDirWorld = normalize(sunDirWorld);
                vec3 lightDir = sunDirWorld;
                vec3 sunColor = sunColorFromElevation(
                    lightDir,
                    vec3(pc.sunColorR, pc.sunColorG, pc.sunColorB) * pc.sunIntensity);

                if (!hit) {
                    bool hasAlbedo = finiteVec4(gAlbedoRaw)
                        && (gAlbedoRaw.a > 0.001 || any(greaterThan(abs(gAlbedoRaw.rgb), vec3(0.001))));
                    if (hasAlbedo) {
                        vec3 fallbackAlbedo = clamp(gAlbedoRaw.rgb, vec3(0.0), vec3(1.0));
                        writeAlbedoFallbackSidecars(pixel, fallbackAlbedo, gMaterialRaw);
                        imageStore(outputImage, pixel, vec4(fallbackAlbedo, 1.0));
                    } else {
                        writeSkySidecars(pixel);
                        vec3 moonDirection = normalize(cam.moonPosition.xyz);
                        vec4 volume = cachedVolumetrics(
                            direction, 10000.0, lightDir, sunColor, 1.0);
                        vec3 sky = skyColor(
                            direction,
                            lightDir,
                            moonDirection,
                            sunColor,
                            float(cam.frameId) * 0.016);
                        imageStore(outputImage, pixel, vec4(sky * volume.a + volume.rgb, 1.0));
                    }
                    return;
                }

                vec3 albedo = max(gAlbedoRaw.rgb, vec3(0.0));
                vec3 normal = normalize(gNormalRaw.xyz);
                vec3 f0 = clamp(gMaterialRaw.rgb, vec3(0.0), vec3(1.0));
                float roughness = clamp(gMaterialRaw.a, 0.04, 1.0);
                float materialTag = gPos.w;
                float blockId = materialTag >= 999.5 ? floor(materialTag + 0.5) : 0.0;
                float metallic = materialTag >= 999.5 ? 0.0 : clamp(materialTag, 0.0, 1.0);
                float surfaceAlpha = clamp(gAlbedoRaw.a, 0.0, 1.0);
                float ao = clamp(gExtraRaw.b, 0.0, 1.0);
                float blocklight = clamp(gExtraRaw.r, 0.0, 1.0);
                float skylight = clamp(gExtraRaw.g, 0.0, 1.0);
                bool waterSurface = isBlockId(blockId, BLOCK_ID_WATER);
                bool glassSurface = isBlockId(blockId, BLOCK_ID_GLASS);
                bool iceSurface = isBlockId(blockId, BLOCK_ID_ICE);
                bool crystalSurface = isBlockId(blockId, BLOCK_ID_CRYSTAL);
                bool thinTransparentSurface = surfaceAlpha < 0.98
                    && !isBlockId(blockId, 1007.0)
                    && !waterSurface
                    && !glassSurface
                    && !iceSurface;
                float diffuseOpacity = waterSurface
                    ? 0.08
                    : (glassSurface
                        ? 0.12
                        : (iceSurface
                            ? 0.38
                            : (crystalSurface
                                ? 0.72
                                : (thinTransparentSurface ? surfaceAlpha : 1.0))));
                vec3 diffuseAlbedo = albedo * diffuseOpacity;
                vec3 directEmission = hasEncodedEmission(gExtraRaw.a)
                    ? visibleEmissionRadiance(
                        albedo
                        * decodeEncodedEmission(gExtraRaw.a)
                        * EMISSION_INTENSITY
                        * EMISSIVE_SURFACE_INTENSITY)
                    : vec3(0.0);

                vec3 absWorldPos = gPos.xyz + origin;
                vec4 prevClip = cam.prevViewProj * vec4(absWorldPos, 1.0);
                vec2 prevUv = uv;
                bool validPrev = prevClip.w > 1e-5 && !any(isnan(prevClip)) && !any(isinf(prevClip));
                if (validPrev) {
                    vec3 prevNdc = prevClip.xyz / prevClip.w;
                    validPrev = all(lessThanEqual(abs(prevNdc.xy), vec2(1.25)));
                    if (validPrev) {
                        prevUv = prevNdc.xy * 0.5 + 0.5;
                    }
                }
                vec2 motion = validPrev ? clamp(prevUv * launchSize - pixelCenter, -launchSize, launchSize) : vec2(0.0);
                ivec2 previousPixel = validPrev ? ivec2(prevUv * launchSize) : ivec2(-1);

                vec3 cameraForward = -normalize(cam.viewInverse[2].xyz);
                float linearDepth = max(dot(gPos.xyz, cameraForward), 0.0);
                vec3 viewDir = normalize(origin - absWorldPos);

                imageStore(motionVectors, pixel, vec4(motion, 0.0, 0.0));
                imageStore(linearDepthImage, pixel, vec4(linearDepth));
                imageStore(DiffuseAlbedoMetallic, pixel, vec4(clamp(albedo, vec3(0.0), vec3(1.0)), metallic));
                imageStore(SpecularAlbedo, pixel, vec4(f0, 1.0));
                imageStore(NormalRoughness, pixel, vec4(encodeDlssNormalGuide(normal), roughness));
                imageStore(FirstHitDepth, pixel, vec4(linearDepth));

                // The scalar skylight guide is the stable no-RT directional
                // occlusion source. Do not inject a permanent 18% sun term into
                // caves and roofs while directional visibility is uncached.
                float directionalVisibility = skylight * skylight;
                float dayAmount = smoothstep(-0.1, 0.3, lightDir.y);
                float duskAmount = smoothstep(-0.15, 0.05, lightDir.y)
                    * (1.0 - smoothstep(0.05, 0.25, lightDir.y));
                float skyVisibility = max(normal.y * 0.5 + 0.5, 0.15);
                vec3 ambientDay = mix(
                    vec3(0.35, 0.45, 0.6),
                    vec3(0.5, 0.6, 0.8),
                    skyVisibility);
                vec3 ambientDusk = mix(
                    vec3(0.2, 0.12, 0.08),
                    vec3(0.35, 0.2, 0.15),
                    skyVisibility);
                vec3 ambientNight = mix(
                    vec3(0.01, 0.015, 0.025),
                    vec3(0.02, 0.03, 0.06),
                    skyVisibility);
                vec3 ambientColor = mix(ambientNight, ambientDay, dayAmount)
                    + ambientDusk * duskAmount;
                vec3 ambient = diffuseAlbedo * ambientColor * pc.ambientFactor
                    * (0.3 + 0.7 * ao);
                vec3 diffuseResponse = diffuseAlbedo
                    * max(vec3(1.0) - f0, vec3(0.0))
                    * (1.0 - metallic);
                bool emissiveFirstHitSurface = dot(
                    directEmission,
                    vec3(0.2126, 0.7152, 0.0722)) > 0.0001;
                vec3 cachedIncident = vec3(0.0);
                bool cacheHit = !emissiveFirstHitSurface
                    && readVoxelProbeRadiance(absWorldPos, normal, cachedIncident);
                float cacheConfidence = cacheHit ? 1.0 : 0.0;
                vec3 genericLocalFallback = emissiveFirstHitSurface
                    ? vec3(0.0)
                    : diffuseAlbedo * blocklight * vec3(1.0, 0.74, 0.50) * 0.62;
                vec3 fallbackLocalSpecular = vec3(0.0);
                vec3 cachedLocalSpecular = vec3(0.0);
                bool surfaceDirectHit = cacheHit;
                vec3 resolvedLocalLight = cacheHit
                    ? cachedIncident * diffuseResponse
                    : genericLocalFallback;
                vec3 directDiffuse;
                vec3 directSpecular;
                evaluatePbrSplit(
                    diffuseAlbedo,
                    normal,
                    viewDir,
                    lightDir,
                    sunColor,
                    f0,
                    roughness,
                    metallic,
                    pc.specularIntensity,
                    directDiffuse,
                    directSpecular);
                directDiffuse *= directionalVisibility;
                directSpecular *= directionalVisibility;
                // The persistent section voxels currently hold visibility-
                // resolved local incident radiance. Global diffuse bounces can
                // be added to the same six faces later without reintroducing a
                // camera-visible surface bake.
                vec3 diffuseIndirect = vec3(0.0);
                vec3 cachedSpecular = vec3(0.0);
                float specularHitDistance = UNKNOWN_SPECULAR_HIT_DISTANCE;
                bool specularResultValid = false;
                uint specularHistorySignature = 0u;
                ivec4 transportKey = specularTransportKey(
                    absWorldPos, normal, roughness, metallic, blockId, surfaceAlpha);
                bool refractiveSurface = isRefractiveBlock(blockId) || thinTransparentSurface;
                if (refractiveSurface) {
                    vec3 incident = -viewDir;
                    vec3 reflectionDirection = normalize(reflect(incident, normal));
                    float ior = isBlockId(blockId, BLOCK_ID_WATER)
                        ? 1.333
                        : (isBlockId(blockId, BLOCK_ID_ICE)
                            ? 1.31
                            : (isBlockId(blockId, BLOCK_ID_CRYSTAL) ? 2.20 : 1.50));
                    vec3 refractionDirection = refract(incident, normal, 1.0 / ior);
                    if (dot(refractionDirection, refractionDirection) <= 1e-6) {
                        refractionDirection = reflectionDirection;
                    } else {
                        refractionDirection = normalize(refractionDirection);
                    }
                    uint transportDetail = uint(specularTransportMedium(blockId))
                        | (uint(specularTransportDirectionBucket(reflectionDirection)) << 8u)
                        | (uint(specularTransportDirectionBucket(refractionDirection)) << 16u);
                    specularHistorySignature = specularTransportCacheHash(
                        transportKey, 1u, transportDetail);
                    vec3 reflectedRadiance;
                    vec3 refractedRadiance;
                    float reflectionDistance;
                    float refractionDistance;
                    bool transportHit = readSpecularTransportCache(
                        transportKey,
                        1u,
                        transportDetail,
                        reflectedRadiance,
                        refractedRadiance,
                        reflectionDistance,
                        refractionDistance);
                    if (transportHit) {
                        vec3 fresnel = fresnelSchlickResolve(max(dot(normal, viewDir), 0.0), f0);
                        if (isBlockId(blockId, BLOCK_ID_WATER)) {
                            fresnel = clamp(fresnel * 1.15, vec3(0.0), vec3(1.0));
                            vec3 tint = waterTintResolve(albedo);
                            vec3 absorption = waterAbsorptionResolve(refractionDistance);
                            refractedRadiance = refractedRadiance * absorption
                                + tint * (vec3(1.0) - absorption);
                        } else if (isBlockId(blockId, BLOCK_ID_GLASS)) {
                            refractedRadiance *= mix(vec3(1.0), max(albedo, vec3(0.2)), 0.28) * 0.88;
                        } else if (isBlockId(blockId, BLOCK_ID_ICE)) {
                            vec3 tint = iceTintResolve(albedo);
                            vec3 absorption = iceAbsorptionResolve(refractionDistance);
                            refractedRadiance = refractedRadiance * absorption * tint
                                + tint * (vec3(1.0) - absorption) * 0.18;
                        } else if (isBlockId(blockId, BLOCK_ID_CRYSTAL)) {
                            refractedRadiance *= mix(vec3(1.0), max(albedo, vec3(0.12)), 0.55) * 0.28;
                        } else {
                            refractedRadiance *= mix(vec3(1.0), max(albedo, vec3(0.1)), 0.35)
                                * surfaceAlpha;
                        }
                        cachedSpecular = mix(refractedRadiance, reflectedRadiance, fresnel)
                            * pc.specularIntensity;
                        specularHitDistance = min(reflectionDistance, refractionDistance);
                        specularResultValid = true;
                    } else {
                        specularResultValid = readSpecularScreenHistory(
                            previousPixel,
                            normal,
                            linearDepth,
                            specularHistorySignature,
                            cachedSpecular,
                            specularHitDistance);
                    }
                } else {
                    float reflectionRoughnessLimit = mix(0.62, 0.88, metallic);
                    float surfaceFresnelLuma = dot(
                        fresnelSchlickResolve(max(dot(normal, viewDir), 0.0), f0),
                        vec3(0.2126, 0.7152, 0.0722));
                    if (roughness < reflectionRoughnessLimit
                            && (metallic > 0.5 || surfaceFresnelLuma > 0.025)) {
                        vec3 reflectionDirection = normalize(reflect(-viewDir, normal));
                        uint transportDetail = uint(specularTransportDirectionBucket(reflectionDirection));
                        specularHistorySignature = specularTransportCacheHash(
                            transportKey, 0u, transportDetail);
                        vec3 reflectedRadiance;
                        vec3 unusedRefraction;
                        float reflectionDistance;
                        float unusedRefractionDistance;
                        bool transportHit = readSpecularTransportCache(
                            transportKey,
                            0u,
                            transportDetail,
                            reflectedRadiance,
                            unusedRefraction,
                            reflectionDistance,
                            unusedRefractionDistance);
                        if (transportHit) {
                            vec3 fresnel = fresnelSchlickResolve(max(dot(normal, viewDir), 0.0), f0);
                            float reflectionStrength = mix(0.65, 1.0, metallic)
                                * (1.0 - roughness * roughness);
                            float envBrightness = dot(reflectedRadiance, vec3(0.299, 0.587, 0.114));
                            reflectionStrength *= 0.2 + 0.8 * smoothstep(0.0, 0.3, envBrightness);
                            cachedSpecular = reflectedRadiance * fresnel * reflectionStrength
                                * pc.specularIntensity;
                            specularHitDistance = reflectionDistance;
                            specularResultValid = true;
                        } else {
                            specularResultValid = readSpecularScreenHistory(
                                previousPixel,
                                normal,
                                linearDepth,
                                specularHistorySignature,
                                cachedSpecular,
                                specularHitDistance);
                        }
                    }
                }
                // A first-surface depth is not a continuation-ray hit distance.
                // Preserve the explicit miss sentinel until a valid cache entry exists.
                imageStore(SpecularHitDepth, pixel, vec4(specularHitDistance));
                if (specularResultValid) {
                    imageStore(currentSpecularHistory, pixel,
                        vec4(cachedSpecular, specularHitDistance));
                    imageStore(currentSpecularSurfaceHistory, pixel,
                        vec4(encodeHistoryNormal(normal), linearDepth,
                            float(specularHistorySignature & 0x000007ffu)));
                } else {
                    imageStore(currentSpecularHistory, pixel, vec4(0.0));
                    imageStore(currentSpecularSurfaceHistory, pixel, vec4(0.0));
                }
                vec3 resolvedLocalSpecular = (surfaceDirectHit
                    ? cachedLocalSpecular
                    : fallbackLocalSpecular) * pc.specularIntensity;
                vec3 minLight = diffuseAlbedo * pc.minLighting
                    * (0.2 + 0.8 * ao) * 0.08;
                // Missing transport uses stable G-buffer/lightmap fallbacks.
                // Publication enriches that image instead of switching from
                // flat albedo to a completely different exposure.
                // Camera-independent transport is the stable base. Fresnel,
                // local highlights and reflection/refraction are additive
                // resolve layers; a view change never invalidates the base.
                vec3 worldLightingBase = directEmission
                    + directDiffuse
                    + ambient
                    + resolvedLocalLight
                    + diffuseIndirect
                    + minLight;
                vec3 viewDependentLighting = directSpecular
                    + resolvedLocalSpecular
                    + cachedSpecular;
                vec4 volume = cachedVolumetrics(
                    direction,
                    linearDepth,
                    lightDir,
                    sunColor,
                    directionalVisibility);
                vec3 finalColor = linearColorGrade(
                    (worldLightingBase + viewDependentLighting) * volume.a + volume.rgb);

                imageStore(blocklightDetailImage, pixel,
                    vec4(resolvedLocalLight + directEmission,
                        max(surfaceDirectHit ? 1.0 : blocklight, cacheConfidence)));

                vec4 color = vec4(finalColor, 1.0);
                if (pc.debugMode == 1) {
                    int cell;
                    if (pc.debugCellIndex < 0) {
                        vec2 cellPos = vec2(pixel) / launchSize * 3.0;
                        cell = clamp(int(cellPos.y), 0, 2) * 3 + clamp(int(cellPos.x), 0, 2);
                    } else {
                        cell = clamp(pc.debugCellIndex, 0, 8);
                    }
                    color = debugCell(cell, hit, albedo, normal, linearDepth, motion, roughness, f0, finalColor, gExtraRaw);
                } else if (pc.debugMode == 2) {
                    int quadrant = 0;
                    vec2 q = vec2(pixel) / launchSize;
                    if (q.x >= 0.5) {
                        quadrant += 1;
                    }
                    if (q.y >= 0.5) {
                        quadrant += 2;
                    }
                    int activeQuadrant = pc.debugCellIndex < 0 ? quadrant : clamp(pc.debugCellIndex, 0, 3);
                    if (activeQuadrant == 0) {
                        float depthVis = 1.0 - clamp(linearDepth / 100.0, 0.0, 1.0);
                        color = vec4(depthVis * 0.5, depthVis * 0.8, depthVis, 1.0);
                    } else if (activeQuadrant == 1) {
                        vec2 motionVis = motion / max(max(launchSize.x, launchSize.y), 1.0);
                        color = vec4(motionVis * 0.5 + 0.5, length(motionVis) * 8.0, 1.0);
                    } else if (activeQuadrant == 2) {
                        color = vec4(blocklight, blocklight * 0.5, 1.0 - blocklight, 1.0);
                    }
                }

                imageStore(outputImage, pixel, color);
            }
            """;
}
