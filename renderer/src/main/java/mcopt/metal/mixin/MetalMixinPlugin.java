// Orchard's own file (see NOTICE): not part of mcopt.
package mcopt.metal.mixin;

import java.util.List;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * mcopt-metal.mixins.json had no plugin, as each of its mixins always applied: now they apply only when Switch has the
 * backend on, beside Sodium too. Besides the backend they hold only the frame limiter and flag-gated texture mixins.
 */
public final class MetalMixinPlugin implements IMixinConfigPlugin {
	static {
		mcopt.metal.Profile.apply(); // before any flag is read
	}

	@Override public void onLoad(String mixinPackage) { }
	@Override public String getRefMapperConfig() { return null; }
	@Override public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		return mcopt.metal.Switch.BACKEND;
	}
	@Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) { }
	@Override public List<String> getMixins() { return null; }
	@Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) { }
	@Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) { }
}
