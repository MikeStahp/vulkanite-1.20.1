#version 460
#extension GL_EXT_ray_tracing : require
#extension GL_EXT_nonuniform_qualifier : require
#extension GL_EXT_shader_explicit_arithmetic_types_int16 : require

#include "/lib/rt/payload.glsl"

layout(location = 0) rayPayloadInEXT Payload payload;

// xyz = object-space face normal, w = uintBitsToFloat(brick-local cell index)
hitAttributeEXT vec4 proceduralHitAttribute;

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

layout(set = 1, binding = 0, std430) readonly buffer ProceduralGeometry {
    uint words[];
} geometryBuffers[];

const uint VOXEL_BRICK_MAGIC = 0x5642524bu;
const uint VOXEL_BRICK_MATERIAL_VERSION = 2u;
const uint INVALID_CELL_MATERIAL = 0xffffu;
const uint INVALID_FACE_MATERIAL = 0xffffffffu;
const uint CELL_REFERENCE_BYTES = 8192u;
const uint CELL_MATERIAL_RECORD_BYTES = 48u;
const uint FACE_MATERIAL_RECORD_BYTES = 32u;
const uint FACE_LAYER_RECORD_BYTES = 48u;

uint geometryIndex() {
    return nonuniformEXT(uint(gl_InstanceCustomIndexEXT) + uint(gl_GeometryIndexEXT));
}

uint payloadWord(uint wordIndex) {
    return geometryBuffers[geometryIndex()].words[wordIndex];
}

uint payloadWordCount() {
    return uint(geometryBuffers[geometryIndex()].words.length());
}

bool tableFits(uint offsetBytes, uint recordCount, uint recordBytes, uint totalBytes) {
    return recordBytes != 0u
        && (offsetBytes & 3u) == 0u
        && offsetBytes <= totalBytes
        && recordCount <= (totalBytes - offsetBytes) / recordBytes;
}

uint faceIndexFromNormal(vec3 normal) {
    if (normal.x > 0.5) {
        return 0u;
    }
    if (normal.x < -0.5) {
        return 1u;
    }
    if (normal.y > 0.5) {
        return 2u;
    }
    if (normal.y < -0.5) {
        return 3u;
    }
    if (normal.z > 0.5) {
        return 4u;
    }
    return 5u;
}

vec2 faceCoordinates(uint faceIndex, vec3 blockLocal) {
    if (faceIndex < 2u) {
        return blockLocal.zy;
    }
    if (faceIndex < 4u) {
        return blockLocal.xz;
    }
    return blockLocal.xy;
}

vec2 readUv(uint wordBase, uint corner) {
    uint base = wordBase + corner * 2u;
    return vec2(
        uintBitsToFloat(payloadWord(base)),
        uintBitsToFloat(payloadWord(base + 1u)));
}

vec2 interpolateUv(uint wordBase, vec2 faceUv) {
    vec2 uv00 = readUv(wordBase, 0u);
    vec2 uv10 = readUv(wordBase, 1u);
    vec2 uv11 = readUv(wordBase, 2u);
    vec2 uv01 = readUv(wordBase, 3u);
    return mix(mix(uv00, uv10, faceUv.x), mix(uv01, uv11, faceUv.x), faceUv.y);
}

vec2 unpackLightUv(uint packed) {
    return vec2(float(packed & 0xffffu), float(packed >> 16u));
}

float unpackSignedByte(uint packed, uint byteIndex) {
    int value = int((packed >> (byteIndex * 8u)) & 0xffu);
    return float(value >= 128 ? value - 256 : value) * 0.0078125;
}

vec4 unpackTerrainTangent(uint packed) {
    return vec4(
        unpackSignedByte(packed, 0u),
        unpackSignedByte(packed, 1u),
        unpackSignedByte(packed, 2u),
        unpackSignedByte(packed, 3u));
}

