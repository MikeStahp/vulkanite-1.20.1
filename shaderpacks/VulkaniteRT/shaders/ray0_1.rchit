#version 460
#extension GL_EXT_ray_tracing : require
#extension GL_EXT_nonuniform_qualifier : require
#extension GL_EXT_shader_explicit_arithmetic_types_int16 : require

#include "/lib/rt/payload.glsl"

layout(location = 0) rayPayloadInEXT Payload payload;
hitAttributeEXT vec2 baryCoord;

layout(set = 1, binding = 0, std430) readonly buffer EntityGeometry {
    uint words[];
} geometryBuffers[];

layout(set = 2, binding = 0) uniform sampler2D entityTextures[256];

const uint WORDS_PER_VERTEX = 16u;
const uint WORDS_PER_QUAD = WORDS_PER_VERTEX * 4u;

uint vertexBase(uint vertexIndex) {
    uint quadBase = uint(gl_PrimitiveID >> 1) * WORDS_PER_QUAD;
    return quadBase + vertexIndex * WORDS_PER_VERTEX;
}

vec3 vertexPosition(uint vertexIndex) {
    uint base = vertexBase(vertexIndex);
    return vec3(
        uintBitsToFloat(geometryBuffers[nonuniformEXT(gl_InstanceCustomIndexEXT + gl_GeometryIndexEXT)].words[base]),
        uintBitsToFloat(geometryBuffers[nonuniformEXT(gl_InstanceCustomIndexEXT + gl_GeometryIndexEXT)].words[base + 1u]),
        uintBitsToFloat(geometryBuffers[nonuniformEXT(gl_InstanceCustomIndexEXT + gl_GeometryIndexEXT)].words[base + 2u]));
}

vec3 vertexNormal(uint vertexIndex) {
    uint base = vertexBase(vertexIndex);
    return vec3(
        uintBitsToFloat(geometryBuffers[nonuniformEXT(gl_InstanceCustomIndexEXT + gl_GeometryIndexEXT)].words[base + 4u]),
        uintBitsToFloat(geometryBuffers[nonuniformEXT(gl_InstanceCustomIndexEXT + gl_GeometryIndexEXT)].words[base + 5u]),
        uintBitsToFloat(geometryBuffers[nonuniformEXT(gl_InstanceCustomIndexEXT + gl_GeometryIndexEXT)].words[base + 6u]));
}

vec2 vertexUv(uint vertexIndex) {
    uint base = vertexBase(vertexIndex);
    return vec2(
        uintBitsToFloat(geometryBuffers[nonuniformEXT(gl_InstanceCustomIndexEXT + gl_GeometryIndexEXT)].words[base + 8u]),
        uintBitsToFloat(geometryBuffers[nonuniformEXT(gl_InstanceCustomIndexEXT + gl_GeometryIndexEXT)].words[base + 9u]));
}

vec4 vertexColor(uint vertexIndex) {
    uint packed = geometryBuffers[nonuniformEXT(gl_InstanceCustomIndexEXT + gl_GeometryIndexEXT)]
        .words[vertexBase(vertexIndex) + 10u];
    return vec4(
        float(packed & 0xffu),
        float((packed >> 8u) & 0xffu),
        float((packed >> 16u) & 0xffu),
        float((packed >> 24u) & 0xffu)) / 255.0;
}

uint vertexTextureIndex() {
    return geometryBuffers[nonuniformEXT(gl_InstanceCustomIndexEXT + gl_GeometryIndexEXT)]
        .words[vertexBase(0u) + 11u];
}

uvec2 triangleIndices() {
    return (gl_PrimitiveID & 1) == 0 ? uvec2(1u, 2u) : uvec2(2u, 3u);
}

void main() {
    uvec2 other = triangleIndices();
    vec3 bary = vec3(1.0 - baryCoord.x - baryCoord.y, baryCoord.x, baryCoord.y);
    vec2 uv = vertexUv(0u) * bary.x
        + vertexUv(other.x) * bary.y
        + vertexUv(other.y) * bary.z;
    vec4 tint = vertexColor(0u) * bary.x
        + vertexColor(other.x) * bary.y
        + vertexColor(other.y) * bary.z;
    vec4 texel = texture(entityTextures[nonuniformEXT(vertexTextureIndex())], uv);
    vec4 albedo = vec4(pow(max(texel.rgb * tint.rgb, vec3(0.0)), vec3(2.2)), texel.a * tint.a);

    vec3 normal = normalize(
        vertexNormal(0u) * bary.x
        + vertexNormal(other.x) * bary.y
        + vertexNormal(other.y) * bary.z);
    uint geometryIndex = nonuniformEXT(gl_InstanceCustomIndexEXT + gl_GeometryIndexEXT);
    uint lightU = geometryBuffers[geometryIndex].words[vertexBase(0u) + 12u];
    uint lightV = geometryBuffers[geometryIndex].words[vertexBase(0u) + 13u];

    payload.hitData = vec4(
        gl_WorldRayOriginEXT + gl_RayTmaxEXT * gl_WorldRayDirectionEXT,
        gl_RayTmaxEXT);
    payload.geometryNormal = normal;
    payload.material.albedo = albedo.rgb;
    payload.material.F0 = vec3(0.04);
    payload.material.metallic = 0.0;
    payload.material.roughness = 0.7;
    payload.material.subsurface_scattering = 0.0;
    payload.material.emission = vec3(0.0);
    payload.material.normal = normal;
    payload.material.ambientOcclusion = 1.0;
    payload.material.translucent = albedo.a;
    payload.material.block_id = i16vec2(0);
    payload.material.light_texture = vec3(vec2(lightU, lightV) / 240.0, 0.0);
}
