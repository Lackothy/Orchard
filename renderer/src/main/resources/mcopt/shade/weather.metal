// Copyright 2026 mcopt-shade contributors
//
// Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the
// License. You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0. Unless required by applicable
// law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES
// OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and
// limitations under the License.
//
// Rain and snow (minecraft:pipeline/weather), applied to the result of vanilla's generated fragment shader (ShadeMsl.weather
// patches the call in): in the overworld the streaks become grey-white, about as bright as the sky behind them (plus a
// little sun), through the same exposure and tone curve, and more transparent; vanilla's look elsewhere.
// M3 weather (SHADE_WEATHER): the streaks are lit from the sky in their own view direction (never darker than the sky behind
// them: drops scatter it toward the eye), get the sun's forward glow, torch light from vanilla's lightmap, the lightning
// flash, and fade a little in light rain; the vertex stage (shadeRainSlant, ShadeMsl.weatherVertex) leans them with the wind.
#ifndef MCOPT_SHADE_WEATHER
#define MCOPT_SHADE_WEATHER
static float4 shadeWeather(float4 c, constant ShadeFrame &f, constant ShadeLook &L, constant ShadeGpu &g) {
	if (f.flags.x != SHADE_DIM_OVERWORLD || f.fogColor.a > 0.5) return float4(shadeVanillaOut(c.rgb, L), c.a);
	float l = shadeLuma(c.rgb);
	float3 light = f.ambientSky.rgb * 1.15 + f.sunColor.rgb * 0.35;
	float3 lit = shadeOut(light * (0.8 + 0.35 * l) * float3(0.97, 0.99, 1.0), f, shadeExposure(f, g));
	return float4(lit, c.a * 0.55);
}

#ifdef SHADE_WEATHER
#ifndef SHADE_WX_BUDGET
#ifdef SHADE_WX_LITE
#define SHADE_WX_BUDGET(f, bit) true
#else
#define SHADE_WX_BUDGET(f, bit) shadeDev(f, bit)
#endif
#endif

static float4 shadeWeather3(float4 c, float4 pixel, constant ShadeFrame &f, constant ShadeLook &L, constant ShadeGpu &g, texture2d<half> lut,
                            sampler lutS) {
	if (f.flags.x != SHADE_DIM_OVERWORLD || f.fogColor.a > 0.5) return float4(shadeVanillaOut(c.rgb, L), c.a);
	if (shadeDev(f, SHADE_DEV_WX_PLAIN_RAIN)) return shadeWeather(c, f, L, g);
	if (c.a < 0.004) return float4(0.0);
	float2 ndc = float2(pixel.x * f.screen.z * 2.0 - 1.0, pixel.y * f.screen.w * 2.0 - 1.0);
	float3 v = normalize(shadeViewRay(ndc, f.invViewProj));
	float dist = f.proj[3][2] / max(pixel.z + f.proj[2][2], 1e-7);
	float l = shadeLuma(c.rgb);
	float3 skyMean = f.ambientSky.rgb;
	float3 sky = SHADE_WX_BUDGET(f, SHADE_DEV_WX_LITE_RAIN) ? skyMean * 1.12 : shadeSkyLookup(float3(v.x, max(v.y, 0.02), v.z), lut, lutS, f);
	float mu = dot(v, f.lightDirWorld.xyz);
	float3 sun = f.sunColor.rgb * (0.03 + 0.45 * shadeHG(mu, 0.6));
	float3 light = max(sky, skyMean) * 1.02 + sun + float3(0.78, 0.86, 1.0) * (L.flash.w * 0.6);
	float snowK = saturate(L.wind.w);
	float torch = max(l - 0.75, 0.0) * 2.0 * (1.0 - snowK);
	light += f.ambientBlock.rgb * torch * 0.6;
	float3 lit = shadeOut(light * (0.9 + 0.2 * l) * float3(0.97, 0.99, 1.0), f, shadeExposure(f, g));
	float a = c.a * mix(0.28 + 0.14 * saturate(L.wet.w), 0.55, snowK) * smoothstep(1.2, 4.5, dist);
	return float4(lit, a);
}
#endif

static inline float3 shadeRainSlant(float3 p, constant ShadeLook &L) {
	float k = L.wind.z;
	if (k <= 0.0) return p;
	return float3(p.x - L.wind.x * k * p.y, p.y, p.z - L.wind.y * k * p.y);
}
#endif
