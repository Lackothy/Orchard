package mcopt.metal.own;

import net.minecraft.client.renderer.chunk.SectionCopy;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.jspecify.annotations.Nullable;

public interface OwnRegionAccess {
	SectionCopy[] mcopt$sections();

	@Nullable OwnRegion mcopt$own();

	void mcopt$own(@Nullable OwnRegion own);

	interface Copy {
		@Nullable PalettedContainer<BlockState> mcopt$section();

		boolean mcopt$debug();
	}
}
