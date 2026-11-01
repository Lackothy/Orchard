// Copyright 2026 mcopt-shade contributors
//
// Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the
// License. You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0. Unless required by applicable
// law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES
// OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and
// limitations under the License.
//
// Dynamic lights: the visibility volumes (compiled by mcdynlight.m; dynlight.metal describes the whole module). One thread per
// voxel of each occluded light's 32^3 block of the world around it: it walks the voxels a ray from the light's centre to the
// voxel's centre crosses (3D DDA, Amanatides & Woo 1987) through the CPU's block occupancy and writes 1 when none of them
// (the light's own voxel and the target excepted) is an opaque block, else 0. The shading samples it trilinearly in front
// of each surface, so light stops at walls, floors and ceilings with block-sized soft edges.
//
// Occupancy (mcopt.metal.shade.light.Occupancy, a shared buffer the CPU fills from the client's chunk sections): a toroidal
// window of 8 x 8 x 8 sections (128^3 blocks) around the camera, one bit per block: section slot (sy & 7) * 64 +
// (sz & 7) * 8 + (sx & 7), 128 words each, bit index (y & 15) << 8 | (z & 15) << 4 | (x & 15) (the order of the game's
// PalettedContainer). Sections not (yet) known read as air.
#include <metal_stdlib>
using namespace metal;

struct DlVolume {
	int4 origin;
	float4 light;
};

#define DL_VOLUMES_MAX 64
struct DlVisParams {
	uint4 info;
	DlVolume vols[DL_VOLUMES_MAX];
};

static inline bool dlSolid(device const uint *occ, int3 b) {
	uint slot = ((uint(b.y >> 4) & 7u) << 6) | ((uint(b.z >> 4) & 7u) << 3) | (uint(b.x >> 4) & 7u);
	uint idx = (uint(b.y & 15) << 8) | (uint(b.z & 15) << 4) | uint(b.x & 15);
	return (occ[(slot << 7) | (idx >> 5)] >> (idx & 31u)) & 1u;
}

kernel void shade_dl_vis(uint3 t [[thread_position_in_grid]], constant DlVisParams &P [[buffer(0)]], device const uint *occ [[buffer(1)]],
                         texture3d<half, access::write> out [[texture(0)]]) {
	uint k = t.z >> 5;
	if (k >= P.info.x) return;
	DlVolume V = P.vols[k];
	int3 target = int3(int(t.x), int(t.y), int(t.z & 31u));
	float3 src = V.light.xyz;
	float3 dv = float3(target) + 0.5 - src;
	float dist = length(dv);
	half visible = 0.0h;
	if (dist <= V.light.w - 1.0) {
		visible = 1.0h;
		int3 cell = int3(floor(src));
		if (any(cell != target)) {
			float3 dir = dv / max(dist, 1e-4);
			int3 stp = int3(sign(dir));
			float3 inv = 1.0 / max(abs(dir), 1e-6);
			float3 next = select(float3(cell) + 1.0 - src, src - float3(cell), dir < 0.0);
			float3 tMax = select(next * inv, float3(1e30), stp == 0);
			float3 tDelta = select(inv, float3(1e30), stp == 0);
			for (int i = 0; i < 96; i++) {
				if (tMax.x < tMax.y && tMax.x < tMax.z) {
					cell.x += stp.x;
					tMax.x += tDelta.x;
				} else if (tMax.y < tMax.z) {
					cell.y += stp.y;
					tMax.y += tDelta.y;
				} else {
					cell.z += stp.z;
					tMax.z += tDelta.z;
				}
				if (all(cell == target)) break;
				if (dlSolid(occ, V.origin.xyz + cell)) {
					visible = 0.0h;
					break;
				}
			}
		}
	}
	out.write(half4(visible), uint3(t.x, t.y, uint(V.origin.w) * 32u + (t.z & 31u)));
}

