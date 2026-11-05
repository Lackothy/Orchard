// Modified for Russet: see-through sections tested against the depth pyramid (frag_t_test, frag_t_sum, frag_t_copy).
// Our own near terrain (mcopt.metal.own, -Dmcopt.own=true): GPU cull of every compiled section, then one instanced indirect
// draw per layer that pulls its vertices from the arena. The shading is vanilla's core/terrain (terrain.vsh / terrain.fsh and
// their includes fog, globals, sample_lightmap, texture_sampling), restated for vertex pulling.
#include <metal_stdlib>
using namespace metal;

struct Vtx {
	packed_float3 pos;
	uchar4 color;
	packed_float2 uv0;
	short2 uv2;
};

struct Section {
	int x, y, z;
	int uploadMs;
	uint recStart, recCount;
	uint pad0, pad1;
};

struct Rec {
	uint quadStart, slot, meta;
	int plane;
	uint lo, hi;
	uint pad0, pad1;
};

struct CullFrame {
	float4 planes[4];
	int4 camBlock;
	float4 camOffset;
	int4 camSection;
	int viewDistance;
	uint sectionCount;
	uint listCap;
	int useMask;
	int run;
	int occ;
	int fat;
	int fatT;
#ifdef MCOPT_SHADE
	float4 shadePlanes[2];
	int shadeClasses;
	int shadePad0, shadePad1, shadePad2;
#endif
};

#ifdef MCOPT_SHADE
#define SHADE_CLASSES 4
#define SHADE_CLASS_ARGS 32
static uint shadeClassId(constant CullFrame &f, uint layer, uint meta) {
	uint step = (uint(f.run) + SHADE_CLASSES - 1) / SHADE_CLASSES;
	return layer * SHADE_CLASSES + min(uint(SHADE_CLASSES - 1), (meta & 63) / max(step, 1u));
}
#endif

struct TUnit {
	uint quadStart, meta, lo, hi;
	int x, y, z;
	uint rec;
};

struct Inst {
	uint quadStart, meta;
	int x, y, z;
	int uploadMs;
	uint pad0, pad1;
};

static void putInst(device Inst *fat, uint at, Rec rec, device const Section *sections) {
	Section s = sections[rec.slot];
	Inst e;
	e.quadStart = rec.quadStart;
	e.meta = rec.meta;
	e.x = s.x;
	e.y = s.y;
	e.z = s.z;
	e.uploadMs = s.uploadMs;
	e.pad0 = uint(rec.plane);
	e.pad1 = 0;
	fat[at] = e;
}

#define ARGS_T 20
#define ARGS_DISPATCH 21

struct DrawFrame {
	uint listBase;
	int nowMs;
	int fadeMs;
	int clipOnCpu;
	float4x4 clip;
};

struct Projection { float4x4 ProjMat; };
struct Fog {
	packed_float4 FogColor;
	float FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogSkyEnd, FogCloudsEnd;
};
struct Globals {
	packed_int3 CameraBlockPos;
	float GlintAlpha;
	packed_float3 CameraOffset;
	float GameTime;
	float2 ScreenSize;
	int MenuBlurRadius;
	int UseRgss;
};
struct Terrain {
	float4x4 ModelViewMat;
	int2 TextureSize;
};

kernel void own_reset(constant CullFrame &f [[buffer(0)]], device uint *args [[buffer(3)]], uint i [[thread_position_in_grid]]) {
	if (i >= 4) return;
	device uint *a = args + i * 5;
	a[0] = f.run * 6;
	a[1] = 0;
	a[2] = 0;
	a[3] = 0;
	a[4] = 0;
	if (i == 0) {
		args[ARGS_T] = 0;
		args[ARGS_DISPATCH] = 0;
		args[ARGS_DISPATCH + 1] = 1;
		args[ARGS_DISPATCH + 2] = 1;
	}
#ifdef MCOPT_SHADE
	if (f.shadeClasses != 0 && i < 2) {
		uint step = (uint(f.run) + SHADE_CLASSES - 1) / SHADE_CLASSES;
		for (uint c = 0; c < SHADE_CLASSES; c++) {
			device uint *b = args + SHADE_CLASS_ARGS + (i * SHADE_CLASSES + c) * 5;
			b[0] = min(uint(f.run), (c + 1) * step) * 6;
			b[1] = 0;
			b[2] = 0;
			b[3] = 0;
			b[4] = 0;
		}
	}
#endif
}

kernel void own_clear_vis(device uint *vis [[buffer(6)]], constant uint &words [[buffer(7)]], uint i [[thread_position_in_grid]]) {
	if (i < words) vis[i] = 0;
}

constant float FACE_MARGIN = 1.0 / 64.0;

struct SolidCol {
	int x, z;
	uint lo, hi;
};

struct SolidOcc {
	float4x4 clip;
	int4 camBlock;
	float4 camOffset;
	int4 camSection;
	uint2 size;
	uint mips;
	uint on;
	int rd;
	int minY;
	int levels;
	int grid;
};

static bool solidHidden(constant SolidOcc &p, texture2d<float, access::read> occ, float3 lo, float3 hi) {
	if (p.on == 0) return false;
	float2 mn = float2(1e30), mx = float2(-1e30);
	float zmax = 0;
	for (int c = 0; c < 8; c++) {
		float3 v = float3((c & 1) ? hi.x : lo.x, (c & 2) ? hi.y : lo.y, (c & 4) ? hi.z : lo.z);
		float4 q = p.clip * float4(v, 1.0);
		if (q.w <= 0.05) return false;
		float3 n = q.xyz / q.w;
		mn = min(mn, n.xy);
		mx = max(mx, n.xy);
		zmax = max(zmax, n.z);
	}
	if (mx.x < -1 || mx.y < -1 || mn.x > 1 || mn.y > 1) return false;
	float2 sz = float2(p.size);
	uint2 a = uint2(clamp((mn * 0.5 + 0.5) * sz, float2(0), sz - 1)), b = uint2(clamp((mx * 0.5 + 0.5) * sz, float2(0), sz - 1));
	uint ext = max(b.x - a.x, b.y - a.y);
	uint level = ext == 0 ? 0 : min(p.mips - 1, 32 - clz(ext));
	uint2 lsize = uint2(max(1u, p.size.x >> level), max(1u, p.size.y >> level));
	uint2 la = min(a >> level, lsize - 1), lb = min(b >> level, lsize - 1);
	float far = 1.0;
	for (uint y = la.y; y <= lb.y; y++)
		for (uint x = la.x; x <= lb.x; x++) far = min(far, occ.read(uint2(x, y), level).x);
	return zmax < far - 1e-6;
}

kernel void own_cull(constant CullFrame &f [[buffer(0)]], device const Section *sections [[buffer(1)]], device const Rec *recs [[buffer(2)]],
	device atomic_uint *args [[buffer(3)]], device uint *lists [[buffer(4)]], device const uint *mask [[buffer(5)]],
	device const uint *prevVis [[buffer(8)]], device uint *tested [[buffer(9)]], device Inst *fat [[buffer(10)]], constant SolidOcc &so [[buffer(12)]],
	texture2d<float, access::read> soTex [[texture(1)]], uint i [[thread_position_in_grid]]) {
	if (i >= f.sectionCount) return;
	if (f.useMask != 0 && (mask[i >> 5] & (1u << (i & 31))) == 0) return;
	Section s = sections[i];
	if (s.recCount == 0) return;
	int3 sec = int3(s.x, s.y, s.z) >> 4;
	int dx = max(0, abs(sec.x - f.camSection.x) - 1), dz = max(0, abs(sec.z - f.camSection.z) - 1);
	if (dx * dx + dz * dz >= f.viewDistance * f.viewDistance) return;
	if (abs(sec.y - f.camSection.y) > f.viewDistance) return;
	float3 o = float3(int3(s.x, s.y, s.z) - f.camBlock.xyz) + f.camOffset.xyz;
	for (int p = 0; p < 4; p++) {
		float4 pl = f.planes[p];
		float3 v = o + select(float3(0), float3(16), pl.xyz > 0);
		if (dot(pl.xyz, v) + pl.w < -0.5) return;
	}
#ifdef MCOPT_SHADE
	for (int p = 0; p < 2; p++) {
		float4 pl = f.shadePlanes[p];
		if (all(pl.xyz == 0.0)) continue;
		float3 v = o + select(float3(0), float3(16), pl.xyz > 0);
		if (dot(pl.xyz, v) + pl.w < -0.5) return;
	}
#endif
	float3 cam = -o;
	for (uint r = s.recStart; r < s.recStart + s.recCount; r++) {
		Rec rec = recs[r];
		uint bucket = (rec.meta >> 6) & 7;
		float plane = float(rec.plane) / 256.0;
		bool vis;
		switch (bucket) {
			case 0: vis = cam.x > plane - FACE_MARGIN; break;
			case 1: vis = cam.x < plane + FACE_MARGIN; break;
			case 2: vis = cam.y > plane - FACE_MARGIN; break;
			case 3: vis = cam.y < plane + FACE_MARGIN; break;
			case 4: vis = cam.z > plane - FACE_MARGIN; break;
			case 5: vis = cam.z < plane + FACE_MARGIN; break;
			default: vis = true; break;
		}
		uint layer = (rec.meta >> 9) & 3;
		if (!vis || layer > 1) continue;
		if (so.on != 0 && solidHidden(so, soTex, o + float3(int3(rec.lo & 255, (rec.lo >> 8) & 255, (rec.lo >> 16) & 255) - 16),
			o + float3(int3(rec.hi & 255, (rec.hi >> 8) & 255, (rec.hi >> 16) & 255) - 16))) continue;
		bool was = true;
		if (f.occ == 2) {
			uint t = atomic_fetch_add_explicit(&args[ARGS_T], 1, memory_order_relaxed);
			if (t < f.listCap) tested[t] = r;
			continue;
		}
		if (f.occ != 0) {
			was = (prevVis[r >> 5] & (1u << (r & 31))) != 0;
			uint t = atomic_fetch_add_explicit(&args[ARGS_T], 1, memory_order_relaxed);
			if (t < f.listCap) tested[t] = r | (was ? 0x80000000u : 0u);
		}
		if (!was) continue;
#ifdef MCOPT_SHADE
		if (f.shadeClasses != 0) {
			uint id = shadeClassId(f, layer, rec.meta);
			uint ct = atomic_fetch_add_explicit(&args[SHADE_CLASS_ARGS + id * 5 + 1], 1, memory_order_relaxed);
			if (ct < f.listCap) lists[id * f.listCap + ct] = r;
			continue;
		}
#endif
		uint at = atomic_fetch_add_explicit(&args[layer * 5 + 1], 1, memory_order_relaxed);
		if (at < f.listCap) {
			if (f.fat != 0) putInst(fat, layer * f.listCap + at, rec, sections);
			else lists[layer * f.listCap + at] = r;
		}
	}
}

static uint cullAppendAt(device atomic_uint *counter, device uint *dst, uint cap, bool want, uint value) {
	uint mine = want ? 1u : 0u;
	uint total = simd_sum(mine);
	if (total == 0) return 0xFFFFFFFFu;
	uint before = simd_prefix_exclusive_sum(mine);
	uint base = 0;
	if (simd_is_first()) base = atomic_fetch_add_explicit(counter, total, memory_order_relaxed);
	base = simd_broadcast_first(base);
	if (!want || base + before >= cap) return 0xFFFFFFFFu;
	dst[base + before] = value;
	return base + before;
}

static void cullAppend(device atomic_uint *counter, device uint *dst, uint cap, bool want, uint value) {
	uint mine = want ? 1u : 0u;
	uint total = simd_sum(mine);
	if (total == 0) return;
	uint before = simd_prefix_exclusive_sum(mine);
	uint base = 0;
	if (simd_is_first()) base = atomic_fetch_add_explicit(counter, total, memory_order_relaxed);
	base = simd_broadcast_first(base);
	if (want && base + before < cap) dst[base + before] = value;
}

kernel void own_cull_r(constant CullFrame &f [[buffer(0)]], device const Section *sections [[buffer(1)]], device const Rec *recs [[buffer(2)]],
	device atomic_uint *args [[buffer(3)]], device uint *lists [[buffer(4)]], device const uint *mask [[buffer(5)]],
	device uint *tested [[buffer(9)]], constant SolidOcc &so [[buffer(12)]], texture2d<float, access::read> soTex [[texture(1)]],
	constant uint &recCap [[buffer(13)]], device TUnit *tfat [[buffer(10)]], uint r [[thread_position_in_grid]]) {
	bool want = false;
	uint layer = 0;
	Rec rec;
	Section s;
	if (r < recCap) {
		rec = recs[r];
		uint slot = rec.slot;
		if (slot < f.sectionCount && (f.useMask == 0 || (mask[slot >> 5] & (1u << (slot & 31))) != 0)) {
			s = sections[slot];
			if (s.recCount != 0 && r >= s.recStart && r < s.recStart + s.recCount) {
				int3 sec = int3(s.x, s.y, s.z) >> 4;
				int dx = max(0, abs(sec.x - f.camSection.x) - 1), dz = max(0, abs(sec.z - f.camSection.z) - 1);
				float3 o = float3(int3(s.x, s.y, s.z) - f.camBlock.xyz) + f.camOffset.xyz;
				bool in = dx * dx + dz * dz < f.viewDistance * f.viewDistance && abs(sec.y - f.camSection.y) <= f.viewDistance;
				for (int p = 0; p < 4 && in; p++) {
					float4 pl = f.planes[p];
					float3 v = o + select(float3(0), float3(16), pl.xyz > 0);
					if (dot(pl.xyz, v) + pl.w < -0.5) in = false;
				}
#ifdef MCOPT_SHADE
				for (int p = 0; p < 2 && in; p++) {
					float4 pl = f.shadePlanes[p];
					if (all(pl.xyz == 0.0)) continue;
					float3 v = o + select(float3(0), float3(16), pl.xyz > 0);
					if (dot(pl.xyz, v) + pl.w < -0.5) in = false;
				}
#endif
				layer = (rec.meta >> 9) & 3;
				if (in && layer <= 1) {
					float3 cam = -o;
					uint bucket = (rec.meta >> 6) & 7;
					float plane = float(rec.plane) / 256.0;
					bool vis;
					switch (bucket) {
						case 0: vis = cam.x > plane - FACE_MARGIN; break;
						case 1: vis = cam.x < plane + FACE_MARGIN; break;
						case 2: vis = cam.y > plane - FACE_MARGIN; break;
						case 3: vis = cam.y < plane + FACE_MARGIN; break;
						case 4: vis = cam.z > plane - FACE_MARGIN; break;
						case 5: vis = cam.z < plane + FACE_MARGIN; break;
						default: vis = true; break;
					}
					want = vis && !(so.on != 0 && solidHidden(so, soTex, o + float3(int3(rec.lo & 255, (rec.lo >> 8) & 255, (rec.lo >> 16) & 255) - 16),
						o + float3(int3(rec.hi & 255, (rec.hi >> 8) & 255, (rec.hi >> 16) & 255) - 16)));
				}
			}
		}
	}
#ifdef MCOPT_SHADE
	if (f.shadeClasses != 0) {
		uint id = want ? shadeClassId(f, layer, rec.meta) : 0xFFFFFFFFu;
		for (uint j = 0; j < 2 * SHADE_CLASSES; j++)
			cullAppend((device atomic_uint *) &args[SHADE_CLASS_ARGS + j * 5 + 1], lists + j * f.listCap, f.listCap, id == j, r);
		return;
	}
#endif
	if (f.occ == 2 && f.fatT != 0) {
		uint at = cullAppendAt(&args[ARGS_T], tested, f.listCap, want, r);
		if (at != 0xFFFFFFFFu) tfat[at] = TUnit {rec.quadStart, rec.meta, rec.lo, rec.hi, s.x, s.y, s.z, r};
	} else if (f.occ == 2) {
		cullAppend(&args[ARGS_T], tested, f.listCap, want, r);
	} else {
		cullAppend(&args[0 * 5 + 1], lists, f.listCap, want && layer == 0, r);
		cullAppend(&args[1 * 5 + 1], lists + f.listCap, f.listCap, want && layer == 1, r);
	}
}

kernel void own_finish(constant CullFrame &f [[buffer(0)]], device uint *args [[buffer(3)]], uint i [[thread_position_in_grid]]) {
	if (i >= 2) return;
	args[i * 5 + 1] = min(args[i * 5 + 1], f.listCap);
#ifdef MCOPT_SHADE
	if (f.shadeClasses != 0)
		for (uint c = 0; c < SHADE_CLASSES; c++) args[SHADE_CLASS_ARGS + (i * SHADE_CLASSES + c) * 5 + 1] = min(args[SHADE_CLASS_ARGS + (i * SHADE_CLASSES + c) * 5 + 1], f.listCap);
#endif
	if (i == 0) {
		args[ARGS_T] = min(args[ARGS_T], f.listCap);
		args[ARGS_DISPATCH] = (args[ARGS_T] + 63) / 64;
	}
}

struct FragFrame {
	float rdStart;
	int nowMs;
	int fadeMs;
	uint flags;
	uint sizes;
	uint sizeQuads;
	uint lists;
	uint classes;
	float binBlocks;
	uint run;
	uint epoch;
	uint tCap;
	float nearBlocks;
	uint recCap;
	uint capLimit;
};

static uint fragCap(constant CullFrame &f, constant FragFrame &g) {
	return g.capLimit != 0 ? min(f.listCap, g.capLimit) : f.listCap;
}

static bool fragInA(uint v, constant FragFrame &g) {
	return (g.flags & 4096) != 0 ? (v == g.epoch * 2 + 1 || (v >> 1) != g.epoch) : v == g.epoch;
}

static bool fragInAL(uint v, constant FragFrame &g, uint layer) {
	return fragInA(v, g) && ((g.flags & 16384) == 0 || layer != 0);
}

static uint fragBank(uint v, constant FragFrame &g, uint aBank) {
	return (g.flags & 8192) != 0 && (g.flags & 1024) != 0 && v != g.epoch * 2 + 1 ? g.lists / 2 : aBank;
}

#define F_TIE_CLOSE (1u << 20)
#define F_VGROUP_SHIFT 24u
#define F_VGROUP_MASK (3u << 24)
#define F_VARGS_COPY (1u << 26)
#define TIE_NONE 0xFFFFFFFFu
#define TIE_ALWAYS 0xFFFFFFFEu
#define TIE_RING_MAX 16u
#define TIE_CNT 6u
#define CAP_CNT 8u
#define VERIFY_CNT 11u
#define TIE_PROMO 4u
#define TIE_SEEN 5u

static bool fragTiePromote(constant FragFrame &g, device const uint *ring, device uint *occVis, uint r) {
	uint m = ring[r];
	if (m == TIE_NONE) return false;
	if (m == TIE_ALWAYS) return true;
	for (uint k = 0; k < TIE_RING_MAX && m != r; k++) {
		if (fragInA(occVis[m], g)) return true;
		m = ring[m];
	}
	return m != r;
}

struct A1Bind {
	uint maskLo, maskHi, mode, region;
};

static bool a1In(constant A1Bind &k, uint id) {
	return id < 32 ? ((k.maskLo >> id) & 1u) != 0 : id < 64 && ((k.maskHi >> (id - 32)) & 1u) != 0;
}
#ifndef A1_SH
#define A1_SH 2
#endif

static void a1Write(device uint *tab, constant A1Bind &k, uint id, uint at, uint r, uint blocks) {
	if (k.mode != 1 || at + blocks > k.region) return;
	device uint *t = tab + id * k.region + at;
	for (uint q = 0; q < blocks; q++) t[q] = r << 6 | q;
}

kernel void frag_reset(constant FragFrame &g [[buffer(11)]], device uint *args [[buffer(3)]], device uint *bArgs [[buffer(15)]],
	device uint *a1Cnt [[buffer(22)]], device uint *a1Draws [[buffer(24)]], constant A1Bind &a1 [[buffer(27)]], uint i [[thread_position_in_grid]]) {
	if (a1In(a1, i)) {
		a1Cnt[i] = 0;
		a1Cnt[64 + i] = 0;
	}
	if (a1.mode != 0 && i < 24) a1Draws[640 + i] = 0;
	if (i < 32) args[g.lists * 8 + i] = 0;
	if ((g.flags & 64) != 0 && i == 0) bArgs[g.lists * 5] = 0;
	if ((g.flags & 64) != 0 && i == 1) {
		bArgs[g.lists * 5 + 4] = 0;
		bArgs[g.lists * 5 + 5] = 0;
	}
	if ((g.flags & 64) != 0 && i == 2) {
		for (uint k = TIE_CNT; k <= VERIFY_CNT + 1; k++) bArgs[g.lists * 5 + k] = 0;
	}
	uint ib = (g.flags & 1024) != 0 ? i % (g.lists / 2) : i;
	if ((g.flags & 64) != 0 && i < g.lists) {
		device uint *b = bArgs + i * 5;
		uint cls = (ib >> 1) % g.classes, layer = (ib >> 1) / g.classes;
		b[0] = (layer == 1 && (g.flags & 16) != 0) || (g.flags & 2) == 0 ? g.run * 6 : (min(cls, g.sizes - 1) + 1) * g.sizeQuads * 6;
		b[1] = 0;
		b[2] = 0;
		b[3] = 0;
		b[4] = 0;
	}
	if (i >= g.lists) return;
	device uint *a = args + i * 5;
	uint cls = (ib >> 1) % g.classes, layer = (ib >> 1) / g.classes;
	a[0] = (layer == 1 && (g.flags & 16) != 0) || (g.flags & 2) == 0 ? g.run * 6 : (min(cls, g.sizes - 1) + 1) * g.sizeQuads * 6;
	a[1] = 0;
	a[2] = 0;
	a[3] = 0;
	a[4] = 0;
}

static bool fragBoxIn(constant CullFrame &f, float3 lo, float3 hi) {
	for (int p = 0; p < 4; p++) {
		float4 pl = f.planes[p];
		float3 v = select(lo, hi, pl.xyz > 0);
		if (dot(pl.xyz, v) + pl.w < -0.5) return false;
	}
	return true;
}

