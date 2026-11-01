// Copyright 2026 mcopt-shade contributors
//
// Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the
// License. You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0. Unless required by applicable
// law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES
// OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and
// limitations under the License.
//
// M2: the art layer and the GPU-written state every shaded pipeline (and every pipeline drawn into the HDR world target)
// can read, plus the display transform ("Shade Filmic") and its inverse. Self-contained (only metal_stdlib): it is
// prepended to shade_common.metal, and on its own to the game's pipelines when they draw into the HDR target (their
// display-referred colours are converted to scene-linear there, see shadeLdrToHdr).
//
//   buffer(17)  constant ShadeLook&   CPU-written per frame (mcopt.metal.shade.ShadeLook packs this exact layout, 1024 B)
//   buffer(18)  ShadeGpu              GPU-written (auto-exposure); read by the next frame
//
// HDR CONVENTION (mcopt.shade.hdr=true): the world renders into an RGBA16Float target holding PRE-EXPOSED linear Rec.709
// radiance (scene radiance x ShadeGpu.exposure.x). The final pass (shade_post.metal) applies bloom, Purkinje, vignette and
// shadeDisplay(), writing sRGB into the game's RGBA8 main target before the hand and the GUI.
#ifndef MCOPT_SHADE_LOOK
#define MCOPT_SHADE_LOOK
#include <metal_stdlib>
using namespace metal;

struct ShadeLook {
	float4x4 viewProjNoJitter;
	float4x4 prevViewProjNoJitter;
	float4x4 celestial;
	float4x4 cloudShadowVP;
	float4 sunGeom;
	float4 sunArt;
	float4 ambientArt;
	float4 bounce;
	float4 grade0;
	float4 grade1;
	float4 grade2;
	float4 grade3;
	float4 tonemap;
	float4 output;
	float4 bloom;
	float4 fogR;
	float4 fogM;
	float4 fogMisc;
	float4 vl;
	float4 wet;
	float4 wind;
	float4 clouds0;
	float4 clouds1;
	float4 waterShadow;
	float4 water;
	float4 waterAbs;
	float4 tintRect;
	float4 eye;
	float4 skyGoldH;
	float4 skyGoldZ;
	float4 night;
	float4 ssr;
	float4 clock2;
	float4 renderScale;
	float4 exposureCfg;
	float4 dimTint;
	float4 flash;
	float4 dt;
	int4 tier;
	float4 gradeIn[3];
	float4 gradeOut[3];
	float4 gradeInInv[3];
	float4 gradeOutInv[3];
	float4 exposureRef;
	float4 user0;
	float4 user1;
	float4 user2;
	float4 user3;
};

#define SHADE_FEATURE_HDR 1
#define SHADE_FEATURE_BLOOM 2
#define SHADE_FEATURE_AUTO_EXPOSURE 4
#define SHADE_FEATURE_PCSS 8
#define SHADE_FEATURE_STARS 16
#define SHADE_FEATURE_HAZE 32
#define SHADE_FEATURE_LIGHT_V2 64
#define SHADE_FEATURE_CLOUDS 4096

struct ShadeGpu {
	float4 sh[9];
	float4 sunAtCamera;
	float4 moonAtCamera;
	float4 exposure;
	float4 scene;
	uint histogram[64];
	uint xdrHot;
};

struct ShadeResources {
	texture2d<half> milkyWay;
	texture2d<half> prevScene;
	texture2d<half> scene;
	texture2d<half> bloom;
	texture2d<half> weatherNoise;
	texture2d<half> prevDepth;
	texture2d<half> shadowTint;
	texture2d<float> pbrNormal;
	texture2d<float> pbrSpecular;
	texture2d<half> reserved9;
	texture2d<half> reserved10;
	texture2d<half> aurora;
	texture2d<half> cloudShadow;
	texture2d<half> cloudDome;
	texture2d<half> cloudScreen;
	texture2d<half> reserved15;
};

static inline float shadeAirSky(constant ShadeLook &L) { return L.clock2.z > 0.5 ? L.clock2.y : 1.0; }
static inline bool shadeAirSmooth(constant ShadeLook &L) { return L.clock2.z > 0.5; }
static inline float shadeAirSun(constant ShadeLook &L) { return L.clock2.w; }

static inline float3 shadeRows(const constant float4 *m, float3 c) {
	return float3(dot(m[0].xyz, c), dot(m[1].xyz, c), dot(m[2].xyz, c));
}

static inline float3 shadeLottes(float3 x, float4 t) {
	float3 xa = pow(max(x, 1e-7), float3(t.x));
	float3 xad = pow(max(x, 1e-7), float3(t.x * t.y));
	return xa / (xad * t.z + t.w);
}

static inline float3 shadeLottesInverse(float3 y, float4 t) {
	float a = t.x, d = t.y, b = t.z, beta = t.w;
	y = clamp(y, 0.0, 0.95 / (b * d));
	float3 u = (y * b * (1.0 - d) + y * beta) / (1.0 - y * b * d);
	for (int i = 0; i < 3; i++) {
		float3 ud = pow(max(u, 1e-9), float3(d));
		float3 g = u - y * (b * ud + beta);
		float3 dg = 1.0 - y * b * d * ud / max(u, 1e-9);
		u = max(u - g / max(dg, 0.05), 0.0);
	}
	return pow(u, float3(1.0 / a));
}

static inline float3 shadeDisplay(float3 x, constant ShadeLook &L) {
	float3 c = max(shadeRows(L.gradeIn, max(x, 0.0)), 0.0);
	c = min(shadeLottes(c, L.tonemap), L.output.x);
	float3 y = max(shadeRows(L.gradeOut, c), 0.0);
	return max(y * L.grade3.rgb + L.grade2.rgb, 0.0);
}

static inline float3 shadeSrgbToLinearExact(float3 c) {
	return select(pow((c + 0.055) / 1.055, 2.4), c / 12.92, c <= 0.04045);
}
static inline float3 shadeLinearToSrgbExact(float3 c) {
	return select(1.055 * pow(max(c, 0.0), 1.0 / 2.4) - 0.055, c * 12.92, c <= 0.0031308);
}

static inline float3 shadeLdrToHdr(float3 srgb, constant ShadeLook &L) {
	float3 y = shadeSrgbToLinearExact(saturate(srgb));
	y = (y - L.grade2.rgb) / max(L.grade3.rgb, 1e-3);
	y = clamp(shadeRows(L.gradeOutInv, max(y, 0.0)), 0.0, L.output.w);
	float3 c = shadeLottesInverse(y, L.tonemap);
	return max(shadeRows(L.gradeInInv, c), 0.0);
}
static inline half4 shadeLdrToHdr(half4 c, constant ShadeLook &L) { return half4(half3(min(shadeLdrToHdr(float3(c.rgb), L), 60000.0)), c.a); }
static inline float4 shadeLdrToHdr(float4 c, constant ShadeLook &L) { return float4(shadeLdrToHdr(c.rgb, L), c.a); }

#endif
