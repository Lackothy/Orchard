// Copyright 2026 mcopt-shade contributors
//
// Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the
// License. You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0. Unless required by applicable
// law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES
// OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and
// limitations under the License.
//
// Catmull-Rom history sampling (taaCatmullRom) after Matt Pettineo (MJP), "Bicubic texture filtering with the Catmull-Rom
// spline in 9 / 5 taps" (https://gist.github.com/TheRealMJP/c83b8c0f46b63f3a88a5986f4fa982b1), MIT License; the 5-tap
// variant (corners dropped and renormalised) follows Jimenez, "Filmic SMAA", SIGGRAPH 2016. Everything else here is
// original, built from public techniques: Karis 2014 (YCoCg neighbourhood rectification, luma-weighted resolve), Salvi 2016
// (variance clipping), Yang et al. 2020 (confidence-weighted temporal upsampling).
//
// mcopt native shading: temporal anti-aliasing and upscaling (TAAU, shader bible E18), compiled natively by mctaa.m.
//
// taau_fs runs once per OUTPUT pixel (native resolution) as a fragment pass into two compressible targets:
//   color(0) RGBA16Float: the resolved colour (rgb, in the input's encoding) and the pixel's history age in frames (a);
//   color(1) R16Float:    linear view depth (clip w, blocks) of the nearest input sample, for next frame's disocclusion test.
// Both are ping-ponged by mctaa.m: next frame reads them as `hist` / `histDepth`.
// taa_output_fs then copies the resolve into the game's main target with a contrast-limited sharpen.
//
// Conventions (same as every mcopt target): rows are in GL order (texture row 0 = bottom of the picture), so a pixel
// centre (x + 0.5, row + 0.5) of a W x H target is NDC ((x + 0.5) / W * 2 - 1, (row + 0.5) / H * 2 - 1) and Metal
// texture coordinates need no flip. Depth is reversed Z in [0, 1] (0 = far plane / sky).
//
// The INPUT is the jittered world render: a viewport sub-rect (0, 0, input.xy) of textures that may be larger (dynamic
// resolution renders into fixed-size targets). The scene is drawn shifted by `jitter.xy` input pixels (the projection's
// sub-pixel offset, Halton 2,3), so the input pixel centred at c + 0.5 shows the unjittered scene point c + 0.5 - jitter,
// and output pixel centre q maps to unjittered input position q * ratio.
#include <metal_stdlib>
using namespace metal;

struct TaaParams {
	float4x4 invCur;
	float4x4 prevVP;
	float4 curRowW;
	float4 input;
	float4 output;
	float4 jitter;
	float4 blend;
	float4 misc;
	float4 sharpen;
	float4x4 reproj;
};

#define TAA_MOVERS 32
struct TaaMovers {
	uint4 count;
	float4 box[TAA_MOVERS * 3];
};

static inline float3 taaMoverDelta(float3 world, constant TaaMovers &mv) {
	uint n = min(mv.count.x, uint(TAA_MOVERS));
	for (uint i = 0; i < n; i++) {
		float3 lo = mv.box[i * 3].xyz, hi = mv.box[i * 3 + 1].xyz;
		if (all(world >= lo) && all(world <= hi)) return mv.box[i * 3 + 2].xyz;
	}
	return float3(0.0);
}

static inline bool taaBad(half4 v) { return any((as_type<ushort4>(v) & ushort4(0x7c00)) == ushort4(0x7c00)); }
static inline bool taaBad(float3 v) { return any((as_type<uint3>(v) & uint3(0x7f800000)) == uint3(0x7f800000)); }

struct TaaVOut { float4 position [[position]]; };

vertex TaaVOut taa_vs(uint id [[vertex_id]]) {
	float2 p = float2((id << 1) & 2, id & 2);
	TaaVOut o;
	o.position = float4(p * 2.0 - 1.0, 0.0, 1.0);
	return o;
}

