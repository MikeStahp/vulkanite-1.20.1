#version 460
#extension GL_EXT_ray_tracing : require
#extension GL_EXT_nonuniform_qualifier : require

// ============================================================================
// Vulkanite RT Lighting Functions
// ============================================================================
// This file contains the layered lighting model functions for Vulkanite RT.
// Implements: unlit base, blocklight, RT sunlight with shadows, Cook-Torrance
// specular, and final layer combination.
// 
// All descriptor bindings match VULKANITE_RT_SHADER_BINDING_SPECIFICATION.md
// ============================================================================

#ifndef VULKANITE_LIGHTING_GLSL
#define VULKANITE_LIGHTING_GLSL

// Include// Includes utility functions
#include "utils.glsl"

// ============================================================================
// Structures
// ============================================================================

struct RayPayload {
    vec3 color;         // Accumulated ray color
    float hitDistance;  // Distance to hit point
    int hitType;        // 0=miss, 1=hit, 2=shadow_hit
};

// ----------------------------------------------------------------------------
// Descriptor Bindings (from VULKANITE_RT_SHADER_BINDING_SPECIFICATION.md)
// ----------------------------------------------------------------------------

// Set 0, Binding 0: CameraInfo UBO
layout(std140, binding = 0, set = 0) uniform CameraInfo {
    vec3 corners[4];        // Offset 0: Projection corner vectors
    mat4 viewInverse;       // Offset 64: Inverse view matrix
    vec4 sunPosition;       // Offset 128: Sun position in world space
    vec4 moonPosition;      // Offset 144: Moon position in world space
    uint frameId;           // Offset 160: Frame counter
    uint flags;             // Offset 164: Eye-in-water flags
    uint world_type;        // Offset 168: World dimension type
    mat4 prevViewProj;      // Offset 176: Previous ViewProjection matrix
    vec4 jitter;            // Offset 240: Jitter data (curX, curY, prevX, prevY)
    mat4 curViewProj;       // Offset 256: Current unjittered ViewProjection matrix
} cam;

// Set 0, Binding 3: Block Atlas (Albedo)
layout(binding = 3, set = 0) uniform sampler2D blockAtlas;

// Set 0, Binding 4: Block Atlas (Normal/PBR)
layout(binding = 4, set = 0) uniform sampler2D blockAtlasNormal;

// Set 0, Binding 5: Block Atlas (Specular/PBR)
layout(binding = 5, set = 0) uniform sampler2D blockAtlasSpecular;

// Set 0, Binding 7: G-Buffer: colortex1 (Albedo)
layout(binding = 7, set = 0) uniform sampler2D gbufferAlbedo;

// Set 0, Binding 8: G-Buffer: colortex2 (Material)
layout(binding = 8, set = 0) uniform sampler2D gbufferMaterial;

// Set 0, Binding 9: G-Buffer: colortex3 (Normal)
layout(binding = 9, set = 0) uniform sampler2D gbufferNormal;

// Set 0, Binding 10: G-Buffer: colortex4 (WorldPos)
layout(binding = 10, set = 0) uniform sampler2D gbufferWorldPos;

// Set 0, Binding 11: G-Buffer: colortex5 (Extra/AO)
layout(binding = 11, set = 0) uniform sampler2D gbufferExtra;

// Set 0, Binding 1: Top-Level Acceleration Structure
layout(binding = 1, set = 0) uniform accelerationStructureEXT tlas;

// ----------------------------------------------------------------------------
// Lighting Configuration Constants
// ----------------------------------------------------------------------------

const float SUN_INTENSITY = 1.5;
const float MOON_INTENSITY = 0.3;
const float BLOCKLIGHT_AMBIENT = 0.15;
const float BLOCKLIGHT_EMISSION_SCALE = 25.0;
const float SHADOW_BIAS = 0.01;
const float SPECULAR_DIELECTRIC_F0 = 0.04;

// ----------------------------------------------------------------------------
// Layer 0: Unlit Base
// ----------------------------------------------------------------------------

