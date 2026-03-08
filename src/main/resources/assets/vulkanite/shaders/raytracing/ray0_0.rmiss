#version 460
#extension GL_EXT_ray_tracing : require

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

// Precomputed sky color and intensity to reduce uniform buffer reads
const vec3 SKY_COLOR = vec3(0.4, 0.6, 1.0);
const float INTENSITY = 1.0;

void main() {
    // Simple sky gradient based on ray direction with reduced computation
    vec3 rayDir = normalize(payload.direction);
    float t = max(0.0, rayDir.y); // Clamp to avoid negative values
    
    // Simplified sky color calculation
    vec3 skyColor = mix(vec3(0.2, 0.3, 0.5), SKY_COLOR, t);
    
    // Apply intensity
    payload.color = skyColor * INTENSITY;
}