constant bool TAA_HDR [[function_constant(0)]];
constant bool taaHdr = is_function_constant_defined(TAA_HDR) && TAA_HDR;
constant bool TAA_MOVERS_ON [[function_constant(1)]];
constant bool taaMoversOn = !is_function_constant_defined(TAA_MOVERS_ON) || TAA_MOVERS_ON;
static inline half3 taaCompress(half3 c) {
	return taaHdr ? c / (1.0h + dot(c, half3(0.2126h, 0.7152h, 0.0722h))) : c;
}
static inline float3 taaExpand(float3 c) {
	return taaHdr ? c / max(1.0 - dot(c, float3(0.2126, 0.7152, 0.0722)), 1e-4) : c;
}

static inline half3 taaTap(half3 c, float rescale, bool comp) {
	if (!comp) return min(c, half3(16384.0h));
	float3 x = float3(c) * rescale;
	return half3(x / (1.0 + dot(x, float3(0.2126, 0.7152, 0.0722))));
}
static inline half3 taaCatmullRom(texture2d<half> tex, sampler s, float2 uv, float2 size, float2 invSize, thread half &centreA,
                                  float rescale = 1.0, bool comp = false) {
	float2 pos = uv * size;
	float2 t1 = floor(pos - 0.5) + 0.5;
	half2 f = half2(pos - t1);
	half2 w0 = f * (-0.5h + f * (1.0h - 0.5h * f));
	half2 w1 = 1.0h + f * f * (-2.5h + 1.5h * f);
	half2 w2 = f * (0.5h + f * (2.0h - 1.5h * f));
	half2 w3 = f * f * (-0.5h + 0.5h * f);
	half2 w12 = w1 + w2;
	float2 t0 = (t1 - 1.0) * invSize, t3 = (t1 + 2.0) * invSize, t12 = (t1 + float2(w2 / w12)) * invSize;
	half4 cc = tex.sample(s, t12);
	centreA = cc.a;
	half3 c = taaTap(tex.sample(s, float2(t12.x, t0.y)).rgb, rescale, comp) * (w12.x * w0.y)
		+ taaTap(tex.sample(s, float2(t0.x, t12.y)).rgb, rescale, comp) * (w0.x * w12.y)
		+ taaTap(cc.rgb, rescale, comp) * (w12.x * w12.y)
		+ taaTap(tex.sample(s, float2(t3.x, t12.y)).rgb, rescale, comp) * (w3.x * w12.y)
		+ taaTap(tex.sample(s, float2(t12.x, t3.y)).rgb, rescale, comp) * (w12.x * w3.y);
	half wsum = w12.x * w0.y + w0.x * w12.y + w12.x * w12.y + w3.x * w12.y + w12.x * w3.y;
	return max(c / wsum, 0.0h);
}

struct TaaOut {
	half4 color [[color(0)]];
	half depth [[color(1)]];
};

