// Modified for Russet: applies nothing when Switch has the renderer off.
// Copyright 2026 mcopt-shade contributors. Licensed under the Apache License, Version 2.0 (LICENSE).
package mcopt.metal.shade.shadow.mixin;

import java.util.List;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

public final class ShadowMixinPlugin implements IMixinConfigPlugin {
	static {
		mcopt.metal.Profile.apply();
	}

	private static final boolean ENABLED = Boolean.getBoolean("mcopt.shade")
		&& Boolean.parseBoolean(System.getProperty("mcopt.shade.shadows", "true")) && Boolean.parseBoolean(System.getProperty("mcopt.metal", "true"));

	private static final boolean TERRAIN_CASTERS = true;

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
		return ENABLED && TERRAIN_CASTERS;
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
