// Modified for Russet: applies nothing when Switch has the renderer off.
package mcopt.metal.mixin.compat;

import java.util.List;
import java.util.Set;
import mcopt.metal.DhCompat;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

public final class CompatMixinPlugin implements IMixinConfigPlugin {
	static {
		mcopt.metal.Profile.apply();
	}

	@Override public void onLoad(String mixinPackage) { }
	@Override public String getRefMapperConfig() { return null; }

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		if (!mcopt.metal.Switch.ON) return false;
		if (mixinClassName.endsWith(".DhRenderApiMixin")) {
			if (!DhCompat.loaded()) return false;
			if (!Boolean.getBoolean("mcopt.shade")) return true;
			boolean apply = DhCompat.versionSupported() && Boolean.parseBoolean(System.getProperty("mcopt.metal", "true"));
			System.out.println("[mcopt] compat: Distant Horizons " + DhCompat.version() + " render-API mixin " + (apply ? "applied" : "not applied")
				+ (DhCompat.versionSupported() ? "" : " (tested: " + DhCompat.range() + ")"));
			return apply;
		}
		return false;
	}

	@Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) { }
	@Override public List<String> getMixins() { return null; }
	@Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) { }
	@Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) { }
}