#define DL_FD 29
#define DL_FO 2
#define DL_FROW 32
#define DL_FWORDS (DL_FROW / 4 * DL_FD * DL_FD)
#define DL_FSOLID 128u
#define DL_FFAR 127u
#define DL_FSTEP 4u
#define DL_FROUNDS 8

static inline uint dlFloodStep1(uint c, uint p) {
	uint w = min(p + DL_FSTEP, DL_FFAR - 1u);
	if (p < DL_FFAR && (c & 127u) > w) c = (c & DL_FSOLID) | w;
	return c;
}

static inline uint dlFloodStep4(uint cur, uint prev) {
	uint res = 0u;
	for (uint k = 0u; k < 32u; k += 8u) res |= dlFloodStep1((cur >> k) & 255u, (prev >> k) & 255u) << k;
	return res;
}

static inline int3 dlFloodSeeds(float3 src, thread uint4 &lo, thread uint4 &hi) {
	int3 c0 = int3(floor(src - 0.5)) - DL_FO;
	float3 q0 = float3(c0 + DL_FO) + 0.5 - src, q1 = q0 + 1.0;
	float4 xy2 = float4(q0.x * q0.x, q1.x * q1.x, q0.x * q0.x, q1.x * q1.x) + float4(q0.y * q0.y, q0.y * q0.y, q1.y * q1.y, q1.y * q1.y);
	lo = uint4(rint(sqrt(xy2 + q0.z * q0.z) * float(DL_FSTEP)));
	hi = uint4(rint(sqrt(xy2 + q1.z * q1.z) * float(DL_FSTEP)));
	return c0;
}

kernel void shade_dl_flood(uint tg [[threadgroup_position_in_grid]], uint ti [[thread_index_in_threadgroup]],
                           uint tn [[threads_per_threadgroup]], constant DlVisParams &P [[buffer(0)]], device const uint *occ [[buffer(1)]],
                           device uint *ways [[buffer(2)]]) {
	threadgroup uint g[DL_FWORDS];
	threadgroup atomic_uint changed[3];
	if (tg >= P.info.x) return;
	DlVolume V = P.vols[tg];
	float3 src = V.light.xyz;
	int3 own = int3(floor(src)) - DL_FO;
	uint4 sLo, sHi;
	int3 c0 = dlFloodSeeds(src, sLo, sHi);

	for (uint w = ti; w < DL_FWORDS; w += tn) {
		uint r = w >> 3, y = r % DL_FD, z = r / DL_FD;
		uint word = 0u;
		for (uint j = 0u; j < 4u; j++) {
			int3 v = int3(int((w & 7u) * 4u + j), int(y), int(z));
			uint val = DL_FSOLID | DL_FFAR;
			if (v.x < DL_FD) {
				val = all(v == own) || !dlSolid(occ, V.origin.xyz + v + DL_FO) ? DL_FFAR : DL_FSOLID | DL_FFAR;
				int3 o = v - c0;
				if (val == DL_FFAR && all(o >= 0) && all(o <= 1)) {
					uint4 sv = o.z == 0 ? sLo : sHi;
					uint sd = o.y == 0 ? (o.x == 0 ? sv.x : sv.y) : (o.x == 0 ? sv.z : sv.w);
					val = min(sd, DL_FFAR - 1u);
				}
			}
			word |= val << (8u * j);
		}
		g[w] = word;
	}
	if (ti < 3u) atomic_store_explicit(&changed[ti], 0u, memory_order_relaxed);
	threadgroup_barrier(mem_flags::mem_threadgroup);

	for (int round = 0; round < DL_FROUNDS; round++) {
		uint f = uint(round) % 3u;
		bool moved = false;
		for (uint r = ti; r < DL_FD * DL_FD; r += tn) {
			uint row[8];
			for (uint k = 0u; k < 8u; k++) row[k] = g[r * 8u + k];
			uint v[DL_FROW];
			for (uint x = 0u; x < DL_FROW; x++) v[x] = (row[x >> 2] >> (8u * (x & 3u))) & 255u;
			for (uint x = 1u; x < DL_FD; x++) v[x] = dlFloodStep1(v[x], v[x - 1u]);
			for (int x = DL_FD - 2; x >= 0; x--) v[x] = dlFloodStep1(v[x], v[x + 1]);
			for (uint k = 0u; k < 8u; k++) {
				uint word = v[k * 4u] | v[k * 4u + 1u] << 8u | v[k * 4u + 2u] << 16u | v[k * 4u + 3u] << 24u;
				if (word != row[k]) {
					g[r * 8u + k] = word;
					moved = true;
				}
			}
		}
		threadgroup_barrier(mem_flags::mem_threadgroup);
		for (uint axis = 0u; axis < 2u; axis++) {
			uint stride = axis == 0u ? 8u : 8u * DL_FD;
			for (uint c = ti; c < 8u * DL_FD; c += tn) {
				uint base = axis == 0u ? (c >> 3) * (8u * DL_FD) + (c & 7u)
				                       : (c >> 3) * 8u + (c & 7u);
				uint prev = g[base];
				for (uint s = 1u; s < DL_FD; s++) {
					uint cur = g[base + s * stride], nw = dlFloodStep4(cur, prev);
					if (nw != cur) {
						g[base + s * stride] = nw;
						moved = true;
					}
					prev = nw;
				}
				for (int s = DL_FD - 2; s >= 0; s--) {
					uint cur = g[base + uint(s) * stride], nw = dlFloodStep4(cur, prev);
					if (nw != cur) {
						g[base + uint(s) * stride] = nw;
						moved = true;
					}
					prev = nw;
				}
			}
			threadgroup_barrier(mem_flags::mem_threadgroup);
		}
		if (moved) atomic_store_explicit(&changed[f], 1u, memory_order_relaxed);
		if (ti == 0u) atomic_store_explicit(&changed[(f + 1u) % 3u], 0u, memory_order_relaxed);
		threadgroup_barrier(mem_flags::mem_threadgroup);
		if (atomic_load_explicit(&changed[f], memory_order_relaxed) == 0u) break;
	}

	for (uint w = ti; w < DL_FWORDS; w += tn) ways[tg * DL_FWORDS + w] = g[w];
}