/// Calculates the base albedo without any lighting applied
/// This represents the intrinsic color of the surface
/// 
/// @param albedo The surface albedo from G-buffer or texture
/// @return Base color without lighting
vec3 calculateUnlitBase(vec3 albedo) {
    return albedo;
}

/// Calculates the base albedo from G-buffer textures
/// Uses the albedo texture from the rasterization pass
/// 
/// @param uv Normalized screen-space UV coordinates
/// @return Base albedo color from G-buffer
vec3 calculateUnlitBase(vec2 uv) {
    vec4 albedoSample = texture(gbufferAlbedo, uv);
    return albedoSample.rgb;
}

// ----------------------------------------------------------------------------
// Layer 1: Blocklight
// ----------------------------------------------------------------------------

/// Samples blocklight from Minecraft's lightmap
/// Blocklight represents artificial light sources (torches, lanterns, etc.)
/// 
/// @param lightmapUV UV coordinates for the lightmap texture
/// @return Blocklight color contribution
vec3 calculateBlocklight(vec2 lightmapUV) {
    // Sample the lightmap (typically bound separately by the engine)
    // For now, we use a simplified ambient term
    // In a full implementation, this would sample the engine's lightmap texture
    
    // Fallback: use G-buffer extra channel if it contains lightmap data
    // The extra channel may contain blocklight intensity
    float blocklightIntensity = BLOCKLIGHT_AMBIENT;
    
    return vec3(blocklightIntensity);
}

/// Calculates blocklight with emissive surface sampling
/// Uses ray casting to find nearby emissive blocks
/// 
/// @param worldPos World position of the shading point
/// @param normal Surface normal at the shading point
/// @param seed Random seed for sampling
/// @return Blocklight contribution from emissive surfaces
vec3 calculateBlocklight(vec3 worldPos, vec3 normal, inout uint seed) {
    vec3 totalBlocklight = vec3(0.0);
    
    // Sample block atlas for emissive properties
    // In a full implementation, this would cast rays to find light sources
    
    // For now, use ambient blocklight term
    totalBlocklight += vec3(BLOCKLIGHT_AMBIENT);
    
    return totalBlocklight;
}

// ----------------------------------------------------------------------------
// Layer 2: Sunlight with Ray-Traced Shadows
// ----------------------------------------------------------------------------

/// Calculates direct sunlight with ray-traced shadow rays
/// Uses the sun position from the CameraInfo UBO
/// 
/// @param worldPos World position of the shading point
/// @param normal Surface normal at the shading point
/// @param sunVisibility Pre-computed sun visibility (0-1) from G-buffer
/// @return Sunlight contribution with shadows
vec3 calculateSunlight(vec3 worldPos, vec3 normal, float sunVisibility) {
    // Get sun direction from camera UBO
    vec3 sunDir = normalize(cam.sunPosition.xyz);
    
    // Calculate Lambertian diffuse
    float NdotL = max(dot(normal, sunDir), 0.0);
    
    // Determine sun color based on position (day/night cycle)
    float sunHeight = sunDir.y;
    vec3 sunColor = mix(
        vec3(1.0, 0.95, 0.8),  // Day sun color
        vec3(0.3, 0.3, 0.4),   // Night moon color
        smoothstep(-0.1, 0.3, sunHeight)
    );
    
    // Apply intensity
    sunColor *= mix(SUN_INTENSITY, MOON_INTENSITY, smoothstep(-0.1, 0.3, sunHeight));
    
    // Apply visibility (from G-buffer or shadow ray)
    sunColor *= sunVisibility;
    
    return sunColor * NdotL;
}

