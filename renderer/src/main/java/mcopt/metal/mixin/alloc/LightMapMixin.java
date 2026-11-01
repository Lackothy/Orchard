package mcopt.metal.mixin.alloc;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import mcopt.metal.alloc.CowLong2ObjectMap;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.lighting.DataLayerStorageMap;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(DataLayerStorageMap.class)
abstract class LightMapMixin {
	@Shadow @Final @Mutable protected Long2ObjectOpenHashMap<DataLayer> map;

	@Inject(method = "<init>", at = @At("RETURN"))
	private void mcopt$cow(Long2ObjectOpenHashMap<DataLayer> given, CallbackInfo ci) {
		if (!(map instanceof CowLong2ObjectMap)) map = CowLong2ObjectMap.of(map);
	}
}
