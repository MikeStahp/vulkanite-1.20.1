#version 460
#extension GL_EXT_ray_tracing : require
#extension GL_EXT_nonuniform_qualifier : require

// ============================================================================
// Vulkanite RT - Closest Hit Shader with Layered Lighting
// ============================================================================
// This shader calculates the layered lighting model at ray-surface intersection
// points. It implements:
// - Layer 0: Unlit Base (albedo from G-buffer or block atlas)
// - Layer 1: Blocklight (from Minecraft's lightmap)
// - Layer 2: Sunlight with ray-traced shadows
// - Layer 3: Cook-Torrance specular reflections
//
// All descriptor bindings match VULKANITE_RT_SHADER_BINDING_SPECIFICATION.md
// ============================================================================

// Include utility libraries
#include "lib/utils.glsl"
#include "lib/lighting.glsl"

// ============================================================================
// Ray Payload Structure (Defined in lib/lighting.glsl)
// ============================================================================

layout(location = 6) rayPayloadInEXT RayPayload payload;
layout(location = 6) rayPayloadEXT RayPayload nextPayload;
layout(location = 0) hitAttributeEXT vec2 attribs;  // Barycentric coordinates

// ============================================================================
// Descriptor Bindings (Set 0) - Must match Java specification exactly
// ============================================================================

// Binding 0: CameraInfo UBO (Defined in lib/lighting.glsl)
// layout(std140, binding = 0, set = 0) uniform CameraInfo { ... } cam;

// Binding 1: Top-Level Acceleration Structure (TLAS)
layout(binding = 1, set = 0) uniform accelerationStructureEXT tlas;

// Binding 3: Block Atlas (Albedo)
layout(binding = 3, set = 0) uniform sampler2D blockAtlas0;

// Binding 4: Block Atlas (Normal/PBR)
layout(binding = 4, set = 0) uniform sampler2D blockAtlas1;

// Binding 5: Block Atlas (Specular/PBR)
layout(binding = 5, set = 0) uniform sampler2D blockAtlas2;

// Binding 7: G-Buffer colortex1 (Albedo)
layout(binding = 7, set = 0) uniform sampler2D colortex1;

// Binding 8: G-Buffer colortex2 (Material - Roughness, Metallic, Specular, Emission)
layout(binding = 8, set = 0) uniform sampler2D colortex2;

// Binding 9: G-Buffer colortex3 (Normal)
layout(binding = 9, set = 0) uniform sampler2D colortex3;

// Binding 10: G-Buffer colortex4 (World Position)
layout(binding = 10, set = 0) uniform sampler2D colortex4;

// Binding 11: G-Buffer colortex5 (Extra - Lightmap/AO)
layout(binding = 11, set = 0) uniform sampler2D colortex5;

// Binding 6: Intermediate Render Target (storage image array)
layout(binding = 6, set = 0, rgba16f) uniform image2D RayTraceIntermediate[];

// Binding 12: Final Output Target (storage image array)
layout(binding = 12, set = 0, rgba16f) uniform image2D RayTraceData[];

// ============================================================================
// Constants
// ============================================================================

const float BIAS = 0.001;           // Ray origin bias to avoid self-intersection
const float EPSILON = 1e-5;         // Small epsilon for numerical stability
const float PI = 3.14159265359;     // Pi constant
const int MAX_BOUNCES = 3;          // Maximum ray bounces for Russian Roulette

// ============================================================================
// Helper Functions
// ============================================================================

/// Calculates texture UV coordinates from barycentric attributes and world position
/// @param bary Barycentric coordinates from hit attributes
/// @param worldPos World position of the hit point
/// @return UV coordinates for texture sampling
vec2 calculateTextureUV(vec2 bary, vec3 worldPos) {
    // Use the barycentric coordinates to interpolate UVs
    // For now, we use a simple projection based on world position
    // In a full implementation, this would use vertex UVs from the geometry buffer
    
    // Fallback: use world position for UV calculation
    vec2 uv = fract(worldPos.xz * 0.0625); // 1/16 = block texture scale
    
    // Apply barycentric interpolation if available
    uv = mix(uv, uv + bary, 0.0); // Placeholder for proper UV interpolation
    
    return uv;
}

