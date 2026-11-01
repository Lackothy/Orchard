package com.lackothy.orchard.video;

import com.lackothy.Orchard;
import com.mojang.renderpearl.api.pipeline.IndexType;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;
import java.util.function.BiConsumer;
import net.minecraft.client.renderer.DynamicGpuData;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

public final class SeeThroughFaces {

    private static final float EPS = 1f / 64;
    private static final long LOG_MS = 30_000;
    private static final int ANY = 3;
    private static final int[] AXIS = {0, 1, 2, -1, 2, 1, 0};

    private static long logged;
    private static long faces;
    private static long drawn;
    private static long sections;
    private static long draws;

    private SeeThroughFaces() {
    }

    public record Groups(int[] start, float[] plane) {
    }

    public interface Holder {
        Groups orchard$groups();

        void orchard$groups(Groups groups);
    }

    public static Groups group(ByteBuffer vertexBuffer, ByteBuffer indexBuffer, IndexType type) {
        ByteBuffer vertices = vertexBuffer.duplicate().order(ByteOrder.nativeOrder());
        ByteBuffer indices = indexBuffer.duplicate().order(ByteOrder.nativeOrder());
        int bytes = type.bytes;
        int count = indices.remaining() / bytes;
        if (count == 0 || count % 6 != 0 || indices.remaining() % bytes != 0) return null;
        int base = indices.position();
        int[] index = new int[count];
        int highest = 0;
        for (int i = 0; i < count; i++) {
            int v = bytes == 2 ? indices.getShort(base + i * 2) & 0xFFFF : indices.getInt(base + i * 4);
            if (v < 0) return null;
            index[i] = v;
            highest = Math.max(highest, v);
        }
        if ((highest + 1L) * 12 > vertices.remaining() || vertices.remaining() % (highest + 1) != 0) return null;
        int stride = vertices.remaining() / (highest + 1);
        if (stride < 12) return null;
        int start = vertices.position();

        int n = count / 6;
        int[] group = new int[n];
        float[] plane = new float[n];
        float[] p = new float[12];
        for (int q = 0; q < n; q++) {
            for (int c = 0; c < 4; c++) {
                int v = index[q * 6 + (c < 3 ? c : 4)];
                int at = start + v * stride;
                p[c * 3] = vertices.getFloat(at);
                p[c * 3 + 1] = vertices.getFloat(at + 4);
                p[c * 3 + 2] = vertices.getFloat(at + 8);
            }
            group[q] = facing(p, index, q, vertices, start, stride);
            int axis = AXIS[group[q]];
            plane[q] = axis < 0 ? 0 : (p[axis] + p[3 + axis] + p[6 + axis] + p[9 + axis]) / 4;
            if (axis >= 0 && (plane[q] < -1 || plane[q] > 17)) {
                group[q] = ANY;
                plane[q] = 0;
            }
        }

        if (n >= 1 << 28) return null;
        long[] keys = new long[n];
        for (int q = 0; q < n; q++) {
            int bits = Float.floatToIntBits(-plane[q]);
            bits ^= (bits >> 31) & 0x7FFFFFFF;
            long order = (bits ^ Integer.MIN_VALUE) & 0xFFFFFFFFL;
            keys[q] = ((long) group[q] << 60) | (order << 28) | q;
        }
        Arrays.sort(keys);

        int[] starts = new int[8];
        float[] planes = new float[n];
        int[] sorted = new int[count];
        for (int i = 0; i < n; i++) {
            int q = (int) (keys[i] & ((1 << 28) - 1));
            starts[group[q] + 1]++;
            planes[i] = plane[q];
            System.arraycopy(index, q * 6, sorted, i * 6, 6);
        }
        for (int g = 1; g < 8; g++) starts[g] += starts[g - 1];
        for (int i = 0; i < count; i++) {
            if (bytes == 2) indices.putShort(base + i * 2, (short) sorted[i]);
            else indices.putInt(base + i * 4, sorted[i]);
        }
        return new Groups(starts, planes);
    }

