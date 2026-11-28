// Modified for Russet: applies nothing when Switch has the renderer off, and the far terrain hooks wherever LodConfig.LIVE.
package mcopt.metal.mixin.lod;

import java.util.List;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

public final class LodMixinPlugin implements IMixinConfigPlugin {
	static {
		mcopt.metal.Profile.apply();
	}

	private static final boolean ENABLED = Boolean.getBoolean("mcopt.lod") || System.getProperty("mcopt.lod.radius") != null
		|| mcopt.metal.lod.LodConfig.LIVE;
	private static final boolean TRUTH = System.getProperty("mcopt.lod.truthRd") != null;
	private static final boolean FADE_INSTANT = "instant".equals(System.getProperty("mcopt.lod.fade", ""));

	@Override
	public void onLoad(String mixinPackage) {
	}

	@Override
	public String getRefMapperConfig() {
		return null;
	}

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		if (!mcopt.metal.Switch.ON) return false;
		if (mixinClassName.endsWith(".LodSodiumFadeMixin")) return ENABLED && FADE_INSTANT && net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("sodium");
		return mixinClassName.contains(".LodTruth") ? TRUTH : ENABLED;
	}

	@Override
	public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
	}

	@Override
	public List<String> getMixins() {
		return null;
	}

	@Override
	public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}

	@Override
	public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}
}