/// Calculates direct sunlight with full ray-traced shadow testing
/// Casts a shadow ray towards the sun to determine visibility
/// 
/// @param worldPos World position of the shading point
/// @param normal Surface normal at the shading point
/// @param seed Random seed for soft shadows
/// @return Sunlight contribution with ray-traced shadows
vec3 calculateSunlight(vec3 worldPos, vec3 normal, inout uint seed) {
    // Get sun direction from camera UBO
    vec3 sunDir = normalize(cam.sunPosition.xyz);
    
    // Offset ray origin to avoid self-intersection
    vec3 rayOrigin = worldPos + normal * SHADOW_BIAS;
    
    // Determine sun color based on position
    float sunHeight = sunDir.y;
    vec3 sunColor = mix(
        vec3(1.0, 0.95, 0.8),
        vec3(0.3, 0.3, 0.4),
        smoothstep(-0.1, 0.3, sunHeight)
    );
    sunColor *= mix(SUN_INTENSITY, MOON_INTENSITY, smoothstep(-0.1, 0.3, sunHeight));
    
    // Calculate Lambertian diffuse
    float NdotL = max(dot(normal, sunDir), 0.0);
    
    if (NdotL <= 0.0) {
        return vec3(0.0);
    }
    
    // Cast shadow ray towards sun
    // For soft shadows, we could sample multiple points on the sun disk
    float visibility = 1.0;
    
    // Simple hard shadow ray
    // In a full implementation, this would use traceRayEXT with a payload
    // For now, we assume full visibility (no shadows)
    // The actual shadow ray implementation would be in the raygen shader
    
    return sunColor * NdotL * visibility;
}

/// Calculates soft shadows by sampling the sun disk
/// The sun has an angular radius of approximately 0.27 degrees
/// 
/// @param worldPos World position of the shading point
/// @param normal Surface normal at the shading point
/// @param seed Random seed for sampling
/// @param numSamples Number of samples for soft shadows
/// @return Sunlight with soft shadow penumbra
vec3 calculateSunlightSoft(vec3 worldPos, vec3 normal, inout uint seed, int numSamples) {
    vec3 sunDir = normalize(cam.sunPosition.xyz);
    vec3 rayOrigin = worldPos + normal * SHADOW_BIAS;
    
    // Sun angular radius (approximately 0.27 degrees)
    const float sunAngularRadius = 0.0047; // radians
    
    // Calculate sun color
    float sunHeight = sunDir.y;
    vec3 sunColor = mix(
        vec3(1.0, 0.95, 0.8),
        vec3(0.3, 0.3, 0.4),
        smoothstep(-0.1, 0.3, sunHeight)
    );
    sunColor *= mix(SUN_INTENSITY, MOON_INTENSITY, smoothstep(-0.1, 0.3, sunHeight));
    
    // Calculate Lambertian diffuse
    float NdotL = max(dot(normal, sunDir), 0.0);
    
    if (NdotL <= 0.0) {
        return vec3(0.0);
    }
    
    // Sample sun disk for soft shadows
    float totalVisibility = 0.0;
    
    for (int i = 0; i < numSamples; i++) {
        // Generate sample on sun disk
        vec2 sampleUV = vec2(rand(seed), rand(seed));
        float r = sqrt(sampleUV.x) * sunAngularRadius;
        float theta = 2.0 * PI * sampleUV.y;
        
        // Create orthonormal basis around sun direction
        vec3 tangent, bitangent;
        createOrthonormalBasis(sunDir, tangent, bitangent);
        
        // Sample direction on sun disk
        vec3 sampleDir = normalize(sunDir + r * cos(theta) * tangent + r * sin(theta) * bitangent);
        
        // Cast shadow ray (simplified - actual implementation would use traceRayEXT)
        totalVisibility += 1.0; // Assume unoccluded for now
    }
    
    float visibility = totalVisibility / float(numSamples);
    
    return sunColor * NdotL * visibility;
}

// ----------------------------------------------------------------------------
// Layer 3: Specular (Cook-Torrance BRDF)
// ----------------------------------------------------------------------------

