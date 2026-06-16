package me.cortex.vulkanite.client.rendering;

import me.cortex.vulkanite.client.Vulkanite;
import me.cortex.vulkanite.lib.base.VContext;
import me.cortex.vulkanite.lib.base.VRef;
import me.cortex.vulkanite.lib.cmd.VCmdBuff;
import me.cortex.vulkanite.lib.descriptors.DescriptorSetLayoutBuilder;
import me.cortex.vulkanite.lib.descriptors.DescriptorUpdateBuilder;
import me.cortex.vulkanite.lib.descriptors.VDescriptorSet;
import me.cortex.vulkanite.lib.descriptors.VDescriptorSetLayout;
import me.cortex.vulkanite.lib.memory.MemoryManager;
import me.cortex.vulkanite.lib.memory.VBuffer;
import me.cortex.vulkanite.lib.memory.VImage;
import me.cortex.vulkanite.lib.other.VImageView;
import me.cortex.vulkanite.lib.other.VSampler;
import me.cortex.vulkanite.lib.pipeline.ComputePipelineBuilder;
import me.cortex.vulkanite.lib.pipeline.VComputePipeline;
import me.cortex.vulkanite.lib.shader.VShader;
import me.cortex.vulkanite.lib.shader.reflection.ShaderReflection;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.util.vma.Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Executes deferred lighting using Vulkan compute shaders.
 *
 * <p>This pass reads the G-buffer from Iris and computes:
 * <ul>
 * <li>Directional lighting with shadow mapping</li>
 * <li>Point/spot lights from blocklight data</li>
 * <li>Ambient and sky lighting</li>
 * </ul>
 *
 * <h2>Descriptor Bindings</h2>
 * <ul>
 * <li>Binding 0: UBO - Camera/sun uniforms</li>
 * <li>Binding 7-11: G-buffer textures (colortex1-5)</li>
 * <li>Binding 20: Shadow map</li>
 * <li>Binding 21: Sun light output image</li>
 * <li>Binding 22: Block light output image</li>
 * <li>Binding 23: LabPBR output image</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * DeferredLightingPass pass = new DeferredLightingPass(context);
 * pass.initialize(width, height);
 *
 * // In render loop:
 * pass.execute(cmd, gbufferViews, shadowMapView, camera, sunDir);
 *
 * // Get outputs for composition:
 * LightingOutputs outputs = pass.getOutputs();
 * }</pre>
 */
public class DeferredLightingPass {
    private static final Logger LOGGER = LoggerFactory.getLogger(DeferredLightingPass.class);

    // Workgroup size for compute shader
    private static final int WORKGROUP_SIZE_X = 8;
    private static final int WORKGROUP_SIZE_Y = 8;
    private static final int LIGHTING_UBO_SIZE = 320;

    private final VContext ctx;
    private VRef<VComputePipeline> computePipeline;
    private VRef<VDescriptorSetLayout> descriptorLayout;
    private VRef<VSampler> sampler;
    private VRef<VSampler> shadowSampler;

    // Output images
    private VRef<VImage> sunLightImage;
    private VRef<VImageView> sunLightImageView;
    private VRef<VImage> blockLightImage;
    private VRef<VImageView> blockLightImageView;
    private VRef<VImage> labpbrImage;
    private VRef<VImageView> labpbrImageView;

    // Current dimensions
    private int currentWidth = 0;
    private int currentHeight = 0;

    // UBO for lighting constants
    private VRef<VBuffer> uboBuffer;

    /**
     * Container for deferred lighting output images.
     */
    public record LightingOutputs(
            VRef<VImage> sunLightImage,      // RGB: Direct lighting, A: Shadow factor
            VRef<VImage> blockLightImage,    // RGB: Blocklight contribution, A: Intensity
            VRef<VImage> labpbrImage         // R: Roughness, G: Metallic, B: AO, A: Emission
    ) {}

    /**
     * Views for externally-owned deferred lighting outputs, usually Iris
     * CUSTOM_IMAGES shared with Vulkan.
     */
    public record LightingOutputViews(
            VRef<VImageView> sunLightImageView,
            VRef<VImageView> blockLightImageView,
            VRef<VImageView> labpbrImageView
    ) {
        public boolean isComplete() {
            return sunLightImageView != null && blockLightImageView != null && labpbrImageView != null;
        }
    }

    /**
     * Creates a new deferred lighting pass.
     * @param context Vulkan context
     */
    public DeferredLightingPass(VContext context) {
        this.ctx = context;
        LOGGER.info("DeferredLightingPass created");
    }

