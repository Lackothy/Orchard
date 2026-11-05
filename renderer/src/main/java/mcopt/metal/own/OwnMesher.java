package mcopt.metal.own;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import java.util.EnumMap;
import java.util.Map;
import net.minecraft.CrashReport;
import net.minecraft.CrashReportCategory;
import net.minecraft.ReportedException;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockModelLighter;
import net.minecraft.client.renderer.block.BlockQuadOutput;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.client.renderer.block.FluidStateModelSet;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.client.renderer.chunk.VisGraph;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.jspecify.annotations.Nullable;

public final class OwnMesher {
	public static final String MODE = System.getProperty("mcopt.own.mesh", "false");
	static final boolean SHADE = Boolean.getBoolean("mcopt.own.mesh.shade");
	public static final boolean ON = "true".equals(MODE) && OwnTerrain.COMPACT && (SHADE || !"native".equals(System.getProperty("mcopt.shade")));
	public static final boolean VERIFY = "verify".equals(MODE);
	static final boolean REGION = Boolean.getBoolean("mcopt.own.mesh.region");
	static final boolean HIDDEN = Boolean.getBoolean("mcopt.own.mesh.hidden");
	private static final long[] HIDDEN_TOTAL = new long[2], HIDDEN_COUNT = new long[2 * 7];
	private static long hiddenLog, hiddenControl;

	private static void countHidden(OwnQuads quads, RenderSectionRegion region, SectionPos sectionPos) {
		long[] out = new long[8];
		long[] all = new long[2], hid = new long[14];
		long control = 0;
		for (int l = 0; l < 2; l++) {
			java.util.Arrays.fill(out, 0);
			quads.layers[l].countHidden(region, sectionPos.minBlockX(), sectionPos.minBlockY(), sectionPos.minBlockZ(), out);
			all[l] = quads.layers[l].total();
			System.arraycopy(out, 0, hid, l * 7, 7);
			control += out[7];
		}
		synchronized (HIDDEN_TOTAL) {
			for (int l = 0; l < 2; l++) HIDDEN_TOTAL[l] += all[l];
			for (int i = 0; i < 14; i++) HIDDEN_COUNT[i] += hid[i];
			hiddenControl += control;
			long now = System.nanoTime();
			if (now > hiddenLog) {
				hiddenLog = now + 5_000_000_000L;
				System.out.printf("mcopt-own mesh hidden: solid %d of %d quads (+X %d -X %d +Y %d -Y %d +Z %d -Z %d), cutout %d of %d (+X %d -X %d +Y %d -Y %d +Z %d -Z %d)%n",
					HIDDEN_COUNT[0], HIDDEN_TOTAL[0], HIDDEN_COUNT[1], HIDDEN_COUNT[2], HIDDEN_COUNT[3], HIDDEN_COUNT[4], HIDDEN_COUNT[5], HIDDEN_COUNT[6],
					HIDDEN_COUNT[7], HIDDEN_TOTAL[1], HIDDEN_COUNT[8], HIDDEN_COUNT[9], HIDDEN_COUNT[10], HIDDEN_COUNT[11], HIDDEN_COUNT[12], HIDDEN_COUNT[13]);
				System.out.println("mcopt-own mesh hidden: control (same boundary test, open neighbour): " + hiddenControl + " quads");
			}
		}
	}

	static {
		if (("true".equals(MODE) || VERIFY) && !OwnTerrain.COMPACT) System.out.println("mcopt-own mesh: needs -Dmcopt.own.compact=true, off");
		if (ON || VERIFY) System.out.println("mcopt-own mesh: " + MODE);
	}

	private OwnMesher() {
	}

