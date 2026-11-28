// Modified for Russet: block light raised by Russet's dynamic lights (lightCoords, lightAt, only those near the section), and biomes read without the zoom inside one biome.
package mcopt.metal.own;

import com.lackothy.russet.fabulous.DynamicLights;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.chunk.SectionCopy;
import net.minecraft.core.Holder;
import net.minecraft.util.ARGB;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.core.BlockPos;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import org.jspecify.annotations.Nullable;

public final class OwnRegion implements BlockAndTintGetter {
	private static final int S = 20, L = 18;
	private static final ThreadLocal<OwnRegion> LOCAL = ThreadLocal.withInitial(OwnRegion::new);

	private final BlockState[] states = new BlockState[S * S * S];
	private final byte[] sky = new byte[L * L * L], block = new byte[L * L * L];
	private final int[] packed = new int[L * L * L];
	private final float[] shade = new float[S * S * S];
	static final boolean AOCACHE = Boolean.getBoolean("mcopt.own.mesh.aocache");
	public static final boolean FRAPI_REGION = Boolean.getBoolean("mcopt.own.mesh.frapiRegion");
	private SectionCopy[] copies;
	private int minSx, minSy, minSz;
	final int[] aoLight = new int[S * S * S];
	final float[] aoShade = new float[S * S * S];
	final byte[] aoPerm = new byte[S * S * S];
	private int gen;
	private RenderSectionRegion region;
	private LevelLightEngine light;
	private int sx, sy, sz, lx, ly, lz;
	private static final ThreadLocal<OwnRegion> CURRENT = new ThreadLocal<>();
	static final boolean TINT = Boolean.getBoolean("mcopt.own.mesh.tint");
	static final boolean PREFILL = Boolean.getBoolean("mcopt.own.mesh.prefill");
	private static final int TW = 32, TH = 18, TR = 8;
	@SuppressWarnings("unchecked")
	private final Holder<Biome>[] biomes = new Holder[TW * TW * TH];
	private final int[] biomeGen = new int[TW * TW * TH];
	/** Russet: the quart cells the zoom reads for the tint box (QW x QW x QH from qx, qy, qz), per compile as biomes are. */
	private static final int QW = TW / 4 + 2, QH = TH / 4 + 3;
	@SuppressWarnings("unchecked")
	private final Holder<Biome>[] quarts = new Holder[QW * QW * QH];
	private final int[] quartGen = new int[QW * QW * QH];
	private int qx, qy, qz;
	/** Russet: the dynamic lights that reach the cached box (DynamicLights.near), taken once a compile. */
	private DynamicLights.Built lights;
	private boolean biomesUsed;
	private final ColorResolver[] resolvers = new ColorResolver[4];
	private final int[][] colors = new int[4][TW * TW * TH], colorGen = new int[4][TW * TW * TH];
	private final BlockPos.MutableBlockPos tintPos = new BlockPos.MutableBlockPos();
	private int tx, ty, tz;
	private final int[] ids = new int[4096];

	static OwnRegion of(RenderSectionRegion region, BlockPos origin) {
		OwnRegion r = LOCAL.get();
		java.util.Arrays.fill(r.states, null);
		java.util.Arrays.fill(r.sky, (byte) -1);
		java.util.Arrays.fill(r.block, (byte) -1);
		java.util.Arrays.fill(r.packed, -1);
		java.util.Arrays.fill(r.shade, Float.NaN);
		if (AOCACHE) {
			java.util.Arrays.fill(r.aoLight, -1);
			java.util.Arrays.fill(r.aoShade, Float.NaN);
			java.util.Arrays.fill(r.aoPerm, (byte) -1);
		}
		if (++r.gen == 0) {
			java.util.Arrays.fill(r.biomeGen, 0);
			java.util.Arrays.fill(r.quartGen, 0);
			for (int[] cg : r.colorGen) java.util.Arrays.fill(cg, 0);
			r.gen = 1;
		}
		r.region = region;
		if (FRAPI_REGION) {
			r.copies = ((OwnRegionAccess) region).mcopt$sections();
			r.minSx = (origin.getX() >> 4) - 1;
			r.minSy = (origin.getY() >> 4) - 1;
			r.minSz = (origin.getZ() >> 4) - 1;
			((OwnRegionAccess) region).mcopt$own(r);
		}
		r.light = region.getLightEngine();
		r.sx = origin.getX() - 2;
		r.sy = origin.getY() - 2;
		r.sz = origin.getZ() - 2;
		r.lights = DynamicLights.near(r.sx, r.sy, r.sz, r.sx + S - 1, r.sy + S - 1, r.sz + S - 1);
		r.lx = origin.getX() - 1;
		r.ly = origin.getY() - 1;
		r.lz = origin.getZ() - 1;
		r.tx = origin.getX() - TR;
		r.ty = origin.getY() - 1;
		r.tz = origin.getZ() - TR;
		r.qx = (r.tx - 2) >> 2;
		r.qy = (r.ty - 2) >> 2;
		r.qz = (r.tz - 2) >> 2;
		if (PREFILL) r.prefill(region);
		if (TINT) CURRENT.set(r);
		return r;
	}

