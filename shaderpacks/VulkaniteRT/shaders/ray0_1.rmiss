#version 460
#extension GL_EXT_ray_tracing : require

layout(location = 1) rayPayloadInEXT float shadowPayload;

// OPTIMIZATION: Precomputed constant for shadow miss (full transmission)
const float SHADOW_MISS_VALUE = 1.0;

void main() {
  // OPTIMIZATION: Direct assignment of precomputed constant
  shadowPayload = SHADOW_MISS_VALUE;
}
