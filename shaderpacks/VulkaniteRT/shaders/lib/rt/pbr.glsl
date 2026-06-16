#ifndef PBR_GLSL
#define PBR_GLSL 1

#include "/lib/rt/utils.glsl"

// ============================================================================
// PBR Lighting Functions (Cook-Torrance BRDF)
// ============================================================================

// Schlick Fresnel approximation
vec3 fresnelSchlick(float cosTheta, vec3 F0) {
    return F0 + (1.0 - F0) * pow(max(1.0 - cosTheta, 0.0), 5.0);
}

// GGX/Trowbridge-Reitz Normal Distribution Function
float distributionGGX(float NdotH, float roughness) {
    float a = roughness * roughness;
    float a2 = a * a;
    float NdotH2 = NdotH * NdotH;
    float denom = NdotH2 * (a2 - 1.0) + 1.0;
    return a2 / (PI * denom * denom + 1e-5);
}

// Smith's geometry function (Schlick-GGX)
float geometrySmith(float NdotV, float NdotL, float roughness) {
    float r = roughness + 1.0;
    float k = (r * r) / 8.0;
    float ggx1 = NdotV / (NdotV * (1.0 - k) + k);
    float ggx2 = NdotL / (NdotL * (1.0 - k) + k);
    return ggx1 * ggx2;
}

// Full Cook-Torrance BRDF evaluation for direct lighting
// Returns outgoing radiance for the given light
vec3 evaluatePBR(vec3 albedo, vec3 normal, vec3 viewDir, vec3 lightDir,
                 vec3 lightColor, vec3 F0, float roughness, float metallic, float specularIntensity) {
    vec3 V = viewDir;
    vec3 L = lightDir;
    vec3 H = normalize(V + L);

    float NdotV = max(dot(normal, V), 0.001);
    float NdotL = max(dot(normal, L), 0.0);
    float NdotH = max(dot(normal, H), 0.0);
    float HdotV = max(dot(H, V), 0.0);

    if (NdotL <= 0.0) return vec3(0.0);

    // Specular BRDF (Cook-Torrance microfacet)
    float clampedRoughness = max(roughness, 0.04);
    float D = distributionGGX(NdotH, clampedRoughness);
    float G = geometrySmith(NdotV, NdotL, clampedRoughness);
    vec3  F = fresnelSchlick(HdotV, F0);

    vec3 specular = (D * G * F) / (4.0 * NdotV * NdotL + 0.001);

    // Diffuse — use albedo directly instead of dividing by PI
    // This provides a better visual balance for Minecraft while keeping PBR properties intact.
    vec3 kS = F;
    vec3 kD = (1.0 - kS) * (1.0 - metallic);
    vec3 diffuse = kD * albedo;

    return (diffuse + specular * specularIntensity) * lightColor * NdotL;
}

void evaluatePBRSplit(vec3 albedo, vec3 normal, vec3 viewDir, vec3 lightDir,
                      vec3 lightColor, vec3 F0, float roughness, float metallic, float specularIntensity,
                      out vec3 diffuseTerm, out vec3 specularTerm) {
    vec3 V = viewDir;
    vec3 L = lightDir;
    vec3 H = normalize(V + L);

    float NdotV = max(dot(normal, V), 0.001);
    float NdotL = max(dot(normal, L), 0.0);
    float NdotH = max(dot(normal, H), 0.0);
    float HdotV = max(dot(H, V), 0.0);

    if (NdotL <= 0.0) {
        diffuseTerm = vec3(0.0);
        specularTerm = vec3(0.0);
        return;
    }

    float clampedRoughness = max(roughness, 0.04);
    float D = distributionGGX(NdotH, clampedRoughness);
    float G = geometrySmith(NdotV, NdotL, clampedRoughness);
    vec3 F = fresnelSchlick(HdotV, F0);

    vec3 specular = (D * G * F) / (4.0 * NdotV * NdotL + 0.001);
    vec3 kS = F;
    vec3 kD = (1.0 - kS) * (1.0 - metallic);
    vec3 diffuse = kD * albedo;

    diffuseTerm = diffuse * lightColor * NdotL;
    specularTerm = specular * specularIntensity * lightColor * NdotL;
}

#endif // PBR_GLSL
