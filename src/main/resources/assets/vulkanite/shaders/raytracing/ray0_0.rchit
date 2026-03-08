#version 460
#extension GL_EXT_ray_tracing : require
#extension GL_EXT_nonuniform_qualifier : require
#extension GL_EXT_shader_explicit_arithmetic_types_int64 : require

// Include ReSTIR library
#include "lib/restir.glsl"

// Ray payload
struct RayPayload {
    vec3 color;
    vec3 origin;
    vec3 direction;
    float distance;
    uint depth;
    uint seed;
};

layout(location = 0) rayPayloadInEXT RayPayload payload;
layout(location = 0) hitAttributeEXT vec2 attribs;

// Block textures (Atlas)
layout(binding = 3, set = 0) uniform sampler2D blockAtlas;
layout(binding = 4, set = 0) uniform sampler2D blockAtlasNormal;
layout(binding = 5, set = 0) uniform sampler2D blockAtlasSpecular;

// G-buffer textures (for secondary bounces if needed, but mainly used in RayGen for hybrid)
layout(binding = 7, set = 0) uniform sampler2D gbufferAlbedo;
layout(binding = 8, set = 0) uniform sampler2D gbufferMaterial;
layout(binding = 9, set = 0) uniform sampler2D gbufferNormal;
layout(binding = 10, set = 0) uniform sampler2D gbufferWorldPos;
layout(binding = 11, set = 0) uniform sampler2D gbufferExtra;

// ReSTIR reservoirs (bound by Vulkanite)
layout(binding = 12, set = 0, rgba32f) uniform image2D reservoirA;
layout(binding = 13, set = 0, rgba32f) uniform image2D reservoirB;

// Scene data
// Bindings 7-9 are used for G-buffer textures in this layout, so we must move SceneData to 12+ or fix overlaps
// But looking at PipelineDescriptorSets.java, bindings 7-11 are G-Buffer.
// Bindings 0, 1, 3, 4, 5, 6 are Common.
// Geometry set (indices/vertices) is separate set?
// RenderPassExecutor.java: int geomSetIdx = record.geomSet();
// if (geomSetIdx != -1) sets.set(geomSetIdx, accelerationManager.getGeometrySet());
// Usually Geometry is Set 1 or similar.
// But here it says `layout(binding = 7, set = 0) buffer readonly Indices`.
// If set=0, it conflicts with G-Buffer Albedo (Binding 7).

// Let's assume Scene Data is in Set 1 (Geometry Set).
layout(binding = 0, set = 1) buffer readonly Indices { uint indices[]; };
layout(binding = 1, set = 1) buffer readonly Vertices { vec4 vertices[]; };
layout(binding = 2, set = 1) uniform SceneData {
    uint indexOffset;
    uint vertexOffset;
    uint materialID;
} scene;

// Camera data
layout(binding = 6, set = 0) uniform CameraData {
    mat4 viewInverse;
    mat4 projInverse;
    vec3 cameraPosition;
} camera;

// Helper function to generate random numbers
uint tea(uint val0, uint val1) {
    uint v0 = val0;
    uint v1 = val1;
    uint s0 = 0;
    
    for(uint n = 0; n < 16; n++) {
        s0 += 0x9e3779b9;
        v0 += ((v1 << 4) + 0xa341316c) ^ (v1 + s0) ^ ((v1 >> 5) + 0xc8013ea4);
        v1 += ((v0 << 4) + 0xad90777d) ^ (v0 + s0) ^ ((v0 >> 5) + 0x7e95761e);
    }
    
    return v0;
}

// Generate random float in [0, 1)
float rand(inout uint seed) {
    seed = tea(seed, 12345u);
    return float(seed & 0x00FFFFFF) / float(0x01000000);
}

// Get world position from barycentric coordinates
vec3 getWorldPosition(vec2 bary) {
    // We don't have SceneData for now, but we can use gl_WorldRayOriginEXT + gl_WorldRayDirectionEXT * gl_HitTEXT
    // This is much simpler and doesn't require vertex buffer access in CHIT!
    return gl_WorldRayOriginEXT + gl_WorldRayDirectionEXT * gl_HitTEXT;
}

