#version 460
#extension GL_EXT_ray_tracing : require

struct ProceduralDebugPayload {
    vec4 worldHitAndDistance;
    vec4 normalAndIds;
    vec4 blockLocalPosition;
};

layout(location = 2) rayPayloadInEXT ProceduralDebugPayload proceduralDebugPayload;

void main() {
    proceduralDebugPayload.worldHitAndDistance = vec4(0.0, 0.0, 0.0, -1.0);
    proceduralDebugPayload.normalAndIds = vec4(0.0);
    proceduralDebugPayload.blockLocalPosition = vec4(0.0);
}
