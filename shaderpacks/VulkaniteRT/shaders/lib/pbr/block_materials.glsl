#ifndef BLOCK_MATERIALS_GLSL
#define BLOCK_MATERIALS_GLSL 1

const float BLOCK_ID_WATER = 1000.0;
const float BLOCK_ID_GLASS = 1001.0;
const float BLOCK_ID_PORTAL = 1002.0;
const float BLOCK_ID_EMISSIVE = 1003.0;
const float BLOCK_ID_SHROOMLIGHT = 1004.0;
const float BLOCK_ID_SCULK = 1005.0;
const float BLOCK_ID_REDSTONE = 1006.0;
const float BLOCK_ID_FOLIAGE = 1007.0;
const float BLOCK_ID_FLAME = 1008.0;
const float BLOCK_ID_FURNACE = 1009.0;
const float BLOCK_ID_SMALL_LIGHT = 1010.0;
const float BLOCK_ID_SHULKER = 1011.0;
const float BLOCK_ID_ICE = 1012.0;
const float BLOCK_ID_GOLD = 1100.0;
const float BLOCK_ID_METAL = 1101.0;
const float BLOCK_ID_COPPER = 1102.0;
const float BLOCK_ID_CRYSTAL = 1103.0;
const float LABPBR_DEFAULT_DIELECTRIC_F0 = 10.0 / 255.0;

vec4 samplePbrTexel(sampler2D atlas, vec2 uv) {
    ivec2 size = textureSize(atlas, 0);
    ivec2 texel = clamp(ivec2(floor(uv * vec2(size))), ivec2(0), size - 1);
    return texelFetch(atlas, texel, 0);
}

int labPbrByte(float value) {
    return int(round(clamp(value, 0.0, 1.0) * 255.0));
}

vec4 defaultLabPbrSpecular() {
    return vec4(0.0, LABPBR_DEFAULT_DIELECTRIC_F0, 0.0, 1.0);
}

bool isMissingLabPbrSpecular(vec4 specular) {
    return labPbrByte(specular.r) == 0 &&
           labPbrByte(specular.g) == 0 &&
           labPbrByte(specular.b) == 0 &&
           labPbrByte(specular.a) == 0;
}

vec4 sanitizeLabPbrSpecular(vec4 specular) {
    return isMissingLabPbrSpecular(specular) ? defaultLabPbrSpecular() : specular;
}

vec3 decodeLabPbrNormal(vec4 normalTex) {
    vec2 xy = normalTex.xy * 2.0 - 1.0;
    return normalize(vec3(xy, sqrt(max(1.0 - dot(xy, xy), 0.0))));
}

float decodeLabPbrAmbientOcclusion(vec4 normalTex) {
    return clamp(normalTex.b, 0.0, 1.0);
}

bool hasExplicitLabPbrEmission(float alpha) {
    return labPbrByte(alpha) != 255;
}

float decodeLabPbrEmission(float alpha) {
    int intEmission = labPbrByte(alpha);
    if (intEmission == 255) {
        return 0.0;
    }
    return clamp(float(intEmission) / 254.0, 0.0, 1.0);
}

float encodeExplicitLabPbrEmission(float emission) {
    return -(1.0 + clamp(emission, 0.0, 1.0));
}

bool hasEncodedLabPbrEmission(float encodedEmission) {
    return encodedEmission < -0.5;
}

float decodeEncodedLabPbrEmission(float encodedEmission) {
    return clamp(-encodedEmission - 1.0, 0.0, 1.0);
}

bool isBlockId(float blockId, float expected) {
    return abs(blockId - expected) < 0.5;
}

bool isWaterMaterial(float blockId) {
    return isBlockId(blockId, BLOCK_ID_WATER);
}

bool isGlassMaterial(float blockId) {
    return isBlockId(blockId, BLOCK_ID_GLASS);
}

bool isIceMaterial(float blockId) {
    return isBlockId(blockId, BLOCK_ID_ICE);
}

