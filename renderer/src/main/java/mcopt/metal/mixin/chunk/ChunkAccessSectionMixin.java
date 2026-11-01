package mcopt.metal.mixin.chunk;

import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChunkAccess.class)
abstract class ChunkAccessSectionMixin implements LevelHeightAccessor {
	@Shadow @Final protected LevelHeightAccessor levelHeightAccessor;
	@Unique private int mcopt$minSectionY;
	@Unique private boolean mcopt$minSet;

	@Inject(method = "<init>", at = @At("RETURN"))
	private void mcopt$cacheMinSection(CallbackInfo ci) {
		this.mcopt$minSectionY = SectionPos.blockToSectionCoord(this.levelHeightAccessor.getMinY());
		this.mcopt$minSet = true;
	}

	@Override
	public int getSectionIndex(int blockY) {
		return SectionPos.blockToSectionCoord(blockY) - (this.mcopt$minSet ? this.mcopt$minSectionY : this.getMinSectionY());
	}
}