kernel void frag_cull(constant CullFrame &f [[buffer(0)]], device const Section *sections [[buffer(1)]], device const Rec *recs [[buffer(2)]],
	device atomic_uint *args [[buffer(3)]], device uint *lists [[buffer(4)]], device const uint *mask [[buffer(5)]], constant FragFrame &g [[buffer(11)]],
	constant SolidOcc &so [[buffer(12)]], device uint *occVis [[buffer(13)]], device uint2 *tList [[buffer(14)]],
	device atomic_uint *a1Cnt [[buffer(22)]], device uint *a1Tab [[buffer(26)]], constant A1Bind &a1 [[buffer(27)]],
	device atomic_uint *bArgs [[buffer(15)]], device const uint *tieRing [[buffer(30)]],
	texture2d<float, access::read> soTex [[texture(1)]], uint i [[thread_position_in_grid]]) {
	if (i >= f.sectionCount) return;
	if (f.useMask != 0 && (mask[i >> 5] & (1u << (i & 31))) == 0) return;
	Section s = sections[i];
	if (s.recCount == 0) return;
	int3 sec = int3(s.x, s.y, s.z) >> 4;
	int dx = max(0, abs(sec.x - f.camSection.x) - 1), dz = max(0, abs(sec.z - f.camSection.z) - 1);
	if (dx * dx + dz * dz >= f.viewDistance * f.viewDistance) return;
	if (abs(sec.y - f.camSection.y) > f.viewDistance) return;
	float3 o = float3(int3(s.x, s.y, s.z) - f.camBlock.xyz) + f.camOffset.xyz;
	if (!fragBoxIn(f, o, o + 16)) return;
	float3 cam = -o;
	uint end = s.recStart + s.recCount;
	uint lean = 0;
	uint bin = min(g.classes - 1, uint(length(o + 8.0) / g.binBlocks));
	uint aBank = (g.flags & 1024) != 0 && length(o + 8.0) >= g.nearBlocks ? g.lists / 2 : 0;
	if ((g.flags & 1) != 0 && g.nowMs - s.uploadMs >= g.fadeMs) {
		uint3 lo = uint3(255), hi = uint3(0);
		for (uint r = s.recStart; r < end; r++) {
			Rec rec = recs[r];
			lo = min(lo, uint3(rec.lo & 255, (rec.lo >> 8) & 255, (rec.lo >> 16) & 255));
			hi = max(hi, uint3(rec.hi & 255, (rec.hi >> 8) & 255, (rec.hi >> 16) & 255));
		}
		float3 a = abs(o + float3(int3(lo) - 16)), b = abs(o + float3(int3(hi) - 16));
		float3 m = max(a, b);
		float cyl = max(length(m.xz), m.y);
		lean = cyl + 1.0 < g.rdStart ? 1 : 0;
	}
	bool reverse = (g.flags & 8) != 0;
	uint funnelIn = 0, funnelFacing = 0;
	for (uint k = 0; k < (reverse ? 2 : 1) * s.recCount; k++) {
		uint pass = k / s.recCount, r = reverse && pass == 0 ? end - 1 - k : s.recStart + k % s.recCount;
		Rec rec = recs[r];
		uint layer = (rec.meta >> 9) & 3;
		if (reverse && layer != pass) continue;
		if (layer <= 1) funnelIn += (rec.meta & 63) + 1;
		uint bucket = (rec.meta >> 6) & 7;
		float plane = float(rec.plane) / 256.0;
		bool vis;
		switch (bucket) {
			case 0: vis = cam.x > plane - FACE_MARGIN; break;
			case 1: vis = cam.x < plane + FACE_MARGIN; break;
			case 2: vis = cam.y > plane - FACE_MARGIN; break;
			case 3: vis = cam.y < plane + FACE_MARGIN; break;
			case 4: vis = cam.z > plane - FACE_MARGIN; break;
			case 5: vis = cam.z < plane + FACE_MARGIN; break;
			default: vis = true; break;
		}
		if (!vis || layer > 1) continue;
		funnelFacing += (rec.meta & 63) + 1;
		if (so.on != 0 && solidHidden(so, soTex, o + float3(int3(rec.lo & 255, (rec.lo >> 8) & 255, (rec.lo >> 16) & 255) - 16),
			o + float3(int3(rec.hi & 255, (rec.hi >> 8) & 255, (rec.hi >> 16) & 255) - 16))) continue;
		if ((g.flags & 4) != 0) {
			float3 lo = o + float3(int3(rec.lo & 255, (rec.lo >> 8) & 255, (rec.lo >> 16) & 255) - 16);
			float3 hi = o + float3(int3(rec.hi & 255, (rec.hi >> 8) & 255, (rec.hi >> 16) & 255) - 16);
			if (!fragBoxIn(f, lo, hi)) continue;
		}
		if ((g.flags & 32) != 0) {
			atomic_fetch_add_explicit(&args[g.lists * 8 + bucket * 2], 1, memory_order_relaxed);
			atomic_fetch_add_explicit(&args[g.lists * 8 + bucket * 2 + 1], (rec.meta & 63) + 1, memory_order_relaxed);
		}
		uint cq = (g.flags & 128) != 0 ? (rec.meta >> 11) & 63 : rec.meta & 63;
		uint cls = layer == 1 && (g.flags & 16) != 0 ? bin : (g.flags & 2) != 0 ? min(cq / g.sizeQuads, g.sizes - 1) : 0;
		uint id = (layer * g.classes + cls) * 2 + lean;
		bool promoted = false;
		if ((g.flags & 64) != 0) {
			uint t = atomic_fetch_add_explicit(&args[g.lists * 8 + 18], 1, memory_order_relaxed);
			if (t < g.tCap) tList[t] = uint2(r, id);
			else atomic_fetch_add_explicit(&bArgs[g.lists * 5 + CAP_CNT + 1], 1, memory_order_relaxed);
			bool inA = fragInAL(occVis[r], g, layer);
			promoted = !inA && (g.flags & F_TIE_CLOSE) != 0 && fragTiePromote(g, tieRing, occVis, r);
			if (!inA && !promoted) continue;
			if ((g.flags & 2048) != 0 && aBank != 0) continue;
			if (promoted) {
				atomic_fetch_add_explicit(&bArgs[g.lists * 5 + TIE_CNT], 1, memory_order_relaxed);
				atomic_fetch_add_explicit(&bArgs[g.lists * 5 + TIE_CNT + 1], (rec.meta & 63) + 1, memory_order_relaxed);
			}
			if ((g.flags & 32) != 0) atomic_fetch_add_explicit(&args[g.lists * 8 + 14], (rec.meta & 63) + 1, memory_order_relaxed);
			if ((g.flags & 32768) != 0 && layer == 1) {
				occVis[g.recCap + 2 * r] = g.epoch * 2;
				occVis[g.recCap + 2 * r + 1] = id;
				occVis[g.recCap * 3 + s.recStart] = g.epoch;
			}
			id += fragBank(occVis[r], g, aBank);
		}
		uint at = atomic_fetch_add_explicit(&args[id * 5 + 1], 1, memory_order_relaxed);
		if (at < fragCap(f, g)) {
			lists[id * f.listCap + at] = r;
			if (promoted) occVis[g.recCap * TIE_PROMO + r] = g.epoch;
		} else if ((g.flags & 64) != 0) {
			atomic_fetch_add_explicit(&bArgs[g.lists * 5 + CAP_CNT], 1, memory_order_relaxed);
		}
		if (a1In(a1, id)) {
			uint nb = ((rec.meta & 63) + (1u << A1_SH)) >> A1_SH;
			uint qa = atomic_fetch_add_explicit(&a1Cnt[id], nb, memory_order_relaxed);
			atomic_fetch_add_explicit(&a1Cnt[64 + id], (rec.meta & 63) + 1, memory_order_relaxed);
			a1Write(a1Tab, a1, id, qa, r, nb);
		}
	}
	if ((g.flags & 32) != 0) {
		atomic_fetch_add_explicit(&args[g.lists * 8 + 27], funnelIn, memory_order_relaxed);
		atomic_fetch_add_explicit(&args[g.lists * 8 + 28], funnelFacing, memory_order_relaxed);
	}
}

static uint fragOrderedAppend(device atomic_uint *counter, bool want) {
	uint mine = want ? 1u : 0u;
	uint total = simd_sum(mine);
	if (total == 0) return 0xFFFFFFFFu;
	uint before = simd_prefix_exclusive_sum(mine);
	uint base = 0;
	if (simd_is_first()) base = atomic_fetch_add_explicit(counter, total, memory_order_relaxed);
	base = simd_broadcast_first(base);
	return want ? base + before : 0xFFFFFFFFu;
}

#define FRAG_CULL_S_PARAMS constant CullFrame &f [[buffer(0)]], device const Section *sections [[buffer(1)]], device const Rec *recs [[buffer(2)]], \
	device atomic_uint *args [[buffer(3)]], device uint *lists [[buffer(4)]], device const uint *mask [[buffer(5)]], constant FragFrame &g [[buffer(11)]], \
	constant SolidOcc &so [[buffer(12)]], device uint *occVis [[buffer(13)]], device uint2 *tList [[buffer(14)]], \
	device atomic_uint *a1Cnt [[buffer(22)]], device uint *a1Tab [[buffer(26)]], constant A1Bind &a1 [[buffer(27)]], \
	device atomic_uint *bArgs [[buffer(15)]], device const uint *tieRing [[buffer(30)]], \
	texture2d<float, access::read> soTex [[texture(1)]], uint gid [[thread_position_in_grid]], uint lane [[thread_index_in_simdgroup]]
#define FRAG_CULL_S_ARGS f, sections, recs, args, lists, mask, g, so, occVis, tList, a1Cnt, a1Tab, a1, bArgs, tieRing, soTex, gid, lane
template <ushort W> static void fragCullS(constant CullFrame &f, device const Section *sections, device const Rec *recs, device atomic_uint *args, device uint *lists,
	device const uint *mask, constant FragFrame &g, constant SolidOcc &so, device uint *occVis, device uint2 *tList,
	device atomic_uint *a1Cnt, device uint *a1Tab, constant A1Bind &a1, device atomic_uint *bArgs, device const uint *tieRing,
	texture2d<float, access::read> soTex, uint gid, uint lane) {
	uint i = gid / W, sl = lane % W;
	if (i >= f.sectionCount) return;
	if (f.useMask != 0 && (mask[i >> 5] & (1u << (i & 31))) == 0) return;
	Section s = sections[i];
	if (s.recCount == 0) return;
	int3 sec = int3(s.x, s.y, s.z) >> 4;
	int dx = max(0, abs(sec.x - f.camSection.x) - 1), dz = max(0, abs(sec.z - f.camSection.z) - 1);
	if (dx * dx + dz * dz >= f.viewDistance * f.viewDistance) return;
	if (abs(sec.y - f.camSection.y) > f.viewDistance) return;
	float3 o = float3(int3(s.x, s.y, s.z) - f.camBlock.xyz) + f.camOffset.xyz;
	if (!fragBoxIn(f, o, o + 16)) return;
	float3 cam = -o;
	uint end = s.recStart + s.recCount;
	uint lean = 0;
	uint aBank = (g.flags & 1024) != 0 && length(o + 8.0) >= g.nearBlocks ? g.lists / 2 : 0;
	if ((g.flags & 1) != 0 && g.nowMs - s.uploadMs >= g.fadeMs) {
		uint3 lo = uint3(255), hi = uint3(0);
		for (uint r = s.recStart + sl; r < end; r += W) {
			Rec rec = recs[r];
			lo = min(lo, uint3(rec.lo & 255, (rec.lo >> 8) & 255, (rec.lo >> 16) & 255));
			hi = max(hi, uint3(rec.hi & 255, (rec.hi >> 8) & 255, (rec.hi >> 16) & 255));
		}
		if (W == 32) {
			lo = uint3(simd_min(lo.x), simd_min(lo.y), simd_min(lo.z));
			hi = uint3(simd_max(hi.x), simd_max(hi.y), simd_max(hi.z));
		} else {
			for (ushort k = W / 2; k > 0; k >>= 1) {
				lo = min(lo, simd_shuffle_xor(lo, k));
				hi = max(hi, simd_shuffle_xor(hi, k));
			}
		}
		float3 a = abs(o + float3(int3(lo) - 16)), b = abs(o + float3(int3(hi) - 16));
		float3 m = max(a, b);
		float cyl = max(length(m.xz), m.y);
		lean = cyl + 1.0 < g.rdStart ? 1 : 0;
	}
	uint funnelIn = 0, funnelFacing = 0;
	for (uint base = s.recStart; base < end; base += W) {
		uint r = base + sl;
		bool live = r < end, want = false, tested = false, promoted = false;
		uint id = 0, bucket = 0, quads = 0, bank = aBank;
		if (live) {
			Rec rec = recs[r];
			uint layer = (rec.meta >> 9) & 3;
			quads = (rec.meta & 63) + 1;
			if (layer <= 1) funnelIn += quads;
			bucket = (rec.meta >> 6) & 7;
			float plane = float(rec.plane) / 256.0;
			bool vis;
			switch (bucket) {
				case 0: vis = cam.x > plane - FACE_MARGIN; break;
				case 1: vis = cam.x < plane + FACE_MARGIN; break;
				case 2: vis = cam.y > plane - FACE_MARGIN; break;
				case 3: vis = cam.y < plane + FACE_MARGIN; break;
				case 4: vis = cam.z > plane - FACE_MARGIN; break;
				case 5: vis = cam.z < plane + FACE_MARGIN; break;
				default: vis = true; break;
			}
			if (vis && layer <= 1) {
				funnelFacing += quads;
				float3 lo = o + float3(int3(rec.lo & 255, (rec.lo >> 8) & 255, (rec.lo >> 16) & 255) - 16);
				float3 hi = o + float3(int3(rec.hi & 255, (rec.hi >> 8) & 255, (rec.hi >> 16) & 255) - 16);
				if (!(so.on != 0 && solidHidden(so, soTex, lo, hi)) && ((g.flags & 4) == 0 || fragBoxIn(f, lo, hi))) {
					uint cq = (g.flags & 128) != 0 ? (rec.meta >> 11) & 63 : rec.meta & 63;
					uint cls = (g.flags & 2) != 0 ? min(cq / g.sizeQuads, g.sizes - 1) : 0;
					id = (layer * g.classes + cls) * 2 + lean;
					tested = (g.flags & 64) != 0;
					bool inA = fragInAL(occVis[r], g, layer);
					promoted = tested && !inA && (g.flags & F_TIE_CLOSE) != 0 && fragTiePromote(g, tieRing, occVis, r);
					want = !tested || ((inA || promoted) && ((g.flags & 2048) == 0 || aBank == 0));
					promoted = promoted && want;
					if (promoted) {
						atomic_fetch_add_explicit(&bArgs[g.lists * 5 + TIE_CNT], 1, memory_order_relaxed);
						atomic_fetch_add_explicit(&bArgs[g.lists * 5 + TIE_CNT + 1], quads, memory_order_relaxed);
					}
					if (tested && want) bank = fragBank(occVis[r], g, aBank);
					if (tested && want && layer == 1 && (g.flags & 32768) != 0) {
						occVis[g.recCap + 2 * r] = g.epoch * 2;
						occVis[g.recCap + 2 * r + 1] = id;
						occVis[g.recCap * 3 + s.recStart] = g.epoch;
					}
				}
			}
		}
		if ((g.flags & 32) != 0 && (want || tested)) {
			atomic_fetch_add_explicit(&args[g.lists * 8 + bucket * 2], 1, memory_order_relaxed);
			atomic_fetch_add_explicit(&args[g.lists * 8 + bucket * 2 + 1], quads, memory_order_relaxed);
			if (tested && want) atomic_fetch_add_explicit(&args[g.lists * 8 + 14], quads, memory_order_relaxed);
		}
		if ((g.flags & 64) != 0) {
			uint t = fragOrderedAppend(&args[g.lists * 8 + 18], tested);
			if (tested && t < g.tCap) tList[t] = uint2(r, id);
			else if (tested) atomic_fetch_add_explicit(&bArgs[g.lists * 5 + CAP_CNT + 1], 1, memory_order_relaxed);
			id += bank;
		}
		bool pending = want;
		while (simd_any(pending)) {
			uint lid = simd_min(pending ? id : 0xFFFFFFFFu);
			bool mine = pending && id == lid;
			uint at = fragOrderedAppend(&args[lid * 5 + 1], mine);
			if (mine && at < fragCap(f, g)) {
				lists[lid * f.listCap + at] = r;
				if (promoted) occVis[g.recCap * TIE_PROMO + r] = g.epoch;
			} else if (mine && (g.flags & 64) != 0) {
				atomic_fetch_add_explicit(&bArgs[g.lists * 5 + CAP_CNT], 1, memory_order_relaxed);
			}
			if (a1In(a1, lid)) {
				uint mq = mine ? (quads + (1u << A1_SH) - 1) >> A1_SH : 0u, pre = simd_prefix_exclusive_sum(mq), total = simd_sum(mq);
				uint rbase = 0;
				uint realq = simd_sum(mine ? quads : 0u);
				if (simd_is_first()) {
					rbase = atomic_fetch_add_explicit(&a1Cnt[lid], total, memory_order_relaxed);
					atomic_fetch_add_explicit(&a1Cnt[64 + lid], realq, memory_order_relaxed);
				}
				rbase = simd_broadcast_first(rbase);
				if (mine && a1.mode == 1 && rbase + total <= a1.region)
					for (uint j = 0; j < mq; j++) a1Tab[lid * a1.region + rbase + pre + j] = r << 6 | j;
			}
			pending = pending && !mine;
		}
	}
	if ((g.flags & 32) != 0) {
		uint fin = simd_sum(funnelIn), ffa = simd_sum(funnelFacing);
		if (simd_is_first()) {
			atomic_fetch_add_explicit(&args[g.lists * 8 + 27], fin, memory_order_relaxed);
			atomic_fetch_add_explicit(&args[g.lists * 8 + 28], ffa, memory_order_relaxed);
		}
	}
}

kernel void frag_cull_s(FRAG_CULL_S_PARAMS) { fragCullS<32>(FRAG_CULL_S_ARGS); }
kernel void frag_cull_s16(FRAG_CULL_S_PARAMS) { fragCullS<16>(FRAG_CULL_S_ARGS); }
kernel void frag_cull_s8(FRAG_CULL_S_PARAMS) { fragCullS<8>(FRAG_CULL_S_ARGS); }
kernel void frag_cull_s4(FRAG_CULL_S_PARAMS) { fragCullS<4>(FRAG_CULL_S_ARGS); }

kernel void frag_cull_sig(constant CullFrame &f [[buffer(0)]], constant FragFrame &g [[buffer(11)]], device const uint *args [[buffer(3)]],
	device const uint *lists [[buffer(4)]], device atomic_uint *sig [[buffer(16)]], constant uint &set [[buffer(17)]], uint i [[thread_position_in_grid]]) {
	uint id = i / f.listCap, at = i % f.listCap;
	if (id >= g.lists || at >= min(args[id * 5 + 1], f.listCap)) return;
	uint v = lists[id * f.listCap + at];
	device atomic_uint *q = sig + (id * 2 + set) * 4;
	atomic_fetch_add_explicit(&q[0], 1u, memory_order_relaxed);
	atomic_fetch_add_explicit(&q[1], v, memory_order_relaxed);
	atomic_fetch_xor_explicit(&q[2], v, memory_order_relaxed);
	atomic_fetch_add_explicit(&q[3], (v ^ 0x9e3779b9u) * 2654435761u, memory_order_relaxed);
}

template <bool G>
static void fragFinish(constant CullFrame &f, constant FragFrame &g, device uint *args, device const uint *a1Cnt, device uint *a1Draws,
	device uint *a1Tab, constant A1Bind &a1, device uint *lists, device uint *gArgs, uint i) {
	if (i >= g.lists) return;
	uint raw = args[i * 5 + 1], n = min(raw, fragCap(f, g));
	args[i * 5 + 1] = n;
	if (G && (g.flags & (F_VGROUP_MASK | F_VARGS_COPY)) != 0) {
		uint sh = (g.flags & F_VGROUP_MASK) >> F_VGROUP_SHIFT, inst = (n + (1u << sh) - 1) >> sh;
		device uint *ga = gArgs + i * 5;
		ga[0] = args[i * 5] << sh;
		ga[1] = inst;
		ga[2] = 0;
		ga[3] = 0;
		ga[4] = 0;
		for (uint e = n; e < (inst << sh); e++) lists[i * f.listCap + e] = 0xFFFFFFFFu;
	}
	if (a1In(a1, i)) {
		uint q = a1Cnt[i], inst = ((q << A1_SH) + 63) / 64;
		bool full = (inst * 64 >> A1_SH) > a1.region, over = full || raw > f.listCap;
		if (a1.mode == 1 && !over)
			for (uint e = q; e < (inst * 64 >> A1_SH); e++) a1Tab[i * a1.region + e] = 0xFFFFFFFFu;
		device uint *d = a1Draws + i * 10;
		d[0] = 64 * 6;
		d[1] = over ? 0 : inst;
		d[2] = 0;
		d[3] = (i * a1.region << A1_SH) * 4;
		d[4] = 0;
		d[5] = args[i * 5];
		d[6] = over ? n : 0;
		d[7] = 0;
		d[8] = 0;
		d[9] = 0;
		uint hl = (g.flags & 1024) != 0 ? g.lists / 2 : g.lists, ib = i % hl;
		uint grp = (ib >> 1) / g.classes == 1 ? 2u : i >= hl ? 1u : 0u;
		device atomic_uint *tm = (device atomic_uint *) (a1Draws + 640 + grp * 8);
		if (n > 0 || q > 0) atomic_fetch_add_explicit(&tm[full ? 1 : over ? 2 : 0], 1, memory_order_relaxed);
		atomic_fetch_add_explicit(&tm[3], q, memory_order_relaxed);
		atomic_fetch_add_explicit(&tm[4], n, memory_order_relaxed);
		atomic_fetch_add_explicit(&tm[5], a1Cnt[64 + i], memory_order_relaxed);
		atomic_fetch_add_explicit(&tm[6], (args[i * 5] / 6) * n, memory_order_relaxed);
		atomic_fetch_add_explicit(&tm[7], over ? (args[i * 5] / 6) * n : inst * 64, memory_order_relaxed);
		if ((g.flags & 32) != 0 && grp == 0) {
			device atomic_uint *st = (device atomic_uint *) (args + g.lists * 8);
			atomic_fetch_add_explicit(&st[29], q << A1_SH, memory_order_relaxed);
			atomic_fetch_add_explicit(&st[30], n, memory_order_relaxed);
			if (over) atomic_fetch_add_explicit(&st[31], 1, memory_order_relaxed);
		}
	}
	device uint *m = args + g.lists * 5 + i * 3;
	m[0] = n;
	m[1] = 1;
	m[2] = 1;
	if (i == 0 && (g.flags & 64) != 0) {
		uint t = min(args[g.lists * 8 + 18], g.tCap);
		args[g.lists * 8 + 18] = t;
		args[g.lists * 8 + 19] = (t + 63) / 64;
		args[g.lists * 8 + 20] = 1;
		args[g.lists * 8 + 21] = 1;
	}
}

kernel void frag_finish(constant CullFrame &f [[buffer(0)]], constant FragFrame &g [[buffer(11)]], device uint *args [[buffer(3)]],
	device const uint *a1Cnt [[buffer(22)]], device uint *a1Draws [[buffer(24)]], device uint *a1Tab [[buffer(26)]], constant A1Bind &a1 [[buffer(27)]],
	uint i [[thread_position_in_grid]]) {
	fragFinish<false>(f, g, args, a1Cnt, a1Draws, a1Tab, a1, args, args, i);
}

kernel void frag_finish_g(constant CullFrame &f [[buffer(0)]], constant FragFrame &g [[buffer(11)]], device uint *args [[buffer(3)]],
	device const uint *a1Cnt [[buffer(22)]], device uint *a1Draws [[buffer(24)]], device uint *a1Tab [[buffer(26)]], constant A1Bind &a1 [[buffer(27)]],
	device uint *lists [[buffer(4)]], device uint *gArgs [[buffer(28)]], uint i [[thread_position_in_grid]]) {
	fragFinish<true>(f, g, args, a1Cnt, a1Draws, a1Tab, a1, lists, gArgs, i);
}

struct FragTest {
	float4x4 clip;
	uint2 screen;
	uint2 hiz0;
	uint mips;
	uint fine;
	uint hull;
	uint debug;
	uint minLevel;
};

static float fragCross(float2 o, float2 a, float2 b) {
	return (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x);
}

static uint fragHull(thread float2 *p, thread float2 *h) {
	for (uint i = 1; i < 8; i++) {
		float2 v = p[i];
		int j = int(i) - 1;
		while (j >= 0 && (p[j].x > v.x || (p[j].x == v.x && p[j].y > v.y))) {
			p[j + 1] = p[j];
			j--;
		}
		p[j + 1] = v;
	}
	uint k = 0;
	for (uint i = 0; i < 8; i++) {
		while (k >= 2 && fragCross(h[k - 2], h[k - 1], p[i]) <= 0) k--;
		h[k++] = p[i];
	}
	for (int i = 6, t = int(k) + 1; i >= 0; i--) {
		while (int(k) >= t && fragCross(h[k - 2], h[k - 1], p[i]) <= 0) k--;
		h[k++] = p[i];
	}
	return k - 1;
}

static bool fragTestBox(float3 lo, float3 hi, constant FragTest &ft, texture2d<float, access::read> hiz) {
	if (ft.mips == 0) return true;
	float2 mn = float2(1e30), mx = float2(-1e30);
	float zmax = 0;
	float2 pts[8];
	for (uint c = 0; c < 8; c++) {
		float4 h = ft.clip * float4(select(lo, hi, bool3((c & 1) != 0, (c & 2) != 0, (c & 4) != 0)), 1.0);
		if (h.w <= 0.05) return true;
		float3 n = h.xyz / h.w;
		mn = min(mn, n.xy);
		mx = max(mx, n.xy);
		zmax = max(zmax, n.z);
		pts[c] = (n.xy * 0.5 + 0.5) * float2(ft.screen);
	}
	if (mx.x < -1 || mx.y < -1 || mn.x > 1 || mn.y > 1) return false;
	float2 smin = clamp((mn * 0.5 + 0.5) * float2(ft.screen), float2(0), float2(ft.screen) - 1);
	float2 smax = clamp((mx * 0.5 + 0.5) * float2(ft.screen), float2(0), float2(ft.screen) - 1);
	uint2 a = uint2(smin) / 2, b = uint2(smax) / 2;
	uint ext = max(b.x - a.x, b.y - a.y);
	uint level = ext == 0 ? 0 : min(ft.mips - 1, 32 - clz(ext));
	level = level > ft.fine ? level - ft.fine : 0;
	level = max(level, ft.minLevel);
	uint2 lsize = uint2(max(1u, ft.hiz0.x >> level), max(1u, ft.hiz0.y >> level));
	uint2 la = min(a >> level, lsize - 1), lb = min(b >> level, lsize - 1);
	float2 hp[16];
	uint hn = ft.hull != 0 ? fragHull(pts, hp) : 0;
	float cell = float(2u << level);
	float far = 1.0;
	for (uint y = la.y; y <= lb.y; y++) {
		for (uint x = la.x; x <= lb.x; x++) {
			if (hn >= 3) {
				float2 q0 = float2(x, y) * cell, q1 = q0 + cell;
				bool outside = false;
				for (uint e = 0; e < hn && !outside; e++) {
					float2 ea = hp[e], eb = hp[e + 1];
					outside = fragCross(ea, eb, q0) < 0 && fragCross(ea, eb, float2(q1.x, q0.y)) < 0 && fragCross(ea, eb, q1) < 0
						&& fragCross(ea, eb, float2(q0.x, q1.y)) < 0;
				}
				if (outside) continue;
			}
			far = min(far, hiz.read(uint2(x, y), level).x);
		}
	}
	return zmax >= far - 1e-6;
}

