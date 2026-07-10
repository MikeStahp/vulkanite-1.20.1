#version 460

#include "/lib/rt/settings.glsl"
#include "/lib/pbr/block_materials.glsl"

// Iris/OptiFine standard texture bindings
uniform sampler2D gtexture; // Diffuse/albedo

// PBR textures - these may not be available on all systems
// Use conditional sampling with fallback
#ifdef MC_NORMAL_MAP
uniform sampler2D normals; // Normal map (LabPBR: _n.png)
#endif
#ifdef MC_SPECULAR_MAP
uniform sampler2D specular; // Specular map (LabPBR: _s.png)
#endif

// Inputs from vertex shader
in vec2 texCoord;
in vec4 vertexColor;
in vec3 normal;
in vec3 worldPos;
in vec2 lightmapCoord;
in mat3 tbnMatrix;
flat in float blockId;
flat in float intrinsicBlockLight;
uniform float frameTimeCounter;

// Iris reads render-target formats from GLSL const directives, not from
// iris.properties. These must remain floating point because normals are signed
// and world positions are not constrained to the normalized [0, 1] range.
const int RGBA16F = 34842; // GL_RGBA16F
const int RGBA32F = 34836; // GL_RGBA32F
const int colortex1Format = RGBA16F;
const int colortex2Format = RGBA16F;
const int colortex3Format = RGBA16F;
const int colortex4Format = RGBA32F;
const int colortex5Format = RGBA16F;

// Output to G-Buffer for DLSSD Ray Reconstruction
/* RENDERTARGETS: 1,2,3,4,5 */
layout(location = 0) out vec4 colortex1; // Albedo (RGB, A = alpha)
layout(location = 1) out vec4 colortex2; // F0 reflectance (RGB), roughness (A)
layout(location = 2) out vec4 colortex3; // Normal (RGB), roughness packed (A)
layout(location = 3) out vec4 colortex4; // World position (RGB), unused (A)
layout(location = 4) out vec4 colortex5; // Blocklight, skylight, AO, emission

// LabPBR HCM metals lookup table
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

int flatAxisBucket(vec3 direction) {
    vec3 axis = abs(direction);
    if (axis.x >= axis.y && axis.x >= axis.z) return direction.x >= 0.0 ? 0 : 1;
    if (axis.y >= axis.z) return direction.y >= 0.0 ? 2 : 3;
    return direction.z >= 0.0 ? 4 : 5;
}

float encodeSurfaceFaceBucket(vec3 geometricNormal) {
    // Negative values distinguish a real terrain face marker from the previous
    // roughness payload and from entity G-buffer samples. The cache resolver
    // decodes -1..-6 to +X, -X, +Y, -Y, +Z, -Z.
    return -float(flatAxisBucket(geometricNormal) + 1);
}