	void release() {
		if (FRAPI_REGION) {
			if (this.region != null) ((OwnRegionAccess) this.region).mcopt$own(null);
			this.copies = null;
		}
		this.region = null;
		this.light = null;
		this.lights = null;
		if (this.biomesUsed) {
			java.util.Arrays.fill(this.biomes, null);
			java.util.Arrays.fill(this.quarts, null);
			this.biomesUsed = false;
		}
		if (TINT) CURRENT.remove();
	}

	private static final ThreadLocal<boolean[]> SUSPENDED = ThreadLocal.withInitial(() -> new boolean[1]);

	static @Nullable OwnRegion current() {
		return SUSPENDED.get()[0] ? null : CURRENT.get();
	}

	static void suspend(boolean on) {
		SUSPENDED.get()[0] = on;
	}

	private void prefill(RenderSectionRegion region) {
		SectionCopy copy = ((OwnRegionAccess) region).mcopt$sections()[13];
		OwnRegionAccess.Copy c = (OwnRegionAccess.Copy) copy;
		if (c.mcopt$debug()) return;
		PalettedContainer<BlockState> section = c.mcopt$section();
		int[] ids = this.ids;
		BlockState[] states = this.states;
		if (section == null) {
			BlockState air = Blocks.AIR.defaultBlockState();
			for (int i = 0; i < 4096; i++) {
				int at = ((i >> 8) + 2) * S * S + ((i >> 4 & 15) + 2) * S + (i & 15) + 2;
				states[at] = air;
			}
			return;
		}
		Palette<BlockState> palette = OwnPalette.unpack(section, ids);
		BlockState last = null;
		int lastId = -1;
		for (int i = 0; i < 4096; i++) {
			int id = ids[i];
			if (id != lastId) {
				last = palette.valueFor(id);
				lastId = id;
			}
			int at = ((i >> 8) + 2) * S * S + ((i >> 4 & 15) + 2) * S + (i & 15) + 2;
			states[at] = last;
		}
	}

	private Holder<Biome> biome(ClientLevel level, int x, int y, int z) {
		int qx = (x - 2 >> 2) - this.qx, qy = (y - 2 >> 2) - this.qy, qz = (z - 2 >> 2) - this.qz;
		if (qx >= 0 && qy >= 0 && qz >= 0 && qx + 1 < QW && qy + 1 < QH && qz + 1 < QW) {
			Holder<Biome> b = this.quart(level, qx, qy, qz);
			if (b == this.quart(level, qx + 1, qy, qz) && b == this.quart(level, qx, qy + 1, qz) && b == this.quart(level, qx + 1, qy + 1, qz)
				&& b == this.quart(level, qx, qy, qz + 1) && b == this.quart(level, qx + 1, qy, qz + 1) && b == this.quart(level, qx, qy + 1, qz + 1)
				&& b == this.quart(level, qx + 1, qy + 1, qz + 1)) return b;
		}
		return level.getBiome(this.tintPos.set(x, y, z));
	}

	private Holder<Biome> quart(ClientLevel level, int qx, int qy, int qz) {
		int i = (qy * QW + qz) * QW + qx;
		if (this.quartGen[i] == this.gen) return this.quarts[i];
		Holder<Biome> b = level.getNoiseBiome(this.qx + qx, this.qy + qy, this.qz + qz);
		this.quarts[i] = b;
		this.quartGen[i] = this.gen;
		this.biomesUsed = true;
		return b;
	}

