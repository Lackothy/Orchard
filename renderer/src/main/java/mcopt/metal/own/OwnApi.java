// Modified for Russet: the mesher is told whether smart leaves (OwnLeaves) apply.
package mcopt.metal.own;

import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.function.Supplier;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

public final class OwnApi {
	public static final boolean FRAPI = FabricLoader.getInstance().isModLoaded("fabric-renderer-api-v1") && !"false".equals(System.getProperty("mcopt.own.api"));

	private OwnApi() {
	}

	public interface Mesher {
		void block(float x, float y, float z, BlockAndTintGetter level, BlockPos pos, BlockState state, BlockStateModel model, long seed);
	}

	public static @Nullable Mesher mesher(boolean ambientOcclusion, boolean smartLeaves, BlockColors blockColors, OwnQuads quads,
		Supplier<VertexConsumer> translucent) {
		return FRAPI ? OwnFrapi.get(ambientOcclusion, smartLeaves, blockColors, quads, translucent) : null;
	}
}
