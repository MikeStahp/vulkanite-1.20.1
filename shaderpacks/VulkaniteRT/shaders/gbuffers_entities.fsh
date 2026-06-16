#version 460

uniform sampler2D gtexture;

in vec2 texCoord;
in vec4 vertexColor;
in vec3 normal;
in vec3 worldPos;
in vec2 lightmapCoord;

layout(location = 0) out vec4 colortex1;
layout(location = 1) out vec4 colortex2;
layout(location = 2) out vec4 colortex3;
layout(location = 3) out vec4 colortex4;
layout(location = 4) out vec4 colortex5;

void main() {
    vec4 albedo = texture(gtexture, texCoord) * vertexColor;
    if (albedo.a < 0.1) discard;

    colortex1 = albedo;
    // Material: Specular Albedo (F0) for DLSS RR. Default dielectric F0 is 0.04 (4%). We send F0 as RGB to colortex2.
    colortex2 = vec4(0.04, 0.04, 0.04, 0.55);
    // Signed world-space unit normal in RGB, roughness in alpha.
    colortex3 = vec4(normalize(normal), 0.55);
    colortex4 = vec4(worldPos, 0.0);
    // R = blocklight, G = skylight, B = AO, A = reserved
    colortex5 = vec4(lightmapCoord.x, lightmapCoord.y, 1.0, 0.0);
}
