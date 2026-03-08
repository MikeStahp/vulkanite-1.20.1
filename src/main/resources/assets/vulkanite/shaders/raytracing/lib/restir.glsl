#ifndef RESTIR_GLSL
#define RESTIR_GLSL 1

#include "/lib/rt/utils.glsl"

// ReSTIR implementation for Vulkanite
// Based on the Dirt-RT implementation but adapted for our needs

// ReSTIR reservoir structure
struct RestirReservoir {
    float w_sum;      // Sum of weights
    float W;          // Confidence weight (w_sum / (m * pHat))
    float m;          // Number of candidates seen
    vec2 offset;      // Jittered offset for the sun disk
};

// Pack/Unpack logic
RestirReservoir unpackReservoir(vec4 data) {
    return RestirReservoir(data.x, data.y, data.z, unpackHalf2x16(floatBitsToUint(data.w)));
}

vec4 packReservoir(RestirReservoir r) {
    return vec4(r.w_sum, r.W, r.m, uintBitsToFloat(packHalf2x16(r.offset)));
}

void initReservoir(out RestirReservoir r) {
    r.w_sum = 0.0;
    r.W = 0.0;
    r.m = 0.0;
    r.offset = vec2(0.0);
}

// ReSTIR Reservoir - binding 6 (current frame write target)
layout(binding = 6, rgba32f) uniform image2D reservoirImage;

// Previous frame reservoir - binding 15 (read-only for temporal/spatial reuse)
layout(binding = 15, rgba32f) uniform image2D prevReservoirImage;

// Target function for directional lights (sun)
// Uses NdotL * luminance — no distance attenuation for directional sources
float getPHat(vec3 pos, vec3 normal, vec3 lightPos, vec3 lightColor) {
    vec3 L = normalize(lightPos - pos);
    float NdotL = max(0.0, dot(normal, L));
    float intensity = dot(lightColor, vec3(0.299, 0.587, 0.114)); // Luminance
    return intensity * NdotL;
}

// Streaming Reservoir Sampling Update
bool updateReservoir(inout RestirReservoir r, float w, vec2 offset, float rnd) {
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
    float w = other.W * other.m * pHat;

    r.w_sum += w;
    r.m += other.m;

    if (r.w_sum > 0.0 && rnd < (w / r.w_sum)) {
        r.offset = other.offset;
    }
}

// Finalize W calculation
void finalizeReservoir(inout RestirReservoir r, float pHat) {
    if (pHat <= 0.0 || r.m == 0.0) {
        r.W = 0.0;
    } else {
        r.W = r.w_sum / (r.m * pHat);
    }
}

// Clamp sample count to avoid history exploding (Temporal)
void clampReservoir(inout RestirReservoir r, float maxM) {
    if (r.m > maxM) {
        r.w_sum *= maxM / r.m;
        r.m = maxM;
    }
}

// Spatial Reuse
// Samples neighboring reservoirs and combines them
void spatialReuse(inout RestirReservoir r, sampler2D gbufferNormal, sampler2D gbufferWorldPos, ivec2 pixelCoord, vec3 pos, vec3 normal, vec3 lightDir, vec3 lightColor, float radius, float rnd, int samples) {
    ivec2 size = imageSize(prevReservoirImage);
    vec2 invSize = 1.0 / vec2(size);
    
    // Basis for reconstructing direction from offset
    vec3 L_T, L_B;
    createOrthonormalBasis(lightDir, L_T, L_B);
    
    for (int i = 0; i < samples; i++) {
        // Simple ring sampling or random offset
        float angle = rnd * 6.283185 + float(i) * (6.283185 / float(samples));
        float dist = radius * (0.5 + 0.5 * fract(rnd * 0.123 + float(i) * 0.456));
        ivec2 offset = ivec2(cos(angle) * dist, sin(angle) * dist);
        ivec2 neighborCoord = clamp(pixelCoord + offset, ivec2(0), size - 1);
        vec2 neighborUV = (vec2(neighborCoord) + 0.5) * invSize;
        
        // Similarity check: Normal and Depth
        vec4 gNormal = texture(gbufferNormal, neighborUV);
        vec3 neighborNormal = normalize(gNormal.xyz * 2.0 - 1.0); // Decode [0,1] to [-1,1]
        vec3 neighborPos = texture(gbufferWorldPos, neighborUV).xyz;
        
        if (dot(normal, neighborNormal) < 0.9) continue; // Angle > ~25 degrees
        if (distance(pos, neighborPos) > 0.5) continue; // Distance > 0.5 blocks
        
        // Read neighbor reservoirs from prevReservoirImage (previous frame)
        RestirReservoir neighbor = unpackReservoir(imageLoad(prevReservoirImage, neighborCoord));
        
        // Construct the neighbor's light direction
        vec3 nDir = normalize(lightDir + (L_T * neighbor.offset.x + L_B * neighbor.offset.y));
        
        // Re-evaluate pHat for the current pixel using neighbor's light sample
        float pHat = getPHat(pos, normal, pos + nDir * 100.0, lightColor); 
        combineReservoir(r, neighbor, pHat, fract(rnd * 0.789 + float(i) * 0.321));
    }
}

#endif // RESTIR_GLSL