void main() {
    // Sample base albedo texture
    vec4 textureAlbedo = texture(gtexture, texCoord);
#if BAREBONES_GBUFFER_ALBEDO != 0
    vec3 vertexTint = max(vertexColor.rgb, vec3(0.0));
    float tintPeak = max(max(vertexTint.r, vertexTint.g), vertexTint.b);
    vec3 tintChroma = tintPeak > 0.0001 ? vertexTint / tintPeak : vec3(1.0);
    vec4 albedo = vec4(textureAlbedo.rgb * tintChroma, textureAlbedo.a * vertexColor.a);
#else
    vec4 albedo = textureAlbedo * vertexColor;
#endif

    // Alpha test
    if (albedo.a < 0.1) discard;

    // ========================================================================
    // Sample PBR textures with fallbacks
    // ========================================================================

    // Normal map - default to flat normal (0.5, 0.5, 1.0)
    vec4 normalTex = vec4(0.5, 0.5, 1.0, 1.0);
    #ifdef MC_NORMAL_MAP
    normalTex = texture(normals, texCoord);
    #endif

    // Specular map - default to dielectric with F0 ~= 0.04
    // LabPBR: R=smoothness, G=F0/HCM, B=SSS, A=emission
    vec4 specularTex = defaultLabPbrSpecular();
    #ifdef MC_SPECULAR_MAP
    specularTex = texture(specular, texCoord);
    specularTex.a = samplePbrTexel(specular, texCoord).a;
    #endif
    specularTex = sanitizeLabPbrSpecular(specularTex);

    // ========================================================================
    // Normal Mapping
    // ========================================================================
    vec3 finalNormal;
    float ao = 1.0;

    // Check if normal map is valid (not pure black or pure white)
    bool hasNormalMap = !(normalTex.r <= 0.01 && normalTex.g <= 0.01 && normalTex.b <= 0.01) &&
                        !(normalTex.r >= 0.99 && normalTex.g >= 0.99 && normalTex.b >= 0.99);

    if (hasNormalMap) {
    vec3 tangentNormal;
    tangentNormal = decodeLabPbrNormal(normalTex);
    ao = decodeLabPbrAmbientOcclusion(normalTex);
    finalNormal = normalize(tbnMatrix * tangentNormal);
    } else {
    finalNormal = normalize(normal);
    }
    if (isFoliageMaterial(blockId)) {
        finalNormal = applyFoliageNormal(finalNormal, worldPos, frameTimeCounter);
    } else if (isWaterMaterial(blockId)) {
        finalNormal = applyWaterNormal(finalNormal, worldPos, frameTimeCounter);
    } else if (isIceMaterial(blockId)) {
        finalNormal = applyIceNormal(finalNormal, worldPos, frameTimeCounter);
    }

    // ========================================================================
    // PBR Material Properties
    // ========================================================================
    float smoothness = specularTex.r;
    float roughness = (1.0 - smoothness) * (1.0 - smoothness);

    vec3 F0;
    float metallic = 0.0;

    int f0Channel = labPbrByte(specularTex.g);

    if (f0Channel < 230) {
        // Dielectric - F0 is stored directly in LabPBR green.
        F0 = vec3(float(f0Channel) / 255.0);
    } else if (f0Channel < 238) {
        // HCM Metal - use lookup table
        F0 = HCM_METALS[f0Channel - 230];
        metallic = 1.0;
    } else {
        // Conductor (f0 >= 238) - albedo IS the reflectance
        F0 = albedo.rgb;
        metallic = 1.0;
    }

    F0 = clamp(F0, vec3(0.0), vec3(1.0));
    float specularAlpha = specularTex.a;
    bool hasLabPbrEmission = hasExplicitLabPbrEmission(specularAlpha);
    float labPbrEmission = decodeLabPbrEmission(specularAlpha);
    float emission = hasLabPbrEmission ? encodeExplicitLabPbrEmission(labPbrEmission) : 0.0;
    applyBlockMaterialOverrides(blockId, albedo.rgb, F0, metallic, roughness);

    // ========================================================================
    // Output - Properly formatted for DLSSD Ray Reconstruction
    // ========================================================================
    
    // IMPORTANT: For proper metallic rendering:
    // - Metals: albedo contains the base color (used for tinting), F0 has reflectance
    // - Dielectrics: albedo is diffuse color, F0 is ~0.04
    // The ray tracing shader will handle the proper PBR combination.
    // We keep albedo.rgb for metals so the RT shader can use it for colored metal rendering.
    // Metal albedo is used as the base color that gets modulated by F0 reflectance.
    
    // colortex1: Diffuse Albedo (RGB), Alpha (A)
    // For metals, we store the albedo color - RT shader will use F0 for specular
    colortex1 = vec4(albedo.rgb, albedo.a);
    
    // colortex2: Specular Albedo / F0 (RGB), Roughness (A)
    // DLSSD expects F0 reflectance values here for proper ray reconstruction
    // Metallic flag is encoded: if any F0 component > 0.5, it's a metal
    colortex2 = vec4(F0, roughness);
    
    // colortex3: signed world-space shaded normal (RGB), terrain flat-face
    // marker (A). Roughness is already stored in colortex2.a and copied to the
    // final DLSS/RR guide by resolve/raygen. The face marker keeps persistent
    // blocklight cache keys independent from normal maps and animated normals.
    colortex3 = vec4(finalNormal, encodeSurfaceFaceBucket(normal));
    
    // colortex4: World Position (RGB), Metallic flag in A (1.0 for metal, 0.0 for dielectric)
    // Storing metallic flag here so RT shader knows material type
    colortex4 = vec4(worldPos, encodeGbufferMaterialTag(blockId, metallic));
    
    // colortex5: Blocklight (R), Skylight (G), AO (B), emission (A)
    // Positive A is a fallback block-light strength. Negative A encodes explicit
    // LabPBR alpha so animated dark pixels can suppress the block fallback.
    colortex5 = vec4(lightmapCoord.x, lightmapCoord.y, ao, emission);
}