kernel void shade_dl_flood_out(uint3 t [[thread_position_in_grid]], constant DlVisParams &P [[buffer(0)]], device const uint *ways [[buffer(2)]],
                               texture3d<half, access::write> out [[texture(0)]]) {
	uint k = t.z >> 5;
	if (k >= P.info.x) return;
	DlVolume V = P.vols[k];
	int3 v = int3(int(t.x), int(t.y), int(t.z & 31u)) - DL_FO;
	uint e = 255u;
	if (all(v >= 0) && all(v < DL_FD)) {
		uint bi = (uint(v.z) * DL_FD + uint(v.y)) * DL_FROW + uint(v.x);
		uint gv = (ways[k * DL_FWORDS + (bi >> 2)] >> (8u * (bi & 3u))) & 127u;
		if (gv < DL_FFAR) {
			uint4 sLo, sHi;
			int3 c0 = dlFloodSeeds(V.light.xyz, sLo, sHi);
			uint3 a0 = uint3(abs(v - c0)), a1 = uint3(abs(v - c0 - 1));
			uint4 dxy = uint4(a0.x + a0.y, a1.x + a0.y, a0.x + a1.y, a1.x + a1.y);
			uint4 m = min(sLo + DL_FSTEP * (dxy + a0.z), sHi + DL_FSTEP * (dxy + a1.z));
			uint open = min(min(m.x, m.y), min(m.z, m.w));
			e = min((gv - min(gv, open)) * (16u / DL_FSTEP), 254u);
		}
	}
	out.write(half4(half(float(e) * (1.0 / 255.0))), uint3(t.xy, uint(V.origin.w) * 32u + (t.z & 31u)));
}
