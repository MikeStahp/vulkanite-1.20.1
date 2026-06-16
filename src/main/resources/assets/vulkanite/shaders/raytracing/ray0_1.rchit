#version 460
#extension GL_EXT_ray_tracing : require
#extension GL_EXT_nonuniform_qualifier : require

struct RayPayload {
    vec3 color;
    float hitDistance;
    int hitType;
};

layout(location = 6) rayPayloadInEXT RayPayload payload;
hitAttributeEXT vec2 attribs;

layout(set = 1, binding = 0, std430) readonly buffer EntityGeometry {
    uint words[];
} geometryBuffers[];

layout(set = 2, binding = 0) uniform sampler2D entityTextures[256];

void main() {
    payload.hitType = 2;
    payload.hitDistance = gl_HitTEXT;
}
