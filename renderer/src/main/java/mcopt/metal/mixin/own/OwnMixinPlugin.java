package mcopt.metal.mixin.own;

import java.util.List;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

public final class OwnMixinPlugin implements IMixinConfigPlugin {
	static {
		mcopt.metal.Profile.apply();
	}

	private static final boolean ENABLED = Boolean.getBoolean("mcopt.own") && !FabricLoader.getInstance().isModLoaded("sodium");

	@Override
	public void onLoad(String mixinPackage) {
		if (Boolean.getBoolean("mcopt.own") && !ENABLED) System.out.println("mcopt-own: Sodium is loaded, our near terrain stays off");
	}

	@Override
	public String getRefMapperConfig() {
		return null;
	}

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		if (!mcopt.metal.Switch.ON) return false;
		if (mixinClassName.endsWith(".OwnAnimFlushMixin") || mixinClassName.endsWith(".OwnAnimFlushFrameMixin")) return ENABLED && Boolean.getBoolean("mcopt.own.int.animOnePass");
		if (mixinClassName.endsWith(".OwnAtlasWriteMixin")) return ENABLED && Boolean.getBoolean("mcopt.own.int.atlasWrite");
		if (mixinClassName.endsWith(".OwnAnimCopyMixin")) return ENABLED && (Boolean.getBoolean("mcopt.own.int.animCopy") || Integer.getInteger("mcopt.own.int.animHash", 0) > 0 || Integer.getInteger("mcopt.own.int.animVerify", 0) > 0 || Integer.getInteger("mcopt.own.int.animVerifyCopy", 0) > 0 || Boolean.getBoolean("mcopt.own.int.animOnePass"));
		if (mixinClassName.endsWith(".FrameLogNoAnimMixin")) return ENABLED && Boolean.getBoolean("mcopt.own.int.noAnim");
		if (mixinClassName.contains(".FrameLog")) return ENABLED && Boolean.getBoolean("mcopt.own.int.frameLog");
		return ENABLED;
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
