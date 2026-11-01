package mcopt.metal.chunk;

public final class VisSweep {
	private VisSweep() { }

	public static int reach(long[] opaque, int directionSet, int origin) {
		boolean down = (directionSet & 1) != 0, north = (directionSet & 4) != 0, west = (directionSet & 16) != 0;
		int yFace = down ? 0 : 1, zFace = north ? 2 : 3, xFace = west ? 4 : 5;
		int seedKind = origin == (down ? 1 : 0) ? 0 : origin == (north ? 3 : 2) ? 1 : 2;
		int yStart = down ? 15 : 0, yStep = down ? -1 : 1;
		int zStart = north ? 15 : 0, zStep = north ? -1 : 1;
		int xSeed = west ? 0x8000 : 0x0001, xEnd = west ? 0x0001 : 0x8000;
		int zEnd = north ? 0 : 15;
		int allFaces = 1 << yFace | 1 << zFace | 1 << xFace;
		int[] prev = new int[16], cur = new int[16];
		boolean seeded = false;
		int faces = 0;
		for (int yi = 0, y = yStart; yi < 16; yi++, y += yStep) {
			int rowPrev = 0, layer = 0;
			for (int zi = 0, z = zStart; zi < 16; zi++, z += zStep) {
				int open = ~(int) (opaque[y << 2 | z >>> 2] >>> ((z & 3) << 4)) & 0xFFFF;
				int in = (prev[z] | rowPrev) & open;
				if (seedKind == 0 ? yi == 0 : seedKind == 1 ? zi == 0 : true) {
					int seeds = seedKind == 2 ? open & xSeed : open;
					seeded |= seeds != 0;
					in |= seeds;
				}
				int r = west ? fillDown(in, open) : fillUp(in, open);
				cur[z] = r;
				rowPrev = r;
				layer |= r;
				if (r != 0) {
					if (z == zEnd) faces |= 1 << zFace;
					if ((r & xEnd) != 0) faces |= 1 << xFace;
				}
			}
			if (yi == 15 && layer != 0) faces |= 1 << yFace;
			int[] t = prev;
			prev = cur;
			cur = t;
			if (faces == allFaces || layer == 0 && seedKind == 0) break;
		}
		return seeded ? faces | 64 : 0;
	}

	static int fillDown(int g, int open) {
		int p = open;
		g |= p & g >>> 1;
		p &= p >>> 1;
		g |= p & g >>> 2;
		p &= p >>> 2;
		g |= p & g >>> 4;
		p &= p >>> 4;
		g |= p & g >>> 8;
		return g;
	}

	static int fillUp(int g, int open) {
		int p = open;
		g |= p & g << 1;
		p &= p << 1;
		g |= p & g << 2;
		p &= p << 2;
		g |= p & g << 4;
		p &= p << 4;
		g |= p & g << 8;
		return g & 0xFFFF;
	}
}