bool isCrystalMaterial(float blockId) {
    return isBlockId(blockId, BLOCK_ID_CRYSTAL);
}

bool isFoliageMaterial(float blockId) {
    return isBlockId(blockId, BLOCK_ID_FOLIAGE);
}

bool isRefractiveBlock(float blockId) {
    return isWaterMaterial(blockId) || isGlassMaterial(blockId) || isIceMaterial(blockId) || isCrystalMaterial(blockId);
}

vec3 waterTintColor(vec3 albedo) {
    return mix(vec3(WATER_TINT_R, WATER_TINT_G, WATER_TINT_B), max(albedo, vec3(0.02)), 0.18);
}

vec3 waterAbsorption(float distance) {
    return exp(
        -(vec3(1.0) - vec3(WATER_TINT_R, WATER_TINT_G, WATER_TINT_B))
        * min(max(distance, 0.0), 48.0)
        * (1.0 - WATER_CLARITY)
        * WATER_ABSORPTION_STRENGTH);
}

vec3 iceTintColor(vec3 albedo) {
    return mix(vec3(0.72, 0.90, 1.0), max(albedo, vec3(0.35)), 0.35);
}

vec3 iceAbsorption(float distance) {
    return exp(-vec3(0.10, 0.035, 0.015)
        * min(max(distance, 0.0), 32.0)
        * (1.0 - ICE_TRANSMISSION));
}

vec3 refractiveShadowTransmission(float blockId, vec3 albedo, float alpha, float distance) {
    if (isWaterMaterial(blockId)) {
        vec3 absorption = waterAbsorption(distance);
        return mix(waterTintColor(albedo), vec3(1.0), absorption);
    }
    if (isIceMaterial(blockId)) {
        vec3 tint = iceTintColor(albedo);
        return mix(tint * 0.82, vec3(1.0), iceAbsorption(distance));
    }
    if (isGlassMaterial(blockId)) {
        return mix(max(albedo, vec3(0.2)), vec3(1.0), GLASS_TRANSMISSION);
    }
    if (isCrystalMaterial(blockId)) {
        return mix(max(albedo, vec3(0.12)), vec3(1.0), CRYSTAL_TRANSMISSION);
    }
    return exp(-10.0 * distance * (1.05 - albedo) * alpha);
}

float decodeGbufferBlockId(float materialTag) {
    return materialTag >= 999.5 ? floor(materialTag + 0.5) : 0.0;
}

float encodeGbufferMaterialTag(float blockId, float metallic) {
    return blockId >= 999.5 ? blockId : clamp(metallic, 0.0, 1.0);
}

float blockEmitterHash(vec3 cell) {
    vec3 p = fract(cell * 0.1031);
    p += dot(p, p.yzx + 33.33);
    return fract((p.x + p.y) * p.z);
}

float blockEmissionFallbackLightLevel(float blockId) {
    if (isBlockId(blockId, BLOCK_ID_PORTAL)) return 11.0;
    if (isBlockId(blockId, BLOCK_ID_EMISSIVE)) return 15.0;
    if (isBlockId(blockId, BLOCK_ID_SHROOMLIGHT)) return 15.0;
    if (isBlockId(blockId, BLOCK_ID_SCULK)) return 6.0;
    if (isBlockId(blockId, BLOCK_ID_REDSTONE)) return 7.0;
    if (isBlockId(blockId, BLOCK_ID_FLAME)) return 15.0;
    if (isBlockId(blockId, BLOCK_ID_FURNACE)) return 13.0;
    if (isBlockId(blockId, BLOCK_ID_SMALL_LIGHT)) return 14.0;
    if (isBlockId(blockId, BLOCK_ID_SHULKER)) return SHULKER_EMISSION > 0.0 ? 15.0 : 0.0;
    return 0.0;
}

float blockEmissionFromLightLevel(float lightLevel) {
    return pow(clamp(lightLevel / 15.0, 0.0, 1.0), 1.25);
}

