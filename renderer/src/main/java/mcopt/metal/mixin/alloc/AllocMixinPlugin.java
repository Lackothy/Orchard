package mcopt.metal.mixin.alloc;

import java.util.List;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

public final class AllocMixinPlugin implements IMixinConfigPlugin {
	static {
		mcopt.metal.Profile.apply();
	}

	@Override public void onLoad(String mixinPackage) { }
	@Override public String getRefMapperConfig() { return null; }
	@Override public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		if (!mcopt.metal.Switch.ON) return false;
		if (mixinClassName.endsWith("LightMapMixin")) return Boolean.getBoolean("mcopt.alloc.lightMap");
		if (mixinClassName.endsWith("PalettePackingMixin")) {
			if (!Boolean.parseBoolean(System.getProperty("mcopt.alloc.palettePacking", "true"))) return false;
			if (FabricLoader.getInstance().isModLoaded("lithium")) {
				System.out.println("mcopt-alloc: PalettePackingMixin not applied: lithium is loaded");
				return false;
			}
			return true;
		}
		return false;
	}
	@Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) { }
	@Override public List<String> getMixins() { return null; }
	@Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) { }
	@Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) { }
}
