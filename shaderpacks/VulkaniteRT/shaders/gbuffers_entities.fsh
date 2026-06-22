#version 460

#include "/lib/rt/settings.glsl"

uniform sampler2D gtexture;

in vec2 texCoord;
in vec4 vertexColor;
in vec3 normal;
in vec3 worldPos;
in vec2 lightmapCoord;

// Keep this program's render-target contract explicit as well. Iris collects
// these directives from shader source when it creates the shared targets.
const int RGBA16F = 34842; // GL_RGBA16F
const int RGBA32F = 34836; // GL_RGBA32F
const int colortex1Format = RGBA16F;
const int colortex2Format = RGBA16F;
const int colortex3Format = RGBA16F;
const int colortex4Format = RGBA32F;
const int colortex5Format = RGBA16F;

/* RENDERTARGETS: 1,2,3,4,5 */
layout(location = 0) out vec4 colortex1;
layout(location = 1) out vec4 colortex2;
layout(location = 2) out vec4 colortex3;
layout(location = 3) out vec4 colortex4;
layout(location = 4) out vec4 colortex5;

void main() {
    vec4 textureAlbedo = texture(gtexture, texCoord);
#if BAREBONES_GBUFFER_ALBEDO != 0
    vec3 vertexTint = max(vertexColor.rgb, vec3(0.0));
    float tintPeak = max(max(vertexTint.r, vertexTint.g), vertexTint.b);
    vec3 tintChroma = tintPeak > 0.0001 ? vertexTint / tintPeak : vec3(1.0);
    vec4 albedo = vec4(textureAlbedo.rgb * tintChroma, textureAlbedo.a * vertexColor.a);
#else
    vec4 albedo = textureAlbedo * vertexColor;
#endif
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