struct PyrOut {
	float4 pos [[position]];
};

vertex PyrOut own_pyr_vs(uint vid [[vertex_id]]) {
	PyrOut o;
	float2 p = float2((vid << 1) & 2, vid & 2);
	o.pos = float4(p * 2.0 - 1.0, 0.0, 1.0);
	return o;
}

fragment float own_pyr_fs(PyrOut in [[stage_in]], depth2d<float, access::read> depth [[texture(0)]]) {
	uint2 t = uint2(in.pos.xy);
	uint dw = depth.get_width(), dh = depth.get_height();
	float m = 1.0;
	for (uint y = t.y * 2; y < min(t.y * 2 + 2, dh); y++)
		for (uint x = t.x * 2; x < min(t.x * 2 + 2, dw); x++) m = min(m, depth.read(uint2(x, y)));
	return m;
}

static void fragRedraw(constant CullFrame &f, constant FragFrame &g, device const Section *sections, device const Rec *recs, device uint *occVis,
	device atomic_uint *bArgs, device uint *listsB, Rec rec) {
	if ((g.flags & 32768) == 0 || ((rec.meta >> 9) & 3) != 0) return;
	Section s = sections[rec.slot];
	if (occVis[g.recCap * 3 + s.recStart] != g.epoch) return;
	device atomic_uint *am = (device atomic_uint *) (occVis + g.recCap);
	uint bucket = (rec.meta >> 6) & 7, inA = g.epoch * 2;
	uint3 lo = uint3(rec.lo & 255, (rec.lo >> 8) & 255, (rec.lo >> 16) & 255), hi = uint3(rec.hi & 255, (rec.hi >> 8) & 255, (rec.hi >> 16) & 255);
	uint k0 = s.recStart, k1 = s.recStart + s.recCount;
	if ((s.pad0 & 0x80000000u) != 0) {
		k0 = s.recStart + (s.pad0 & 0xFFFFu);
		k1 = min(k1, k0 + ((s.pad0 >> 16) & 0x7FFFu));
	}
	for (uint k = k0; k < k1; k++) {
		Rec q = recs[k];
		if (((q.meta >> 9) & 3) != 1 || ((q.meta >> 6) & 7) != bucket) continue;
		uint3 ql = uint3(q.lo & 255, (q.lo >> 8) & 255, (q.lo >> 16) & 255), qh = uint3(q.hi & 255, (q.hi >> 8) & 255, (q.hi >> 16) & 255);
		if (any(ql > hi) || any(lo > qh)) continue;
		uint expected = inA;
		bool won = false;
		while (!won) {
			won = atomic_compare_exchange_weak_explicit(&am[2 * k], &expected, inA + 1, memory_order_relaxed, memory_order_relaxed);
			if (!won && expected != inA) break;
			expected = inA;
		}
		if (!won) continue;
		uint id = atomic_load_explicit(&am[2 * k + 1], memory_order_relaxed);
		if (id >= g.lists) continue;
		uint at = atomic_fetch_add_explicit(&bArgs[id * 5 + 1], 1, memory_order_relaxed);
		if (at >= f.listCap) continue;
		listsB[id * f.listCap + at] = k;
		atomic_fetch_add_explicit(&bArgs[g.lists * 5 + 4], 1, memory_order_relaxed);
		atomic_fetch_add_explicit(&bArgs[g.lists * 5 + 5], (q.meta & 63) + 1, memory_order_relaxed);
	}
}

kernel void frag_uocc_test(constant CullFrame &f [[buffer(0)]], constant FragFrame &g [[buffer(11)]], constant FragTest &ft [[buffer(17)]],
	device const Section *sections [[buffer(1)]], device const Rec *recs [[buffer(2)]], device const uint *args [[buffer(3)]],
	device uint *occVis [[buffer(13)]], device const uint2 *tList [[buffer(14)]], device atomic_uint *bArgs [[buffer(15)]],
	device uint *listsB [[buffer(16)]], texture2d<float, access::read> hiz [[texture(0)]], uint i [[thread_position_in_grid]]) {
	if (i >= min(args[g.lists * 8 + 18], g.tCap)) return;
	uint2 e = tList[i];
	uint r = e.x, id = e.y;
	bool hist = (g.flags & 4096) != 0;
	uint mark = g.epoch + ((g.flags & 512) != 0 ? 2 : 1);
	if (hist) mark = mark * 2 + 1;
	if ((ft.debug & 4) != 0) {
		occVis[r] = mark;
		return;
	}
	Rec rec = recs[r];
	Section s = sections[rec.slot];
	float3 o = float3(int3(s.x, s.y, s.z) - f.camBlock.xyz) + f.camOffset.xyz;
	float3 lo = o + float3(int3(rec.lo & 255, (rec.lo >> 8) & 255, (rec.lo >> 16) & 255) - 16);
	float3 hi = o + float3(int3(rec.hi & 255, (rec.hi >> 8) & 255, (rec.hi >> 16) & 255) - 16);
	bool vis;
	if ((g.flags & 256) != 0 && ((rec.lo >> 24) | (rec.hi >> 24) | rec.pad1) != 0) {
		int3 ul = int3(rec.lo & 255, (rec.lo >> 8) & 255, (rec.lo >> 16) & 255) - 16, uh = int3(rec.hi & 255, (rec.hi >> 8) & 255, (rec.hi >> 16) & 255) - 16;
		uint p = rec.pad1;
		int3 aLo = ul + int3((rec.lo >> 24) & 15, rec.lo >> 28, (rec.hi >> 24) & 15), aHi = uh - int3(rec.hi >> 28, p & 15, (p >> 4) & 15);
		int3 bLo = ul + int3((p >> 8) & 15, (p >> 12) & 15, (p >> 16) & 15), bHi = uh - int3((p >> 20) & 15, (p >> 24) & 15, p >> 28);
		vis = fragTestBox(o + float3(aLo), o + float3(aHi), ft, hiz) || fragTestBox(o + float3(bLo), o + float3(bHi), ft, hiz);
	} else {
		vis = fragTestBox(lo, hi, ft, hiz);
	}
	if ((ft.debug & 2) != 0) vis = true;
	if (!vis) {
		if (hist) occVis[r] = mark - 1;
		return;
	}
	uint old = occVis[r];
	occVis[r] = mark;
	bool farOnly = (g.flags & 2048) != 0 && length(o + 8.0) >= g.nearBlocks;
	bool promoted = (g.flags & F_TIE_CLOSE) != 0 && occVis[g.recCap * TIE_PROMO + r] == g.epoch;
	if ((fragInAL(old, g, (rec.meta >> 9) & 3) || promoted) && (ft.debug & 1) == 0 && !farOnly) return;
	uint at = atomic_fetch_add_explicit(&bArgs[id * 5 + 1], 1, memory_order_relaxed);
	if (at >= fragCap(f, g)) {
		if ((g.flags & F_TIE_CLOSE) != 0) atomic_fetch_add_explicit(&bArgs[g.lists * 5 + CAP_CNT + 2], 1, memory_order_relaxed);
		return;
	}
	listsB[id * f.listCap + at] = r;
	if ((g.flags & 32) != 0) atomic_fetch_add_explicit(&bArgs[g.lists * 5], (rec.meta & 63) + 1, memory_order_relaxed);
	fragRedraw(f, g, sections, recs, occVis, bArgs, listsB, rec);
}

constant float FAST_TEST_DZ = 5e-7;
constant float FAST_TEST_PX = 1.0 / 32.0;

template <bool G, bool STOP>
static float fragFar(uint level, uint2 la, uint2 lb, uint2 lsize, float zm, texture2d<float, access::read> hiz, array<texture2d<float>, 12> lv) {
	float far = 1.0;
	if (G) {
		constexpr sampler nearestClamp(coord::normalized, address::clamp_to_edge, filter::nearest);
		float2 inv = 1.0 / float2(lsize);
		for (uint y = la.y; y <= lb.y; y += 2) {
			for (uint x = la.x; x <= lb.x; x += 2) {
				float4 q = lv[level].gather(nearestClamp, float2(x + 1, y + 1) * inv);
				bool r = x + 1 <= lb.x, d = y + 1 <= lb.y;
				float m = min(q.w, r ? q.z : 1.0);
				if (d) m = min(m, min(q.x, r ? q.y : 1.0));
				far = min(far, m);
				if (STOP && zm >= far - 1e-6) return far;
			}
		}
		return far;
	}
	for (uint y = la.y; y <= lb.y; y++) {
		for (uint x = la.x; x <= lb.x; x++) {
			far = min(far, hiz.read(uint2(x, y), level).x);
			if (STOP && zm >= far - 1e-6) return far;
		}
	}
	return far;
}

template <bool G>
static bool fragTestBoxFast(float3 lo, float3 hi, constant FragTest &ft, texture2d<float, access::read> hiz, array<texture2d<float>, 12> lv) {
	if (ft.mips == 0) return true;
	float2 mn = float2(1e30), mx = float2(-1e30);
	float zmax = 0;
	for (uint c = 0; c < 8; c++) {
		float4 h = ft.clip * float4(select(lo, hi, bool3((c & 1) != 0, (c & 2) != 0, (c & 4) != 0)), 1.0);
		if (h.w <= 0.051) return true;
		float3 n = h.xyz / h.w;
		mn = min(mn, n.xy);
		mx = max(mx, n.xy);
		zmax = max(zmax, n.z);
	}
	float2 px = FAST_TEST_PX * 2.0 / float2(ft.screen);
	if (mx.x < -1 - px.x || mx.y < -1 - px.y || mn.x > 1 + px.x || mn.y > 1 + px.y) return false;
	float2 smin = clamp((mn * 0.5 + 0.5) * float2(ft.screen) - FAST_TEST_PX, float2(0), float2(ft.screen) - 1);
	float2 smax = clamp((mx * 0.5 + 0.5) * float2(ft.screen) + FAST_TEST_PX, float2(0), float2(ft.screen) - 1);
	uint2 a = uint2(smin) / 2, b = uint2(smax) / 2;
	uint ext = max(b.x - a.x, b.y - a.y);
	uint coarse = ext == 0 ? 0 : min(ft.mips - 1, 32 - clz(ext));
	uint level = max(coarse > ft.fine ? coarse - ft.fine : 0, ft.minLevel);
	coarse = max(coarse, level);
	float zm = zmax + FAST_TEST_DZ;
	if (level < coarse) {
		uint2 csize = uint2(max(1u, ft.hiz0.x >> coarse), max(1u, ft.hiz0.y >> coarse));
		float cf = fragFar<G, false>(coarse, min(a >> coarse, csize - 1), min(b >> coarse, csize - 1), csize, zm, hiz, lv);
		if (!(zm >= cf - 1e-6)) return false;
	}
	uint2 lsize = uint2(max(1u, ft.hiz0.x >> level), max(1u, ft.hiz0.y >> level));
	float far = fragFar<G, true>(level, min(a >> level, lsize - 1), min(b >> level, lsize - 1), lsize, zm, hiz, lv);
	return zm >= far - 1e-6;
}

static bool fragBoxMayPass(float3 lo, float3 hi, constant FragTest &ft, texture2d<float, access::read> hiz) {
	if (ft.mips == 0) return true;
	float2 mn = float2(1e30), mx = float2(-1e30);
	float zmax = 0;
	for (uint c = 0; c < 8; c++) {
		float4 h = ft.clip * float4(select(lo, hi, bool3((c & 1) != 0, (c & 2) != 0, (c & 4) != 0)), 1.0);
		if (h.w <= 0.051) return true;
		float3 n = h.xyz / h.w;
		mn = min(mn, n.xy);
		mx = max(mx, n.xy);
		zmax = max(zmax, n.z);
	}
	float2 px = FAST_TEST_PX * 2.0 / float2(ft.screen);
	if (mx.x < -1 - px.x || mx.y < -1 - px.y || mn.x > 1 + px.x || mn.y > 1 + px.y) return false;
	float2 smin = clamp((mn * 0.5 + 0.5) * float2(ft.screen) - FAST_TEST_PX, float2(0), float2(ft.screen) - 1);
	float2 smax = clamp((mx * 0.5 + 0.5) * float2(ft.screen) + FAST_TEST_PX, float2(0), float2(ft.screen) - 1);
	uint2 a = uint2(smin) / 2, b = uint2(smax) / 2;
	uint ext = max(b.x - a.x, b.y - a.y);
	uint coarse = max(ext == 0 ? 0 : min(ft.mips - 1, 32 - clz(ext)), ft.minLevel);
	uint2 csize = uint2(max(1u, ft.hiz0.x >> coarse), max(1u, ft.hiz0.y >> coarse));
	uint2 ca = min(a >> coarse, csize - 1), cb = min(b >> coarse, csize - 1);
	float cf = 1.0;
	for (uint y = ca.y; y <= cb.y; y++)
		for (uint x = ca.x; x <= cb.x; x++) cf = min(cf, hiz.read(uint2(x, y), coarse).x);
	return zmax + 1e-5 >= cf - 1e-6;
}

template <bool BOX, bool G>
static void fragUoccTestF(constant CullFrame &f, constant FragFrame &g, constant FragTest &ft, device const Section *sections,
	device const Rec *recs, device const uint *args, device uint *occVis, device const uint2 *tList, device atomic_uint *bArgs,
	device uint *listsB, texture2d<float, access::read> hiz, array<texture2d<float>, 12> lv, uint i) {
	if (i >= min(args[g.lists * 8 + 18], g.tCap)) return;
	uint2 e = tList[i];
	uint r = e.x, id = e.y;
	bool hist = (g.flags & 4096) != 0;
	uint mark = g.epoch + ((g.flags & 512) != 0 ? 2 : 1);
	if (hist) mark = mark * 2 + 1;
	if ((ft.debug & 4) != 0) {
		occVis[r] = mark;
		return;
	}
	Rec rec = recs[r];
	Section s = sections[rec.slot];
	float3 o = float3(int3(s.x, s.y, s.z) - f.camBlock.xyz) + f.camOffset.xyz;
	float3 lo = o + float3(int3(rec.lo & 255, (rec.lo >> 8) & 255, (rec.lo >> 16) & 255) - 16);
	float3 hi = o + float3(int3(rec.hi & 255, (rec.hi >> 8) & 255, (rec.hi >> 16) & 255) - 16);
	bool vis, sub = (g.flags & 256) != 0 && ((rec.lo >> 24) | (rec.hi >> 24) | rec.pad1) != 0;
	float3 aL, aH, bL, bH;
	if (sub) {
		int3 ul = int3(rec.lo & 255, (rec.lo >> 8) & 255, (rec.lo >> 16) & 255) - 16, uh = int3(rec.hi & 255, (rec.hi >> 8) & 255, (rec.hi >> 16) & 255) - 16;
		uint p = rec.pad1;
		int3 aLo = ul + int3((rec.lo >> 24) & 15, rec.lo >> 28, (rec.hi >> 24) & 15), aHi = uh - int3(rec.hi >> 28, p & 15, (p >> 4) & 15);
		int3 bLo = ul + int3((p >> 8) & 15, (p >> 12) & 15, (p >> 16) & 15), bHi = uh - int3((p >> 20) & 15, (p >> 24) & 15, p >> 28);
		aL = o + float3(aLo); aH = o + float3(aHi); bL = o + float3(bLo); bH = o + float3(bHi);
		vis = (!BOX || fragBoxMayPass(lo, hi, ft, hiz)) && (fragTestBoxFast<G>(aL, aH, ft, hiz, lv) || fragTestBoxFast<G>(bL, bH, ft, hiz, lv));
	} else {
		vis = fragTestBoxFast<G>(lo, hi, ft, hiz, lv);
	}
	if ((ft.debug & 8) != 0) {
		bool slow = sub ? fragTestBox(aL, aH, ft, hiz) || fragTestBox(bL, bH, ft, hiz) : fragTestBox(lo, hi, ft, hiz);
		if (slow && !vis) atomic_fetch_add_explicit(&bArgs[g.lists * 5 + 1], 1, memory_order_relaxed);
		atomic_fetch_add_explicit(&bArgs[g.lists * 5 + 2], 1, memory_order_relaxed);
		if (vis && !slow) atomic_fetch_add_explicit(&bArgs[g.lists * 5 + 3], 1, memory_order_relaxed);
	}
	if ((ft.debug & 2) != 0) vis = true;
	if (!vis) {
		if (hist) occVis[r] = mark - 1;
		return;
	}
	uint old = occVis[r];
	occVis[r] = mark;
	bool farOnly = (g.flags & 2048) != 0 && length(o + 8.0) >= g.nearBlocks;
	bool promoted = (g.flags & F_TIE_CLOSE) != 0 && occVis[g.recCap * TIE_PROMO + r] == g.epoch;
	if ((fragInAL(old, g, (rec.meta >> 9) & 3) || promoted) && (ft.debug & 1) == 0 && !farOnly) return;
	uint at = atomic_fetch_add_explicit(&bArgs[id * 5 + 1], 1, memory_order_relaxed);
	if (at >= fragCap(f, g)) {
		if ((g.flags & F_TIE_CLOSE) != 0) atomic_fetch_add_explicit(&bArgs[g.lists * 5 + CAP_CNT + 2], 1, memory_order_relaxed);
		return;
	}
	listsB[id * f.listCap + at] = r;
	if ((g.flags & 32) != 0) atomic_fetch_add_explicit(&bArgs[g.lists * 5], (rec.meta & 63) + 1, memory_order_relaxed);
	fragRedraw(f, g, sections, recs, occVis, bArgs, listsB, rec);
}

#define FRAG_UOCC_TEST_PARAMS constant CullFrame &f [[buffer(0)]], constant FragFrame &g [[buffer(11)]], constant FragTest &ft [[buffer(17)]], \
	device const Section *sections [[buffer(1)]], device const Rec *recs [[buffer(2)]], device const uint *args [[buffer(3)]], \
	device uint *occVis [[buffer(13)]], device const uint2 *tList [[buffer(14)]], device atomic_uint *bArgs [[buffer(15)]], \
	device uint *listsB [[buffer(16)]], texture2d<float, access::read> hiz [[texture(0)]], array<texture2d<float>, 12> lv [[texture(1)]], \
	uint i [[thread_position_in_grid]]
kernel void frag_uocc_test_f(FRAG_UOCC_TEST_PARAMS) { fragUoccTestF<false, false>(f, g, ft, sections, recs, args, occVis, tList, bArgs, listsB, hiz, lv, i); }
kernel void frag_uocc_test_fb(FRAG_UOCC_TEST_PARAMS) { fragUoccTestF<true, false>(f, g, ft, sections, recs, args, occVis, tList, bArgs, listsB, hiz, lv, i); }
kernel void frag_uocc_test_fg(FRAG_UOCC_TEST_PARAMS) { fragUoccTestF<false, true>(f, g, ft, sections, recs, args, occVis, tList, bArgs, listsB, hiz, lv, i); }

kernel void frag_uocc_finish(constant CullFrame &f [[buffer(0)]], constant FragFrame &g [[buffer(11)]], device uint *bArgs [[buffer(15)]],
	uint i [[thread_position_in_grid]]) {
	if (i >= g.lists) return;
	bArgs[i * 5 + 1] = min(bArgs[i * 5 + 1], fragCap(f, g));
}

kernel void frag_uocc_finish_g(constant CullFrame &f [[buffer(0)]], constant FragFrame &g [[buffer(11)]], device uint *bArgs [[buffer(15)]],
	device uint *listsB [[buffer(16)]], device uint *gArgs [[buffer(28)]], uint i [[thread_position_in_grid]]) {
	if (i >= g.lists) return;
	uint n = min(bArgs[i * 5 + 1], fragCap(f, g));
	bArgs[i * 5 + 1] = n;
	if ((g.flags & (F_VGROUP_MASK | F_VARGS_COPY)) != 0) {
		uint sh = (g.flags & F_VGROUP_MASK) >> F_VGROUP_SHIFT, inst = (n + (1u << sh) - 1) >> sh;
		device uint *ga = gArgs + i * 5;
		ga[0] = bArgs[i * 5] << sh;
		ga[1] = inst;
		ga[2] = 0;
		ga[3] = 0;
		ga[4] = 0;
		for (uint e = n; e < (inst << sh); e++) listsB[i * f.listCap + e] = 0xFFFFFFFFu;
	}
}

struct TOccHead {
	uint groups, one0, one1;
	uint runs;
	uint split;
};

struct TOccRun {
	uint first, units;
	packed_float3 lo, hi;
};

kernel void frag_t_test(constant FragTest &ft [[buffer(17)]], device const TOccHead &h [[buffer(0)]], device const TOccRun *runs [[buffer(1)]],
	device uint *vis [[buffer(2)]], texture2d<float, access::read> hiz [[texture(0)]], uint i [[thread_position_in_grid]]) {
	if (i >= h.runs) return;
	vis[i] = fragTestBox(float3(runs[i].lo), float3(runs[i].hi), ft, hiz) ? 1 : 0;
}

kernel void frag_t_sum(device const TOccHead &h [[buffer(0)]], device const TOccRun *runs [[buffer(1)]], device uint *vis [[buffer(2)]],
	device uint *args [[buffer(3)]]) {
	uint rich = 0, lean = h.split, kept = 0;
	for (uint i = 0; i < h.runs; i++) {
		if (vis[i] == 0) continue;
		kept++;
		if (runs[i].first < h.split) {
			vis[i] = rich + 1;
			rich += runs[i].units;
		} else {
			vis[i] = lean + 1;
			lean += runs[i].units;
		}
	}
	args[1] = rich;
	args[9] = lean - h.split;
	args[14] = kept;
}

kernel void frag_t_copy(device const TOccHead &h [[buffer(0)]], device const TOccRun *runs [[buffer(1)]], device const uint *vis [[buffer(2)]],
	device const uint *list [[buffer(4)]], device uint *out [[buffer(5)]], uint i [[thread_position_in_grid]]) {
	if (i >= h.runs || vis[i] == 0) return;
	uint at = vis[i] - 1, first = runs[i].first, units = runs[i].units;
	for (uint u = 0; u < units; u++) out[at + u] = list[first + u];
}

kernel void frag_tie_verify_a(constant CullFrame &f [[buffer(0)]], constant FragFrame &g [[buffer(11)]], device const uint *args [[buffer(3)]],
	device const uint *lists [[buffer(4)]], device atomic_uint *occVis [[buffer(13)]], device atomic_uint *bArgs [[buffer(15)]], uint i [[thread_position_in_grid]]) {
	uint id = i / f.listCap, at = i % f.listCap;
	if (id >= g.lists || at >= min(args[id * 5 + 1], fragCap(f, g))) return;
	uint r = lists[id * f.listCap + at];
	if (atomic_exchange_explicit(&occVis[g.recCap * TIE_SEEN + r], g.epoch, memory_order_relaxed) == g.epoch)
		atomic_fetch_add_explicit(&bArgs[g.lists * 5 + VERIFY_CNT + 1], 1, memory_order_relaxed);
}

kernel void frag_tie_verify_b(constant CullFrame &f [[buffer(0)]], constant FragFrame &g [[buffer(11)]], device atomic_uint *occVis [[buffer(13)]],
	device atomic_uint *bArgs [[buffer(15)]], device const uint *listsB [[buffer(16)]], uint i [[thread_position_in_grid]]) {
	uint id = i / f.listCap, at = i % f.listCap;
	if (id >= g.lists || at >= atomic_load_explicit(&bArgs[id * 5 + 1], memory_order_relaxed)) return;
	uint r = listsB[id * f.listCap + at];
	if (atomic_load_explicit(&occVis[g.recCap * TIE_SEEN + r], memory_order_relaxed) == g.epoch)
		atomic_fetch_add_explicit(&bArgs[g.lists * 5 + VERIFY_CNT], 1, memory_order_relaxed);
}

