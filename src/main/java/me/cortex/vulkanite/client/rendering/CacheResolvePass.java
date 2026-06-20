package me.cortex.vulkanite.client.rendering;

import me.cortex.vulkanite.client.Vulkanite;
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

import java.util.ArrayList;
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
    private static final int PUSH_CONSTANT_BYTES = 48;

    private final VContext ctx;
    private final VRef<VSampler> sampler;
    private final VRef<VComputePipeline> pipeline;
    private final VRef<VDescriptorSetLayout> setLayout;
    private final ShaderReflection.Set setReflection;
    private final long[] pushConstants = new long[6];

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

        List<VRef<?>> resourcesToClose = new ArrayList<>(16);
        VRef<VDescriptorPool> pool = Vulkanite.INSTANCE.getPoolByLayout(setLayout);
        VRef<VDescriptorSet> set = null;
        try {
            set = pool.get().allocateSet();
            DescriptorUpdateBuilder updater = new DescriptorUpdateBuilder(ctx, setReflection)
                    .set(set)
                    .uniform(0, frame.uboBuffer(), frame.uboOffset(), frame.uboSize());

            updater.imageStore(6, createView(frame.currentReservoir(), resourcesToClose));
            bindGbuffer(updater, frame, 7, 0);
            bindGbuffer(updater, frame, 8, 1);
            bindGbuffer(updater, frame, 9, 2);
            bindGbuffer(updater, frame, 10, 3);
            bindGbuffer(updater, frame, 11, 4);
            updater.imageStore(12, createView(frame.noisyOutput(), resourcesToClose));
            updater.imageStore(13, createView(frame.motionVectors(), resourcesToClose));
            updater.imageStore(14, createView(frame.linearDepth(), resourcesToClose));
            updater.imageStore(16, createView(frame.diffuseAlbedoMetallic(), resourcesToClose));
            updater.imageStore(17, createView(frame.specularAlbedo(), resourcesToClose));
            updater.imageStore(18, createView(frame.normalRoughness(), resourcesToClose));
            updater.imageStore(19, createView(frame.specularHitDepth(), resourcesToClose));
            updater.imageStore(20, createView(frame.firstHitDepth(), resourcesToClose));
            updater.imageStore(21, createView(frame.blocklightDetail(), resourcesToClose));
            bindOptionalStorageBuffer(updater, frame.diffuseRadianceCacheBuffer(), 26);
            updater.apply();

            cmdPushConstants(frame, clearReservoir);
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
            for (VRef<?> ref : resourcesToClose) {
                ref.close();
            }
        }
    }

    private void cmdPushConstants(RtxPassGraph.Frame frame, boolean clearReservoir) {
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
    }

    private void bindGbuffer(DescriptorUpdateBuilder updater, RtxPassGraph.Frame frame, int binding, int index) {
        VRef<VImageView> view = frame.placeholderNormalsView();
        VRef<VImageView>[] gbufferViews = frame.gbufferViews();
        if (gbufferViews != null && index < gbufferViews.length && gbufferViews[index] != null) {
            view = gbufferViews[index];
        }
        updater.imageSampler(binding, view, sampler);
    }

    private VRef<VImageView> createView(VRef<VImage> image, List<VRef<?>> resourcesToClose) {
        VRef<VImageView> view = VImageView.create(ctx, image);
        resourcesToClose.add(view);
        return view;
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

            struct DiffuseRadianceCacheEntry {
                ivec4 key;
                uvec4 payload;
            };

            layout(binding = 26, std430) readonly buffer DiffuseRadianceCacheBuffer {
                uvec4 diffuseRadianceCacheHeader;
                DiffuseRadianceCacheEntry diffuseRadianceCacheEntries[];
            };

            const float UNKNOWN_SPECULAR_HIT_DISTANCE = 10000.0;

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
                return clamp(max(color, vec3(0.0)), vec3(0.0), vec3(32.0));
            }

            vec3 skyColor(vec3 direction, vec3 lightDir, vec3 sunColor) {
                float up = clamp(direction.y * 0.5 + 0.5, 0.0, 1.0);
                float day = smoothstep(-0.15, 0.25, lightDir.y);
                vec3 nightSky = mix(vec3(0.006, 0.010, 0.020), vec3(0.020, 0.030, 0.060), up);
                vec3 daySky = mix(vec3(0.45, 0.56, 0.72), vec3(0.10, 0.26, 0.62), up);
                float sunDisk = pow(max(dot(direction, lightDir), 0.0), 512.0);
                return mix(nightSky, daySky, day) + sunColor * sunDisk * day;
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
                        confidence = 0.45 * distanceConfidence;
                        return true;
                    }
                }
                return false;
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

                ivec2 g0 = clamp(pixel, ivec2(0), textureSize(gbufferWorldPos, 0) - 1);
                vec4 gPos = texelFetch(gbufferWorldPos, g0, 0);
                vec4 gNormalRaw = texelFetch(gbufferNormal, clamp(pixel, ivec2(0), textureSize(gbufferNormal, 0) - 1), 0);
                vec4 gAlbedoRaw = texelFetch(gbufferAlbedo, clamp(pixel, ivec2(0), textureSize(gbufferAlbedo, 0) - 1), 0);
                vec4 gMaterialRaw = texelFetch(gbufferMaterial, clamp(pixel, ivec2(0), textureSize(gbufferMaterial, 0) - 1), 0);
                vec4 gExtraRaw = texelFetch(gbufferExtra, clamp(pixel, ivec2(0), textureSize(gbufferExtra, 0) - 1), 0);

                if (pc.clearReservoir != 0) {
                    imageStore(reservoirImage, pixel, vec4(0.0));
                }

                bool hit = validHybridSample(gPos, gNormalRaw, gAlbedoRaw, gMaterialRaw, gExtraRaw);
                vec3 sunDirView = vec3(pc.sunDirX, pc.sunDirY, pc.sunDirZ);
                if (dot(sunDirView, sunDirView) < 0.000001) {
                    sunDirView = vec3(0.5, 1.0, 0.2);
                }
                vec3 lightDir = normalize(mat3(cam.viewInverse) * normalize(sunDirView));
                vec3 sunColor = vec3(pc.sunColorR, pc.sunColorG, pc.sunColorB) * 3.4;

                if (!hit) {
                    writeSkySidecars(pixel);
                    imageStore(outputImage, pixel, vec4(skyColor(direction, lightDir, sunColor), 1.0));
                    return;
                }

                vec3 albedo = max(gAlbedoRaw.rgb, vec3(0.0));
                vec3 normal = normalize(gNormalRaw.xyz);
                vec3 f0 = clamp(gMaterialRaw.rgb, vec3(0.0), vec3(1.0));
                float roughness = clamp(gMaterialRaw.a, 0.04, 1.0);
                float materialTag = gPos.w;
                float metallic = materialTag >= 999.5 ? 0.0 : clamp(materialTag, 0.0, 1.0);
                float ao = clamp(gExtraRaw.b, 0.0, 1.0);
                float blocklight = clamp(gExtraRaw.r, 0.0, 1.0);
                float skylight = clamp(gExtraRaw.g, 0.0, 1.0);

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

                vec3 cameraForward = -normalize(cam.viewInverse[2].xyz);
                float linearDepth = max(dot(gPos.xyz, cameraForward), 0.0);
                vec3 viewDir = normalize(origin - absWorldPos);

                imageStore(motionVectors, pixel, vec4(motion, 0.0, 0.0));
                imageStore(linearDepthImage, pixel, vec4(linearDepth));
                imageStore(DiffuseAlbedoMetallic, pixel, vec4(clamp(albedo, vec3(0.0), vec3(1.0)), metallic));
                imageStore(SpecularAlbedo, pixel, vec4(f0, 1.0));
                imageStore(NormalRoughness, pixel, vec4(encodeDlssNormalGuide(normal), roughness));
                imageStore(FirstHitDepth, pixel, vec4(linearDepth));
                // No reflection/refraction cache has supplied a continuation hit.
                // First-surface depth is not a valid specular hit-distance guide.
                imageStore(SpecularHitDepth, pixel, vec4(UNKNOWN_SPECULAR_HIT_DISTANCE));

                float nDotL = max(dot(normal, lightDir), 0.0);
                float day = smoothstep(-0.15, 0.25, lightDir.y);
                vec3 skyAmbient = mix(vec3(0.010, 0.015, 0.030), vec3(0.35, 0.45, 0.60), day);
                vec3 direct = albedo * sunColor * nDotL * (0.18 + 0.82 * skylight) * (0.25 + 0.75 * ao);
                vec3 ambient = albedo * skyAmbient * (0.10 + 0.40 * skylight) * (0.30 + 0.70 * ao);
                vec3 localLight = albedo * blocklight * vec3(1.0, 0.78, 0.48) * 1.15;
                ivec4 cacheKey = ivec4(
                    ivec3(floor(absWorldPos * 0.5)),
                    diffuseRadianceNormalBucket(normal));
                vec3 cachedIncident;
                float cacheConfidence;
                bool cacheHit = readDiffuseRadianceCache(cacheKey, cachedIncident, cacheConfidence);
                vec3 diffuseResponse = albedo * max(vec3(1.0) - f0, vec3(0.0)) * (1.0 - metallic);
                vec3 fallbackIndirect = ambient + localLight;
                vec3 cachedIndirect = cachedIncident * diffuseResponse + ambient * 0.20 + localLight * 0.25;
                vec3 diffuseIndirect = cacheHit
                    ? mix(fallbackIndirect, cachedIndirect, cacheConfidence)
                    : fallbackIndirect;
                vec3 halfway = normalize(lightDir + viewDir);
                float specPower = mix(96.0, 8.0, roughness);
                vec3 specular = f0 * pow(max(dot(normal, halfway), 0.0), specPower) * nDotL * (1.0 - roughness * 0.65);
                vec3 minLight = albedo * 0.004 * (0.2 + 0.8 * ao);
                vec3 finalColor = linearColorGrade(direct + diffuseIndirect + specular + minLight);

                imageStore(blocklightDetailImage, pixel,
                    vec4(localLight + (cacheHit ? cachedIncident * diffuseResponse : vec3(0.0)),
                        max(blocklight, cacheConfidence)));

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
