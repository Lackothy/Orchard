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

/**
 * The Quality page's "Faster Improved Transparency". With Improved Transparency on, Minecraft
 * draws a section's see-through faces in three passes (depth bounds, transmittance, accumulate),
 * every face each time, though its pipelines then cull the ones turned away (26.3's OIT terrain
 * keeps RenderPipeline's default culling). Order doesn't matter to those passes, so when a
 * section's see-through faces are uploaded they are put in groups by the way they face, each
 * sorted by its plane; each frame a section is then drawn as the runs of those groups that can
 * face the camera, and only faces the GPU would cull are left out, so it looks the same.
 * <p>
 * Faces that aren't flat on one axis plane, or whose two triangles disagree, go in a group that
 * is always drawn. A face is left out only when the camera is {@link #EPS} behind its plane.
 * Sorting happens on the worker that compiled the section; the runs are found on the render thread.
 */
public final class SeeThroughFaces {

    /** A face is left out only when the camera is at least this far behind its plane (blocks). */
    private static final float EPS = 1f / 64;
    private static final long LOG_MS = 30_000;
    /** Groups in upload order: +X, +Y, +Z, any, -Z, -Y, -X, so a camera off every axis of a section draws at most three runs. */
    private static final int ANY = 3;
    private static final int[] AXIS = {0, 1, 2, -1, 2, 1, 0};

    private static long logged;
    private static long faces;
    private static long drawn;
    private static long sections;
    private static long draws;

    private SeeThroughFaces() {
    }

    /** What a section keeps for this: where each group starts, in faces (8 entries), and each face's plane in upload order. */
    public record Groups(int[] start, float[] plane) {
    }

    /** CompiledSectionMesh, through a mixin: its see-through faces' groups, or null when they are in Minecraft's own order. */
    public interface Holder {
        Groups orchard$groups();

        void orchard$groups(Groups groups);
    }

    /**
     * A section's see-through layer about to be uploaded with Improved Transparency on: its faces
     * reordered in {@code indices} by group and plane. Null (and nothing changed) when the buffers
     * aren't what's expected.
     */
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
        // (the vertex size from the data: positions are the first three floats of each vertex, as in DefaultVertexFormat.BLOCK)
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
                // corners: the first triangle's three, then the second's one not in it
                int v = index[q * 6 + (c < 3 ? c : 4)];
                int at = start + v * stride;
                p[c * 3] = vertices.getFloat(at);
                p[c * 3 + 1] = vertices.getFloat(at + 4);
                p[c * 3 + 2] = vertices.getFloat(at + 8);
            }
            group[q] = facing(p, index, q, vertices, start, stride);
            int axis = AXIS[group[q]];
            plane[q] = axis < 0 ? 0 : (p[axis] + p[3 + axis] + p[6 + axis] + p[9 + axis]) / 4;
            // (positions are the section's own, 0-16: anything far outside isn't, so it's always drawn)
            if (axis >= 0 && (plane[q] < -1 || plane[q] > 17)) {
                group[q] = ANY;
                plane[q] = 0;
            }
        }

        // each group sorted by plane, highest first: a + group's faces that face the camera are then a run at its
        // end (planes below the camera), a - group's a run at its start (planes above)
        // (the key: the group in bits 60-62, the plane's order in the next 32 down, the face in the low 28: a section holds far fewer)
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

    /** The group of face q from its corners: an axis plane and the side its triangles face, or ANY. */
    private static int facing(float[] p, int[] index, int q, ByteBuffer vertices, int start, int stride) {
        int axis = -1;
        for (int a = 0; a < 3; a++) {
            float lo = Math.min(Math.min(p[a], p[3 + a]), Math.min(p[6 + a], p[9 + a]));
            float hi = Math.max(Math.max(p[a], p[3 + a]), Math.max(p[6 + a], p[9 + a]));
            if (hi - lo < 1e-4f) {
                if (axis >= 0) return ANY;  // (flat on two axes: a line or a point)
                axis = a;
            }
        }
        if (axis < 0) return ANY;
        float first = normal(index, q * 6, axis, vertices, start, stride);
        float second = normal(index, q * 6 + 3, axis, vertices, start, stride);
        if (Math.abs(first) < 1e-6f || Math.abs(second) < 1e-6f || first > 0 != second > 0) return ANY;
        return first > 0 ? axis : 6 - axis;
    }

    /** The axis component of the normal of the triangle at index i, wound as drawn. */
    private static float normal(int[] index, int i, int axis, ByteBuffer vertices, int start, int stride) {
        int u = (axis + 1) % 3, w = (axis + 2) % 3;
        int a = start + index[i] * stride, b = start + index[i + 1] * stride, c = start + index[i + 2] * stride;
        float bu = vertices.getFloat(b + u * 4) - vertices.getFloat(a + u * 4), bw = vertices.getFloat(b + w * 4) - vertices.getFloat(a + w * 4);
        float cu = vertices.getFloat(c + u * 4) - vertices.getFloat(a + u * 4), cw = vertices.getFloat(c + w * 4) - vertices.getFloat(a + w * 4);
        return bu * cw - bw * cu;
    }

    /**
     * A section's see-through draw this frame: through {@code add}, the runs of its groups that can
     * face the camera. False, and nothing added, when it should be drawn as it is.
     */
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

    /** The first face in [s, e) whose plane is below {@code limit} (planes run highest first), or e. */
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

    /** Each frame's draws made: logs what was left out every 30 s while there was anything to split. */
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