    private static int facing(float[] p, int[] index, int q, ByteBuffer vertices, int start, int stride) {
        int axis = -1;
        for (int a = 0; a < 3; a++) {
            float lo = Math.min(Math.min(p[a], p[3 + a]), Math.min(p[6 + a], p[9 + a]));
            float hi = Math.max(Math.max(p[a], p[3 + a]), Math.max(p[6 + a], p[9 + a]));
            if (hi - lo < 1e-4f) {
                if (axis >= 0) return ANY;
                axis = a;
            }
        }
        if (axis < 0) return ANY;
        float first = normal(index, q * 6, axis, vertices, start, stride);
        float second = normal(index, q * 6 + 3, axis, vertices, start, stride);
        if (Math.abs(first) < 1e-6f || Math.abs(second) < 1e-6f || first > 0 != second > 0) return ANY;
        return first > 0 ? axis : 6 - axis;
    }

    private static float normal(int[] index, int i, int axis, ByteBuffer vertices, int start, int stride) {
        int u = (axis + 1) % 3, w = (axis + 2) % 3;
        int a = start + index[i] * stride, b = start + index[i + 1] * stride, c = start + index[i + 2] * stride;
        float bu = vertices.getFloat(b + u * 4) - vertices.getFloat(a + u * 4), bw = vertices.getFloat(b + w * 4) - vertices.getFloat(a + w * 4);
        float cu = vertices.getFloat(c + u * 4) - vertices.getFloat(a + u * 4), cw = vertices.getFloat(c + w * 4) - vertices.getFloat(a + w * 4);
        return bu * cw - bw * cu;
    }

    public static boolean split(Groups groups, BlockPos origin, Vec3 camera, DynamicGpuData.IndexedDraw draw,
        List<DynamicGpuData.IndexedDraw> list, BiConsumer<List<DynamicGpuData.IndexedDraw>, DynamicGpuData.IndexedDraw> add) {
        int[] start = groups.start();
        int n = start[7];
        if (n * 6 != draw.indexCount()) return false;
        float[] cam = {(float) (camera.x - origin.getX()), (float) (camera.y - origin.getY()), (float) (camera.z - origin.getZ())};
        int runStart = -1, runEnd = -1, kept = 0, emitted = 0;
        for (int g = 0; g < 7; g++) {
            int s = start[g], e = start[g + 1];
            if (s == e) continue;
            if (g < ANY) {
                s = firstBelow(groups.plane(), s, e, cam[AXIS[g]] + EPS);
            } else if (g > ANY) {
                e = firstBelow(groups.plane(), s, e, cam[AXIS[g]] - EPS);
            }
            if (s == e) continue;
            kept += e - s;
            if (s == runEnd) {
                runEnd = e;
                continue;
            }
            if (runStart >= 0) {
                add.accept(list, sub(draw, runStart, runEnd));
                emitted++;
            }
            runStart = s;
            runEnd = e;
        }
        faces += n;
        drawn += kept;
        if (kept == n) return false;
        if (runStart >= 0) {
            add.accept(list, sub(draw, runStart, runEnd));
            emitted++;
        }
        sections++;
        draws += emitted;
        return true;
    }

    private static int firstBelow(float[] plane, int s, int e, float limit) {
        int lo = s, hi = e;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (plane[mid] < limit) hi = mid;
            else lo = mid + 1;
        }
        return lo;
    }

    private static DynamicGpuData.IndexedDraw sub(DynamicGpuData.IndexedDraw draw, int from, int to) {
        return new DynamicGpuData.IndexedDraw((to - from) * 6, draw.instanceCount(), draw.firstIndex() + from * 6, draw.baseVertex(), draw.baseInstance());
    }

    public static void frame() {
        long now = System.nanoTime() / 1_000_000L;
        if (now - logged < LOG_MS) return;
        logged = now;
        if (faces > 0) {
            Orchard.LOG.info("Faster Improved Transparency in the last {} s: {}% of see-through faces drawn, {} section draws split into {}",
                LOG_MS / 1000, drawn * 100 / faces, sections, draws);
        }
        faces = drawn = sections = draws = 0;
    }
}