float blockEmissionMultiplier(float blockId, float intrinsicLightLevel, vec3 worldPos, float time) {
    float resolvedLightLevel = intrinsicLightLevel > 0.01
        ? clamp(intrinsicLightLevel, 0.0, 15.0)
        : blockEmissionFallbackLightLevel(blockId);
    float baseEmission = blockEmissionFromLightLevel(resolvedLightLevel);
    if (baseEmission <= 0.0) return 0.0;

    vec3 emitterCell = floor(worldPos + vec3(0.5));
    float seed = blockEmitterHash(emitterCell + vec3(blockId * 0.03125));

    if (isBlockId(blockId, BLOCK_ID_PORTAL)) {
        float pulse = 0.86 + 0.14 * sin(time * 2.1 + seed * 6.2831853);
        return baseEmission * pulse * 1.2;
    }
    if (isBlockId(blockId, BLOCK_ID_SHROOMLIGHT)) {
        return max(baseEmission, SHROOMLIGHT_EMISSION);
    }
    if (isBlockId(blockId, BLOCK_ID_SCULK)) {
        float pulse = 0.78 + 0.22 * sin(time * 2.7 + seed * 6.2831853);
        return max(baseEmission, SCULK_EMISSION * pulse);
    }
    if (isBlockId(blockId, BLOCK_ID_REDSTONE)) {
        return max(baseEmission, REDSTONE_EMISSION);
    }
    if (isBlockId(blockId, BLOCK_ID_FLAME)) {
        float flicker = 0.88
            + 0.12 * sin(time * 7.3 + seed * 6.2831853)
            + 0.05 * sin(time * 15.1 + seed * 11.7);
        return baseEmission * clamp(flicker, 0.70, 1.20) * 1.15;
    }
    if (isBlockId(blockId, BLOCK_ID_FURNACE)) {
        float pulse = 0.82 + 0.08 * sin(time * 3.4 + seed * 6.2831853);
        return baseEmission * pulse;
    }
    if (isBlockId(blockId, BLOCK_ID_SMALL_LIGHT)) {
        return baseEmission * 0.9;
    }
    if (isBlockId(blockId, BLOCK_ID_SHULKER)) {
        float pulse = 0.76 + 0.24 * sin(time * 1.8 + seed * 6.2831853);
        return SHULKER_EMISSION * pulse;
    }

    return baseEmission;
}

float blockEmissionMultiplier(float blockId) {
    return blockEmissionMultiplier(blockId, 0.0, vec3(0.0), 0.0);
}

vec3 blockEmissionTint(vec3 albedo, float blockId) {
    vec3 normalizedAlbedo = albedo / max(max(max(albedo.r, albedo.g), albedo.b), 0.08);
    if (isBlockId(blockId, BLOCK_ID_PORTAL)) {
        return mix(normalizedAlbedo, vec3(0.62, 0.18, 1.0), 0.58);
    }
    if (isBlockId(blockId, BLOCK_ID_REDSTONE)) {
        return vec3(1.0, 0.30, 0.055);
    }
    if (isBlockId(blockId, BLOCK_ID_SCULK)) {
        return mix(normalizedAlbedo, vec3(0.02, 0.72, 0.88), 0.72);
    }
    if (isBlockId(blockId, BLOCK_ID_SHROOMLIGHT)) {
        return mix(normalizedAlbedo, vec3(1.0, 0.55, 0.18), 0.42);
    }
    if (isBlockId(blockId, BLOCK_ID_FLAME)) {
        return mix(normalizedAlbedo, vec3(1.0, 0.38, 0.055), 0.36);
    }
    if (isBlockId(blockId, BLOCK_ID_FURNACE)) {
        return mix(normalizedAlbedo, vec3(1.0, 0.48, 0.12), 0.48);
    }
    if (isBlockId(blockId, BLOCK_ID_SMALL_LIGHT)) {
        return mix(normalizedAlbedo, vec3(1.0, 0.76, 0.42), 0.22);
    }
    if (isBlockId(blockId, BLOCK_ID_SHULKER)) {
        return mix(normalizedAlbedo, vec3(0.70, 0.32, 1.0), 0.34);
    }
    return mix(normalizedAlbedo, vec3(1.0), 0.08);
}

