#version 460
#extension GL_EXT_ray_tracing : require
#extension GL_EXT_nonuniform_qualifier : require

struct ProceduralDebugPayload {
    vec4 worldHitAndDistance;
    vec4 normalAndIds;
    vec4 blockLocalPosition;
};

layout(location = 2) rayPayloadInEXT ProceduralDebugPayload proceduralDebugPayload;
// xyz = object-space face normal, w = uintBitsToFloat(brick-local cell index)
hitAttributeEXT vec4 proceduralHitAttribute;

layout(set = 1, binding = 0, std430) readonly buffer ProceduralGeometry {
    uint words[];
} geometryBuffers[];

uint geometryIndex() {
    return nonuniformEXT(uint(gl_InstanceCustomIndexEXT) + uint(gl_GeometryIndexEXT));
}

void main() {
    uint descriptorIndex = geometryIndex();
    uint primitiveIndex = uint(gl_PrimitiveID);
    uint brickSize = geometryBuffers[descriptorIndex].words[2u];
    uint recordOffsetBytes = geometryBuffers[descriptorIndex].words[6u];
    uint recordStrideBytes = geometryBuffers[descriptorIndex].words[7u];
    uint recordWordBase = (recordOffsetBytes + primitiveIndex * recordStrideBytes) >> 2u;
    ivec3 brickOrigin = ivec3(
        int(geometryBuffers[descriptorIndex].words[recordWordBase + 0u]),
        int(geometryBuffers[descriptorIndex].words[recordWordBase + 1u]),
        int(geometryBuffers[descriptorIndex].words[recordWordBase + 2u]));

    uint packedCell = floatBitsToUint(proceduralHitAttribute.w);
    bool originInsideSolid = (packedCell & 0x80000000u) != 0u;
    uint localCellIndex = packedCell & 0x7fffffffu;
    uint cellX = localCellIndex % brickSize;
    uint yz = localCellIndex / brickSize;
    uint cellZ = yz % brickSize;
    uint cellY = yz / brickSize;
    ivec3 cell = ivec3(int(cellX), int(cellY), int(cellZ));

    vec3 objectHitPosition = gl_ObjectRayOriginEXT + gl_HitTEXT * gl_ObjectRayDirectionEXT;
    vec3 worldHitPosition = gl_WorldRayOriginEXT + gl_HitTEXT * gl_WorldRayDirectionEXT;
    vec3 blockLocal = clamp(
        objectHitPosition - vec3(brickOrigin + cell),
        vec3(0.0),
        vec3(1.0));
    uint packedIds = (primitiveIndex & 0xffffu) | ((localCellIndex & 0xffffu) << 16u);

    // Section instances use translation-only transforms, so object- and
    // world-space face normals are identical.
    proceduralDebugPayload.worldHitAndDistance = vec4(worldHitPosition, gl_HitTEXT);
    proceduralDebugPayload.normalAndIds = vec4(
        normalize(proceduralHitAttribute.xyz),
        uintBitsToFloat(packedIds));
    vec3 brickMinimum = vec3(brickOrigin);
    vec3 brickMaximum = brickMinimum + vec3(float(brickSize));
    bool originInsideBrick = all(greaterThanEqual(gl_ObjectRayOriginEXT, brickMinimum))
        && all(lessThanEqual(gl_ObjectRayOriginEXT, brickMaximum));
    uint metadata = brickSize
        | (originInsideBrick ? 0x100u : 0u)
        | (originInsideSolid ? 0x200u : 0u);
    proceduralDebugPayload.blockLocalPosition = vec4(blockLocal, uintBitsToFloat(metadata));
}