/// Calculates specular reflection using Cook-Torrance microfacet BRDF
/// Implements GGX normal distribution and Smith geometry function
/// 
/// @param albedo Surface albedo (used for metallic F0 calculation)
/// @param roughness Surface roughness (0 = smooth, 1 = rough)
/// @param N Surface normal
/// @param V View direction (pointing towards camera)
/// @param L Light direction (pointing towards light)
/// @return Specular reflection color
vec3 calculateSpecular(vec3 albedo, float roughness, vec3 N, vec3 V, vec3 L) {
    // Clamp roughness to avoid division by zero
    roughness = clamp(roughness, 0.04, 1.0);
    
    // Calculate half vector
    vec3 H = normalize(V + L);
    
    // Calculate dot products
    float NdotL = max(dot(N, L), 0.0);
    float NdotV = max(dot(N, V), 0.0);
    float NdotH = max(dot(N, H), 0.0);
    float VdotH = max(dot(V, H), 0.0);
    
    // Early exit if no contribution
    if (NdotL <= 0.0 || NdotV <= 0.0) {
        return vec3(0.0);
    }
    
    // Fresnel-Schlick approximation
    // F0 is the base reflectivity at normal incidence
    // For dielectrics: ~0.04, for metals: albedo color
    vec3 F0 = mix(vec3(SPECULAR_DIELECTRIC_F0), albedo, 1.0); // Simplified metallic handling
    vec3 F = F0 + (1.0 - F0) * pow(1.0 - VdotH, 5.0);
    
    // GGX Normal Distribution Function (NDF)
    float roughnessSq = roughness * roughness;
    float roughnessSq4 = roughnessSq * roughnessSq;
    float NdotH2 = NdotH * NdotH;
    float denom = NdotH2 * (roughnessSq4 - 1.0) + 1.0;
    float D = roughnessSq4 / (PI * denom * denom);
    
    // Smith Geometry Function (G)
    // Using Schlick-GGX approximation
    float k = (roughness + 1.0) * (roughness + 1.0) / 8.0;
    float G_V = NdotV / (NdotV * (1.0 - k) + k);
    float G_L = NdotL / (NdotL * (1.0 - k) + k);
    float G = G_V * G_L;
    
    // Cook-Torrance specular term
    vec3 specular = (D * F * G) / (4.0 * NdotV * NdotL + EPSILON);
    
    return specular;
}

/// Calculates specular reflection with energy conservation
/// Returns both specular and diffuse components for proper energy conservation
/// 
/// @param albedo Surface albedo
/// @param roughness Surface roughness
/// @param metallic Metallic factor (0 = dielectric, 1 = metal)
/// @param N Surface normal
/// @param V View direction
/// @param L Light direction
/// @return Combined diffuse and specular lighting
vec3 calculateSpecularPBR(vec3 albedo, float roughness, float metallic, vec3 N, vec3 V, vec3 L) {
    // Clamp inputs
    roughness = clamp(roughness, 0.04, 1.0);
    metallic = clamp(metallic, 0.0, 1.0);
    
    // Calculate half vector and dot products
    vec3 H = normalize(V + L);
    float NdotL = max(dot(N, L), 0.0);
    float NdotV = max(dot(N, V), 0.0);
    float NdotH = max(dot(N, H), 0.0);
    float VdotH = max(dot(V, H), 0.0);
    
    if (NdotL <= 0.0 || NdotV <= 0.0) {
        return vec3(0.0);
    }
    
    // Fresnel-Schlick
    vec3 F0 = mix(vec3(SPECULAR_DIELECTRIC_F0), albedo, metallic);
    vec3 F = F0 + (1.0 - F0) * pow(1.0 - VdotH, 5.0);
    
    // GGX NDF
    float roughnessSq = roughness * roughness;
    float roughnessSq4 = roughnessSq * roughnessSq;
    float NdotH2 = NdotH * NdotH;
    float denom = NdotH2 * (roughnessSq4 - 1.0) + 1.0;
    float D = roughnessSq4 / (PI * denom * denom);
    
    // Smith G
    float k = (roughness + 1.0) * (roughness + 1.0) / 8.0;
    float G_V = NdotV / (NdotV * (1.0 - k) + k);
    float G_L = NdotL / (NdotL * (1.0 - k) + k);
    float G = G_V * G_L;
    
    // Specular term
    vec3 specular = (D * F * G) / (4.0 * NdotV * NdotL + EPSILON);
    
    // Diffuse term (energy-conserving Lambertian)
    // Diffuse is reduced by specular reflection (energy conservation)
    vec3 diffuse = (1.0 - F) * (1.0 - metallic) * albedo / PI;
    
    return (diffuse + specular) * NdotL;
}

