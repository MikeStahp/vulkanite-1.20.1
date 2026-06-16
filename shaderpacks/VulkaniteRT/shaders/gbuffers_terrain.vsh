#version 460

// Vertex attributes from Minecraft
layout(location = 0) in vec3 vaPosition;
layout(location = 1) in vec2 vaUV0;
layout(location = 2) in vec4 vaColor;
layout(location = 3) in ivec2 vaUV2;
layout(location = 4) in vec3 vaNormal;
in vec4 mc_Entity;
#ifdef IRIS_FEATURE_BLOCK_EMISSION_ATTRIBUTE
in vec4 at_midBlock;
#endif
// Iris/OptiFine provides tangent as attribute (location 5 in newer versions)
// If not available, we'll compute it in the shader
#ifdef MC_NORMAL_MAP
layout(location = 5) in vec4 vaTangent;
#endif

uniform mat4 modelViewMatrix;
uniform mat4 projectionMatrix;
uniform vec3 chunkOffset;

// Outputs to fragment shader
out vec2 texCoord;
out vec4 vertexColor;
out vec3 normal;
out vec3 worldPos;
out vec2 lightmapCoord;
out mat3 tbnMatrix; // TBN matrix for normal mapping
flat out float blockId;
flat out float intrinsicBlockLight;

void main() {
    texCoord = vaUV0;
    vertexColor = vaColor;
    
    // Transform normal to world space
    // Note: Minecraft uses Z-up, but we need to handle the coordinate system properly
    normal = normalize(vaNormal);

    // Lightmap UV: vaUV2 contains packed light values
    // Minecraft lightmap: each component is 0-15 (4 bits), scaled to 0-240 for UV
    // vaUV2.x = blocklight * 16 (torch light)
    // vaUV2.y = skylight * 16 (sky light)
    // Convert to [0,1] range for proper light values
    // Each component: value/240 gives the UV coordinate for the lightmap texture
    // But for actual light level: (value/16)/15 = value/240
    lightmapCoord = vec2(vaUV2) / 240.0;
    blockId = mc_Entity.x;
#ifdef IRIS_FEATURE_BLOCK_EMISSION_ATTRIBUTE
    intrinsicBlockLight = clamp(at_midBlock.w, 0.0, 15.0);
#else
    intrinsicBlockLight = 0.0;
#endif

    // World position
    vec3 pos = vaPosition + chunkOffset;
    worldPos = pos;

    // Build TBN matrix for normal mapping
    vec3 tangent = vec3(0.0);
    vec3 bitangent = vec3(0.0);
    
#ifdef MC_NORMAL_MAP
    // Use the tangent attribute if available (LabPBR/OptiFine)
    tangent = normalize(vaTangent.xyz);
    float tangentSign = vaTangent.w;
    bitangent = normalize(cross(normal, tangent)) * tangentSign;
#else
    // Compute tangent in the shader if not provided
    // Use a simple method: find a vector perpendicular to normal
    vec3 up = abs(normal.y) < 0.999 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0);
    tangent = normalize(cross(up, normal));
    bitangent = normalize(cross(normal, tangent));
#endif
    
    tbnMatrix = mat3(tangent, bitangent, normal);

    gl_Position = projectionMatrix * modelViewMatrix * vec4(pos, 1.0);
}
