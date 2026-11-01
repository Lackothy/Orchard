#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:dynamictransforms.glsl>

layout(location = 0) in vec4 vertexColor;
layout(location = 1) in vec2 localPos;
layout(location = 2) flat in vec2 halfSize;
layout(location = 3) flat in vec2 params;

layout(location = 0) out vec4 fragColor;

// Signed distance to a rounded box centred on the origin: negative inside.
float roundedBox(vec2 p, vec2 size, float radius) {
    vec2 q = abs(p) - size + radius;
    return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - radius;
}

void main() {
    float radius = min(params.x, min(halfSize.x, halfSize.y));
    float d = roundedBox(localPos, halfSize, radius);
    // One framebuffer pixel in local units, so edges stay exactly one pixel soft
    // at any GUI scale and under any transform.
    float aa = max(fwidth(d), 1e-4);

    float coverage;
    if (params.y > 0.0) {
        coverage = 1.0 - smoothstep(-params.y, params.y, d);
    } else if (params.y < 0.0) {
        float outer = clamp(0.5 - d / aa, 0.0, 1.0);
        float inner = clamp(0.5 - (d - params.y) / aa, 0.0, 1.0);
        coverage = outer - inner;
    } else {
        coverage = clamp(0.5 - d / aa, 0.0, 1.0);
    }

    vec4 color = vec4(vertexColor.rgb, vertexColor.a * coverage);
    if (color.a <= 0.0) {
        discard;
    }
    fragColor = color * ColorModulator;
}
