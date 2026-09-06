#version 460
#extension GL_EXT_ray_tracing : require

layout(location = 1) rayPayloadInEXT float shadowPayload;
hitAttributeEXT vec2 baryCoord;

void main() {
    shadowPayload = 0.0;
}