kernel void own_hiz0(depth2d<float, access::read> depth [[texture(0)]], texture2d<float, access::write> out [[texture(1)]],
	constant uint &scale [[buffer(2)]], uint2 p [[thread_position_in_grid]]) {
	uint w = out.get_width(), h = out.get_height(), dw = depth.get_width(), dh = depth.get_height();
	if (p.x >= w || p.y >= h) return;
	float m = 1.0;
	for (uint y = p.y * scale; y < min(p.y * scale + scale, dh); y++)
		for (uint x = p.x * scale; x < min(p.x * scale + scale, dw); x++) m = min(m, depth.read(uint2(x, y)));
	out.write(float4(m), p);
}

kernel void own_hizn(texture2d<float, access::read> prev [[texture(0)]], texture2d<float, access::write> out [[texture(1)]],
	uint2 p [[thread_position_in_grid]]) {
	uint w = out.get_width(), h = out.get_height(), pw = prev.get_width(), ph = prev.get_height();
	if (p.x >= w || p.y >= h) return;
	uint x1 = p.x == w - 1 ? pw : min(p.x * 2 + 2, pw), y1 = p.y == h - 1 ? ph : min(p.y * 2 + 2, ph);
	float m = 1.0;
	for (uint y = p.y * 2; y < y1; y++)
		for (uint x = p.x * 2; x < x1; x++) m = min(m, prev.read(uint2(x, y)).x);
	out.write(float4(m), p);
}

struct OccFrame {
	float4x4 clip;
	int4 camBlock;
	float4 camOffset;
	uint2 screen;
	uint2 hiz0;
	uint mips;
	uint listCap;
	uint visWords;
	uint fat;
};

kernel void own_occ(constant OccFrame &f [[buffer(0)]], device const Section *sections [[buffer(1)]], device const Rec *recs [[buffer(2)]],
	device atomic_uint *args [[buffer(3)]], device uint *lists [[buffer(4)]], device atomic_uint *vis [[buffer(6)]],
	device const uint *tested [[buffer(9)]], device Inst *fat [[buffer(10)]], texture2d<float, access::read> hiz [[texture(0)]],
	uint i [[thread_position_in_grid]]) {
	if (i >= atomic_load_explicit(&args[ARGS_T], memory_order_relaxed)) return;
	uint t = tested[i], r = t & 0x7fffffffu;
	bool drawnA = (t >> 31) != 0;
	Rec rec = recs[r];
	Section s = sections[rec.slot];
	float3 o = float3(int3(s.x, s.y, s.z) - f.camBlock.xyz) + f.camOffset.xyz;
	float3 lo = o + float3(int3(rec.lo & 255, (rec.lo >> 8) & 255, (rec.lo >> 16) & 255) - 16);
	float3 hi = o + float3(int3(rec.hi & 255, (rec.hi >> 8) & 255, (rec.hi >> 16) & 255) - 16);
	bool visible = false;
	float2 mn = float2(1e30), mx = float2(-1e30);
	float zmax = 0;
	for (int c = 0; c < 8 && !visible; c++) {
		float3 v = float3((c & 1) ? hi.x : lo.x, (c & 2) ? hi.y : lo.y, (c & 4) ? hi.z : lo.z);
		float4 q = f.clip * float4(v, 1.0);
		if (q.w <= 0.05) {
			visible = true;
			break;
		}
		float3 n = q.xyz / q.w;
		mn = min(mn, n.xy);
		mx = max(mx, n.xy);
		zmax = max(zmax, n.z);
	}
	if (!visible) {
		float2 smin = clamp((mn * 0.5 + 0.5) * float2(f.screen), float2(0), float2(f.screen) - 1);
		float2 smax = clamp((mx * 0.5 + 0.5) * float2(f.screen), float2(0), float2(f.screen) - 1);
		if (mx.x < -1 || mx.y < -1 || mn.x > 1 || mn.y > 1) {
			visible = false;
		} else {
			uint2 a = uint2(smin) / 2, b = uint2(smax) / 2;
			uint ext = max(b.x - a.x, b.y - a.y);
			uint level = ext == 0 ? 0 : min(f.mips - 1, 32 - clz(ext));
			uint2 la = a >> level, lb = b >> level;
			uint2 lsize = uint2(max(1u, f.hiz0.x >> level), max(1u, f.hiz0.y >> level));
			la = min(la, lsize - 1);
			lb = min(lb, lsize - 1);
			float far = 1.0;
			for (uint y = la.y; y <= lb.y; y++)
				for (uint x = la.x; x <= lb.x; x++) far = min(far, hiz.read(uint2(x, y), level).x);
			visible = zmax >= far - 1e-6;
		}
	}
	if (!visible) return;
	atomic_fetch_or_explicit(&vis[r >> 5], 1u << (r & 31), memory_order_relaxed);
	if (drawnA) return;
	uint layer = (rec.meta >> 9) & 1;
	uint at = atomic_fetch_add_explicit(&args[(2 + layer) * 5 + 1], 1, memory_order_relaxed);
	if (at < f.listCap) {
		if (f.fat != 0) putInst(fat, (2 + layer) * f.listCap + at, rec, sections);
		else lists[(2 + layer) * f.listCap + at] = r;
	}
}

kernel void own_finish_b(constant OccFrame &f [[buffer(0)]], device uint *args [[buffer(3)]], uint i [[thread_position_in_grid]]) {
	if (i >= 2) return;
	args[(2 + i) * 5 + 1] = min(args[(2 + i) * 5 + 1], f.listCap);
}

constant bool F_HALF_SET [[function_constant(9)]];
constant bool F_LEAN_SET [[function_constant(10)]];
constant bool F_NONIDX_SET [[function_constant(11)]];
constant bool F_REVERSE_SET [[function_constant(12)]];
constant bool F_REVERSE = is_function_constant_defined(F_REVERSE_SET) && F_REVERSE_SET;
constant bool F_TIGHT_SET [[function_constant(13)]];
constant bool F_TIGHT = is_function_constant_defined(F_TIGHT_SET) && F_TIGHT_SET;
constant bool F_POSONLY_SET [[function_constant(14)]];
constant bool F_POSONLY = is_function_constant_defined(F_POSONLY_SET) && F_POSONLY_SET;
constant bool F_OCCA_SET [[function_constant(15)]];
constant bool F_OCCB_SET [[function_constant(16)]];
constant bool F_OCCA = is_function_constant_defined(F_OCCA_SET) && F_OCCA_SET;
constant bool F_OCCB = is_function_constant_defined(F_OCCB_SET) && F_OCCB_SET;
constant bool F_OCC = F_OCCA || F_OCCB;
constant bool F_HALF = is_function_constant_defined(F_HALF_SET) && F_HALF_SET;
constant bool F_LEAN = is_function_constant_defined(F_LEAN_SET) && F_LEAN_SET;
constant bool F_NONIDX = is_function_constant_defined(F_NONIDX_SET) && F_NONIDX_SET;
constant bool F_VARY = !F_POSONLY;
constant bool F_FLOATCOLOR = !F_HALF && F_VARY;
constant bool F_HALFCOLOR = F_HALF && F_VARY;
constant bool F_RICH = !F_LEAN && F_VARY;
constant bool F_FOGVERT_SET [[function_constant(18)]];
constant bool F_FOGVERT_ALL_SET [[function_constant(19)]];
constant bool F_FOGVERT = is_function_constant_defined(F_FOGVERT_SET) && F_FOGVERT_SET;
constant bool F_FOGVERT_ALL = is_function_constant_defined(F_FOGVERT_ALL_SET) && F_FOGVERT_ALL_SET;
constant bool F_FV = F_FOGVERT && F_VARY && (F_LEAN || F_FOGVERT_ALL);
constant bool F_FOGVERT_T_SET [[function_constant(20)]];
constant bool F_FVT = is_function_constant_defined(F_FOGVERT_T_SET) && F_FOGVERT_T_SET && F_VARY;
constant bool F_FOGVERT_ANY = F_FV || F_FVT;
constant bool F_QFLAT_SET [[function_constant(21)]];
constant bool F_QFLAT = is_function_constant_defined(F_QFLAT_SET) && F_QFLAT_SET;
constant bool F_GROUP_SET [[function_constant(25)]];
constant bool F_GROUP = is_function_constant_defined(F_GROUP_SET) && F_GROUP_SET;

static float linearFogV(float vertexDistance, float fogStart, float fogEnd) {
	if (vertexDistance <= fogStart) return 0.0;
	else if (vertexDistance >= fogEnd) return 1.0;
	return (vertexDistance - fogStart) / (fogEnd - fogStart);
}
constant bool F_ORACLE_SET [[function_constant(17)]];
constant bool F_ORACLE = is_function_constant_defined(F_ORACLE_SET) && F_ORACLE_SET;

struct VOut {
	float4 pos [[position]];
	float sphericalVertexDistance [[function_constant(F_VARY)]];
	float cylindricalVertexDistance [[function_constant(F_RICH)]];
	float4 vertexColor [[function_constant(F_FLOATCOLOR)]];
	half4 vertexColorH [[function_constant(F_HALFCOLOR)]];
	float2 texCoord0 [[function_constant(F_VARY)]];
	float chunkVisibility [[function_constant(F_RICH)]];
	uint oracleUnit [[flat]] [[function_constant(F_ORACLE)]];
	float fogF [[function_constant(F_FVT)]];
	uint fogIn [[flat]] [[function_constant(F_FVT)]];
#ifdef MCOPT_SHADE
	float3 shadePos [[function_constant(F_VARY)]];
	half2 shadeLight [[function_constant(F_VARY)]];
#endif
};

static void putColor(thread VOut &o, float4 c) {
	if (F_POSONLY) return;
	if (F_HALF) o.vertexColorH = half4(c);
	else o.vertexColor = c;
}

static float4 getColor(VOut in) {
	if (F_POSONLY) return float4(1);
	if (F_HALF) return float4(in.vertexColorH);
	return in.vertexColor;
}

static void putRich(thread VOut &o, float cyl, float cv) {
	if (F_RICH) {
		o.cylindricalVertexDistance = cyl;
		o.chunkVisibility = cv;
	}
}

#ifdef OWN_EXACT_POS
static float ownPosCode(device const void *arena, uint c) {
	return c >= 61440u ? ((device const float *) arena)[c - 61440u] : float(c) / 2048.0 - 8.0;
}
#define OWN_POS3(arena, a, b, c) float3(ownPosCode(arena, uint(a)), ownPosCode(arena, uint(b)), ownPosCode(arena, uint(c)))
#else
#define OWN_POS3(arena, a, b, c) (float3(a, b, c) / 2048.0 - 8.0)
#endif

struct CVtx {
	ushort px, py, pz;
	uchar lu, lv;
	uchar4 color;
	ushort u, v;
};

constant bool COMPACT [[function_constant(1)]];
constant bool TRANSLUCENT [[function_constant(4)]];
constant bool QUADS [[function_constant(8)]];
constant int VS_MODE [[function_constant(7)]];
constant bool FAT [[function_constant(2)]];
constant bool CPU_CLIP [[function_constant(3)]];

static VOut ownVertex(uint vi, int3 origin, int uploadMs, constant DrawFrame &df, device const void *arenaRaw, constant Projection &proj,
	constant Globals &g, constant Terrain &t, texture2d<float> lightmap, sampler lightSampler, thread float3 &rel);
static VOut ownShade(Vtx v, int3 origin, int uploadMs, constant DrawFrame &df, constant Projection &proj, constant Globals &g,
	constant Terrain &t, texture2d<float> lightmap, sampler lightSampler, thread float3 &rel);

static Vtx ownQrec(device const void *arenaRaw, uint r, uint c) {
	device const uint2 *w = ((device const uint2 *) arenaRaw) + r * 5;
	uint2 w01 = w[0];
	Vtx v;
	if ((w01.x & 0x80000000u) != 0) {
		CVtx k = ((device const CVtx *) arenaRaw)[w01.y * 4 + c];
		v.pos = packed_float3(OWN_POS3(arenaRaw, k.px, k.py, k.pz));
		v.color = k.color;
		v.uv0 = packed_float2(float2(k.u, k.v) / 65536.0);
		v.uv2 = short2(k.lu, k.lv);
		return v;
	}
	uint2 w23 = w[1], w45 = w[2], w67 = w[3], w89 = w[4];
	uint sel = w01.x;
	uint ps = sel >> (3 * c), us = sel >> (12 + 2 * c);
	uint x = (w01.y >> ((ps & 1) * 16)) & 0xffff, y = (w23.x >> (((ps >> 1) & 1) * 16)) & 0xffff, z = (w23.y >> (((ps >> 2) & 1) * 16)) & 0xffff;
	uint u = (w45.x >> ((us & 1) * 16)) & 0xffff, vv = (w45.y >> (((us >> 1) & 1) * 16)) & 0xffff;
	uint tint = w67.x, grey = (w67.y >> (8 * c)) & 255;
	uint light = ((c < 2 ? w89.x : w89.y) >> ((c & 1) * 16)) & 0xffff;
	v.pos = packed_float3(OWN_POS3(arenaRaw, x, y, z));
	if ((sel & (1u << 20)) != 0) v.color = uchar4(uchar(tint), uchar(tint >> 8), uchar(tint >> 16), uchar(grey));
	else v.color = uchar4(uchar(grey * (tint & 255) / 255), uchar(grey * ((tint >> 8) & 255) / 255), uchar(grey * ((tint >> 16) & 255) / 255), uchar(tint >> 24));
	v.uv0 = packed_float2(float2(u, vv) / 65536.0);
	v.uv2 = short2(light & 255, light >> 8);
	return v;
}

static float3 ownQrecPos(device const void *arenaRaw, uint r, uint c) {
	device const uint2 *w = ((device const uint2 *) arenaRaw) + r * 5;
	uint2 w01 = w[0];
	if ((w01.x & 0x80000000u) != 0) {
		CVtx k = ((device const CVtx *) arenaRaw)[w01.y * 4 + c];
		return OWN_POS3(arenaRaw, k.px, k.py, k.pz);
	}
	uint2 w23 = w[1];
	uint ps = w01.x >> (3 * c);
	uint x = (w01.y >> ((ps & 1) * 16)) & 0xffff, y = (w23.x >> (((ps >> 1) & 1) * 16)) & 0xffff, z = (w23.y >> (((ps >> 2) & 1) * 16)) & 0xffff;
	return OWN_POS3(arenaRaw, x, y, z);
}

#ifdef OWN_QREC
constant bool OWN_REC = true;
#else
constant bool OWN_REC = false;
#endif

vertex VOut own_vs(uint vid [[vertex_id]], uint iid [[instance_id]], constant DrawFrame &df [[buffer(20)]],
	device const void *arenaRaw [[buffer(16)]], device const Section *sections [[buffer(17)]], device const Rec *recs [[buffer(18)]],
	device const uint *lists [[buffer(19)]], constant Projection &proj [[buffer(21)]], constant Globals &g [[buffer(22)]],
	constant Terrain &t [[buffer(23)]], device const uint *qlist [[buffer(25)]], texture2d<float> lightmap [[texture(17)]],
	sampler lightSampler [[sampler(15)]],
	device uint *oracleKept [[buffer(26), function_constant(F_ORACLE)]], constant uint &oracleFrame [[buffer(27), function_constant(F_ORACLE)]],
	constant Fog &fogV [[buffer(24), function_constant(F_FOGVERT_ANY)]], constant uint4 &grp [[buffer(29), function_constant(F_GROUP)]]
#ifdef OWN_VCOUNT
	, device atomic_uint *vcount [[buffer(28)]]
#endif
	) {
	VOut o;
	uint quadStart, meta, base;
#ifdef OWN_TPROBE
	uint tOff = 0;
#endif
	uint qv = QUADS && F_NONIDX ? qlist[vid / 6] * 4 + ((0x032210u >> ((vid % 6) * 4)) & 3) : vid;
	int3 origin;
	int uploadMs;
	uint unitRec = 0xffffffffu;
	bool qtab = F_QFLAT && (df.listBase >> 31) == 0;
	uint lb = df.listBase & 0x7FFFFFFFu;
	uint qslot = (vid >> 2) + iid * 64;
	uint qe = qtab ? lists[lb + (qslot >> A1_SH)] : 0u;
	uint gUnit = F_GROUP ? ((vid >> 2) * grp.y) >> 21 : 0u, gQuad = F_GROUP ? (vid >> 2) - gUnit * grp.x : 0u;
	if (F_ORACLE && !QUADS && !FAT && !qtab) unitRec = lists[lb + iid];
	if (QUADS) {
		Section s = sections[((device const uint *) lists)[qv >> 2]];
		quadStart = 0;
		meta = 63;
		origin = int3(s.x, s.y, s.z);
		uploadMs = s.uploadMs;
		base = 0;
	} else if (F_TIGHT) {
		uint4 r4 = ((device const uint4 *) recs)[lists[df.listBase + iid] * 2];
		uint4 s4 = ((device const uint4 *) sections)[r4.y * 2];
		quadStart = r4.x;
		meta = r4.z;
		origin = int3(as_type<int>(s4.x), as_type<int>(s4.y), as_type<int>(s4.z));
		uploadMs = as_type<int>(s4.w);
		base = r4.w;
	} else if (FAT) {
		Inst e = ((device const Inst *) lists)[df.listBase + iid];
		quadStart = e.quadStart;
		meta = e.meta;
		origin = int3(e.x, e.y, e.z);
		uploadMs = e.uploadMs;
		base = e.pad0;
	} else {
#ifdef OWN_TFLAT
		uint le = F_GROUP ? lists[lb + (iid << grp.z) + gUnit] : qtab || TRANSLUCENT ? 0u : lists[lb + iid];
		if (F_GROUP && le == 0xFFFFFFFFu) {
			o.pos = float4(0, 0, -2, 1);
			o.sphericalVertexDistance = 0;
			putColor(o, float4(0));
			o.texCoord0 = float2(0);
			putRich(o, 0, 1);
			return o;
		}
		Rec rec = recs[qtab ? (qe == 0xFFFFFFFFu ? 0u : qe >> 6) : TRANSLUCENT ? lists[lb + (vid >> 2)] >> 6 : le];
#else
		uint le = F_GROUP ? lists[lb + (iid << grp.z) + gUnit] : qtab ? 0u : lists[lb + iid];
		if (F_GROUP && le == 0xFFFFFFFFu) {
			o.pos = float4(0, 0, -2, 1);
			o.sphericalVertexDistance = 0;
			putColor(o, float4(0));
			o.texCoord0 = float2(0);
			putRich(o, 0, 1);
			return o;
		}
		Rec rec = recs[qtab ? (qe == 0xFFFFFFFFu ? 0u : qe >> 6) : le];
#endif
		Section s = sections[rec.slot];
		quadStart = rec.quadStart;
		meta = rec.meta;
		origin = int3(s.x, s.y, s.z);
		uploadMs = s.uploadMs;
		base = uint(rec.plane);
#ifdef OWN_TPROBE
		tOff = rec.pad1;
#endif
	}
	uint quad = QUADS ? 0 : F_NONIDX ? vid / 6 : qtab ? (qe == 0xFFFFFFFFu ? 64u : (qe & 63) << A1_SH | (qslot & ((1u << A1_SH) - 1))) : F_GROUP ? gQuad : vid >> 2;
#ifdef OWN_TFLAT
	if (TRANSLUCENT && !FAT && !F_TIGHT) quad = lists[df.listBase + (vid >> 2)] & 63;
#endif
	uint corner = F_NONIDX ? (0x032210u >> ((vid % 6) * 4)) & 3 : vid & 3;
#ifdef OWN_VCOUNT
	{
		uint k = (QUADS ? 16u : F_NONIDX ? 24u : TRANSLUCENT ? 8u : 0u);
		bool pad = !QUADS && quad > (meta & 63);
		atomic_fetch_add_explicit(&vcount[k], 1, memory_order_relaxed);
		if (corner == 1) atomic_fetch_add_explicit(&vcount[k + 1], 1, memory_order_relaxed);
		if (pad) atomic_fetch_add_explicit(&vcount[k + 2], 1, memory_order_relaxed);
		if (pad && corner == 1) atomic_fetch_add_explicit(&vcount[k + 3], 1, memory_order_relaxed);
		atomic_fetch_add_explicit(&vcount[k + 4 + (corner == 0 ? 0u : corner == 2 ? 1u : corner == 3 ? 2u : 3u)], 1, memory_order_relaxed);
	}
#endif
	if (quad > (meta & 63)) {
		o.pos = float4(0, 0, -2, 1);
		if (F_ORACLE) o.oracleUnit = unitRec;
		if (F_TIGHT || F_POSONLY) return o;
		o.sphericalVertexDistance = 0;
		putColor(o, float4(0));
		o.texCoord0 = float2(0);
		putRich(o, 0, 1);
		return o;
	}
	if (F_REVERSE) quad = (meta & 63) - quad;
	uint vi = QUADS ? qv : TRANSLUCENT && (meta & (1u << 18)) == 0 ? (base + ((device const uint *) arenaRaw)[quadStart + quad]) * 4 + corner
		: (quadStart + quad) * 4 + corner;
#ifdef OWN_TPROBE
	if (OWN_TPROBE == 2 && TRANSLUCENT && !QUADS) vi = (base + tOff + quad) * 4 + corner;
#endif
	float3 rel;
	if (F_ORACLE) {
		VOut ov = ownVertex(vi, origin, uploadMs, df, arenaRaw, proj, g, t, lightmap, lightSampler, rel);
		ov.oracleUnit = unitRec;
		if (quad == 0 && corner == 0 && unitRec != 0xffffffffu) oracleKept[unitRec] = oracleFrame;
		return ov;
	}
	VOut ov = ownVertex(vi, origin, uploadMs, df, arenaRaw, proj, g, t, lightmap, lightSampler, rel);
	if (F_FV) {
		float fv = linearFogV(ov.sphericalVertexDistance, fogV.FogEnvironmentalStart, fogV.FogEnvironmentalEnd);
		if (F_RICH) fv = max(fv, linearFogV(ov.cylindricalVertexDistance, fogV.FogRenderDistanceStart, fogV.FogRenderDistanceEnd));
		ov.sphericalVertexDistance = fv;
	}
	if (F_FVT) {
		float3 so = float3(origin - int3(g.CameraBlockPos)) + float3(g.CameraOffset);
		float3 m = max(abs(so), abs(so + 16.0));
		float3 n = select(min(abs(so), abs(so + 16.0)), float3(0), so < 0.0 && so + 16.0 > 0.0);
		float cylMax = max(length(m.xz), m.y), cylMin = max(length(n.xz), n.y), sphMax = length(m);
		float rs = fogV.FogRenderDistanceStart, re = fogV.FogRenderDistanceEnd, es = fogV.FogEnvironmentalStart, ee = fogV.FogEnvironmentalEnd;
		bool a = cylMax + 1.0 < rs && sphMax < ee && es <= 0.0;
		bool b = cylMin - 1.0 >= rs && cylMax + 1.0 <= re && es <= 0.0 && sphMax < ee && (cylMin - 1.0 - rs) / (re - rs) >= sphMax / ee;
		bool c = cylMin - 1.0 >= re;
		ov.fogIn = a || b || c ? 1 : 0;
		ov.fogF = max(linearFogV(ov.sphericalVertexDistance, es, ee), linearFogV(ov.cylindricalVertexDistance, rs, re));
	}
	return ov;
}

static VOut ownVertex(uint vi, int3 origin, int uploadMs, constant DrawFrame &df, device const void *arenaRaw, constant Projection &proj,
	constant Globals &g, constant Terrain &t, texture2d<float> lightmap, sampler lightSampler, thread float3 &rel) {
	Vtx v;
	if (OWN_REC && COMPACT && !TRANSLUCENT) {
		v = ownQrec(arenaRaw, vi >> 2, vi & 3);
	} else if (COMPACT && F_TIGHT) {
		uint4 w = ((device const uint4 *) arenaRaw)[vi];
		v.pos = packed_float3(OWN_POS3(arenaRaw, ushort(w.x), ushort(w.x >> 16), ushort(w.y)));
		v.color = as_type<uchar4>(w.z);
		v.uv0 = packed_float2(float2(ushort(w.w), ushort(w.w >> 16)) / 65536.0);
		v.uv2 = short2(uchar(w.y >> 16), uchar(w.y >> 24));
	} else if (COMPACT) {
		CVtx c = ((device const CVtx *) arenaRaw)[vi];
		v.pos = packed_float3(OWN_POS3(arenaRaw, c.px, c.py, c.pz));
		v.color = c.color;
		v.uv0 = packed_float2(float2(c.u, c.v) / 65536.0);
		v.uv2 = short2(c.lu, c.lv);
	} else {
		v = ((device const Vtx *) arenaRaw)[vi];
	}
	return ownShade(v, origin, uploadMs, df, proj, g, t, lightmap, lightSampler, rel);
}