	@Nullable Integer blendTint(ClientLevel level, BlockPos pos, ColorResolver resolver, int dist) {
		int x0 = pos.getX() - dist - this.tx, z0 = pos.getZ() - dist - this.tz, y = pos.getY() - this.ty;
		int w = dist * 2 + 1;
		if (x0 < 0 || z0 < 0 || y < 0 || y >= TH || x0 + w > TW || z0 + w > TW) return null;
		int slot = -1;
		for (int i = 0; i < 4; i++) {
			if (this.resolvers[i] == resolver) {
				slot = i;
				break;
			}
			if (this.resolvers[i] == null) {
				this.resolvers[i] = resolver;
				slot = i;
				break;
			}
		}
		if (slot < 0) return null;
		int[] col = this.colors[slot], cg = this.colorGen[slot];
		int g = this.gen, totalRed = 0, totalGreen = 0, totalBlue = 0;
		for (int dz = 0; dz < w; dz++) {
			for (int dx = 0; dx < w; dx++) {
				int i = (y * TW + z0 + dz) * TW + x0 + dx;
				int color;
				if (cg[i] == g) {
					color = col[i];
				} else {
					int bx = this.tx + x0 + dx, bz = this.tz + z0 + dz;
					Holder<Biome> biome;
					if (this.biomeGen[i] == g) {
						biome = this.biomes[i];
					} else {
						biome = this.biome(level, bx, pos.getY(), bz);
						this.biomes[i] = biome;
						this.biomeGen[i] = g;
						this.biomesUsed = true;
					}
					color = resolver.getColor(biome.value(), bx, bz);
					col[i] = color;
					cg[i] = g;
				}
				totalRed += ARGB.red(color);
				totalGreen += ARGB.green(color);
				totalBlue += ARGB.blue(color);
			}
		}
		int count = w * w;
		return ARGB.color(totalRed / count, totalGreen / count, totalBlue / count);
	}

	@Override
	public BlockState getBlockState(BlockPos pos) {
		int x = pos.getX() - this.sx, y = pos.getY() - this.sy, z = pos.getZ() - this.sz;
		if ((x | y | z) >= 0 && x < S && y < S && z < S) {
			int i = (y * S + z) * S + x;
			BlockState s = this.states[i];
			if (s != null) return s;
			s = this.raw(pos);
			this.states[i] = s;
			return s;
		}
		return this.raw(pos);
	}

	private BlockState raw(BlockPos pos) {
		if (!FRAPI_REGION) return this.region.getBlockState(pos);
		int sx = pos.getX() >> 4, sy = pos.getY() >> 4, sz = pos.getZ() >> 4;
		return this.copies[sx - this.minSx + (sy - this.minSy) * 3 + (sz - this.minSz) * 9].getBlockState(pos);
	}

	public static @Nullable OwnRegion forRegion(Object r) {
		return r instanceof OwnRegionAccess a ? a.mcopt$own() : null;
	}

	@Override
	public FluidState getFluidState(BlockPos pos) {
		return this.getBlockState(pos).getFluidState();
	}

	@Override
	public int getBrightness(LightLayer layer, BlockPos pos) {
		int x = pos.getX() - this.lx, y = pos.getY() - this.ly, z = pos.getZ() - this.lz;
		if ((x | y | z) >= 0 && x < L && y < L && z < L) {
			int i = (y * L + z) * L + x;
			if (layer == LightLayer.SKY) {
				int c = this.sky[i];
				if (c >= 0) return c;
				int v = this.light.getLayerListener(LightLayer.SKY).getLightValue(pos);
				this.sky[i] = (byte) v;
				return v;
			}
			int c = this.block[i];
			if (c >= 0) return c;
			int v = this.light.getLayerListener(LightLayer.BLOCK).getLightValue(pos);
			this.block[i] = (byte) v;
			return v;
		}
		return this.light.getLayerListener(layer).getLightValue(pos);
	}

	public int lightCoords(BlockState state, BlockPos pos) {
		if (state.emissiveRendering()) return 15728880;
		int x = pos.getX() - this.lx, y = pos.getY() - this.ly, z = pos.getZ() - this.lz;
		int p;
		if ((x | y | z) >= 0 && x < L && y < L && z < L) {
			int i = (y * L + z) * L + x;
			p = this.packed[i];
			if (p < 0) {
				p = LightCoordsUtil.BrightnessGetter.DEFAULT.packedBrightness(this, pos);
				this.packed[i] = p;
			}
		} else {
			p = LightCoordsUtil.BrightnessGetter.DEFAULT.packedBrightness(this, pos);
		}
		int blockLight = LightCoordsUtil.block(p), emission = state.getLightEmission();
		// Russet: and to its dynamic lights' level, as vanilla's own lookup is (DynamicLightMixin).
		return this.dynamic(blockLight < emission ? LightCoordsUtil.withBlock(p, emission) : p, pos.getX(), pos.getY(), pos.getZ());
	}

