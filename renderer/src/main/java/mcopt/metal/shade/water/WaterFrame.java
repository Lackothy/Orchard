/*
 * Copyright 2026 mcopt-shade contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package mcopt.metal.shade.water;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

public final class WaterFrame {
	private static final float[] STILL = new float[4], FLOW = new float[4], NONE = new float[4];

	private WaterFrame() {
	}

	public static void update(long ctx, ClientLevel level, double camX, double camY, double camZ, boolean underwater, float wetness) {
		boolean sprites = sprites();
		WaterFlowScan.update(level, camX, camY, camZ);
		float surface = underwater && level != null ? surfaceAbove(level, camX, camY, camZ) : 0f;
		ShadeWater.frame(ctx, sprites ? STILL : NONE, sprites ? FLOW : NONE, surface, wetness);
	}

	public static void rects(float[] out) {
		System.arraycopy(STILL, 0, out, 0, 4);
		System.arraycopy(FLOW, 0, out, 4, 4);
	}

	private static boolean sprites() {
		try {
			FluidModel model = Minecraft.getInstance().getModelManager().getFluidStateModelSet().get(Fluids.WATER.defaultFluidState());
			rect(model.stillMaterial().sprite(), STILL);
			rect(model.flowingMaterial().sprite(), FLOW);
			return true;
		} catch (RuntimeException e) {
			return false;
		}
	}

	private static void rect(TextureAtlasSprite s, float[] out) {
		out[0] = s.getU0();
		out[1] = s.getV0();
		out[2] = s.getU1();
		out[3] = s.getV1();
	}

	private static float surfaceAbove(ClientLevel level, double x, double y, double z) {
		BlockPos.MutableBlockPos pos = BlockPos.containing(x, y, z).mutable();
		float top = (float) y;
		for (int i = 0; i < 32; i++) {
			FluidState fluid = level.getFluidState(pos);
			if (!fluid.getType().isSame(Fluids.WATER)) break;
			top = pos.getY() + fluid.getHeight(level, pos);
			pos.move(0, 1, 0);
		}
		return (float) (top - y);
	}
}