static VOut ownShade(Vtx v, int3 origin, int uploadMs, constant DrawFrame &df, constant Projection &proj, constant Globals &g,
	constant Terrain &t, texture2d<float> lightmap, sampler lightSampler, thread float3 &rel) {
	VOut o;
	rel = float3(v.pos);
	int3 cbp = int3(g.CameraBlockPos);
	float3 pos = float3(v.pos) + float3(origin - cbp) + float3(g.CameraOffset);
	if (CPU_CLIP) o.pos = df.clip * float4(pos, 1.0);
	else o.pos = (proj.ProjMat * t.ModelViewMat) * float4(pos, 1.0);
	o.pos.y = -o.pos.y;
	if (F_POSONLY) return o;
#ifdef OWN_TPROBE
	if (TRANSLUCENT && (OWN_TPROBE == 1 || OWN_TPROBE == 4)) {
		o.sphericalVertexDistance = 0;
		putColor(o, float4(0.6, 0.7, 1.0, 0.6));
		o.texCoord0 = OWN_TPROBE == 1 ? float2(0) : float2(v.uv0);
		putRich(o, 0, 1);
		return o;
	}
#endif
	if (VS_MODE == 2) {
		o.sphericalVertexDistance = 0;
		putColor(o, float4(1));
		o.texCoord0 = float2(0);
		putRich(o, 0, 1);
		return o;
	}
	o.sphericalVertexDistance = length(pos);
#ifdef MCOPT_SHADE
	o.shadePos = pos;
	o.shadeLight = half2(saturate(float2(int2(v.uv2)) / 240.0));
	putColor(o, float4(v.color) / 255.0);
#else
	float2 luv = clamp(float2(int2(v.uv2)) / 256.0 + 0.5 / 16.0, float2(0.5 / 16.0), float2(15.5 / 16.0));
	putColor(o, VS_MODE == 1 ? float4(v.color) / 255.0 : float4(v.color) / 255.0 * lightmap.sample(lightSampler, luv, level(0.0)));
#endif
	o.texCoord0 = float2(v.uv0);
#ifdef OWN_TPROBE
	if (TRANSLUCENT && OWN_TPROBE == 5) {
		putRich(o, 0, 1);
		return o;
	}
#endif
	if (F_RICH) {
		int elapsed = df.nowMs - uploadMs;
		float visibility = elapsed >= df.fadeMs ? 1.0 : float(elapsed) / float(df.fadeMs);
		const float chunkFullyVisibleRange = 16.0;
		float dist = length(pos);
		putRich(o, max(length(pos.xz), abs(pos.y)), mix(1.0, visibility, clamp((dist - chunkFullyVisibleRange) / chunkFullyVisibleRange, 0.0, 1.0)));
	}
	return o;
}

using OwnMeshT = metal::mesh<VOut, void, 128, 64, metal::topology::triangle>;

struct FragOcc {
	uint2 screen;
	uint2 hiz0;
	uint mips;
	uint stats;
	uint slot;
	uint pad;
};

static float4 ownClip(uint vi, int3 origin, constant DrawFrame &df, device const void *arenaRaw, constant Projection &proj, constant Globals &g,
	constant Terrain &t, thread float3 &rel) {
	float3 p;
	if (OWN_REC && COMPACT) {
		p = ownQrecPos(arenaRaw, vi >> 2, vi & 3);
	} else if (COMPACT) {
		CVtx c = ((device const CVtx *) arenaRaw)[vi];
		p = OWN_POS3(arenaRaw, c.px, c.py, c.pz);
	} else {
		p = float3(((device const Vtx *) arenaRaw)[vi].pos);
	}
	rel = p;
	int3 cbp = int3(g.CameraBlockPos);
	float3 pos = p + float3(origin - cbp) + float3(g.CameraOffset);
	float4 o = CPU_CLIP ? df.clip * float4(pos, 1.0) : (proj.ProjMat * t.ModelViewMat) * float4(pos, 1.0);
	o.y = -o.y;
	return o;
}

static bool fragOccVisible(float4 p0, float4 p1, float4 p2, float4 p3, constant FragOcc &fo, texture2d<float, access::read> hiz) {
	if (fo.mips == 0) return true;
	float4 p[4] = {p0, p1, p2, p3};
	float2 mn = float2(1e30), mx = float2(-1e30);
	float zmax = 0;
	for (uint c = 0; c < 4; c++) {
		if (p[c].w <= 0.05) return true;
		float3 n = p[c].xyz / p[c].w;
		n.y = -n.y;
		mn = min(mn, n.xy);
		mx = max(mx, n.xy);
		zmax = max(zmax, n.z);
	}
	if (mx.x < -1 || mx.y < -1 || mn.x > 1 || mn.y > 1) return true;
	float2 smin = clamp((mn * 0.5 + 0.5) * float2(fo.screen), float2(0), float2(fo.screen) - 1);
	float2 smax = clamp((mx * 0.5 + 0.5) * float2(fo.screen), float2(0), float2(fo.screen) - 1);
	uint2 a = uint2(smin) / 2, b = uint2(smax) / 2;
	uint ext = max(b.x - a.x, b.y - a.y);
	uint level = ext == 0 ? 0 : min(fo.mips - 1, 32 - clz(ext));
	uint2 lsize = uint2(max(1u, fo.hiz0.x >> level), max(1u, fo.hiz0.y >> level));
	uint2 la = min(a >> level, lsize - 1), lb = min(b >> level, lsize - 1);
	float far = 1.0;
	for (uint y = la.y; y <= lb.y; y++)
		for (uint x = la.x; x <= lb.x; x++) far = min(far, hiz.read(uint2(x, y), level).x);
	return zmax >= far - 1e-6;
}

static bool fragOccBox(float3 lo, float3 hi, constant DrawFrame &df, constant Projection &proj, constant Terrain &t, constant FragOcc &fo,
	texture2d<float, access::read> hiz) {
	if (fo.mips == 0) return true;
	float4x4 m = CPU_CLIP ? df.clip : proj.ProjMat * t.ModelViewMat;
	float2 mn = float2(1e30), mx = float2(-1e30);
	float zmax = 0;
	for (uint c = 0; c < 8; c++) {
		float4 h = m * float4(select(lo, hi, bool3((c & 1) != 0, (c & 2) != 0, (c & 4) != 0)), 1.0);
		if (h.w <= 0.05) return true;
		float3 n = h.xyz / h.w;
		mn = min(mn, n.xy);
		mx = max(mx, n.xy);
		zmax = max(zmax, n.z);
	}
	if (mx.x < -1 || mx.y < -1 || mn.x > 1 || mn.y > 1) return false;
	float2 smin = clamp((mn * 0.5 + 0.5) * float2(fo.screen), float2(0), float2(fo.screen) - 1);
	float2 smax = clamp((mx * 0.5 + 0.5) * float2(fo.screen), float2(0), float2(fo.screen) - 1);
	uint2 a = uint2(smin) / 2, b = uint2(smax) / 2;
	uint ext = max(b.x - a.x, b.y - a.y);
	uint level = ext == 0 ? 0 : min(fo.mips - 1, 32 - clz(ext));
	uint2 lsize = uint2(max(1u, fo.hiz0.x >> level), max(1u, fo.hiz0.y >> level));
	uint2 la = min(a >> level, lsize - 1), lb = min(b >> level, lsize - 1);
	float far = 1.0;
	for (uint y = la.y; y <= lb.y; y++)
		for (uint x = la.x; x <= lb.x; x++) far = min(far, hiz.read(uint2(x, y), level).x);
	return zmax >= far - 1e-6;
}

[[mesh]] void own_mesh(OwnMeshT out, uint tg [[threadgroup_position_in_grid]], uint q [[thread_index_in_threadgroup]],
	constant DrawFrame &df [[buffer(20)]], device const void *arenaRaw [[buffer(16)]], device const Section *sections [[buffer(17)]],
	device const Rec *recs [[buffer(18)]], device const uint *lists [[buffer(19)]], constant Projection &proj [[buffer(21)]],
	constant Globals &g [[buffer(22)]], constant Terrain &t [[buffer(23)]], texture2d<float> lightmap [[texture(17)]],
	sampler lightSampler [[sampler(15)]], device uint *masks [[buffer(26), function_constant(F_OCC)]],
	constant FragOcc &fo [[buffer(27), function_constant(F_OCC)]], device atomic_uint *occStats [[buffer(28), function_constant(F_OCC)]],
	texture2d<float, access::read> hiz [[texture(18), function_constant(F_OCCB)]]) {
	uint r = lists[df.listBase + tg];
	Rec rec = recs[r];
	Section s = sections[rec.slot];
	int3 origin = int3(s.x, s.y, s.z);
	bool keep = q <= (rec.meta & 63);
	VOut v[4];
	if (F_OCCB) {
		float3 o = float3(origin - int3(g.CameraBlockPos)) + float3(g.CameraOffset);
		float3 lo = o + float3(int3(rec.lo & 255, (rec.lo >> 8) & 255, (rec.lo >> 16) & 255) - 16);
		float3 hi = o + float3(int3(rec.hi & 255, (rec.hi >> 8) & 255, (rec.hi >> 16) & 255) - 16);
		if (!fragOccBox(lo, hi, df, proj, t, fo, hiz)) {
			if (q == 0) {
				masks[r] = 0;
				out.set_primitive_count(0);
			}
			return;
		}
	}
	if (F_OCC) {
		uint mask = masks[r];
		bool drawnA = ((mask >> q) & 1) != 0;
		if (F_OCCA) keep = keep && drawnA;
		bool vis = false;
		if (keep) {
			float3 rel[4];
			float4 p[4];
			for (uint c = 0; c < 4; c++) p[c] = ownClip((rec.quadStart + q) * 4 + c, origin, df, arenaRaw, proj, g, t, rel[c]);
			bool gone = (p[0].x > p[0].w && p[1].x > p[1].w && p[2].x > p[2].w && p[3].x > p[3].w)
				|| (p[0].x < -p[0].w && p[1].x < -p[1].w && p[2].x < -p[2].w && p[3].x < -p[3].w)
				|| (p[0].y > p[0].w && p[1].y > p[1].w && p[2].y > p[2].w && p[3].y > p[3].w)
				|| (p[0].y < -p[0].w && p[1].y < -p[1].w && p[2].y < -p[2].w && p[3].y < -p[3].w);
			uint bucket = (rec.meta >> 6) & 7;
			if (bucket < 6) {
				float3 cam = float3(int3(g.CameraBlockPos) - origin) - float3(g.CameraOffset);
				uint axis = bucket >> 1;
				float plane = rel[0][axis];
				gone = gone || ((bucket & 1) == 0 ? cam[axis] <= plane - FACE_MARGIN : cam[axis] >= plane + FACE_MARGIN);
			}
			vis = !gone && (F_OCCA || fragOccVisible(p[0], p[1], p[2], p[3], fo, hiz));
		}
		keep = F_OCCA ? vis : vis && !drawnA;
		if (F_OCCB) {
			uint now = uint(static_cast<simd_vote::vote_t>(simd_ballot(vis)));
			if (q == 0) masks[r] = now;
		}
		if (keep) {
			float3 rel;
			for (uint c = 0; c < 4; c++) v[c] = ownVertex((rec.quadStart + q) * 4 + c, origin, s.uploadMs, df, arenaRaw, proj, g, t, lightmap, lightSampler, rel);
		}
	} else if (keep) {
		float3 rel[4];
		for (uint c = 0; c < 4; c++) v[c] = ownVertex((rec.quadStart + q) * 4 + c, origin, s.uploadMs, df, arenaRaw, proj, g, t, lightmap, lightSampler, rel[c]);
		float4 p0 = v[0].pos, p1 = v[1].pos, p2 = v[2].pos, p3 = v[3].pos;
		bool gone = (p0.x > p0.w && p1.x > p1.w && p2.x > p2.w && p3.x > p3.w) || (p0.x < -p0.w && p1.x < -p1.w && p2.x < -p2.w && p3.x < -p3.w)
			|| (p0.y > p0.w && p1.y > p1.w && p2.y > p2.w && p3.y > p3.w) || (p0.y < -p0.w && p1.y < -p1.w && p2.y < -p2.w && p3.y < -p3.w);
		uint bucket = (rec.meta >> 6) & 7;
		if (bucket < 6) {
			float3 cam = float3(int3(g.CameraBlockPos) - origin) - float3(g.CameraOffset);
			uint axis = bucket >> 1;
			float plane = rel[0][axis];
			gone = gone || ((bucket & 1) == 0 ? cam[axis] <= plane - FACE_MARGIN : cam[axis] >= plane + FACE_MARGIN);
		}
		keep = !gone;
	}
	uint at = simd_prefix_exclusive_sum(keep ? 1u : 0u);
	uint kept = simd_sum(keep ? 1u : 0u);
	if (F_REVERSE) at = kept - 1 - at;
	if (q == 0) out.set_primitive_count(kept * 2);
	if (F_OCC && q == 0 && fo.stats != 0) atomic_fetch_add_explicit(&occStats[fo.slot], kept, memory_order_relaxed);
	if (keep) {
		for (uint c = 0; c < 4; c++) out.set_vertex(at * 4 + c, v[c]);
		out.set_index(at * 6 + 0, at * 4 + 0);
		out.set_index(at * 6 + 1, at * 4 + 1);
		out.set_index(at * 6 + 2, at * 4 + 2);
		out.set_index(at * 6 + 3, at * 4 + 2);
		out.set_index(at * 6 + 4, at * 4 + 3);
		out.set_index(at * 6 + 5, at * 4 + 0);
	}
}

[[mesh]] void own_mesh4(OwnMeshT out, uint tg [[threadgroup_position_in_grid]], uint t [[thread_index_in_threadgroup]],
	uint lane [[thread_index_in_simdgroup]], uint sg [[simdgroup_index_in_threadgroup]],
	constant DrawFrame &df [[buffer(20)]], device const void *arenaRaw [[buffer(16)]], device const Section *sections [[buffer(17)]],
	device const Rec *recs [[buffer(18)]], device const uint *lists [[buffer(19)]], constant Projection &proj [[buffer(21)]],
	constant Globals &g [[buffer(22)]], constant Terrain &t2 [[buffer(23)]], texture2d<float> lightmap [[texture(17)]],
	sampler lightSampler [[sampler(15)]]) {
	threadgroup uint counts[4];
	Rec rec = recs[lists[df.listBase + tg]];
	Section s = sections[rec.slot];
	int3 origin = int3(s.x, s.y, s.z);
	uint q = t >> 2, c = t & 3;
	bool live = q <= (rec.meta & 63);
	VOut v;
	float3 rel = float3(0);
	if (live) v = ownVertex((rec.quadStart + q) * 4 + c, origin, s.uploadMs, df, arenaRaw, proj, g, t2, lightmap, lightSampler, rel);
	float4 p = live ? v.pos : float4(0, 0, 0, 1);
	ushort l0 = ushort(lane & ~3u);
	float4 p0 = simd_shuffle(p, l0), p1 = simd_shuffle(p, ushort(l0 + 1)), p2 = simd_shuffle(p, ushort(l0 + 2)), p3 = simd_shuffle(p, ushort(l0 + 3));
	float3 r0 = simd_shuffle(rel, l0);
	bool gone = (p0.x > p0.w && p1.x > p1.w && p2.x > p2.w && p3.x > p3.w) || (p0.x < -p0.w && p1.x < -p1.w && p2.x < -p2.w && p3.x < -p3.w)
		|| (p0.y > p0.w && p1.y > p1.w && p2.y > p2.w && p3.y > p3.w) || (p0.y < -p0.w && p1.y < -p1.w && p2.y < -p2.w && p3.y < -p3.w);
	uint bucket = (rec.meta >> 6) & 7;
	if (bucket < 6) {
		float3 cam = float3(int3(g.CameraBlockPos) - origin) - float3(g.CameraOffset);
		uint axis = bucket >> 1;
		gone = gone || ((bucket & 1) == 0 ? cam[axis] <= r0[axis] - FACE_MARGIN : cam[axis] >= r0[axis] + FACE_MARGIN);
	}
	bool keep = live && !gone;
	uint vote = keep && c == 0 ? 1u : 0u;
	uint pre = simd_prefix_exclusive_sum(vote), tot = simd_sum(vote);
	if (lane == 0) counts[sg] = tot;
	threadgroup_barrier(mem_flags::mem_threadgroup);
	uint base = 0, kept = 0;
	for (uint i = 0; i < 4; i++) {
		base += i < sg ? counts[i] : 0;
		kept += counts[i];
	}
	uint at = base + simd_shuffle(pre, l0);
	if (F_REVERSE) at = kept - 1 - at;
	if (t == 0) out.set_primitive_count(kept * 2);
	if (keep) {
		out.set_vertex(at * 4 + c, v);
		if (c == 0) {
			out.set_index(at * 6 + 0, at * 4 + 0);
			out.set_index(at * 6 + 1, at * 4 + 1);
			out.set_index(at * 6 + 2, at * 4 + 2);
			out.set_index(at * 6 + 3, at * 4 + 2);
			out.set_index(at * 6 + 4, at * 4 + 3);
			out.set_index(at * 6 + 5, at * 4 + 0);
		}
	}
}

constant bool SAMPLE_LEVEL [[function_constant(6)]];

static float4 sampleNearestG(texture2d<float> source, sampler s, float2 uv, float2 pixelSize, float2 du, float2 dv, float2 texelScreenSize) {
	float2 uvTexelCoords = uv / pixelSize;
	float2 texelCenter = round(uvTexelCoords) - 0.5f;
	float2 texelOffset = uvTexelCoords - texelCenter;
	texelOffset = (texelOffset - 0.5f) * pixelSize / texelScreenSize + 0.5f;
	texelOffset = clamp(texelOffset, 0.0f, 1.0f);
	uv = (texelCenter + texelOffset) * pixelSize;
	if (SAMPLE_LEVEL) {
		float2 size = float2(source.get_width(), source.get_height());
		float rho = max(length(du * size), length(dv * size));
		return source.sample(s, uv, level(max(0.0f, log2(rho))));
	}
	return source.sample(s, uv, gradient2d(du, dv));
}

static float4 sampleNearest(texture2d<float> source, sampler s, float2 uv, float2 pixelSize) {
	float2 du = dfdx(uv);
	float2 dv = dfdy(uv);
	float2 texelScreenSize = sqrt(du * du + dv * dv);
	return sampleNearestG(source, s, uv, pixelSize, du, dv, texelScreenSize);
}

static float4 sampleRGSS(texture2d<float> source, sampler s, float2 uv, float2 pixelSize) {
	float2 du = dfdx(uv);
	float2 dv = dfdy(uv);
	float2 texelScreenSize = sqrt(du * du + dv * dv);
	float maxTexelSize = max(texelScreenSize.x, texelScreenSize.y);
	float minPixelSize = min(pixelSize.x, pixelSize.y);
	float transitionStart = minPixelSize * 1.0;
	float transitionEnd = minPixelSize * 2.0;
	float blendFactor = smoothstep(transitionStart, transitionEnd, maxTexelSize);
	float duLength = length(du);
	float dvLength = length(dv);
	float minDerivative = min(duLength, dvLength);
	float maxDerivative = max(duLength, dvLength);
	float effectiveDerivative = sqrt(minDerivative * maxDerivative);
	float mipLevelExact = max(0.0, log2(effectiveDerivative / minPixelSize));
	float mipLevelLow = floor(mipLevelExact);
	float mipLevelHigh = mipLevelLow + 1.0;
	float mipBlend = fract(mipLevelExact);
	const float2 offsets[4] = {float2(0.125, 0.375), float2(-0.125, -0.375), float2(0.375, -0.125), float2(-0.375, 0.125)};
	float4 rgssColorLow = float4(0.0);
	float4 rgssColorHigh = float4(0.0);
	for (int i = 0; i < 4; ++i) {
		float2 sampleUV = uv + offsets[i] * pixelSize;
		rgssColorLow += source.sample(s, sampleUV, level(mipLevelLow));
		rgssColorHigh += source.sample(s, sampleUV, level(mipLevelHigh));
	}
	rgssColorLow *= 0.25;
	rgssColorHigh *= 0.25;
	float4 rgssColor = mix(rgssColorLow, rgssColorHigh, mipBlend);
	float4 nearestColor = sampleNearestG(source, s, uv, pixelSize, du, dv, texelScreenSize);
	return mix(nearestColor, rgssColor, blendFactor);
}

static float linear_fog_value(float vertexDistance, float fogStart, float fogEnd) {
	if (vertexDistance <= fogStart) return 0.0;
	else if (vertexDistance >= fogEnd) return 1.0;
	return (vertexDistance - fogStart) / (fogEnd - fogStart);
}

constant bool CUTOUT [[function_constant(0)]];
constant float CUTOUT_LIMIT [[function_constant(5)]];

#ifdef MCOPT_SHADE
#define OWN_FS_GLOBALS 28
#define OWN_FS_TERRAIN 29
#define OWN_FS_FOG 30
#define OWN_FS_ATLAS 26
constant bool SHADE_OWN_HDR_SET [[function_constant(40)]];
constant bool SHADE_OWN_HDR = is_function_constant_defined(SHADE_OWN_HDR_SET) && SHADE_OWN_HDR_SET;
#else
#define OWN_FS_GLOBALS 22
#define OWN_FS_TERRAIN 23
#define OWN_FS_FOG 24
#define OWN_FS_ATLAS 16
#endif

fragment float4 own_fs(VOut in [[stage_in]], constant Globals &g [[buffer(OWN_FS_GLOBALS)]], constant Terrain &t [[buffer(OWN_FS_TERRAIN)]],
	constant Fog &fog [[buffer(OWN_FS_FOG)]], texture2d<float> atlas [[texture(OWN_FS_ATLAS)]], sampler atlasSampler [[sampler(14)]]
#ifdef MCOPT_SHADE
	, constant ShadeLook &shadeLook [[buffer(17)]], texture2d<float> shadeLightmap [[texture(27)]], sampler shadeLightSampler [[sampler(15)]]
#endif
	) {
#ifdef OWN_TPROBE
	if ((OWN_TPROBE == 1 || OWN_TPROBE == 3) && CUTOUT && CUTOUT_LIMIT < 0.2) return float4(0.2, 0.3, 0.8, 0.5);
	if (OWN_TPROBE == 6 && CUTOUT && CUTOUT_LIMIT < 0.2) return atlas.sample(atlasSampler, in.texCoord0) * getColor(in);
	if (OWN_TPROBE == 7 && CUTOUT && CUTOUT_LIMIT < 0.2)
		return sampleNearest(atlas, atlasSampler, in.texCoord0, 1.0f / float2(t.TextureSize)) * getColor(in);
#endif
	float2 pixelSize = 1.0f / float2(t.TextureSize);
	float4 color = (g.UseRgss == 1 ? sampleRGSS(atlas, atlasSampler, in.texCoord0, pixelSize) : sampleNearest(atlas, atlasSampler, in.texCoord0, pixelSize))
		* getColor(in);
#ifdef MCOPT_SHADE
	if (F_VARY) color *= shadeLightmap.sample(shadeLightSampler, clamp(float2(in.shadeLight) * (240.0 / 256.0) + 0.5 / 16.0, float2(0.5 / 16.0), float2(15.5 / 16.0)), level(0.0));
#endif
	if (F_RICH) color = mix(fog.FogColor * float4(1, 1, 1, color.a), color, in.chunkVisibility);
	if (CUTOUT && color.a < CUTOUT_LIMIT) discard_fragment();
	float fogValue;
	if (F_FVT && in.fogIn != 0) {
		fogValue = in.fogF;
	} else {
		fogValue = F_FV ? in.sphericalVertexDistance : linear_fog_value(in.sphericalVertexDistance, fog.FogEnvironmentalStart, fog.FogEnvironmentalEnd);
		if (F_RICH && !F_FV) fogValue = max(fogValue, linear_fog_value(in.cylindricalVertexDistance, fog.FogRenderDistanceStart, fog.FogRenderDistanceEnd));
	}
#ifdef MCOPT_SHADE
	if (SHADE_OWN_HDR) return shadeLdrToHdr(float4(mix(color.rgb, fog.FogColor.rgb, fogValue * fog.FogColor.a), color.a), shadeLook);
#endif
	return float4(mix(color.rgb, fog.FogColor.rgb, fogValue * fog.FogColor.a), color.a);
}

