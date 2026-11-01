package mcopt.metal.own;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ColorResolver;
import org.jspecify.annotations.Nullable;

public final class OwnTintHook {
	private OwnTintHook() {
	}

	public static @Nullable Integer blend(ClientLevel level, BlockPos pos, ColorResolver resolver) {
		OwnRegion r = OwnRegion.current();
		if (r == null) return null;
		int dist = Minecraft.getInstance().options.biomeBlendRadius().get();
		Integer v = dist == 0 ? null : r.blendTint(level, pos, resolver, dist);
		if (v != null && OwnMesher.VERIFY) {
			OwnRegion.suspend(true);
			int ref;
			try {
				ref = level.calculateBlockTint(pos, resolver);
			} finally {
				OwnRegion.suspend(false);
			}
			CHECKED.increment();
			if (ref != v) {
				BAD.increment();
				if (BAD.sum() <= 5) System.out.println("mcopt-own mesh verify: TINT MISMATCH " + pos + " ours " + Integer.toHexString(v) + " vanilla " + Integer.toHexString(ref));
			}
		}
		return v;
	}

	private static final java.util.concurrent.atomic.LongAdder CHECKED = new java.util.concurrent.atomic.LongAdder(), BAD = new java.util.concurrent.atomic.LongAdder();

	static void log() {
		if (OwnRegion.TINT) System.out.println("mcopt-own mesh verify: tint blends checked " + CHECKED.sum() + ", mismatched " + BAD.sum());
	}
}
