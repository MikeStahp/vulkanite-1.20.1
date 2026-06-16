#ifndef VULKANITE_RESTIR_GLSL
#define VULKANITE_RESTIR_GLSL 1
#define VULKANITE_RESTIR_API_VERSION 2

// ReSTIR implementation for Vulkanite
// Adapted from Radiance's reservoir model: keep explicit confidence/validity,
// re-evaluate donor samples at the query point, and gate reuse by surface
// similarity instead of raw screen-space proximity.

#ifndef RESTIR_MIN_TARGET
#define RESTIR_MIN_TARGET 1e-8
#endif

#ifndef RESTIR_MAX_WEIGHT
#define RESTIR_MAX_WEIGHT 16.0
#endif

#ifndef RESTIR_NORMAL_THRESHOLD
#define RESTIR_NORMAL_THRESHOLD 0.9
#endif

#ifndef RESTIR_PLANE_DISTANCE_TOLERANCE
#define RESTIR_PLANE_DISTANCE_TOLERANCE 0.08
#endif

#ifndef RESTIR_MIN_TANGENT_TOLERANCE
#define RESTIR_MIN_TANGENT_TOLERANCE 0.25
#endif

#ifndef RESTIR_TANGENT_PIXEL_FOOTPRINT_SCALE
#define RESTIR_TANGENT_PIXEL_FOOTPRINT_SCALE 2.0
#endif

#ifndef SUN_HISTORY_MAX_FRAMES
#define SUN_HISTORY_MAX_FRAMES 8.0
#endif

#ifndef SUN_HISTORY_NORMAL_THRESHOLD
#define SUN_HISTORY_NORMAL_THRESHOLD 0.92
#endif

// ReSTIR reservoir structure
struct RestirReservoir {
    float w_sum;      // Sum of weights
    float W;          // Confidence weight (w_sum / (m * pHat))
    float m;          // Number of candidates seen
    vec2 offset;      // Jittered offset for the sun disk
};

// Pack/Unpack logic
RestirReservoir unpackReservoir(vec4 data) {
    RestirReservoir r;
    r.w_sum = max(data.x, 0.0);
    r.W = max(data.y, 0.0);
    r.m = max(data.z, 0.0);
    r.offset = unpackHalf2x16(floatBitsToUint(data.w));
    if (any(isnan(data)) || any(isinf(data)) || any(isnan(r.offset)) || any(isinf(r.offset))) {
        r.w_sum = 0.0;
        r.W = 0.0;
        r.m = 0.0;
        r.offset = vec2(0.0);
    }
    return r;
}

vec4 packReservoir(RestirReservoir r) {
    if (any(isnan(vec4(r.w_sum, r.W, r.m, 0.0))) ||
        any(isinf(vec4(r.w_sum, r.W, r.m, 0.0))) ||
        any(isnan(r.offset)) || any(isinf(r.offset))) {
        r.w_sum = 0.0;
        r.W = 0.0;
        r.m = 0.0;
        r.offset = vec2(0.0);
    }

    r.w_sum = max(r.w_sum, 0.0);
    r.W = max(r.W, 0.0);
    r.m = max(r.m, 0.0);
    return vec4(r.w_sum, r.W, r.m, uintBitsToFloat(packHalf2x16(clamp(r.offset, vec2(-1.0), vec2(1.0)))));
}

bool normalizeSafe(vec3 v, out vec3 n) {
    if (any(isnan(v)) || any(isinf(v))) {
        n = vec3(0.0, 1.0, 0.0);
        return false;
    }
    float len2 = dot(v, v);
    if (len2 <= 1e-8) {
        n = vec3(0.0, 1.0, 0.0);
        return false;
    }
    n = v * inversesqrt(len2);
    return true;
}

void restirCreateOrthonormalBasis(vec3 n, out vec3 tangent, out vec3 bitangent) {
    vec3 up = abs(n.y) < 0.999 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0);
    tangent = normalize(cross(up, n));
    bitangent = cross(n, tangent);
}