[[mesh]] void own_mesh_q(OwnMeshT out, uint tg [[threadgroup_position_in_grid]], uint q [[thread_index_in_threadgroup]],
	constant DrawFrame &df [[buffer(20)]], device const void *arenaRaw [[buffer(16)]], device const Section *sections [[buffer(17)]],
	device const uint *qa [[buffer(18)]], device const uint *owner [[buffer(19)]], device const uint *qIdx [[buffer(25)]],
	constant Projection &proj [[buffer(21)]], constant Globals &g [[buffer(22)]], constant Terrain &t [[buffer(23)]],
	texture2d<float> lightmap [[texture(17)]], sampler lightSampler [[sampler(15)]]) {
	uint list = df.listBase;
	uint n = qa[list * 5] / 6, start = qa[list * 5 + 2];
	uint k = tg * 32 + q;
	bool keep = k < n;
	VOut v[4];
	if (keep) {
		uint quad = qIdx[start + k * 6] >> 2;
		Section s = sections[owner[quad]];
		int3 origin = int3(s.x, s.y, s.z);
		float3 rel;
		for (uint c = 0; c < 4; c++) v[c] = ownVertex(quad * 4 + c, origin, s.uploadMs, df, arenaRaw, proj, g, t, lightmap, lightSampler, rel);
		float4 p0 = v[0].pos, p1 = v[1].pos, p2 = v[2].pos, p3 = v[3].pos;
		keep = !((p0.x > p0.w && p1.x > p1.w && p2.x > p2.w && p3.x > p3.w) || (p0.x < -p0.w && p1.x < -p1.w && p2.x < -p2.w && p3.x < -p3.w)
			|| (p0.y > p0.w && p1.y > p1.w && p2.y > p2.w && p3.y > p3.w) || (p0.y < -p0.w && p1.y < -p1.w && p2.y < -p2.w && p3.y < -p3.w));
	}
	uint at = simd_prefix_exclusive_sum(keep ? 1u : 0u);
	uint kept = simd_sum(keep ? 1u : 0u);
	if (q == 0) out.set_primitive_count(kept * 2);
	if (keep) {
		for (uint c = 0; c < 4; c++) out.set_vertex(at * 4 + c, v[c]);
		out.set_index(at * 6 + 0, at * 4 + 0);
		out.set_index(at * 6 + 1, at * 4 + 1);
		out.set_index(at * 6 + 2, at * 4 + 2);
		out.set_index(at * 6 + 3, at * 4 + 2);
		out.set_index(at * 6 + 4, at * 4 + 3);
		out.set_index(at * 6 + 5, at * 4 + 0);
	}
}

[[early_fragment_tests]] fragment void own_fs_oracle(VOut in [[stage_in]], device uint *oracleVis [[buffer(26)]], constant uint &oracleFrame [[buffer(27)]]) {
	if (F_ORACLE && in.oracleUnit != 0xffffffffu) oracleVis[in.oracleUnit] = oracleFrame;
}

fragment float4 own_fs_pos(VOut in [[stage_in]]) {
	return float4(1);
}

fragment float4 own_fs_tex(VOut in [[stage_in]], constant Globals &g [[buffer(OWN_FS_GLOBALS)]], constant Terrain &t [[buffer(OWN_FS_TERRAIN)]],
	texture2d<float> atlas [[texture(OWN_FS_ATLAS)]], sampler atlasSampler [[sampler(14)]]) {
	float2 pixelSize = 1.0f / float2(t.TextureSize);
	return (g.UseRgss == 1 ? sampleRGSS(atlas, atlasSampler, in.texCoord0, pixelSize) : sampleNearest(atlas, atlasSampler, in.texCoord0, pixelSize))
		* getColor(in);
}

fragment float4 own_fs_nofade(VOut in [[stage_in]], constant Globals &g [[buffer(OWN_FS_GLOBALS)]], constant Terrain &t [[buffer(OWN_FS_TERRAIN)]], constant Fog &fog [[buffer(OWN_FS_FOG)]],
	texture2d<float> atlas [[texture(OWN_FS_ATLAS)]], sampler atlasSampler [[sampler(14)]]) {
	float2 pixelSize = 1.0f / float2(t.TextureSize);
	float4 color = (g.UseRgss == 1 ? sampleRGSS(atlas, atlasSampler, in.texCoord0, pixelSize) : sampleNearest(atlas, atlasSampler, in.texCoord0, pixelSize))
		* getColor(in);
	float fogValue = linear_fog_value(in.sphericalVertexDistance, fog.FogEnvironmentalStart, fog.FogEnvironmentalEnd);
	if (F_RICH) fogValue = max(fogValue, linear_fog_value(in.cylindricalVertexDistance, fog.FogRenderDistanceStart, fog.FogRenderDistanceEnd));
	return float4(mix(color.rgb, fog.FogColor.rgb, fogValue * fog.FogColor.a), color.a);
}

fragment float4 own_fs_nofog(VOut in [[stage_in]], constant Globals &g [[buffer(OWN_FS_GLOBALS)]], constant Terrain &t [[buffer(OWN_FS_TERRAIN)]], constant Fog &fog [[buffer(OWN_FS_FOG)]],
	texture2d<float> atlas [[texture(OWN_FS_ATLAS)]], sampler atlasSampler [[sampler(14)]]) {
	float2 pixelSize = 1.0f / float2(t.TextureSize);
	float4 color = (g.UseRgss == 1 ? sampleRGSS(atlas, atlasSampler, in.texCoord0, pixelSize) : sampleNearest(atlas, atlasSampler, in.texCoord0, pixelSize))
		* getColor(in);
	if (F_RICH) color = mix(fog.FogColor * float4(1, 1, 1, color.a), color, in.chunkVisibility);
	return color;
}

fragment float4 own_fs_flat(VOut in [[stage_in]]) {
	return getColor(in);
}

fragment void own_fs_alpha(VOut in [[stage_in]], constant Globals &g [[buffer(OWN_FS_GLOBALS)]], constant Terrain &t [[buffer(OWN_FS_TERRAIN)]], constant Fog &fog [[buffer(OWN_FS_FOG)]],
	texture2d<float> atlas [[texture(OWN_FS_ATLAS)]], sampler atlasSampler [[sampler(14)]]) {
	float2 pixelSize = 1.0f / float2(t.TextureSize);
	float a = (g.UseRgss == 1 ? sampleRGSS(atlas, atlasSampler, in.texCoord0, pixelSize) : sampleNearest(atlas, atlasSampler, in.texCoord0, pixelSize)).a
		* getColor(in).a;
	if (F_RICH) a = mix(fog.FogColor.a * a, a, in.chunkVisibility);
	if (a < CUTOUT_LIMIT) discard_fragment();
}

struct QuadFrame {
	float4x4 clip;
	int4 camBlock;
	float4 camOffset;
	uint2 screen;
	uint2 hiz0;
	uint mips;
	uint qcap;
	uint run;
	uint compact;
	uint listCap;
	uint frame;
	uint hizScale;
	uint coarse;
	uint unitStagger;
	uint units;
	uint qlist;
	uint box;
	uint debug;
	uint flat;
	uint centre;
	uint qmesh;
	uint gate;
	uint alu;
	uint fatT;
	uint lazy;
};

kernel void own_q_reset(constant QuadFrame &f [[buffer(0)]], device uint *qa [[buffer(11)]], uint i [[thread_position_in_grid]]) {
	if (i >= 4) return;
	device uint *a = qa + i * 5;
	a[0] = 0;
	a[1] = 1;
	a[2] = 0;
	a[3] = 0;
	a[4] = 0;
	if (i == 0) {
		qa[20] = 0;
		qa[21] = 1;
		qa[22] = 1;
		qa[24] = 0;
		qa[25] = 1;
		qa[26] = 1;
		qa[28] = 0;
		qa[29] = 0;
		qa[30] = 1;
		qa[31] = 1;
	}
}

kernel void own_q_size(constant QuadFrame &f [[buffer(0)]], device const uint *args [[buffer(3)]], device uint *qa [[buffer(11)]],
	uint i [[thread_position_in_grid]]) {
	if (i != 0) return;
	uint t = min(args[ARGS_T], f.listCap);
	qa[20] = (t * f.run + 63) / 64;
	qa[24] = (t + 63) / 64;
}

static void putQuads(device uint *idx, device atomic_uint *qa, uint phase, bool want, uint layer, uint quad, uint qcap, uint qlist) {
	for (uint l = 0; l < 2; l++) {
		uint mine = want && layer == l ? 6u : 0u;
		uint total = simd_sum(mine);
		if (total == 0) continue;
		uint before = simd_prefix_exclusive_sum(mine);
		uint base = 0;
		if (simd_is_first()) base = atomic_fetch_add_explicit(&qa[(phase * 2 + l) * 5], total, memory_order_relaxed);
		base = simd_broadcast_first(base);
		if (mine == 0) continue;
		uint at = base + before;
		if (at + 6 > qcap) continue;
		uint pos = l == 0 ? at : qcap - at - 6;
		if (qlist != 0) {
			idx[(phase * qcap + pos) / 6] = quad;
			continue;
		}
		device uint *o = idx + phase * qcap + pos;
		uint b = quad * 4;
		o[0] = b;
		o[1] = b + 1;
		o[2] = b + 2;
		o[3] = b + 2;
		o[4] = b + 3;
		o[5] = b;
	}
}

kernel void own_q_a(constant QuadFrame &f [[buffer(0)]], device const Rec *recs [[buffer(2)]], device const uint *args [[buffer(3)]],
	device const uint *tested [[buffer(9)]], device const uint *prevVis [[buffer(12)]], device uint *idx [[buffer(13)]],
	device atomic_uint *qa [[buffer(11)]], device const TUnit *tfat [[buffer(10)]], uint t [[thread_position_in_grid]]) {
	uint bits = 0, start = 0, layer = 0;
	if (t < min(args[ARGS_T], f.listCap)) {
		uint meta;
		if (f.fatT != 0) {
			TUnit u = tfat[t];
			start = u.quadStart;
			meta = u.meta;
		} else {
			Rec rec = recs[tested[t]];
			start = rec.quadStart;
			meta = rec.meta;
		}
		Rec rec;
		rec.meta = meta;
		layer = (rec.meta >> 9) & 1;
		uint count = (rec.meta & 63) + 1, w = start >> 5, sh = start & 31;
		ulong pair = ulong(prevVis[w]) | (sh + count > 32 ? ulong(prevVis[w + 1]) << 32 : 0ul);
		bits = uint(pair >> sh) & (count >= 32 ? 0xFFFFFFFFu : (1u << count) - 1u);
	}
	uint n = popcount(bits) * 6;
	for (uint l = 0; l < 2; l++) {
		uint mine = layer == l ? n : 0u;
		uint total = simd_sum(mine);
		if (total == 0) continue;
		uint before = simd_prefix_exclusive_sum(mine);
		uint base = 0;
		if (simd_is_first()) base = atomic_fetch_add_explicit(&qa[l * 5], total, memory_order_relaxed);
		base = simd_broadcast_first(base);
		if (mine == 0) continue;
		uint at = base + before;
		if (at + mine > f.qcap) continue;
		uint pos = l == 0 ? at : f.qcap - at - mine;
		if (f.qlist != 0) {
			device uint *e = idx + pos / 6;
			for (uint b = bits; b != 0; b &= b - 1) *e++ = start + ctz(b);
			continue;
		}
		device uint *o = idx + pos;
		for (uint b = bits; b != 0; b &= b - 1) {
			uint v = (start + ctz(b)) * 4;
			o[0] = v;
			o[1] = v + 1;
			o[2] = v + 2;
			o[3] = v + 2;
			o[4] = v + 3;
			o[5] = v;
			o += 6;
		}
	}
}

static float3 quadCorner(device const void *arena, uint compact, uint v) {
	if (OWN_REC) return ownQrecPos(arena, v >> 2, v & 3);
	if (compact != 0) {
		device const ushort *c = (device const ushort *) ((device const uchar *) arena + v * 16);
		return OWN_POS3(arena, c[0], c[1], c[2]);
	}
	device const float *p = (device const float *) ((device const uchar *) arena + v * 28);
	return float3(p[0], p[1], p[2]);
}

static bool holdsCentre(constant QuadFrame &f, float2 mn, float2 mx) {
	float2 pmin = (mn * 0.5 + 0.5) * float2(f.screen), pmax = (mx * 0.5 + 0.5) * float2(f.screen);
	return all(floor(pmax - 0.5 + 1e-3) >= ceil(pmin - 0.5 - 1e-3));
}

static bool quadTest(constant QuadFrame &f, device const void *arena, device const uint2 *boxes, texture2d<float, access::read> hiz, float3 o,
	uint quad) {
	bool visible = false, behind = false;
	float2 mn = float2(1e30), mx = float2(-1e30);
	float zmax = 0;
	if (f.box != 0) {
		uint2 b = boxes[quad];
		float3 lo = o + float3(b.x & 255, (b.x >> 8) & 255, (b.x >> 16) & 255) * 0.25 - 8.0;
		float3 ext = float3(b.y & 255, (b.y >> 8) & 255, (b.y >> 16) & 255) * 0.25 - 8.0 + o - lo;
		float4 h0 = f.clip * float4(lo, 1.0);
		float4 ex = f.clip[0] * ext.x, ey = f.clip[1] * ext.y, ez = f.clip[2] * ext.z;
		for (uint c = 0; c < 8; c++) {
			float4 h = h0 + ((c & 1) ? ex : 0.0) + ((c & 2) ? ey : 0.0) + ((c & 4) ? ez : 0.0);
			if (h.w <= 0.05) behind = true;
			float3 n = h.xyz / max(h.w, 1e-6);
			mn = min(mn, n.xy);
			mx = max(mx, n.xy);
			zmax = max(zmax, n.z);
		}
	} else {
		for (uint c = 0; c < 4; c++) {
			float4 h = f.clip * float4(o + quadCorner(arena, f.compact, quad * 4 + c), 1.0);
			if (h.w <= 0.05) behind = true;
			float3 n = h.xyz / max(h.w, 1e-6);
			mn = min(mn, n.xy);
			mx = max(mx, n.xy);
			zmax = max(zmax, n.z);
		}
	}
	if (behind) {
		visible = true;
	} else if (!(mx.x < -1 || mx.y < -1 || mn.x > 1 || mn.y > 1)) {
		float2 smin = clamp((mn * 0.5 + 0.5) * float2(f.screen), float2(0), float2(f.screen) - 1);
		float2 smax = clamp((mx * 0.5 + 0.5) * float2(f.screen), float2(0), float2(f.screen) - 1);
		uint2 a = uint2(smin) / f.hizScale, b = uint2(smax) / f.hizScale;
		uint ext = max(b.x - a.x, b.y - a.y);
		uint level = ext == 0 ? 0 : min(f.mips - 1, 32 - clz(ext));
		uint2 lsize = uint2(max(1u, f.hiz0.x >> level), max(1u, f.hiz0.y >> level));
		uint2 la = min(a >> level, lsize - 1), lb = min(b >> level, lsize - 1);
		float far = 1.0;
		for (uint y = la.y; y <= lb.y; y++)
			for (uint x = la.x; x <= lb.x; x++) far = min(far, hiz.read(uint2(x, y), level).x);
		visible = zmax >= far - 1e-6 && (f.centre == 0 || holdsCentre(f, mn, mx));
	}
	return visible;
}

kernel void own_q_b(constant QuadFrame &f [[buffer(0)]], device const Section *sections [[buffer(1)]], device const Rec *recs [[buffer(2)]],
	device const uint *args [[buffer(3)]], device const uint *tested [[buffer(9)]], device const uint *prevVis [[buffer(12)]],
	device uint *idx [[buffer(13)]], device atomic_uint *qa [[buffer(11)]], device atomic_uint *curVis [[buffer(14)]],
	device const void *arena [[buffer(15)]], device const uint2 *boxes [[buffer(17)]], texture2d<float, access::read> hiz [[texture(0)]],
	uint tid [[thread_position_in_grid]]) {
	uint t = tid / f.run, q = tid % f.run;
	bool want = false, vis = false;
	uint quad = 0, layer = 0;
	bool live = t < min(args[ARGS_T], f.listCap);
	Rec rec = recs[live ? tested[t] : 0];
	Section s = sections[rec.slot];
	float3 o = float3(int3(s.x, s.y, s.z) - f.camBlock.xyz) + f.camOffset.xyz;
	if (f.debug == 1) {
		if (live && o.x == 12345.0) atomic_store_explicit(&curVis[0], rec.quadStart, memory_order_relaxed);
		return;
	}
	bool unitHidden = false, unitDecides = false;
	if (f.run == 32) {
		float3 lo = o + float3(int3(rec.lo & 255, (rec.lo >> 8) & 255, (rec.lo >> 16) & 255) - 16);
		float3 hi = o + float3(int3(rec.hi & 255, (rec.hi >> 8) & 255, (rec.hi >> 16) & 255) - 16);
		uint c = q & 7;
		float4 h = f.clip * float4((c & 1) ? hi.x : lo.x, (c & 2) ? hi.y : lo.y, (c & 4) ? hi.z : lo.z, 1.0);
		bool near = h.w <= 0.05;
		float3 n = h.xyz / max(h.w, 1e-6);
		bool anyNear = simd_any(near && q < 8);
		float2 mn = float2(simd_min(q < 8 ? n.x : 1e30), simd_min(q < 8 ? n.y : 1e30));
		float2 mx = float2(simd_max(q < 8 ? n.x : -1e30), simd_max(q < 8 ? n.y : -1e30));
		float zmax = simd_max(q < 8 ? n.z : 0.0);
		if (!anyNear && !(mx.x < -1 || mx.y < -1 || mn.x > 1 || mn.y > 1)) {
			float2 smin = clamp((mn * 0.5 + 0.5) * float2(f.screen), float2(0), float2(f.screen) - 1);
			float2 smax = clamp((mx * 0.5 + 0.5) * float2(f.screen), float2(0), float2(f.screen) - 1);
			uint2 a = uint2(smin) / f.hizScale, b = uint2(smax) / f.hizScale;
			uint ext = max(b.x - a.x, b.y - a.y);
			uint level = ext == 0 ? 0 : min(f.mips - 1, 32 - clz(ext));
			uint2 lsize = uint2(max(1u, f.hiz0.x >> level), max(1u, f.hiz0.y >> level));
			uint2 la = min(a >> level, lsize - 1), lb = min(b >> level, lsize - 1);
			uint2 tx = uint2(la.x + (q & 1), la.y + ((q >> 1) & 1));
			float v = q < 4 && tx.x <= lb.x && tx.y <= lb.y ? hiz.read(tx, level).x : 1.0;
			float far = simd_min(v);
			unitHidden = zmax < far - 1e-6;
			unitDecides = f.coarse != 0 && ext <= f.coarse;
		}
	}
	if (f.debug == 2) {
		if (live && unitHidden && o.x == 12345.0) atomic_store_explicit(&curVis[0], rec.quadStart, memory_order_relaxed);
		return;
	}
	if (live && !unitHidden) {
		quad = rec.quadStart + q;
		layer = (rec.meta >> 9) & 1;
		bool was = (prevVis[quad >> 5] & (1u << (quad & 31))) != 0;
		uint stagger = f.unitStagger != 0 ? rec.quadStart >> 5 : quad;
		if (q <= (rec.meta & 63) && was && f.debug != 4 && ((stagger + f.frame) & 3) != 0) {
			vis = true;
		} else if (q <= (rec.meta & 63) && unitDecides) {
			vis = true;
			want = !was;
		} else if (q <= (rec.meta & 63)) {
			bool visible = quadTest(f, arena, boxes, hiz, o, quad);
			if (visible) {
				vis = true;
				want = !was;
			}
		}
	}
	uint word = quad >> 5, bit = vis ? 1u << (quad & 31) : 0u;
	bool pending = vis;
	for (uint i = 0; i < 32 && simd_any(pending); i++) {
		uint lead = simd_min(pending ? word : 0xFFFFFFFFu);
		uint bits = simd_or(pending && word == lead ? bit : 0u);
		if (simd_is_first() && bits != 0) atomic_fetch_or_explicit(&curVis[lead], bits, memory_order_relaxed);
		if (word == lead) pending = false;
	}
	if (f.debug == 3) {
		if (want && o.x == 12345.0) atomic_store_explicit(&curVis[0], quad, memory_order_relaxed);
		return;
	}
	putQuads(idx, qa, 1, want, layer, quad, f.qcap, f.qlist);
}

static void texelRect(constant QuadFrame &f, float2 mn, float2 mx, thread uint &level, thread uint2 &la, thread uint2 &lb) {
	float2 smin = clamp((mn * 0.5 + 0.5) * float2(f.screen), float2(0), float2(f.screen) - 1);
	float2 smax = clamp((mx * 0.5 + 0.5) * float2(f.screen), float2(0), float2(f.screen) - 1);
	uint2 a, b;
	if (f.alu != 0) {
		uint sh = ctz(f.hizScale);
		a = uint2(smin) >> sh;
		b = uint2(smax) >> sh;
	} else {
		a = uint2(smin) / f.hizScale;
		b = uint2(smax) / f.hizScale;
	}
	uint ext = max(b.x - a.x, b.y - a.y);
	level = ext == 0 ? 0 : min(f.mips - 1, 32 - clz(ext));
	uint2 lsize = uint2(max(1u, f.hiz0.x >> level), max(1u, f.hiz0.y >> level));
	la = min(a >> level, lsize - 1);
	lb = min(b >> level, lsize - 1);
}

