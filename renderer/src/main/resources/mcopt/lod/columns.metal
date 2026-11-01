#include <metal_stdlib>
using namespace metal;

#ifndef COL_DEBUG
#define COL_DEBUG(...)
#endif
#ifndef COL_STAT
#define COL_STAT(k)
#endif

#define MAX_LEVELS 12
#define FACE_TOP 0u
#define FACE_XP 1u
#define FACE_XN 2u
#define FACE_ZP 3u
#define FACE_ZN 4u

struct ColLevel {
    int4 info;
    float4 range;
};

struct ColFrame {
    float4 A, B, C;
    float4 cam;
    int4 origin;
    float4 cols;
    float4 lines;
    float4 screen;
    int4 mask;
    float4 maskDist;
    ColLevel levels[MAX_LEVELS];
};

#define GEOM_Y(g) (int((g) & 0xFFFu) - 512)
#define GEOM_WET 0x1000u
#define GEOM_VALID 0x2000u

#define GEOM_CROWN 0x4000u
#define GEOM_THICK(g) int(((g) >> 15) & 63u)
#define GEOM_FRINGE (1u << 21)
#define REC_WET (1u << 19)
#define REC_CROWN (1u << 24)
#define REC_UNDER_CROWN (1u << 25)
#define FACE_BOTTOM 5u
#define FACE_PLANT 6u
#define MAX_GAPS 5

#define GEOM_PLANT_BLOCKS(g) int(((g) >> 22) & 3u)

struct PaletteEntry {
    float4 topUv, sideUv;
    float4 topAvg, sideAvg;
    uint4 profile;
};

static inline int plantProfile(device const PaletteEntry* palette, uint id, int k) {
    return int((palette[id].profile[k >> 2] >> (uint(k & 3) * 8u)) & 255u);
}

struct ColRows {
    int row;
    int rEnd;
    float t;
    int px, pxPrev;
    int gaps;
    int gapLo[MAX_GAPS], gapHi[MAX_GAPS];
    float gapT[MAX_GAPS];
};

struct ColRow {
    float t;
    int px, pxPrev;
};

static inline float colX(constant ColFrame& f, float col, float g) {
    float Xref = f.cols.x + (col + 0.5) * f.cols.y;
    return f.lines.x + (Xref - f.lines.x) * g;
}

static inline ColRow colRow(int row, constant ColFrame& f, float cf, float2 h, bool first) {
    ColRow r;
    float Y = 2.0 * (float(row) + 0.5) / f.screen.y - 1.0;
    float g = 1.0 + (Y - f.cols.z) * f.lines.y;
    float c = cf;
    float X = colX(f, c, g);
    float3 D = f.A.xyz * X + f.B.xyz * Y + f.C.xyz;
    r.t = D.y / max(dot(D.xz, h), 1e-6);
    r.px = int(floor((X + 1.0) * 0.5 * f.screen.x));
    r.pxPrev = first ? -1 : int(floor((colX(f, c - 1.0, g) + 1.0) * 0.5 * f.screen.x));
    return r;
}

static inline void colPx(int row, constant ColFrame& f, float cf, bool first, thread int& px, thread int& pxPrev) {
    float Y = 2.0 * (float(row) + 0.5) / f.screen.y - 1.0;
    float g = 1.0 + (Y - f.cols.z) * f.lines.y;
    px = int(floor((colX(f, cf, g) + 1.0) * 0.5 * f.screen.x));
    pxPrev = first ? -1 : int(floor((colX(f, cf - 1.0, g) + 1.0) * 0.5 * f.screen.x));
}

static inline int colRowAbove(int row, float t, constant ColFrame& f, float cf, float2 h, bool first, int R1) {
    int lo = row, step = 1, hi = row;
    while (true) {
        hi = min(row + step, R1 + 1);
        if (hi > R1 || colRow(hi, f, cf, h, first).t > t) break;
        lo = hi;
        step <<= 1;
    }
    while (hi - lo > 1) {
        int mid = (lo + hi) >> 1;
        if (colRow(mid, f, cf, h, first).t > t) hi = mid;
        else lo = mid;
    }
    return lo == row && colRow(row, f, cf, h, first).t > t ? row : hi;
}

static inline void colPut(int row, ColRow r, constant ColFrame& f, device uint2* out, uint2 v) {
    COL_STAT(3);
#ifdef COL_ABLATE_NOWRITE
    if (v.x == 0xFFFFFFFFu && r.px == -7) out[0] = v;
#else
    if (r.px != r.pxPrev && r.px >= 0 && r.px < int(f.screen.x)) out[row * int(f.screen.x) + r.px] = v;
#endif
}

struct ColSpan {
    float tLo, tHi;
    float tWall, tWallTop;
    float yBottom, yTop;
    float dIn, dOut;
    uint face;
    uint topColor, sideColor, flags;
};

static inline uint2 colRecord(thread const ColSpan& s, float t) {
    if (t < s.tWall) {
        float d = clamp(s.yBottom / max(t, 1e-9), s.dIn, s.dOut);
        return uint2((as_type<uint>(d) & ~7u) | FACE_BOTTOM, s.sideColor | s.flags);
    }
    if (t <= s.tWallTop) return uint2((as_type<uint>(s.dIn) & ~7u) | s.face, s.sideColor | s.flags);
    float d = clamp(s.yTop / min(t, -1e-9), s.dIn, s.dOut);
    return uint2((as_type<uint>(d) & ~7u) | FACE_TOP, s.topColor | s.flags);
}

static inline void colAdvance(thread ColRows& r, constant ColFrame& f, float cf, float2 h, bool first) {
    r.row++;
    ColRow n = colRow(r.row, f, cf, h, first);
    r.t = n.t;
    r.px = n.px;
    r.pxPrev = n.pxPrev;
}

static inline void colFill(thread ColRows& r, thread const ColSpan& s, constant ColFrame& f, device uint2* out, float cf, float2 h, bool first) {
    const int R1 = r.rEnd;
    for (int k = 0; k < r.gaps; k++) {
        if (r.gapT[k] > s.tHi) continue;
        int a = r.gapLo[k];
        ColRow cr = colRow(a, f, cf, h, first);
        while (a < r.gapHi[k] && cr.t < s.tLo) {
            a++;
            cr = colRow(a, f, cf, h, first);
        }
        int b = a;
        while (b < r.gapHi[k] && cr.t <= s.tHi) {
            colPut(b, cr, f, out, colRecord(s, cr.t));
            b++;
            cr = colRow(b, f, cf, h, first);
        }
        if (b == a) continue;
        if (a == r.gapLo[k]) {
            r.gapLo[k] = b;
            r.gapT[k] = cr.t;
        } else if (b >= r.gapHi[k]) {
            r.gapHi[k] = a;
        } else if (r.gaps < MAX_GAPS) {
            r.gapLo[r.gaps] = b;
            r.gapHi[r.gaps] = r.gapHi[k];
            r.gapT[r.gaps] = cr.t;
            r.gaps++;
            r.gapHi[k] = a;
        } else {
            for (int row = r.gapLo[k]; row < a; row++) colPut(row, colRow(row, f, cf, h, first), f, out, colRecord(s, s.tLo));
            r.gapLo[k] = b;
            r.gapT[k] = cr.t;
        }
    }
    int n = 0;
    for (int k = 0; k < r.gaps; k++) {
        if (r.gapLo[k] >= r.gapHi[k]) continue;
        r.gapLo[n] = r.gapLo[k];
        r.gapHi[n] = r.gapHi[k];
        r.gapT[n] = r.gapT[k];
        n++;
    }
    r.gaps = n;
    if (r.row > R1 || r.t > s.tHi) return;
    if (r.t < s.tLo) {
        int a = r.row;
        float ta = r.t;
        while (r.row <= R1 && r.t < s.tLo) colAdvance(r, f, cf, h, first);
        if (r.row > R1) {
            r.row = a;
            r.t = ta;
            ColRow cr = colRow(a, f, cf, h, first);
            r.px = cr.px;
            r.pxPrev = cr.pxPrev;
            return;
        }
        if (r.gaps < MAX_GAPS) {
            r.gapLo[r.gaps] = a;
            r.gapHi[r.gaps] = r.row;
            r.gapT[r.gaps] = ta;
            r.gaps++;
        } else {
            for (int row = a; row < r.row; row++) {
                ColRow cr = colRow(row, f, cf, h, first);
                colPut(row, cr, f, out, colRecord(s, s.tLo));
            }
        }
    }
    if (r.t >= s.tWall && r.t <= min(s.tWallTop, s.tHi)) {
        int end = colRowAbove(r.row, min(s.tWallTop, s.tHi), f, cf, h, first, R1);
        uint2 v = colRecord(s, r.t);
        for (int row = r.row; row < end; row++) {
            int px, pxPrev;
            colPx(row, f, cf, first, px, pxPrev);
            ColRow cr;
            cr.t = 0.0;
            cr.px = px;
            cr.pxPrev = pxPrev;
            colPut(row, cr, f, out, v);
        }
        if (end > r.row) {
            ColRow n = colRow(end, f, cf, h, first);
            r.row = end;
            r.t = n.t;
            r.px = n.px;
            r.pxPrev = n.pxPrev;
        }
    }
    while (r.row <= R1 && r.t <= s.tHi) {
        ColRow cr;
        cr.t = r.t;
        cr.px = r.px;
        cr.pxPrev = r.pxPrev;
        colPut(r.row, cr, f, out, colRecord(s, r.t));
        colAdvance(r, f, cf, h, first);
    }
}

static inline float colOpenT(thread const ColRows& r) {
    float t = r.t;
    for (int k = 0; k < r.gaps; k++) t = min(t, r.gapT[k]);
    return t;
}

static inline uint colColorAt(device const uint* color, constant ColFrame& f, int dataLevel, int L, int ax, int az) {
    int ln = f.levels[dataLevel].info.x, lm = (1 << ln) - 1, sh = dataLevel - L;
    return color[f.levels[dataLevel].info.y + ((((az >> sh) & lm) << ln) | ((ax >> sh) & lm))];
}

struct Walk {
    float2 h;
    float hx, hz, ihx, ihz;
    int sx, sz;
    int L;
    int ax, az;
    float tMaxX, tMaxZ;
    float dIn;
    uint face;
    float sNext;
    int logN, gOff, mOff, nMask;
    int blockX, blockZ;
    int tileX, tileZ;
};

static inline float walkCrossX(constant ColFrame& f, thread const Walk& w, int ax, int L) {
    return (float(((ax + (w.sx > 0 ? 1 : 0)) << L) - f.origin.x) - f.cam.x) * w.ihx;
}

static inline float walkCrossZ(constant ColFrame& f, thread const Walk& w, int az, int L) {
    return (float(((az + (w.sz > 0 ? 1 : 0)) << L) - f.origin.z) - f.cam.z) * w.ihz;
}

static inline int walkCellBeforeX(constant ColFrame& f, thread const Walk& w, int k, float d, thread float& t) {
    float rel = f.cam.x + d * w.hx + float(f.origin.x & ((1 << k) - 1));
    int a = (f.origin.x >> k) + int(floor(rel / float(1 << k)));
    t = walkCrossX(f, w, a, k);
    if (t < d) {
        a += w.sx;
        t = walkCrossX(f, w, a, k);
    } else {
        float t2 = walkCrossX(f, w, a - w.sx, k);
        if (t2 >= d) {
            a -= w.sx;
            t = t2;
        }
    }
    return a;
}

static inline int walkCellBeforeZ(constant ColFrame& f, thread const Walk& w, int k, float d, thread float& t) {
    float rel = f.cam.z + d * w.hz + float(f.origin.z & ((1 << k) - 1));
    int a = (f.origin.z >> k) + int(floor(rel / float(1 << k)));
    t = walkCrossZ(f, w, a, k);
    if (t < d) {
        a += w.sz;
        t = walkCrossZ(f, w, a, k);
    } else {
        float t2 = walkCrossZ(f, w, a - w.sz, k);
        if (t2 >= d) {
            a -= w.sz;
            t = t2;
        }
    }
    return a;
}

static inline float walkLevelStart(constant ColFrame& f, thread const Walk& w, int k) {
    if (k <= 0) return 0.0;
    if (k >= f.origin.w) return INFINITY;
    float d = f.levels[k - 1].range.x, tx, tz;
    walkCellBeforeX(f, w, k, d, tx);
    walkCellBeforeZ(f, w, k, d, tz);
    return min(tx, tz);
}

static inline void walkLevel(thread Walk& w, constant ColFrame& f, int L) {
    w.L = L;
    w.logN = f.levels[L].info.x;
    w.gOff = f.levels[L].info.y;
    w.mOff = f.levels[L].info.z;
    w.nMask = (1 << w.logN) - 1;
    w.blockX = w.blockZ = 0x7FFFFFFF;
    w.tileX = w.tileZ = 0x7FFFFFFF;
    w.sNext = walkLevelStart(f, w, L + 1);
}

static inline void walkInit(thread Walk& w, constant ColFrame& f, float2 h) {
    w.h = h;
    w.hx = abs(h.x) < 1e-7 ? 1e-7 : h.x;
    w.hz = abs(h.y) < 1e-7 ? 1e-7 : h.y;
    w.ihx = 1.0 / w.hx;
    w.ihz = 1.0 / w.hz;
    w.sx = w.hx > 0.0 ? 1 : -1;
    w.sz = w.hz > 0.0 ? 1 : -1;
}

static inline void walkFromCamera(thread Walk& w, constant ColFrame& f) {
    walkLevel(w, f, 0);
    w.ax = f.origin.x;
    w.az = f.origin.z;
    w.tMaxX = walkCrossX(f, w, w.ax, 0);
    w.tMaxZ = walkCrossZ(f, w, w.az, 0);
    w.dIn = 0.0;
    w.face = FACE_TOP;
}

static inline void walkFromLevel(thread Walk& w, constant ColFrame& f, int L) {
    walkLevel(w, f, L);
    float d = walkLevelStart(f, w, L), tx, tz;
    int bx = walkCellBeforeX(f, w, L, d, tx), bz = walkCellBeforeZ(f, w, L, d, tz);
    w.dIn = d;
    if (tx < tz) {
        w.ax = bx + w.sx;
        w.az = bz;
        w.face = w.sx > 0 ? FACE_XN : FACE_XP;
    } else {
        w.ax = bx;
        w.az = bz + w.sz;
        w.face = w.sz > 0 ? FACE_ZN : FACE_ZP;
    }
    w.tMaxX = walkCrossX(f, w, w.ax, L);
    w.tMaxZ = walkCrossZ(f, w, w.az, L);
}

static inline void walkStep(thread Walk& w, constant ColFrame& f) {
    if (w.tMaxX < w.tMaxZ) {
        w.dIn = w.tMaxX;
        w.ax += w.sx;
        w.face = w.sx > 0 ? FACE_XN : FACE_XP;
    } else {
        w.dIn = w.tMaxZ;
        w.az += w.sz;
        w.face = w.sz > 0 ? FACE_ZN : FACE_ZP;
    }
    if (w.dIn >= w.sNext) {
        int L = w.L + 1;
        walkLevel(w, f, L);
        w.ax >>= 1;
        w.az >>= 1;
    }
    w.tMaxX = walkCrossX(f, w, w.ax, w.L);
    w.tMaxZ = walkCrossZ(f, w, w.az, w.L);
}

static inline bool walkSkipBlock(thread Walk& w, constant ColFrame& f, uint m, int bx, int bz, int shift, float tOpen, float end, bool hidden) {
    if (m == 0u && !hidden) return false;
    float tbx = (float((((bx + (w.sx > 0 ? 1 : 0)) << shift) << w.L) - f.origin.x) - f.cam.x) * w.ihx;
    float tbz = (float((((bz + (w.sz > 0 ? 1 : 0)) << shift) << w.L) - f.origin.z) - f.cam.z) * w.ihz;
    float bOut = min(tbx, tbz);
    if (!(bOut > w.dIn) || bOut >= w.sNext || bOut > end) return false;
    if (!hidden) {
        float bmax = float(int(m) - 512) - f.cam.y;
        float tTop = bmax >= 0.0 ? bmax / max(w.dIn, 1e-3) : bmax / max(bOut, 1e-3);
        if (tTop >= tOpen) return false;
    }
    w.dIn = bOut;
    if (tbx < tbz) {
        w.ax = w.sx > 0 ? (bx + 1) << shift : (bx << shift) - 1;
        w.face = w.sx > 0 ? FACE_XN : FACE_XP;
        float t;
        w.az = walkCellBeforeZ(f, w, w.L, bOut, t);
    } else {
        w.az = w.sz > 0 ? (bz + 1) << shift : (bz << shift) - 1;
        w.face = w.sz > 0 ? FACE_ZN : FACE_ZP;
        float t;
        w.ax = walkCellBeforeX(f, w, w.L, bOut, t);
    }
    w.tMaxX = walkCrossX(f, w, w.ax, w.L);
    w.tMaxZ = walkCrossZ(f, w, w.az, w.L);
    return true;
}

static inline uint walkBlockMax(constant ColFrame& f, device const ushort* maxmip, int L, int bx, int bz, int shift) {
    for (int k = L; k < f.origin.w; k++) {
        int logN = f.levels[k].info.x, m = ((1 << logN) - 1) >> shift;
        int at = shift == 6 ? f.levels[k].info.w : f.levels[k].info.z;
        if (shift == 6 && at <= 0) return 0u;
        uint v = maxmip[at + (((bz & m) << (logN - shift)) | (bx & m))];
        if (v != 0u) return v;
        bx >>= 1;
        bz >>= 1;
    }
    return 0u;
}

static inline bool walkBlockMasked(thread const Walk& w, constant ColFrame& f, device const uint* mask, int bx, int bz) {
    if (w.L != 0 || !(w.dIn < f.maskDist.x)) return false;
    int mx = (bx >> 1) - f.mask.x, mz = (bz >> 1) - f.mask.y;
    return mx >= 0 && mz >= 0 && mx < f.mask.z && mz < f.mask.z && (mask[mz * f.mask.w + (mx >> 5)] >> (mx & 31) & 1u) != 0u;
}

static inline bool walkSkip(thread Walk& w, constant ColFrame& f, device const ushort* maxmip, device const uint* mask, float tOpen, float end) {
    int tx = w.ax >> 6, tz = w.az >> 6;
    if ((tx != w.tileX || tz != w.tileZ) && f.levels[w.L].info.w > 0) {
        w.tileX = tx;
        w.tileZ = tz;
        if (walkSkipBlock(w, f, walkBlockMax(f, maxmip, w.L, tx, tz, 6), tx, tz, 6, tOpen, end, false)) return true;
    }
    int bx = w.ax >> 3, bz = w.az >> 3;
    if (bx == w.blockX && bz == w.blockZ) return false;
    w.blockX = bx;
    w.blockZ = bz;
    if (walkBlockMasked(w, f, mask, bx, bz)) return walkSkipBlock(w, f, 0u, bx, bz, 3, tOpen, end, true);
    return walkSkipBlock(w, f, walkBlockMax(f, maxmip, w.L, bx, bz, 3), bx, bz, 3, tOpen, end, false);
}

struct CellSpans {
    ColSpan ground, crown;
    float tTop;
    float din, dout;
    bool wall;
    float yLo;
    uint spans;
    int plants;
    float pd[2];
    float pTop[2];
    float pBase;
    uint plantColor;
};

#define CROWN_RUNS 4

static inline ColSpan crownRun(thread const CellSpans& cs, float yb, float ye) {
    ColSpan s = cs.crown;
    s.tLo = yb > 0.0 ? yb / cs.dout : yb / cs.din;
    s.tHi = max(ye / cs.din, ye / cs.dout);
    s.tWall = yb > 0.0 ? yb / cs.din : -1e30;
    s.tWallTop = cs.wall ? ye / cs.din : (yb > 0.0 ? yb / cs.din : -1e30);
    s.yBottom = yb;
    s.yTop = ye;
    return s;
}

static inline int cellPlants(thread const Walk& w, constant ColFrame& f, device const uint* texWords, device const PaletteEntry* palette,
                             int idx, int blocks, float yGround, float din, float dout, thread CellSpans& cs) {
    uint n2 = 1u << uint(2 * w.logN);
    uint pa = texWords[n2 + uint(idx)], pb = texWords[2u * n2 + uint(idx)];
    uint lower = pa & 1023u, upper = (pa >> 10) & 1023u;
    if (lower == 0u) return 0;
    float ox = float((pb >> 16) & 15u) / 30.0 - 0.25, oz = float((pb >> 20) & 15u) / 30.0 - 0.25, oy = -float((pb >> 24) & 15u) / 60.0;
    float cx = f.cam.x + float(f.origin.x - w.ax) - ox, cz = f.cam.z + float(f.origin.z - w.az) - oz;
    float hx = w.hx, hz = w.hz;
    int n = 0;
    for (int q = 0; q < 2; q++) {
        float den = q == 0 ? hx - hz : hx + hz;
        if (abs(den) < 1e-6) continue;
        float d = q == 0 ? (cz - cx) / den : (1.0 - cx - cz) / den;
        if (!(d >= din && d <= dout)) continue;
        float x = cx + d * hx;
        if (x < 0.05 || x > 0.95) continue;
        float s = (x - 0.05) / 0.9;
        if ((q == 0 ? hx - hz : -(hx + hz)) < 0.0) s = 1.0 - s;
        int k = clamp(int(s * 16.0), 0, 15);
        float hTop = float(plantProfile(palette, upper != 0u ? upper : lower, k));
        float hgt = 0.0;
        if (blocks <= 1) {
            hgt = hTop / 16.0;
        } else if (hTop > 0.0) {
            hgt = float(blocks - 1) + hTop / 16.0;
        } else {
            float hLow = float(plantProfile(palette, lower, k));
            hgt = hLow > 0.0 ? float(blocks - 2) + hLow / 16.0 : 0.0;
        }
        if (hgt <= 0.0) continue;
        cs.pd[n] = d;
        cs.pTop[n] = yGround + oy + hgt;
        n++;
    }
    if (n == 2 && cs.pd[1] < cs.pd[0]) {
        float t = cs.pd[0];
        cs.pd[0] = cs.pd[1];
        cs.pd[1] = t;
        t = cs.pTop[0];
        cs.pTop[0] = cs.pTop[1];
        cs.pTop[1] = t;
    }
    cs.pBase = yGround + oy;
    cs.plantColor = pb & 0xFFFFu;
    return n;
}

