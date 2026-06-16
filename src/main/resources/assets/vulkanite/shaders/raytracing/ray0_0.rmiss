#version 460
#extension GL_EXT_ray_tracing : require

struct RayPayload {
    vec3 color;
    float hitDistance;
    int hitType;
};

layout(location = 6) rayPayloadInEXT RayPayload payload;

void main() {
    payload.hitType = 0;
    payload.hitDistance = -1.0;
}
