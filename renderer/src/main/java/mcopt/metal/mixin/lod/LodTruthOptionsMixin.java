package mcopt.metal.mixin.lod;

import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.Slice;

@Mixin(Options.class)
abstract class LodTruthOptionsMixin {
	@ModifyConstant(method = "<init>", constant = @Constant(intValue = 32), require = 2,
		slice = @Slice(from = @At(value = "CONSTANT", args = "stringValue=options.renderDistance")))
	private int mcopt$truthRange(int max) {
		return Math.max(max, Integer.getInteger("mcopt.lod.truthRd", max));
	}
}