static inline TaaOut taauResolve(float2 q, constant TaaParams &p, constant float4 &exposure, constant TaaMovers &movers, texture2d<half> cur,
                                 depth2d<float> curDepth, texture2d<half> hist, texture2d<half> histDepth, texture2d<half> reactive, thread half3 &debug)
{
	half rescale = exposure.x > 0.0 && exposure.z > 0.0 ? half(clamp(exposure.x / exposure.z, 0.25, 4.0)) : 1.0h;
	constexpr sampler linear(filter::linear, address::clamp_to_edge);
	float2 pin = q * p.jitter.zw + p.jitter.xy;
	int2 last = int2(p.input.xy) - 1;
	int2 base = clamp(int2(floor(pin)), int2(1), last - 1);

	half2 off = half2(float2(base) + 0.5 - pin);
	half3 ox = half3(off.x - 1.0h, off.x, off.x + 1.0h), oy = half3(off.y - 1.0h, off.y, off.y + 1.0h);
	half3 wx = exp(-2.29h * ox * ox), wy = exp(-2.29h * oy * oy);
	half3 sum = 0.0h, m1 = 0.0h, m2 = 0.0h;
	constexpr sampler texel(coord::pixel, filter::nearest, address::clamp_to_edge);
	float2 centre = float2(base) + 0.5;
	for (int dy = 0; dy < 3; dy++) {
		for (int dx = 0; dx < 3; dx++) {
			half3 c = taaCompress(clamp(cur.sample(texel, centre, int2(dx - 1, dy - 1)).rgb, 0.0h, 60000.0h));
			sum += c * (wx[dx] * wy[dy]);
			m1 += c;
			m2 += c * c;
		}
	}
	constexpr sampler point(filter::nearest, address::clamp_to_edge);
	float4 z4 = curDepth.gather(point, pin / float2(curDepth.get_width(), curDepth.get_height()));
	float zmax = max(max(z4.x, z4.y), max(z4.z, z4.w));
	half wsum = (wx.x + wx.y + wx.z) * (wy.x + wy.y + wy.z);
	half wnear = max3(wx.x, wx.y, wx.z) * max3(wy.x, wy.y, wy.z);
	half3 current = sum / wsum;
	half3 mean = m1 * (1.0h / 9.0h);
	half3 sigma = sqrt(max(m2 * (1.0h / 9.0h) - mean * mean, 0.0h));

	float2 ndc = q * p.output.zw * 2.0 - 1.0;
	float4 v4 = float4(ndc, zmax, 1.0);
	float iw = 1.0 / dot(float4(p.invCur[0].w, p.invCur[1].w, p.invCur[2].w, p.invCur[3].w), v4);
	float4 prev = (p.reproj * v4) * iw;
	float viewDepth = iw;
	float3 moved = float3(0.0);
	if (taaMoversOn && movers.count.x > 0u && zmax > 0.0) {
		moved = taaMoverDelta((p.invCur * v4).xyz * iw, movers);
		prev -= p.prevVP * float4(moved, 0.0);
	}
	float2 uvPrev = prev.xy / prev.w * 0.5 + 0.5;

	TaaOut o;
	bool skyNow = zmax <= 0.0;
	o.depth = skyNow ? half(65000.0) : half(clamp(viewDepth, 0.0, 60000.0));
	bool valid = p.blend.w < 0.5 && prev.w > 0.0 && all(uvPrev > 0.0) && all(uvPrev < 1.0);
	half age = 0.0h;
	half3 history = current;
	half disocclusion = 0.0h;
	if (valid) {
		uint2 hp = uint2(min(uvPrev * p.output.xy, p.output.xy - 1.0));
		float stored = float(histDepth.read(hp).r);
		bool skyThen = stored > 60000.0;
		float tolerance = 0.03 * prev.w + 0.1;
		disocclusion = skyNow || skyThen ? half(skyNow != skyThen) : half(saturate((abs(stored - prev.w) - tolerance) / tolerance));
#ifndef TAA_LINEAR_HISTORY
		history = taaCatmullRom(hist, linear, uvPrev, p.output.xy, p.output.zw, age, float(rescale), taaHdr);
#else
		history = taaCompress(min(taaCatmullRom(hist, linear, uvPrev, p.output.xy, p.output.zw, age) * rescale, half3(60000.0h)));
#endif
	}
	half3 extent = half(p.blend.y) * sigma + 0.002h;
	half3 v = history - mean;
	half3 a = abs(v) / extent;
	half outside = max3(a.x, a.y, a.z);
	half3 clipped = outside > 1.0h ? mean + v / outside : history;
	age *= 1.0h - disocclusion;
	half alpha = age < 0.5h ? 1.0h : max(1.0h / (age + 1.0h), half(p.blend.x)) * mix(0.35h, 1.0h, wnear);
	alpha = max(alpha, saturate((outside - 1.0h) * 0.25h) * 0.5h);
	float motionPx = length(uvPrev * p.output.xy - q);
	alpha = max(alpha, half(p.sharpen.y) * half(saturate(motionPx * 2.0)));
	if (p.misc.z > 0.0) alpha = max(alpha, reactive.read(uint2(clamp(base, int2(0), int2(reactive.get_width(), reactive.get_height()) - 1))).r * half(p.misc.z));
	half3 resolved = mix(clipped, current, saturate(alpha));
	float3 rgb = taaExpand(float3(resolved));
	half newAge = min(age + 1.0h, half(p.blend.z));
	o.color = half4(half3(max(rgb, 0.0)), newAge);
	if (taaBad(o.color) || taaBad(rgb)) {
		o.color = half4(half3(clamp(taaExpand(float3(current)), 0.0, 60000.0)), 1.0h);
		if (taaBad(o.color)) o.color = half4(0.0h, 0.0h, 0.0h, 1.0h);
	}
	int view = int(p.misc.w);
	if (view == 5) debug = half3(saturate(abs(moved) * 4.0));
	else if (view == 1) debug = half3(mix(float3(1.0, 0.1, 0.0), float3(0.1, 1.0, 0.2), saturate(float(newAge) / p.blend.z)));
	else if (view == 2) debug = half3(saturate(float(outside) - 1.0), float(disocclusion), valid ? 0.0 : 1.0);
	else if (view == 3) debug = half3(taaExpand(float3(current)));
	else if (view == 4) debug = half3(taaExpand(float3(mix(history, current, 0.5h))));
	else debug = o.color.rgb;
	return o;
}

