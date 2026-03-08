#version 460
#extension GL_EXT_ray_tracing : require
#extension GL_EXT_nonuniform_qualifier : require

// ============================================================================
// Vulkanite RT Utility Functions
// ============================================================================
// This file contains shared utility functions for the ray tracing shaders.
// Includes: firefly clamping, tone mapping, gamma correction, safe division,
// vector utilities, and random number generation.
// ============================================================================

#ifndef VULKANITE_UTILS_GLSL
#define VULKANITE_UTILS_GLSL

// ----------------------------------------------------------------------------
// Constants
// ----------------------------------------------------------------------------

const float PI = 3.14159265359;
const float INV_PI = 0.31830988618;
const float EPSILON = 1e-5;
const float FIREFLY_THRESHOLD = 10.0;

// ----------------------------------------------------------------------------
// Firefly Clamping
// ----------------------------------------------------------------------------

/// Clamps color values to prevent fireflies (extremely bright pixels)
/// Uses a threshold-based approach that preserves color ratios
/// @param color The input color to clamp
/// @param threshold Maximum luminance threshold (default: 10.0)
/// @return Clamped color with luminance <= threshold
vec3 clampFirefly(vec3 color, float threshold) {
    float luminance = dot(color, vec3(0.2126, 0.7152, 0.0722));
    if (luminance > threshold) {
        float scale = threshold / max(luminance, EPSILON);
        return color * scale;
    }
    return color;
}

/// Clamps color values using default threshold
vec3 clampFirefly(vec3 color) {
    return clampFirefly(color, FIREFLY_THRESHOLD);
}

// ----------------------------------------------------------------------------
// Tone Mapping
// ----------------------------------------------------------------------------

/// ACES Filmic Tone Mapping
/// Produces cinematic results with smooth highlights and preserved shadows
/// Based on Stephen Hill's approximation of ACES
/// @param color Input color in linear space (HDR)
/// @return Tone-mapped color in LDR space [0, 1]
vec3 toneMapACES(vec3 color) {
    const float a = 2.51;
    const float b = 0.03;
    const float c = 2.43;
    const float d = 0.59;
    const float e = 0.14;
    
    vec3 x = color * (a * color + b);
    vec3 y = color * (c * color + d) + e;
    return saturate(x / (y + EPSILON));
}

/// Reinhard Tone Mapping
/// Simple and fast tone mapping with soft highlight roll-off
/// @param color Input color in linear space (HDR)
/// @return Tone-mapped color in LDR space [0, 1]
vec3 toneMapReinhard(vec3 color) {
    return color / (color + vec3(1.0));
}

/// Reinhard Tone Mapping with white point
/// Allows control over the white point mapping
/// @param color Input color in linear space (HDR)
/// @param whitePoint The HDR value that maps to 1.0 in LDR
/// @return Tone-mapped color in LDR space [0, 1]
vec3 toneMapReinhard(vec3 color, float whitePoint) {
    vec3 x = color * (1.0 / whitePoint);
    return x / (x + vec3(1.0));
}

// ----------------------------------------------------------------------------
// Gamma Correction
// ----------------------------------------------------------------------------

/// Converts from linear space to gamma (sRGB) space
/// @param color Input color in linear space
/// @return Color in gamma space (sRGB)
vec3 toGammaSpace(vec3 color) {
    // Use proper sRGB transfer function
    vec3 linear = color;
    bvec3 cutoff = lessThan(linear, vec3(0.0031308));
    vec3 lower = linear * vec3(12.92);
    vec3 higher = vec3(1.055) * pow(linear, vec3(1.0 / 2.4)) - vec3(0.055);
    return mix(higher, lower, cutoff);
}

/// Converts from gamma (sRGB) space to linear space
/// @param color Input color in gamma space (sRGB)
/// @return Color in linear space
vec3 toLinearSpace(vec3 color) {
    // Use proper sRGB transfer function
    vec3 gamma = color;
    bvec3 cutoff = lessThan(gamma, vec3(0.04045));
    vec3 lower = gamma / vec3(12.92);
    vec3 higher = pow((gamma + vec3(0.055)) / vec3(1.055), vec3(2.4));
    return mix(higher, lower, cutoff);
}

// ----------------------------------------------------------------------------
// Safe Division
// ----------------------------------------------------------------------------