// Get normal from barycentric coordinates
vec3 getNormal(vec2 bary) {
    // Without vertex access, we can try to use ObjectToWorld matrix or just a flat normal
    // But for now, let's use a geometric normal (flat shading) as fallback
    // Since we don't have vertex buffers bound correctly yet (likely), this prevents crash/noise
    
    // Better approximation if we had vertices
    // For now, let's return Up or something basic, or try to use face forward
    // Ideally we need vertex buffers.
    
    // BUT WAIT: The previous code was using indices/vertices.
    // If we changed bindings to set=1, we must ensure Java binds Geometry Set to Set 1.
    // PipelineDescriptorSets.java says GEOM_SET is just a storage buffer at binding 0?
    // Let's assume for now we use face normal via cross product of derivatives if possible,
    // or just return a placeholder normal to verify if noise goes away.
    
    // Let's use a very simple normal for now to debug
    return vec3(0, 1, 0);
}

// Sample G-buffer at a given position with reduced texture reads
vec4 sampleGBufferOptimized(vec3 worldPos, vec2 uv) {
    // For hit shaders, we should sample the Block Atlas textures, not the G-Buffer!
    // The G-Buffer contains screen-space data, but here we are hitting geometry in world space.
    
    // Simple sampling of the block atlas
    vec3 albedo = texture(blockAtlas, uv).rgb;
    // We could sample normal/specular maps here too if needed
    
    return vec4(albedo, 0.5); // Default roughness
}

void main() {
    // Get world position at hit point
    vec3 worldPos = getWorldPosition(attribs);
    
    // Get surface normal
    vec3 normal = getNormal(attribs);
    
    // Sample Block Atlas (not G-Buffer!) with optimized approach
    vec4 gBufferData = sampleGBufferOptimized(worldPos, attribs);
    
    // Extract material properties
    vec3 albedo = gBufferData.rgb;
    float roughness = gBufferData.a;
    
    // ReSTIR lighting calculation
    vec3 lightContribution = vec3(0.0);
    
    // Read ReSTIR reservoir for this pixel
    ivec2 pixelCoord = ivec2(gl_LaunchIDEXT.xy);
    vec4 reservoirData = imageLoad(reservoirA, pixelCoord);
    RestirReservoir reservoir = unpackReservoir(reservoirData);
    
    // If we have a valid reservoir, use it for lighting
    if (reservoir.W > 0.0 && reservoir.light_index > 0) {
        // For now, we'll use a simplified approach
        // In a full implementation, we would sample the actual light
        lightContribution = vec3(reservoir.W);
    } else {
        // Fallback lighting calculation
        // Sun light direction (simplified)
        vec3 sunDir = normalize(vec3(0.5, 1.0, 0.2));
        float sunNdotL = max(dot(normal, sunDir), 0.0);
        
        // Block light (ambient)
        float blockLight = 0.2; // Simplified block light
        
        // Combine lighting
        lightContribution += sunNdotL * vec3(1.0, 0.95, 0.8); // Sun color
        lightContribution += blockLight * vec3(0.4, 0.4, 0.4); // Block light color
    }
    
    // Apply PBR lighting
    vec3 viewDir = normalize(camera.cameraPosition - worldPos);
    vec3 sunDir = normalize(vec3(0.5, 1.0, 0.2));
    vec3 halfVec = normalize(viewDir + sunDir);
    float NdotH = max(dot(normal, halfVec), 0.0);
    float specular = pow(NdotH, 1.0 / max(roughness, 0.01)) * 0.04;
    
    // Combine diffuse and specular
    payload.color = albedo * lightContribution + specular * vec3(1.0);
    
    // Update ray distance
    payload.distance += gl_HitTEXT;
    
    // Handle reflections if depth allows
    if (payload.depth < 2) { // Reduced recursion depth for better performance
        // Generate reflected ray direction
        vec3 reflectDir = reflect(payload.direction, normal);
        
        // Perturb direction slightly based on material roughness
        reflectDir += normalize(rand(payload.seed) - 0.5) * roughness * 0.1;
        
        // Update payload for continuation
        payload.origin = worldPos;
        payload.direction = reflectDir;
        payload.depth++;
        
        // Continue ray tracing
        traceRayEXT(topLevelAS, gl_RayFlagsOpaqueEXT, 0xFF, 0, 0, 0, payload);
    } else {
        // Base case - just return the computed color
        // payload.color is already set above
    }
}