vec2 interpolateLight(uint faceWordBase, vec2 faceUv) {
    vec2 uv00 = unpackLightUv(payloadWord(faceWordBase + 4u));
    vec2 uv10 = unpackLightUv(payloadWord(faceWordBase + 5u));
    vec2 uv11 = unpackLightUv(payloadWord(faceWordBase + 6u));
    vec2 uv01 = unpackLightUv(payloadWord(faceWordBase + 7u));
    return mix(mix(uv00, uv10, faceUv.x), mix(uv01, uv11, faceUv.x), faceUv.y);
}

vec3 fallbackTangent(vec3 normal) {
    vec3 reference = abs(normal.y) < 0.9 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0);
    return normalize(cross(reference, normal));
}

void main() {
    // The group-6 intersection shader validates these tables before reporting
    // a hit. Repeat the cheap checks here so a stale or mismatched descriptor
    // can never turn into an out-of-bounds material read.
    if (payloadWordCount() < 16u
            || payloadWord(0u) != VOXEL_BRICK_MAGIC
            || payloadWord(1u) != VOXEL_BRICK_MATERIAL_VERSION) {
        return;
    }

    uint totalBytes = payloadWord(15u);
    uint availableBytes = payloadWordCount() * 4u;
    uint cellReferenceOffset = payloadWord(8u);
    uint cellMaterialOffset = payloadWord(9u);
    uint faceMaterialOffset = payloadWord(10u);
    uint faceLayerOffset = payloadWord(11u);
    uint cellMaterialCount = payloadWord(12u);
    uint faceMaterialCount = payloadWord(13u);
    uint faceLayerCount = payloadWord(14u);
    if (totalBytes < 64u || totalBytes > availableBytes
            || (cellReferenceOffset & 15u) != 0u
            || (cellMaterialOffset & 15u) != 0u
            || (faceMaterialOffset & 15u) != 0u
            || (faceLayerOffset & 15u) != 0u
            || !tableFits(cellReferenceOffset, 1u, CELL_REFERENCE_BYTES, totalBytes)
            || !tableFits(cellMaterialOffset, cellMaterialCount,
                CELL_MATERIAL_RECORD_BYTES, totalBytes)
            || !tableFits(faceMaterialOffset, faceMaterialCount,
                FACE_MATERIAL_RECORD_BYTES, totalBytes)
            || !tableFits(faceLayerOffset, faceLayerCount,
                FACE_LAYER_RECORD_BYTES, totalBytes)) {
        return;
    }

    uint brickSize = payloadWord(2u);
    uint primitiveIndex = uint(gl_PrimitiveID);
    uint brickCount = payloadWord(3u);
    uint recordOffsetBytes = payloadWord(6u);
    uint recordStrideBytes = payloadWord(7u);
    uint occupancyWords64 = payloadWord(4u);
    uint expectedOccupancyWords64 = brickSize * brickSize * brickSize / 64u;
    uint minimumRecordBytes = 16u + occupancyWords64 * 8u;
    if ((brickSize != 4u && brickSize != 8u && brickSize != 16u)
            || primitiveIndex >= brickCount
            || occupancyWords64 != expectedOccupancyWords64
            || (recordOffsetBytes & 3u) != 0u
            || (recordStrideBytes & 3u) != 0u
            || recordStrideBytes < minimumRecordBytes
            || !tableFits(recordOffsetBytes, brickCount, recordStrideBytes, totalBytes)) {
        return;
    }

    uint recordWordBase = (recordOffsetBytes + primitiveIndex * recordStrideBytes) >> 2u;
    ivec3 brickOrigin = ivec3(
        int(payloadWord(recordWordBase + 0u)),
        int(payloadWord(recordWordBase + 1u)),
        int(payloadWord(recordWordBase + 2u)));
    uint localCellIndex = floatBitsToUint(proceduralHitAttribute.w);
    uint cellsPerBrick = brickSize * brickSize * brickSize;
    if (localCellIndex >= cellsPerBrick) {
        return;
    }
    uint cellX = localCellIndex % brickSize;
    uint yz = localCellIndex / brickSize;
    uint cellZ = yz % brickSize;
    uint cellY = yz / brickSize;
    ivec3 sectionCell = brickOrigin + ivec3(int(cellX), int(cellY), int(cellZ));
    if (any(lessThan(sectionCell, ivec3(0)))
            || any(greaterThanEqual(sectionCell, ivec3(16)))) {
        return;
    }

    uint sectionCellIndex = uint((sectionCell.y * 16 + sectionCell.z) * 16 + sectionCell.x);
    uint cellReferenceWord = payloadWord((cellReferenceOffset >> 2u) + (sectionCellIndex >> 1u));
    uint cellMaterialIndex = (sectionCellIndex & 1u) == 0u
        ? cellReferenceWord & 0xffffu
        : cellReferenceWord >> 16u;
    if (cellMaterialIndex == INVALID_CELL_MATERIAL || cellMaterialIndex >= cellMaterialCount) {
        return;
    }

    vec3 geometryNormal = normalize(proceduralHitAttribute.xyz);
    uint faceIndex = faceIndexFromNormal(geometryNormal);
    uint cellMaterialWordBase = (cellMaterialOffset
        + cellMaterialIndex * CELL_MATERIAL_RECORD_BYTES) >> 2u;
    uint faceMaterialIndex = payloadWord(cellMaterialWordBase + 4u + faceIndex);
    if (faceMaterialIndex == INVALID_FACE_MATERIAL || faceMaterialIndex >= faceMaterialCount) {
        return;
    }

    uint faceMaterialWordBase = (faceMaterialOffset
        + faceMaterialIndex * FACE_MATERIAL_RECORD_BYTES) >> 2u;
    uint firstLayer = payloadWord(faceMaterialWordBase + 0u);
    uint layerCount = payloadWord(faceMaterialWordBase + 1u);
    if (layerCount == 0u || firstLayer > faceLayerCount
            || layerCount > faceLayerCount - firstLayer) {
        return;
    }

    vec3 objectHitPosition = gl_ObjectRayOriginEXT + gl_HitTEXT * gl_ObjectRayDirectionEXT;
    vec3 worldHitPosition = gl_WorldRayOriginEXT + gl_HitTEXT * gl_WorldRayDirectionEXT;
    vec3 blockLocal = clamp(objectHitPosition - vec3(sectionCell), vec3(0.0), vec3(1.0));
    vec2 faceUv = clamp(faceCoordinates(faceIndex, blockLocal), vec2(0.0), vec2(1.0));

    vec4 shadedAlbedo = vec4(0.0);
    vec4 sampledNormal = vec4(0.5, 0.5, 1.0, 1.0);
    vec4 sampledSpecular = defaultLabPbrSpecular();
    bool firstSample = true;
    for (uint layer = 0u; layer < layerCount; layer++) {
        uint layerWordBase = (faceLayerOffset
            + (firstLayer + layer) * FACE_LAYER_RECORD_BYTES) >> 2u;
        vec2 atlasUv = interpolateUv(layerWordBase, faceUv) * ATLAS_SCALE;
        vec4 tint = unpackUnorm4x8(payloadWord(layerWordBase + 8u));
        vec4 layerAlbedo = texture(blockTex, atlasUv);
        // ray0_0 applies the baked vertex colour to RGB only; texture alpha
        // remains authoritative for material transmission.
        layerAlbedo.rgb *= tint.rgb;
        vec4 layerNormal = texture(blockTexNormal, atlasUv);
        vec4 layerSpecular = texture(blockTexSpecular, atlasUv);
        layerSpecular.a = samplePbrTexel(blockTexSpecular, atlasUv).a;
        layerSpecular = sanitizeLabPbrSpecular(layerSpecular);

        if (firstSample) {
            shadedAlbedo = layerAlbedo;
            sampledNormal = layerNormal;
            sampledSpecular = layerSpecular;
            firstSample = false;
        } else {
            float coverage = clamp(layerAlbedo.a, 0.0, 1.0);
            shadedAlbedo.rgb = mix(shadedAlbedo.rgb, layerAlbedo.rgb, coverage);
            shadedAlbedo.a = coverage + shadedAlbedo.a * (1.0 - coverage);
            sampledNormal = mix(sampledNormal, layerNormal, coverage);
            sampledSpecular = mix(sampledSpecular, layerSpecular, coverage);
        }
    }
    shadedAlbedo.rgb = pow(max(shadedAlbedo.rgb, vec3(0.0)), vec3(2.2));

    // Match fragment_info.glsl's signed-byte / 128 decode exactly rather than
    // using unpackSnorm4x8's asymmetric positive divisor.
    vec4 packedTangent = unpackTerrainTangent(payloadWord(faceMaterialWordBase + 2u));
    vec3 tangent = packedTangent.xyz;
    tangent = dot(tangent, tangent) > 1.0e-8
        ? normalize(tangent)
        : fallbackTangent(geometryNormal);
    float bitangentSign = packedTangent.w >= 0.0 ? 1.0 : -1.0;
    vec3 bitangent = normalize(cross(geometryNormal, tangent)) * bitangentSign;
    mat3 tbn = mat3(tangent, bitangent, geometryNormal);

    Material material = getMaterial(
        shadedAlbedo,
        sampledNormal,
        sampledSpecular,
        tbn,
        geometryNormal);
    int shaderBlockId = int(payloadWord(cellMaterialWordBase + 0u));
    int materialId = int(payloadWord(cellMaterialWordBase + 1u));
    float intrinsicEmission = max(uintBitsToFloat(payloadWord(cellMaterialWordBase + 2u)), 0.0);
    material.block_id = i16vec2(shaderBlockId, materialId);
    material.light_texture = vec3(interpolateLight(faceMaterialWordBase, faceUv), 0.0);

    float blockId = float(shaderBlockId);
    applyBlockMaterialOverrides(
        blockId,
        shadedAlbedo.rgb,
        material.F0,
        material.metallic,
        material.roughness);
    if (isFoliageMaterial(blockId)) {
        material.normal = applyFoliageNormal(
            material.normal, worldHitPosition, float(cam.frameId) * 0.016);
    } else if (isWaterMaterial(blockId)) {
        material.normal = applyWaterNormal(
            material.normal, worldHitPosition, float(cam.frameId) * 0.016);
    } else if (isIceMaterial(blockId)) {
        material.normal = applyIceNormal(
            material.normal, worldHitPosition, float(cam.frameId) * 0.016);
    }

    vec3 labPbrEmission = hasExplicitLabPbrEmission(sampledSpecular.a)
        ? visibleEmissionRadiance(material.emission * EMISSIVE_SURFACE_INTENSITY)
        : vec3(0.0);
    vec3 intrinsicRadiance = intrinsicEmission > 0.0
        ? blockEmissionSurfaceRadiance(
            shadedAlbedo.rgb,
            blockId,
            intrinsicEmission,
            worldHitPosition,
            float(cam.frameId) * 0.016)
        : vec3(0.0);
    material.emission = max(labPbrEmission, intrinsicRadiance);

    payload.hitData = vec4(worldHitPosition, gl_HitTEXT);
    payload.geometryNormal = geometryNormal;
    payload.material = material;
    if (payload.inside_block) {
        float distanceDelta = clamp(gl_HitTEXT - payload.prev_distance, 0.0, 10.0);
        payload.shadowTransmission *= refractiveShadowTransmission(
            blockId,
            shadedAlbedo.rgb,
            shadedAlbedo.a,
            distanceDelta);
    }
}
