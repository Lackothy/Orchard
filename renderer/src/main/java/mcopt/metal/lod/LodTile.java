package mcopt.metal.lod;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;

final class LodTile {
	static final int SIZE = 64, CELLS = SIZE * SIZE;
	static final short DRY = Short.MIN_VALUE;
	static final int SOURCE_NOISE = 1, SOURCE_CHUNKS = 2;

	final int level, x0, z0, size;
	final short[] height;
	final short[] water;
	final int[] top;
	final int[] side;
	final int[] below;
	final boolean[] fringe;
	final int[] tex;
	final BlockState[] state;
	final Holder<Biome>[] biome;
	final short[] ground;
	final short[] canopyLo, canopyHi;
	final BlockState[] canopyState;
	final boolean[] standing;
	final BlockState[] trunk;
	final int[] crownTop, crownSide;
	final int[] crownRuns;
	final BlockState[] plantLower, plantUpper;
	final byte[] plantBlocks;
	final int[] plantColor;
	boolean waterKnown;
	boolean impostorTrees = true;
	int source;

	LodTile(int level, int tx, int tz) {
		this(level, tx * (SIZE << level), tz * (SIZE << level), SIZE);
	}

	@SuppressWarnings("unchecked")
	LodTile(int level, int x0, int z0, int size) {
		this.level = level;
		this.x0 = x0;
		this.z0 = z0;
		this.size = size;
		int n = size * size;
		this.height = new short[n];
		this.water = new short[n];
		this.top = new int[n];
		this.side = new int[n];
		this.below = new int[n];
		this.fringe = new boolean[n];
		this.tex = new int[n];
		this.state = new BlockState[n];
		this.biome = new Holder[n];
		this.ground = new short[n];
		this.canopyLo = new short[n];
		this.canopyHi = new short[n];
		java.util.Arrays.fill(this.canopyHi, Short.MIN_VALUE);
		this.canopyState = new BlockState[n];
		this.standing = new boolean[n];
		this.trunk = new BlockState[n];
		this.crownTop = new int[n];
		this.crownSide = new int[n];
		this.crownRuns = new int[n];
		this.plantLower = new BlockState[n];
		this.plantUpper = new BlockState[n];
		this.plantBlocks = new byte[n];
		this.plantColor = new int[n];
	}

	static int runs(long occupied) {
		if (occupied == 0) return 0;
		int[] start = new int[64], len = new int[64];
		int n = 0;
		for (int b = 0; b < 64; ) {
			if ((occupied >>> b & 1) == 0) {
				b++;
				continue;
			}
			int e = b;
			while (e < 64 && (occupied >>> e & 1) != 0) e++;
			start[n] = b;
			len[n] = e - b;
			n++;
			b = e;
		}
		while (n > 1) {
			int merge = -1;
			if (n > 4) {
				int best = Integer.MAX_VALUE;
				for (int k = 1; k < n; k++) {
					int gap = start[k] - (start[k - 1] + len[k - 1]);
					if (gap < best) {
						best = gap;
						merge = k;
					}
				}
			} else {
				for (int k = 1; k < n && merge < 0; k++) if (start[k] - (start[k - 1] + len[k - 1]) > 7) merge = k;
			}
			if (merge < 0) break;
			len[merge - 1] = start[merge] + len[merge] - start[merge - 1];
			System.arraycopy(start, merge + 1, start, merge, n - merge - 1);
			System.arraycopy(len, merge + 1, len, merge, n - merge - 1);
			n--;
		}
		for (int k = 0; k < n; k++) if (len[k] > 31) return 0;
		if (n == 1) return 0;
		int word = 0, at = 0;
		for (int k = 0; k < n; k++) {
			int gap = start[k] - at, l = Math.min(31, len[k]);
			word |= (gap & 7 | l << 3) << (8 * k);
			at = start[k] + l;
		}
		return word;
	}

	static long key(int level, int tx, int tz) {
		return (long) level << 58 | (long) (tx & 0x1FFFFFFF) << 29 | (tz & 0x1FFFFFFF);
	}

	static int levelOf(long key) {
		return (int) (key >>> 58);
	}

	static int txOf(long key) {
		return (int) (key << 6 >> 35);
	}

	static int tzOf(long key) {
		return (int) (key << 35 >> 35);
	}

	int cells() {
		return this.size * this.size;
	}

	int cell() {
		return 1 << this.level;
	}

	int minX() {
		return this.x0;
	}

	int minZ() {
		return this.z0;
	}

	int surface(int i) {
		return this.water[i] != DRY ? Math.max(this.water[i], this.height[i]) : this.height[i];
	}
}
