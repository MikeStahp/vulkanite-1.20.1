#ifndef PBR_MATERIAL_GLSL
#define PBR_MATERIAL_GLSL 1

#include "/lib/rt/settings.glsl"
#include "/lib/pbr/block_materials.glsl"

// ============================================================================
// PBR Material System for VulkaniteRT
// Adapted from Dirt-RT-Optimized — LabPBR 1.3 format
// ============================================================================

// LabPBR emission intensity scale - matches Dirt RT Old (2.0 for proper LabPBR scale)
const float EMISSION_INTENSITY = 2.0;

// Hardcoded Metal (HCM) reflectance values from LabPBR spec
const vec3 HCM_METALS[] = vec3[](
    vec3(0.53123, 0.51236, 0.49583), // 230: Iron
    vec3(0.94423, 0.77610, 0.37340), // 231: Gold
    vec3(0.91230, 0.91385, 0.91968), // 232: Aluminium
    vec3(0.55560, 0.55454, 0.55478), // 233: Chrome
    vec3(0.92595, 0.72090, 0.50415), // 234: Copper
    vec3(0.63248, 0.62594, 0.64148), // 235: Lead
    vec3(0.67885, 0.64240, 0.58841), // 236: Platinum
    vec3(0.96200, 0.94947, 0.92212)  // 237: Silver
);

struct Material {
    vec3 albedo;
    vec3 F0;
    float metallic;
    float roughness;
    float subsurface_scattering;
    vec3 emission;
    vec3 normal;
    float ambientOcclusion;
    float translucent;
    i16vec2 block_id;
    vec3 light_texture;
};

// Extract PBR material from LabPBR textures
// albedo: blockTex sample (already gamma-corrected)
// normal: blockTexNormal sample (raw [0,1])
// specular: blockTexSpecular sample (LabPBR encoded)
// tbn: tangent-bitangent-normal matrix from geometry
// macroNormal: the geometric normal from the mesh
Material getMaterial(vec4 albedo, vec4 normal, vec4 specular, mat3 tbn, vec3 macroNormal) {
    Material material;
    specular = sanitizeLabPbrSpecular(specular);

    // --- Translucency ---
    material.translucent = albedo.a;

    // --- Roughness (LabPBR: specular.r = smoothness) ---
    float smoothness = specular.r;
    material.roughness = (1.0 - smoothness) * (1.0 - smoothness);

    // --- Normal Mapping (LabPBR: RG = XY, B = AO, A = height) ---
    // Safety check: if normal map is missing/invalid (pure black or pure white)
    // A proper flat normal map must be ~0.5, 0.5, 1.0 (flat blue)
    if ((normal.r <= 0.01 && normal.g <= 0.01 && normal.b <= 0.01) ||
        (normal.r >= 0.99 && normal.g >= 0.99 && normal.b >= 0.99)) {
        material.normal = macroNormal; // Just use geometric normal
        material.ambientOcclusion = 1.0; // No AO from invalid normal map
    } else {
        material.normal = normalize(tbn * decodeLabPbrNormal(normal));
        material.ambientOcclusion = decodeLabPbrAmbientOcclusion(normal);
    }

    // --- Albedo, F0, Metallic (LabPBR: specular.g encodes F0/HCM) ---
    int f0Channel = labPbrByte(specular.g);

    if (f0Channel < 230) {
        material.F0 = vec3(float(f0Channel) / 255.0);
        material.metallic = 0.0;
        material.albedo = albedo.rgb;
    } else if (f0Channel < 238) {
        material.F0 = HCM_METALS[f0Channel - 230];
        material.metallic = 1.0;
        material.albedo = vec3(0.0);
    } else {
        material.F0 = albedo.rgb;
        material.metallic = 1.0;
        material.albedo = vec3(0.0);
    }

    // --- Subsurface Scattering (LabPBR 1.3: specular.b) ---
    material.subsurface_scattering = specular.b < 0.255 ? specular.b * 4.0 : 0.25;

    // --- Emission (LabPBR 1.3: alpha 0-254 = linear emissive, 255 = ignored) ---
    float emissionIntensity = decodeLabPbrEmission(specular.a);
    material.emission = albedo.rgb * emissionIntensity * EMISSION_INTENSITY;
    material.block_id = i16vec2(0);
    material.light_texture = vec3(0.0);

    return material;
}

#endif // PBR_MATERIAL_GLSL