void initReservoir(out RestirReservoir r) {
    r.w_sum = 0.0;
    r.W = 0.0;
    r.m = 0.0;
    r.offset = vec2(0.0);
}

bool reservoirValid(RestirReservoir r) {
    return r.m > RESTIR_MIN_TARGET && r.W > RESTIR_MIN_TARGET && r.w_sum > RESTIR_MIN_TARGET &&
           !any(isnan(vec4(r.w_sum, r.W, r.m, r.offset.x))) &&
           !any(isinf(vec4(r.w_sum, r.W, r.m, r.offset.x))) &&
           !isnan(r.offset.y) && !isinf(r.offset.y);
}

// ReSTIR Reservoir - binding 6 (current frame write target)
layout(binding = 6, rgba32f) uniform image2D reservoirImage;

// Previous frame reservoir - binding 15 (read-only for temporal/spatial reuse)
layout(binding = 15, rgba32f) uniform image2D prevReservoirImage;

vec2 restirOctEncode(vec3 normal) {
    normal /= max(abs(normal.x) + abs(normal.y) + abs(normal.z), 1e-8);
    vec2 encoded = normal.xy;
    if (normal.z < 0.0) {
        encoded = (1.0 - abs(encoded.yx)) * sign(encoded.xy);
    }
    return encoded;
}

vec3 restirOctDecode(vec2 encoded) {
    vec3 normal = vec3(encoded, 1.0 - abs(encoded.x) - abs(encoded.y));
    if (normal.z < 0.0) {
        normal.xy = (1.0 - abs(normal.yx)) * sign(normal.xy);
    }
    return normalize(normal);
}

float packSunShadowHistory(vec3 normal, float visibility, float historyLength) {
    vec2 encodedNormal = restirOctEncode(normal) * 0.5 + 0.5;
    vec4 packed = vec4(
        encodedNormal,
        clamp(visibility, 0.0, 1.0),
        clamp(historyLength / SUN_HISTORY_MAX_FRAMES, 0.0, 1.0));
    return uintBitsToFloat(packUnorm4x8(packed));
}

void unpackSunShadowHistory(float packedValue, out vec3 normal, out float visibility, out float historyLength) {
    vec4 packed = unpackUnorm4x8(floatBitsToUint(packedValue));
    normal = restirOctDecode(packed.xy * 2.0 - 1.0);
    visibility = packed.z;
    historyLength = packed.w * SUN_HISTORY_MAX_FRAMES;
}

float resolveSunShadowHistory(
        ivec2 pixel,
        vec2 previousPixel,
        bool hasPreviousProjection,
        vec3 absoluteWorldPos,
        vec3 normal,
        float viewDistance,
        float currentVisibility) {
    ivec2 size = imageSize(reservoirImage);
    float resolvedVisibility = clamp(currentVisibility, 0.0, 1.0);
    float outputHistoryLength = 1.0;

    if (hasPreviousProjection &&
            all(greaterThanEqual(previousPixel, vec2(0.0))) &&
            all(lessThan(previousPixel, vec2(size)))) {
        ivec2 previousCoord = clamp(ivec2(floor(previousPixel)), ivec2(0), size - 1);
        vec4 previousData = imageLoad(prevReservoirImage, previousCoord);
        vec3 previousNormal;
        float previousVisibility;
        float previousHistoryLength;
        unpackSunShadowHistory(previousData.w, previousNormal, previousVisibility, previousHistoryLength);

        if (previousHistoryLength >= 0.5 &&
                !any(isnan(previousData.xyz)) &&
                !any(isinf(previousData.xyz)) &&
                dot(previousNormal, normal) > SUN_HISTORY_NORMAL_THRESHOLD) {
            vec3 positionDelta = previousData.xyz - absoluteWorldPos;
            float planeDistance = max(abs(dot(positionDelta, normal)),
                                      abs(dot(positionDelta, previousNormal)));
            vec3 tangentNormal = normalize(normal + previousNormal);
            vec3 tangentDelta = positionDelta - tangentNormal * dot(positionDelta, tangentNormal);

            float worldPerPixel = max(viewDistance, 1.0) * 2.0 / float(max(size.y, 1));
            float planeTolerance = max(0.04, worldPerPixel * 1.5);
            float tangentTolerance = max(0.12, worldPerPixel * 2.5);
            if (planeDistance <= planeTolerance && length(tangentDelta) <= tangentTolerance) {
                float retainedHistory = min(previousHistoryLength, SUN_HISTORY_MAX_FRAMES - 1.0);
                float historyWeight = retainedHistory / (retainedHistory + 1.0);
                float clampedHistory = clamp(previousVisibility,
                                             resolvedVisibility - 0.35,
                                             resolvedVisibility + 0.35);
                resolvedVisibility = mix(resolvedVisibility, clampedHistory, historyWeight);
                outputHistoryLength = retainedHistory + 1.0;
            }
        }
    }

    imageStore(reservoirImage, pixel, vec4(
        absoluteWorldPos,
        packSunShadowHistory(normal, resolvedVisibility, outputHistoryLength)));
    return resolvedVisibility;
}

