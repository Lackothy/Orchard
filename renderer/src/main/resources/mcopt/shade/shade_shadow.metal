// Copyright 2026 mcopt-shade contributors. Licensed under the Apache License, Version 2.0 (LICENSE).
//
// Receiver side of the sun shadow map (mcopt.metal.shade.shadow.ShadowPass). Prepend after <metal_stdlib> (and after
// shade_common.metal if used) to any shader that wants sun shadows. Independent of the ShadeFrame layout: pass the
// fields in. The conventions are the contract between the shadow pass and every receiver:
//
//  * Binding: texture(16) = the map, depth2d_array<float> with SHADE_SHADOW_CASCADES slices (0 = near cascade, 1 = far),
//    sampler(12) = the compare sampler made by mcshadow.m. Both bound for the vertex and fragment stages.
//  * Matrices: ShadeFrame.shadowViewProj[i] (= ShadowPass.matrices()) maps CAMERA-RELATIVE world positions (the same
//    origin as Sodium's terrain vertices this frame: world position minus the camera position) to orthographic light
//    clip space: x, y in [-1, 1], z in [0, 1]. Texture coordinates: uv = (0.5 + 0.5 x, 0.5 - 0.5 y).
//  * Depth: REVERSED Z. 1 = nearest to the light, 0 = farthest; the map is cleared to 0 (no caster).
//  * Compare: the sampler's compareFunction is GreaterEqual, and Metal compares (reference OP texel): sample_compare
//    returns 1 (lit) when the receiver's depth >= the stored caster depth, i.e. the receiver is at least as close to the
//    light as the nearest caster. Linear min/mag filter: every tap is a hardware 2x2 PCF. Border: depth 0 (lit).
//  * Bias: casters are already pushed away from the light at raster time (constant + slope-scaled). Receivers add a normal
//    offset of about one texel (shadowTexelWorld[i] blocks, ShadowPass.texelSize) along the surface normal; see below.
#ifndef SHADE_SHADOW_METAL
#define SHADE_SHADOW_METAL

#define SHADE_SHADOW_TEXTURE 16
#define SHADE_SHADOW_SAMPLER 12
#define SHADE_SHADOW_CASCADES 2

static inline float2 shade_shadow_uv(float4 clip)
{
    return float2(0.5 + 0.5 * clip.x, 0.5 - 0.5 * clip.y);
}

static inline float shade_shadow_pcf(depth2d_array<float> map, sampler cmp, uint layer, float2 uv, float ref, float texel)
{
    float h = 0.5 * texel;
    float s = map.sample_compare(cmp, uv + float2(-h, -h), layer, ref);
    s += map.sample_compare(cmp, uv + float2(h, -h), layer, ref);
    s += map.sample_compare(cmp, uv + float2(-h, h), layer, ref);
    s += map.sample_compare(cmp, uv + float2(h, h), layer, ref);
    return s * 0.25;
}

static inline float3 shade_shadow_offset(float3 nrm, float3 light, float nl, float texelWorld)
{
    float tanT = clamp(sqrt(max(1.0 - nl * nl, 0.0)) / max(nl, 0.02), 0.0, 12.0);
    return nrm * (texelWorld * 0.75) + light * (texelWorld * (0.5 + 0.75 * tanT));
}

static inline float shade_shadow_cascade(depth2d_array<float> map, sampler cmp, uint layer, float4x4 vp, float3 posCam, float3 nrm,
                                         float3 light, float nl, float texelWorld, float invRes, thread float &m)
{
    float4 c = vp * float4(posCam + shade_shadow_offset(nrm, light, nl, texelWorld), 1.0);
    m = max(abs(c.x), abs(c.y));
    if (c.z <= 0.0 || c.z >= 1.0) m = 2.0;
    if (m >= 1.0) return 1.0;
    return shade_shadow_pcf(map, cmp, layer, shade_shadow_uv(c), c.z, invRes);
}

static inline float shade_shadow(depth2d_array<float> map, sampler cmp, float3 posCam, float3 nrm, float3 light,
                                 float4x4 vp0, float4x4 vp1, float2 texelWorld, float invRes)
{
    float nl = dot(nrm, light);
    if (nl <= 0.0) return 0.0;
    float m0, m1;
    float near = shade_shadow_cascade(map, cmp, 0u, vp0, posCam, nrm, light, nl, texelWorld.x, invRes, m0);
    if (m0 < 0.85) return near;
    float far = shade_shadow_cascade(map, cmp, 1u, vp1, posCam, nrm, light, nl, texelWorld.y, invRes, m1);
    far = mix(far, 1.0, smoothstep(0.85, 1.0, m1));
    if (m0 >= 1.0) return far;
    return mix(near, far, smoothstep(0.85, 1.0, m0));
}

#endif