static inline int walkCell(thread const Walk& w, constant ColFrame& f, device const uint* geom, device const uint* color, device const uint* mask,
                           device const uint* crowns, device const uint* texWords, device const PaletteEntry* palette, bool wantSpans,
                           float tOpen, thread CellSpans& cs) {
    int L = w.L, ax = w.ax, az = w.az;
    int idx = ((az & w.nMask) << w.logN) | (ax & w.nMask);
    uint g = geom[w.gOff + idx];
    int dataLevel = L;
    if ((g & GEOM_VALID) == 0u) {
        for (int k = L + 1; k < f.origin.w; k++) {
            int ln = f.levels[k].info.x, lm = (1 << ln) - 1, sh = k - L;
            uint g2 = geom[f.levels[k].info.y + ((((az >> sh) & lm) << ln) | ((ax >> sh) & lm))];
            if ((g2 & GEOM_VALID) != 0u) {
                g = g2;
                dataLevel = k;
                break;
            }
        }
        if ((g & GEOM_VALID) == 0u) return 0;
    }
    if (w.dIn < f.maskDist.x && L <= 4) {
        int mx = ((ax << L) >> 4) - f.mask.x, mz = ((az << L) >> 4) - f.mask.y;
        if (mx >= 0 && mz >= 0 && mx < f.mask.z && mz < f.mask.z && (mask[mz * f.mask.w + (mx >> 5)] >> (mx & 31) & 1u) != 0u) return 0;
    }
    float dOut = min(w.tMaxX, w.tMaxZ);
    float yTop = float(GEOM_Y(g)) - f.cam.y;
    float din = max(w.dIn, 1e-3), dout = max(dOut, 1e-3);
    float tGround = max(yTop / din, yTop / dout);
    cs.tTop = tGround;
    bool crown = (g & GEOM_CROWN) != 0u && dataLevel == L && L < int(f.maskDist.y);
    cs.plants = 0;
    int blocks = GEOM_PLANT_BLOCKS(g);
    if (blocks > 0 && !crown && L == 0 && dataLevel == 0 && f.maskDist.w > 0.0) {
        cs.plants = cellPlants(w, f, texWords, palette, idx, blocks, yTop, din, dout, cs);
        for (int k = 0; k < cs.plants; k++) cs.tTop = max(cs.tTop, cs.pTop[k] / cs.pd[k]);
    }
    cs.din = din;
    cs.dout = dout;
    if (!wantSpans || cs.tTop < tOpen) return 1;
    uint cw = colColorAt(color, f, dataLevel, L, ax, az);
    uint flags = ((g & GEOM_WET) != 0u ? REC_WET : 0u) | uint(dataLevel) << 20;
    bool wall = w.dIn > 1e-3 && w.face != FACE_TOP;
    cs.ground.dIn = w.dIn;
    cs.ground.dOut = dOut;
    cs.ground.face = w.face;
    cs.ground.flags = flags;
    if (crown) {
        uint cr = crowns[w.gOff + idx];
        float yGround = float(int(cr & 0xFFFu) - 512) - f.cam.y;
        float yBottom = yTop - float(GEOM_THICK(g));
        float tcLo = yBottom > 0.0 ? yBottom / dout : yBottom / din;
        cs.ground.tLo = -1e30;
        cs.ground.tHi = (yGround < 0.0 || wall) ? min(max(yGround / din, yGround / dout), tcLo - 1e-7) : -1e30;
        cs.ground.tWall = -1e30;
        cs.ground.tWallTop = wall ? yGround / din : -1e30;
        cs.ground.yBottom = yGround;
        cs.ground.yTop = yGround;
        uint gc = (cr >> 12) & 0xFFFFu;
        cs.ground.topColor = gc;
        cs.ground.sideColor = gc;
        cs.ground.flags = flags | REC_UNDER_CROWN;
        cs.crown = cs.ground;
        cs.crown.flags = flags | REC_CROWN;
        cs.crown.tLo = tcLo;
        cs.crown.tHi = cs.tTop;
        cs.crown.tWall = yBottom > 0.0 ? yBottom / din : -1e30;
        cs.crown.tWallTop = wall ? yTop / din : (yBottom > 0.0 ? yBottom / din : -1e30);
        cs.crown.yBottom = yBottom;
        cs.crown.yTop = yTop;
        cs.crown.topColor = cw & 0xFFFFu;
        cs.crown.sideColor = cw >> 16;
        cs.din = din;
        cs.dout = dout;
        cs.wall = wall;
        cs.yLo = yBottom;
        cs.spans = crowns[int(f.maskDist.z) + w.gOff + idx];
        return 2;
    }
    cs.ground.tLo = -1e30;
    cs.ground.tHi = (wall || yTop < 0.0) ? tGround : -1e30;
    cs.ground.tWall = -1e30;
    cs.ground.tWallTop = wall ? yTop / din : -1e30;
    cs.ground.yBottom = yTop;
    cs.ground.yTop = yTop;
    cs.ground.topColor = cw & 0xFFFFu;
    cs.ground.sideColor = cw >> 16;
    return 1;
}

static inline void colFillPlants(thread ColRows& r, thread const CellSpans& cs, constant ColFrame& f, device uint2* out, float cf, float2 h, bool first) {
    ColSpan g = cs.ground;
    float y = g.yTop;
    float prev = cs.din;
    for (int k = 0; k <= cs.plants; k++) {
        float dk = k < cs.plants ? max(cs.pd[k], 1e-3) : cs.dout;
        ColSpan s = g;
        s.dIn = k == 0 ? g.dIn : cs.pd[k - 1];
        s.dOut = k < cs.plants ? cs.pd[k] : g.dOut;
        if (k > 0) s.tWallTop = -1e30;
        bool seen = (k == 0 && g.tWallTop > -1e29) || y < 0.0;
        s.tHi = seen ? max(y / prev, y / dk) : -1e30;
        if (s.tHi > -1e29) colFill(r, s, f, out, cf, h, first);
        if (k < cs.plants) {
            ColSpan p = g;
            p.tLo = cs.pBase / dk;
            p.tHi = cs.pTop[k] / dk;
            p.tWall = -1e30;
            p.tWallTop = p.tHi;
            p.dIn = cs.pd[k];
            p.dOut = cs.pd[k];
            p.face = FACE_PLANT;
            p.topColor = cs.plantColor;
            p.sideColor = cs.plantColor;
            colFill(r, p, f, out, cf, h, first);
        }
        prev = dk;
    }
}

static inline void walkGuardTrip(device atomic_uint* dbg, uint c, thread const Walk& w, int row, float t) {
    if (atomic_fetch_add_explicit(&dbg[0], 1u, memory_order_relaxed) == 0u) {
        atomic_store_explicit(&dbg[1], c, memory_order_relaxed);
        atomic_store_explicit(&dbg[2], as_type<uint>(w.dIn), memory_order_relaxed);
        atomic_store_explicit(&dbg[3], uint(w.L), memory_order_relaxed);
        atomic_store_explicit(&dbg[4], uint(w.ax), memory_order_relaxed);
        atomic_store_explicit(&dbg[5], uint(w.az), memory_order_relaxed);
        atomic_store_explicit(&dbg[6], as_type<uint>(w.tMaxX), memory_order_relaxed);
        atomic_store_explicit(&dbg[7], as_type<uint>(w.tMaxZ), memory_order_relaxed);
        atomic_store_explicit(&dbg[8], as_type<uint>(w.h.x), memory_order_relaxed);
        atomic_store_explicit(&dbg[9], as_type<uint>(w.h.y), memory_order_relaxed);
        atomic_store_explicit(&dbg[10], uint(row), memory_order_relaxed);
        atomic_store_explicit(&dbg[11], as_type<uint>(t), memory_order_relaxed);
    }
}

static inline float2 colPlane(constant ColFrame& f, float cf) {
    float Xref = colX(f, cf, 1.0);
    float3 Dref = f.A.xyz * Xref + f.B.xyz * f.cols.z + f.C.xyz;
    return normalize(Dref.xz);
}