	public static SectionCompiler.@Nullable Results compile(boolean ambientOcclusion, boolean cutoutLeaves, BlockStateModelSet blockModelSet,
		FluidStateModelSet fluidModelSet, BlockColors blockColors, SectionPos sectionPos, RenderSectionRegion region, VertexSorting vertexSorting,
		SectionBufferBuilderPack builders) {
		if (OwnTerrain.get() == null) return null;
		SectionCompiler.Results results = new SectionCompiler.Results();
		OwnQuads quads = OwnQuads.get();
		Map<ChunkSectionLayer, BufferBuilder> startedLayers = new EnumMap<>(ChunkSectionLayer.class);
		if (OwnMaterials.ON) OwnMaterials.compiling(true);
		try {
			mesh(ambientOcclusion, cutoutLeaves, blockModelSet, fluidModelSet, blockColors, sectionPos, region, builders, results, quads, startedLayers);
		} finally {
			if (OwnMaterials.ON) OwnMaterials.compiling(false);
		}
		for (Map.Entry<ChunkSectionLayer, BufferBuilder> entry : startedLayers.entrySet()) {
			ChunkSectionLayer layer = entry.getKey();
			MeshData mesh = entry.getValue().build();
			if (mesh != null) {
				if (layer == ChunkSectionLayer.TRANSLUCENT) results.transparencyState = mesh.sortQuads(builders.buffer(layer), vertexSorting);
				results.renderedLayers.put(layer, mesh);
			}
		}
		for (int l = 0; l < 2; l++) {
			int n = quads.layers[l].total();
			if (n == 0) continue;
			ChunkSectionLayer layer = l == 0 ? ChunkSectionLayer.SOLID : ChunkSectionLayer.CUTOUT;
			ByteBufferBuilder buffer = builders.buffer(layer);
			buffer.reserve(4);
			ByteBufferBuilder.Result placeholder = buffer.build();
			results.renderedLayers.put(layer, new MeshData(placeholder,
				new MeshData.DrawState(layer.vertexFormat(), n * 4, n * 6, PrimitiveTopology.QUADS, IndexType.least(n * 4))));
		}
		if (OwnTieGroups.ON) quads.groups = OwnTieGroups.build(quads, OwnTerrain.RUN);
		((OwnQuadHolder) (Object) results).mcopt$setQuads(quads);
		if (HIDDEN) countHidden(quads, region, sectionPos);
		if (OwnMergeStat.ON) OwnMergeStat.count(quads);
		return results;
	}

	static void mesh(boolean ambientOcclusion, boolean cutoutLeaves, BlockStateModelSet blockModelSet, FluidStateModelSet fluidModelSet,
		BlockColors blockColors, SectionPos sectionPos, RenderSectionRegion region, SectionBufferBuilderPack builders, SectionCompiler.Results results,
		OwnQuads quads, @Nullable Map<ChunkSectionLayer, BufferBuilder> startedLayers) {
		BlockPos minPos = sectionPos.origin();
		VisGraph visGraph = OwnVisGraph.ON ? null : new VisGraph();
		OwnVisGraph ownVis = OwnVisGraph.ON ? OwnVisGraph.get() : null;
		BlockModelLighter.enableCaching();
		ModelBlockRenderer blockRenderer = new ModelBlockRenderer(ambientOcclusion, true, blockColors);
		FluidRenderer fluidRenderer = new FluidRenderer(fluidModelSet);
		OwnRegion own = REGION ? OwnRegion.of(region, minPos) : null;
		BlockAndTintGetter level = own != null ? own : region;
		OwnBlockRenderer ownRenderer = own != null && OwnBlockRenderer.ON ? OwnBlockRenderer.get(ambientOcclusion, blockColors) : null;
		if (ownRenderer != null) ownRenderer.qrecSink = OwnQrec.ON ? quads : null;
		VertexConsumer[] translucent = new VertexConsumer[1];
		BlockQuadOutput quadOutput = (x, y, z, quad, instance) -> {
			ChunkSectionLayer layer = quad.materialInfo().layer();
			if (layer == ChunkSectionLayer.SOLID) quads.put(0, x, y, z, quad, instance);
			else if (layer == ChunkSectionLayer.CUTOUT) quads.put(1, x, y, z, quad, instance);
			else if (startedLayers != null) translucent(startedLayers, builders, translucent).putBlockBakedQuad(x, y, z, quad, instance);
		};
		BlockQuadOutput opaqueQuadOutput = (x, y, z, quad, instance) -> quads.put(0, x, y, z, quad, instance);
		FluidRenderer.Output fluidOutput = layer -> layer == ChunkSectionLayer.SOLID ? quads.fluid[0]
			: layer == ChunkSectionLayer.CUTOUT ? quads.fluid[1]
			: startedLayers != null ? translucent(startedLayers, builders, translucent) : Discard.INSTANCE;
		mcopt.metal.shade.terrain.ShadeFluidLight[] fluidLight = new mcopt.metal.shade.terrain.ShadeFluidLight[1];
		if (mcopt.metal.shade.terrain.ShadeFluidLight.ON && startedLayers != null) fluidOutput = mcopt.metal.shade.terrain.ShadeFluidLight.wrap(fluidOutput, level, minPos, fluidLight);
		OwnApi.Mesher api = OwnApi.mesher(ambientOcclusion, blockColors, quads, () -> startedLayers != null ? translucent(startedLayers, builders, translucent) : Discard.INSTANCE);
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		int minX = minPos.getX(), minY = minPos.getY(), minZ = minPos.getZ();
		for (int i = 0; i < 4096; i++) {
			pos.set(minX + (i & 15), minY + (i >> 4 & 15), minZ + (i >> 8));
			BlockState blockState = level.getBlockState(pos);
			if (OwnGridStat.ON || OwnQrStat.ON) OwnGridStat.block(blockState, quads);
			if (OwnMaterials.ON) OwnMaterials.block(pos.getX(), pos.getY(), pos.getZ(), blockState);
			if (!blockState.isAir()) {
				try {
					if (blockState.isSolidRender()) {
						if (ownVis != null) ownVis.setOpaque(i & 15, i >> 4 & 15, i >> 8);
						else visGraph.setOpaque(pos);
					}
					if (blockState.hasBlockEntity()) {
						BlockEntity blockEntity = region.getBlockEntity(pos);
						if (blockEntity != null && startedLayers != null) results.blockEntities.add(blockEntity);
					}
					FluidState fluidState = blockState.getFluidState();
					if (!fluidState.isEmpty()) {
						if (fluidLight[0] != null) fluidLight[0].water = fluidState.is(net.minecraft.tags.FluidTags.WATER);
						fluidRenderer.tesselate(level, pos, fluidOutput, blockState, fluidState);
					}
					if (blockState.getRenderShape() == RenderShape.MODEL && api != null) {
						api.block(SectionPos.sectionRelative(pos.getX()), SectionPos.sectionRelative(pos.getY()), SectionPos.sectionRelative(pos.getZ()), region, pos,
							blockState, blockModelSet.get(blockState), blockState.getSeed(pos));
					} else if (blockState.getRenderShape() == RenderShape.MODEL && ownRenderer != null) {
						ownRenderer.tesselateBlock(ModelBlockRenderer.forceOpaque(cutoutLeaves, blockState) ? opaqueQuadOutput : quadOutput,
							SectionPos.sectionRelative(pos.getX()), SectionPos.sectionRelative(pos.getY()), SectionPos.sectionRelative(pos.getZ()), own, pos,
							blockState, blockModelSet.get(blockState), blockState.getSeed(pos));
					} else if (blockState.getRenderShape() == RenderShape.MODEL) {
						blockRenderer.tesselateBlock(ModelBlockRenderer.forceOpaque(cutoutLeaves, blockState) ? opaqueQuadOutput : quadOutput,
							SectionPos.sectionRelative(pos.getX()), SectionPos.sectionRelative(pos.getY()), SectionPos.sectionRelative(pos.getZ()), level, pos,
							blockState, blockModelSet.get(blockState), blockState.getSeed(pos));
					}
				} catch (Throwable t) {
					CrashReport report = CrashReport.forThrowable(t, "Tesselating block in world");
					CrashReportCategory category = report.addCategory("Block being tesselated");
					CrashReportCategory.populateBlockDetails(category, region, pos, blockState);
					throw new ReportedException(report);
				}
			}
		}
		BlockModelLighter.clearCache();
		if (own != null) own.release();
		results.visibilitySet = ownVis != null ? ownVis.resolve() : visGraph.resolve();
	}

