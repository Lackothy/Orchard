// Copyright 2026 mcopt-shade contributors. Licensed under the Apache License, Version 2.0 (LICENSE).
package mcopt.metal.shade.shadow;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

final class ShadowCasters {
	static final int KIND_SOLID = 0, KIND_CUTOUT = 1, KIND_TRANSLUCENT = 2;
	private static final int REGION_BYTES = 16, CHUNK_BYTES = 8;
	private static final int UNASSIGNED = 6;

	static final class DrawList {
		GpuBuffer arena;
		int kind;
		int[] chunks = new int[256];
		int count;

		DrawList(GpuBuffer arena, int kind) {
			this.arena = arena;
			this.kind = kind;
		}
	}

	@SuppressWarnings("unchecked")
	final List<DrawList>[] lists = new List[] {new ArrayList<DrawList>(), new ArrayList<DrawList>()};
	long regions = MemoryUtil.nmemAlloc((long) REGION_BYTES * 16);
	int regionCount;
	final int[] sections = new int[2];
	final long[] quads = new long[2];
	@Nullable String problem;
	int[] tintBoxes = new int[6 * 64];
	int tintCount;
	int farPart = -1, farParts = 1;
	boolean tintTight;
	int tightScans;

	boolean build(ShadowCascade[] cascades, boolean[] draw, int faceMask, double camX, double camY, double camZ, boolean lightMode, boolean tint,
		boolean tintNoWater) {
		for (int c = 0; c < 2; c++) {
			this.lists[c].clear();
			this.sections[c] = 0;
			this.quads[c] = 0;
		}
		this.regionCount = 0;
		this.tintCount = 0;
		this.tightScans = 0;
		this.problem = null;
		return true;
	}

	static int part(int sx, int sz, int parts) {
		return Math.floorMod(sx + sz * ((parts + 1) / 2), parts);
	}

	static int faceMask(double[] l, boolean all) {
		if (all) return (1 << 7) - 1;
		final double eps = 1e-4;
		int mask = 1 << UNASSIGNED;
		if (l[0] > eps) mask |= 1;
		if (l[1] > eps) mask |= 1 << 1;
		if (l[2] > eps) mask |= 1 << 2;
		if (l[0] < -eps) mask |= 1 << 3;
		if (l[1] < -eps) mask |= 1 << 4;
		if (l[2] < -eps) mask |= 1 << 5;
		return mask;
	}

	static int chunkBytes() {
		return CHUNK_BYTES;
	}

	static int regionBytes() {
		return REGION_BYTES;
	}

	void close() {
		MemoryUtil.nmemFree(this.regions);
		this.regions = 0;
	}
}
