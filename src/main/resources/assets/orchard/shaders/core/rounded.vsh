#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
// Position relative to the shape's centre, in the shape's own (pre-transform) units.
layout(location = 2) in vec2 UV0;
// Half width and half height, in 1/16 units.
layout(location = 3) in ivec2 UV1;
// x: corner radius; y: >0 shadow blur, <0 stroke width, 0 plain fill. 1/16 units.
layout(location = 4) in ivec2 UV2;

layout(location = 0) out vec4 vertexColor;
layout(location = 1) out vec2 localPos;
layout(location = 2) flat out vec2 halfSize;
layout(location = 3) flat out vec2 params;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    vertexColor = Color;
    localPos = UV0;
    halfSize = vec2(UV1) / 16.0;
    params = vec2(UV2) / 16.0;
}
