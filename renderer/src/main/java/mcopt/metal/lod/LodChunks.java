// Modified for Russet: water's color over its floor from LodColors.underWater, its below colour its lit floor; a snapshot of any chunk (LodSaved reads saved ones); overhangs as crowns (overhangs);
// each column's block light at its top (glow).
package mcopt.metal.lod;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.SectionPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.levelgen.Heightmap;

final class LodChunks {
	record Snapshot(int chunkX, int chunkZ, BlockState[] top, BlockState[] under, short[] height, short[] water, Holder<Biome>[] biome,
		BlockState[] crown, BlockState[] crownLeaf, short[] crownLo, short[] crownHi, int[] crownRuns, BlockState[] trunk, BlockState[] above,
		short[] runFrom, short[] slabTop, PalettedContainer<BlockState>[] sections, DataLayer[] sky, int secFirst,
		int minY, byte[] glow) {
	}

	record Summary(int chunkX, int chunkZ, short[] height, short[] water, int[] top, int[] side, int[] below, boolean[] fringe, short[] crownLo,
		short[] crownHi, int[] groundColor, int[] tex, int[] runs, int[] plantA, int[] plantB, boolean[] clear, byte[] depth, byte[] glow) {
	}

	private LodChunks() {
	}

	private static boolean decoration(BlockState s) {
		return !s.isAir() && s.getFluidState().isEmpty() && !s.is(Blocks.SNOW) && !s.is(Blocks.POWDER_SNOW)
			&& s.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty();
	}

	private static boolean crownBlock(BlockState s) {
		return s.is(BlockTags.LEAVES) || s.is(Blocks.SNOW);
	}

	static final boolean OVERHANGS = !"false".equals(System.getProperty("mcopt.lod.overhangs"));
	private static final int SLAB_MAX = 63, GAP_MAX = 128, WATER_MAX = 32;

	static final int DEPTH_MAX = 127;
	private static final boolean RUNS = LodMesh.REAL_OCC && !"false".equals(System.getProperty("mcopt.lod.realOccRuns"));

	private static int solidRun(Snapshot s, byte[] solid, int x, int y, int z) {
		int d = 0;
		while (d < DEPTH_MAX) {
			if (y < s.minY) return DEPTH_MAX;
			int si = ((y - s.minY) >> 4) - s.secFirst;
			if (si < 0 || si >= s.sections.length) return d;
			PalettedContainer<BlockState> sec = s.sections[si];
			if (sec == null) return d;
			if (solid[si] == 0) solid[si] = (byte) (!sec.maybeHas(st -> !st.canOcclude()) ? 1 : 2);
			if (solid[si] == 1) {
				int n = ((y - s.minY) & 15) + 1;
				d += n;
				y -= n;
				continue;
			}
			if (!sec.get(x, (y - s.minY) & 15, z).canOcclude()) return d;
			d++;
			y--;
		}
		return DEPTH_MAX;
	}

	static Snapshot snapshot(net.minecraft.world.level.chunk.ChunkAccess chunk) {
		return snapshot(chunk, null, null);
	}

