#version 460

layout(location = 0) in vec3 vaPosition;
layout(location = 1) in vec2 vaUV0;
layout(location = 2) in vec4 vaColor;
layout(location = 3) in ivec2 vaUV2;
layout(location = 4) in vec3 vaNormal;

uniform mat4 modelViewMatrix;
uniform mat4 projectionMatrix;
uniform vec3 chunkOffset;

out vec2 texCoord;
out vec4 vertexColor;
out vec3 normal;
out vec3 worldPos;
out vec2 lightmapCoord;

void main() {
    texCoord = vaUV0;
    vertexColor = vaColor;
    normal = vaNormal;
    
    // Lightmap UV from Iris/Minecraft
    lightmapCoord = vec2(vaUV2) / 240.0;
    
    vec3 pos = vaPosition + chunkOffset; 
    worldPos = pos;
    
    gl_Position = projectionMatrix * modelViewMatrix * vec4(pos, 1.0);
}
