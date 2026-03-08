#version 460
#extension GL_EXT_ray_tracing : require
#extension GL_EXT_nonuniform_qualifier : require

// ============================================================================
// Vulkanite RT Miss Shader
// ============================================================================
// Handles rays that don't intersect any geometry, rendering the sky
// environment with sun and moon disks.
//
// All descriptor bindings match VULKANITE_RT_SHADER_BINDING_SPECIFICATION.md
// ============================================================================

// ----------------------------------------------------------------------------
// Include utility libraries
// ----------------------------------------------------------------------------
#include "lib/utils.glsl"
#include "lib/lighting.glsl"

// ----------------------------------------------------------------------------
// Descriptor Bindings (Set 0) - Must match Java specification exactly
// ----------------------------------------------------------------------------

// Binding 0: CameraInfo UBO (Defined in lib/lighting.glsl)
// layout(std140, binding = 0, set = 0) uniform CameraInfo { ... } cam;

// Binding 1: TLAS (accelerationStructureEXT) - declared for consistency
layout(binding = 1, set = 0) uniform accelerationStructureEXT tlas;

// ----------------------------------------------------------------------------
// Ray Payload
// ----------------------------------------------------------------------------
// Uses location = 6 to match Java trace calls and closesthit shader

// RayPayload defined in lib/lighting.glsl
layout(location = 6) rayPayloadInEXT RayPayload payload;

// ----------------------------------------------------------------------------
// Constants
// ----------------------------------------------------------------------------

/// Angular radius of sun/moon disks (~0.26 degrees in radians)
const float CELESTIAL_ANGULAR_RADIUS = 0.0046;

/// Sun intensity for day rendering
const float SUN_DISK_INTENSITY = 1.0;

/// Moon intensity for night rendering
const float MOON_DISK_INTENSITY = 0.5;

/// Star intensity multiplier
const float STAR_INTENSITY = 0.5;

// ----------------------------------------------------------------------------
// Sky Color Calculation
// ----------------------------------------------------------------------------

/// Calculates the base sky gradient color based on ray direction
/// Implements a day/night gradient based on the Y component of the ray direction
///
/// @param rayDir Normalized ray direction in world space
/// @return Sky color for the given direction
vec3 calculateSkyGradient(vec3 rayDir) {
    // Sky gradient based on ray direction Y component
    // Higher Y (looking up) = lighter blue, Lower Y (looking at horizon) = darker
    float t = max(0.0, rayDir.y);
    
    // Day sky gradient: dark blue at horizon to light blue at zenith
    vec3 horizonColor = vec3(0.2, 0.3, 0.5);
    vec3 zenithColor = vec3(0.5, 0.7, 1.0);
    vec3 daySkyColor = mix(horizonColor, zenithColor, t);
    
    // Night sky: dark blue/black
    vec3 nightSky = vec3(0.02, 0.02, 0.05);
    
    // Determine if it's day or night based on sun position
    vec3 sunDir = normalize(cam.sunPosition.xyz);
    float sunHeight = sunDir.y;
    
    // Smooth transition between day and night
    // Sun height > 0.3 = full day, < -0.1 = full night
    float dayFactor = smoothstep(-0.1, 0.3, sunHeight);
    
    // Mix between day and night sky
    return mix(nightSky, daySkyColor, dayFactor);
}

/// Checks if the ray hits the sun disk
///
/// @param rayDir Normalized ray direction in world space
/// @return True if ray points toward sun disk
bool hitsSunDisk(vec3 rayDir) {
    vec3 sunDir = normalize(cam.sunPosition.xyz);
    float sunDot = dot(rayDir, sunDir);
    return sunDot > cos(CELESTIAL_ANGULAR_RADIUS);
}

/// Checks if the ray hits the moon disk
///
/// @param rayDir Normalized ray direction in world space
/// @return True if ray points toward moon disk
bool hitsMoonDisk(vec3 rayDir) {
    vec3 moonDir = normalize(cam.moonPosition.xyz);
    float moonDot = dot(rayDir, moonDir);
    return moonDot > cos(CELESTIAL_ANGULAR_RADIUS);
}

/// Calculates simple star field for night sky using hash-based noise
///
/// @param rayDir Normalized ray direction in world space
/// @return Star contribution color
vec3 calculateStars(vec3 rayDir) {
    // Use hash of ray direction for deterministic star positions
    float stars = hash(rayDir.xy * 100.0);
    
    // Only show stars in very dark areas (threshold > 0.99)
    if (stars > 0.99) {
        return vec3(stars) * STAR_INTENSITY;
    }
    
    return vec3(0.0);
}

// ----------------------------------------------------------------------------
// Miss Entry Point
// ----------------------------------------------------------------------------

/// Main miss shader entry point
/// Called when a ray doesn't intersect any geometry
void main() {
    // Reconstruct ray direction from payload
    // Use gl_WorldRayDirectionEXT provided by GL_EXT_ray_tracing
    vec3 rayDir = normalize(gl_WorldRayDirectionEXT);
    
    // Set hitType to 0 (miss)
    payload.hitType = 0;
    
    // Set hitDistance to negative value to indicate miss
    payload.hitDistance = -1.0;
    
    // Check for sun disk hit first (highest priority)
    if (hitsSunDisk(rayDir)) {
        // Ray hits sun disk - return bright sun color
        payload.color = vec3(1.0, 0.95, 0.8) * SUN_DISK_INTENSITY;
        return;
    }
    
    // Check for moon disk hit
    if (hitsMoonDisk(rayDir)) {
        // Ray hits moon disk - return moon color
        payload.color = vec3(0.9, 0.9, 1.0) * MOON_DISK_INTENSITY;
        return;
    }
    
    // Calculate base sky gradient
    vec3 skyColor = calculateSkyGradient(rayDir);
    
    // Add stars for night sky
    // Check if it's night based on sun position
    vec3 sunDir = normalize(cam.sunPosition.xyz);
    float sunHeight = sunDir.y;
    float nightFactor = 1.0 - smoothstep(-0.1, 0.3, sunHeight);
    
    if (nightFactor > 0.5) {
        // Add stars, scaled by night factor
        vec3 stars = calculateStars(rayDir);
        skyColor += stars * nightFactor;
    }
    
    // Set final sky color
    payload.color = skyColor;
}
