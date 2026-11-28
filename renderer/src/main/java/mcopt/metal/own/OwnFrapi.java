// Modified for Russet: smart leaves (OwnLeaves) choose where a leaf block's quads go.
package mcopt.metal.own;

import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.client.renderer.v1.Renderer;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.MutableQuadView;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.client.renderer.v1.render.AltModelBlockRenderer;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

final class OwnFrapi implements OwnApi.Mesher {
	private static final ThreadLocal<OwnFrapi> LOCAL = ThreadLocal.withInitial(OwnFrapi::new);

	private final QuadEmitter emitter = Renderer.get().quadEmitter(this::emitted);
	private @Nullable AltModelBlockRenderer blocks;
	private @Nullable OwnQuads quads;
	private @Nullable Supplier<VertexConsumer> translucent;
	private @Nullable VertexConsumer translucentSink;
	private final float[] p = new float[12], uv = new float[8];
	private final int[] abgr = new int[4], light = new int[4];
	private boolean smartLeaves, leaf;
	private @Nullable BlockAndTintGetter level;
	private @Nullable BlockPos pos;
	private final BlockPos.MutableBlockPos near = new BlockPos.MutableBlockPos();

	private OwnFrapi() {
	}

	static OwnApi.Mesher get(boolean ambientOcclusion, boolean smartLeaves, BlockColors blockColors, OwnQuads quads, Supplier<VertexConsumer> translucent) {
		OwnFrapi f = LOCAL.get();
		f.smartLeaves = smartLeaves;
		f.blocks = Renderer.get().altModelBlockRenderer(ambientOcclusion, true, blockColors);
		f.quads = quads;
		f.translucent = translucent;
		f.translucentSink = null;
		return f;
	}

	@Override
	public void block(float x, float y, float z, BlockAndTintGetter level, BlockPos pos, BlockState state, BlockStateModel model, long seed) {
		this.leaf = this.smartLeaves && OwnLeaves.leaf(state);
		this.level = level;
		this.pos = pos;
		this.blocks.tesselateBlock(this.emitter, x, y, z, level, pos, state, model, seed);
	}

	private void emitted(MutableQuadView quad) {
		ChunkSectionLayer layer = quad.chunkLayer();
		if (layer == ChunkSectionLayer.SOLID || layer == ChunkSectionLayer.CUTOUT) {
			int side = this.leaf ? OwnLeaves.side(this.level, this.pos, quad.cullFace(), this.near) : OwnLeaves.AS_IS;
			if (side == OwnLeaves.HIDDEN) return;
			float[] p = this.p, uv = this.uv;
			int[] abgr = this.abgr, light = this.light;
			for (int v = 0; v < 4; v++) {
				p[v * 3] = quad.x(v);
				p[v * 3 + 1] = quad.y(v);
				p[v * 3 + 2] = quad.z(v);
				uv[v * 2] = quad.u(v);
				uv[v * 2 + 1] = quad.v(v);
				int c = quad.color(v);
				abgr[v] = c & 0xFF00FF00 | (c & 0xFF0000) >> 16 | (c & 0xFF) << 16;
				light[v] = quad.lightmap(v);
			}
			this.quads.layers[layer == ChunkSectionLayer.SOLID || side == OwnLeaves.OPAQUE ? 0 : 1].quad(p, abgr, uv, light);
			return;
		}
		VertexConsumer sink = this.translucentSink;
		if (sink == null) sink = this.translucentSink = this.translucent.get();
		quad.buffer(OverlayTexture.NO_OVERLAY, sink);
	}
}
