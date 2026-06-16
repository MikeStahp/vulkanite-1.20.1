package me.cortex.vulkanite.client.rendering;

import me.cortex.vulkanite.client.Vulkanite;
import me.cortex.vulkanite.lib.base.VContext;
import me.cortex.vulkanite.lib.base.VRef;
import me.cortex.vulkanite.lib.cmd.VCmdBuff;
import me.cortex.vulkanite.lib.descriptors.DescriptorSetLayoutBuilder;
import me.cortex.vulkanite.lib.descriptors.DescriptorUpdateBuilder;
import me.cortex.vulkanite.lib.descriptors.VDescriptorSetLayout;
import me.cortex.vulkanite.lib.memory.VImage;
import me.cortex.vulkanite.lib.other.VImageView;
import me.cortex.vulkanite.lib.other.VSampler;
import me.cortex.vulkanite.lib.pipeline.ComputePipelineBuilder;
import me.cortex.vulkanite.lib.pipeline.VComputePipeline;
import me.cortex.vulkanite.lib.shader.VShader;
import me.cortex.vulkanite.lib.shader.reflection.ShaderReflection;

import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Restores high-frequency Minecraft texture detail after temporal reconstruction.
 *
 * <p>The ray pass keeps blocklight in HDR radiance so DLSS can denoise it. A
 * separate blocklight image is upscaled as a low-frequency reference. This
 * pass may restore missing light energy, but it must never reinterpret albedo
 * differences as emitted radiance. Doing so projects block textures onto
 * nearby geometry.</p>
 */
final class StableBlocklightPass {
    private static final int WORKGROUP_SIZE = 8;

    private final VContext ctx;
    private final VRef<VSampler> sampler;
    private final VRef<VDescriptorSetLayout> descriptorLayout;
    private final VRef<VComputePipeline> pipeline;
    private final ShaderReflection.Set reflectedSet;

    StableBlocklightPass(VContext ctx, VRef<VSampler> sampler) {
        this.ctx = ctx;
        this.sampler = sampler;

        descriptorLayout = new DescriptorSetLayoutBuilder()
                .binding(0, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, VK_SHADER_STAGE_COMPUTE_BIT)
                .binding(1, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, VK_SHADER_STAGE_COMPUTE_BIT)
                .binding(2, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, VK_SHADER_STAGE_COMPUTE_BIT)
                .binding(3, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, VK_SHADER_STAGE_COMPUTE_BIT)
                .binding(4, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, VK_SHADER_STAGE_COMPUTE_BIT)
                .binding(5, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, VK_SHADER_STAGE_COMPUTE_BIT)
                .binding(6, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, VK_SHADER_STAGE_COMPUTE_BIT)
                .binding(7, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, VK_SHADER_STAGE_COMPUTE_BIT)
                .build(ctx);
        reflectedSet = createReflectedSet();

        VRef<VShader> shader = VShader.compileLoad(ctx, SHADER_SOURCE, VK_SHADER_STAGE_COMPUTE_BIT);
        var shaderModule = shader.get().named();
        try {
            pipeline = new ComputePipelineBuilder()
                    .setShader(shaderModule)
                    .addLayout(descriptorLayout)
                    .build(ctx);
        } finally {
            shaderModule.shader().close();
            shader.close();
        }
    }

    boolean execute(
            VCmdBuff cmd,
            VRef<VImage> color,
            VRef<VImageView>[] gbufferViews,
            VRef<VImage> blocklightReference,
            VRef<VImage> albedoReference) {
        if (color == null || blocklightReference == null || albedoReference == null
                || gbufferViews == null || gbufferViews.length < 5) {
            return false;
        }
        for (int i = 0; i < 5; i++) {
            if (gbufferViews[i] == null) {
                return false;
            }
        }

        VRef<VImageView> colorView = VImageView.create(ctx, color);
        VRef<VImageView> blocklightView = VImageView.create(ctx, blocklightReference);
        VRef<VImageView> albedoReferenceView = VImageView.create(ctx, albedoReference);
        var descriptorSet = Vulkanite.INSTANCE.getPoolByLayout(descriptorLayout).get().allocateSet();
        try {
            var updater = new DescriptorUpdateBuilder(ctx, reflectedSet)
                    .set(descriptorSet)
                    .imageStore(0, colorView);
            for (int i = 0; i < 5; i++) {
                updater.imageSampler(i + 1, gbufferViews[i], sampler);
            }
            updater.imageSampler(6, VK_IMAGE_LAYOUT_GENERAL, blocklightView, sampler);
            updater.imageSampler(7, VK_IMAGE_LAYOUT_GENERAL, albedoReferenceView, sampler);
            updater.apply();

            cmd.encodeImageTransition(color, VK_IMAGE_LAYOUT_GENERAL,
                    VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_ASPECT_COLOR_BIT, 1);
            cmd.bindCompute(pipeline);
            cmd.bindDSet(descriptorSet);
            cmd.dispatch(
                    (color.get().width + WORKGROUP_SIZE - 1) / WORKGROUP_SIZE,
                    (color.get().height + WORKGROUP_SIZE - 1) / WORKGROUP_SIZE,
                    1);
            cmd.encodeImageTransition(color, VK_IMAGE_LAYOUT_GENERAL,
                    VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_ASPECT_COLOR_BIT, 1);
            return true;
        } finally {
            descriptorSet.close();
            albedoReferenceView.close();
            blocklightView.close();
            colorView.close();
        }
    }

