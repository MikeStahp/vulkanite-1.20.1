#version 460
#extension GL_EXT_ray_tracing : require
#extension GL_EXT_nonuniform_qualifier : require
#extension GL_EXT_shader_explicit_arithmetic_types_int64 : require
#extension GL_EXT_buffer_reference : require
#extension GL_EXT_shader_16bit_storage : require
#extension GL_EXT_shader_8bit_storage : require
#extension GL_EXT_shader_explicit_arithmetic_types : require

#include "/lib/rt/data.glsl"
#include "/lib/rt/payload.glsl"
#include "/lib/rt/fragment_info.glsl"

layout(location = 0) rayPayloadInEXT Payload payload;

hitAttributeEXT vec2 baryCoord;

layout(std140, binding = 0) uniform CameraInfo {
  vec3 corners[4];
  mat4 viewInverse;
  vec4 sunPosition;
  vec4 moonPosition;
  uint frameId;
  uint flags;
  vec2 padding1;
  mat4 prevViewProj;
  vec4 jitterData;
} cam;

layout(binding = 3) uniform sampler2D blockTex;
layout(binding = 4) uniform sampler2D blockTexNormal;
layout(binding = 5) uniform sampler2D blockTexSpecular;

layout(set = 1, binding = 0) buffer Quads {
  Quad quads[];
} geometryBuffers[];

Quad getRayQuad() {
  return geometryBuffers[nonuniformEXT(gl_InstanceCustomIndexEXT + gl_GeometryIndexEXT)].quads[gl_PrimitiveID >> 1];
}

// Color conversion constant (1.0 / 255.0)
const float COLOR_INV_255 = 0.0039215686274509803921568627451;
void main() {
	vec3 worldPos = gl_WorldRayOriginEXT + gl_RayTmaxEXT * gl_WorldRayDirectionEXT;
	Quad quad = getRayQuad();

  FragmentInfo fragInfo = getFragmentInfo(quad, baryCoord);
  vec4 shadeColor = quad.vertices[0].color * COLOR_INV_255;

  // Sample all PBR textures
  vec4 albedo = texture(blockTex, fragInfo.uv);
  vec4 normalTex = texture(blockTexNormal, fragInfo.uv);
  vec4 specular = texture(blockTexSpecular, fragInfo.uv);
  specular.a = samplePbrTexel(blockTexSpecular, fragInfo.uv).a;
  specular = sanitizeLabPbrSpecular(specular);

  // Apply vertex color tinting and gamma correction (sRGB → linear)
  albedo.rgb = pow(albedo.rgb * shadeColor.rgb, vec3(2.2));

  // Build TBN matrix for normal mapping
  mat3 tbn = mat3(
    fragInfo.tangent,
    fragInfo.bitangent,
    fragInfo.normal
  );

  // Extract full PBR material using LabPBR format
  Material mat = getMaterial(albedo, normalTex, specular, tbn, fragInfo.normal);
  mat.block_id = quad.vertices[0].block_id;

  // Interpolate light texture coordinates
  fragInfo.uv = fract(fragInfo.uv * vec2(64, 32));
  float AB = float(max(quad.vertices[1].position.x, quad.vertices[0].position.x)
                 - min(quad.vertices[1].position.x, quad.vertices[0].position.x));
  vec2 A = quad.vertices[0].light_texture.xy;
  vec2 B = quad.vertices[1].light_texture.xy;
  vec2 C = quad.vertices[2].light_texture.xy;
  vec2 D = quad.vertices[3].light_texture.xy;
  if (AB > 0.5) {
    A = quad.vertices[3].light_texture.xy;
    B = quad.vertices[0].light_texture.xy;
    C = quad.vertices[1].light_texture.xy;
    D = quad.vertices[2].light_texture.xy;
  }
  mat.light_texture = vec3(mix(mix(A, B, fragInfo.uv.y), mix(D, C, fragInfo.uv.y), fragInfo.uv.x), 0);

  float blockId = quad.vertices[0].block_id.x;
  applyBlockMaterialOverrides(blockId, albedo.rgb, mat.F0, mat.metallic, mat.roughness);
  if (isFoliageMaterial(blockId)) {
    mat.normal = applyFoliageNormal(mat.normal, worldPos, float(cam.frameId) * 0.016);
  } else if (isWaterMaterial(blockId)) {
    mat.normal = applyWaterNormal(mat.normal, worldPos, float(cam.frameId) * 0.016);
  } else if (isIceMaterial(blockId)) {
    mat.normal = applyIceNormal(mat.normal, worldPos, float(cam.frameId) * 0.016);
  }
  mat.emission = hasExplicitLabPbrEmission(specular.a)
    ? visibleEmissionRadiance(mat.emission * EMISSIVE_SURFACE_INTENSITY)
    : vec3(0.0);

  // Store hit data
  payload.hitData = vec4(worldPos, gl_RayTmaxEXT);
  payload.geometryNormal = fragInfo.normal;
  payload.material = mat;

  // Handle shadow transmission for transparent blocks using the same
  // water/ice/glass controls as the main raygen shading path.
  if (payload.inside_block) {
    float distDelta = clamp(gl_RayTmaxEXT - payload.prev_distance, 0.0, 10.0);
    payload.shadowTransmission *= refractiveShadowTransmission(blockId, albedo.rgb, albedo.a, distDelta);
  }
}