/// Samples material properties from block atlas textures
/// @param uv UV coordinates for sampling
/// @param outAlbedo Output albedo color
/// @param outNormal Output normal (from normal map)
/// @param outRoughness Output roughness value
/// @param outMetallic Output metallic value
void sampleMaterial(vec2 uv, out vec3 outAlbedo, out vec3 outNormal, 
                    out float outRoughness, out float outMetallic) {
    // Sample albedo from block atlas
    vec4 albedoSample = texture(blockAtlas0, uv);
    outAlbedo = albedoSample.rgb;
    
    // Sample normal from block atlas (binding 4)
    vec4 normalSample = texture(blockAtlas1, uv);
    // Convert from [0,1] to [-1,1] and normalize
    outNormal = normalize(normalSample.rgb * 2.0 - 1.0);
    
    // Sample specular/roughness from block atlas (binding 5)
    vec4 specularSample = texture(blockAtlas2, uv);
    // Roughness is typically stored in the red channel
    outRoughness = specularSample.r;
    // Metallic could be in green channel or derived
    outMetallic = specularSample.g;
}

/// Gets the view direction from world position to camera
/// @param worldPos World position of the shading point
/// @return View direction (pointing towards camera)
vec3 getViewDirection(vec3 worldPos) {
    // Extract camera position from viewInverse matrix (4th column)
    vec3 cameraPos = cam.viewInverse[3].xyz;
    return normalize(cameraPos - worldPos);
}

/// Traces a shadow ray toward the sun
/// @param tlas Top-level acceleration structure
/// @param origin Ray origin (offset from surface)
/// @param direction Ray direction (toward light)
/// @return Visibility factor (1.0 = fully lit, 0.0 = in shadow)
float traceShadowRay(accelerationStructureEXT tlas, vec3 origin, vec3 direction) {
    // Shadow ray payload - we only need to know if we hit something
    // Using a simple float payload for shadow visibility
    
    // For shadow rays, we use a minimal payload
    // The hitType field indicates: 0=miss (visible), 1=hit (shadowed)
    nextPayload.color = vec3(1.0);      // Start with full visibility
    nextPayload.hitDistance = 1000.0;   // Max shadow ray distance
    nextPayload.hitType = 1;            // Assume hit (shadowed) initially
    
    // Cast shadow ray with opaque flag for performance
    // We don't need any-hit shader for shadows
    traceRayEXT(
        tlas,                           // Acceleration structure
        gl_RayFlagsOpaqueEXT | gl_RayFlagsSkipClosestHitShaderEXT | gl_RayFlagsTerminateOnFirstHitEXT, // Ray flags
        0xFF,                           // Ray mask (all bits)
        0,                              // SB record offset
        0,                              // SB record stride
        0,                              // Miss index
        origin,                         // Ray origin
        0.001,                          // Ray tmin
        direction,                      // Ray direction
        1000.0,                         // Ray tmax
        6                               // Payload location
    );
    
    // Return visibility: 1.0 if miss (no occlusion), 0.0 if hit (shadowed)
    // Note: Miss shader sets hitType = 0
    return (nextPayload.hitType == 0) ? 1.0 : 0.0;
}

/// Calculates sun visibility using ray-traced shadows
/// @param worldPos World position of the shading point
/// @param normal Surface normal
/// @return Sun visibility factor (0.0 to 1.0)
float calculateSunVisibility(vec3 worldPos, vec3 normal) {
    // Get sun direction from camera UBO
    vec3 sunDir = normalize(cam.sunPosition.xyz);
    
    // Offset ray origin to avoid self-intersection
    vec3 rayOrigin = worldPos + normal * BIAS;
    
    // Trace shadow ray toward sun
    return traceShadowRay(tlas, rayOrigin, sunDir);
}

/// Handles Russian Roulette ray termination for energy conservation
/// @param throughput Current ray throughput (energy)
/// @param depth Current ray bounce depth
/// @param seed Random seed reference
/// @return true if ray should continue, false if terminated
bool russianRoulette(vec3 throughput, int depth, inout uint seed) {
    if (depth < MAX_BOUNCES) {
        return true; // Always continue for first few bounces
    }
    
    // Calculate continuation probability based on throughput
    float p = max(throughput.r, max(throughput.g, throughput.b));
    p = min(p, 1.0); // Clamp to [0, 1]
    
    // Random decision to continue or terminate
    float xi = rand(seed);
    return xi < p;
}

// ============================================================================
// Closest Hit Entry Point
// ============================================================================

