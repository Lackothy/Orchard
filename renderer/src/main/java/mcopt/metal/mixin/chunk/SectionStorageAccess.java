package mcopt.metal.mixin.chunk;

import java.util.Optional;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.storage.SectionStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(SectionStorage.class)
public interface SectionStorageAccess {
	@Invoker("getOrLoad")
	Optional<?> mcopt$getOrLoad(long sectionPos);

	@Accessor("levelHeightAccessor")
	LevelHeightAccessor mcopt$levelHeightAccessor();
}