float blockEmissionStrength(float blockId, float emission) {
    float providedEmission = clamp(emission, 0.0, 8.0);
    return providedEmission > 0.0001
        ? providedEmission
        : blockEmissionMultiplier(blockId);
}

float blockEmissionStrength(float blockId, float emission, vec3 worldPos, float time) {
    float providedEmission = clamp(emission, 0.0, 8.0);
    return providedEmission > 0.0001
        ? providedEmission
        : blockEmissionMultiplier(blockId, 0.0, worldPos, time);
}

float visibleEmissionStrength(float strength) {
    return (1.0 - exp(-max(strength, 0.0))) * EMISSIVE_SURFACE_VISIBLE_INTENSITY;
}

vec3 visibleEmissionRadiance(vec3 radiance) {
    vec3 safeRadiance = max(radiance, vec3(0.0));
    float luma = dot(safeRadiance, vec3(0.299, 0.587, 0.114));
    if (luma <= 0.0001) {
        return vec3(0.0);
    }
    return safeRadiance * (visibleEmissionStrength(luma) / luma);
}

vec3 blockEmissionRadiance(vec3 albedo, float blockId, float emission) {
    float strength = blockEmissionStrength(blockId, emission);
    return blockEmissionTint(max(albedo, vec3(0.0)), blockId)
        * strength * EMISSIVE_SURFACE_INTENSITY;
}

vec3 blockEmissionRadiance(vec3 albedo, float blockId, float emission, vec3 worldPos, float time) {
    float strength = blockEmissionStrength(blockId, emission, worldPos, time);
    return blockEmissionTint(max(albedo, vec3(0.0)), blockId)
        * strength * EMISSIVE_SURFACE_INTENSITY;
}

vec3 blockEmissionSurfaceRadiance(vec3 albedo, float blockId, float emission) {
    float strength = blockEmissionStrength(blockId, emission);
    return blockEmissionTint(max(albedo, vec3(0.0)), blockId)
        * visibleEmissionStrength(strength);
}

vec3 blockEmissionSurfaceRadiance(vec3 albedo, float blockId, float emission, vec3 worldPos, float time) {
    float strength = blockEmissionStrength(blockId, emission, worldPos, time);
    return blockEmissionTint(max(albedo, vec3(0.0)), blockId)
        * visibleEmissionStrength(strength);
}

void applyBlockMaterialOverrides(
    float blockId,
    vec3 albedo,
    inout vec3 F0,
    inout float metallic,
    inout float roughness
) {
    if (isWaterMaterial(blockId)) {
        F0 = vec3(0.0204);
        metallic = 0.0;
        roughness = clamp(min(roughness, 0.060), WATER_MIN_ROUGHNESS, 0.080);
    } else if (isGlassMaterial(blockId)) {
        F0 = vec3(0.04);
        metallic = 0.0;
        roughness = clamp(max(roughness, GLASS_MIN_ROUGHNESS), GLASS_MIN_ROUGHNESS, 0.30);
    } else if (isIceMaterial(blockId)) {
        F0 = vec3(0.030);
        metallic = 0.0;
        roughness = clamp(min(roughness, 0.12), ICE_MIN_ROUGHNESS, 0.16);
    } else if (isBlockId(blockId, BLOCK_ID_GOLD)) {
        F0 = vec3(0.94423, 0.77610, 0.37340);
        metallic = 1.0;
        roughness = max(roughness, GOLD_MIN_ROUGHNESS);
    } else if (isBlockId(blockId, BLOCK_ID_METAL)) {
        F0 = mix(vec3(0.53123, 0.51236, 0.49583), max(albedo, vec3(0.12)), 0.16);
        metallic = 1.0;
        roughness = max(roughness, METAL_MIN_ROUGHNESS);
    } else if (isBlockId(blockId, BLOCK_ID_COPPER)) {
        F0 = vec3(0.92595, 0.72090, 0.50415);
        metallic = 1.0;
        roughness = max(roughness, COPPER_MIN_ROUGHNESS);
    } else if (isCrystalMaterial(blockId)) {
        F0 = mix(vec3(0.075), max(albedo, vec3(0.08)), 0.18);
        metallic = 0.0;
        roughness = clamp(max(roughness, CRYSTAL_MIN_ROUGHNESS), CRYSTAL_MIN_ROUGHNESS, 0.32);
    } else if (isFoliageMaterial(blockId)) {
        F0 = min(F0, vec3(0.04));
        metallic = 0.0;
        roughness = max(roughness, 0.62);
    }
}