void clearSunShadowHistory(ivec2 pixel) {
    imageStore(reservoirImage, pixel, vec4(0.0));
}

// Target function for directional lights (sun)
// Uses NdotL * luminance — no distance attenuation for directional sources
float getPHat(vec3 pos, vec3 normal, vec3 lightPos, vec3 lightColor) {
    if (any(isnan(pos)) || any(isinf(pos)) || any(isnan(normal)) || any(isinf(normal)) ||
        any(isnan(lightPos)) || any(isinf(lightPos)) || any(isnan(lightColor)) || any(isinf(lightColor))) {
        return 0.0;
    }
    vec3 L = normalize(lightPos - pos);
    float NdotL = max(0.0, dot(normal, L));
    float intensity = dot(lightColor, vec3(0.299, 0.587, 0.114)); // Luminance
    return max(intensity * NdotL, 0.0);
}

// Streaming Reservoir Sampling Update
bool updateReservoir(inout RestirReservoir r, float w, vec2 offset, float rnd) {
    if (!(w > RESTIR_MIN_TARGET) || isnan(w) || isinf(w) || any(isnan(offset)) || any(isinf(offset))) {
        return false;
    }

    r.w_sum += w;
    r.m += 1.0;

    if (r.w_sum <= 0.0) return false;

    if (rnd < (w / r.w_sum)) {
        r.offset = offset;
        return true;
    }
    return false;
}


// Combine another reservoir into the current one
void combineReservoir(inout RestirReservoir r, RestirReservoir other, float pHat, float rnd) {
    if (!reservoirValid(other) || !(pHat > RESTIR_MIN_TARGET) || isnan(pHat) || isinf(pHat)) {
        return;
    }

    float w = min(other.W * other.m * pHat, RESTIR_MAX_WEIGHT);
    if (!(w > RESTIR_MIN_TARGET) || isnan(w) || isinf(w)) {
        return;
    }

    r.w_sum += w;
    r.m += other.m;

    if (r.w_sum > 0.0 && rnd < (w / r.w_sum)) {
        r.offset = other.offset;
    }
}

// Finalize W calculation
void finalizeReservoir(inout RestirReservoir r, float pHat) {
    if (!(pHat > RESTIR_MIN_TARGET) || !(r.m > RESTIR_MIN_TARGET) || !(r.w_sum > RESTIR_MIN_TARGET) ||
        isnan(pHat) || isinf(pHat)) {
        r.w_sum = 0.0;
        r.W = 0.0;
        r.m = 0.0;
        r.offset = vec2(0.0);
    } else {
        r.W = min(r.w_sum / max(r.m * pHat, RESTIR_MIN_TARGET), RESTIR_MAX_WEIGHT);
    }
}

