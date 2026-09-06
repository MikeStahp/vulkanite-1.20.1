#version 460
#extension GL_EXT_ray_tracing : require

layout(location = 1) rayPayloadInEXT float shadowPayload;
hitAttributeEXT vec4 proceduralHitAttribute;

void main() {
    shadowPayload = 0.0;
}
