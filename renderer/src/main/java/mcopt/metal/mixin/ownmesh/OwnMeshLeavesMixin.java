package mcopt.metal.mixin.ownmesh;

import mcopt.metal.own.OwnLeaves;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelExtractor.class)
abstract class OwnMeshLeavesMixin {
	@Shadow
	@Final
	private Minecraft minecraft;

	@Shadow
	private ClientLevel level;

	@Shadow
	private void setSectionDirty(int x, int y, int z, boolean playerChanged) {
		throw new AssertionError();
	}

	@Inject(method = "setBlockDirty(Lnet/minecraft/core/BlockPos;Z)V", at = @At("TAIL"))
	private void mcopt$smartLeaves(BlockPos pos, boolean playerChanged, CallbackInfo ci) {
		int x = pos.getX(), y = pos.getY(), z = pos.getZ();
		if (!mcopt$edge(x) && !mcopt$edge(y) && !mcopt$edge(z) || this.level == null
			|| !OwnLeaves.besideLeaf(this.level, pos, this.minecraft.options.cutoutLeaves().get())) return;
		for (int sz = (z - 2) >> 4; sz <= (z + 2) >> 4; sz++) {
			for (int sy = (y - 2) >> 4; sy <= (y + 2) >> 4; sy++) {
				for (int sx = (x - 2) >> 4; sx <= (x + 2) >> 4; sx++) {
					boolean done = sx >= (x - 1) >> 4 && sx <= (x + 1) >> 4 && sy >= (y - 1) >> 4 && sy <= (y + 1) >> 4
						&& sz >= (z - 1) >> 4 && sz <= (z + 1) >> 4;
					if (!done) this.setSectionDirty(sx, sy, sz, playerChanged);
				}
			}
		}
	}

	@Unique
	private static boolean mcopt$edge(int c) {
		int i = c & 15;
		return i == 1 || i == 14;
	}
}
