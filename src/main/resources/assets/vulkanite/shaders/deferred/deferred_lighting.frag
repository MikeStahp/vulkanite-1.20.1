#version 450

layout(location = 0) in vec2 inUV;
layout(location = 0) out vec4 outColor;

// G-Buffer samplers (Bindings are placeholders)
layout(set = 0, binding = 0) uniform sampler2D samplerAlbedo;
layout(set = 0, binding = 1) uniform sampler2D samplerNormal;
layout(set = 0, binding = 2) uniform sampler2D samplerPosition;
layout(set = 0, binding = 3) uniform sampler2D samplerSpecular;

void main() {
    // Basic deferred lighting implementation
    vec4 albedo = texture(samplerAlbedo, inUV);
    vec3 normal = texture(samplerNormal, inUV).rgb * 2.0 - 1.0;
    vec3 position = texture(samplerPosition, inUV).rgb;
    float specular = texture(samplerSpecular, inUV).r;

    // Simple directional light (e.g., sun)
    vec3 lightDir = normalize(vec3(0.5, 1.0, 0.2));
    float diff = max(dot(normal, lightDir), 0.0);
    vec3 diffuse = diff * albedo.rgb;

    // Simple ambient
    vec3 ambient = albedo.rgb * 0.1;

    outColor = vec4(ambient + diffuse, 1.0);
}
