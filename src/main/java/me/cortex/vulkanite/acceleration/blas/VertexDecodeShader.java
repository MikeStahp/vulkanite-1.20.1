package me.cortex.vulkanite.acceleration.blas;

/**
 * Contains the GPU vertex decode shader source for BLAS construction.
 * This compute shader transforms Sodium's vertex format into float positions
 * for acceleration structure building.
 */
public final class VertexDecodeShader {

    public static final String SOURCE = """
            #version 460
            #extension GL_EXT_buffer_reference : require
            #extension GL_EXT_shader_8bit_storage : require
            #extension GL_EXT_shader_explicit_arithmetic_types : require
            #extension GL_EXT_shader_16bit_storage : require

            layout (local_size_x = 256, local_size_y = 1, local_size_z = 1) in;

            struct InputVertex {
                u16vec4 position;
                u8vec4 color;
                u16vec2 blockTexture;
                u16vec2 lightTexture;
                u16vec2 midTexCoord;
                i8vec4 tangent;
                i8vec3 normal;
                int8_t padA__;
                i16vec2 blockId;
                i8vec3 midBlock;
                int8_t padB__;
            };

            layout(buffer_reference, std430) buffer InputVertices {
                InputVertex vertices[];
            };

            layout(buffer_reference, std430) buffer OutputVertices {
                float vertices[];
            };

            layout(push_constant) uniform PushConstants {
                uint64_t nVertices;
                uint64_t inAddr;
                uint64_t outAddr;
            };

            void main() {
                uint32_t idx = gl_GlobalInvocationID.x;
                uint32_t gridSize = gl_NumWorkGroups.x * gl_WorkGroupSize.x;
                InputVertices inputs = InputVertices(inAddr);
                OutputVertices outputs = OutputVertices(outAddr);
                for (idx; idx < uint32_t(nVertices); idx += gridSize) {
                    vec3 position = vec3(inputs.vertices[idx].position.xyz) * (32.0 / 65536.0) - 8.0;
                    outputs.vertices[idx * 3 + 0] = position.x;
                    outputs.vertices[idx * 3 + 1] = position.y;
                    outputs.vertices[idx * 3 + 2] = position.z;
                }
            }
            """;

    private VertexDecodeShader() {
    }
}