static inline void walkFill(thread Walk& w, thread ColRows& r, constant ColFrame& f, device const uint* geom, device const uint* color,
                            device const ushort* maxmip, device const uint* mask, device const uint* crowns, device const uint* texWords,
                            device const PaletteEntry* palette, device uint2* out, device atomic_uint* dbg, uint c, float cf, bool first, float end,
                            bool fillCursor) {
    const int R1 = r.rEnd;
    const float reach = f.cam.w, slope = f.lines.z;
    int guard = 0;
    const int guardMax = f.origin.w * (3 << f.levels[0].info.x) + 4096;
    while (((fillCursor && r.row <= R1) || r.gaps > 0) && w.dIn < reach && w.dIn < end) {
        if (++guard > guardMax) {
            walkGuardTrip(dbg, c, w, r.row, r.t);
            break;
        }
        float tOpen = fillCursor ? r.t : INFINITY;
        for (int k = 0; k < r.gaps; k++) tOpen = min(tOpen, r.gapT[k]);
        COL_DEBUG(c, "step L%d cell %d,%d dIn %.3f tMax %.3f,%.3f row %d t %.5f gaps %d\n", w.L, w.ax, w.az, w.dIn, w.tMaxX, w.tMaxZ, r.row, r.t, r.gaps);
        if (w.dIn > 1.0 && tOpen > slope / w.dIn) break;
        COL_STAT(0);
        if (walkSkip(w, f, maxmip, mask, tOpen, end)) {
            COL_STAT(1);
            continue;
        }
        COL_STAT(2);
        CellSpans cs;
        int n = walkCell(w, f, geom, color, mask, crowns, texWords, palette, true, tOpen, cs);
#ifdef COL_ABLATE_NOFILL
        if (n > 0 && cs.tTop >= tOpen) {
            while (r.row <= R1 && r.t <= cs.tTop) colAdvance(r, f, cf, w.h, first);
        }
        if (false) {
#else
        if (n > 0 && cs.tTop >= tOpen) {
#endif
            int saveRow = r.row;
            if (!fillCursor) r.row = R1 + 1;
            if (cs.plants > 0) colFillPlants(r, cs, f, out, cf, w.h, first);
            else if (cs.ground.tHi > -1e29) colFill(r, cs.ground, f, out, cf, w.h, first);
            if (n == 2) {
                if (cs.spans == 0u) {
                    colFill(r, cs.crown, f, out, cf, w.h, first);
                } else {
                    float y = cs.yLo;
                    for (int k = 0; k < CROWN_RUNS; k++) {
                        uint run = (cs.spans >> (8 * k)) & 255u, len = run >> 3;
                        if (len == 0u) break;
                        y += float(run & 7u);
                        ColSpan sp = crownRun(cs, y, y + float(len));
                        colFill(r, sp, f, out, cf, w.h, first);
                        y += float(len);
                    }
                }
            }
            if (!fillCursor) r.row = saveRow;
        }
        walkStep(w, f);
    }
}

static inline void colFinish(thread ColRows& r, constant ColFrame& f, device uint2* out, float cf, float2 h, bool first, bool cursor) {
    const int R1 = r.rEnd;
    for (int k = 0; k < r.gaps; k++) {
        for (int row = r.gapLo[k]; row < r.gapHi[k]; row++) colPut(row, colRow(row, f, cf, h, first), f, out, uint2(0u, 0u));
    }
    r.gaps = 0;
    if (!cursor) return;
    while (r.row <= R1) {
        ColRow cr;
        cr.t = r.t;
        cr.px = r.px;
        cr.pxPrev = r.pxPrev;
        colPut(r.row, cr, f, out, uint2(0u, 0u));
        colAdvance(r, f, cf, h, first);
    }
}

static inline void colRowsAt(thread ColRows& r, constant ColFrame& f, int row, float cf, float2 h, bool first) {
    r.row = row;
    r.rEnd = int(f.screen.w);
    r.gaps = 0;
    ColRow cr = colRow(row, f, cf, h, first);
    r.t = cr.t;
    r.px = cr.px;
    r.pxPrev = cr.pxPrev;
}

kernel void lod_columns(constant ColFrame& f [[buffer(0)]],
                        device const uint* geom [[buffer(1)]],
                        device const uint* color [[buffer(2)]],
                        device const ushort* maxmip [[buffer(3)]],
                        device const uint* mask [[buffer(4)]],
                        device uint2* out [[buffer(5)]],
                        device atomic_uint* dbg [[buffer(6)]],
                        device const uint* crowns [[buffer(7)]],
                        device const uint* texWords [[buffer(9)]],
                        device const PaletteEntry* palette [[buffer(10)]],
                        uint c [[thread_position_in_grid]]) {
    if (c >= uint(f.cols.w)) return;
    const bool first = c == 0u;
    const float cf = float(c);
    Walk w;
    walkInit(w, f, colPlane(f, cf));
    walkFromCamera(w, f);
    ColRows r;
    colRowsAt(r, f, int(f.screen.z), cf, w.h, first);
    walkFill(w, r, f, geom, color, maxmip, mask, crowns, texWords, palette, out, dbg, c, cf, first, INFINITY, true);
    colFinish(r, f, out, cf, w.h, first, true);
}

static inline float rowWork(float t, float e) {
    return t < -e ? -1.0 / t : t > e ? 4.0 / e - 1.0 / t : 2.0 / e + t / (e * e);
}

static inline int rowAtWork(float u, int lo, int hi, float e, constant ColFrame& f, float cf, float2 h, bool first) {
    int a = lo, b = hi + 1;
    while (a < b) {
        int mid = (a + b) >> 1;
        if (rowWork(colRow(mid, f, cf, h, first).t, e) >= u) b = mid;
        else a = mid + 1;
    }
    return a;
}
kernel void lod_rows(constant ColFrame& f [[buffer(0)]],
                     device const uint* geom [[buffer(1)]],
                     device const uint* color [[buffer(2)]],
                     device const ushort* maxmip [[buffer(3)]],
                     device const uint* mask [[buffer(4)]],
                     device uint2* out [[buffer(5)]],
                     device atomic_uint* dbg [[buffer(6)]],
                     device const uint* crowns [[buffer(7)]],
                     device const uint* texWords [[buffer(9)]],
                     device const PaletteEntry* palette [[buffer(10)]],
                     uint gid [[thread_position_in_grid]]) {
    uint columns = uint(f.cols.w);
    uint s = gid / columns, c = gid % columns;
    const int R0 = int(f.screen.z), R1 = int(f.screen.w), segs = max(1, int(f.lines.w));
    if (int(s) >= segs) return;
    const bool first = c == 0u;
    const float cf = float(c);
    Walk w;
    walkInit(w, f, colPlane(f, cf));
    float tA = colRow(R0, f, cf, w.h, first).t, tB = colRow(R1, f, cf, w.h, first).t;
    float e = max(4.0 * (tB - tA) / float(max(1, R1 - R0)), 1e-6);
    float uA = rowWork(tA, e), uB = rowWork(tB, e);
    int r0 = s == 0u ? R0 : rowAtWork(uA + (uB - uA) * float(s) / float(segs), R0, R1, e, f, cf, w.h, first);
    int r1 = int(s) == segs - 1 ? R1 : rowAtWork(uA + (uB - uA) * float(s + 1u) / float(segs), R0, R1, e, f, cf, w.h, first) - 1;
    if (r1 < r0) return;
    walkFromCamera(w, f);
    ColRows r;
    colRowsAt(r, f, r0, cf, w.h, first);
    r.rEnd = r1;
    walkFill(w, r, f, geom, color, maxmip, mask, crowns, texWords, palette, out, dbg, c, cf, first, INFINITY, true);
    colFinish(r, f, out, cf, w.h, first, true);
}

kernel void lod_seg_max(constant ColFrame& f [[buffer(0)]],
                        device const uint* geom [[buffer(1)]],
                        device const uint* color [[buffer(2)]],
                        device const ushort* maxmip [[buffer(3)]],
                        device const uint* mask [[buffer(4)]],
                        device float* segMax [[buffer(5)]],
                        device atomic_uint* dbg [[buffer(6)]],
                        device const uint* crowns [[buffer(7)]],
                        device const uint* texWords [[buffer(9)]],
                        device const PaletteEntry* palette [[buffer(10)]],
                        uint gid [[thread_position_in_grid]]) {
    uint columns = uint(f.cols.w);
    uint s = gid / columns, c = gid % columns;
    if (s >= uint(f.origin.w)) return;
    Walk w;
    walkInit(w, f, colPlane(f, float(c)));
    if (s == 0u) walkFromCamera(w, f);
    else walkFromLevel(w, f, int(s));
    float end = w.sNext, best = -INFINITY;
    const float reach = f.cam.w, slope = f.lines.z;
    int guard = 0;
    const int guardMax = 3 << f.levels[0].info.x;
    while (w.dIn < reach && w.dIn < end) {
        if (++guard > guardMax) {
            walkGuardTrip(dbg, c, w, -1, best);
            break;
        }
        if (w.dIn > 1.0 && best >= slope / w.dIn) break;
        if (walkSkip(w, f, maxmip, mask, best, end)) continue;
        CellSpans cs;
        if (walkCell(w, f, geom, color, mask, crowns, texWords, palette, false, INFINITY, cs) > 0) best = max(best, cs.tTop);
        walkStep(w, f);
    }
    segMax[s * columns + c] = best;
}

kernel void lod_seg_fill(constant ColFrame& f [[buffer(0)]],
                         device const uint* geom [[buffer(1)]],
                         device const uint* color [[buffer(2)]],
                         device const ushort* maxmip [[buffer(3)]],
                         device const uint* mask [[buffer(4)]],
                         device uint2* out [[buffer(5)]],
                         device atomic_uint* dbg [[buffer(6)]],
                         device const uint* crowns [[buffer(7)]],
                         device const float* segMax [[buffer(8)]],
                         device const uint* texWords [[buffer(9)]],
                         device const PaletteEntry* palette [[buffer(10)]],
                         uint gid [[thread_position_in_grid]]) {
    uint columns = uint(f.cols.w);
    uint s = gid / columns, c = gid % columns;
    int levels = f.origin.w;
    if (s >= uint(levels)) return;
    const bool first = c == 0u;
    const float cf = float(c);
    Walk w;
    walkInit(w, f, colPlane(f, cf));
    float prefix = -INFINITY;
    for (uint k = 0u; k < s; k++) prefix = max(prefix, segMax[k * columns + c]);
    const int R0 = int(f.screen.z), R1 = int(f.screen.w);
    int lo = R0, hi = R1 + 1;
    if (prefix > -INFINITY) {
        while (lo < hi) {
            int mid = (lo + hi) >> 1;
            if (colRow(mid, f, cf, w.h, first).t > prefix) hi = mid;
            else lo = mid + 1;
        }
    }
    ColRows r;
    colRowsAt(r, f, lo, cf, w.h, first);
    if (s == 0u) walkFromCamera(w, f);
    else walkFromLevel(w, f, int(s));
    walkFill(w, r, f, geom, color, maxmip, mask, crowns, texWords, palette, out, dbg, c, cf, first, w.sNext, true);
    if (r.gaps > 0 && !(w.dIn >= f.cam.w)) walkFill(w, r, f, geom, color, maxmip, mask, crowns, texWords, palette, out, dbg, c, cf, first, INFINITY, false);
    colFinish(r, f, out, cf, w.h, first, s == uint(levels - 1));
}

struct CompFrame {
    float4x4 viewProj;
    float4 A, B, C;
    float4 screen;
    float4 fogColor;
    float4 fog;
    float4 skyLight;
    float4 faceShade;
    float4 quadDepth;
    int4 origin;
    float4 camFrac;
    float4 tex;
};

struct CompVOut {
    float4 position [[position]];
};

vertex CompVOut lod_comp_vs(uint vid [[vertex_id]], constant CompFrame& f [[buffer(22)]]) {
    float2 corner = float2(float(vid == 1u || vid == 2u || vid == 4u), float(vid == 2u || vid == 4u || vid == 5u));
    float r = mix(f.screen.z, f.screen.w + 1.0, corner.y);
    CompVOut o;
    o.position = float4(corner.x * 2.0 - 1.0, 1.0 - 2.0 * r / f.screen.y, f.quadDepth.x, 1.0);
    return o;
}

struct CompSurface {
    float3 rel;
    float depth;
    half3 albedo;
    uint face;
    bool wet;
    float ao;
    float sky;
    uint debug;
};

static inline half3 comp565(uint c) {
    return half3(float((c >> 11) & 31u) / 31.0, float((c >> 5) & 63u) / 63.0, float(c & 31u) / 31.0);
}

static inline int compTop(constant CompFrame& f, device const uint* geom, int x, int z) {
    int n = 1 << f.origin.w, m = n - 1;
    uint g = geom[((z & m) << f.origin.w) | (x & m)];
    return (g & GEOM_VALID) != 0u ? GEOM_Y(g) : -100000;
}

static inline float compVertexAo(bool s1, bool s2, bool c) {
    int n = int(s1) + int(s2) + int(s1 && s2 ? true : c);
    return 1.0 - 0.2 * float(n);
}

static inline half3 compTexture(constant CompFrame& f, device const PaletteEntry* palette, texture2d<half> atlas, sampler smp, uint id, bool top,
                                float2 uv, float3 rel, float3 n, half3 albedo) {
    if (id == 0u) return albedo;
    PaletteEntry e = palette[id];
    float4 rect = top ? e.topUv : e.sideUv;
    if (rect.z <= rect.x) return albedo;
    float3 avg = max(top ? e.topAvg.rgb : e.sideAvg.rgb, float3(0.02));
    float dist = length(rel);
    float cosA = abs(dot(n, rel)) / max(dist, 1e-3);
    float lod = clamp(log2(16.0 * dist * f.tex.x / max(cosA, 0.08)), 0.0, f.tex.y);
#ifdef SEAM_THIN_TEX
    constexpr sampler smpLin(mag_filter::nearest, min_filter::linear, mip_filter::linear, address::clamp_to_edge);
    lod = clamp(lod + 0.5, 0.0, f.tex.y);
    float inset = min(0.49, 0.5 * exp2(floor(lod) + 1.0) / 16.0);
    float2 auv = rect.xy + clamp(uv, inset, 1.0 - inset) * (rect.zw - rect.xy);
    half4 t = lod > 0.0 ? atlas.sample(smpLin, auv, level(lod)) : atlas.sample(smp, auv, level(lod));
#else
    float2 auv = rect.xy + clamp(uv, 0.001, 0.999) * (rect.zw - rect.xy);
    half4 t = atlas.sample(smp, auv, level(lod));
#endif
    if (f.tex.w == 2.0) return t.rgb;
    if (f.tex.w == 3.0) return half3(half(lod / 4.0), half(t.a), 0.0h);
    float3 c = t.a < 0.5h ? avg * 0.7 : float3(t.rgb);
    return half3(clamp(c * (float3(albedo) / avg), 0.0, 1.0));
}

static inline void compPlant(constant CompFrame& f, device const uint* geom, device const uint* texWords, device const PaletteEntry* palette,
                             texture2d<half> atlas, sampler smp, thread CompSurface& s) {
    float3 p = s.rel + f.camFrac.xyz;
    int ax = int(floor(p.x)), az = int(floor(p.z));
    int gx = ax + f.origin.x, gz = az + f.origin.z;
    int n = 1 << f.origin.w, m = n - 1;
    uint idx = uint(((gz & m) << f.origin.w) | (gx & m));
    uint n2 = uint(n) * uint(n);
    uint pa = texWords[n2 + idx], pb = texWords[2u * n2 + idx];
    uint lower = pa & 1023u, upper = (pa >> 10) & 1023u;
    if (lower == 0u) return;
    int blocks = int((pa >> 20) & 3u) + 1;
    float ox = float((pb >> 16) & 15u) / 30.0 - 0.25, oz = float((pb >> 20) & 15u) / 30.0 - 0.25, oy = -float((pb >> 24) & 15u) / 60.0;
    float X = p.x - float(ax) - ox, Z = p.z - float(az) - oz;
    bool qa = abs(X - Z) < abs(X + Z - 1.0);
    float u = clamp((X - 0.05) / 0.9, 0.0, 1.0);
    if ((qa ? s.rel.x - s.rel.z : -(s.rel.x + s.rel.z)) < 0.0) u = 1.0 - u;
    float yr = p.y + float(f.origin.y) - (float(GEOM_Y(geom[idx])) + oy);
    int kb = clamp(int(floor(yr)), 0, blocks - 1);
    uint id = kb == blocks - 1 && upper != 0u ? upper : lower;
    float v = 1.0 - clamp(yr - float(kb), 0.0, 1.0);
    float3 nrm = qa ? float3(0.70710678, 0.0, -0.70710678) : float3(0.70710678, 0.0, 0.70710678);
    if (dot(nrm, s.rel) > 0.0) nrm = -nrm;
    half3 albedo = s.albedo;
    if (id != lower) albedo = half3(clamp(float3(albedo) * palette[id].sideAvg.rgb / max(palette[lower].sideAvg.rgb, float3(0.02)), 0.0, 1.0));
    s.albedo = compTexture(f, palette, atlas, smp, id, false, float2(u, v), s.rel, nrm, albedo);
    s.debug = 1u;
}

static inline bool compShade(constant CompFrame& f, uint2 v, device const uint* geom, device const uint* color, device const uint* crowns,
                             device const uint* texWords, device const PaletteEntry* palette, texture2d<half> atlas, sampler smp,
                             thread CompSurface& s);

static inline bool compSurface(constant CompFrame& f, float4 pos, device const uint2* img, device const uint* geom, device const uint* color,
                               device const uint* crowns, device const uint* texWords, device const PaletteEntry* palette, texture2d<half> atlas,
                               sampler smp, thread CompSurface& s) {
    int W = int(f.screen.x);
    int px = int(pos.x), row = int(pos.y);
    uint2 v = img[row * W + px];
    if (v.x == 0u) return false;
    float d = as_type<float>(v.x & ~7u);
    s.face = v.x & 7u;
    float X = 2.0 * pos.x / f.screen.x - 1.0, Y = 2.0 * pos.y / f.screen.y - 1.0;
    float3 D = f.A.xyz * X + f.B.xyz * Y + f.C.xyz;
    s.rel = D * (d / max(length(D.xz), 1e-6));
    float4 clip = f.viewProj * float4(s.rel, 1.0);
    s.depth = clip.z / clip.w;
    return compShade(f, v, geom, color, crowns, texWords, palette, atlas, smp, s);
}

static inline bool compShade(constant CompFrame& f, uint2 v, device const uint* geom, device const uint* color, device const uint* crowns,
                             device const uint* texWords, device const PaletteEntry* palette, texture2d<half> atlas, sampler smp,
                             thread CompSurface& s) {
    uint c = v.y & 0xFFFFu;
    s.albedo = comp565(c);
    s.wet = (v.y & REC_WET) != 0u;
    s.ao = 1.0;
    s.sky = (v.y & (REC_UNDER_CROWN)) != 0u || ((v.y & REC_CROWN) != 0u && s.face == FACE_BOTTOM) ? f.quadDepth.z : 1.0;
    uint level = (v.y >> 20) & 15u;
    bool crownHit = (v.y & REC_CROWN) != 0u;
    s.debug = level != 0u ? 6u : (v.y & REC_UNDER_CROWN) != 0u ? 5u : 0u;
    if (s.face == FACE_PLANT) {
        if (f.quadDepth.y != 0.0 && f.tex.z > 0.0) compPlant(f, geom, texWords, palette, atlas, smp, s);
        return true;
    }
    bool underCrown = (v.y & REC_UNDER_CROWN) != 0u;
    if (f.quadDepth.y == 0.0 || level != 0u || s.wet || underCrown && (s.face == FACE_TOP || f.tex.z == 0.0)) return true;
    float3 p = s.rel + f.camFrac.xyz;
    int ax, az;
    float fx, fz;
    if (s.face == FACE_XN || s.face == FACE_XP) {
        ax = int(rint(p.x)) - (s.face == FACE_XP ? 1 : 0);
        az = int(floor(p.z));
    } else if (s.face == FACE_ZN || s.face == FACE_ZP) {
        az = int(rint(p.z)) - (s.face == FACE_ZP ? 1 : 0);
        ax = int(floor(p.x));
    } else {
        ax = int(floor(p.x));
        az = int(floor(p.z));
    }
    fx = p.x - float(ax);
    fz = p.z - float(az);
    int gx = ax + f.origin.x, gz = az + f.origin.z;
    int n = 1 << f.origin.w, m = n - 1;
    int idx = ((gz & m) << f.origin.w) | (gx & m);
    uint g = geom[idx];
    if ((g & GEOM_VALID) == 0u) {
        s.debug = 7u;
        return true;
    }
    int top = GEOM_Y(g);
    float y = p.y + float(f.origin.y);
    uint tw = f.tex.z > 0.0 ? texWords[idx] : 0u;
    if (underCrown) {
        uint gid = (tw >> 20) & 1023u;
        if (gid != 0u) s.albedo = half3(palette[gid].sideAvg.rgb);
        return true;
    }
    s.debug = crownHit ? (tw != 0u ? 3u : 4u) : (tw != 0u ? 1u : 2u);
    float3 nrm = s.face == FACE_TOP ? float3(0, 1, 0) : s.face == FACE_BOTTOM ? float3(0, -1, 0) : s.face == FACE_XP ? float3(1, 0, 0)
        : s.face == FACE_XN ? float3(-1, 0, 0) : s.face == FACE_ZP ? float3(0, 0, 1) : float3(0, 0, -1);
    if (crownHit) {
        float2 cuv = s.face == FACE_TOP || s.face == FACE_BOTTOM ? float2(fx, fz) : float2(s.face <= FACE_XN ? fz : fx, 1.0 - fract(y));
        s.albedo = compTexture(f, palette, atlas, smp, s.face == FACE_TOP ? (tw & 1023u) : ((tw >> 10) & 1023u), s.face == FACE_TOP, cuv, s.rel, nrm, s.albedo);
        return true;
    }
    if (s.face == FACE_TOP) {
        s.albedo = compTexture(f, palette, atlas, smp, tw & 1023u, true, float2(fx, fz), s.rel, nrm, s.albedo);
#ifdef MESH_BENCH_NOAO
        return true;
#endif
        bool w = compTop(f, geom, gx - 1, gz) > top, e = compTop(f, geom, gx + 1, gz) > top;
        bool nn = compTop(f, geom, gx, gz - 1) > top, so = compTop(f, geom, gx, gz + 1) > top;
        bool nw = compTop(f, geom, gx - 1, gz - 1) > top, ne = compTop(f, geom, gx + 1, gz - 1) > top;
        bool sw = compTop(f, geom, gx - 1, gz + 1) > top, se = compTop(f, geom, gx + 1, gz + 1) > top;
        float a00 = compVertexAo(w, nn, nw), a10 = compVertexAo(e, nn, ne), a01 = compVertexAo(w, so, sw), a11 = compVertexAo(e, so, se);
        float u = clamp(fx, 0.0, 1.0), vv = clamp(fz, 0.0, 1.0);
        s.ao = mix(mix(a00, a10, u), mix(a01, a11, u), vv);
        return true;
    }
    if (s.face == FACE_BOTTOM) return true;
    int fxo = s.face == FACE_XN ? -1 : s.face == FACE_XP ? 1 : 0, fzo = s.face == FACE_ZN ? -1 : s.face == FACE_ZP ? 1 : 0;
    int ox = gx + fxo, oz = gz + fzo;
    int ux = fxo != 0 ? 0 : 1, uz = fxo != 0 ? 1 : 0;
    float along = fxo != 0 ? fz : fx;
    int b = int(floor(y));
    float fy = y - float(b);
#ifdef MESH_BENCH_NOAO
    int t0 = -100000, tm = -100000, tp = -100000;
#else
    int t0 = compTop(f, geom, ox, oz), tm = compTop(f, geom, ox - ux, oz - uz), tp = compTop(f, geom, ox + ux, oz + uz);
#endif
    bool belowFront = b - 1 < t0, aboveFront = b + 1 < t0;
    bool sideM = b < tm, sideP = b < tp, cMb = b - 1 < tm, cPb = b - 1 < tp, cMt = b + 1 < tm, cPt = b + 1 < tp;
    float a00 = compVertexAo(belowFront, sideM, cMb), a10 = compVertexAo(belowFront, sideP, cPb);
    float a01 = compVertexAo(aboveFront, sideM, cMt), a11 = compVertexAo(aboveFront, sideP, cPt);
    float u = clamp(along, 0.0, 1.0), vv = clamp(fy, 0.0, 1.0);
    s.ao = mix(mix(a00, a10, u), mix(a01, a11, u), vv);
    float depth = float(top) - y;
    float2 wuv = float2(along, 1.0 - fy);
    if ((g & GEOM_FRINGE) != 0u && depth < 3.0 / 16.0) {
        s.albedo = comp565(color[idx] & 0xFFFFu);
    } else if (depth >= 1.0 && f.quadDepth.w > 0.0) {
        s.albedo = compTexture(f, palette, atlas, smp, (tw >> 20) & 1023u, false, wuv, s.rel, nrm, comp565((crowns[idx] >> 12) & 0xFFFFu));
    } else {
        s.albedo = compTexture(f, palette, atlas, smp, (tw >> 10) & 1023u, false, wuv, s.rel, nrm, s.albedo);
    }
    return true;
}

struct CompVanillaOut {
    half4 color [[color(0)]];
    float depth [[depth(less)]];
};

static inline half3 compFog(constant CompFrame& f, float3 rel, half3 lit) {
    float cyl = max(length(rel.xz), abs(rel.y));
    float sph = length(rel);
    float fr = saturate((cyl - f.fog.x) / max(f.fog.y - f.fog.x, 1e-3));
    float fe = saturate((sph - f.fog.z) / max(f.fog.w - f.fog.z, 1e-3));
    return mix(lit, half3(f.fogColor.rgb), half(max(fr, fe) * f.fogColor.a));
}

fragment CompVanillaOut lod_comp_vanilla(CompVOut in [[stage_in]], constant CompFrame& f [[buffer(22)]], device const uint2* img [[buffer(23)]],
                                         device const uint* geom [[buffer(24)]], device const uint* color [[buffer(25)]],
                                         device const uint* crowns [[buffer(26)]], device const uint* texWords [[buffer(27)]],
                                         device const PaletteEntry* palette [[buffer(28)]], texture2d<half> atlas [[texture(20)]],
                                         sampler smp [[sampler(14)]]) {
    CompSurface s;
    if (!compSurface(f, in.position, img, geom, color, crowns, texWords, palette, atlas, smp, s)) discard_fragment();
    float shade = s.face == FACE_TOP || s.face == FACE_PLANT ? f.faceShade.x : s.face == FACE_BOTTOM ? f.faceShade.y : (s.face <= FACE_XN ? f.faceShade.w : f.faceShade.z);
    CompVanillaOut o;
    o.color = half4(compFog(f, s.rel, s.albedo * half3(shade * s.ao * s.sky * f.skyLight.rgb)), 1.0h);
    if (f.tex.w == 1.0) {
        const half3 dc[8] = {half3(0.5h), half3(0, 1, 0), half3(1, 0, 0), half3(0, 0, 1), half3(1, 0, 1), half3(0, 1, 1), half3(1, 1, 0), half3(0)};
        o.color = half4(dc[s.debug & 7u] * half(0.5 + 0.5 * s.ao), 1.0h);
    }
    o.depth = s.depth;
    return o;
}

struct CompGBufferOut {
    half4 albedo [[color(1)]];
    half4 normal [[color(2)]];
    half4 light [[color(3)]];
    float gdepth [[color(4)]];
    float depth [[depth(less)]];
};

static inline float2 compOctWrap(float2 v) { return (1.0 - abs(v.yx)) * select(float2(-1.0), float2(1.0), v.xy >= 0.0); }
static inline half2 compOctEncode(float3 n) {
    n /= (abs(n.x) + abs(n.y) + abs(n.z));
    float2 e = n.z >= 0.0 ? n.xy : compOctWrap(n.xy);
    return half2(e * 0.5 + 0.5);
}

fragment CompGBufferOut lod_comp_gbuffer(CompVOut in [[stage_in]], constant CompFrame& f [[buffer(22)]], device const uint2* img [[buffer(23)]],
                                         device const uint* geom [[buffer(24)]], device const uint* color [[buffer(25)]],
                                         device const uint* crowns [[buffer(26)]], device const uint* texWords [[buffer(27)]],
                                         device const PaletteEntry* palette [[buffer(28)]], texture2d<half> atlas [[texture(20)]],
                                         sampler smp [[sampler(14)]]) {
    CompSurface s;
    if (!compSurface(f, in.position, img, geom, color, crowns, texWords, palette, atlas, smp, s)) discard_fragment();
    float3 n = s.face == FACE_TOP || s.face == FACE_PLANT ? float3(0, 1, 0) : s.face == FACE_BOTTOM ? float3(0, -1, 0) : s.face == FACE_XP ? float3(1, 0, 0)
        : s.face == FACE_XN ? float3(-1, 0, 0) : s.face == FACE_ZP ? float3(0, 0, 1) : float3(0, 0, -1);
    CompGBufferOut o;
    o.albedo = half4(s.albedo, 1.0h);
    uint mat = s.wet ? 5u : 0u;
    o.normal = half4(compOctEncode(n), half(s.ao), half(float(mat) / 255.0));
    o.light = half4(0.0h, half(s.sky < 1.0 ? 13.0 / 15.0 : 1.0), 0.0h, 0.0h);
    o.gdepth = s.depth;
    o.depth = s.depth;
    return o;
}

#define MESH_Q 32
#define MESH_GROUPS 16
#define MESH_HDR 32
#define HZ_BINS 4096
#define HZ_BANDS 128
#define HZ_FARMIN (HZ_BINS * HZ_BANDS)

struct MeshFrame {
    float4x4 viewProj;
    float4 cam;
    int4 origin;
    int4 mask;
    float4 maskDist;
    int4 counts;
    int4 win[MAX_LEVELS];
    float4 sw[3];
    int4 opts;
    float4 hz;
    int4 caps;
};

static inline uint hzKey(float v) {
    uint u = as_type<uint>(v);
    return (u & 0x80000000u) != 0u ? ~u : u | 0x80000000u;
}

static inline float hzValue(uint k) {
    return as_type<float>((k & 0x80000000u) != 0u ? k & 0x7FFFFFFFu : ~k);
}

static inline int hzBand(constant MeshFrame& f, float d) {
    return d <= f.hz.x ? -1 : min(int(log(d / f.hz.x) * f.hz.y), HZ_BANDS - 1);
}

static inline float hzAzimuth(float2 p) {
#ifdef HZ_ATAN
    return (atan2(p.y, p.x) + M_PI_F) * (2.0 / M_PI_F);
#else
    float q = p.x / max(abs(p.x) + abs(p.y), 1e-20);
    return p.y >= 0.0 ? 1.0 - q : 3.0 + q;
#endif
}

static inline float2 hzRange(thread const float2* p, int n) {
    float a0 = hzAzimuth(p[0]), lo = 0.0, hi = 0.0;
    for (int i = 1; i < n; i++) {
        float d = hzAzimuth(p[i]) - a0;
        d = d > 2.0 ? d - 4.0 : d < -2.0 ? d + 4.0 : d;
        lo = min(lo, d);
        hi = max(hi, d);
    }
    return float2(a0 + lo, a0 + hi);
}

static inline int hzBin(float a) {
    return int(floor(a * (float(HZ_BINS) / 4.0)));
}

static inline void hzOccluder(constant MeshFrame& f, device atomic_uint* hz, float x0, float x1, float z0, float z1, float y
#ifdef HZ_DEBUG
                              , device float4* dbg
#endif
                              ) {
    if (x0 <= 0.0 && x1 >= 0.0 && z0 <= 0.0 && z1 >= 0.0) return;
    float nx = max(0.0, max(x0, -x1)), nz = max(0.0, max(z0, -z1));
    float fx = max(abs(x0), abs(x1)), fz = max(abs(z0), abs(z1));
    float dn = sqrt(nx * nx + nz * nz), df = sqrt(fx * fx + fz * fz);
    int band = hzBand(f, df);
    if (band < 0 || band >= HZ_BANDS - 1) return;
    float tau = y > 0.0 ? y / df : y / max(dn, 1e-3);
    float2 c[4] = {float2(x0, z0), float2(x1, z0), float2(x0, z1), float2(x1, z1)};
    float2 r = hzRange(c, 4);
    int b0 = hzBin(r.x) + 1, b1 = hzBin(r.y) - 1;
    if (b1 - b0 > 256) return;
    uint k = hzKey(tau);
#ifdef HZ_DEBUG
    {
        uint at = atomic_fetch_add_explicit((device atomic_uint*) dbg, 1u, memory_order_relaxed);
        if (at < 400000u) {
            dbg[2 * at + 2] = float4(x0, x1, z0, z1);
            dbg[2 * at + 3] = float4(y, tau, float(band), float(b0) + float(b1) / 65536.0);
        }
    }
#endif
    for (int b = b0; b <= b1; b++) {
        int bb = (b % HZ_BINS + HZ_BINS) % HZ_BINS;
        atomic_fetch_max_explicit(&hz[bb * HZ_BANDS + band], k, memory_order_relaxed);
    }
}

kernel void lod_mesh_hz_clear(device uint* hz [[buffer(8)]], uint gid [[thread_position_in_grid]]) {
    if (gid < uint(HZ_BINS * HZ_BANDS)) hz[gid] = 0u;
    if (gid < uint(HZ_BINS)) hz[HZ_FARMIN + gid] = 0xFFFFFFFFu;
}

kernel void lod_mesh_hz_prefix(device uint* hz [[buffer(8)]], uint gid [[thread_position_in_grid]]) {
    if (gid >= uint(HZ_BINS)) return;
    uint m = 0u;
    for (int k = 0; k < HZ_BANDS; k++) {
        uint v = hz[gid * HZ_BANDS + uint(k)];
        m = max(m, v);
        hz[gid * HZ_BANDS + uint(k)] = m;
    }
}

static inline float hzFootprint(constant MeshFrame& f, device const uint* hz, float2 lo, float2 hi, thread float& dn, thread float& df) {
    dn = 0.0;
    df = 1.0;
    if (lo.x <= 0.0 && hi.x >= 0.0 && lo.y <= 0.0 && hi.y >= 0.0) return -INFINITY;
    float nx = max(0.0, max(lo.x, -hi.x)), nz = max(0.0, max(lo.y, -hi.y));
    float fx = max(abs(lo.x), abs(hi.x)), fz = max(abs(lo.y), abs(hi.y));
    dn = sqrt(nx * nx + nz * nz);
    df = sqrt(fx * fx + fz * fz);
    int band = hzBand(f, dn) - 1;
    if (band < 0) return -INFINITY;
    float2 c[4] = {lo, float2(hi.x, lo.y), float2(lo.x, hi.y), hi};
    float2 r = hzRange(c, 4);
    int b0 = hzBin(r.x), b1 = hzBin(r.y);
    if (b1 - b0 > 32) return -INFINITY;
    float m = INFINITY;
    for (int b = b0; b <= b1; b++) {
        int bb = (b % HZ_BINS + HZ_BINS) % HZ_BINS;
        uint k = hz[bb * HZ_BANDS + band];
        if (k == 0u) return -INFINITY;
        m = min(m, hzValue(k));
    }
    return m;
}

static inline float hzTangent(float y, float dn, float df) {
    return y > 0.0 ? y / max(dn, 1e-3) : y / df;
}

static inline bool hzHidden(constant MeshFrame& f, device const uint* hz, float2 lo, float2 hi, float y) {
    if (lo.x <= 0.0 && hi.x >= 0.0 && lo.y <= 0.0 && hi.y >= 0.0) return false;
    float nx = max(0.0, max(lo.x, -hi.x)), nz = max(0.0, max(lo.y, -hi.y));
    float fx = max(abs(lo.x), abs(hi.x)), fz = max(abs(lo.y), abs(hi.y));
    float dn = sqrt(nx * nx + nz * nz), df = sqrt(fx * fx + fz * fz);
    int band = hzBand(f, dn) - 1;
    if (band < 0) return false;
    float t = y > 0.0 ? y / max(dn, 1e-3) : y / df;
    float2 c[4] = {lo, float2(hi.x, lo.y), float2(lo.x, hi.y), hi};
    float2 r = hzRange(c, 4);
    int b0 = hzBin(r.x), b1 = hzBin(r.y);
    if (b1 - b0 > 32) return false;
    for (int b = b0; b <= b1; b++) {
        int bb = (b % HZ_BINS + HZ_BINS) % HZ_BINS;
        if (!(hzValue(hz[bb * HZ_BANDS + band]) > t)) return false;
    }
    return true;
}

#define PK_AZ 64
#define PK_BANDS 2
#define PK_SECTORS (PK_AZ * PK_BANDS)
#define PK_SECTOR_WORDS 16

struct PkParams {
    uint4 rebuild;
    uint4 drawable;
    uint4 live;
    uint2 needed;
    uint2 liveNeeded;
    uint recCap, plantCap, standCap;
    uint standBase;
    uint recCap0;
    float needFar;
    float liveFar;
    uint pad;
};

static inline bool pkHas(uint2 m, int s) {
    return ((s < 32 ? m.x >> uint(s) : m.y >> uint(s - 32)) & 1u) != 0u;
}

static inline bool pkHas4(uint4 m, int s) {
    uint w = s < 64 ? (s < 32 ? m.x : m.y) : (s < 96 ? m.z : m.w);
    return ((w >> uint(s & 31)) & 1u) != 0u;
}

static inline uint pkRecBase(constant PkParams& pk, uint s) {
    return s < uint(PK_AZ) ? s * pk.recCap0 : uint(PK_AZ) * pk.recCap0 + (s - uint(PK_AZ)) * pk.recCap;
}

static inline uint pkRecCap(constant PkParams& pk, uint s) {
    return s < uint(PK_AZ) ? pk.recCap0 : pk.recCap;
}

static inline int pkSector(float a) {
    a -= 4.0 * floor(a * 0.25);
    return clamp(int(floor(a * (float(PK_AZ) / 4.0))), 0, PK_AZ - 1);
}

static inline int pkHomeSector(constant MeshFrame& f, float sx, float sz) {
    float2 p = float2(sx * 0.5 - f.maskDist.y, sz * 0.5 - f.maskDist.z);
    return pkSector(hzAzimuth(p)) + (length(p) >= f.hz.w ? PK_AZ : 0);
}

static inline bool pkTouches(uint2 m, float2 r) {
    int s0 = int(floor(r.x * (float(PK_AZ) / 4.0))), s1 = int(floor(r.y * (float(PK_AZ) / 4.0)));
    if (s1 - s0 >= PK_AZ - 1) return true;
    for (int s = s0; s <= s1; s++)
        if (pkHas(m, (s % PK_AZ + PK_AZ) % PK_AZ)) return true;
    return false;
}

static inline bool pkBinIn(uint2 m, int b) {
    return pkHas(m, b / (HZ_BINS / PK_AZ));
}

static inline void hzOccluderRun(constant MeshFrame& f, device atomic_uint* hz, float x0, float x1, float z0, float z1, float y, float by, float eps,
                                 uint2 needed) {
    if (x0 <= eps && x1 >= -eps && z0 <= eps && z1 >= -eps) return;
    float nx = max(0.0, max(x0, -x1)), nz = max(0.0, max(z0, -z1));
    float fx = max(abs(x0), abs(x1)), fz = max(abs(z0), abs(z1));
    float dn = sqrt(nx * nx + nz * nz) - eps, df = sqrt(fx * fx + fz * fz) + eps;
    if (dn <= 1e-3) return;
    int band = hzBand(f, df);
    if (band < 0 || band >= HZ_BANDS - 1) return;
    float yy = y - eps, bb = by + eps;
    if (bb >= yy) return;
    float tau = yy > 0.0 ? yy / df : yy / dn, tb = bb > 0.0 ? bb / dn : bb / df;
    float2 c[4] = {float2(x0, z0), float2(x1, z0), float2(x0, z1), float2(x1, z1)};
    float2 r = hzRange(c, 4);
    float m = eps > 0.0 ? 1.01 * eps / dn : 0.0;
    int b0 = hzBin(r.x + m) + 1, b1 = hzBin(r.y - m) - 1;
    if (b1 - b0 > 256) return;
    uint k = hzKey(tau), kb = hzKey(tb);
    for (int b = b0; b <= b1; b++) {
        int bin = (b % HZ_BINS + HZ_BINS) % HZ_BINS;
        if (!pkBinIn(needed, bin)) continue;
        if (atomic_load_explicit(&hz[HZ_FARMIN + bin], memory_order_relaxed) < kb) continue;
        atomic_fetch_max_explicit(&hz[bin * HZ_BANDS + band], k, memory_order_relaxed);
    }
}

static inline void hzOccluderEps(constant MeshFrame& f, device atomic_uint* hz, float x0, float x1, float z0, float z1, float y, float eps, uint2 needed) {
    if (x0 <= eps && x1 >= -eps && z0 <= eps && z1 >= -eps) return;
    float nx = max(0.0, max(x0, -x1)), nz = max(0.0, max(z0, -z1));
    float fx = max(abs(x0), abs(x1)), fz = max(abs(z0), abs(z1));
    float dn = sqrt(nx * nx + nz * nz) - eps, df = sqrt(fx * fx + fz * fz) + eps;
    if (dn <= 1e-3) return;
    int band = hzBand(f, df);
    if (band < 0 || band >= HZ_BANDS - 1) return;
    float yy = y - eps;
    float tau = yy > 0.0 ? yy / df : yy / dn;
    float2 c[4] = {float2(x0, z0), float2(x1, z0), float2(x0, z1), float2(x1, z1)};
    float2 r = hzRange(c, 4);
    float m = 1.01 * eps / dn;
    int b0 = hzBin(r.x + m) + 1, b1 = hzBin(r.y - m) - 1;
    if (b1 - b0 > 256) return;
    uint k = hzKey(tau);
    for (int b = b0; b <= b1; b++) {
        int bb = (b % HZ_BINS + HZ_BINS) % HZ_BINS;
        if (pkBinIn(needed, bb)) atomic_fetch_max_explicit(&hz[bb * HZ_BANDS + band], k, memory_order_relaxed);
    }
}

static inline float hzFootprintEps(constant MeshFrame& f, device const uint* hz, float2 lo, float2 hi, thread float& dn, thread float& df, float eps,
                                   uint2 needed) {
    dn = 0.0;
    df = 1.0;
    if (lo.x <= eps && hi.x >= -eps && lo.y <= eps && hi.y >= -eps) return -INFINITY;
    float nx = max(0.0, max(lo.x, -hi.x)), nz = max(0.0, max(lo.y, -hi.y));
    float fx = max(abs(lo.x), abs(hi.x)), fz = max(abs(lo.y), abs(hi.y));
    dn = sqrt(nx * nx + nz * nz) - eps;
    df = sqrt(fx * fx + fz * fz) + eps;
    if (dn <= 1e-3) return -INFINITY;
    int band = hzBand(f, dn) - 1;
    if (band < 0) return -INFINITY;
    float2 c[4] = {lo, float2(hi.x, lo.y), float2(lo.x, hi.y), hi};
    float2 r = hzRange(c, 4);
    float m = 1.01 * eps / dn;
    int b0 = hzBin(r.x - m), b1 = hzBin(r.y + m);
    if (b1 - b0 > 32) return -INFINITY;
    float v = INFINITY;
    for (int b = b0; b <= b1; b++) {
        int bb = (b % HZ_BINS + HZ_BINS) % HZ_BINS;
        if (!pkBinIn(needed, bb)) return -INFINITY;
        uint k = hz[bb * HZ_BANDS + band];
        if (k == 0u) return -INFINITY;
        v = min(v, hzValue(k));
    }
    return v;
}

static inline bool hzHiddenEps(constant MeshFrame& f, device const uint* hz, float2 lo, float2 hi, float y, float eps, uint2 needed) {
    float dn, df;
    float v = hzFootprintEps(f, hz, lo, hi, dn, df, eps, needed);
    return v > -INFINITY && hzTangent(y + eps, dn, df) < v;
}

static inline float meshSwitch(constant MeshFrame& f, int L) {
    return f.sw[L >> 2][L & 3];
}

static inline bool meshResident(constant MeshFrame& f, device const uint4* table, int L, int tx, int tz, thread uint4& e) {
    int tps = f.counts.x, m = tps - 1;
    e = table[uint(L * tps * tps + (tz & m) * tps + (tx & m))];
    return e.x != 0u && int(e.y) == tx && int(e.z) == tz;
}

template <bool SEL = false>
static inline float2 meshBlockDist(constant MeshFrame& f, int L, int bx, int bz) {
    int B = f.counts.y;
    float cx = SEL ? f.cam.x + f.maskDist.y : f.cam.x, cz = SEL ? f.cam.z + f.maskDist.z : f.cam.z;
    float x0 = float((bx * B << L) - f.origin.x) - cx, z0 = float((bz * B << L) - f.origin.z) - cz;
    float s = float(B << L);
    float x1 = x0 + s, z1 = z0 + s;
    float nx = max(0.0, max(x0, -x1)), nz = max(0.0, max(z0, -z1));
    float fx = max(abs(x0), abs(x1)), fz = max(abs(z0), abs(z1));
    return float2(sqrt(nx * nx + nz * nz), sqrt(fx * fx + fz * fz));
}

static inline int meshTileOf(constant MeshFrame& f, int b) {
    return b >> (f.counts.y == 16 ? 2 : 3);
}

template <bool SEL = false>
static inline bool meshMasked(constant MeshFrame& f, device const uint* mask, int L, int bx, int bz) {
    if (f.mask.z <= 0 || !(meshBlockDist<SEL>(f, L, bx, bz).x < f.maskDist.x)) return false;
    int B = f.counts.y;
    int c0x = (bx * B << L) >> 4, c1x = (((bx + 1) * B << L) - 1) >> 4;
    int c0z = (bz * B << L) >> 4, c1z = (((bz + 1) * B << L) - 1) >> 4;
    if (c1x - c0x > 3 || c1z - c0z > 3) return false;
    for (int cz = c0z; cz <= c1z; cz++) {
        for (int cx = c0x; cx <= c1x; cx++) {
            int mx = cx - f.mask.x, mz = cz - f.mask.y;
            if (mx < 0 || mz < 0 || mx >= f.mask.z || mz >= f.mask.z) return false;
            if ((mask[mz * f.mask.w + (mx >> 5)] >> (mx & 31) & 1u) == 0u) return false;
        }
    }
    return true;
}

template <bool SEL = false>
static inline bool meshRightLevelNoMask(constant MeshFrame& f, int L, int bx, int bz) {
    int top = f.origin.w - 1;
    float2 d = meshBlockDist<SEL>(f, L, bx, bz);
    if (L < top) {
        if (!(meshBlockDist<SEL>(f, L + 1, bx >> 1, bz >> 1).y < meshSwitch(f, L))) return false;
    } else if (!(d.x < f.cam.w)) {
        return false;
    }
    return !(L > 0 && d.y < meshSwitch(f, L - 1));
}

template <bool SEL = false>
static inline bool meshRightLevel(constant MeshFrame& f, device const uint* mask, int L, int bx, int bz) {
    int top = f.origin.w - 1;
    float2 d = meshBlockDist<SEL>(f, L, bx, bz);
    if (L < top) {
        if (!(meshBlockDist<SEL>(f, L + 1, bx >> 1, bz >> 1).y < meshSwitch(f, L))) return false;
    } else if (!(d.x < f.cam.w)) {
        return false;
    }
    if (L > 0 && d.y < meshSwitch(f, L - 1)) return false;
    return !meshMasked<SEL>(f, mask, L, bx, bz);
}

static inline int meshDrawLevel(constant MeshFrame& f, device const uint4* table, int L, int bx, int bz, thread uint4& e) {
    int top = f.origin.w - 1;
    for (int K = L; K <= top; K++) {
        if (meshResident(f, table, K, meshTileOf(f, bx), meshTileOf(f, bz), e)) return K;
        bx >>= 1;
        bz >>= 1;
    }
    return -1;
}

static inline bool meshInFrustum(constant MeshFrame& f, float3 lo, float3 hi) {
    float4x4 m = f.viewProj;
    float4 r0 = float4(m[0][0], m[1][0], m[2][0], m[3][0]), r1 = float4(m[0][1], m[1][1], m[2][1], m[3][1]);
    float4 r3 = float4(m[0][3], m[1][3], m[2][3], m[3][3]);
    float4 planes[4] = {r3 + r0, r3 - r0, r3 + r1, r3 - r1};
    for (int i = 0; i < 4; i++) {
        float3 n = planes[i].xyz;
        float3 p = select(lo, hi, n > 0.0);
        if (dot(n, p) + planes[i].w < 0.0) return false;
    }
    return true;
}

#define MESH_DRAW_Q 64
#define MESH_QUAD_TG 64
#define ARGS_CAND 13
#define ARGS_SURV 14
#define ARGS_LIST 22

kernel void lod_mesh_reset(device uint* args [[buffer(4)]], uint gid [[thread_position_in_grid]]) {
    if (gid != 0u) return;
    for (int k = 0; k < 128; k++) args[k] = 0u;
    args[0] = MESH_DRAW_Q * 6u;
    args[5] = MESH_Q * 6u;
}

kernel void lod_mesh_finish_list(constant MeshFrame& f [[buffer(0)]], device uint* args [[buffer(4)]], uint gid [[thread_position_in_grid]]) {
    if (gid != 0u) return;
    args[ARGS_LIST] = min(args[ARGS_LIST], uint(f.caps.y));
    args[19] = (args[ARGS_LIST] + 63u) / 64u;
    args[20] = 1u;
    args[21] = 1u;
}

kernel void lod_mesh_finish(constant MeshFrame& f [[buffer(0)]], device uint* args [[buffer(4)]], uint gid [[thread_position_in_grid]]) {
    if (gid != 0u) return;
    args[ARGS_CAND] = min(args[ARGS_CAND], uint(f.counts.z));
    args[6] = min(args[6], uint(f.counts.w));
    args[0] = MESH_Q * 6u;
    args[1] = args[ARGS_CAND];
    args[16] = (args[ARGS_CAND] * MESH_Q + MESH_QUAD_TG - 1u) / MESH_QUAD_TG;
    args[17] = 1u;
    args[18] = 1u;
    args[25] = args[ARGS_CAND];
    args[26] = 1u;
    args[27] = 1u;
}

kernel void lod_mesh_finish2(constant MeshFrame& f [[buffer(0)]], device uint* args [[buffer(4)]], uint gid [[thread_position_in_grid]]) {
    if (gid != 0u) return;
    uint n = min(args[ARGS_SURV], uint(f.caps.x));
    args[ARGS_SURV] = n;
    args[0] = MESH_DRAW_Q * 6u;
    args[1] = (n + MESH_DRAW_Q - 1u) / MESH_DRAW_Q;
}

struct MeshInstance {
    uint4 a;
    int4 b;
};

#define MESH_REL (1u << 15)

static inline bool meshResolve(constant MeshFrame& f, device const uint4* table, thread MeshInstance& mi) {
    if ((mi.a.y & MESH_REL) == 0u) return true;
    uint4 e;
    if (!meshResident(f, table, int((mi.a.y >> 8) & 15u), int(mi.a.z), int(mi.a.w), e)) return false;
    int bpt = 64 / f.counts.y;
    mi.a.x += (e.x - 1u + uint(bpt * bpt * MESH_HDR)) / 2u;
    mi.a.y &= ~MESH_REL;
    return true;
}

static inline void meshEmit(constant MeshFrame& f, device atomic_uint* args, device MeshInstance* inst, int counter, uint cap, uint first, uint n,
                            uint tag, int tx, int tz, int4 clip) {
    uint need = (n + MESH_Q - 1u) / MESH_Q;
    uint at = atomic_fetch_add_explicit(&args[counter], need, memory_order_relaxed);
    for (uint k = 0u; k < n; k += MESH_Q) {
        if (at < cap) {
            MeshInstance m;
            m.a = uint4(first + k, min(uint(MESH_Q), n - k) | tag, uint(tx), uint(tz));
            m.b = clip;
            inst[at] = m;
        }
        at++;
    }
}

static inline float meshPartBottom(constant MeshFrame& f, device const uint* h, int k) {
    uint sh = 24u + uint(k & 1) * 4u;
    uint e = (h[16 + (k >> 1)] >> sh & 15u) | (h[24 + (k >> 1)] >> sh & 15u) << 4;
    return e == 0u ? -1e30 : float(int(e) * 2 - 66 - f.origin.y) - f.cam.y;
}

template <int MODE>
static inline void meshCullBlock(constant MeshFrame& f, device const uint4* table, device const uint* arena, device const uint* mask,
                                 device atomic_uint* args, device atomic_uint* hz, device uint4* blocks, constant PkParams* pk,
#ifdef HZ_DEBUG
                                 device float4* dbg,
#endif
                                 uint gid) {
    constexpr bool PK = MODE == 1, LIVE = MODE == 2;
    int tps = f.counts.x, B = f.counts.y, bpt = 64 / B, bpw = tps * bpt;
    uint perLevel = uint(bpw * bpw);
    int L = int(gid / perLevel);
    if (L >= f.origin.w) return;
    uint i = gid - uint(L) * perLevel;
    int bx = f.win[L].x * bpt + int(i % uint(bpw)), bz = f.win[L].y * bpt + int(i / uint(bpw));
    if (PK) {
        float bx0 = float((bx * B << L) - f.origin.x) - f.cam.x, bz0 = float((bz * B << L) - f.origin.z) - f.cam.z, bs = float(B << L);
        float2 c[4] = {float2(bx0, bz0), float2(bx0 + bs, bz0), float2(bx0, bz0 + bs), float2(bx0 + bs, bz0 + bs)};
        bool inside = bx0 <= 0.0 && bx0 + bs >= 0.0 && bz0 <= 0.0 && bz0 + bs >= 0.0;
        if (!inside && !pkTouches(pk->needed, hzRange(c, 4))) return;
        float nx = max(0.0, max(bx0, -(bx0 + bs))), nz = max(0.0, max(bz0, -(bz0 + bs)));
        if (nx * nx + nz * nz > pk->needFar * pk->needFar) return;
    }
    if (LIVE && !(meshBlockDist<true>(f, L, bx, bz).x < f.maskDist.w)) {
        if ((pk->liveNeeded.x | pk->liveNeeded.y) == 0u) return;
        float bx0 = float((bx * B << L) - f.origin.x) - f.cam.x, bz0 = float((bz * B << L) - f.origin.z) - f.cam.z, bs = float(B << L);
        float2 c[4] = {float2(bx0, bz0), float2(bx0 + bs, bz0), float2(bx0, bz0 + bs), float2(bx0 + bs, bz0 + bs)};
        bool inside = bx0 <= 0.0 && bx0 + bs >= 0.0 && bz0 <= 0.0 && bz0 + bs >= 0.0;
        if (!inside && !pkTouches(pk->liveNeeded, hzRange(c, 4))) return;
        float nx = max(0.0, max(bx0, -(bx0 + bs))), nz = max(0.0, max(bz0, -(bz0 + bs)));
        if (nx * nx + nz * nz > pk->liveFar * pk->liveFar) return;
    }
    if (f.opts.x != 0 && ((f.opts.x >> L) & 1) == 0) return;
    bool fake = f.opts.w != 0;
    bool occOnly = false;
    if (fake) {
        if (L != 0 || !meshMasked(f, mask, 0, bx, bz)) return;
    } else if (!meshRightLevel<(MODE != 0)>(f, mask, L, bx, bz)) {
        if (L != 0 || (f.opts.z & 16) == 0 || !meshRightLevelNoMask<(MODE != 0)>(f, L, bx, bz)) return;
        occOnly = true;
    }
    uint4 e;
    int K = fake ? (meshResident(f, table, 0, meshTileOf(f, bx), meshTileOf(f, bz), e) ? 0 : -1) : meshDrawLevel(f, table, L, bx, bz, e);
    if (K < 0) return;
    int sh = K - L, kx = bx >> sh, kz = bz >> sh;
    uint hdr = e.x - 1u;
    uint blk = uint((kz & (bpt - 1)) * bpt + (kx & (bpt - 1)));
    device const uint* h = arena + hdr + blk * MESH_HDR;
    uint info = h[15];
    float yLo = float(int(info & 0xFFFFu) - 512 - f.origin.y) - f.cam.y, yHi = float(int(info >> 16) - 512 - f.origin.y) - f.cam.y;
    int ox0 = (bx * B << L) - f.origin.x, oz0 = (bz * B << L) - f.origin.z, size = B << L;
    int kx0 = (kx * B << K) - f.origin.x, kz0 = (kz * B << K) - f.origin.z, ksize = B << K;
    float x0 = float(ox0) - f.cam.x, z0 = float(oz0) - f.cam.z;
    float x1 = x0 + float(size), z1 = z0 + float(size), c = float(1 << K);
    bool body = PK || meshInFrustum(f, float3(x0, yLo, z0), float3(x1, yHi, z1));
    bool skirts = body || meshInFrustum(f, float3(x0, float(-512 - f.origin.y) - f.cam.y, z0), float3(x1, yHi, z1));
    if (!skirts) return;
    bool pkOccludes = false, pkHome = false, liveList = true;
    if (MODE != 0) {
        bool ring = meshBlockDist<true>(f, L, bx, bz).x < f.maskDist.w;
        if (PK && ring) {
            if ((f.opts.z & 16) == 0) return;
            occOnly = true;
        }
        if (LIVE) liveList = ring || pkHas4(pk->live, pkHomeSector(f, x0 + x1, z0 + z1));
    }
    if (PK) {
        float2 c[4] = {float2(x0, z0), float2(x1, z0), float2(x0, z1), float2(x1, z1)};
        bool inside = x0 <= 0.0 && x1 >= 0.0 && z0 <= 0.0 && z1 >= 0.0;
        pkOccludes = inside || pkTouches(pk->needed, hzRange(c, 4));
        pkHome = !occOnly && pkHas4(pk->rebuild, pkHomeSector(f, x0 + x1, z0 + z1));
        if (!pkOccludes && !pkHome) return;
    }
    if (f.opts.z != 0 && sh == 0 && (!PK || pkOccludes)) {
#ifdef HZ_QUARTERS
        float hs = float(size) * 0.5;
        for (int k = 0; k < 4; k++) {
            int px = (k & 1) * 2, pz = (k >> 1) * 2;
            uint w = 0xFFFu;
            bool open = false;
            for (int j = 0; j < 4; j++) {
                int p = (pz + (j >> 1)) * 4 + px + (j & 1);
                w = min(w, h[16 + (p >> 1)] >> ((p & 1) * 12) & 0xFFFu);
                open = open || (occOnly && meshPartBottom(f, h, p) > -1e29);
            }
            if (open) continue;
            float qx = x0 + float(k & 1) * hs, qz = z0 + float(k >> 1) * hs;
            hzOccluder(f, hz, qx, qx + hs, qz, qz + hs, float(int(w) - 512 - f.origin.y) - f.cam.y
#ifdef HZ_DEBUG
                , dbg
#endif
            );
        }
#else
        float ps = float(size) * 0.25;
        for (int k = 0; k < 16; k++) {
            uint w = h[16 + (k >> 1)] >> ((k & 1) * 12) & 0xFFFu;
            float qx = x0 + float(k & 3) * ps, qz = z0 + float(k >> 2) * ps;
            float by = occOnly ? meshPartBottom(f, h, k) : -1e30;
            if ((f.opts.z & 32) != 0 && by > -1e29) {
                if (by > -(PK ? f.hz.z : 0.0)) continue;
                by = -1e30;
            }
            if (by > -1e29) {
                hzOccluderRun(f, hz, qx, qx + ps, qz, qz + ps, float(int(w) - 512 - f.origin.y) - f.cam.y, by, PK ? f.hz.z : 0.0,
                              PK ? pk->needed : uint2(0xFFFFFFFFu));
                continue;
            }
            if (PK) {
                hzOccluderEps(f, hz, qx, qx + ps, qz, qz + ps, float(int(w) - 512 - f.origin.y) - f.cam.y, f.hz.z, pk->needed);
                continue;
            }
            hzOccluder(f, hz, qx, qx + ps, qz, qz + ps, float(int(w) - 512 - f.origin.y) - f.cam.y
#ifdef HZ_DEBUG
                , dbg
#endif
            );
        }
#endif
    }
    if (occOnly) return;
    bool own = sh == 0;
    uint4 ne;
    bool exact[4];
    exact[0] = own && meshRightLevel<(MODE != 0)>(f, mask, L, bx + 1, bz) && meshDrawLevel(f, table, L, bx + 1, bz, ne) == L;
    exact[1] = own && meshRightLevel<(MODE != 0)>(f, mask, L, bx - 1, bz) && meshDrawLevel(f, table, L, bx - 1, bz, ne) == L;
    exact[2] = own && meshRightLevel<(MODE != 0)>(f, mask, L, bx, bz + 1) && meshDrawLevel(f, table, L, bx, bz + 1, ne) == L;
    exact[3] = own && meshRightLevel<(MODE != 0)>(f, mask, L, bx, bz - 1) && meshDrawLevel(f, table, L, bx, bz - 1, ne) == L;
    if (fake) exact[0] = exact[1] = exact[2] = exact[3] = true;
    bool edge[4] = {ox0 + size == kx0 + ksize, ox0 == kx0, oz0 + size == kz0 + ksize, oz0 == kz0};
    bool vis[15];
    const float m = PK ? f.hz.z : 0.0;
    vis[0] = body && yLo < m;
    vis[1] = body && yHi > -m;
    float cc = own ? c : 0.0;
    vis[2] = body && x0 + cc < m;
    vis[3] = body && x1 - cc > -m;
    vis[4] = body && z0 + cc < m;
    vis[5] = body && z1 - cc > -m;
    vis[6] = body && exact[0] && x1 < m;
    vis[7] = body && exact[1] && x0 > -m;
    vis[8] = body && exact[2] && z1 < m;
    vis[9] = body && exact[3] && z0 > -m;
    vis[10] = !exact[0] && edge[0] && x1 < m;
    vis[11] = !exact[1] && edge[1] && x0 > -m;
    vis[12] = !exact[2] && edge[2] && z1 < m;
    vis[13] = !exact[3] && edge[3] && z0 > -m;
    vis[14] = body && !fake;
    uint bits = 0u;
    for (int g = 0; g < 15; g++) {
        bool v = vis[g] && (f.opts.y == 0 || ((f.opts.y >> g) & 1) != 0) && (h[g] >> 20) != 0u;
        bits |= v ? 1u << g : 0u;
    }
    if (bits == 0u || (PK && !pkHome) || (LIVE && !liveList)) return;
    uint at = atomic_fetch_add_explicit(&args[ARGS_LIST], 1u, memory_order_relaxed);
    if (at < uint(f.caps.y)) blocks[at] = uint4(uint(bx), uint(bz), uint(L) | uint(K) << 4 | bits << 8, hdr);
}

template <int MODE>
static inline void meshFarMin(constant MeshFrame& f, device const uint4* table, device const uint* arena, device const uint* mask, device atomic_uint* hz,
                              constant PkParams* pk, uint gid) {
    constexpr bool PK = MODE == 1;
    int tps = f.counts.x, B = f.counts.y, bpt = 64 / B, bpw = tps * bpt;
    uint perLevel = uint(bpw * bpw);
    int L = int(gid / perLevel);
    if (L >= f.origin.w) return;
    uint i = gid - uint(L) * perLevel;
    int bx = f.win[L].x * bpt + int(i % uint(bpw)), bz = f.win[L].y * bpt + int(i / uint(bpw));
    if (f.opts.x != 0 && ((f.opts.x >> L) & 1) == 0) return;
    if (!meshRightLevel<(MODE != 0)>(f, mask, L, bx, bz)) return;
    float eps = PK ? f.hz.z : 0.0;
    int ox0 = (bx * B << L) - f.origin.x, oz0 = (bz * B << L) - f.origin.z, size = B << L;
    float x0 = float(ox0) - f.cam.x, z0 = float(oz0) - f.cam.z, x1 = x0 + float(size), z1 = z0 + float(size);
    float nx = max(0.0, max(x0, -x1)), nz = max(0.0, max(z0, -z1));
    float fx = max(abs(x0), abs(x1)), fz = max(abs(z0), abs(z1));
    float dn = sqrt(nx * nx + nz * nz), df = sqrt(fx * fx + fz * fz);
    if (MODE == 1 && dn > pk->needFar) return;
    if (MODE == 2 && dn > pk->liveFar) return;
    uint4 e;
    int K = meshDrawLevel(f, table, L, bx, bz, e);
    if (K < 0) return;
    int sh = K - L, kx = bx >> sh, kz = bz >> sh;
    uint hdr = e.x - 1u;
    uint blk = uint((kz & (bpt - 1)) * bpt + (kx & (bpt - 1)));
    device const uint* h = arena + hdr + blk * MESH_HDR;
    float yLo = float(int(h[15] & 0xFFFFu) - 512 - f.origin.y) - f.cam.y - eps;
    dn = max(dn - eps, 1e-3);
    df += eps;
    float tan = yLo < 0.0 ? yLo / dn : yLo / df;
    float2 c[4] = {float2(x0, z0), float2(x1, z0), float2(x0, z1), float2(x1, z1)};
    float2 r = hzRange(c, 4);
    bool inside = x0 <= eps && x1 >= -eps && z0 <= eps && z1 >= -eps;
    float m = eps > 0.0 ? 1.01 * eps / dn : 0.0;
    int b0 = hzBin(r.x - m), b1 = hzBin(r.y + m);
    if (inside || b1 - b0 >= HZ_BINS - 1) {
        b0 = 0;
        b1 = HZ_BINS - 1;
        tan = -INFINITY;
    }
    uint k = hzKey(tan);
    for (int b = b0; b <= b1; b++) {
        int bin = (b % HZ_BINS + HZ_BINS) % HZ_BINS;
        if (PK && !pkBinIn(pk->needed, bin)) continue;
        atomic_fetch_min_explicit(&hz[HZ_FARMIN + bin], k, memory_order_relaxed);
    }
}

kernel void lod_mesh_far_min(constant MeshFrame& f [[buffer(0)]], device const uint4* table [[buffer(1)]], device const uint* arena [[buffer(2)]],
                             device const uint* mask [[buffer(3)]], device atomic_uint* hz [[buffer(8)]], uint gid [[thread_position_in_grid]]) {
    meshFarMin<0>(f, table, arena, mask, hz, nullptr, gid);
}

kernel void lod_pk_far_min(constant MeshFrame& f [[buffer(0)]], device const uint4* table [[buffer(1)]], device const uint* arena [[buffer(2)]],
                           device const uint* mask [[buffer(3)]], device atomic_uint* hz [[buffer(8)]], constant PkParams& pk [[buffer(14)]],
                           uint gid [[thread_position_in_grid]]) {
    meshFarMin<1>(f, table, arena, mask, hz, &pk, gid);
}

kernel void lod_pk_live_far_min(constant MeshFrame& f [[buffer(0)]], device const uint4* table [[buffer(1)]], device const uint* arena [[buffer(2)]],
                                device const uint* mask [[buffer(3)]], device atomic_uint* hz [[buffer(8)]], constant PkParams& pk [[buffer(14)]],
                                uint gid [[thread_position_in_grid]]) {
    meshFarMin<2>(f, table, arena, mask, hz, &pk, gid);
}

kernel void lod_mesh_cull(constant MeshFrame& f [[buffer(0)]], device const uint4* table [[buffer(1)]], device const uint* arena [[buffer(2)]],
                          device const uint* mask [[buffer(3)]], device atomic_uint* args [[buffer(4)]], device atomic_uint* hz [[buffer(8)]],
                          device uint4* blocks [[buffer(9)]],
#ifdef HZ_DEBUG
                          device float4* dbg [[buffer(10)]],
#endif
                          uint gid [[thread_position_in_grid]]) {
    meshCullBlock<0>(f, table, arena, mask, args, hz, blocks, nullptr,
#ifdef HZ_DEBUG
                         dbg,
#endif
                         gid);
}

kernel void lod_pk_cull(constant MeshFrame& f [[buffer(0)]], device const uint4* table [[buffer(1)]], device const uint* arena [[buffer(2)]],
                        device const uint* mask [[buffer(3)]], device atomic_uint* args [[buffer(4)]], device atomic_uint* hz [[buffer(8)]],
                        device uint4* blocks [[buffer(9)]], constant PkParams& pk [[buffer(14)]],
#ifdef HZ_DEBUG
                        device float4* dbg [[buffer(10)]],
#endif
                        uint gid [[thread_position_in_grid]]) {
    meshCullBlock<1>(f, table, arena, mask, args, hz, blocks, &pk,
#ifdef HZ_DEBUG
                        dbg,
#endif
                        gid);
}

kernel void lod_pk_live(constant MeshFrame& f [[buffer(0)]], device const uint4* table [[buffer(1)]], device const uint* arena [[buffer(2)]],
                        device const uint* mask [[buffer(3)]], device atomic_uint* args [[buffer(4)]], device atomic_uint* hz [[buffer(8)]],
                        device uint4* blocks [[buffer(9)]], constant PkParams& pk [[buffer(14)]],
#ifdef HZ_DEBUG
                        device float4* dbg [[buffer(10)]],
#endif
                        uint gid [[thread_position_in_grid]]) {
    meshCullBlock<2>(f, table, arena, mask, args, hz, blocks, &pk,
#ifdef HZ_DEBUG
                     dbg,
#endif
                     gid);
}

template <bool PK>
static inline void meshEmitBlock(constant MeshFrame& f, device const uint* arena, device atomic_uint* args, device MeshInstance* inst,
                                 device MeshInstance* plantInst, device const uint* hz, device const uint4* blocks, constant PkParams* pk,
                                 device atomic_uint* sec, uint gid) {
    uint n = atomic_load_explicit(&args[ARGS_LIST], memory_order_relaxed);
    if (gid >= min(n, uint(f.caps.y))) return;
    uint4 bl = blocks[gid];
    int bx = int(bl.x), bz = int(bl.y), L = int(bl.z & 15u), K = int((bl.z >> 4) & 15u);
    uint bits = bl.z >> 8, hdr = bl.w;
    int B = f.counts.y, bpt = 64 / B;
    int sh = K - L, kx = bx >> sh, kz = bz >> sh;
    uint blk = uint((kz & (bpt - 1)) * bpt + (kx & (bpt - 1)));
    device const uint* h = arena + hdr + blk * MESH_HDR;
    int ox0 = (bx * B << L) - f.origin.x, oz0 = (bz * B << L) - f.origin.z, size = B << L;
    bool hiddenBlock = false;
    if (f.opts.z != 0 && (f.opts.z & 2) == 0) {
        float x0 = float(ox0) - f.cam.x, z0 = float(oz0) - f.cam.z, dn, df;
        float hzt = PK ? hzFootprintEps(f, hz, float2(x0, z0), float2(x0 + float(size), z0 + float(size)), dn, df, f.hz.z, pk->needed)
                       : hzFootprint(f, hz, float2(x0, z0), float2(x0 + float(size), z0 + float(size)), dn, df);
        float yHi = float(int(h[15] >> 16) - 512 - f.origin.y) - f.cam.y;
        if (PK) yHi += f.hz.z;
        if (hzTangent(yHi, dn, df) < hzt) {
            atomic_fetch_add_explicit(&args[23], 1u, memory_order_relaxed);
            if ((f.opts.z & 8) == 0) return;
            hiddenBlock = true;
        } else if (hzt > -INFINITY && sh == 0) {
            for (int g = 0; g < 15; g++) {
                if (((bits >> g) & 1u) == 0u) continue;
                float gy = float(int(h[24 + (g >> 1)] >> ((g & 1) * 12) & 0xFFFu) - 512 - f.origin.y) - f.cam.y;
                if (PK) gy += f.hz.z;
                if (hzTangent(gy, dn, df) < hzt) {
                    bits &= ~(1u << g);
                    atomic_fetch_add_explicit(&args[24], 1u, memory_order_relaxed);
                }
            }
        }
    }
    bool own = sh == 0;
    uint quadBase = (hdr + uint(bpt * bpt * MESH_HDR)) / 2u;
    uint tag = uint(K) << 8 | (own ? 0u : 1u << 12) | (hiddenBlock ? 1u << 13 : 0u);
    int home = 0;
    if (PK) {
        float x0 = float(ox0) - f.cam.x, z0 = float(oz0) - f.cam.z, x1 = x0 + float(size), z1 = z0 + float(size);
        home = pkHomeSector(f, x0 + x1, z0 + z1);
        tag |= uint(home) << 16;
    }
    if (PK && ((bits >> 14) & 1u) != 0u) {
        float x0 = float(ox0) - f.cam.x, z0 = float(oz0) - f.cam.z, x1 = x0 + float(size), z1 = z0 + float(size);
        device atomic_uint* w = sec + home * PK_SECTOR_WORDS;
        float2 c[4] = {float2(x0, z0), float2(x1, z0), float2(x0, z1), float2(x1, z1)};
        float2 r = hzRange(c, 4);
        float a0 = r.x - float(home % PK_AZ) * (4.0 / float(PK_AZ));
        a0 -= 4.0 * floor((a0 + 2.0) * 0.25);
        float a1 = a0 + (r.y - r.x);
        bool inside = x0 <= 0.0 && x1 >= 0.0 && z0 <= 0.0 && z1 >= 0.0;
        if (inside) {
            a0 = -2.0;
            a1 = 2.0;
        }
        atomic_fetch_min_explicit((device atomic_int*) &w[1], int(floor(a0 * 1048576.0)), memory_order_relaxed);
        atomic_fetch_max_explicit((device atomic_int*) &w[2], int(ceil(a1 * 1048576.0)), memory_order_relaxed);
        float nx = max(0.0, max(x0, -x1)), nz = max(0.0, max(z0, -z1));
        float fx = max(abs(x0), abs(x1)), fz = max(abs(z0), abs(z1));
        atomic_fetch_min_explicit(&w[3], hzKey(sqrt(nx * nx + nz * nz)), memory_order_relaxed);
        atomic_fetch_max_explicit(&w[4], hzKey(sqrt(fx * fx + fz * fz)), memory_order_relaxed);
        atomic_fetch_min_explicit((device atomic_int*) &w[5], int(floor(float(int(h[15] & 0xFFFFu) - 512 - f.origin.y) - f.cam.y)), memory_order_relaxed);
        atomic_fetch_max_explicit((device atomic_int*) &w[6], int(ceil(float(int(h[15] >> 16) - 512 - f.origin.y) - f.cam.y)), memory_order_relaxed);
    }
    int4 clip = int4(ox0, ox0 + size, oz0, oz0 + size);
    int ttx = meshTileOf(f, kx), ttz = meshTileOf(f, kz);
    uint quads = 0u;
    for (int g = 0; g < 14; g++) {
        if (((bits >> g) & 1u) == 0u) continue;
        uint n = h[g] >> 20;
        atomic_fetch_add_explicit(&args[48 + L], n, memory_order_relaxed);
        atomic_fetch_add_explicit(&args[32 + g], n, memory_order_relaxed);
        quads += n;
        meshEmit(f, args, inst, ARGS_CAND, uint(f.counts.z), quadBase + (h[g] & 0xFFFFFu), n, tag, ttx, ttz, clip);
    }
    atomic_fetch_add_explicit(&args[10], 1u, memory_order_relaxed);
    atomic_fetch_add_explicit(&args[11], quads, memory_order_relaxed);
    if (((bits >> 14) & 1u) != 0u) {
        uint np = h[14] >> 20;
        atomic_fetch_add_explicit(&args[12], np, memory_order_relaxed);
        if (PK) {
            device atomic_uint* w = sec + home * PK_SECTOR_WORDS;
            uint first = h[14] & 0xFFFFFu, need = (np + MESH_Q - 1u) / MESH_Q;
            uint at = atomic_fetch_add_explicit(&w[7], need, memory_order_relaxed);
            for (uint k = 0u; k < np; k += MESH_Q) {
                if (at < pk->plantCap) {
                    MeshInstance m;
                    m.a = uint4(first + k, min(uint(MESH_Q), np - k) | tag | MESH_REL, uint(ttx), uint(ttz));
                    m.b = clip;
                    plantInst[uint(home) * pk->plantCap + at] = m;
                } else {
                    atomic_fetch_or_explicit(&w[15], 2u, memory_order_relaxed);
                }
                at++;
            }
        } else {
            meshEmit(f, args, plantInst, 6, uint(f.counts.w), quadBase + (h[14] & 0xFFFFFu), np, tag, ttx, ttz, clip);
        }
    }
}

kernel void lod_mesh_emit(constant MeshFrame& f [[buffer(0)]], device const uint* arena [[buffer(2)]], device atomic_uint* args [[buffer(4)]],
                          device MeshInstance* inst [[buffer(5)]], device MeshInstance* plantInst [[buffer(6)]], device const uint* hz [[buffer(8)]],
                          device const uint4* blocks [[buffer(9)]], uint gid [[thread_position_in_grid]]) {
    meshEmitBlock<false>(f, arena, args, inst, plantInst, hz, blocks, nullptr, nullptr, gid);
}

kernel void lod_pk_emit(constant MeshFrame& f [[buffer(0)]], device const uint* arena [[buffer(2)]], device atomic_uint* args [[buffer(4)]],
                        device MeshInstance* inst [[buffer(5)]], device MeshInstance* plantInst [[buffer(6)]], device const uint* hz [[buffer(8)]],
                        device const uint4* blocks [[buffer(9)]], constant PkParams& pk [[buffer(14)]], device atomic_uint* sec [[buffer(15)]],
                        uint gid [[thread_position_in_grid]]) {
    meshEmitBlock<true>(f, arena, args, inst, plantInst, hz, blocks, &pk, sec, gid);
}

struct MeshVOut {
    float4 position [[position]];
    uint info [[flat]];
};

static inline float4 meshClip(constant CompFrame& f, float3 rel) {
    float4 clip = f.viewProj * float4(rel, 1.0);
    clip.y = -clip.y;
#ifdef SEAM_DEPTH_PUSH
    clip.z *= SEAM_DEPTH_PUSH;
#endif
    return clip;
}

static inline MeshVOut meshCorner(constant CompFrame& f, MeshInstance mi, device const uint* arena, uint vid);

vertex MeshVOut lod_mesh_vs(uint vid [[vertex_id]], uint iid [[instance_id]], constant CompFrame& f [[buffer(22)]],
                            device const MeshInstance* inst [[buffer(23)]], device const uint* arena [[buffer(24)]]) {
    return meshCorner(f, inst[iid], arena, vid);
}

static inline MeshVOut meshQuadCorner(constant CompFrame& f, uint w0, uint w1, int tx, int tz, int L, bool clipped, int4 clip, uint corner, uint id);

static inline MeshVOut meshCorner(constant CompFrame& f, MeshInstance mi, device const uint* arena, uint vid) {
    uint4 in = mi.a;
    uint q = vid >> 2, corner = vid & 3u;
    if (q >= (in.y & 255u)) {
        MeshVOut o;
        o.position = float4(0.0, 0.0, -2.0, 1.0);
        o.info = 0u;
        return o;
    }
    uint qi = in.x + q;
    return meshQuadCorner(f, arena[qi * 2u], arena[qi * 2u + 1u], int(in.z), int(in.w), int((in.y >> 8) & 15u), (in.y & (1u << 12)) != 0u, mi.b, corner, qi);
}

static inline MeshVOut meshQuadCorner(constant CompFrame& f, uint w0, uint w1, int tx, int tz, int L, bool clipped, int4 clip, uint corner, uint id) {
    MeshVOut o;
    int x = int(w0 & 127u), z = int((w0 >> 7) & 127u), e1 = int((w0 >> 14) & 63u) + 1, e2 = int((w0 >> 20) & 63u) + 1;
    uint face = (w0 >> 26) & 7u, kind = (w0 >> 29) & 3u;
    int y0 = int(w1 & 0xFFFu) - 512, y1 = int((w1 >> 12) & 0xFFFu) - 512;
#ifdef MESH_SKIRT_DEPTH
    if (y0 == -512 && face != FACE_TOP && face != FACE_BOTTOM) y0 = y1 - MESH_SKIRT_DEPTH;
#endif
    int cx, cz, cy;
    bool a = corner == 1u || corner == 2u, up = corner >= 2u;
    if (face == FACE_TOP) {
        cx = corner >= 2u ? x + e1 : x;
        cz = a ? z + e2 : z;
        cy = y0;
    } else if (face == FACE_BOTTOM) {
        cx = a ? x + e1 : x;
        cz = corner >= 2u ? z + e2 : z;
        cy = y0;
    } else if (face == FACE_XP || face == FACE_XN) {
        cx = x;
        cz = face == FACE_XP ? (a ? z : z + e1) : (a ? z + e1 : z);
        cy = up ? y1 : y0;
    } else {
        cz = z;
        cx = face == FACE_ZP ? (a ? x + e1 : x) : (a ? x : x + e1);
        cy = up ? y1 : y0;
    }
    int wx = (((tx << 6) + cx) << L) - f.origin.x, wz = (((tz << 6) + cz) << L) - f.origin.z;
    if (clipped) {
        int px = (((tx << 6) + x) << L) - f.origin.x, pz = (((tz << 6) + z) << L) - f.origin.z;
        bool outside = face == FACE_XP ? (px <= clip.x || px > clip.y) : face == FACE_XN ? (px < clip.x || px >= clip.y)
            : face == FACE_ZP ? (pz <= clip.z || pz > clip.w) : face == FACE_ZN ? (pz < clip.z || pz >= clip.w) : false;
        if (outside) {
            o.position = float4(0.0, 0.0, -2.0, 1.0);
            o.info = 0u;
            return o;
        }
        wx = clamp(wx, clip.x, clip.y);
        wz = clamp(wz, clip.z, clip.w);
    }
    float3 rel = float3(float(wx), float(cy - f.origin.y), float(wz)) - f.camFrac.xyz;
#ifndef MESH_EPS
#define MESH_EPS 3e-6
#endif
    float eps = 0.0005 + length(rel) * MESH_EPS;
    if (face == FACE_TOP || face == FACE_BOTTOM) {
        rel.x += cx != x ? eps : -eps;
        rel.z += cz != z ? eps : -eps;
    } else if (face == FACE_XP || face == FACE_XN) {
        rel.z += cz != z ? eps : -eps;
        rel.y += up ? eps : -eps;
    } else {
        rel.x += cx != x ? eps : -eps;
        rel.y += up ? eps : -eps;
    }
    o.position = meshClip(f, rel);
    int plane = face == FACE_TOP || face == FACE_BOTTOM ? y0 - f.origin.y
        : face <= FACE_XN ? (((tx << 6) + x) << L) - f.origin.x : (((tz << 6) + z) << L) - f.origin.z;
    o.info = face | kind << 3 | uint(L) << 5 | uint(plane) << 9;
#ifdef MESH_BENCH_ID
    o.info = id + 1u;
#endif
#ifdef MESH_BENCH_COLLAPSE
    o.position = float4(o.position.xy / max(abs(o.position.w), 1e-6) * 0.0, 0.5 + 1e-9 * float(o.info), 1.0);
#endif
    return o;
}

static inline int meshSkirtFloor(constant MeshFrame& mf, device const uint4* table, device const uint* geom, device const uint* crowns, uint w0, int tx,
                                 int tz, int L);

static inline void meshQuadClip(constant CompFrame& f, MeshInstance mi, device const uint* arena, uint q, thread float4* P, thread float3& lo,
                                thread float3& hi, constant MeshFrame& mf, device const uint4* table, device const uint* geom, device const uint* crowns) {
    if ((mi.a.y & (1u << 12)) != 0u) {
        for (uint c = 0; c < 4u; c++) P[c] = meshCorner(f, mi, arena, q * 4u + c).position;
        lo = float3(1.0);
        hi = float3(0.0);
        return;
    }
    uint qi = mi.a.x + q;
    uint w0 = arena[qi * 2u], w1 = arena[qi * 2u + 1u];
    int L = int((mi.a.y >> 8) & 15u);
    int x = int(w0 & 127u), z = int((w0 >> 7) & 127u), e1 = int((w0 >> 14) & 63u) + 1, e2 = int((w0 >> 20) & 63u) + 1;
    uint face = (w0 >> 26) & 7u;
    int y0 = int(w1 & 0xFFFu) - 512, y1 = int((w1 >> 12) & 0xFFFu) - 512;
    if ((w0 >> 31) != 0u) y0 = min(y0, meshSkirtFloor(mf, table, geom, crowns, w0, int(mi.a.z), int(mi.a.w), L));
    int wx = (((int(mi.a.z) << 6) + x) << L) - f.origin.x, wz = (((int(mi.a.w) << 6) + z) << L) - f.origin.z;
    float3 rel = float3(float(wx), float(y0 - f.origin.y), float(wz)) - f.camFrac.xyz;
    float4x4 m = f.viewProj;
    float4 p = m * float4(rel, 1.0);
    float cell = float(1 << L);
    float4 X = m[0], Y = m[1], Z = m[2];
    float ex = face == FACE_TOP || face == FACE_BOTTOM || face == FACE_ZP || face == FACE_ZN ? float(e1) * cell : 0.0;
    float ez = face == FACE_TOP || face == FACE_BOTTOM ? float(e2) * cell : (face == FACE_XP || face == FACE_XN ? float(e1) * cell : 0.0);
    lo = rel;
    hi = float3(rel.x + ex, rel.y + float(face == FACE_TOP || face == FACE_BOTTOM ? 0 : y1 - y0), rel.z + ez);
    if (face == FACE_TOP || face == FACE_BOTTOM) {
        float4 U = X * (float(e1) * cell), V = Z * (float(e2) * cell);
        if (face == FACE_TOP) {
            P[0] = p; P[1] = p + V; P[2] = p + U + V; P[3] = p + U;
        } else {
            P[0] = p; P[1] = p + U; P[2] = p + U + V; P[3] = p + V;
        }
    } else {
        float4 H = Y * float(y1 - y0);
        float4 W = (face == FACE_XP || face == FACE_XN ? Z : X) * (float(e1) * cell);
        if (face == FACE_XP || face == FACE_ZN) {
            P[0] = p + W; P[1] = p; P[2] = p + H; P[3] = p + W + H;
        } else {
            P[0] = p; P[1] = p + W; P[2] = p + W + H; P[3] = p + H;
        }
    }
    for (int c = 0; c < 4; c++) P[c].y = -P[c].y;
}

static inline bool meshQuadKeep(constant CompFrame& f, MeshInstance mi, device const uint* arena, uint q, constant MeshFrame& mf, device const uint* hz,
                                device const uint4* table, device const uint* geom, device const uint* crowns) {
    if (q >= (mi.a.y & 255u)) return false;
    float2 s[4];
    float2 lo = float2(1e30), hi = float2(-1e30);
    float4 P[4];
    float3 qlo, qhi;
    meshQuadClip(f, mi, arena, q, P, qlo, qhi, mf, table, geom, crowns);
    for (uint c = 0; c < 4u; c++) {
        float4 p = P[c];
        if (p.w <= 1e-4) return true;
        s[c] = (p.xy / p.w * float2(0.5, -0.5) + 0.5) * f.screen.xy;
        lo = min(lo, s[c]);
        hi = max(hi, s[c]);
    }
    bool onScreen = hi.x > 0.0 && hi.y > 0.0 && lo.x < f.screen.x && lo.y < f.screen.y;
    bool covers = ceil(lo.x - 0.5) <= floor(hi.x - 0.5) && ceil(lo.y - 0.5) <= floor(hi.y - 0.5);
    float area = (s[1].x - s[0].x) * (s[2].y - s[0].y) - (s[2].x - s[0].x) * (s[1].y - s[0].y)
        + (s[2].x - s[0].x) * (s[3].y - s[0].y) - (s[3].x - s[0].x) * (s[2].y - s[0].y);
    if (!(onScreen && covers && area > 0.0)) return false;
    return !(mf.opts.z != 0 && (mf.opts.z & 4) == 0 && qhi.x >= qlo.x && hzHidden(mf, hz, qlo.xz, qhi.xz, qhi.y));
}

kernel void lod_mesh_quads(constant CompFrame& f [[buffer(22)]], device const MeshInstance* inst [[buffer(23)]], device const uint* arena [[buffer(24)]],
                           device atomic_uint* args [[buffer(4)]], device uint4* survivors [[buffer(7)]], constant MeshFrame& mf [[buffer(0)]],
                           device const uint* hz [[buffer(8)]], device const uint4* table [[buffer(1)]], device const uint* geom [[buffer(12)]],
                           device const uint* crowns [[buffer(13)]],
#ifdef HZ_DEBUG
                           device float4* qdbg [[buffer(11)]],
#endif
                           uint gid [[thread_position_in_grid]], uint lane [[thread_index_in_simdgroup]]) {
    uint n = atomic_load_explicit(&args[ARGS_CAND], memory_order_relaxed);
    uint iid = gid / MESH_Q, q = gid % MESH_Q;
    bool keep = false;
    uint marked = 0u;
    MeshInstance mi;
    mi.a = uint4(0u);
    mi.b = int4(0);
    uint w0 = 0u, w1 = 0u;
    if (iid < n) {
        mi = inst[iid];
        if (q < (mi.a.y & 255u)) {
            uint qa = mi.a.x + q;
            w0 = arena[qa * 2u];
            w1 = arena[qa * 2u + 1u];
            float2 s[4];
            bool behind = false;
            float2 lo = float2(1e30), hi = float2(-1e30);
            float4 P[4];
            float3 qlo, qhi;
            meshQuadClip(f, mi, arena, q, P, qlo, qhi, mf, table, geom, crowns);
            for (uint c = 0; c < 4u; c++) {
                float4 p = P[c];
                if (p.w <= 1e-4) {
                    behind = true;
                    break;
                }
                s[c] = (p.xy / p.w * float2(0.5, -0.5) + 0.5) * f.screen.xy;
                lo = min(lo, s[c]);
                hi = max(hi, s[c]);
            }
            if (behind) {
                keep = true;
            } else {
                bool onScreen = hi.x > 0.0 && hi.y > 0.0 && lo.x < f.screen.x && lo.y < f.screen.y;
                bool covers = ceil(lo.x - 0.5) <= floor(hi.x - 0.5) && ceil(lo.y - 0.5) <= floor(hi.y - 0.5);
                float area = (s[1].x - s[0].x) * (s[2].y - s[0].y) - (s[2].x - s[0].x) * (s[1].y - s[0].y)
                    + (s[2].x - s[0].x) * (s[3].y - s[0].y) - (s[3].x - s[0].x) * (s[2].y - s[0].y);
                keep = onScreen && covers && area > 0.0;
#ifdef MESH_BENCH_STATS
                atomic_fetch_add_explicit(&args[!onScreen ? 100 : !covers ? 101 : !(area > 0.0) ? 102 : 103], 1u, memory_order_relaxed);
#endif
                if (keep && mf.opts.z != 0 && (mf.opts.z & 4) == 0 && qhi.x >= qlo.x && hzHidden(mf, hz, qlo.xz, qhi.xz, qhi.y)) {
                    keep = (mf.opts.z & 8) != 0;
                    marked = 2u;
#ifdef HZ_DEBUG
                    uint at = atomic_fetch_add_explicit((device atomic_uint*) qdbg, 1u, memory_order_relaxed);
                    if (at < 400000u) {
                        qdbg[3 * at + 3] = float4(qlo.x, qhi.x, qlo.z, qhi.z);
                        qdbg[3 * at + 4] = float4(qlo.y, qhi.y, float(iid), float(q));
                        qdbg[3 * at + 5] = float4(lo.x, lo.y, hi.x, hi.y);
                    }
#endif
                    atomic_fetch_add_explicit(&args[15], 1u, memory_order_relaxed);
                }
#ifdef MESH_BENCH_AREA
                if (keep) {
                    float2 cl = clamp(lo, float2(0.0), f.screen.xy), ch = clamp(hi, float2(0.0), f.screen.xy);
                    atomic_fetch_add_explicit(&args[60], uint(max(0.0, (ch.x - cl.x) * (ch.y - cl.y)) / 16.0), memory_order_relaxed);
                    atomic_fetch_add_explicit(&args[61], uint(abs(area) * 0.5 / 16.0), memory_order_relaxed);
                }
#endif
            }
        }
    }
    uint k = keep ? 1u : 0u;
    uint before = simd_prefix_exclusive_sum(k), total = simd_sum(k);
    uint base = 0u;
    if (lane == 0u && total > 0u) base = atomic_fetch_add_explicit(&args[ARGS_SURV], total, memory_order_relaxed);
    base = simd_broadcast_first(base);
    uint cap = uint(mf.caps.x);
    if (keep && base + before < cap) {
        uint qi = mi.a.x + q;
        bool clipped = (mi.a.y & (1u << 12)) != 0u;
        uint L = (mi.a.y >> 8) & 15u;
        if ((mi.a.y & (1u << 13)) != 0u) marked |= 1u;
#ifdef MESH_BENCH_STATS
        atomic_fetch_add_explicit(&args[64 + L], 1u, memory_order_relaxed);
        atomic_fetch_add_explicit(&args[80 + ((arena[qi * 2u] >> 26) & 7u) + 8u * min(L, 3u)], 1u, memory_order_relaxed);
#endif
        survivors[base + before] = clipped ? uint4(q, 0u, iid, 1u << 24 | L << 20) : uint4(w0, w1, mi.a.z, (mi.a.w & 0xFFFFFu) | L << 20 | marked << 25);
    }
}

static inline bool meshGround(constant MeshFrame& mf, device const uint4* table, device const uint* geom, device const uint* crowns, int k, int cx, int cz,
                              thread int& y) {
    uint4 e;
    if (k < 0 || k >= mf.origin.w || !meshResident(mf, table, k, cx >> 6, cz >> 6, e)) return false;
    int n = mf.counts.x * 64, logN = ctz(n), m = n - 1;
    uint idx = uint(k) * uint(n) * uint(n) + uint(((cz & m) << logN) | (cx & m));
    uint g = geom[idx];
    if ((g & GEOM_VALID) == 0u) return false;
    y = (g & GEOM_CROWN) != 0u ? int(crowns[idx] & 0xFFFu) - 512 : GEOM_Y(g);
    return true;
}

static inline int meshSkirtFloor(constant MeshFrame& mf, device const uint4* table, device const uint* geom, device const uint* crowns, uint w0, int tx,
                                 int tz, int L) {
    int x = int(w0 & 127u), z = int((w0 >> 7) & 127u);
    uint face = (w0 >> 26) & 7u;
    int ax = (tx << 6) + x - (face == FACE_XN ? 1 : 0), az = (tz << 6) + z - (face == FACE_ZN ? 1 : 0);
    int lo = 100000, y;
    if (meshGround(mf, table, geom, crowns, L, ax, az, y)) lo = min(lo, y);
    if (L > 0) {
        int fx = face == FACE_XN ? 2 * ax + 1 : 2 * ax, fz = face == FACE_ZN ? 2 * az + 1 : 2 * az;
        int sx = face == FACE_ZP || face == FACE_ZN ? 1 : 0, sz = 1 - sx;
        if (meshGround(mf, table, geom, crowns, L - 1, fx, fz, y)) lo = min(lo, y);
        if (meshGround(mf, table, geom, crowns, L - 1, fx + sx, fz + sz, y)) lo = min(lo, y);
    }
    for (int k = L + 1; k < mf.origin.w; k++) {
        if (meshGround(mf, table, geom, crowns, k, ax >> (k - L), az >> (k - L), y)) {
            lo = min(lo, y);
            break;
        }
    }
    return lo == 100000 ? -512 : max(lo - 1, -512);
}

vertex MeshVOut lod_mesh_vs2(uint vid [[vertex_id]], uint iid [[instance_id]], constant CompFrame& f [[buffer(22)]],
                             device const MeshInstance* inst [[buffer(23)]], device const uint* arena [[buffer(24)]],
                             device const uint* args [[buffer(25)]], device const uint4* survivors [[buffer(26)]],
                             device const uint4* table [[buffer(27)]], device const uint* geom [[buffer(28)]], device const uint* crowns [[buffer(29)]],
                             constant MeshFrame& mf [[buffer(30)]]) {
    uint sidx = iid * MESH_DRAW_Q + (vid >> 2);
    if (sidx >= args[ARGS_SURV]) {
        MeshVOut o;
        o.position = float4(0.0, 0.0, -2.0, 1.0);
        o.info = 0u;
        return o;
    }
    uint4 r = survivors[sidx];
    int L = int((r.w >> 20) & 15u);
    if ((r.w & (1u << 24)) != 0u) {
        MeshInstance mi = inst[r.z];
        if (!meshResolve(mf, table, mi)) {
            MeshVOut o;
            o.position = float4(0.0, 0.0, -2.0, 1.0);
            o.info = 0u;
            return o;
        }
        return meshCorner(f, mi, arena, r.x * 4u + (vid & 3u));
    }
    int tz = int(r.w << 12) >> 12;
    uint w1 = r.y;
    if ((r.x >> 31) != 0u && (vid & 3u) < 2u) {
        int y0 = int(w1 & 0xFFFu) - 512;
        y0 = min(y0, meshSkirtFloor(mf, table, geom, crowns, r.x, int(r.z), tz, L));
        w1 = (w1 & ~0xFFFu) | uint(y0 + 512);
    }
    MeshVOut o = meshQuadCorner(f, r.x, w1, int(r.z), tz, L, false, int4(0), vid & 3u, sidx);
    if (((r.w >> 25) & 3u) != 0u) o.info = 7u | ((r.w >> 25) & 3u) << 3;
    return o;
}

static inline float3 meshRay(constant CompFrame& f, float4 pos) {
    float X = 2.0 * pos.x / f.screen.x - 1.0, Y = 2.0 * pos.y / f.screen.y - 1.0;
    return f.A.xyz * X + f.B.xyz * Y + f.C.xyz;
}

static inline void meshSurface(constant CompFrame& f, float4 pos, uint info, device const uint* geom, device const uint* color,
                               device const uint* crowns, thread CompSurface& s, thread uint& record) {
    uint face = info & 7u, kind = (info >> 3) & 3u;
    int L = int((info >> 5) & 15u), plane = int(info) >> 9;
    float3 D = meshRay(f, pos);
    bool flat = face == FACE_TOP || face == FACE_BOTTOM;
    float pc = float(plane) - (flat ? f.camFrac.y : face <= FACE_XN ? f.camFrac.x : f.camFrac.z);
    float dc = flat ? D.y : face <= FACE_XN ? D.x : D.z;
    float t = pc / (abs(dc) < 1e-12 ? (dc < 0.0 ? -1e-12 : 1e-12) : dc);
    s.rel = D * max(t, 0.0);
    s.depth = pos.z;
    s.face = face;
    float3 p = s.rel + f.camFrac.xyz;
    int ax = f.origin.x + int(floor(p.x)), az = f.origin.z + int(floor(p.z));
    if (face == FACE_XP) ax = f.origin.x + plane - 1;
    else if (face == FACE_XN) ax = f.origin.x + plane;
    else if (face == FACE_ZP) az = f.origin.z + plane - 1;
    else if (face == FACE_ZN) az = f.origin.z + plane;
    ax >>= L;
    az >>= L;
    int logN = f.origin.w, m = (1 << logN) - 1;
    uint idx = uint(L) * (1u << uint(2 * logN)) + uint(((az & m) << logN) | (ax & m));
    uint g = geom[idx], cw = color[idx];
    uint c565 = face == FACE_TOP ? (cw & 0xFFFFu) : (cw >> 16);
    if (kind == 2u) c565 = (crowns[idx] >> 12) & 0xFFFFu;
    record = c565 | ((g & GEOM_WET) != 0u ? REC_WET : 0u) | uint(L) << 20 | (kind == 1u ? REC_CROWN : 0u) | (kind == 2u ? REC_UNDER_CROWN : 0u);
}

#ifdef SEAM_DISSOLVE_MS
static inline float seamHash(int x, int z, int l) {
    uint h = uint(x) * 0x8DA6B343u ^ uint(z) * 0xD8163841u ^ uint(l) * 0xCB1AB31Fu;
    h ^= h >> 15;
    h *= 0x2C1B3C6Du;
    h ^= h >> 12;
    return float(h & 0xFFFFFFu) / 16777216.0;
}

static inline void seamDissolve(constant CompFrame& f, uint info, device const uint4* table, device const uint* geom, device const uint* color,
                                thread const CompSurface& s, thread uint& record) {
    uint face = info & 7u, kind = (info >> 3) & 3u;
    int L = int((info >> 5) & 15u), plane = int(info) >> 9;
    float3 p = s.rel + f.camFrac.xyz;
    int ax = f.origin.x + int(floor(p.x)), az = f.origin.z + int(floor(p.z));
    if (face == FACE_XP) ax = f.origin.x + plane - 1;
    else if (face == FACE_XN) ax = f.origin.x + plane;
    else if (face == FACE_ZP) az = f.origin.z + plane - 1;
    else if (face == FACE_ZN) az = f.origin.z + plane;
    int logN = f.origin.w, tps = (1 << logN) >> 6, cx = ax >> L, cz = az >> L;
    int tx = cx >> 6, tz = cz >> 6;
    uint4 e = table[uint(L * tps * tps + (tz & (tps - 1)) * tps + (tx & (tps - 1)))];
    if (e.x == 0u || int(e.y) != tx || int(e.z) != tz || (e.w & (1u << 24)) == 0u) return;
    uint age = (uint(f.camFrac.w) - (e.w & 0xFFFFFFu)) & 0xFFFFFFu;
    if (age >= uint(SEAM_DISSOLVE_MS)) return;
    if (seamHash(cx, cz, L) < float(age) / float(SEAM_DISSOLVE_MS)) return;
    int m = (1 << logN) - 1;
    for (int P = L + 1; P < 12; P++) {
        int px = ax >> P, pz = az >> P;
        uint idx = uint(P) * (1u << uint(2 * logN)) + uint(((pz & m) << logN) | (px & m));
        uint g = geom[idx];
        if ((g & GEOM_VALID) == 0u) continue;
        uint cw = color[idx];
        uint c565 = face == FACE_TOP || kind == 1u ? (cw & 0xFFFFu) : (cw >> 16);
        record = c565 | ((g & GEOM_WET) != 0u ? REC_WET : 0u) | uint(P) << 20;
        return;
    }
}
#endif

#ifdef SEAM_THIN
#ifndef SEAM_CROWN_LEVELS
#define SEAM_CROWN_LEVELS 1
#endif
static inline float seamCellPx(constant CompFrame& f, float3 rel, float c) {
    float d = max(length(rel.xz), 1.0);
    return c * (abs(rel.x) + abs(rel.z)) / d * abs(rel.y) / (d * d) / max(f.tex.x, 1e-9);
}

static inline float seamTopOverlap(constant CompFrame& f, float3 rel, int cx, int cz, int L, float t) {
    float3 p = rel + f.camFrac.xyz;
    float c = float(1 << L);
    bool alongX = abs(rel.x) > abs(rel.z);
    float lo = alongX ? float((cx << L) - f.origin.x) : float((cz << L) - f.origin.z), pa = alongX ? p.x : p.z;
    float ra = alongX ? rel.x : rel.z;
    float depth = ra > 0.0 ? pa - lo : lo + c - pa;
    float u = clamp(depth, 0.0, c) * t / c;
    return clamp(min(u + 0.5, t) - max(u - 0.5, 0.0), 0.0, 1.0);
}

static inline void seamThin(constant CompFrame& f, uint info, thread const CompSurface& s, device const uint* geom, device const uint* color,
                            device const uint* crowns, thread half3& lit) {
    uint face = info & 7u, kind = (info >> 3) & 3u;
    if (kind == 2u || face == FACE_BOTTOM || face == FACE_PLANT) return;
    int L = int((info >> 5) & 15u), plane = int(info) >> 9;
    if (seamCellPx(f, s.rel, float(1 << L)) >= 1.5) return;
    float3 p = s.rel + f.camFrac.xyz;
    int ax = f.origin.x + int(floor(p.x)), az = f.origin.z + int(floor(p.z));
    if (face == FACE_XP) ax = f.origin.x + plane - 1;
    else if (face == FACE_XN) ax = f.origin.x + plane;
    else if (face == FACE_ZP) az = f.origin.z + plane - 1;
    else if (face == FACE_ZN) az = f.origin.z + plane;
    int logN = f.origin.w, m = (1 << logN) - 1, cx = ax >> L, cz = az >> L;
    uint base = uint(L) * (1u << uint(2 * logN));
    uint idx = base + uint(((cz & m) << logN) | (cx & m));
    uint g = geom[idx], cw = color[idx];
    float c = float(1 << L);
    half sideShade = half(abs(s.rel.x) > abs(s.rel.z) ? f.faceShade.w : f.faceShade.z);
    if (kind == 1u && L == 0) {
        float d0 = max(length(s.rel.xz), 1.0), perPx0 = d0 * max(f.tex.x, 1e-9);
        if (face == FACE_TOP) {
            float t = seamCellPx(f, s.rel, c);
            if (t < 1.0) lit = mix(comp565(cw >> 16) * sideShade, lit, half(seamTopOverlap(f, s.rel, cx, cz, L, t)));
        } else {
            float yTopAbs = float(int(g & 0xFFFu) - 512), thick = float((g >> 15) & 63u);
            float yAbs = s.rel.y + f.camFrac.y + float(f.origin.y);
            uint runs = crowns[(1u << uint(2 * logN)) * uint(SEAM_CROWN_LEVELS) + idx];
            float yb = yTopAbs - thick, ye = yTopAbs;
            if (runs != 0u) {
                float y = yTopAbs - thick;
                for (int k = 0; k < 4; k++) {
                    uint run = (runs >> (8 * k)) & 255u, len = run >> 3;
                    if (len == 0u) break;
                    y += float(run & 7u);
                    if (yAbs >= y - 0.01 && yAbs <= y + float(len) + 0.01) {
                        yb = y;
                        ye = y + float(len);
                    }
                    y += float(len);
                }
            }
            float yTopRel = ye - float(f.origin.y) - f.camFrac.y;
            float eTop = (yTopRel - s.rel.y) / perPx0;
            if (yTopRel < 0.0 && eTop < 0.5) {
                float cov = clamp(min(0.5 - eTop, seamCellPx(f, float3(s.rel.x, yTopRel, s.rel.z), c)), 0.0, 1.0);
                lit = mix(lit, comp565(cw & 0xFFFFu) * half(f.faceShade.x), half(cov));
            }
        }
    }
    if (kind == 1u) {
        float d = max(length(s.rel.xz), 1.0);
        float tTop = seamCellPx(f, s.rel, c), tSide = 2.0 / (d * max(f.tex.x, 1e-9));
        float w = saturate(2.0 - (tTop + tSide));
        if (w <= 0.0) return;
        half3 avg = mix(comp565(cw >> 16) * sideShade, comp565(cw & 0xFFFFu) * half(f.faceShade.x), half(tTop / max(tTop + tSide, 1e-6)));
        lit = mix(lit, avg, half(w));
        return;
    }
    if ((g & GEOM_CROWN) != 0u) return;
    float dd = max(length(s.rel.xz), 1.0), radPx = dd * max(f.tex.x, 1e-9);
    if (face == FACE_TOP) {
        bool alongX = abs(s.rel.x) > abs(s.rel.z);
        int fx = cx + (alongX ? (s.rel.x > 0.0 ? -1 : 1) : 0), fz = cz + (alongX ? 0 : (s.rel.z > 0.0 ? -1 : 1));
        uint fg = geom[base + uint(((fz & m) << logN) | (fx & m))];
        float h = (fg & GEOM_VALID) != 0u ? max(0.0, float(int(g & 0xFFFu) - int(fg & 0xFFFu))) : 0.0;
        float t = seamCellPx(f, s.rel, c), tSide = h / radPx;
        if (t < 1.0) lit = mix(comp565(cw >> 16) * sideShade, lit, half(seamTopOverlap(f, s.rel, cx, cz, L, t)));
        float w = h > 0.0 ? saturate(2.0 - (t + tSide)) : 0.0;
        if (w > 0.0) lit = mix(lit, mix(comp565(cw >> 16) * sideShade, comp565(cw & 0xFFFFu) * half(f.faceShade.x), half(t / max(t + tSide, 1e-6))), half(w));
        return;
    }
    int nx = cx + (face == FACE_XP ? 1 : face == FACE_XN ? -1 : 0), nz = cz + (face == FACE_ZP ? 1 : face == FACE_ZN ? -1 : 0);
    uint ni = base + uint(((nz & m) << logN) | (nx & m));
    uint ng = geom[ni];
    float yTop = float(int(g & 0xFFFu) - 512 - f.origin.y) - f.camFrac.y;
    float yFoot = (ng & GEOM_VALID) != 0u ? float(int(ng & 0xFFFu) - 512 - f.origin.y) - f.camFrac.y : -1e9;
    float d = max(length(s.rel.xz), 1.0), perPx = d * max(f.tex.x, 1e-9);
    float eTop = (yTop - s.rel.y) / perPx, eFoot = (s.rel.y - yFoot) / perPx;
    float covTop = 0.0, covFoot = 0.0;
    if (yTop < 0.0 && eTop < 0.5) covTop = clamp(min(0.5 - eTop, seamCellPx(f, float3(s.rel.x, yTop, s.rel.z), c)), 0.0, 1.0);
    if (yFoot < 0.0 && eFoot < 0.5) covFoot = clamp(min(0.5 - eFoot, seamCellPx(f, float3(s.rel.x, yFoot, s.rel.z), c)), 0.0, 1.0 - covTop);
    half topShade = half(f.faceShade.x);
    if (covTop + covFoot > 0.0) {
        lit = lit * half(1.0 - covTop - covFoot) + comp565(cw & 0xFFFFu) * topShade * half(covTop)
            + comp565(color[ni] & 0xFFFFu) * topShade * half(covFoot);
    }
    float h = max(0.0, yTop - yFoot), tSide = h / radPx, tTop = yTop < 0.0 ? seamCellPx(f, float3(s.rel.x, yTop, s.rel.z), c) : 0.0;
    float w = saturate(2.0 - (tTop + tSide));
    if (w > 0.0) lit = mix(lit, mix(comp565(cw >> 16) * sideShade, comp565(cw & 0xFFFFu) * topShade, half(tTop / max(tTop + tSide, 1e-6))), half(w));
}
#endif

#ifdef SEAM_TAA
struct SeamTaaFrame {
    float4x4 prevVP;
    float4 camDelta;
    float4 params;
};

static inline half3 seamTaa(constant CompFrame& f, constant SeamTaaFrame& tf, texture2d<half> hist, uint info, thread const CompSurface& s,
                            device const uint* color, half3 cur) {
    if (tf.camDelta.w == 0.0 || length(s.rel.xz) < tf.params.z) return cur;
    float4 pc = tf.prevVP * float4(s.rel + tf.camDelta.xyz, 1.0);
    if (pc.w <= 1e-6) return cur;
    float2 uv = (pc.xy / pc.w + 1.0) * 0.5;
    if (any(uv <= 0.0) || any(uv >= 1.0)) return cur;
    constexpr sampler lin(filter::linear, address::clamp_to_edge);
    half3 h = hist.sample(lin, uv).rgb;
    uint face = info & 7u;
    int L = int((info >> 5) & 15u), plane = int(info) >> 9;
    float3 p = s.rel + f.camFrac.xyz;
    int ax = f.origin.x + int(floor(p.x)), az = f.origin.z + int(floor(p.z));
    if (face == FACE_XP) ax = f.origin.x + plane - 1;
    else if (face == FACE_XN) ax = f.origin.x + plane;
    else if (face == FACE_ZP) az = f.origin.z + plane - 1;
    else if (face == FACE_ZN) az = f.origin.z + plane;
    int logN = f.origin.w, m = (1 << logN) - 1;
    uint cw = color[uint(L) * (1u << uint(2 * logN)) + uint((((az >> L) & m) << logN) | ((ax >> L) & m))];
    half3 light = half3(s.ao * s.sky * f.skyLight.rgb);
    half sideShade = half(abs(s.rel.x) > abs(s.rel.z) ? f.faceShade.w : f.faceShade.z);
    half3 top = compFog(f, s.rel, comp565(cw & 0xFFFFu) * half(f.faceShade.x) * light);
    half3 side = compFog(f, s.rel, comp565(cw >> 16) * sideShade * light);
    half3 lo = min(cur, min(top, side)), hi = max(cur, max(top, side));
    half3 grow = (hi - lo) * half(tf.params.y);
    h = clamp(h, lo - grow, hi + grow);
    return mix(h, cur, half(tf.params.x));
}
#endif

fragment half4 lod_mesh_vanilla(MeshVOut in [[stage_in]], constant CompFrame& f [[buffer(22)]], device const uint* geom [[buffer(24)]],
                                device const uint* color [[buffer(25)]], device const uint* crowns [[buffer(26)]],
                                device const uint* texWords [[buffer(27)]], device const PaletteEntry* palette [[buffer(28)]],
                                texture2d<half> atlas [[texture(20)]], sampler smp [[sampler(14)]]
#ifdef SEAM_DISSOLVE_MS
                                , device const uint4* table [[buffer(29)]]
#endif
#ifdef SEAM_TAA
                                , constant SeamTaaFrame& tf [[buffer(30)]], texture2d<half> hist [[texture(21)]]
#endif
                                ) {
    if ((in.info & 7u) == 7u) {
        uint m = (in.info >> 3) & 3u;
        return m == 1u ? half4(1.0h, 0.0h, 0.0h, 1.0h) : m == 2u ? half4(0.0h, 1.0h, 0.0h, 1.0h) : half4(0.0h, 0.0h, 1.0h, 1.0h);
    }
    CompSurface s;
    uint record;
    meshSurface(f, in.position, in.info, geom, color, crowns, s, record);
#ifdef SEAM_DISSOLVE_MS
    seamDissolve(f, in.info, table, geom, color, s, record);
#endif
    compShade(f, uint2(1u, record), geom, color, crowns, texWords, palette, atlas, smp, s);
    float shade = s.face == FACE_TOP ? f.faceShade.x : s.face == FACE_BOTTOM ? f.faceShade.y : (s.face <= FACE_XN ? f.faceShade.w : f.faceShade.z);
#ifdef SEAM_THIN
    half3 lit = s.albedo * half(shade);
    seamThin(f, in.info, s, geom, color, crowns, lit);
    half4 o = half4(compFog(f, s.rel, lit * half3(s.ao * s.sky * f.skyLight.rgb)), 1.0h);
#else
    half4 o = half4(compFog(f, s.rel, s.albedo * half3(shade * s.ao * s.sky * f.skyLight.rgb)), 1.0h);
#endif
#ifdef SEAM_TAA
    o.rgb = seamTaa(f, tf, hist, in.info, s, color, o.rgb);
#endif
#ifdef SEAM_TAA_TILE
    o.a = half((1.0 + round(253.0 * clamp(log2(max(length(s.rel), 256.0) / 256.0) / 8.0, 0.0, 1.0))) / 255.0);
#endif
    if (f.tex.w == 1.0) {
        const half3 dc[8] = {half3(0.5h), half3(0, 1, 0), half3(1, 0, 0), half3(0, 0, 1), half3(1, 0, 1), half3(0, 1, 1), half3(1, 1, 0), half3(0)};
        o = half4(dc[s.debug & 7u] * half(0.5 + 0.5 * s.ao), 1.0h);
    }
    return o;
}

struct MeshGBufferOut {
    half4 albedo [[color(1)]];
    half4 normal [[color(2)]];
    half4 light [[color(3)]];
    float gdepth [[color(4)]];
};

static inline MeshGBufferOut meshGBuffer(thread const CompSurface& s) {
    float3 n = s.face == FACE_TOP || s.face == FACE_PLANT ? float3(0, 1, 0) : s.face == FACE_BOTTOM ? float3(0, -1, 0) : s.face == FACE_XP ? float3(1, 0, 0)
        : s.face == FACE_XN ? float3(-1, 0, 0) : s.face == FACE_ZP ? float3(0, 0, 1) : float3(0, 0, -1);
    MeshGBufferOut o;
    o.albedo = half4(s.albedo, 1.0h);
    uint mat = s.wet ? 5u : 0u;
    o.normal = half4(compOctEncode(n), half(s.ao), half(float(mat) / 255.0));
    o.light = half4(0.0h, half(s.sky < 1.0 ? 13.0 / 15.0 : 1.0), 0.0h, 0.0h);
    o.gdepth = s.depth;
    return o;
}

fragment MeshGBufferOut lod_mesh_gbuffer(MeshVOut in [[stage_in]], constant CompFrame& f [[buffer(22)]], device const uint* geom [[buffer(24)]],
                                         device const uint* color [[buffer(25)]], device const uint* crowns [[buffer(26)]],
                                         device const uint* texWords [[buffer(27)]], device const PaletteEntry* palette [[buffer(28)]],
                                         texture2d<half> atlas [[texture(20)]], sampler smp [[sampler(14)]]
#ifdef SEAM_DISSOLVE_MS
                                         , device const uint4* table [[buffer(29)]]
#endif
                                         ) {
    CompSurface s;
    uint record;
    meshSurface(f, in.position, in.info, geom, color, crowns, s, record);
#ifdef SEAM_DISSOLVE_MS
    seamDissolve(f, in.info, table, geom, color, s, record);
#endif
    compShade(f, uint2(1u, record), geom, color, crowns, texWords, palette, atlas, smp, s);
#ifdef SEAM_THIN
    {
        float sh = s.face == FACE_TOP ? f.faceShade.x : (s.face <= FACE_XN ? f.faceShade.w : f.faceShade.z);
        half3 lit = s.albedo * half(sh);
        seamThin(f, in.info, s, geom, color, crowns, lit);
        s.albedo = lit / half(max(sh, 1e-3));
    }
#endif
    return meshGBuffer(s);
}

struct MeshPayload {
    uint iid;
    uint count;
    ushort q[MESH_Q];
};

[[object]] void lod_mesh_obj(object_data MeshPayload& pl [[payload]], mesh_grid_properties mgp, constant CompFrame& f [[buffer(22)]],
                             device const MeshInstance* inst [[buffer(23)]], device const uint* arena [[buffer(24)]], device const uint* args [[buffer(25)]],
                             device const uint4* table [[buffer(27)]], device const uint* geom [[buffer(28)]], device const uint* crowns [[buffer(29)]],
                             constant MeshFrame& mf [[buffer(30)]], device const uint* hz [[buffer(26)]], uint tid [[thread_index_in_threadgroup]],
                             uint tgid [[threadgroup_position_in_grid]]) {
    uint n = args[ARGS_CAND];
    bool keep = tgid < n && meshQuadKeep(f, inst[tgid], arena, tid, mf, hz, table, geom, crowns);
    uint k = keep ? 1u : 0u;
    uint before = simd_prefix_exclusive_sum(k), total = simd_sum(k);
    if (keep) pl.q[before] = ushort(tid);
    if (tid == 0u) {
        pl.iid = tgid;
        pl.count = total;
        mgp.set_threadgroups_per_grid(uint3(total > 0u ? 1u : 0u, 1u, 1u));
    }
}

using MeshStageOut = metal::mesh<MeshVOut, void, MESH_Q * 4, MESH_Q * 2, metal::topology::triangle>;

[[mesh]] void lod_mesh_msh(MeshStageOut out, const object_data MeshPayload& pl [[payload]], constant CompFrame& f [[buffer(22)]],
                           device const MeshInstance* inst [[buffer(23)]], device const uint* arena [[buffer(24)]],
                           device const uint4* table [[buffer(27)]], device const uint* geom [[buffer(28)]], device const uint* crowns [[buffer(29)]],
                           constant MeshFrame& mf [[buffer(30)]], uint tid [[thread_index_in_threadgroup]]) {
    uint n = pl.count;
    if (tid == 0u) out.set_primitive_count(n * 2u);
    if (tid >= n) return;
    MeshInstance mi = inst[pl.iid];
    uint q = uint(pl.q[tid]);
    uint qi = mi.a.x + q;
    uint w0 = arena[qi * 2u], w1 = arena[qi * 2u + 1u];
    int L = int((mi.a.y >> 8) & 15u);
    bool clipped = (mi.a.y & (1u << 12)) != 0u;
    uint w1b = w1;
    if ((w0 >> 31) != 0u && !clipped) {
        int y0 = min(int(w1 & 0xFFFu) - 512, meshSkirtFloor(mf, table, geom, crowns, w0, int(mi.a.z), int(mi.a.w), L));
        w1b = (w1 & ~0xFFFu) | uint(y0 + 512);
    }
    for (uint c = 0; c < 4u; c++) {
        out.set_vertex(tid * 4u + c, meshQuadCorner(f, w0, c < 2u ? w1b : w1, int(mi.a.z), int(mi.a.w), L, clipped, mi.b, c, qi));
    }
    uint v = tid * 4u, t = tid * 6u;
    out.set_index(t, v);
    out.set_index(t + 1u, v + 1u);
    out.set_index(t + 2u, v + 2u);
    out.set_index(t + 3u, v + 2u);
    out.set_index(t + 4u, v + 3u);
    out.set_index(t + 5u, v);
}

struct MeshPlantVOut {
    float4 position [[position]];
    uint info [[flat]];
};

vertex MeshPlantVOut lod_mesh_plant_vs(uint vid [[vertex_id]], uint iid [[instance_id]], constant CompFrame& f [[buffer(22)]],
                                       device const MeshInstance* inst [[buffer(23)]], device const uint* arena [[buffer(24)]]) {
    uint4 in = inst[iid].a;
    uint q = vid >> 2, corner = vid & 3u;
    MeshPlantVOut o;
    if (q >= (in.y & 255u)) {
        o.position = float4(0.0, 0.0, -2.0, 1.0);
        o.info = 0u;
        return o;
    }
    uint qi = in.x + q;
    uint w0 = arena[qi * 2u], w1 = arena[qi * 2u + 1u];
    int x = int(w0 & 127u), z = int((w0 >> 7) & 127u), e1 = int((w0 >> 14) & 63u), ox = int((w0 >> 20) & 63u);
    int quad = e1 & 1, blocks = (e1 >> 1) + 1;
    int oz = int((w1 >> 24) & 15u), oy = int((w1 >> 28) & 15u);
    int y0 = int(w1 & 0xFFFu) - 512;
    int ax = (int(in.z) << 6) + x, az = (int(in.w) << 6) + z;
    float fox = float(ox) / 30.0 - 0.25, foz = float(oz) / 30.0 - 0.25, foy = -float(oy) / 60.0;
    bool a = corner == 1u || corner == 2u;
    float sa = a ? 0.95 : 0.05;
    float lx = sa, lz = quad == 0 ? sa : 1.0 - sa;
    float ly = corner >= 2u ? float(blocks) : 0.0;
    float3 rel = float3(float(ax - f.origin.x) + lx + fox, float(y0 - f.origin.y) + foy + ly, float(az - f.origin.z) + lz + foz) - f.camFrac.xyz;
    o.position = meshClip(f, rel);
    o.info = uint(quad) | (uint(ax - f.origin.x) & 0x7FFFu) << 1 | (uint(az - f.origin.z) & 0xFFFFu) << 16;
    return o;
}

static inline bool meshPlantSurface(constant CompFrame& f, float4 pos, uint info, device const uint* geom, device const uint* texWords,
                                    device const PaletteEntry* palette, thread CompSurface& s, thread uint& record) {
    int quad = int(info & 1u);
    int cxr = int(info << 16) >> 17, czr = int(info) >> 16;
    int ax = f.origin.x + cxr, az = f.origin.z + czr;
    int logN = f.origin.w, n = 1 << logN, m = n - 1;
    uint idx = uint(((az & m) << logN) | (ax & m)), n2 = uint(n) * uint(n);
    uint g = geom[idx], pa = texWords[n2 + idx], pb = texWords[2u * n2 + idx];
    uint lower = pa & 1023u, upper = (pa >> 10) & 1023u;
    int blocks = GEOM_PLANT_BLOCKS(g);
    if (lower == 0u || blocks == 0) return false;
    float ox = float((pb >> 16) & 15u) / 30.0 - 0.25, oz = float((pb >> 20) & 15u) / 30.0 - 0.25, oy = -float((pb >> 24) & 15u) / 60.0;
    float3 D = meshRay(f, pos);
    float cx = f.camFrac.x - float(cxr) - ox, cz = f.camFrac.z - float(czr) - oz;
    float den = quad == 0 ? D.x - D.z : D.x + D.z;
    if (abs(den) < 1e-12) return false;
    float t = quad == 0 ? (cz - cx) / den : (1.0 - cx - cz) / den;
    if (!(t > 0.0)) return false;
    float X = cx + t * D.x;
    if (X < 0.05 || X > 0.95) return false;
    float sx = (X - 0.05) / 0.9;
    if ((quad == 0 ? D.x - D.z : -(D.x + D.z)) < 0.0) sx = 1.0 - sx;
    int k = clamp(int(sx * 16.0), 0, 15);
    float hTop = float(plantProfile(palette, upper != 0u ? upper : lower, k));
    float hgt = 0.0;
    if (blocks <= 1) {
        hgt = hTop / 16.0;
    } else if (hTop > 0.0) {
        hgt = float(blocks - 1) + hTop / 16.0;
    } else {
        float hLow = float(plantProfile(palette, lower, k));
        hgt = hLow > 0.0 ? float(blocks - 2) + hLow / 16.0 : 0.0;
    }
    s.rel = D * t;
    float yr = s.rel.y + f.camFrac.y + float(f.origin.y) - (float(GEOM_Y(g)) + oy);
    if (hgt <= 0.0 || yr > hgt || yr < 0.0) return false;
    s.depth = pos.z;
    s.face = FACE_PLANT;
    record = (pb & 0xFFFFu) | ((g & GEOM_WET) != 0u ? REC_WET : 0u);
    return true;
}

fragment half4 lod_mesh_plant_vanilla(MeshPlantVOut in [[stage_in]], constant CompFrame& f [[buffer(22)]], device const uint* geom [[buffer(24)]],
                                      device const uint* color [[buffer(25)]], device const uint* crowns [[buffer(26)]],
                                      device const uint* texWords [[buffer(27)]], device const PaletteEntry* palette [[buffer(28)]],
                                      texture2d<half> atlas [[texture(20)]], sampler smp [[sampler(14)]]) {
    CompSurface s;
    uint record;
    if (!meshPlantSurface(f, in.position, in.info, geom, texWords, palette, s, record)) discard_fragment();
    compShade(f, uint2(1u, record), geom, color, crowns, texWords, palette, atlas, smp, s);
    half4 o = half4(compFog(f, s.rel, s.albedo * half3(f.faceShade.x * s.ao * s.sky * f.skyLight.rgb)), 1.0h);
    if (f.tex.w == 1.0) {
        const half3 dc[8] = {half3(0.5h), half3(0, 1, 0), half3(1, 0, 0), half3(0, 0, 1), half3(1, 0, 1), half3(0, 1, 1), half3(1, 1, 0), half3(0)};
        o = half4(dc[s.debug & 7u] * half(0.5 + 0.5 * s.ao), 1.0h);
    }
    return o;
}

fragment MeshGBufferOut lod_mesh_plant_gbuffer(MeshPlantVOut in [[stage_in]], constant CompFrame& f [[buffer(22)]], device const uint* geom [[buffer(24)]],
                                               device const uint* color [[buffer(25)]], device const uint* crowns [[buffer(26)]],
                                               device const uint* texWords [[buffer(27)]], device const PaletteEntry* palette [[buffer(28)]],
                                               texture2d<half> atlas [[texture(20)]], sampler smp [[sampler(14)]]) {
    CompSurface s;
    uint record;
    if (!meshPlantSurface(f, in.position, in.info, geom, texWords, palette, s, record)) discard_fragment();
    compShade(f, uint2(1u, record), geom, color, crowns, texWords, palette, atlas, smp, s);
    return meshGBuffer(s);
}

kernel void lod_pk_reset(constant MeshFrame& f [[buffer(0)]], device uint* args [[buffer(4)]], device uint* sec [[buffer(15)]],
                         constant PkParams& pk [[buffer(14)]], uint gid [[thread_position_in_grid]]) {
    if (gid < 128u) args[gid] = 0u;
    if (gid < uint(PK_SECTORS) && pkHas4(pk.rebuild, int(gid))) {
        device uint* w = sec + gid * PK_SECTOR_WORDS;
        w[0] = 0u;
        w[1] = 0x7FFFFFFFu;
        w[2] = 0x80000000u;
        w[3] = 0xFFFFFFFFu;
        w[4] = 0u;
        w[5] = 0x7FFFFFFFu;
        w[6] = 0x80000000u;
        w[7] = 0u;
        w[8] = 0u;
        w[9] = uint(f.origin.x);
        w[10] = uint(f.origin.y);
        w[11] = uint(f.origin.z);
        w[12] = as_type<uint>(f.cam.x);
        w[13] = as_type<uint>(f.cam.y);
        w[14] = as_type<uint>(f.cam.z);
        w[15] = 0u;
    }
}

kernel void lod_pk_hz_clear(device uint* hz [[buffer(8)]], constant PkParams& pk [[buffer(14)]], uint gid [[thread_position_in_grid]]) {
    if (gid < uint(HZ_BINS * HZ_BANDS) && pkBinIn(pk.needed, int(gid / uint(HZ_BANDS)))) hz[gid] = 0u;
    if (gid < uint(HZ_BINS) && pkBinIn(pk.needed, int(gid))) hz[HZ_FARMIN + gid] = 0xFFFFFFFFu;
}

kernel void lod_pk_hz_prefix(device uint* hz [[buffer(8)]], constant PkParams& pk [[buffer(14)]], uint gid [[thread_position_in_grid]]) {
    if (gid >= uint(HZ_BINS) || !pkBinIn(pk.needed, int(gid))) return;
    uint m = 0u;
    for (int k = 0; k < HZ_BANDS; k++) {
        uint v = hz[gid * HZ_BANDS + uint(k)];
        m = max(m, v);
        hz[gid * HZ_BANDS + uint(k)] = m;
    }
}

static inline void pkWordsBox(constant MeshFrame& f, uint w0, uint w1, int tx, int tz, int L, thread float3& lo, thread float3& hi) {
    int x = int(w0 & 127u), z = int((w0 >> 7) & 127u), e1 = int((w0 >> 14) & 63u) + 1, e2 = int((w0 >> 20) & 63u) + 1;
    uint face = (w0 >> 26) & 7u;
    int y0 = int(w1 & 0xFFFu) - 512, y1 = int((w1 >> 12) & 0xFFFu) - 512;
    int wx = (((tx << 6) + x) << L) - f.origin.x, wz = (((tz << 6) + z) << L) - f.origin.z;
    float3 rel = float3(float(wx), float(y0 - f.origin.y), float(wz)) - f.cam.xyz;
    float cell = float(1 << L);
    bool flat = face == FACE_TOP || face == FACE_BOTTOM;
    float ex = flat || face == FACE_ZP || face == FACE_ZN ? float(e1) * cell : 0.0;
    float ez = flat ? float(e2) * cell : (face == FACE_XP || face == FACE_XN ? float(e1) * cell : 0.0);
    lo = rel;
    hi = float3(rel.x + ex, rel.y + (flat ? 0.0 : float(y1 - y0)), rel.z + ez);
}

kernel void lod_pk_quads(constant MeshFrame& f [[buffer(0)]], device const uint4* table [[buffer(1)]], device const uint* arena [[buffer(2)]],
                         device atomic_uint* args [[buffer(4)]], device const MeshInstance* inst [[buffer(5)]], device uint4* recs [[buffer(7)]],
                         device const uint* hz [[buffer(8)]], device const uint* geom [[buffer(12)]], device const uint* crowns [[buffer(13)]],
                         constant PkParams& pk [[buffer(14)]], device atomic_uint* sec [[buffer(15)]], device MeshInstance* stand [[buffer(16)]],
                         uint gid [[thread_position_in_grid]], uint lane [[thread_index_in_simdgroup]]) {
    uint n = atomic_load_explicit(&args[ARGS_CAND], memory_order_relaxed);
    uint iid = gid / MESH_Q, q = gid % MESH_Q;
    MeshInstance mi;
    mi.a = uint4(0u);
    mi.b = int4(0);
    if (iid < n) mi = inst[iid];
    bool live = iid < n && q < (mi.a.y & 255u);
    uint s = (mi.a.y >> 16) & 127u, L = (mi.a.y >> 8) & 15u;
    bool clipped = (mi.a.y & (1u << 12)) != 0u;
    device atomic_uint* w = sec + s * PK_SECTOR_WORDS;
    uint standAt = 0u;
    if (iid < n && clipped && lane == 0u) {
        standAt = atomic_fetch_add_explicit(&w[8], 1u, memory_order_relaxed);
        if (standAt < pk.standCap) {
            MeshInstance ms = mi;
            uint4 e;
            if (meshResident(f, table, int(L), int(mi.a.z), int(mi.a.w), e)) {
                int bpt = 64 / f.counts.y;
                ms.a.x -= (e.x - 1u + uint(bpt * bpt * MESH_HDR)) / 2u;
                ms.a.y |= MESH_REL;
            }
            stand[pk.standBase + s * pk.standCap + standAt] = ms;
        }
        else atomic_fetch_or_explicit(&w[15], 4u, memory_order_relaxed);
    }
    standAt = simd_broadcast_first(standAt);
    bool keep = false;
    uint4 rec = uint4(0u);
    if (live) {
        if (clipped) {
            keep = standAt < pk.standCap;
            rec = uint4(q, 0u, pk.standBase + s * pk.standCap + standAt, 1u << 24 | L << 20);
        } else {
            uint qi = mi.a.x + q;
            uint w0 = arena[qi * 2u], w1 = arena[qi * 2u + 1u];
            int tx = int(mi.a.z), tz = int(mi.a.w);
            if ((w0 >> 31) != 0u) {
                int y0 = min(int(w1 & 0xFFFu) - 512, meshSkirtFloor(f, table, geom, crowns, w0, tx, tz, int(L)));
                w1 = (w1 & ~0xFFFu) | uint(y0 + 512);
                w0 &= 0x7FFFFFFFu;
            }
            float3 lo, hi;
            pkWordsBox(f, w0, w1, tx, tz, int(L), lo, hi);
            uint face = (w0 >> 26) & 7u;
            float eps = f.hz.z;
            keep = face == FACE_TOP ? lo.y < eps : face == FACE_BOTTOM ? lo.y > -eps : face == FACE_XP ? lo.x < eps
                : face == FACE_XN ? lo.x > -eps : face == FACE_ZP ? lo.z < eps : lo.z > -eps;
            if (keep && f.opts.z != 0 && hzHiddenEps(f, hz, lo.xz, hi.xz, hi.y, eps, pk.needed)) keep = false;
            rec = uint4(w0, w1, mi.a.z, (mi.a.w & 0xFFFFFu) | L << 20);
        }
    }
    uint k = keep ? 1u : 0u;
    uint before = simd_prefix_exclusive_sum(k), total = simd_sum(k);
    uint base = 0u;
    if (lane == 0u && total > 0u) base = atomic_fetch_add_explicit(&w[0], total, memory_order_relaxed);
    base = simd_broadcast_first(base);
    if (keep) {
        if (base + before < pkRecCap(pk, s)) recs[pkRecBase(pk, s) + base + before] = rec;
        else atomic_fetch_or_explicit(&w[15], 1u, memory_order_relaxed);
    }
    if (total > 0u) {
        float a0 = INFINITY, a1 = -INFINITY, dn = INFINITY, df = 0.0, y0 = INFINITY, y1 = -INFINITY;
        if (keep) {
            float3 lo, hi;
            if (clipped) {
                lo = float3(float(mi.b.x) - f.cam.x, float(-512 - f.origin.y) - f.cam.y, float(mi.b.z) - f.cam.z);
                hi = float3(float(mi.b.y) - f.cam.x, float(512 - f.origin.y) - f.cam.y, float(mi.b.w) - f.cam.z);
            } else {
                pkWordsBox(f, rec.x, rec.y, int(rec.z), int(rec.w << 12) >> 12, int(L), lo, hi);
            }
            float2 cs[4] = {lo.xz, float2(hi.x, lo.z), float2(lo.x, hi.z), hi.xz};
            float2 r = hzRange(cs, 4);
            a0 = r.x - float(s % uint(PK_AZ)) * (4.0 / float(PK_AZ));
            a0 -= 4.0 * floor((a0 + 2.0) * 0.25);
            a1 = a0 + (r.y - r.x);
            float nx = max(0.0, max(lo.x, -hi.x)), nz = max(0.0, max(lo.z, -hi.z));
            float fx = max(abs(lo.x), abs(hi.x)), fz = max(abs(lo.z), abs(hi.z));
            dn = sqrt(nx * nx + nz * nz);
            df = sqrt(fx * fx + fz * fz);
            y0 = lo.y;
            y1 = hi.y;
        }
        a0 = simd_min(a0);
        a1 = simd_max(a1);
        dn = simd_min(dn);
        df = simd_max(df);
        y0 = simd_min(y0);
        y1 = simd_max(y1);
        if (lane == 0u) {
            atomic_fetch_min_explicit((device atomic_int*) &w[1], int(floor(a0 * 1048576.0)), memory_order_relaxed);
            atomic_fetch_max_explicit((device atomic_int*) &w[2], int(ceil(a1 * 1048576.0)), memory_order_relaxed);
            atomic_fetch_min_explicit(&w[3], hzKey(dn), memory_order_relaxed);
            atomic_fetch_max_explicit(&w[4], hzKey(df), memory_order_relaxed);
            atomic_fetch_min_explicit((device atomic_int*) &w[5], int(floor(y0)), memory_order_relaxed);
            atomic_fetch_max_explicit((device atomic_int*) &w[6], int(ceil(y1)), memory_order_relaxed);
        }
    }
}

static inline float2 hzDir(float a) {
    a -= 4.0 * floor(a * 0.25);
    float q = a < 2.0 ? 1.0 - a : a - 3.0;
    return normalize(float2(q, a < 2.0 ? 1.0 - abs(q) : abs(q) - 1.0));
}

#define PK_LIST_IDS 1
#define PK_LIST_RECS (PK_LIST_IDS + PK_SECTORS)
#define PK_LIST_PLANTS (PK_LIST_RECS + PK_SECTORS + 1)

kernel void lod_pk_select(constant CompFrame& f [[buffer(22)]], constant MeshFrame& mf [[buffer(0)]], device const uint* sec [[buffer(15)]],
                          constant PkParams& pk [[buffer(14)]], device uint* list [[buffer(10)]], device uint* args [[buffer(4)]],
                          uint tid [[thread_index_in_threadgroup]]) {
    threadgroup uint rc[PK_SECTORS], pc[PK_SECTORS];
    uint s = tid;
    device const uint* w = sec + s * PK_SECTOR_WORDS;
    uint cnt = min(w[0], pkRecCap(pk, s)), pcnt = min(w[7], pk.plantCap);
    bool vis = pkHas4(pk.drawable, int(s)) && (cnt > 0u || pcnt > 0u);
    if (vis) {
        float3 d = float3(int3(int(w[9]), int(w[10]), int(w[11])) - f.origin.xyz) + float3(as_type<float>(w[12]), as_type<float>(w[13]), as_type<float>(w[14]))
            - f.camFrac.xyz;
        float base = float(s % uint(PK_AZ)) * (4.0 / float(PK_AZ));
        float a0 = base + float(as_type<int>(w[1])) / 1048576.0, a1 = base + float(as_type<int>(w[2])) / 1048576.0;
        if (a1 - a0 < 1.8) {
            float dn = hzValue(w[3]), df = hzValue(w[4]);
            float2 u0 = hzDir(a0), u1 = hzDir(a1), um = hzDir((a0 + a1) * 0.5);
            float ch = sqrt(max(0.5 * (1.0 + dot(u0, u1)), 1e-4));
            float2 pts[5] = {u0 * dn, u1 * dn, u0 * df, u1 * df, um * (df / ch)};
            float y0 = float(as_type<int>(w[5])) + d.y, y1 = float(as_type<int>(w[6])) + d.y;
            float4x4 m = f.viewProj;
            float4 r0 = float4(m[0][0], m[1][0], m[2][0], m[3][0]), r1 = float4(m[0][1], m[1][1], m[2][1], m[3][1]);
            float4 r3 = float4(m[0][3], m[1][3], m[2][3], m[3][3]);
            float4 planes[4] = {r3 + r0, r3 - r0, r3 + r1, r3 - r1};
            for (int i = 0; i < 4 && vis; i++) {
                bool any = false;
                for (int k = 0; k < 10 && !any; k++) {
                    float3 p = float3(pts[k >> 1].x + d.x, (k & 1) != 0 ? y1 : y0, pts[k >> 1].y + d.z);
                    any = dot(planes[i].xyz, p) + planes[i].w >= 0.0;
                }
                vis = any;
            }
        }
    }
    rc[s] = vis ? cnt : 0u;
    pc[s] = vis ? pcnt : 0u;
    threadgroup_barrier(mem_flags::mem_threadgroup);
    if (tid != 0u) return;
    uint n = 0u, acc = 0u, pacc = 0u;
    for (uint k = 0u; k < uint(PK_SECTORS); k++) {
        if (rc[k] == 0u && pc[k] == 0u) continue;
        list[PK_LIST_IDS + n] = k;
        list[PK_LIST_RECS + n] = acc;
        list[PK_LIST_PLANTS + n] = pacc;
        acc += rc[k];
        pacc += pc[k];
        n++;
    }
    list[PK_LIST_RECS + n] = acc;
    list[PK_LIST_PLANTS + n] = pacc;
    list[0] = n;
    if ((pk.live.x | pk.live.y | pk.live.z | pk.live.w) == 0u)
        for (int k = 0; k < 32; k++) args[k] = 0u;
    args[0] = MESH_DRAW_Q * 6u;
    args[5] = MESH_Q * 6u;
    args[16] = (acc + MESH_QUAD_TG - 1u) / MESH_QUAD_TG;
    args[17] = 1u;
    args[18] = 1u;
    args[19] = (pacc + 63u) / 64u;
    args[20] = 1u;
    args[21] = 1u;
}

static inline uint pkFind(device const uint* list, uint at, uint n, uint i) {
    uint lo = 0u, hi = n;
    while (hi - lo > 1u) {
        uint mid = (lo + hi) >> 1;
        if (list[at + mid] <= i) lo = mid;
        else hi = mid;
    }
    return lo;
}

static inline bool pkViewKeep(constant CompFrame& f, constant MeshFrame& mf, device const uint4* table, uint4 r, device const MeshInstance* stand,
                              device const uint* arena) {
    float4 P[4];
    if ((r.w & (1u << 24)) != 0u) {
        MeshInstance mi = stand[r.z];
        if (!meshResolve(mf, table, mi)) return false;
        for (uint c = 0u; c < 4u; c++) P[c] = meshCorner(f, mi, arena, r.x * 4u + c).position;
    } else {
        uint w0 = r.x, w1 = r.y;
        int L = int((r.w >> 20) & 15u), tx = int(r.z), tz = int(r.w << 12) >> 12;
        int x = int(w0 & 127u), z = int((w0 >> 7) & 127u), e1 = int((w0 >> 14) & 63u) + 1, e2 = int((w0 >> 20) & 63u) + 1;
        uint face = (w0 >> 26) & 7u;
        int y0 = int(w1 & 0xFFFu) - 512, y1 = int((w1 >> 12) & 0xFFFu) - 512;
        int wx = (((tx << 6) + x) << L) - f.origin.x, wz = (((tz << 6) + z) << L) - f.origin.z;
        float3 rel = float3(float(wx), float(y0 - f.origin.y), float(wz)) - f.camFrac.xyz;
        float4x4 m = f.viewProj;
        float4 p = m * float4(rel, 1.0);
        float cell = float(1 << L);
        float4 X = m[0], Y = m[1], Z = m[2];
        if (face == FACE_TOP || face == FACE_BOTTOM) {
            float4 U = X * (float(e1) * cell), V = Z * (float(e2) * cell);
            if (face == FACE_TOP) {
                P[0] = p; P[1] = p + V; P[2] = p + U + V; P[3] = p + U;
            } else {
                P[0] = p; P[1] = p + U; P[2] = p + U + V; P[3] = p + V;
            }
        } else {
            float4 H = Y * float(y1 - y0);
            float4 W = (face == FACE_XP || face == FACE_XN ? Z : X) * (float(e1) * cell);
            if (face == FACE_XP || face == FACE_ZN) {
                P[0] = p + W; P[1] = p; P[2] = p + H; P[3] = p + W + H;
            } else {
                P[0] = p; P[1] = p + W; P[2] = p + W + H; P[3] = p + H;
            }
        }
        for (int c = 0; c < 4; c++) P[c].y = -P[c].y;
    }
    float2 s[4];
    float2 lo = float2(1e30), hi = float2(-1e30);
    for (uint c = 0u; c < 4u; c++) {
        float4 p = P[c];
        if (p.w <= 1e-4) return true;
        s[c] = (p.xy / p.w * float2(0.5, -0.5) + 0.5) * f.screen.xy;
        lo = min(lo, s[c]);
        hi = max(hi, s[c]);
    }
    bool onScreen = hi.x > 0.0 && hi.y > 0.0 && lo.x < f.screen.x && lo.y < f.screen.y;
    bool covers = ceil(lo.x - 0.5) <= floor(hi.x - 0.5) && ceil(lo.y - 0.5) <= floor(hi.y - 0.5);
    float area = (s[1].x - s[0].x) * (s[2].y - s[0].y) - (s[2].x - s[0].x) * (s[1].y - s[0].y)
        + (s[2].x - s[0].x) * (s[3].y - s[0].y) - (s[3].x - s[0].x) * (s[2].y - s[0].y);
    return onScreen && covers && area > 0.0;
}

kernel void lod_pk_frame(constant CompFrame& f [[buffer(22)]], constant MeshFrame& mf [[buffer(0)]], device const uint4* recs [[buffer(7)]],
                         device const uint* list [[buffer(10)]], device atomic_uint* args [[buffer(4)]], device uint4* survivors [[buffer(11)]],
                         device const MeshInstance* stand [[buffer(16)]], device const uint* arena [[buffer(2)]], constant PkParams& pk [[buffer(14)]],
                         device const uint4* table [[buffer(1)]], uint gid [[thread_position_in_grid]], uint lane [[thread_index_in_simdgroup]]) {
    uint n = list[0];
    bool keep = false;
    uint4 r = uint4(0u);
    if (gid < list[PK_LIST_RECS + n]) {
        uint k = pkFind(list, PK_LIST_RECS, n, gid);
        r = recs[pkRecBase(pk, list[PK_LIST_IDS + k]) + (gid - list[PK_LIST_RECS + k])];
        keep = pkViewKeep(f, mf, table, r, stand, arena);
    }
    uint k = keep ? 1u : 0u;
    uint before = simd_prefix_exclusive_sum(k), sum = simd_sum(k);
    uint base = 0u;
    if (lane == 0u && sum > 0u) base = atomic_fetch_add_explicit(&args[ARGS_SURV], sum, memory_order_relaxed);
    base = simd_broadcast_first(base);
    if (keep && base + before < uint(mf.caps.x)) survivors[base + before] = r;
}

kernel void lod_pk_plants(constant MeshFrame& mf [[buffer(0)]], device const MeshInstance* plantSec [[buffer(6)]], device const uint* list [[buffer(10)]],
                          device atomic_uint* args [[buffer(4)]], device MeshInstance* plantOut [[buffer(12)]], constant PkParams& pk [[buffer(14)]],
                          device const uint4* table [[buffer(1)]], uint gid [[thread_position_in_grid]]) {
    uint n = list[0];
    if (gid >= list[PK_LIST_PLANTS + n]) return;
    uint k = pkFind(list, PK_LIST_PLANTS, n, gid);
    MeshInstance mi = plantSec[list[PK_LIST_IDS + k] * pk.plantCap + (gid - list[PK_LIST_PLANTS + k])];
    if (!meshResolve(mf, table, mi)) return;
    uint at = atomic_fetch_add_explicit(&args[6], 1u, memory_order_relaxed);
    if (at < uint(mf.counts.w)) plantOut[at] = mi;
}

kernel void lod_pk_finish(constant MeshFrame& f [[buffer(0)]], device uint* args [[buffer(4)]], uint gid [[thread_position_in_grid]]) {
    if (gid != 0u) return;
    uint n = min(args[ARGS_SURV], uint(f.caps.x));
    args[ARGS_SURV] = n;
    args[0] = MESH_DRAW_Q * 6u;
    args[1] = (n + MESH_DRAW_Q - 1u) / MESH_DRAW_Q;
    args[6] = min(args[6], uint(f.counts.w));
}

#define PK_FACET_TILE 8

kernel void lod_pk_facet_gather(constant PkParams& pk [[buffer(14)]], device const uint* sec [[buffer(15)]], device const uint4* recs [[buffer(7)]],
                                device uint4* out [[buffer(11)]], device atomic_uint* args [[buffer(4)]], constant uint& az [[buffer(20)]],
                                constant uint& cap [[buffer(21)]], constant uint4& use [[buffer(9)]], uint gid [[thread_position_in_grid]]) {
    uint stride = max(pk.recCap, pk.recCap0);
    uint k = gid / stride, i = gid % stride;
    if (k >= 6u) return;
    uint a = (az + uint(PK_AZ) + (k % 3u) - 1u) % uint(PK_AZ), s = a + (k / 3u) * uint(PK_AZ);
    if (!pkHas4(use, int(s))) return;
    uint n = min(sec[s * PK_SECTOR_WORDS], pkRecCap(pk, s));
    if (i >= n) return;
    uint4 r = recs[pkRecBase(pk, s) + i];
    if (((r.w >> 25) & 3u) == 3u) return;
    uint at = atomic_fetch_add_explicit(&args[ARGS_SURV], 1u, memory_order_relaxed);
    if (at < cap) out[at] = r;
}

kernel void lod_pk_facet_args(device uint* args [[buffer(4)]], constant uint& cap [[buffer(21)]], uint gid [[thread_position_in_grid]]) {
    if (gid != 0u) return;
    uint n = min(args[ARGS_SURV], cap);
    args[ARGS_SURV] = n;
    args[0] = MESH_DRAW_Q * 6u;
    args[1] = (n + MESH_DRAW_Q - 1u) / MESH_DRAW_Q;
    args[2] = 0u;
    args[3] = 0u;
    args[4] = 0u;
}

fragment float lod_pk_facet_fs(MeshVOut in [[stage_in]]) {
    return 1.0 / in.position.w;
}

kernel void lod_pk_facet_reduce(texture2d<float, access::read> dist [[texture(0)]], device float* tiles [[buffer(0)]], device atomic_uint* near [[buffer(1)]],
                                constant uint2& size [[buffer(2)]], uint2 gid [[thread_position_in_grid]]) {
    uint tw = (size.x + PK_FACET_TILE - 1u) / PK_FACET_TILE, th = (size.y + PK_FACET_TILE - 1u) / PK_FACET_TILE;
    if (gid.x >= tw || gid.y >= th) return;
    float m = 0.0, n = FLT_MAX;
    for (uint y = 0u; y < uint(PK_FACET_TILE); y++)
        for (uint x = 0u; x < uint(PK_FACET_TILE); x++) {
            uint2 p = uint2(gid.x * PK_FACET_TILE + x, gid.y * PK_FACET_TILE + y);
            if (p.x < size.x && p.y < size.y) {
                float d = dist.read(p).r;
                m = max(m, d);
                n = min(n, d);
            }
        }
    tiles[gid.y * tw + gid.x] = m;
    atomic_fetch_min_explicit(near, as_type<uint>(n), memory_order_relaxed);
}

kernel void lod_pk_facet_prune(constant CompFrame& f [[buffer(22)]], constant MeshFrame& mf [[buffer(0)]], constant PkParams& pk [[buffer(14)]],
                               device const uint* sec [[buffer(15)]], device const uint4* recs [[buffer(7)]], device const MeshInstance* stand [[buffer(16)]],
                               device const uint* arena [[buffer(2)]], device const uint4* table [[buffer(1)]], device const float* tiles [[buffer(8)]],
                               device uint4* out [[buffer(11)]], device atomic_uint* cnt [[buffer(12)]], constant uint2& sel [[buffer(20)]],
                               constant float2& epsB [[buffer(21)]], device const float* near [[buffer(9)]], uint gid [[thread_position_in_grid]]) {
    uint stride = max(pk.recCap, pk.recCap0);
    uint k = gid / stride, i = gid % stride;
    if (k >= 2u || ((sel.y >> k) & 1u) == 0u) return;
    uint s = sel.x + k * uint(PK_AZ);
    float eps = k == 0u ? epsB.x : epsB.y;
    uint n = sec[s * PK_SECTOR_WORDS];
    if (n > pkRecCap(pk, s)) {
        if (i == 0u) atomic_store_explicit(&cnt[2u + k], 1u, memory_order_relaxed);
        return;
    }
    if (i >= n) return;
    uint4 r = recs[pkRecBase(pk, s) + i];
    float4 P[4];
    bool keep = false;
    if ((r.w & (1u << 24)) != 0u) {
        keep = true;
        for (uint c = 0u; c < 4u; c++) P[c] = float4(0.0, 0.0, 0.0, 2.0);
    } else {
        int L = int((r.w >> 20) & 15u), tz = int(r.w << 12) >> 12;
        for (uint c = 0u; c < 4u; c++) P[c] = meshQuadCorner(f, r.x & 0x7FFFFFFFu, r.y, int(r.z), tz, L, false, int4(0), c, 0u).position;
    }
    float2 lo = float2(1e30), hi = float2(-1e30);
    float wmin = 1e30;
    for (uint c = 0u; c < 4u && !keep; c++) {
        if (P[c].w <= 1.0) {
            keep = true;
            break;
        }
        float2 sc = (P[c].xy / P[c].w * float2(0.5, -0.5) + 0.5) * f.screen.xy;
        lo = min(lo, sc);
        hi = max(hi, sc);
        wmin = min(wmin, P[c].w);
    }
    if (!keep) {
        float g = (eps / wmin + eps / max(near[0], 1.0)) * abs(f.viewProj[0][0]) * f.screen.x * 0.5 + 1.0;
        lo -= g;
        hi += g;
        if (lo.x < 0.0 || lo.y < 0.0 || hi.x >= f.screen.x || hi.y >= f.screen.y) {
            keep = true;
        } else {
            uint tw = (uint(f.screen.x) + PK_FACET_TILE - 1u) / PK_FACET_TILE;
            uint2 t0 = uint2(lo) / PK_FACET_TILE, t1 = uint2(hi) / PK_FACET_TILE;
            float far = 0.0;
            for (uint ty = t0.y; ty <= t1.y && far < wmin; ty++)
                for (uint tx = t0.x; tx <= t1.x; tx++) far = max(far, tiles[ty * tw + tx]);
            keep = far + 2.0 * eps + 0.5 >= wmin;
        }
    }
    if (!keep) {
        if ((mf.opts.z & 8) == 0) return;
        r.w |= 3u << 25;
    }
    uint at = atomic_fetch_add_explicit(&cnt[k], 1u, memory_order_relaxed);
    out[k * stride + at] = r;
}

kernel void lod_pk_facet_commit(constant PkParams& pk [[buffer(14)]], device uint* sec [[buffer(15)]], device uint4* recs [[buffer(7)]],
                                device const uint4* out [[buffer(11)]], device const uint* cnt [[buffer(12)]], constant uint2& sel [[buffer(20)]],
                                uint gid [[thread_position_in_grid]]) {
    uint stride = max(pk.recCap, pk.recCap0);
    uint k = gid / stride, i = gid % stride;
    if (k >= 2u || ((sel.y >> k) & 1u) == 0u) return;
    uint s = sel.x + k * uint(PK_AZ);
    if (cnt[2u + k] != 0u) return;
    if (i < cnt[k]) recs[pkRecBase(pk, s) + i] = out[k * stride + i];
    if (i == 0u) sec[s * PK_SECTOR_WORDS] = cnt[k];
}