	private static VertexConsumer translucent(Map<ChunkSectionLayer, BufferBuilder> startedLayers, SectionBufferBuilderPack builders, VertexConsumer[] cache) {
		VertexConsumer c = cache[0];
		if (c == null) {
			BufferBuilder builder = new BufferBuilder(builders.buffer(ChunkSectionLayer.TRANSLUCENT), PrimitiveTopology.QUADS, ChunkSectionLayer.TRANSLUCENT.vertexFormat());
			startedLayers.put(ChunkSectionLayer.TRANSLUCENT, builder);
			cache[0] = c = builder;
		}
		return c;
	}

	private enum Discard implements VertexConsumer {
		INSTANCE;

		@Override
		public void addVertex(float x, float y, float z, int color, float u, float v, int overlayCoords, int lightCoords, float nx, float ny, float nz) {
		}

		@Override
		public VertexConsumer addVertex(float x, float y, float z) {
			return this;
		}

		@Override
		public VertexConsumer setColor(int r, int g, int b, int a) {
			return this;
		}

		@Override
		public VertexConsumer setColor(int color) {
			return this;
		}

		@Override
		public VertexConsumer setUv(float u, float v) {
			return this;
		}

		@Override
		public VertexConsumer setUv1(int u, int v) {
			return this;
		}

		@Override
		public VertexConsumer setUv2(int u, int v) {
			return this;
		}

		@Override
		public VertexConsumer setUv3(float u, float v) {
			return this;
		}

		@Override
		public VertexConsumer setNormal(float x, float y, float z) {
			return this;
		}

		@Override
		public VertexConsumer setLineWidth(float width) {
			return this;
		}
	}
}