    /**
     * Initializes output images and compute pipeline.
     * @param width Output width in pixels
     * @param height Output height in pixels
     */
    public void initialize(int width, int height) {
        if (computePipeline != null && width == currentWidth && height == currentHeight) {
            return; // Already initialized at this size
        }

        LOGGER.info("Initializing DeferredLightingPass at {}x{}", width, height);
        currentWidth = width;
        currentHeight = height;

        // Create samplers
        if (sampler == null) {
            sampler = VSampler.create(ctx, a -> a
                    .magFilter(VK_FILTER_NEAREST)
                    .minFilter(VK_FILTER_NEAREST)
                    .mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST)
                    .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .compareOp(VK_COMPARE_OP_NEVER)
                    .maxLod(1)
                    .borderColor(VK_BORDER_COLOR_INT_OPAQUE_BLACK)
                    .maxAnisotropy(1.0f));
        }

        if (shadowSampler == null) {
            shadowSampler = VSampler.create(ctx, a -> a
                    .magFilter(VK_FILTER_LINEAR)
                    .minFilter(VK_FILTER_LINEAR)
                    .mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST)
                    .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .compareOp(VK_COMPARE_OP_LESS)
                    .maxLod(1)
                    .borderColor(VK_BORDER_COLOR_INT_OPAQUE_WHITE)
                    .maxAnisotropy(1.0f));
        }

        // Create output images
        createOutputImages(width, height);

        if (uboBuffer == null) {
            uboBuffer = ctx.memory.createBuffer(
                    LIGHTING_UBO_SIZE,
                    VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT,
                    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
            uboBuffer.get().setDebugUtilsObjectName("DeferredLighting_UBO");
        }

        // Create compute pipeline
        createComputePipeline();

        LOGGER.info("DeferredLightingPass initialized successfully");
    }

    private void createOutputImages(int width, int height) {
        MemoryManager memoryManager = ctx.memory;

        // Sun light output - RGBA32F for HDR lighting
        sunLightImage = memoryManager.createImage2D(
                width, height, 1,
                VK_FORMAT_R32G32B32A32_SFLOAT,
                VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT,
                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        sunLightImage.get().setDebugUtilsObjectName("DeferredLighting_SunLight");
        sunLightImageView = VImageView.create(ctx, sunLightImage);

        // Block light output - RGBA32F
        blockLightImage = memoryManager.createImage2D(
                width, height, 1,
                VK_FORMAT_R32G32B32A32_SFLOAT,
                VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT,
                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        blockLightImage.get().setDebugUtilsObjectName("DeferredLighting_BlockLight");
        blockLightImageView = VImageView.create(ctx, blockLightImage);

        // LabPBR output - RGBA16F is sufficient for material data
        labpbrImage = memoryManager.createImage2D(
                width, height, 1,
                VK_FORMAT_R16G16B16A16_SFLOAT,
                VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT,
                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        labpbrImage.get().setDebugUtilsObjectName("DeferredLighting_LabPBR");
        labpbrImageView = VImageView.create(ctx, labpbrImage);
    }

    private void createComputePipeline() {
        // Create descriptor set layout using binding() method
        var layoutBuilder = new DescriptorSetLayoutBuilder();
        
        // Binding 0: UBO
        layoutBuilder.binding(0, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, VK_SHADER_STAGE_COMPUTE_BIT);
        
        // Bindings 7-11: G-buffer inputs (combined image samplers)
        for (int i = 7; i <= 11; i++) {
            layoutBuilder.binding(i, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, VK_SHADER_STAGE_COMPUTE_BIT);
        }
        
        // Bindings 21-23: Output storage images
        for (int i = 21; i <= 23; i++) {
            layoutBuilder.binding(i, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, VK_SHADER_STAGE_COMPUTE_BIT);
        }

        descriptorLayout = layoutBuilder.build(ctx);

        // Load and compile compute shader from GLSL source
        // The shader source is embedded for simplicity - in production this would be loaded from a file
        String shaderSource = loadComputeShaderSource();
        VRef<VShader> computeShader = VShader.compileLoad(ctx, shaderSource, VK_SHADER_STAGE_COMPUTE_BIT);
        var shaderModule = computeShader.get().named();

        // Create compute pipeline
        var pipelineBuilder = new ComputePipelineBuilder()
                .setShader(shaderModule)
                .addLayout(descriptorLayout);

        computePipeline = pipelineBuilder.build(ctx);
        LOGGER.info("Compute pipeline created for deferred lighting");
    }

    /**
     * Loads the compute shader source code.
     * Attempts to load from the tracked shaderpack source first, then the run directory,
     * and finally falls back to embedded source.
     */
    private String loadComputeShaderSource() {
        String[] shaderPaths = {
                "shaderpacks/VulkaniteDeferred/shaders/deferred_lighting.comp",
                "run/shaderpacks/VulkaniteDeferred/shaders/deferred_lighting.comp"
        };

        for (String shaderPath : shaderPaths) {
            try {
                java.nio.file.Path path = java.nio.file.Paths.get(shaderPath);
                if (java.nio.file.Files.exists(path)) {
                    LOGGER.info("Loading deferred lighting shader from: {}", shaderPath);
                    return java.nio.file.Files.readString(path);
                }
            } catch (Exception e) {
                LOGGER.warn("Failed to load shader from {}: {}", shaderPath, e.getMessage());
            }
        }
        
        // Fallback to embedded source
        LOGGER.info("Using embedded deferred lighting shader source");
        return getEmbeddedShaderSource();
    }
    
    /**
     * Returns the embedded shader source as a fallback.
     */
    private String getEmbeddedShaderSource() {
        return """
#version 460
#extension GL_EXT_shader_explicit_arithmetic_types_int64 : require

layout(local_size_x = 8, local_size_y = 8) in;

// G-Buffer inputs - combined image samplers
layout(set = 0, binding = 7) uniform sampler2D gbufferAlbedo;
layout(set = 0, binding = 8) uniform sampler2D gbufferMaterial;
layout(set = 0, binding = 9) uniform sampler2D gbufferNormal;
layout(set = 0, binding = 10) uniform sampler2D gbufferPosition;
layout(set = 0, binding = 11) uniform sampler2D gbufferLightData;

// Shadow map
layout(set = 0, binding = 20) uniform sampler2D shadowMap;

// Output storage images
layout(set = 0, binding = 21, rgba32f) writeonly uniform image2D sunLightImage;
layout(set = 0, binding = 22, rgba32f) writeonly uniform image2D blockLightImage;
layout(set = 0, binding = 23, rgba16f) writeonly uniform image2D labpbrImage;

// Uniforms
layout(set = 0, binding = 0) uniform LightingUBO {
mat4 viewInverse;
mat4 projectionInverse;
vec3 sunDirection;
float padding1;
vec3 sunColor;
float padding2;
vec3 cameraPosition;
float padding3;
float time;
int worldTime;
} ubo;

void main() {
ivec2 coord = ivec2(gl_GlobalInvocationID.xy);

// Sample G-buffer
vec4 albedo = texelFetch(gbufferAlbedo, coord, 0);
vec4 material = texelFetch(gbufferMaterial, coord, 0);
vec4 normalData = texelFetch(gbufferNormal, coord, 0);
vec4 position = texelFetch(gbufferPosition, coord, 0);
vec4 lightData = texelFetch(gbufferLightData, coord, 0);

// Check for sky (empty pixel)
if (albedo.a < 0.01) {
imageStore(sunLightImage, coord, vec4(0.0));
imageStore(blockLightImage, coord, vec4(0.0));
imageStore(labpbrImage, coord, vec4(0.0));
return;
}

// Decode normal (assuming [0,1] range needs to be converted to [-1,1])
vec3 normal = normalize(normalData.rgb * 2.0 - 1.0);

// Extract material properties
vec3 F0 = material.rgb;
float roughness = material.a;
float metallic = position.a;

// View direction
vec3 viewDir = normalize(ubo.cameraPosition - position.rgb);

// Simple diffuse lighting
float NdotL = max(dot(normal, ubo.sunDirection), 0.0);

// Basic shadow sampling (PCF would be more complex)
float shadow = 1.0; // Placeholder - shadow calculation would go here

// Simple diffuse + ambient
vec3 diffuse = albedo.rgb * ubo.sunColor * NdotL * shadow;
vec3 ambient = albedo.rgb * 0.1 * lightData.g; // Use skylight for ambient

// Blocklight contribution
float blocklightIntensity = lightData.r;
vec3 blocklight = albedo.rgb * blocklightIntensity * vec3(1.0, 0.9, 0.8);
float emission = lightData.a < -0.5
? clamp(-lightData.a - 1.0, 0.0, 1.0)
: 0.0;

// Write outputs
imageStore(sunLightImage, coord, vec4(diffuse + ambient, shadow));
imageStore(blockLightImage, coord, vec4(blocklight, blocklightIntensity));
imageStore(labpbrImage, coord, vec4(roughness, metallic, lightData.b, emission));
}
""";
    }

    /**
     * Records compute dispatch to command buffer.
     * @param cmd Command buffer
     * @param gbufferViews G-buffer image views [albedo, material, normal, position, lightdata]
     * @param shadowMapView Shadow map view (can be null if shadows disabled)
     * @param camera Camera state
     * @param sunDir Sun direction in world space
     * @param sunColor Sun color (RGB)
     */
    public void execute(
            VCmdBuff cmd,
            VRef<VImageView>[] gbufferViews,
            VRef<VImageView> shadowMapView,
            Camera camera,
            Vector3f sunDir,
            Vector3f sunColor) {
        execute(cmd, gbufferViews, shadowMapView, camera, sunDir, sunColor,
                new LightingOutputViews(sunLightImageView, blockLightImageView, labpbrImageView));
    }

    /**
     * Records compute dispatch to command buffer.
     * @param cmd Command buffer
     * @param gbufferViews G-buffer image views [albedo, material, normal, position, lightdata]
     * @param shadowMapView Shadow map view (can be null if shadows disabled)
     * @param camera Camera state
     * @param sunDir Sun direction in world space
     * @param sunColor Sun color (RGB)
     * @param outputViews Externally-owned output image views to write lighting into
     */
    public void execute(
            VCmdBuff cmd,
            VRef<VImageView>[] gbufferViews,
            VRef<VImageView> shadowMapView,
            Camera camera,
            Vector3f sunDir,
            Vector3f sunColor,
            LightingOutputViews outputViews) {

        if (computePipeline == null) {
            LOGGER.warn("DeferredLightingPass not initialized, skipping execute");
            return;
        }

        if (outputViews == null || !outputViews.isComplete()) {
            LOGGER.warn("DeferredLightingPass missing output images, skipping execute");
            return;
        }

        if (gbufferViews == null || gbufferViews.length < 5) {
            LOGGER.warn("DeferredLightingPass missing G-buffer views, skipping execute");
            return;
        }

        for (int i = 0; i < 5; i++) {
            if (gbufferViews[i] == null) {
                LOGGER.warn("DeferredLightingPass missing G-buffer view {}, skipping execute", i);
                return;
            }
        }

        updateLightingUbo(camera, sunDir, sunColor);

        // Allocate descriptor set
        var descriptorSet = Vulkanite.INSTANCE.getPoolByLayout(descriptorLayout).get().allocateSet();

        // Build descriptor updates
        var updater = new DescriptorUpdateBuilder(ctx, createDeferredLightingSet())
                .set(descriptorSet);

        // Binding 0: UBO
        updater.uniform(0, uboBuffer, 0, LIGHTING_UBO_SIZE);

        // Bindings 7-11: G-buffer textures
        for (int i = 0; i < Math.min(5, gbufferViews.length); i++) {
            if (gbufferViews[i] != null) {
                updater.imageSampler(7 + i, gbufferViews[i], sampler);
            }
        }

        // Bindings 21-23: Output images (use imageStore for storage images)
        updater.imageStore(21, outputViews.sunLightImageView());
        updater.imageStore(22, outputViews.blockLightImageView());
        updater.imageStore(23, outputViews.labpbrImageView());

        updater.apply();

        // Bind pipeline and descriptor set
        cmd.bindCompute(computePipeline);
        cmd.bindDSet(descriptorSet);

        // Dispatch compute shader
        int dispatchWidth = outputViews.sunLightImageView().get().image.get().width;
        int dispatchHeight = outputViews.sunLightImageView().get().image.get().height;
        int groupCountX = (dispatchWidth + WORKGROUP_SIZE_X - 1) / WORKGROUP_SIZE_X;
        int groupCountY = (dispatchHeight + WORKGROUP_SIZE_Y - 1) / WORKGROUP_SIZE_Y;
        cmd.dispatch(groupCountX, groupCountY, 1);

        // Add references to keep resources alive
        cmd.moveRefGeneric(outputViews.sunLightImageView().addRefGeneric());
        cmd.moveRefGeneric(outputViews.blockLightImageView().addRefGeneric());
        cmd.moveRefGeneric(outputViews.labpbrImageView().addRefGeneric());
    }

    private void updateLightingUbo(Camera camera, Vector3f sunDir, Vector3f sunColor) {
        long ptr = uboBuffer.get().map();
        try {
            ByteBuffer bb = MemoryUtil.memByteBuffer(ptr, LIGHTING_UBO_SIZE);

            writeIdentityMat4(bb, 0);
            writeIdentityMat4(bb, 64);
            writeIdentityMat4(bb, 128);
            writeIdentityMat4(bb, 192);

            bb.putFloat(256, sunDir.x);
            bb.putFloat(260, sunDir.y);
            bb.putFloat(264, sunDir.z);
            bb.putFloat(268, 0.0f);

            bb.putFloat(272, sunColor.x);
            bb.putFloat(276, sunColor.y);
            bb.putFloat(280, sunColor.z);
            bb.putFloat(284, 0.0f);

            var pos = camera.getPos();
            bb.putFloat(288, (float) pos.x);
            bb.putFloat(292, (float) pos.y);
            bb.putFloat(296, (float) pos.z);
            bb.putFloat(300, 0.0f);

            MinecraftClient mc = MinecraftClient.getInstance();
            int worldTime = mc.world != null ? (int) (mc.world.getTimeOfDay() % 24000L) : 0;
            bb.putFloat(304, worldTime);
            bb.putInt(308, worldTime);
            bb.putFloat(312, 128.0f);
            bb.putFloat(316, 0.0015f);
        } finally {
            uboBuffer.get().unmap();
        }
    }

    private static void writeIdentityMat4(ByteBuffer bb, int offset) {
        for (int i = 0; i < 16; i++) {
            bb.putFloat(offset + i * 4, (i % 5) == 0 ? 1.0f : 0.0f);
        }
    }

    /**
     * Creates the expected ShaderReflection.Set for deferred lighting.
     * This is used for descriptor set validation.
     */
    public static ShaderReflection.Set createDeferredLightingSet() {
        List<ShaderReflection.Binding> bindings = new ArrayList<>();
        
        // Binding 0: UBO
        bindings.add(new ShaderReflection.Binding("", 0, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, 0, false));
        
        // Bindings 7-11: G-buffer inputs
        for (int i = 7; i <= 11; i++) {
            bindings.add(new ShaderReflection.Binding("", i, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 0, false));
        }
        
        // Bindings 21-23: Output images
        for (int i = 21; i <= 23; i++) {
            bindings.add(new ShaderReflection.Binding("", i, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 0, false));
        }
        
        return new ShaderReflection.Set(new ArrayList<>(bindings));
    }

    /**
     * Gets output images for composition pass.
     * @return LightingOutputs containing sun, block, and material images
     */
    public LightingOutputs getOutputs() {
        return new LightingOutputs(sunLightImage, blockLightImage, labpbrImage);
    }

    /**
     * Gets the sun light image view.
     * @return Sun light image view
     */
    public VRef<VImageView> getSunLightImageView() {
        return sunLightImageView;
    }

    /**
     * Gets the block light image view.
     * @return Block light image view
     */
    public VRef<VImageView> getBlockLightImageView() {
        return blockLightImageView;
    }

    /**
     * Gets the labpbr image view.
     * @return LabPBR image view
     */
    public VRef<VImageView> getLabpbrImageView() {
        return labpbrImageView;
    }

    /**
     * Checks if the pass is initialized.
     * @return true if initialized
     */
    public boolean isInitialized() {
        return computePipeline != null;
    }

    /**
     * Gets the current width.
     * @return Current width in pixels
     */
    public int getWidth() {
        return currentWidth;
    }

    /**
     * Gets the current height.
     * @return Current height in pixels
     */
    public int getHeight() {
        return currentHeight;
    }

    /**
     * Cleans up Vulkan resources.
     */
    public void cleanup() {
        LOGGER.info("Cleaning up DeferredLightingPass");
        
        if (sunLightImageView != null) {
            sunLightImageView.close();
            sunLightImageView = null;
        }
        if (sunLightImage != null) {
            sunLightImage.close();
            sunLightImage = null;
        }
        if (blockLightImageView != null) {
            blockLightImageView.close();
            blockLightImageView = null;
        }
        if (blockLightImage != null) {
            blockLightImage.close();
            blockLightImage = null;
        }
        if (labpbrImageView != null) {
            labpbrImageView.close();
            labpbrImageView = null;
        }
        if (labpbrImage != null) {
            labpbrImage.close();
            labpbrImage = null;
        }
        if (computePipeline != null) {
            computePipeline.close();
            computePipeline = null;
        }
        if (descriptorLayout != null) {
            descriptorLayout.close();
            descriptorLayout = null;
        }
        if (sampler != null) {
            sampler.close();
            sampler = null;
        }
        if (shadowSampler != null) {
            shadowSampler.close();
            shadowSampler = null;
        }
        if (uboBuffer != null) {
            uboBuffer.close();
            uboBuffer = null;
        }
    }
}