/// Safe division for floats with epsilon fallback
/// @param numerator The numerator
/// @param denominator The denominator
/// @return numerator / denominator, or 0 if denominator is too small
float safeDivide(float numerator, float denominator) {
    return (abs(denominator) > EPSILON) ? (numerator / denominator) : 0.0;
}

/// Safe division for vec3 with scalar denominator
/// @param numerator The numerator vector
/// @param denominator The scalar denominator
/// @return numerator / denominator for each component, or 0 if denominator is too small
vec3 safeDivide(vec3 numerator, float denominator) {
    float invDenom = (abs(denominator) > EPSILON) ? (1.0 / denominator) : 0.0;
    return numerator * invDenom;
}

/// Safe division for vec3 with vec3 denominator (component-wise)
/// @param numerator The numerator vector
/// @param denominator The denominator vector
/// @return Component-wise division, or 0 for components where denominator is too small
vec3 safeDivide(vec3 numerator, vec3 denominator) {
    vec3 invDenom = vec3(
        (abs(denominator.x) > EPSILON) ? (1.0 / denominator.x) : 0.0,
        (abs(denominator.y) > EPSILON) ? (1.0 / denominator.y) : 0.0,
        (abs(denominator.z) > EPSILON) ? (1.0 / denominator.z) : 0.0
    );
    return numerator * invDenom;
}

// ----------------------------------------------------------------------------
// Vector Utilities
// ----------------------------------------------------------------------------

/// Saturate function - clamps value to [0, 1] range
float saturate(float x) {
    return clamp(x, 0.0, 1.0);
}

/// Saturate function for vec3 - clamps each component to [0, 1] range
vec3 saturate(vec3 x) {
    return clamp(x, vec3(0.0), vec3(1.0));
}

/// Creates an orthonormal basis from a normal vector
/// @param N The normal vector (must be normalized)
/// @param T Output tangent vector
/// @param B Output bitangent vector
void createOrthonormalBasis(vec3 N, out vec3 T, out vec3 B) {
    // Use branchless method for better GPU performance
    float sign = sign(N.z) * 2.0 - 1.0;
    float a = -1.0 / (sign + N.z);
    float b = N.x * N.y * a;
    
    T = vec3(1.0 + sign * N.x * N.x * a, sign * b, -sign * N.x);
    B = vec3(b, sign + N.y * N.y * a, -N.y);
}

/// Reflects a vector around a normal
/// @param V The incident vector (pointing towards surface)
/// @param N The surface normal
/// @return Reflected vector
vec3 reflectVec(vec3 V, vec3 N) {
    return V - 2.0 * dot(V, N) * N;
}

/// Refracts a vector through a surface
/// @param V The incident vector (pointing towards surface)
/// @param N The surface normal
/// @param eta Ratio of indices of refraction (n1/n2)
/// @return Refracted vector, or zero if total internal reflection
vec3 refractVec(vec3 V, vec3 N, float eta) {
    float NdotV = dot(N, V);
    float k = 1.0 - eta * eta * (1.0 - NdotV * NdotV);
    if (k < 0.0) return vec3(0.0);
    return eta * V - (eta * NdotV + sqrt(k)) * N;
}

// ----------------------------------------------------------------------------
// Random Number Generation
// ----------------------------------------------------------------------------

/// Hash function for 2D coordinates
/// Returns a pseudo-random value in [0, 1)
/// @param p Input 2D coordinate (e.g., pixel position)
/// @return Hash value in [0, 1)
float hash(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * vec3(0.1031, 0.1030, 0.0973));
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