fragment TaaOut taau_fs(TaaVOut in [[stage_in]], constant TaaParams &p [[buffer(0)]], constant float4 &exposure [[buffer(1)]],
                        constant TaaMovers &movers [[buffer(2)]],
                        texture2d<half> cur [[texture(0)]], depth2d<float> curDepth [[texture(1)]],
                        texture2d<half> hist [[texture(2)]], texture2d<half> histDepth [[texture(3)]],
                        texture2d<half> reactive [[texture(4)]])
{
	half3 debug;
	return taauResolve(in.position.xy, p, exposure, movers, cur, curDepth, hist, histDepth, reactive, debug);
}

struct TaaDebugOut {
	half4 color [[color(0)]];
	half depth [[color(1)]];
	half4 view [[color(2)]];
};

fragment TaaDebugOut taau_debug_fs(TaaVOut in [[stage_in]], constant TaaParams &p [[buffer(0)]], constant float4 &exposure [[buffer(1)]],
                                   constant TaaMovers &movers [[buffer(2)]],
                                   texture2d<half> cur [[texture(0)]], depth2d<float> curDepth [[texture(1)]],
                                   texture2d<half> hist [[texture(2)]], texture2d<half> histDepth [[texture(3)]],
                                   texture2d<half> reactive [[texture(4)]])
{
	half3 debug;
	TaaOut r = taauResolve(in.position.xy, p, exposure, movers, cur, curDepth, hist, histDepth, reactive, debug);
	TaaDebugOut o;
	o.color = r.color;
	o.depth = r.depth;
	o.view = half4(debug, 1.0h);
	return o;
}

fragment half4 taa_output_fs(TaaVOut in [[stage_in]], constant TaaParams &p [[buffer(0)]], texture2d<half> resolved [[texture(0)]])
{
	int2 c = int2(in.position.xy);
	int2 last = int2(p.output.xy) - 1;
	half3 m = resolved.read(uint2(c)).rgb;
	half amount = half(p.sharpen.x);
	if (amount <= 0.0h) return half4(m, 1.0h);
	half3 n = resolved.read(uint2(clamp(c + int2(0, 1), int2(0), last))).rgb;
	half3 s = resolved.read(uint2(clamp(c - int2(0, 1), int2(0), last))).rgb;
	half3 e = resolved.read(uint2(clamp(c + int2(1, 0), int2(0), last))).rgb;
	half3 w = resolved.read(uint2(clamp(c - int2(1, 0), int2(0), last))).rgb;
	half3 lo = min(m, min(min(n, s), min(e, w)));
	half3 hi = max(m, max(max(n, s), max(e, w)));
	half range = dot(hi - lo, half3(0.2126h, 0.7152h, 0.0722h)) / max(dot(hi, half3(0.2126h, 0.7152h, 0.0722h)), 0.05h);
	half k = amount * 2.0h * (1.0h - smoothstep(0.15h, 0.9h, range));
	half3 o = clamp(m + (m - (n + s + e + w) * 0.25h) * k, lo, hi);
	return half4(o, 1.0h);
}