vec3 applyFoliageNormal(vec3 normal, vec3 worldPos, float time) {
#if FOLIAGE_WIND == 0
    return normal;
#else
    float phase = dot(worldPos.xz, vec2(0.73, 1.17)) + worldPos.y * 0.31;
    vec2 sway = vec2(
        sin(phase + time * FOLIAGE_WIND_SPEED),
        cos(phase * 0.67 - time * FOLIAGE_WIND_SPEED * 0.83));
    vec3 bent = normal + vec3(sway.x, 0.18 * sway.y, sway.y)
        * (FOLIAGE_WIND_STRENGTH * (0.35 + 0.65 * abs(normal.y)));
    return normalize(bent);
#endif
}

vec3 applyWaterNormal(vec3 normal, vec3 worldPos, float time) {
#if WATER_WAVES == 0
    return normal;
#else
    if (abs(normal.y) < 0.72) {
        return normal;
    }

    vec2 p = worldPos.xz * WATER_WAVE_SCALE;
    float t = time * WATER_WAVE_SPEED;
    float lowFlow = sin(dot(worldPos.xz, vec2(0.023, -0.019)) + t * 0.61);
    float highFlow = cos(dot(worldPos.xz, vec2(0.041, 0.037)) - t * 0.88 + 1.9);
    p += vec2(lowFlow, highFlow) * 0.018;

    float a = dot(p, vec2(0.86, 0.50)) + t * 0.72;
    float b = dot(p * 1.35, vec2(-0.42, 0.91)) - t * 1.03;
    float c = dot(p * 0.73 + vec2(0.82, -0.47), vec2(0.35, 0.94)) + t * 1.41;
    vec2 slope = cos(a) * vec2(0.86, 0.50) * 1.05
        + cos(b) * vec2(-0.42, 0.91) * 0.46
        + cos(c) * vec2(0.35, 0.94) * 0.28;
    slope *= WATER_WAVE_STRENGTH;
    slope = clamp(slope, vec2(-0.32), vec2(0.32));

    vec3 waveNormal = normalize(vec3(-slope.x, sign(normal.y) * 1.15, -slope.y));
    return normalize(mix(normal, waveNormal, 0.72));
#endif
}

vec3 applyIceNormal(vec3 normal, vec3 worldPos, float time) {
    if (ICE_FROST_STRENGTH <= 0.0 || abs(normal.y) < 0.5) {
        return normal;
    }

    float grainA = sin(dot(worldPos.xz, vec2(1.73, -2.11)) + worldPos.y * 0.37);
    float grainB = cos(dot(worldPos.xz, vec2(-2.41, 1.29)) - worldPos.y * 0.23);
    vec2 frost = vec2(grainA, grainB) * ICE_FROST_STRENGTH;
    vec3 frostNormal = normalize(vec3(-frost.x, sign(normal.y), -frost.y));
    return normalize(mix(normal, frostNormal, 0.55));
}

#endif