kernel void own_q_bf(constant QuadFrame &f [[buffer(0)]], device const Section *sections [[buffer(1)]], device const Rec *recs [[buffer(2)]],
	device const uint *args [[buffer(3)]], device const uint *tested [[buffer(9)]], device const uint *prevVis [[buffer(12)]],
	device uint *idx [[buffer(13)]], device atomic_uint *qa [[buffer(11)]], device atomic_uint *curVis [[buffer(14)]],
	device const void *arena [[buffer(15)]], device const uint2 *boxes [[buffer(17)]], device const TUnit *tfat [[buffer(10)]],
	texture2d<float, access::read> hiz [[texture(0)]], uint tid [[thread_position_in_grid]]) {
	uint t = tid / f.run, q = tid % f.run;
	bool live = t < min(args[ARGS_T], f.listCap);
	Rec rec;
	int3 origin;
	if (f.fatT != 0) {
		TUnit u = tfat[live ? t : 0];
		rec.quadStart = u.quadStart;
		rec.meta = u.meta;
		rec.lo = u.lo;
		rec.hi = u.hi;
		origin = int3(u.x, u.y, u.z);
	} else {
		rec = recs[live ? tested[t] : 0];
	}
	uint quad = rec.quadStart + q;
	bool mine = live && q <= (rec.meta & 63);
	uint qi = mine ? quad : rec.quadStart;
	if (f.fatT == 0) {
		Section s = sections[rec.slot];
		origin = int3(s.x, s.y, s.z);
	}
	uint prev = prevVis[qi >> 5];
	uint2 b = boxes[qi];
	float3 o = float3(origin - f.camBlock.xyz) + f.camOffset.xyz;
	if (f.debug == 5) {
		if (live && o.x + float(prev) + float(b.x) == 12345.5) atomic_store_explicit(&curVis[0], quad, memory_order_relaxed);
		return;
	}
	bool was = (prev & (1u << (qi & 31))) != 0;
	uint stagger = f.unitStagger != 0 ? rec.quadStart >> 5 : quad;
	bool skip = was && f.debug != 4 && ((stagger + f.frame) & 3) != 0;
	bool vis = false, want = false;
	if (f.lazy != 0 && simd_all(!mine || skip)) {
		vis = mine;
	} else {
	float3 ulo = o + float3(int3(rec.lo & 255, (rec.lo >> 8) & 255, (rec.lo >> 16) & 255) - 16);
	float3 uhi = o + float3(int3(rec.hi & 255, (rec.hi >> 8) & 255, (rec.hi >> 16) & 255) - 16);
	uint c8 = q & 7;
	float4 uh = f.clip * float4((c8 & 1) ? uhi.x : ulo.x, (c8 & 2) ? uhi.y : ulo.y, (c8 & 4) ? uhi.z : ulo.z, 1.0);
	float3 un = uh.xyz / max(uh.w, 1e-6);
	bool uNear = simd_any(uh.w <= 0.05 && q < 8);
	float2 umn = float2(simd_min(q < 8 ? un.x : 1e30), simd_min(q < 8 ? un.y : 1e30));
	float2 umx = float2(simd_max(q < 8 ? un.x : -1e30), simd_max(q < 8 ? un.y : -1e30));
	float uz = simd_max(q < 8 ? un.z : 0.0);
	bool uOn = !uNear && !(umx.x < -1 || umx.y < -1 || umn.x > 1 || umn.y > 1);
	uint ul;
	uint2 ula, ulb;
	texelRect(f, umn, umx, ul, ula, ulb);
	float3 lo = o + float3(b.x & 255, (b.x >> 8) & 255, (b.x >> 16) & 255) * 0.25 - 8.0;
	float3 ext = float3(b.y & 255, (b.y >> 8) & 255, (b.y >> 16) & 255) * 0.25 - 8.0 + o - lo;
	float4 h0 = f.clip * float4(lo, 1.0);
	float4 ex = f.clip[0] * ext.x, ey = f.clip[1] * ext.y, ez = f.clip[2] * ext.z;
	bool behind = false;
	float2 mn = float2(1e30), mx = float2(-1e30);
	float zmax = 0;
	uint flatAxes = f.alu == 0 ? 0u : ((b.x & 255) == (b.y & 255) ? 1u : 0u) | (((b.x >> 8) & 255) == ((b.y >> 8) & 255) ? 2u : 0u)
		| (((b.x >> 16) & 255) == ((b.y >> 16) & 255) ? 4u : 0u);
	for (uint c = 0; c < 8; c++) {
		if ((c & flatAxes) != 0) continue;
		float4 h = h0 + ((c & 1) ? ex : 0.0) + ((c & 2) ? ey : 0.0) + ((c & 4) ? ez : 0.0);
		if (h.w <= 0.05) behind = true;
		float3 n = h.xyz / max(h.w, 1e-6);
		mn = min(mn, n.xy);
		mx = max(mx, n.xy);
		zmax = max(zmax, n.z);
	}
	bool qOn = !behind && !(mx.x < -1 || mx.y < -1 || mn.x > 1 || mn.y > 1);
	uint ql;
	uint2 qla, qlb;
	texelRect(f, mn, mx, ql, qla, qlb);
	if (f.debug == 6) {
		if (live && float(qla.x + ula.y + ql + ul) + zmax + uz == 12345.5 && qOn && uOn) atomic_store_explicit(&curVis[0], quad, memory_order_relaxed);
		return;
	}
	uint2 ut = min(uint2(ula.x + (q & 1), ula.y + ((q >> 1) & 1)), ulb);
	float uv = 1.0, q00 = 1.0, q10 = 1.0, q01 = 1.0, q11 = 1.0;
	if (f.gate == 0) {
		uv = hiz.read(ut, ul).x;
		q00 = hiz.read(qla, ql).x;
		q10 = hiz.read(uint2(min(qla.x + 1, qlb.x), qla.y), ql).x;
		q01 = hiz.read(uint2(qla.x, min(qla.y + 1, qlb.y)), ql).x;
		q11 = hiz.read(min(qla + 1, qlb), ql).x;
	} else {
		if (q < 4 && uOn) uv = hiz.read(ut, ul).x;
		if (mine && !skip && qOn) {
			q00 = hiz.read(qla, ql).x;
			if (qlb.x > qla.x) q10 = hiz.read(uint2(qla.x + 1, qla.y), ql).x;
			if (qlb.y > qla.y) q01 = hiz.read(uint2(qla.x, qla.y + 1), ql).x;
			if (qlb.x > qla.x && qlb.y > qla.y) q11 = hiz.read(qla + 1, ql).x;
		}
	}
	float ufar = simd_min(q < 4 ? uv : 1.0);
	bool unitHidden = uOn && all(ulb - ula <= 1) && uz < ufar - 1e-6;
	float qfar = min(min(q00, q10), min(q01, q11));
	bool quadVisible = !qOn ? behind : (any(qlb - qla > 1) || zmax >= qfar - 1e-6) && (f.centre == 0 || holdsCentre(f, mn, mx));
	if (mine && !unitHidden) {
		if (skip) {
			vis = true;
		} else if (quadVisible) {
			vis = true;
			want = !was;
		}
	}
	}
	uint layer = (rec.meta >> 9) & 1;
	if (f.debug == 7) {
		if (vis && want && o.x == 12345.5) atomic_store_explicit(&curVis[0], quad, memory_order_relaxed);
		return;
	}
	uint word = quad >> 5, bit = vis ? 1u << (quad & 31) : 0u;
	bool pending = vis;
	for (uint i = 0; i < 32 && simd_any(pending); i++) {
		uint lead = simd_min(pending ? word : 0xFFFFFFFFu);
		uint bits = simd_or(pending && word == lead ? bit : 0u);
		if (simd_is_first() && bits != 0) atomic_fetch_or_explicit(&curVis[lead], bits, memory_order_relaxed);
		if (word == lead) pending = false;
	}
	putQuads(idx, qa, 1, want, layer, quad, f.qcap, f.qlist);
}

kernel void own_q_bu(constant QuadFrame &f [[buffer(0)]], device const Section *sections [[buffer(1)]], device const Rec *recs [[buffer(2)]],
	device const uint *args [[buffer(3)]], device const uint *tested [[buffer(9)]], device const uint *prevVis [[buffer(12)]],
	device atomic_uint *qa [[buffer(11)]], device atomic_uint *curVis [[buffer(14)]], device uint *units [[buffer(16)]],
	texture2d<float, access::read> hiz [[texture(0)]], uint t [[thread_position_in_grid]]) {
	bool pass = false;
	uint r = 0;
	if (t < min(args[ARGS_T], f.listCap)) {
		r = tested[t];
		Rec rec = recs[r];
		Section s = sections[rec.slot];
		float3 o = float3(int3(s.x, s.y, s.z) - f.camBlock.xyz) + f.camOffset.xyz;
		float3 lo = o + float3(int3(rec.lo & 255, (rec.lo >> 8) & 255, (rec.lo >> 16) & 255) - 16);
		float3 hi = o + float3(int3(rec.hi & 255, (rec.hi >> 8) & 255, (rec.hi >> 16) & 255) - 16);
		bool near = false;
		float2 mn = float2(1e30), mx = float2(-1e30);
		float zmax = 0;
		for (uint c = 0; c < 8; c++) {
			float4 h = f.clip * float4((c & 1) ? hi.x : lo.x, (c & 2) ? hi.y : lo.y, (c & 4) ? hi.z : lo.z, 1.0);
			if (h.w <= 0.05) near = true;
			float3 n = h.xyz / max(h.w, 1e-6);
			mn = min(mn, n.xy);
			mx = max(mx, n.xy);
			zmax = max(zmax, n.z);
		}
		bool hidden = false;
		if (!near && !(mx.x < -1 || mx.y < -1 || mn.x > 1 || mn.y > 1)) {
			float2 smin = clamp((mn * 0.5 + 0.5) * float2(f.screen), float2(0), float2(f.screen) - 1);
			float2 smax = clamp((mx * 0.5 + 0.5) * float2(f.screen), float2(0), float2(f.screen) - 1);
			uint2 a = uint2(smin) / f.hizScale, b = uint2(smax) / f.hizScale;
			uint ext = max(b.x - a.x, b.y - a.y);
			uint level = ext == 0 ? 0 : min(f.mips - 1, 32 - clz(ext));
			uint2 lsize = uint2(max(1u, f.hiz0.x >> level), max(1u, f.hiz0.y >> level));
			uint2 la = min(a >> level, lsize - 1), lb = min(b >> level, lsize - 1);
			float far = 1.0;
			for (uint y = la.y; y <= min(lb.y, la.y + 1); y++)
				for (uint x = la.x; x <= min(lb.x, la.x + 1); x++) far = min(far, hiz.read(uint2(x, y), level).x);
			hidden = zmax < far - 1e-6;
		}
		if (!hidden) {
			uint start = rec.quadStart, count = (rec.meta & 63) + 1, w = start >> 5, sh = start & 31;
			uint mask = count >= 32 ? 0xFFFFFFFFu : (1u << count) - 1u;
			ulong pair = ulong(prevVis[w]) | (sh + count > 32 ? ulong(prevVis[w + 1]) << 32 : 0ul);
			if (f.unitStagger != 0 && (uint(pair >> sh) & mask) == mask && (((start >> 5) + f.frame) & 3) != 0) {
				ulong m = ulong(mask) << sh;
				atomic_fetch_or_explicit(&curVis[w], uint(m), memory_order_relaxed);
				if (uint(m >> 32) != 0) atomic_fetch_or_explicit(&curVis[w + 1], uint(m >> 32), memory_order_relaxed);
			} else {
				pass = true;
			}
		}
	}
	cullAppend(&qa[28], units, f.listCap, pass, r);
}

kernel void own_q_size2(constant QuadFrame &f [[buffer(0)]], device uint *qa [[buffer(11)]], uint i [[thread_position_in_grid]]) {
	if (i != 0) return;
	qa[29] = (min(qa[28], f.listCap) * f.run + 63) / 64;
}

kernel void own_q_bq(constant QuadFrame &f [[buffer(0)]], device const Section *sections [[buffer(1)]], device const Rec *recs [[buffer(2)]],
	device const uint *prevVis [[buffer(12)]], device uint *idx [[buffer(13)]], device atomic_uint *qa [[buffer(11)]],
	device atomic_uint *curVis [[buffer(14)]], device const void *arena [[buffer(15)]], device const uint *units [[buffer(16)]],
	device const uint2 *boxes [[buffer(17)]], texture2d<float, access::read> hiz [[texture(0)]], uint tid [[thread_position_in_grid]]) {
	uint t = tid / f.run, q = tid % f.run;
	bool want = false, vis = false;
	uint quad = 0, layer = 0;
	if (t < min(atomic_load_explicit(&qa[28], memory_order_relaxed), f.listCap)) {
		Rec rec = recs[units[t]];
		if (q <= (rec.meta & 63)) {
			Section s = sections[rec.slot];
			float3 o = float3(int3(s.x, s.y, s.z) - f.camBlock.xyz) + f.camOffset.xyz;
			quad = rec.quadStart + q;
			layer = (rec.meta >> 9) & 1;
			bool was = (prevVis[quad >> 5] & (1u << (quad & 31))) != 0;
			uint stagger = f.unitStagger != 0 ? rec.quadStart >> 5 : quad;
			if (was && f.debug != 4 && ((stagger + f.frame) & 3) != 0) {
				vis = true;
			} else if (quadTest(f, arena, boxes, hiz, o, quad)) {
				vis = true;
				want = !was;
			}
		}
	}
	uint word = quad >> 5, bit = vis ? 1u << (quad & 31) : 0u;
	bool pending = vis;
	for (uint i = 0; i < 32 && simd_any(pending); i++) {
		uint lead = simd_min(pending ? word : 0xFFFFFFFFu);
		uint bits = simd_or(pending && word == lead ? bit : 0u);
		if (simd_is_first() && bits != 0) atomic_fetch_or_explicit(&curVis[lead], bits, memory_order_relaxed);
		if (word == lead) pending = false;
	}
	putQuads(idx, qa, 1, want, layer, quad, f.qcap, f.qlist);
}

kernel void own_q_finish(constant QuadFrame &f [[buffer(0)]], device uint *qa [[buffer(11)]], uint i [[thread_position_in_grid]]) {
	if (i >= 4) return;
	uint phase = i / 2, layer = i % 2;
	device uint *a = qa + i * 5;
	uint n = min(a[0], f.qcap / 6 * 6);
	a[0] = n;
	a[2] = phase * f.qcap + (layer == 0 ? 0 : f.qcap - n);
	if (f.qmesh != 0) {
		device uint *m = qa + 32 + i * 3;
		m[0] = (n / 6 + 31) / 32;
		m[1] = 1;
		m[2] = 1;
	}
}

kernel void own_clear_words(device uint *w [[buffer(14)]], constant uint &words [[buffer(7)]], uint i [[thread_position_in_grid]]) {
	if (i < words) w[i] = 0;
}

kernel void own_hiz_tile(depth2d<float, access::read> depth [[texture(0)]], texture2d<float, access::write> m0 [[texture(1)]],
	texture2d<float, access::write> m1 [[texture(2)]], texture2d<float, access::write> m2 [[texture(3)]], texture2d<float, access::write> m3 [[texture(4)]],
	texture2d<float, access::write> m4 [[texture(5)]], uint2 lid [[thread_position_in_threadgroup]], uint2 gid [[threadgroup_position_in_grid]]) {
	threadgroup float t[16][16];
	uint dw = depth.get_width(), dh = depth.get_height();
	uint2 p = gid * 16 + lid;
	float m = 1.0;
	for (uint y = p.y * 2; y < min(p.y * 2 + 2, dh); y++)
		for (uint x = p.x * 2; x < min(p.x * 2 + 2, dw); x++) m = min(m, depth.read(uint2(x, y)));
	m0.write(float4(m), p);
	t[lid.y][lid.x] = m;
	for (uint level = 1, n = 8; level <= 4; level++, n >>= 1) {
		threadgroup_barrier(mem_flags::mem_threadgroup);
		float v = 1.0;
		if (lid.x < n && lid.y < n) v = min(min(t[lid.y * 2][lid.x * 2], t[lid.y * 2][lid.x * 2 + 1]), min(t[lid.y * 2 + 1][lid.x * 2], t[lid.y * 2 + 1][lid.x * 2 + 1]));
		threadgroup_barrier(mem_flags::mem_threadgroup);
		if (lid.x < n && lid.y < n) {
			t[lid.y][lid.x] = v;
			uint2 q = gid * n + lid;
			if (level == 1) m1.write(float4(v), q);
			else if (level == 2) m2.write(float4(v), q);
			else if (level == 3) m3.write(float4(v), q);
			else m4.write(float4(v), q);
		}
	}
}

kernel void own_hiz_tile_g(depth2d<float> depth [[texture(0)]], texture2d<float, access::write> m0 [[texture(1)]],
	texture2d<float, access::write> m1 [[texture(2)]], texture2d<float, access::write> m2 [[texture(3)]], texture2d<float, access::write> m3 [[texture(4)]],
	texture2d<float, access::write> m4 [[texture(5)]], uint2 lid [[thread_position_in_threadgroup]], uint2 gid [[threadgroup_position_in_grid]]) {
	threadgroup float t[16][16];
	uint dw = depth.get_width(), dh = depth.get_height();
	uint2 p = gid * 16 + lid;
	constexpr sampler nearestClamp(coord::normalized, address::clamp_to_edge, filter::nearest);
	float m = 1.0;
	if (p.x * 2 < dw && p.y * 2 < dh) {
		float4 g = depth.gather(nearestClamp, (float2(p * 2) + 1.0) / float2(dw, dh));
		m = min(min(g.x, g.y), min(g.z, g.w));
	}
	m0.write(float4(m), p);
	t[lid.y][lid.x] = m;
	for (uint level = 1, n = 8; level <= 4; level++, n >>= 1) {
		threadgroup_barrier(mem_flags::mem_threadgroup);
		float v = 1.0;
		if (lid.x < n && lid.y < n) v = min(min(t[lid.y * 2][lid.x * 2], t[lid.y * 2][lid.x * 2 + 1]), min(t[lid.y * 2 + 1][lid.x * 2], t[lid.y * 2 + 1][lid.x * 2 + 1]));
		threadgroup_barrier(mem_flags::mem_threadgroup);
		if (lid.x < n && lid.y < n) {
			t[lid.y][lid.x] = v;
			uint2 q = gid * n + lid;
			if (level == 1) m1.write(float4(v), q);
			else if (level == 2) m2.write(float4(v), q);
			else if (level == 3) m3.write(float4(v), q);
			else m4.write(float4(v), q);
		}
	}
}

kernel void own_hiz_tile_gs(depth2d<float> depth [[texture(0)]], array<texture2d<float, access::write>, 5> m [[texture(1)]],
	constant uint &k [[buffer(0)]], uint2 lid [[thread_position_in_threadgroup]], uint2 gid [[threadgroup_position_in_grid]]) {
	threadgroup float t[16][16];
	uint dw = depth.get_width(), dh = depth.get_height();
	uint2 p = gid * 16 + lid;
	uint s = 2u << k;
	constexpr sampler nearestClamp(coord::normalized, address::clamp_to_edge, filter::nearest);
	float v0 = 1.0;
	for (uint gy = 0; gy < s; gy += 2) {
		for (uint gx = 0; gx < s; gx += 2) {
			uint2 q = p * s + uint2(gx, gy);
			if (q.x >= dw || q.y >= dh) continue;
			float4 g = depth.gather(nearestClamp, (float2(q) + 1.0) / float2(dw, dh));
			v0 = min(v0, min(min(g.x, g.y), min(g.z, g.w)));
		}
	}
	m[0].write(float4(v0), p);
	t[lid.y][lid.x] = v0;
	for (uint level = 1, n = 8; level <= 4; level++, n >>= 1) {
		threadgroup_barrier(mem_flags::mem_threadgroup);
		float v = 1.0;
		if (lid.x < n && lid.y < n) v = min(min(t[lid.y * 2][lid.x * 2], t[lid.y * 2][lid.x * 2 + 1]), min(t[lid.y * 2 + 1][lid.x * 2], t[lid.y * 2 + 1][lid.x * 2 + 1]));
		threadgroup_barrier(mem_flags::mem_threadgroup);
		if (lid.x < n && lid.y < n) {
			t[lid.y][lid.x] = v;
			m[level].write(float4(v), gid * n + lid);
		}
	}
}

static float hizCell(uint2 p, uint w, uint h, uint pw, uint ph, thread uint &x1, thread uint &y1) {
	x1 = p.x == w - 1 ? pw : min(p.x * 2 + 2, pw);
	y1 = p.y == h - 1 ? ph : min(p.y * 2 + 2, ph);
	return 1.0;
}

kernel void own_hiz_top(texture2d<float, access::read> prev [[texture(0)]], array<texture2d<float, access::write>, 11> out [[texture(1)]],
	constant uint &levels [[buffer(0)]], uint2 lid [[thread_position_in_threadgroup]]) {
	threadgroup float t[32][32];
	uint pw = prev.get_width(), ph = prev.get_height();
	uint w = out[0].get_width(), h = out[0].get_height();
	float m = 1.0;
	if (lid.x < w && lid.y < h) {
		uint x1, y1;
		hizCell(lid, w, h, pw, ph, x1, y1);
		for (uint y = lid.y * 2; y < y1; y++)
			for (uint x = lid.x * 2; x < x1; x++) m = min(m, prev.read(uint2(x, y)).x);
		out[0].write(float4(m), lid);
	}
	t[lid.y][lid.x] = m;
	for (uint k = 1; k < levels; k++) {
		pw = w;
		ph = h;
		w = out[k].get_width();
		h = out[k].get_height();
		threadgroup_barrier(mem_flags::mem_threadgroup);
		float v = 1.0;
		if (lid.x < w && lid.y < h) {
			uint x1, y1;
			hizCell(lid, w, h, pw, ph, x1, y1);
			for (uint y = lid.y * 2; y < y1; y++)
				for (uint x = lid.x * 2; x < x1; x++) v = min(v, t[y][x]);
		}
		threadgroup_barrier(mem_flags::mem_threadgroup);
		t[lid.y][lid.x] = v;
		if (lid.x < w && lid.y < h) out[k].write(float4(v), lid);
	}
}

kernel void own_hiz_pad(texture2d<float, access::read> prev [[texture(0)]], texture2d<float, access::write> out [[texture(1)]],
	uint2 p [[thread_position_in_grid]]) {
	uint w = out.get_width(), h = out.get_height();
	if (p.x >= w || p.y >= h) return;
	uint pw = prev.get_width(), ph = prev.get_height();
	uint x1 = p.x == w - 1 ? pw : min(p.x * 2 + 2, pw), y1 = p.y == h - 1 ? ph : min(p.y * 2 + 2, ph);
	float m = 1.0;
	for (uint y = p.y * 2; y < y1; y++)
		for (uint x = p.x * 2; x < x1; x++) m = min(m, prev.read(uint2(x, y)).x);
	out.write(float4(m), p);
}

struct SoFace {
	float2 q[4];
	float depth;
	float sgn;
	float pad0, pad1;
};

constant uint SO_MAX_FACES = 16384;

kernel void own_so_clear(device uint *occ [[buffer(13)]], constant SolidOcc &p [[buffer(14)]], device uint *head [[buffer(16)]],
	uint i [[thread_position_in_grid]]) {
	if (i < p.size.x * p.size.y) occ[i] = 0;
	if (i == 0) {
		head[0] = 0;
		head[1] = 1;
		head[2] = 1;
	}
}

static void soFace(constant SolidOcc &p, device atomic_uint *head, device SoFace *faces, float3 c0, float3 c1, float3 c2, float3 c3) {
	float3 cs[4] = {c0, c1, c2, c3};
	SoFace f;
	float depth = 1.0;
	float2 sz = float2(p.size);
	for (int i = 0; i < 4; i++) {
		float4 h = p.clip * float4(cs[i], 1.0);
		if (h.w <= 0.05) return;
		float3 n = h.xyz / h.w;
		f.q[i] = (n.xy * 0.5 + 0.5) * sz;
		depth = min(depth, n.z);
	}
	if (depth <= 0) return;
	float area = 0;
	for (int i = 0; i < 4; i++) area += f.q[i].x * f.q[(i + 1) & 3].y - f.q[(i + 1) & 3].x * f.q[i].y;
	if (fabs(area) < 2.0) return;
	float2 mn = min(min(f.q[0], f.q[1]), min(f.q[2], f.q[3])), mx = max(max(f.q[0], f.q[1]), max(f.q[2], f.q[3]));
	if (mx.x < 0 || mx.y < 0 || mn.x > sz.x || mn.y > sz.y) return;
	f.depth = depth;
	f.sgn = area > 0 ? 1.0 : -1.0;
	f.pad0 = 0;
	f.pad1 = 0;
	uint at = atomic_fetch_add_explicit(&head[0], 1, memory_order_relaxed);
	if (at < SO_MAX_FACES) faces[at] = f;
}

kernel void own_so_raster(constant SolidOcc &p [[buffer(14)]], device const SolidCol *cols [[buffer(15)]], device atomic_uint *head [[buffer(16)]],
	device SoFace *faces [[buffer(17)]], uint2 tid [[thread_position_in_grid]]) {
	int span = p.rd * 2 + 1;
	if (int(tid.x) >= span || int(tid.y) >= span) return;
	int cx = p.camSection.x - p.rd + int(tid.x), cz = p.camSection.z - p.rd + int(tid.y);
	SolidCol col = cols[(cx & (p.grid - 1)) + (cz & (p.grid - 1)) * p.grid];
	if (col.x != cx || col.z != cz) return;
	ulong mask = ulong(col.lo) | ulong(col.hi) << 32;
	if (mask == 0) return;
	int ylo = max(0, p.camSection.y - p.rd - p.minY), yhi = min(p.levels - 1, p.camSection.y + p.rd - p.minY);
	int i = ylo;
	while (i <= yhi) {
		if (((mask >> i) & 1) == 0) {
			i++;
			continue;
		}
		int j = i;
		while (j + 1 <= yhi && ((mask >> (j + 1)) & 1) != 0) j++;
		float3 lo = float3(int3(cx * 16, (p.minY + i) * 16, cz * 16) - p.camBlock.xyz) + p.camOffset.xyz;
		float3 hi = lo + float3(16, (j - i + 1) * 16, 16);
		if (0 < lo.x) soFace(p, head, faces, float3(lo.x, lo.y, lo.z), float3(lo.x, hi.y, lo.z), float3(lo.x, hi.y, hi.z), float3(lo.x, lo.y, hi.z));
		if (0 > hi.x) soFace(p, head, faces, float3(hi.x, lo.y, lo.z), float3(hi.x, hi.y, lo.z), float3(hi.x, hi.y, hi.z), float3(hi.x, lo.y, hi.z));
		if (0 < lo.y) soFace(p, head, faces, float3(lo.x, lo.y, lo.z), float3(hi.x, lo.y, lo.z), float3(hi.x, lo.y, hi.z), float3(lo.x, lo.y, hi.z));
		if (0 > hi.y) soFace(p, head, faces, float3(lo.x, hi.y, lo.z), float3(hi.x, hi.y, lo.z), float3(hi.x, hi.y, hi.z), float3(lo.x, hi.y, hi.z));
		if (0 < lo.z) soFace(p, head, faces, float3(lo.x, lo.y, lo.z), float3(hi.x, lo.y, lo.z), float3(hi.x, hi.y, lo.z), float3(lo.x, hi.y, lo.z));
		if (0 > hi.z) soFace(p, head, faces, float3(lo.x, lo.y, hi.z), float3(hi.x, lo.y, hi.z), float3(hi.x, hi.y, hi.z), float3(lo.x, hi.y, hi.z));
		i = j + 1;
	}
}

