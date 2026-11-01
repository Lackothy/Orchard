package mcopt.metal.mixin.chunkio;

import java.util.List;
import java.util.Set;
import mcopt.metal.chunkio.ChunkIo;
import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

public final class ChunkIoMixinPlugin implements IMixinConfigPlugin {
	static {
		try {
			Class.forName("mcopt.metal.Profile").getMethod("apply").invoke(null);
		} catch (ClassNotFoundException e) {
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("mcopt: profile could not be applied", e);
		}
	}

	@Override public void onLoad(String mixinPackage) { }
	@Override public String getRefMapperConfig() { return null; }

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		if (!mcopt.metal.Switch.ON) return false;
		String name = mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1);
		if (!ChunkIo.mixinEnabled(name)) return false;
		if (FabricLoader.getInstance().isModLoaded("c2me")) {
			System.out.println("mcopt-chunk: " + name + " not applied: c2me is loaded");
			return false;
		}
		return true;
	}

	@Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) { }
	@Override public List<String> getMixins() { return null; }
	@Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) { }
	@Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) { }
}