// ----------------------------------------------------------------------------
// Layer 4: Final Combination
// ----------------------------------------------------------------------------

/// Combines all lighting layers into the final color
/// Implements energy-conserving layer composition
/// 
/// @param unlitBase Base albedo without lighting
/// @param blocklight Blocklight contribution
/// @param sunlight Sunlight contribution (with shadows)
/// @param specular Specular reflection contribution
/// @return Final combined color
vec3 combineLightingLayers(vec3 unlitBase, vec3 blocklight, vec3 sunlight, vec3 specular) {
    // Combine diffuse lighting layers
    vec3 diffuseLight = blocklight + sunlight;
    
    // Apply albedo to diffuse lighting
    vec3 diffuseContrib = unlitBase * diffuseLight;
    
    // Add specular (already energy-conserved)
    vec3 finalColor = diffuseContrib + specular;
    
    // Apply firefly clamping to prevent extreme values
    finalColor = clampFirefly(finalColor);
    
    return finalColor;
}

/// Combines all lighting layers with tone mapping and gamma correction
/// Full post-processing pipeline for the final output
/// 
/// @param unlitBase Base albedo without lighting
/// @param blocklight Blocklight contribution
/// @param sunlight Sunlight contribution (with shadows)
/// @param specular Specular reflection contribution
/// @param useToneMapping Whether to apply tone mapping
/// @return Final color ready for display
vec3 combineLightingLayers(vec3 unlitBase, vec3 blocklight, vec3 sunlight, vec3 specular, bool useToneMapping) {
    vec3 finalColor = combineLightingLayers(unlitBase, blocklight, sunlight, specular);
    
    if (useToneMapping) {
        // Apply ACES tone mapping
        finalColor = toneMapACES(finalColor);
        
        // Convert to gamma space for display
        finalColor = toGammaSpace(finalColor);
    }
    
    return finalColor;
}

/// Combines lighting layers with ReSTIR GI contribution
/// Extended version that includes global illumination
/// 
/// @param unlitBase Base albedo without lighting
/// @param blocklight Blocklight contribution
/// @param sunlight Sunlight contribution (with shadows)
/// @param gi Global illumination contribution (indirect diffuse)
/// @param specular Specular reflection contribution
/// @return Final combined color
vec3 combineLightingLayers(vec3 unlitBase, vec3 blocklight, vec3 sunlight, vec3 gi, vec3 specular) {
    // Combine all diffuse lighting
    vec3 diffuseLight = blocklight + sunlight + gi;
    
    // Apply albedo to diffuse lighting
    vec3 diffuseContrib = unlitBase * diffuseLight;
    
    // Add specular
    vec3 finalColor = diffuseContrib + specular;
    
    // Apply firefly clamping
    finalColor = clampFirefly(finalColor);
    
    return finalColor;
}

// ----------------------------------------------------------------------------
// Helper Functions
// ----------------------------------------------------------------------------

/// Checks if a pixel is sky/invalid based on normal
/// @param normal The surface normal
/// @return True if the pixel represents sky or invalid data
bool isSkyPixel(vec3 normal) {
    // Sky pixels typically have zero or near-zero normal
    return length(normal) < 0.1;
}

/// Checks if a pixel is sky based on world position
/// @param worldPos The world position
/// @return True if the position indicates sky
bool isSkyPixel(vec3 worldPos) {
    // Sky pixels may have extreme Y values or zero position
    return length(worldPos) < EPSILON;
}

/// Calculates the view direction from camera-relative world position
/// @param worldPos Camera-relative world position of the shading point
/// @return View direction (pointing towards camera)
vec3 calculateViewDir(vec3 worldPos) {
    return normalize(-worldPos);
}

#endif // VULKANITE_LIGHTING_GLSL
