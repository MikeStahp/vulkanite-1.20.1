#ifndef FRAGMENT_INFO_GLSL
#define FRAGMENT_INFO_GLSL 1

#include "/lib/rt/data.glsl"
#include "/lib/rt/settings.glsl"

struct FragmentInfo {
    vec2 uv;
    vec3 tangent;
    vec3 bitangent;
    vec3 normal;
};

// Get interpolated UV with scale factor of 2 (like Dirt-RT-Old)
vec2 getFragmentUV(Quad quad, vec3 baryCoords, bool isSideA) {
    const float scale = ATLAS_SCALE;
    
    vec2 t0 = (quad.vertices[0].block_texture) * 0.0000152587890625;
    vec2 t1 = (isSideA ? quad.vertices[1].block_texture : quad.vertices[2].block_texture) * 0.0000152587890625;
    vec2 t2 = (isSideA ? quad.vertices[2].block_texture : quad.vertices[3].block_texture) * 0.0000152587890625;
    vec2 uv = t0 * baryCoords.x + t1 * baryCoords.y + t2 * baryCoords.z;
    return uv * scale;
}

vec2 getFragmentUV(Quad quad, vec2 baryCoords) {
    bool isSideA = (gl_PrimitiveID & 1) == 0;
    vec3 barys = vec3(1.0 - baryCoords.x - baryCoords.y, baryCoords.x, baryCoords.y);
    return getFragmentUV(quad, barys, isSideA);
}

FragmentInfo getFragmentInfo(Quad quad, vec2 baryCoords) {
    bool isSideA = (gl_PrimitiveID & 1) == 0;
    
    vec3 barys = vec3(1.0 - baryCoords.x - baryCoords.y, baryCoords.x, baryCoords.y);
    vec2 uv = getFragmentUV(quad, barys, isSideA);
    
    // Unpack and normalize vectors for proper TBN matrix
    vec3 normal = normalize(vec3(quad.vertices[0].normal) * 0.0078125);
    vec3 tangent = normalize(vec3(quad.vertices[0].tangent.xyz) * 0.0078125);
    // Bitangent sign stored in tangent.w: use ±1.0 based on sign
    float bitangentSign = quad.vertices[0].tangent.w >= 0 ? 1.0 : -1.0;
    vec3 bitangent = normalize(cross(normal, tangent)) * bitangentSign;
    
    return FragmentInfo(uv, tangent, bitangent, normal);
}

#endif // FRAGMENT_INFO_GLSL