	public float shadeBrightness(BlockState state, BlockPos pos) {
		int x = pos.getX() - this.sx, y = pos.getY() - this.sy, z = pos.getZ() - this.sz;
		if ((x | y | z) >= 0 && x < S && y < S && z < S) {
			int i = (y * S + z) * S + x;
			float v = this.shade[i];
			if (v == v) return v;
			v = state.getShadeBrightness(this, pos);
			this.shade[i] = v;
			return v;
		}
		return state.getShadeBrightness(this, pos);
	}

	private final BlockPos.MutableBlockPos scratch = new BlockPos.MutableBlockPos();

	int aoIndex(int px, int py, int pz) {
		int x = px - this.sx, y = py - this.sy, z = pz - this.sz;
		return (x | y | z) >= 0 && x < S && y < S && z < S ? (y * S + z) * S + x : -1;
	}

	int aoLight(int i, int px, int py, int pz) {
		int v = this.aoLight[i];
		if (v >= 0) return v;
		v = this.lightAt(this.stateAt(px, py, pz), px, py, pz);
		this.aoLight[i] = v;
		return v;
	}

	float aoShade(int i, int px, int py, int pz) {
		float v = this.aoShade[i];
		if (v == v) return v;
		v = this.shadeAt(this.stateAt(px, py, pz), px, py, pz);
		this.aoShade[i] = v;
		return v;
	}

	boolean aoPerm(int i, int px, int py, int pz) {
		int v = this.aoPerm[i];
		if (v >= 0) return v != 0;
		boolean b = this.stateAt(px, py, pz).isLightPermeable();
		this.aoPerm[i] = (byte) (b ? 1 : 0);
		return b;
	}

	BlockState stateAt(int px, int py, int pz) {
		int x = px - this.sx, y = py - this.sy, z = pz - this.sz;
		if ((x | y | z) >= 0 && x < S && y < S && z < S) {
			int i = (y * S + z) * S + x;
			BlockState s = this.states[i];
			if (s != null) return s;
			s = this.raw(this.scratch.set(px, py, pz));
			this.states[i] = s;
			return s;
		}
		return this.raw(this.scratch.set(px, py, pz));
	}

	int lightAt(BlockState state, int px, int py, int pz) {
		if (state.emissiveRendering()) return 15728880;
		int x = px - this.lx, y = py - this.ly, z = pz - this.lz;
		int p;
		if ((x | y | z) >= 0 && x < L && y < L && z < L) {
			int i = (y * L + z) * L + x;
			p = this.packed[i];
			if (p < 0) {
				p = LightCoordsUtil.BrightnessGetter.DEFAULT.packedBrightness(this, this.scratch.set(px, py, pz));
				this.packed[i] = p;
			}
		} else {
			p = LightCoordsUtil.BrightnessGetter.DEFAULT.packedBrightness(this, this.scratch.set(px, py, pz));
		}
		int blockLight = LightCoordsUtil.block(p), emission = state.getLightEmission();
		// Russet: and to its dynamic lights' level, as vanilla's own lookup is (DynamicLightMixin).
		return this.dynamic(blockLight < emission ? LightCoordsUtil.withBlock(p, emission) : p, px, py, pz);
	}

	private int dynamic(int packed, int x, int y, int z) {
		int bx = x - this.sx, by = y - this.sy, bz = z - this.sz;
		return (bx | by | bz) >= 0 && bx < S && by < S && bz < S ? DynamicLights.raise(packed, this.lights, x, y, z) : DynamicLights.raise(packed, x, y, z);
	}

	float shadeAt(BlockState state, int px, int py, int pz) {
		int x = px - this.sx, y = py - this.sy, z = pz - this.sz;
		if ((x | y | z) >= 0 && x < S && y < S && z < S) {
			int i = (y * S + z) * S + x;
			float v = this.shade[i];
			if (v == v) return v;
			v = state.getShadeBrightness(this, this.scratch.set(px, py, pz));
			this.shade[i] = v;
			return v;
		}
		return state.getShadeBrightness(this, this.scratch.set(px, py, pz));
	}

	@Override
	public @Nullable BlockEntity getBlockEntity(BlockPos pos) {
		return this.region.getBlockEntity(pos);
	}

	@Override
	public CardinalLighting cardinalLighting() {
		return this.region.cardinalLighting();
	}

	@Override
	public LevelLightEngine getLightEngine() {
		return this.light;
	}

	@Override
	public int getBlockTint(BlockPos pos, ColorResolver resolver) {
		return this.region.getBlockTint(pos, resolver);
	}

	@Override
	public int getMinY() {
		return this.region.getMinY();
	}

	@Override
	public int getHeight() {
		return this.region.getHeight();
	}
}