/// Hash function for 3D coordinates
/// Returns a pseudo-random value in [0, 1)
/// @param p Input 3D coordinate
/// @return Hash value in [0, 1)
float hash(vec3 p) {
    vec3 p3 = fract(p * vec3(0.1031, 0.1030, 0.0973));
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

/// Hash function for uint seed (TEA-based)
/// Good quality hash for ray tracing applications
/// @param val0 First value (e.g., pixel index)
/// @param val1 Second value (e.g., frame index)
/// @return Hash value
uint hashUint(uint val0, uint val1) {
    uint v0 = val0;
    uint v1 = val1;
    uint s0 = 0;
    
    for (uint n = 0; n < 8; n++) {
        s0 += 0x9e3779b9u;
        v0 += ((v1 << 4u) + 0xa341316cu) ^ (v1 + s0) ^ ((v1 >> 5u) + 0xc8013ea4u);
        v1 += ((v0 << 4u) + 0xad90777du) ^ (v0 + s0) ^ ((v0 >> 5u) + 0x7e95761eu);
    }
    
    return v0;
}

/// Generate random float in [0, 1) from uint seed
/// Updates the seed for subsequent calls
/// @param seed Reference to the seed value (modified in place)
/// @return Random float in [0, 1)
float rand(inout uint seed) {
    seed = hashUint(seed, 12345u);
    return float(seed & 0x00FFFFFF) / float(0x01000000);
}

/// Generate random float in [0, 1) from 2D coordinate and frame
/// @param coord 2D coordinate (e.g., pixel position)
/// @param frame Frame index for temporal variation
/// @return Random float in [0, 1)
float rand(vec2 coord, uint frame) {
    return hash(coord + vec2(frame) * 0.1);
}

/// Generate random point on unit sphere
/// @param seed Reference to the seed value (modified in place)
/// @return Random point on unit sphere
vec3 randomOnSphere(inout uint seed) {
    float u1 = rand(seed);
    float u2 = rand(seed);
    
    float z = 1.0 - 2.0 * u1;
    float r = sqrt(1.0 - z * z);
    float phi = 2.0 * PI * u2;
    
    return vec3(r * cos(phi), r * sin(phi), z);
}

/// Generate random point in hemisphere oriented around normal
/// Uses cosine-weighted sampling for importance sampling diffuse surfaces
/// @param normal The hemisphere orientation (must be normalized)
/// @param seed Reference to the seed value (modified in place)
/// @return Random direction in hemisphere
vec3 randomInHemisphere(vec3 normal, inout uint seed) {
    // Generate random point on sphere
    vec3 dir = randomOnSphere(seed);
    
    // Orient to hemisphere
    if (dot(dir, normal) < 0.0) {
        dir = -dir;
    }
    
    return dir;
}

/// Generate cosine-weighted random direction in hemisphere
/// Importance sampling for Lambertian diffuse surfaces
/// @param normal The hemisphere orientation (must be normalized)
/// @param seed Reference to the seed value (modified in place)
/// @return Cosine-weighted random direction in hemisphere
vec3 cosineWeightedHemisphere(vec3 normal, inout uint seed) {
    float u1 = rand(seed);
    float u2 = rand(seed);
    
    // Cosine-weighted sampling
    float r = sqrt(u1);
    float theta = 2.0 * PI * u2;
    
    // Convert to Cartesian coordinates in tangent space
    float x = r * cos(theta);
    float y = r * sin(theta);
    float z = sqrt(1.0 - u1);
    
    // Transform from tangent space to world space
    vec3 tangent, bitangent;
    createOrthonormalBasis(normal, tangent, bitangent);
    
    return normalize(x * tangent + y * bitangent + z * normal);
}

/// Generate random direction with Phong lobe distribution
/// @param normal The lobe orientation (must be normalized)
/// @param exponent Phong exponent (higher = tighter lobe)
/// @param seed Reference to the seed value (modified in place)
/// @return Random direction in Phong lobe
vec3 randomPhongLobe(vec3 normal, float exponent, inout uint seed) {
    float u1 = rand(seed);
    float u2 = rand(seed);
    
    // Phong distribution sampling
    float cosTheta = pow(u1, 1.0 / (exponent + 1.0));
    float sinTheta = sqrt(1.0 - cosTheta * cosTheta);
    float phi = 2.0 * PI * u2;
    
    // Convert to Cartesian
    float x = sinTheta * cos(phi);
    float y = sinTheta * sin(phi);
    float z = cosTheta;
    
    // Transform to world space
    vec3 tangent, bitangent;
    createOrthonormalBasis(normal, tangent, bitangent);
    
    return normalize(x * tangent + y * bitangent + z * normal);
}

// ----------------------------------------------------------------------------
// Utility Macros
// ----------------------------------------------------------------------------

#define saturate(x) clamp(x, 0.0, 1.0)
#define saturate3(x) clamp(x, vec3(0.0), vec3(1.0))

#endif // VULKANITE_UTILS_GLSL
