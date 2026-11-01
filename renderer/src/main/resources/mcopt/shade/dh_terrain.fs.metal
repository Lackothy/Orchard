// Copyright 2026 mcopt-shade contributors. Licensed under the Apache License, Version 2.0.
//
// FT1: fragment stage for Distant Horizons' LOD terrain, lit by the shade model (written for mcopt; DH's own stage is
// replaced, only its uniform block layout is shared). The LOD gets what Sodium's terrain gets, minus what a LOD has no
// data for: sun / moon N.L and sky / ground ambient by its sky light (shadeLit2), warm block light by its block light, the
// volumetric clouds' shadow, the aerial perspective and haze toward the sky behind (shadeFogAir2, which in this RGBA8
// compile also applies the height haze the final pass gives the world: the final pass skips DH's pixels, not in its
// depth), the game's fog under water / in lava. No terrain shadow lookup (the cascades end long before the LODs start).
//
// Output: DH composites its LODs into the frame's display-referred main colour, which ShadePost's bridge decodes back
// into the HDR world target (shade_bridge_decode: shadeLdrToHdr). So the LOD writes its pre-exposed radiance through the
// same display transform the bridge's encode uses: it comes back as that radiance (8-bit display steps).
// Prepended: "#define MCOPT_SHADE", the shade prelude and the MCOPT_SLOT_* defines of the pipeline's uniforms.

struct DhFrag {
	float clipDistance;
	float noiseIntensity;
	int noiseSteps;
	int noiseDropoff;
	uint dither;
	uint noiseEnabled;
};

struct DhIn {
	float3 p [[user(locn0)]];
	float3 cell [[user(locn1)]];
	half4 color [[user(locn2)]];
	uint pk [[user(locn3), flat]];
};

static inline half3 dhNormal(uint face) {
	switch (face) {
		case 0u: return half3(0.0h, -1.0h, 0.0h);
		case 1u: return half3(0.0h, 1.0h, 0.0h);
		case 2u: return half3(0.0h, 0.0h, -1.0h);
		case 3u: return half3(0.0h, 0.0h, 1.0h);
		case 4u: return half3(-1.0h, 0.0h, 0.0h);
		default: return half3(1.0h, 0.0h, 0.0h);
	}
}

static inline float2 dhTileUv(float3 cell, uint face) {
	float3 q = fract(cell);
	float2 uv = face <= 1u ? q.xz : face <= 3u ? float2(q.x, 1.0 - q.y) : float2(q.z, 1.0 - q.y);
	if (face == 0u) uv.y = 1.0 - uv.y;
	if (face == 2u || face == 5u) uv.x = 1.0 - uv.x;
	return uv;
}

static inline float2 dhFaceCoord(float3 cell, uint face) {
	return face <= 1u ? cell.xz : face <= 3u ? cell.xy : cell.zy;
}

static inline float dhBayer(float2 px) {
	uint2 i = uint2(px) & 3u;
	uint b = ((i.x ^ i.y) & 1u) << 3 | (i.y & 1u) << 2 | ((i.x ^ i.y) & 2u) | (i.y & 2u) >> 1;
	return float(b) / 16.0;
}