void main() {
    // =========================================================================
    // Step 1: Calculate hit point world position
    // =========================================================================
    
    // Use built-in ray variables for hit position calculation
    // gl_WorldRayOriginEXT + gl_WorldRayDirectionEXT * gl_HitTEXT gives world position
    vec3 worldPos = gl_WorldRayOriginEXT + gl_WorldRayDirectionEXT * gl_HitTEXT;
    
    // Calculate geometric normal using derivatives (fallback without vertex data)
    vec3 geometricNormal = normalize(cross(
        dFdx(worldPos),
        dFdy(worldPos)
    ));
    
    // =========================================================================
    // Step 2: Sample material properties from block atlas
    // =========================================================================
    
    // Calculate texture UV coordinates
    vec2 texUV = calculateTextureUV(attribs, worldPos);
    
    // Sample material properties
    vec3 albedo;
    vec3 normalMap;
    float roughness;
    float metallic;
    sampleMaterial(texUV, albedo, normalMap, roughness, metallic);
    
    // Use geometric normal as fallback, or transform normal map to world space
    // For now, use geometric normal (can be enhanced with normal mapping)
    vec3 normal = geometricNormal;
    
    // Ensure normal faces the ray direction
    if (dot(normal, -gl_WorldRayDirectionEXT) < 0.0) {
        normal = -normal;
    }
    
    // Clamp material properties to valid ranges
    roughness = clamp(roughness, 0.04, 1.0);
    metallic = clamp(metallic, 0.0, 1.0);
    
    // =========================================================================
    // Step 3: Calculate each lighting layer
    // =========================================================================
    
    // Get view direction
    vec3 viewDir = getViewDirection(worldPos);
    
    // Initialize random seed for this hit point
    uint seed = hashUint(gl_HitRecordEXT, gl_InstanceCustomIndexEXT);
    
    // --- Layer 0: Unlit Base ---
    // Sample albedo from G-buffer or use block atlas albedo
    vec3 unlitBase = calculateUnlitBase(albedo);
    
    // --- Layer 1: Blocklight ---
    // Sample lightmap from G-buffer colortex5
    // The lightmap UV or intensity may be stored in the G-buffer extra channel
    vec4 extraSample = texture(colortex5, gl_LaunchIDEXT.xy / vec2(gl_LaunchSizeEXT.xy));
    float blocklightIntensity = extraSample.r; // Assuming blocklight in red channel
    
    // Calculate blocklight contribution
    vec3 blocklight = calculateBlocklight(vec2(blocklightIntensity));
    blocklight *= BLOCKLIGHT_EMISSION_SCALE; // Scale for proper intensity
    
    // --- Layer 2: Sunlight with Ray-Traced Shadows ---
    // Calculate sun visibility using shadow ray
    float sunVisibility = calculateSunVisibility(worldPos, normal);
    
    // Calculate sunlight contribution with shadow factor
    vec3 sunlight = calculateSunlight(worldPos, normal, sunVisibility);
    
    // --- Layer 3: Specular (Cook-Torrance BRDF) ---
    // Calculate specular reflection using the sun direction
    vec3 sunDir = normalize(cam.sunPosition.xyz);
    vec3 specular = calculateSpecular(albedo, roughness, normal, viewDir, sunDir);
    
    // Modulate specular by sun visibility (shadows affect specular too)
    specular *= sunVisibility;
    
    // =========================================================================
    // Step 4: Combine lighting layers
    // =========================================================================
    
    // Use the combineLightingLayers function from lighting.glsl
    vec3 finalColor = combineLightingLayers(unlitBase, blocklight, sunlight, specular);
    
    // Apply additional firefly clamping for numerical stability
    finalColor = clampFirefly(finalColor, FIREFLY_THRESHOLD);
    
    // =========================================================================
    // Step 5: Set payload with final result
    // =========================================================================
    
    payload.color = finalColor;
    payload.hitDistance = gl_HitTEXT;
    payload.hitType = 1; // Indicate this is a hit (not miss or shadow)
    
    // =========================================================================
    // Optional: Debug output to intermediate image (can be used for visualization)
    // =========================================================================
    
    #ifdef DEBUG_OUTPUT
    if (RayTraceIntermediate.length() > 0) {
        imageStore(RayTraceIntermediate[0], ivec2(gl_LaunchIDEXT.xy), vec4(finalColor, 1.0));
    }
    #endif
    }