kernel void own_so_fill(constant SolidOcc &p [[buffer(14)]], device const uint *head [[buffer(16)]], device const SoFace *faces [[buffer(17)]],
	device atomic_uint *occ [[buffer(13)]], uint fi [[threadgroup_position_in_grid]], uint lid [[thread_index_in_threadgroup]],
	uint lanes [[threads_per_threadgroup]]) {
	if (fi >= min(head[0], SO_MAX_FACES)) return;
	SoFace f = faces[fi];
	float2 mn = min(min(f.q[0], f.q[1]), min(f.q[2], f.q[3])), mx = max(max(f.q[0], f.q[1]), max(f.q[2], f.q[3]));
	int y0 = max(0, int(floor(mn.y))), y1 = min(int(p.size.y) - 1, int(ceil(mx.y)) - 1);
	uint bits = as_type<uint>(f.depth);
	for (int y = y0 + int(lid); y <= y1; y += int(lanes)) {
		float L = -1e30, R = 1e30;
		bool empty = false;
		for (int e = 0; e < 4 && !empty; e++) {
			float2 a = f.q[e], d = f.q[(e + 1) & 3] - a;
			float k = f.sgn * d.y;
			for (int r = 0; r < 2; r++) {
				float py = float(y + r);
				float c = f.sgn * d.x * (py - a.y);
				if (fabs(k) < 1e-6) {
					if (c < 0) empty = true;
				} else if (k > 0) {
					R = min(R, a.x + c / k);
				} else {
					L = max(L, a.x + c / k);
				}
			}
		}
		if (empty) continue;
		int x0 = max(0, int(ceil(L))), x1 = min(int(p.size.x) - 1, int(floor(R)) - 1);
		for (int x = x0; x <= x1; x++) atomic_fetch_max_explicit(&occ[y * int(p.size.x) + x], bits, memory_order_relaxed);
	}
}

kernel void own_so_mip0(device const uint *occ [[buffer(13)]], constant SolidOcc &p [[buffer(14)]], texture2d<float, access::write> out [[texture(1)]],
	uint2 q [[thread_position_in_grid]]) {
	if (q.x >= p.size.x || q.y >= p.size.y) return;
	out.write(float4(as_type<float>(occ[q.y * p.size.x + q.x])), q);
}

kernel void own_qstat(constant QuadFrame &f [[buffer(0)]], device const Section *sections [[buffer(1)]], device const Rec *recs [[buffer(2)]],
	device const uint *args [[buffer(3)]], device const uint *lists [[buffer(4)]], device const void *arena [[buffer(15)]],
	device atomic_uint *stats [[buffer(18)]], texture2d<float, access::read> hiz [[texture(0)]], uint tid [[thread_position_in_grid]]) {
	uint entry = tid / f.run, q = tid % f.run;
	uint nSolid = min(args[1], f.listCap), nCut = min(args[6], f.listCap);
	uint li;
	if (entry < nSolid) li = entry;
	else if (entry < nSolid + nCut) li = f.listCap + (entry - nSolid);
	else return;
	Rec rec = recs[lists[li]];
	if (q == 0) {
		atomic_fetch_add_explicit(&stats[0], 1, memory_order_relaxed);
		atomic_fetch_add_explicit(&stats[7], f.run * 4, memory_order_relaxed);
	}
	if (q > (rec.meta & 63)) return;
	atomic_fetch_add_explicit(&stats[1], 1, memory_order_relaxed);
	uint quad = rec.quadStart + q;
	Section s = sections[rec.slot];
	float3 o = float3(int3(s.x, s.y, s.z) - f.camBlock.xyz) + f.camOffset.xyz;
	float3 c[4];
	for (uint i = 0; i < 4; i++) c[i] = o + quadCorner(arena, f.compact, quad * 4 + i);
	float3 n = cross(c[1] - c[0], c[2] - c[0]);
	if (dot(n, -c[0]) <= 0) {
		atomic_fetch_add_explicit(&stats[2], 1, memory_order_relaxed);
		return;
	}
	float4 h[4];
	for (uint i = 0; i < 4; i++) h[i] = f.clip * float4(c[i], 1.0);
	bool outside = false;
	for (int pl = 0; pl < 5 && !outside; pl++) {
		bool all = true;
		for (uint i = 0; i < 4; i++) {
			float v = pl == 0 ? h[i].w + h[i].x : pl == 1 ? h[i].w - h[i].x : pl == 2 ? h[i].w + h[i].y : pl == 3 ? h[i].w - h[i].y : h[i].w - 0.05;
			if (v >= 0) all = false;
		}
		outside = all;
	}
	if (outside) {
		atomic_fetch_add_explicit(&stats[3], 1, memory_order_relaxed);
		return;
	}
	bool behind = false;
	float2 mn = float2(1e30), mx = float2(-1e30);
	float zmax = 0;
	for (uint i = 0; i < 4; i++) {
		if (h[i].w <= 0.05) behind = true;
		float3 d = h[i].xyz / max(h[i].w, 1e-6);
		mn = min(mn, d.xy);
		mx = max(mx, d.xy);
		zmax = max(zmax, d.z);
	}
	float2 sz = float2(f.screen);
	if (!behind) {
		float2 pa = (mn * 0.5 + 0.5) * sz, pb = (mx * 0.5 + 0.5) * sz;
		if (floor(pb.x - 0.5) < ceil(pa.x - 0.5) || floor(pb.y - 0.5) < ceil(pa.y - 0.5)) {
			atomic_fetch_add_explicit(&stats[4], 1, memory_order_relaxed);
			return;
		}
		uint2 a = uint2(clamp(pa, float2(0), sz - 1)) / f.hizScale, b = uint2(clamp(pb, float2(0), sz - 1)) / f.hizScale;
		uint ext = max(b.x - a.x, b.y - a.y);
		uint level = ext == 0 ? 0 : min(f.mips - 1, 32 - clz(ext));
		uint2 lsize = uint2(max(1u, f.hiz0.x >> level), max(1u, f.hiz0.y >> level));
		uint2 la = min(a >> level, lsize - 1), lb = min(b >> level, lsize - 1);
		float far = 1.0;
		for (uint y = la.y; y <= lb.y; y++)
			for (uint x = la.x; x <= lb.x; x++) far = min(far, hiz.read(uint2(x, y), level).x);
		if (zmax < far - 1e-6) {
			atomic_fetch_add_explicit(&stats[5], 1, memory_order_relaxed);
			return;
		}
	}
	atomic_fetch_add_explicit(&stats[6], 1, memory_order_relaxed);
}

kernel void own_q_pre(constant QuadFrame &f [[buffer(0)]], device const Section *sections [[buffer(1)]], device const Rec *recs [[buffer(2)]],
	device const uint *args [[buffer(3)]], device const uint *tested [[buffer(9)]], device uint *idx [[buffer(13)]], device atomic_uint *qa [[buffer(11)]],
	device const void *arena [[buffer(15)]], uint tid [[thread_position_in_grid]]) {
	uint t = tid / f.run, q = tid % f.run;
	bool want = false;
	uint quad = 0, layer = 0;
	if (t < min(args[ARGS_T], f.listCap)) {
		Rec rec = recs[tested[t]];
		quad = rec.quadStart + q;
		layer = (rec.meta >> 9) & 1;
		if (q <= (rec.meta & 63)) {
			Section s = sections[rec.slot];
			float3 o = float3(int3(s.x, s.y, s.z) - f.camBlock.xyz) + f.camOffset.xyz;
			float3 c[4];
			for (uint i = 0; i < 4; i++) c[i] = o + quadCorner(arena, f.compact, quad * 4 + i);
			float3 n1 = cross(c[1] - c[0], c[2] - c[0]), n2 = cross(c[3] - c[2], c[0] - c[2]);
			bool front = dot(n1, -c[0]) > 0 || dot(n2, -c[2]) > 0;
			if (front) {
				float4 h[4];
				for (uint i = 0; i < 4; i++) h[i] = f.clip * float4(c[i], 1.0);
				bool outside = false;
				for (int pl = 0; pl < 4 && !outside; pl++) {
					bool all = true;
					for (uint i = 0; i < 4; i++) {
						float v = pl == 0 ? h[i].w + h[i].x : pl == 1 ? h[i].w - h[i].x : pl == 2 ? h[i].w + h[i].y : h[i].w - h[i].y;
						if (v >= 0) all = false;
					}
					outside = all;
				}
				want = !outside;
				if (want) {
					bool behind = false;
					float2 mn = float2(1e30), mx = float2(-1e30);
					for (uint i = 0; i < 4; i++) {
						if (h[i].w <= 1e-4) behind = true;
						float2 d = h[i].xy / max(h[i].w, 1e-6);
						mn = min(mn, d);
						mx = max(mx, d);
					}
					if (!behind) {
						float2 sz = float2(f.screen);
						float2 pa = (mn * 0.5 + 0.5) * sz - 0.01, pb = (mx * 0.5 + 0.5) * sz + 0.01;
						if (floor(pb.x - 0.5) < ceil(pa.x - 0.5) || floor(pb.y - 0.5) < ceil(pa.y - 0.5)) want = false;
					}
				}
			}
		}
	}
	putQuads(idx, qa, 0, want, layer, quad, f.qcap, f.qlist);
}

kernel void frag_face_stats(constant CullFrame &f [[buffer(0)]], constant FragFrame &g [[buffer(11)]], device const Section *sections [[buffer(1)]],
	device const Rec *recs [[buffer(2)]], device atomic_uint *args [[buffer(3)]], device const uint *lists [[buffer(4)]],
	constant uint &id [[buffer(7)]], constant uint &compact [[buffer(8)]], device const void *arena [[buffer(15)]],
	uint tg [[threadgroup_position_in_grid]], uint q [[thread_index_in_threadgroup]]) {
	uint r = lists[id * f.listCap + tg];
	Rec rec = recs[r];
	bool live = q <= (rec.meta & 63);
	bool any = ((rec.meta >> 6) & 7) == 6;
	uint back = 0, degenerate = 0;
	if (live) {
		Section s = sections[rec.slot];
		float3 cam = -(float3(int3(s.x, s.y, s.z) - f.camBlock.xyz) + f.camOffset.xyz);
		uint v = (rec.quadStart + q) * 4;
		float3 v0 = quadCorner(arena, compact, v), v1 = quadCorner(arena, compact, v + 1), v2 = quadCorner(arena, compact, v + 2);
		float3 n = cross(v1 - v0, v2 - v0);
		if (length_squared(n) < 1e-12) degenerate = 1;
		else if (dot(n, cam - v0) <= 0) back = 1;
	}
	uint quads = simd_sum(live ? 1u : 0u), backFacing = simd_sum(back != 0 && !any ? 1u : 0u), backAny = simd_sum(back != 0 && any ? 1u : 0u);
	uint anyQuads = simd_sum(live && any ? 1u : 0u), degen = simd_sum(degenerate);
	if (simd_is_first()) {
		uint base = g.lists * 8;
		atomic_fetch_add_explicit(&args[base + 22], quads, memory_order_relaxed);
		if (backFacing) atomic_fetch_add_explicit(&args[base + 23], backFacing, memory_order_relaxed);
		if (backAny) atomic_fetch_add_explicit(&args[base + 24], backAny, memory_order_relaxed);
		if (anyQuads) atomic_fetch_add_explicit(&args[base + 25], anyQuads, memory_order_relaxed);
		if (degen) atomic_fetch_add_explicit(&args[base + 26], degen, memory_order_relaxed);
	}
}

kernel void own_oracle_copy(device const uint *src [[buffer(0)]], device uint *dst [[buffer(1)]], constant uint &n [[buffer(2)]],
	uint i [[thread_position_in_grid]]) {
	if (i < n) dst[i] = src[i];
}

kernel void own_qrec_verify(device const Section *sections [[buffer(1)]], device const Rec *recs [[buffer(2)]], device const void *arena [[buffer(15)]],
	device atomic_uint *stats [[buffer(18)]], constant uint &slots [[buffer(7)]], uint tid [[thread_position_in_grid]]) {
	if (!OWN_REC || tid >= slots) return;
	Section s = sections[tid];
	uint corners = 0, bad = 0, units = 0, bare = 0;
	for (uint ri = s.recStart; ri < s.recStart + s.recCount; ri++) {
		Rec rec = recs[ri];
		if (((rec.meta >> 9) & 3) == 2) continue;
		if (rec.pad0 == 0xffffffffu) {
			bare++;
			continue;
		}
		units++;
		for (uint q = 0; q <= (rec.meta & 63); q++) {
			for (uint c = 0; c < 4; c++) {
				CVtx k = ((device const CVtx *) arena)[(rec.pad0 + q) * 4 + c];
				float3 pk = OWN_POS3(arena, k.px, k.py, k.pz);
				float3 pc = quadCorner(arena, 1, (rec.quadStart + q) * 4 + c);
				Vtx v = ownQrec(arena, rec.quadStart + q, c);
				bool same = all(pc == pk) && all(float3(v.pos) == pk) && all(v.color == k.color) && all(float2(v.uv0) == float2(k.u, k.v) / 65536.0)
					&& v.uv2.x == short(k.lu) && v.uv2.y == short(k.lv);
				corners++;
				if (!same) bad++;
			}
		}
	}
	if (corners) atomic_fetch_add_explicit(&stats[0], corners, memory_order_relaxed);
	if (bad) atomic_fetch_add_explicit(&stats[1], bad, memory_order_relaxed);
	if (units) atomic_fetch_add_explicit(&stats[2], units, memory_order_relaxed);
	if (bare) atomic_fetch_add_explicit(&stats[3], bare, memory_order_relaxed);
}

kernel void anim_copy(texture2d<float, access::read> src [[texture(0)]], texture2d<float, access::write> dst [[texture(1)]],
	constant uint *rects [[buffer(0)]], uint3 g [[thread_position_in_grid]]) {
	uint k = g.z * 6;
	if (g.x >= rects[k + 4] || g.y >= rects[k + 5]) return;
	dst.write(src.read(uint2(rects[k] + g.x, rects[k + 1] + g.y)), uint2(rects[k + 2] + g.x, rects[k + 3] + g.y));
}

kernel void own_tl_a1split(device const uint *args [[buffer(3)]], device uint *out [[buffer(29)]], constant uint4 &p [[buffer(28)]],
	uint i [[thread_position_in_grid]]) {
	if (i != 0) return;
	device const uint *a = args + p.x * 5;
	uint n = a[1], k = uint((ulong(n) * p.y) / max(p.z, 1u));
	out[0] = a[0]; out[1] = k; out[2] = a[2]; out[3] = a[3]; out[4] = a[4];
	out[5] = a[0]; out[6] = n - k; out[7] = a[2]; out[8] = a[3]; out[9] = a[4] + k;
}

#ifdef MCOPT_SHADE
struct OwnShadowCascade { float4x4 viewProj; float depthBias; float pad0, pad1, pad2; };
struct OwnShadowFrame { int4 camBlock; float4 camOffset; uint listBase, flags, part, parts; };
struct OwnShadowOut { float4 pos [[position]]; float2 uv; half alpha; };

static OwnShadowOut ownShadowVertex(uint vid, uint iid, constant OwnShadowFrame &f, constant OwnShadowCascade &c, device const void *arenaRaw,
	device const Section *sections, device const Rec *recs, device const uint *lists) {
	OwnShadowOut o;
	o.pos = float4(0, 0, -2, 1);
	o.uv = float2(0);
	o.alpha = 0;
	uint quadStart, meta;
	int3 origin;
	if (FAT) {
		Inst e = ((device const Inst *) lists)[f.listBase + iid];
		quadStart = e.quadStart;
		meta = e.meta;
		origin = int3(e.x, e.y, e.z);
	} else {
		Rec rec = recs[lists[f.listBase + iid]];
		Section s = sections[rec.slot];
		quadStart = rec.quadStart;
		meta = rec.meta;
		origin = int3(s.x, s.y, s.z);
	}
	uint quad = vid >> 2;
	if (quad > (meta & 63)) return o;
	if ((f.flags & 1) != 0 && ((meta >> 6) & 7) == 6) return o;
	if ((f.flags & 2) != 0) {
		int2 sxz = origin.xz >> 4;
		if (((sxz.x + sxz.y * int((f.parts + 1) / 2)) % int(f.parts) + int(f.parts)) % int(f.parts) != int(f.part)) return o;
	}
	uint vi = (quadStart + quad) * 4 + (vid & 3);
	Vtx v;
	if (OWN_REC && COMPACT) {
		v = ownQrec(arenaRaw, vi >> 2, vi & 3);
	} else if (COMPACT) {
		CVtx k = ((device const CVtx *) arenaRaw)[vi];
		v.pos = packed_float3(OWN_POS3(arenaRaw, k.px, k.py, k.pz));
		v.color = k.color;
		v.uv0 = packed_float2(float2(k.u, k.v) / 65536.0);
	} else {
		v = ((device const Vtx *) arenaRaw)[vi];
	}
	float3 p = float3(v.pos) + float3(origin - f.camBlock.xyz) + f.camOffset.xyz;
	o.pos = c.viewProj * float4(p, 1.0);
	o.pos.z -= c.depthBias * o.pos.w;
	o.uv = float2(v.uv0);
	o.alpha = half(float(v.color.a) / 255.0);
	return o;
}

vertex OwnShadowOut own_shadow_vs(uint vid [[vertex_id]], uint iid [[instance_id]], device const void *arenaRaw [[buffer(0)]],
	device const Section *sections [[buffer(1)]], device const Rec *recs [[buffer(2)]], constant OwnShadowCascade &cascade [[buffer(3)]],
	constant OwnShadowFrame &frame [[buffer(4)]], device const uint *lists [[buffer(5)]]) {
	return ownShadowVertex(vid, iid, frame, cascade, arenaRaw, sections, recs, lists);
}

fragment void own_shadow_cutout_fs(OwnShadowOut in [[stage_in]], texture2d<float> atlas [[texture(0)]], sampler s [[sampler(0)]]) {
	if (atlas.sample(s, in.uv, level(0.0)).a * float(in.alpha) < 0.5) discard_fragment();
}

struct OwnTcullFrame { float4 planes[4]; int4 camBlock; float4 camOffset; int4 camSection; int viewDistance; uint sectionCount, cap, pad;
	float4 depthPlanes[2]; };
struct OwnShadowTintOut { float4 pos [[position]]; float2 uv; half4 color; };
struct OwnTintParams { float4 still; float4 flow; float4 mode; };

kernel void own_shadow_tcull_reset(device uint *args [[buffer(3)]], device atomic_int *rect [[buffer(5)]], uint i [[thread_position_in_grid]]) {
	if (i != 0) return;
	args[0] = 64 * 6;
	args[1] = 0;
	args[2] = 0;
	args[3] = 0;
	args[4] = 0;
	for (uint k = 0; k < 4; k++) atomic_store_explicit(&rect[k], INT_MIN, memory_order_relaxed);
	atomic_store_explicit(&rect[4], 0, memory_order_relaxed);
	atomic_store_explicit(&rect[5], 0, memory_order_relaxed);
}

static uint ownTintVertex(device const void *arenaRaw, Rec rec, uint q) {
	return (rec.meta & (1u << 18)) == 0 ? (uint(rec.plane) + ((device const uint *) arenaRaw)[rec.quadStart + q]) * 4 : (rec.quadStart + q) * 4;
}

static float2 ownTintUv(device const void *arenaRaw, uint vi) {
	if (COMPACT) {
		CVtx k = ((device const CVtx *) arenaRaw)[vi];
		return float2(k.u, k.v) / 65536.0;
	}
	return float2(((device const Vtx *) arenaRaw)[vi].uv0);
}

static bool ownTintWater(float2 uv, constant OwnTintParams &tp) {
	return tp.still.z > 0.0 && ((all(uv >= tp.still.xy) && all(uv <= tp.still.zw)) || (all(uv >= tp.flow.xy) && all(uv <= tp.flow.zw)));
}

kernel void own_shadow_tcull(constant OwnTcullFrame &f [[buffer(0)]], device const Section *sections [[buffer(1)]], device const Rec *recs [[buffer(2)]],
	device atomic_uint *args [[buffer(3)]], device uint *list [[buffer(4)]], device const void *arenaRaw [[buffer(6)]], constant OwnTintParams &tp [[buffer(7)]],
	uint tid [[thread_position_in_grid]], uint lane [[thread_index_in_simdgroup]]) {
	uint i = tid >> 5;
	if (i >= f.sectionCount) return;
	Section s = sections[i];
	if (s.recCount == 0) return;
	int3 sec = int3(s.x, s.y, s.z) >> 4;
	int dx = max(0, abs(sec.x - f.camSection.x) - 1), dz = max(0, abs(sec.z - f.camSection.z) - 1);
	if (dx * dx + dz * dz >= f.viewDistance * f.viewDistance) return;
	if (abs(sec.y - f.camSection.y) > f.viewDistance) return;
	float3 o = float3(int3(s.x, s.y, s.z) - f.camBlock.xyz) + f.camOffset.xyz;
	for (int p = 0; p < 6; p++) {
		float4 pl = p < 4 ? f.planes[p] : f.depthPlanes[p - 4];
		if (all(pl.xyz == 0.0)) continue;
		float3 v = o + select(float3(0), float3(16), pl.xyz > 0);
		if (dot(pl.xyz, v) + pl.w < -0.5) return;
	}
	for (uint r = s.recStart; r < s.recStart + s.recCount; r++) {
		uint meta = recs[r].meta;
		if (((meta >> 9) & 3) != 2 || (meta & (1u << 18)) != 0) continue;
		if (tp.mode.x > 0.5) {
			Rec rec = recs[r];
			bool mine = false;
			for (uint q = lane; q <= (meta & 63); q += 32) mine = mine || !ownTintWater(ownTintUv(arenaRaw, ownTintVertex(arenaRaw, rec, q)), tp);
			if (!simd_any(mine)) continue;
		}
		if (lane != 0) continue;
		uint at = atomic_fetch_add_explicit(&args[1], 1, memory_order_relaxed);
		if (at < f.cap) list[at] = r;
	}
}

kernel void own_shadow_tcull_finish(constant OwnTcullFrame &f [[buffer(0)]], device uint *args [[buffer(3)]], device int *rect [[buffer(5)]],
	uint i [[thread_position_in_grid]]) {
	if (i != 0) return;
	args[1] = min(args[1], f.cap);
	rect[5] = int(args[1]);
}

static int ownTintOrdered(float v) { return as_type<int>(v) >= 0 ? as_type<int>(v) : as_type<int>(v) ^ 0x7fffffff; }

vertex OwnShadowTintOut own_shadow_tint_vs(uint vid [[vertex_id]], uint iid [[instance_id]], device const void *arenaRaw [[buffer(0)]],
	device const Section *sections [[buffer(1)]], device const Rec *recs [[buffer(2)]], constant OwnShadowCascade &cascade [[buffer(3)]],
	constant OwnShadowFrame &frame [[buffer(4)]], device const uint *list [[buffer(5)]], constant OwnTintParams &tp [[buffer(6)]],
	device atomic_int *rect [[buffer(7)]]) {
	OwnShadowTintOut o;
	o.pos = float4(0, 0, -2, 1);
	o.uv = float2(0);
	o.color = half4(0);
	Rec rec = recs[list[iid]];
	Section s = sections[rec.slot];
	uint quad = vid >> 2;
	if (quad > (rec.meta & 63)) return o;
	uint corner = vid & 3;
	uint vi = ownTintVertex(arenaRaw, rec, quad) + corner;
	Vtx v;
	if (COMPACT) {
		CVtx k = ((device const CVtx *) arenaRaw)[vi];
		v.pos = packed_float3(OWN_POS3(arenaRaw, k.px, k.py, k.pz));
		v.color = k.color;
		v.uv0 = packed_float2(float2(k.u, k.v) / 65536.0);
	} else {
		v = ((device const Vtx *) arenaRaw)[vi];
	}
	float3 p = float3(v.pos) + float3(int3(s.x, s.y, s.z) - frame.camBlock.xyz) + frame.camOffset.xyz;
	o.pos = cascade.viewProj * float4(p, 1.0);
	o.uv = float2(v.uv0);
	o.color = half4(float4(v.color) / 255.0);
	if (tp.mode.x > 0.5 && ownTintWater(o.uv, tp)) {
		o.pos = float4(0, 0, -2, 1);
		return o;
	}
	float2 c = clamp(o.pos.xy / o.pos.w, -1.0, 1.0);
	atomic_fetch_max_explicit(&rect[0], ownTintOrdered(-c.x), memory_order_relaxed);
	atomic_fetch_max_explicit(&rect[1], ownTintOrdered(-c.y), memory_order_relaxed);
	atomic_fetch_max_explicit(&rect[2], ownTintOrdered(c.x), memory_order_relaxed);
	atomic_fetch_max_explicit(&rect[3], ownTintOrdered(c.y), memory_order_relaxed);
	if (corner == 0) atomic_fetch_add_explicit(&rect[4], 1, memory_order_relaxed);
	return o;
}
#endif