fragment float4 main0(DhIn in [[stage_in]], constant DhFrag &u [[buffer(MCOPT_SLOT_fragUniformBlock)]],
                      texture2d<float> atlas [[texture(MCOPT_SLOT_uBlockAtlas)]], sampler atlasSmplr [[sampler(MCOPT_SLOT_uBlockAtlas)]],
                      constant ShadeFrame &sf [[buffer(16)]], constant ShadeLook &sl [[buffer(17)]], constant ShadeGpu &sg [[buffer(18)]],
                      constant ShadeResources &sr [[buffer(19)]], texture2d<half> skyLut [[texture(17)]], sampler skyLutSmplr [[sampler(13)]],
                      texture2d<float> lightMap [[texture(MCOPT_SLOT_uLightMap)]], sampler lightMapSmplr [[sampler(MCOPT_SLOT_uLightMap)]],
                      float4 fragCoord [[position]]) {
	uint face = (in.pk >> 8) & 7u, tile = in.pk >> 11, l = in.pk & 0xFFu;
#ifdef SHADE_DH_SKY_HIGH
	half2 light = half2(half(l & 15u), half(l >> 4)) / 15.0h;
#else
	half2 light = half2(half(l >> 4), half(l & 15u)) / 15.0h;
#endif
	float3 p = in.p;
	float d = length(p);
	float2 fc = dhFaceCoord(in.cell, face);
	float mipScale = (sf.cameraBlock.w > 0.0 ? sf.cameraBlock.w : 1.0) * 16.0;
	float2 tdx = dfdx(fc) * mipScale, tdy = dfdy(fc) * mipScale;
#ifndef SHADE_DH_NOCLIP
	if (u.dither != 0u) {
		if (smoothstep(u.clipDistance, u.clipDistance * 1.5, d) <= dhBayer(fragCoord.xy) + 0.001) discard_fragment();
	} else if (u.clipDistance > 0.0 && d < u.clipDistance) {
		discard_fragment();
	}
#endif
	half3 srgb = in.color.rgb;
	if (tile != 0u) {
		float2 cellOrigin = float2(float(tile & 255u), float(tile >> 8)) * 16.0;
		float2 asz = float2(atlas.get_width(), atlas.get_height());
		float4 t = atlas.sample(atlasSmplr, (cellOrigin + dhTileUv(in.cell, face) * 16.0) / asz, gradient2d(tdx / asz, tdy / asz));
		srgb = mix(srgb, saturate(srgb * half3(t.rgb) * 2.0h), half(t.a));
	}
	if (sf.flags.x > SHADE_DIM_END || (sf.flags.x != SHADE_DIM_OVERWORLD && !shadeDimsLit(sf))) {
		float3 v = float3(srgb) * lightMap.sample(lightMapSmplr, (float2(light) * 15.0 + 0.5) / 16.0, level(0.0)).rgb;
		float fa = saturate((d - sf.fogRange.x) / max(sf.fogRange.y - sf.fogRange.x, 1.0)) * sf.fogColor.a;
		return float4(mix(v, sf.fogColor.rgb, fa), in.color.a);
	}
	half3 n = dhNormal(face);
	half block = light.x, sky = light.y;
	half3 albedo = shadeToLinear(srgb);
	bool overworld = sf.flags.x == SHADE_DIM_OVERWORLD;
	float ex = max(sg.exposure.x, 1e-6);
	float3 radiance;
	half outAlpha = in.color.a;
	if (!overworld) {
		radiance = shadeLit2(albedo, n, half(0.0), block, half(0.0), false, false, sf, sl);
		radiance += shadeLitDimsExtra(albedo, n, half(1.0), false, false, p, sf, sl);
	} else {
		half shadow = sky > 0.3h ? half(shadeCloudShadow(p, sl, sr.cloudShadow)) : half(1.0);
#ifndef SHADE_DH_TRANSLUCENT
		if (sl.wet.x > 0.0 || sl.wet.z > 0.001) {
			half open = smoothstep(0.90h, 0.995h, sky);
			half face = n.y > 0.5h ? 1.0h : n.y < -0.5h ? 0.0h : 0.55h;
			half2 reach = shadeWxReach(p.y + sf.cameraFract.y + sf.cameraBlock.y, sf);
			half snow = n.y > 0.5h && sl.wet.z > 0.001
				? half(smoothstep(1.0 - float(sl.wet.z) * 1.15, 1.15 - float(sl.wet.z) * 1.15, 0.65) * saturate(float(sl.wet.z) * 40.0)) * open * reach.x : 0.0h;
			half om = half(sl.wet.x) * open * face * (1.0h - snow) * reach.y;
			if (shadeWxVegLight(sf)) om *= 1.0h - 0.67h * shadeWxVegHue(albedo);
			if (om > 0.0h) albedo = pow(max(albedo, half3(1e-4h)), half3(1.0h + 0.65h * om)) * (1.0h - 0.08h * om);
			if (snow > 0.0h) albedo = mix(albedo, half3(0.86h, 0.88h, 0.92h), snow);
		}
#endif
#ifdef SHADE_DH_TRANSLUCENT
		if (face == 1u && in.color.a < 0.99h && sl.vl.w > 0.0) {
			float3 u = sl.vl.rgb;
			float k = shadeLuma(float3(albedo)) / max(shadeLuma(u), 1e-3);
			albedo = half3(mix(float3(albedo), min(u * k, float3(1.0)), sl.vl.w));
		}
#endif
		radiance = shadeLit2(albedo, n, sky, block, shadow, false, false, sf, sl);
		if (sl.flash.w > 0.0) {
			half gk = shadeSkyCurve(sky);
			radiance += float3(albedo) * float3(0.78, 0.86, 1.0) * (sl.flash.w * (saturate(dot(float3(n), sl.flash.xyz)) * 0.65 + 0.35) * float(gk * gk));
		}
#ifdef SHADE_DH_TRANSLUCENT
		if (face == 1u && in.color.a < 0.99h && !shadeDev(sf, SHADE_DEV_FLAT_LIGHT)) {
			float3 v = p / max(d, 1e-4);
			const float slope2 = 0.2 * 0.2;
			float cosV = sqrt(v.y * v.y + slope2);
			float3 r = float3(v.xz * (sqrt(max(1.0 - cosV * cosV, 0.0)) / max(length(v.xz), 1e-4)), cosV).xzy;
			float open = float(shadeSkyCurve(sky));
			float m = 1.0 - saturate(cosV);
			float fr = min((0.0204 + 0.9796 * m * m * m * m * m) * (0.35 + 0.65 * open) * sl.user1.x, 1.0);
			float3 scr = float3(0.0);
			float wS = 0.0;
			if (sl.ssr.x > 0.5 && sl.ssr.y > 0.5 && sl.user1.y > 0.5) {
				float3 rm = float3(v.x, -v.y, v.z);
				float4 pc = sl.prevViewProjNoJitter * float4(rm, 0.0);
				if (pc.w > 1e-4) {
					float2 uvp0 = pc.xy / pc.w * 0.5 + 0.5;
					float wgt = saturate(min(uvp0.x, 1.0 - uvp0.x) * 8.0) * saturate(min(uvp0.y, 1.0 - uvp0.y) * 8.0);
					float2 uvp = saturate(uvp0);
					constexpr sampler lin(filter::linear, address::clamp_to_edge, coord::normalized);
					constexpr sampler nearestClamp(filter::nearest, address::clamp_to_edge, coord::normalized);
					if (wgt > 0.0) {
						half4 dz = sr.prevDepth.gather(nearestClamp, uvp);
						wgt *= smoothstep(400.0, 600.0, float(min(min(dz.x, dz.y), min(dz.z, dz.w))));
					}
					wgt *= 1.0 - smoothstep(cos(4.0 * sl.sunGeom.y), cos(1.5 * sl.sunGeom.y), dot(rm, sf.sunDirWorld.xyz));
					wgt *= 1.0 - smoothstep(cos(4.0 * sl.sunGeom.z), cos(1.5 * sl.sunGeom.z), dot(rm, sf.moonDirWorld.xyz));
					if (wgt > 0.0) {
						scr = float3(sr.prevScene.sample(lin, uvp).rgb) / max(sg.exposure.z, 1e-6);
						wS = wgt;
					}
				}
			}
			float3 refl = scr;
			if (wS < 1.0) {
				float3 skyR = shadeSkyLookup(r, skyLut, skyLutSmplr, sf);
				float4 cd = shadeCloudsDome(r, sl, sr.cloudDome);
				refl = mix(skyR * cd.a + cd.rgb, scr, wS);
			}
			refl *= open;
			float3 l = sf.lightDirWorld.xyz, h = normalize(l - v);
			float nh = saturate(h.y), nl = saturate(l.y), nv = max(-v.y, 1e-3);
			const float a2 = 0.135 * 0.135 * 0.135 * 0.135;
			float dd = nh * nh * (a2 - 1.0) + 1.0;
			float vis = 0.5 / max(nl * sqrt(nv * nv * (1.0 - a2) + a2) + nv * sqrt(nl * nl * (1.0 - a2) + a2), 1e-5);
			float mh = 1.0 - saturate(dot(h, -v));
			float spec = a2 / (dd * dd) * vis * nl * (0.0204 + 0.9796 * mh * mh * mh * mh * mh);
			float3 glint = sf.sunColor.rgb * min(spec, 60.0) * float(smoothstep(0.15h, 0.85h, shadow)) * open * (sl.user1.x * sl.user2.x);
			float ba = float(in.color.a);
			float aw = fr + (1.0 - fr) * ba;
			radiance = (refl * fr + radiance * ba * (1.0 - fr) + glint) / max(aw, 1e-3);
			outAlpha = half(aw);
		}
#endif
		radiance = shadeFogAir2(radiance, p, 0.0, 0.0, skyLut, skyLutSmplr, sf, sl, &sr);
	}
	if (shadeDev(sf, SHADE_DEV_FLAT_LIGHT)) radiance = float3(albedo) * sf.ambientSky.xyz;
	if (sf.flags.w == 2) radiance = (float3(n) * 0.5 + 0.5) / ex * 0.18;
	float3 c = min(max(radiance, 0.0) * ex, 60000.0);
	if (!overworld) c = shadeDimsFog(c, p, true, sf, sl, ex, false, sr.milkyWay, false);
	float a = sf.fogColor.a * saturate((d - sf.fogRange.x) / max(sf.fogRange.y - sf.fogRange.x, 1.0));
	if (a > 0.0) {
		float3 t = sf.flags.y == 1 ? pow(1.0 - a, float3(2.6, 1.25, 1.0)) : float3(1.0 - a);
		c = c * t + shadeLdrToHdr(sf.fogColor.rgb, sl) * (1.0 - t);
	}
	return float4(shadeLinearToSrgbExact(saturate(shadeDisplay(c, sl))), outAlpha);
}