// Clamp sample count to avoid history exploding (Temporal)
void clampReservoir(inout RestirReservoir r, float maxM) {
    if (r.m > maxM) {
        r.w_sum *= maxM / r.m;
        r.m = maxM;
    }
}

bool restirSurfaceMatches(vec3 queryPos, vec3 queryNormal, vec3 donorPos, vec3 donorNormal, float pixelDistance) {
    if (dot(queryNormal, donorNormal) <= RESTIR_NORMAL_THRESHOLD) {
        return false;
    }

    vec3 delta = donorPos - queryPos;
    float planeDistance = max(abs(dot(delta, queryNormal)), abs(dot(delta, donorNormal)));
    if (planeDistance > RESTIR_PLANE_DISTANCE_TOLERANCE) {
        return false;
    }

    vec3 tangentReferenceNormal = normalize(queryNormal + donorNormal);
    vec3 tangentDelta = delta - tangentReferenceNormal * dot(delta, tangentReferenceNormal);
    float tangentTolerance = max(RESTIR_MIN_TANGENT_TOLERANCE,
                                 pixelDistance * RESTIR_TANGENT_PIXEL_FOOTPRINT_SCALE * 0.01);
    return length(tangentDelta) <= tangentTolerance;
}

// Spatial Reuse
// Samples neighboring reservoirs and combines them
void spatialReuse(inout RestirReservoir r, sampler2D gbufferNormal, sampler2D gbufferWorldPos, ivec2 pixelCoord, vec3 pos, vec3 normal, vec3 lightDir, vec3 lightColor, float radius, float rnd, int samples) {
    ivec2 size = imageSize(prevReservoirImage);
    vec2 invSize = 1.0 / vec2(size);
    
    // Basis for reconstructing direction from offset
    vec3 L_T, L_B;
    restirCreateOrthonormalBasis(lightDir, L_T, L_B);
    
    for (int i = 0; i < samples; i++) {
        // Simple ring sampling or random offset
        float angle = rnd * 6.283185 + float(i) * (6.283185 / float(samples));
        float dist = radius * sqrt(clamp(fract(rnd * 0.123 + float(i) * 0.456), 0.0, 1.0));
        ivec2 offset = ivec2(cos(angle) * dist, sin(angle) * dist);
        ivec2 neighborCoord = clamp(pixelCoord + offset, ivec2(0), size - 1);
        if (all(equal(neighborCoord, pixelCoord))) continue;
        vec2 neighborUV = (vec2(neighborCoord) + 0.5) * invSize;
        
        // Similarity check: Normal and Depth
        vec4 gNormal = texture(gbufferNormal, neighborUV);
        vec3 neighborNormal;
        if (!normalizeSafe(gNormal.xyz, neighborNormal)) continue;
        vec3 neighborPos = texture(gbufferWorldPos, neighborUV).xyz;
        
        float pixelDistance = max(length(vec2(neighborCoord - pixelCoord)), 1.0);
        if (!restirSurfaceMatches(pos, normal, neighborPos, neighborNormal, pixelDistance)) continue;
        
        // Read neighbor reservoirs from prevReservoirImage (previous frame)
        RestirReservoir neighbor = unpackReservoir(imageLoad(prevReservoirImage, neighborCoord));
        if (!reservoirValid(neighbor)) continue;
        
        // Construct the neighbor's light direction
        vec3 nDir = normalize(lightDir + (L_T * neighbor.offset.x + L_B * neighbor.offset.y));
        
        // Re-evaluate pHat for the current pixel using neighbor's light sample
        float pHat = getPHat(pos, normal, pos + nDir * 100.0, lightColor); 
        combineReservoir(r, neighbor, pHat, fract(rnd * 0.789 + float(i) * 0.321));
    }
}

#endif // VULKANITE_RESTIR_GLSL