    void destroy() {
        pipeline.close();
        descriptorLayout.close();
    }

    private static ShaderReflection.Set createReflectedSet() {
        List<ShaderReflection.Binding> bindings = new ArrayList<>();
        bindings.add(new ShaderReflection.Binding("", 0, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 0, false));
        for (int binding = 1; binding <= 7; binding++) {
            bindings.add(new ShaderReflection.Binding(
                    "", binding, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 0, false));
        }
        return new ShaderReflection.Set(new ArrayList<>(bindings));
    }

    private static final String SHADER_SOURCE = """
            #version 460

            layout(local_size_x = 8, local_size_y = 8) in;

            layout(binding = 0, rgba16f) uniform image2D hdrColor;
            layout(binding = 1) uniform sampler2D gbufferAlbedo;
            layout(binding = 2) uniform sampler2D gbufferMaterial;
            layout(binding = 3) uniform sampler2D gbufferNormal;
            layout(binding = 4) uniform sampler2D gbufferWorldPosition;
            layout(binding = 5) uniform sampler2D gbufferExtra;
            layout(binding = 6) uniform sampler2D blocklightReference;
            layout(binding = 7) uniform sampler2D albedoReference;

            const float PI = 3.14159265359;

            ivec2 sourceTexel(sampler2D source, vec2 uv) {
                ivec2 size = textureSize(source, 0);
                return clamp(ivec2(uv * vec2(size)), ivec2(0), size - 1);
            }

            void main() {
                ivec2 pixel = ivec2(gl_GlobalInvocationID.xy);
                ivec2 outputSize = imageSize(hdrColor);
                if (any(greaterThanEqual(pixel, outputSize))) {
                    return;
                }

                vec2 uv = (vec2(pixel) + 0.5) / vec2(outputSize);
                vec4 albedoData = texelFetch(gbufferAlbedo, sourceTexel(gbufferAlbedo, uv), 0);
                vec4 materialData = texelFetch(gbufferMaterial, sourceTexel(gbufferMaterial, uv), 0);
                vec4 normalData = texelFetch(gbufferNormal, sourceTexel(gbufferNormal, uv), 0);
                vec4 positionData = texelFetch(gbufferWorldPosition, sourceTexel(gbufferWorldPosition, uv), 0);
                vec4 extraData = texelFetch(gbufferExtra, sourceTexel(gbufferExtra, uv), 0);

                float blocklight = clamp(extraData.r, 0.0, 1.0);
                float normalLengthSquared = dot(normalData.xyz, normalData.xyz);
                if (blocklight <= 0.01 || albedoData.a <= 0.001 || normalLengthSquared <= 0.01) {
                    return;
                }

                vec3 albedo = max(albedoData.rgb, vec3(0.0));
                vec3 normal = normalData.xyz * inversesqrt(normalLengthSquared);
                vec3 viewDir = normalize(-positionData.xyz);
                if (dot(normal, viewDir) < 0.0) {
                    normal = -normal;
                }

                float roughness = clamp(materialData.a, 0.04, 1.0);
                float metallic = clamp(positionData.a, 0.0, 1.0);
                vec3 f0 = clamp(materialData.rgb, vec3(0.0), vec3(1.0));
                vec3 lightDir = normalize(normal * 0.8 + vec3(0.0, 0.4, 0.0));
                vec3 halfVector = normalize(viewDir + lightDir);

                float nDotL = max(dot(normal, lightDir), 0.0);
                float nDotV = max(dot(normal, viewDir), 0.001);
                float nDotH = max(dot(normal, halfVector), 0.0);
                float vDotH = max(dot(viewDir, halfVector), 0.0);
                float alpha = roughness * roughness;
                float alphaSquared = alpha * alpha;
                float denominator = nDotH * nDotH * (alphaSquared - 1.0) + 1.0;
                float distribution = alphaSquared / max(PI * denominator * denominator, 1e-5);
                float geometryK = (roughness + 1.0) * (roughness + 1.0) * 0.125;
                float geometryV = nDotV / mix(nDotV, 1.0, geometryK);
                float geometryL = nDotL / mix(nDotL, 1.0, geometryK);
                vec3 fresnel = f0 + (1.0 - f0) * pow(1.0 - vDotH, 5.0);

                vec3 diffuse = (1.0 - fresnel) * (1.0 - metallic) * albedo / PI;
                vec3 specular = distribution * geometryV * geometryL * fresnel
                        / max(4.0 * nDotV * max(nDotL, 0.001), 1e-5);
                float blocklightExtended = clamp(pow(blocklight, 0.72) * 1.45, 0.0, 1.0);
                float blocklightIntensity = max(pow(blocklightExtended, 1.05), 0.08);
                vec3 lightColor = vec3(1.0, 0.82, 0.55) * blocklightIntensity * 8.0;
                vec3 targetBlocklight = (diffuse + specular) * lightColor * nDotL;
                vec3 reconstructedReference = texture(blocklightReference, uv).rgb;
                vec3 lightDetailCorrection = max(targetBlocklight - reconstructedReference, vec3(0.0));

                vec4 baseColor = imageLoad(hdrColor, pixel);
                imageStore(hdrColor, pixel,
                        vec4(baseColor.rgb + lightDetailCorrection, baseColor.a));
            }
            """;
}