	@SuppressWarnings("unchecked")
	static Snapshot snapshot(net.minecraft.world.level.chunk.ChunkAccess chunk, DataLayer @org.jspecify.annotations.Nullable [] sky,
		DataLayer @org.jspecify.annotations.Nullable [] block) {
		int minY = chunk.getMinY();
		BlockState[] top = new BlockState[256], under = new BlockState[256], crown = new BlockState[256], crownLeaf = new BlockState[256];
		short[] height = new short[256], water = new short[256], crownLo = new short[256], crownHi = new short[256];
		int[] crownRuns = new int[256];
		BlockState[] trunk = new BlockState[256];
		BlockState[] above = new BlockState[256 * 3];
		Holder<Biome>[] biome = new Holder[256];
		short[] runFrom = RUNS ? new short[256] : null;
		int runLo = Integer.MAX_VALUE, runHi = Integer.MIN_VALUE;
		short[] slabTop = OVERHANGS ? new short[256] : null;
		int slabLo = Integer.MAX_VALUE, slabHi = Integer.MIN_VALUE;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		int bx0 = chunk.getPos().getMinBlockX(), bz0 = chunk.getPos().getMinBlockZ();
		for (int z = 0; z < 16; z++) {
			for (int x = 0; x < 16; x++) {
				int i = z * 16 + x;
				int px = bx0 + x, pz = bz0 + z;
				int y = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
				BlockState s = chunk.getBlockState(pos.set(px, y, pz));
				for (int guard = 0; guard < 8 && y > minY && (s.isAir() || decoration(s)); guard++) s = chunk.getBlockState(pos.set(px, --y, pz));
				crownLo[i] = Short.MIN_VALUE;
				crown[i] = null;
				if (crownBlock(s)) {
					int topY = y;
					BlockState topState = s;
					int k = y - 1, lowest = y;
					BlockState b = chunk.getBlockState(pos.set(px, k, pz));
					boolean air = false;
					long below = topState.is(BlockTags.LEAVES) ? 1L : 0L;
					for (int guard = 0; guard < 48 && k > minY; guard++) {
						if (b.is(BlockTags.LEAVES)) {
							lowest = k;
							air = false;
							if (topY - k < 64) below |= 1L << (topY - k);
						} else if (b.isAir() || decoration(b) || b.is(Blocks.SNOW)) {
							air = true;
						} else {
							break;
						}
						b = chunk.getBlockState(pos.set(px, --k, pz));
					}
					if (b.is(BlockTags.LOGS)) trunk[i] = b;
					if (air && !b.is(BlockTags.LOGS)) {
						crown[i] = topState;
						crownLeaf[i] = topState.is(Blocks.SNOW) ? chunk.getBlockState(pos.set(px, topY - 1, pz)) : topState;
						crownLo[i] = (short) lowest;
						long up = 0;
						for (int d = 0; d < 64; d++) if ((below >>> d & 1) != 0 && topY - lowest - d >= 0 && topY - lowest - d < 64) up |= 1L << (topY - lowest - d);
						crownRuns[i] = LodTile.runs(up);
						crownHi[i] = (short) (topState.is(Blocks.SNOW) ? topY : topY + 1);
						y = k;
						s = b;
					}
				}
				short wet = LodTile.DRY;
				if (s.getFluidState().is(FluidTags.WATER)) {
					wet = (short) (y + 1);
					for (int guard = 0; guard < 96 && y > minY && (s.getFluidState().is(FluidTags.WATER) || s.isAir()); guard++) {
						s = chunk.getBlockState(pos.set(px, --y, pz));
					}
				}
				top[i] = s;
				height[i] = (short) (s.is(Blocks.SNOW) ? y : y + 1);
				water[i] = wet;
				under[i] = chunk.getBlockState(pos.set(px, y - 1, pz));
				if (runFrom != null) {
					int from = s.is(Blocks.SNOW) ? y - 1 : y;
					runFrom[i] = wet == LodTile.DRY ? (short) from : Short.MIN_VALUE;
					if (wet == LodTile.DRY) {
						runLo = Math.min(runLo, from);
						runHi = Math.max(runHi, from);
					}
				}
				if (slabTop != null) {
					boolean slab = crown[i] == null && wet == LodTile.DRY && !s.is(Blocks.SNOW) && s.canOcclude();
					slabTop[i] = slab ? (short) y : Short.MIN_VALUE;
					if (slab) {
						slabLo = Math.min(slabLo, y);
						slabHi = Math.max(slabHi, y);
					}
				}
				if (crown[i] == null && wet == LodTile.DRY && !s.is(Blocks.SNOW)) {
					BlockState a = chunk.getBlockState(pos.set(px, y + 1, pz));
					for (int k = 0; k < 3 && decoration(a); k++) {
						above[i * 3 + k] = a;
						a = chunk.getBlockState(pos.set(px, y + 2 + k, pz));
					}
				}
				biome[i] = chunk.getNoiseBiome(QuartPos.fromBlock(px), QuartPos.fromBlock(y), QuartPos.fromBlock(pz));
			}
		}
		PalettedContainer<BlockState>[] sections = null;
		DataLayer[] light = null;
		int secFirst = 0;
		int reachLo = Math.min(runLo <= runHi ? runLo - DEPTH_MAX : Integer.MAX_VALUE, slabLo <= slabHi ? slabLo - SLAB_MAX - GAP_MAX - WATER_MAX : Integer.MAX_VALUE);
		int reachHi = Math.max(runLo <= runHi ? runHi : Integer.MIN_VALUE, slabLo <= slabHi ? slabHi : Integer.MIN_VALUE);
		if (reachLo <= reachHi) {
			secFirst = Math.max(0, (Math.max(minY, reachLo) - minY) >> 4);
			int secLast = Math.min(chunk.getSectionsCount() - 1, (reachHi - minY) >> 4);
			sections = new PalettedContainer[Math.max(0, secLast - secFirst + 1)];
			for (int k = 0; k < sections.length; k++) {
				LevelChunkSection sec = chunk.getSection(secFirst + k);
				sections[k] = sec.hasOnlyAir() ? null : sec.getStates().copy();
			}
			var engine = sky == null && slabLo <= slabHi && chunk instanceof LevelChunk lc ? lc.getLevel().getLightEngine() : null;
			if (sky != null || engine != null && engine.lightOnInColumn(ChunkPos.pack(chunk.getPos().x(), chunk.getPos().z()))) {
				light = new DataLayer[sections.length];
				for (int k = 0; k < light.length; k++) {
					DataLayer d = sky != null ? (secFirst + k < sky.length ? sky[secFirst + k] : null)
						: engine.getLayerListener(LightLayer.SKY)
							.getDataLayerData(SectionPos.of(chunk.getPos(), chunk.getSectionYFromSectionIndex(secFirst + k)));
					light[k] = d == null ? null : sky != null ? d : d.copy();
				}
			}
		}
		return new Snapshot(chunk.getPos().x(), chunk.getPos().z(), top, under, height, water, biome, crown, crownLeaf, crownLo, crownHi, crownRuns, trunk, above,
			runFrom, slabTop, sections, light, secFirst, minY, glow(chunk, block, height, water, crown, crownHi));
	}

