#version 460
#extension GL_EXT_ray_tracing : enable
#extension GL_EXT_shader_explicit_arithmetic_types_int16 : require

#include "/lib/rt/payload.glsl"
#include "/lib/rt/settings.glsl"
#include "/lib/rt/sky.glsl"

layout(location = 0) rayPayloadInEXT Payload payload;

// Sky color for environment reflections
// When a ray misses all geometry, we return the procedural sky color
// based on the ray direction. This is essential for:
// 1. Metal reflections - metals need to reflect the environment
// 2. Specular reflections - rough surfaces need environment probes
// 3. Indirect lighting - diffuse bounces need sky radiance

// Uniform data for sky calculation (must match ray0.rgen)
layout(std140, binding = 0) uniform CameraInfo {
    vec3 corners[4];
    mat4 viewInverse;
    vec4 sunPosition;
    vec4 moonPosition;
    uint frameId;
    uint flags;
    vec2 padding1;
    mat4 prevViewProj;
    vec4 jitterData;
} cam;

// Push constants for sun direction (must match ray0.rgen)
layout(push_constant) uniform PushConstants {
    uint frameIndex;
    uint sampleIndex;
    float sunDirX;
    float sunDirY;
    float sunDirZ;
    float sunColorR;
    float sunColorG;
    float sunColorB;
    int enableReSTIR;
    int debugMode;
    int debugCellIndex;
    int separateStableBlocklight;
} pc;

void main(void) {
    // Calculate sky color for this ray direction
    vec3 rayDir = gl_WorldRayDirectionEXT;
    
    // Get sun direction and color for sky calculation
    vec3 sunDirView = vec3(pc.sunDirX, pc.sunDirY, pc.sunDirZ);
    if (dot(sunDirView, sunDirView) < 0.000001) {
        sunDirView = vec3(0.5, 1.0, 0.2); // Default sun direction
    }
    vec3 lightDir = normalize(mat3(cam.viewInverse) * normalize(sunDirView));
    
    vec3 sunColor = vec3(pc.sunColorR, pc.sunColorG, pc.sunColorB) * SUN_INTENSITY;
    
    // Get procedural sky color based on ray direction
    vec3 moonDir = normalize(cam.moonPosition.xyz);
    vec3 skyColor = getSkyColor(
        rayDir,
        lightDir,
        moonDir,
        sunColor,
        float(cam.frameId) * 0.016);
    
    // Store sky color in hitData.xyz, with w = -1.0 to indicate "miss"
    // The ray generation shader will interpret this as "use hitData.xyz as sky color"
    payload.hitData = vec4(skyColor, -1.0);
    
    // Clear other payload fields for safety
    payload.geometryNormal = vec3(0.0);
    payload.material.albedo = vec3(0.0);
    payload.material.emission = vec3(0.0);
    payload.material.roughness = 1.0;
    payload.material.metallic = 0.0;
    payload.material.F0 = vec3(0.04);
    payload.material.normal = vec3(0, 1, 0);
    payload.material.ambientOcclusion = 1.0;
    payload.material.translucent = 1.0;
    payload.material.block_id = i16vec2(0);
    payload.material.light_texture = vec3(0.0);
    payload.shadowTransmission = vec3(1.0);
    payload.ignore_block_id = i16vec2(0);
    payload.inside_block = false;
    payload.prev_distance = 0.0;
}
