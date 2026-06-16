#ifndef DATA_GLSL
#define DATA_GLSL 1

#extension GL_EXT_shader_explicit_arithmetic_types_int16 : enable
#extension GL_EXT_shader_explicit_arithmetic_types_int8 : enable

struct Vertex {
    u16vec4 position;
    u8vec4 color;
    u16vec2 block_texture;
    u16vec2 light_texture;
    u16vec2 mid_tex_coord;
    i8vec4 tangent;
    i8vec3 normal;
    uint8_t padA__;
    i16vec2 block_id;
    i8vec3 mid_block;
    uint8_t padB__;
};

struct Quad {
    Vertex vertices[4];
};

#endif // DATA_GLSL