	private static byte[] glow(net.minecraft.world.level.chunk.ChunkAccess chunk, DataLayer @org.jspecify.annotations.Nullable [] block, short[] height,
		short[] water, BlockState[] crown, short[] crownHi) {
		byte[] glow = new byte[256];
		var engine = block == null && chunk instanceof LevelChunk lc ? lc.getLevel().getLightEngine().getLayerListener(LightLayer.BLOCK) : null;
		if (block == null && engine == null) return glow;
		int minY = chunk.getMinY(), x0 = chunk.getPos().getMinBlockX(), z0 = chunk.getPos().getMinBlockZ();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int i = 0; i < 256; i++) {
			int y = crown[i] != null ? crownHi[i] : water[i] != LodTile.DRY ? Math.max(water[i], height[i]) : height[i];
			if (block != null) {
				int si = (y - minY) >> 4;
				DataLayer d = y < minY || si >= block.length ? null : block[si];
				glow[i] = (byte) (d == null ? 0 : d.get(i & 15, (y - minY) & 15, i >> 4));
			} else {
				glow[i] = (byte) engine.getLightValue(pos.set(x0 + (i & 15), y, z0 + (i >> 4)));
			}
		}
		return glow;
	}

	private static final BlockState WATER = Blocks.WATER.defaultBlockState();

	static boolean snowy(BlockState s) {
		return s.hasProperty(BlockStateProperties.SNOWY) && s.getValue(BlockStateProperties.SNOWY);
	}

	static Summary summarize(Snapshot s) {
		int[] top = new int[256], side = new int[256], groundColor = new int[256], below = new int[256], tex = new int[256];
		int[] plantA = new int[256], plantB = new int[256];
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		boolean[] fringe = new boolean[256], clear = new boolean[256];
		short[] crownHi = new short[256];
		byte[] solidDepth = new byte[256];
		if (s.sections != null && s.runFrom != null) {
			byte[] solid = new byte[s.sections.length];
			for (int i = 0; i < 256; i++) if (s.runFrom[i] != Short.MIN_VALUE) solidDepth[i] = (byte) solidRun(s, solid, i & 15, s.runFrom[i], i >> 4);
		}
		short[] height = s.height, crownLo = s.crownLo;
		Overhangs over = s.slabTop != null && s.sections != null ? overhangs(s) : null;
		BlockState[] slabGround = over != null ? over.ground() : null;
		if (over != null) {
			height = height.clone();
			crownLo = crownLo.clone();
			for (int i = 0; i < 256; i++) {
				if (slabGround[i] == null) continue;
				crownLo[i] = over.low()[i];
				height[i] = (short) (over.groundY()[i] + 1);
			}
		}
		for (int i = 0; i < 256; i++) {
			clear[i] = s.water[i] != LodTile.DRY || s.top[i] == null || !(s.top[i].is(Blocks.SNOW) ? s.under[i] : s.top[i]).canOcclude();
		}
		int bx0 = s.chunkX * 16, bz0 = s.chunkZ * 16;
		for (int i = 0; i < 256; i++) {
			int x = bx0 + (i & 15), z = bz0 + (i >> 4);
			Biome b = s.biome[i].value();
			int color = LodColors.top(s.top[i], b, x, z);
			boolean layer = s.top[i].is(Blocks.SNOW);
			int sideColor = LodColors.side(layer ? s.under[i] : s.top[i], b, x, z);
			below[i] = LodColors.top(s.under[i], b, x, z);
			fringe[i] = LodColors.fringed(s.top[i]) && !snowy(s.top[i]);
			boolean leavesTop = s.top[i].is(BlockTags.LEAVES), snowOnLeaves = s.top[i].is(Blocks.SNOW) && s.under[i].is(BlockTags.LEAVES);
			if (leavesTop || snowOnLeaves) {
				BlockState leaf = leavesTop ? s.top[i] : s.under[i];
				if (!snowOnLeaves) color = LodColors.top(leaf, b, x, z);
				sideColor = LodColors.side(leaf, b, x, z);
				below[i] = s.trunk[i] != null ? LodColors.side(s.trunk[i], b, x, z) : sideColor;
				fringe[i] = false;
			}
			if (s.water[i] != LodTile.DRY) {
				below[i] = LodColors.floorLit(color, s.water[i] - s.height[i]);
				color = LodColors.underWater(color, LodColors.top(WATER, b, x, z), s.water[i] - s.height[i]);
				sideColor = color;
			}
			int surface = s.water[i] != LodTile.DRY ? Math.max(s.water[i], s.height[i]) : s.height[i];
			crownHi[i] = (short) surface;
			if (LodConfig.TEXTURES) {
				BlockState sideState = layer ? s.under[i] : s.top[i];
				BlockState belowState = s.trunk[i] != null && s.crown[i] == null ? s.trunk[i] : layer ? sideState : s.under[i];
				tex[i] = s.water[i] != LodTile.DRY ? 0 : LodPalette.word(LodPalette.id(s.top[i]), LodPalette.id(sideState), LodPalette.id(belowState));
			}
			if (s.crown[i] != null) {
				groundColor[i] = color;
				BlockState leaf = s.crownLeaf[i];
				BlockState topLeaf = leaf != null && leaf.is(BlockTags.LEAVES) ? leaf : Blocks.OAK_LEAVES.defaultBlockState();
				color = s.crown[i].is(Blocks.SNOW) ? LodColors.top(s.crown[i], b, x, z) : LodColors.top(topLeaf, b, x, z);
				sideColor = LodColors.side(topLeaf, b, x, z);
				crownHi[i] = s.crownHi[i];
				BlockState ground = s.top[i].is(Blocks.SNOW) ? s.under[i] : s.top[i];
				if (LodConfig.TEXTURES) tex[i] = LodPalette.word(LodPalette.id(s.crown[i]), LodPalette.id(topLeaf), LodPalette.id(ground));
			} else if (slabGround != null && slabGround[i] != null) {
				int depth = over.waterDepth()[i];
				groundColor[i] = depth > 0 ? LodColors.underWater(LodColors.top(slabGround[i], b, x, z), LodColors.top(WATER, b, x, z), depth)
					: LodColors.top(slabGround[i], b, x, z);
				crownHi[i] = s.height[i];
				if (LodConfig.TEXTURES) tex[i] = LodPalette.word(LodPalette.id(s.top[i]), LodPalette.id(s.top[i]), LodPalette.id(slabGround[i]));
			}
			top[i] = color;
			side[i] = sideColor;
			BlockState p0 = s.above[i * 3];
			if (LodConfig.PLANTS && p0 != null && s.crown[i] == null && (slabGround == null || slabGround[i] == null) && LodColors.look(p0).cross()
				&& p0.getFluidState().isEmpty()) {
				int blocks = 1;
				while (blocks < 3 && s.above[i * 3 + blocks] != null && LodColors.look(s.above[i * 3 + blocks]).cross()) blocks++;
				BlockState upper = blocks > 1 ? s.above[i * 3 + blocks - 1] : null;
				var off = p0.getOffset(pos.set(x, s.height[i], z));
				plantA[i] = LodClip.plantWordA(LodPalette.id(p0), upper != null ? LodPalette.id(upper) : 0, blocks);
				plantB[i] = LodClip.plantWordB(LodColors.side(p0, b, x, z), off.x(), off.y(), off.z());
			}
		}
		return new Summary(s.chunkX, s.chunkZ, height, s.water, top, side, below, fringe, crownLo, crownHi, groundColor, tex, s.crownRuns, plantA, plantB,
			clear, solidDepth, s.glow);
	}

	private record Overhangs(BlockState[] ground, short[] low, short[] groundY, byte[] waterDepth) {
	}

	private static @org.jspecify.annotations.Nullable BlockState at(Snapshot s, int x, int y, int z) {
		int si = ((y - s.minY) >> 4) - s.secFirst;
		if (y < s.minY || si < 0 || si >= s.sections.length) return null;
		PalettedContainer<BlockState> sec = s.sections[si];
		return sec == null ? Blocks.AIR.defaultBlockState() : sec.get(x, (y - s.minY) & 15, z);
	}

	private static int sky(Snapshot s, int x, int y, int z) {
		int si = ((y - s.minY) >> 4) - s.secFirst;
		if (s.sky == null || y < s.minY || si < 0 || si >= s.sky.length || s.sky[si] == null) return -1;
		return s.sky[si].get(x, (y - s.minY) & 15, z);
	}

	private static boolean open(BlockState b) {
		return b.isAir() || decoration(b);
	}

	private static @org.jspecify.annotations.Nullable Overhangs overhangs(Snapshot s) {
		short[] low = new short[256], groundY = new short[256];
		byte[] waterDepth = new byte[256];
		BlockState[] ground = new BlockState[256];
		boolean any = false;
		for (int i = 0; i < 256; i++) {
			low[i] = Short.MIN_VALUE;
			int top = s.slabTop[i];
			if (top == Short.MIN_VALUE) continue;
			int x = i & 15, z = i >> 4, y = top - 1;
			BlockState b = at(s, x, y, z);
			while (b != null && top - y < SLAB_MAX && !open(b) && b.getFluidState().isEmpty()) b = at(s, x, --y, z);
			if (b == null || !open(b)) continue;
			int under = y + 1, g = y;
			while (b != null && open(b) && under - g <= GAP_MAX) b = at(s, x, --g, z);
			if (b == null || open(b)) continue;
			int surface = g;
			if (!b.getFluidState().isEmpty()) {
				if (!b.getFluidState().is(FluidTags.WATER)) continue;
				while (b != null && !b.getFluidState().isEmpty() && surface - g < WATER_MAX) b = at(s, x, --g, z);
				if (b == null || !b.getFluidState().isEmpty()) continue;
				waterDepth[i] = (byte) (surface - g);
			}
			low[i] = (short) under;
			groundY[i] = (short) surface;
			ground[i] = b;
			any = true;
		}
		if (!any) return null;
		boolean[] keep = new boolean[256];
		int[] queue = new int[256];
		int head = 0, tail = 0;
		byte[] lit = new byte[256];
		for (int i = 0; i < 256; i++) {
			if (low[i] == Short.MIN_VALUE) continue;
			int light = sky(s, i & 15, low[i] - 1, i >> 4);
			lit[i] = (byte) light;
			if (light >= 0) {
				if (light > 0) {
					keep[i] = true;
					queue[tail++] = i;
				}
				continue;
			}
			for (int d = 0; d < 4; d++) {
				int j = neighbor(i, d);
				if (j < 0 || low[j] != Short.MIN_VALUE) continue;
				int floor = s.water[j] != LodTile.DRY ? Math.max(s.water[j], s.height[j]) : s.height[j];
				if (floor < low[i]) {
					keep[i] = true;
					queue[tail++] = i;
					break;
				}
			}
		}
		while (head < tail) {
			int i = queue[head++];
			for (int d = 0; d < 4; d++) {
				int j = neighbor(i, d);
				if (j < 0 || keep[j] || low[j] == Short.MIN_VALUE || lit[j] >= 0) continue;
				if (Math.max(groundY[i], groundY[j]) + 1 < Math.min(low[i], low[j])) {
					keep[j] = true;
					queue[tail++] = j;
				}
			}
		}
		if (tail == 0) return null;
		for (int i = 0; i < 256; i++) if (!keep[i]) ground[i] = null;
		return new Overhangs(ground, low, groundY, waterDepth);
	}

	private static int neighbor(int i, int d) {
		int x = i & 15, z = i >> 4;
		return switch (d) {
			case 0 -> x > 0 ? i - 1 : -1;
			case 1 -> x < 15 ? i + 1 : -1;
			case 2 -> z > 0 ? i - 16 : -1;
			default -> z < 15 ? i + 16 : -1;
		};
	}
}
