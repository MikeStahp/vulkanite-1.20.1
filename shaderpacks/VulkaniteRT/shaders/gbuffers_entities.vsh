#version 460

// Let Iris bind its canonical vertex attribute names. Hard-coded locations do
// not match every Minecraft/Iris vertex format.
in vec3 vaPosition;
in vec2 vaUV0;
in vec4 vaColor;
in ivec2 vaUV2;
in vec3 vaNormal;

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
    float normalLen2 = dot(vaNormal, vaNormal);
    normal = normalLen2 > 0.000001
        ? vaNormal * inversesqrt(normalLen2)
        : vec3(0.0, 1.0, 0.0);
    
    // Lightmap UV from Iris/Minecraft
    lightmapCoord = vec2(vaUV2) / 240.0;
    
    vec3 pos = vaPosition + chunkOffset; 
    worldPos = pos;
    
    gl_Position = projectionMatrix * modelViewMatrix * vec4(pos, 1.0);
}
