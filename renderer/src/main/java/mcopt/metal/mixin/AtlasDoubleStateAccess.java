package mcopt.metal.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "net.minecraft.client.renderer.texture.SpriteContents$AnimationState")
public interface AtlasDoubleStateAccess {
	@Accessor("isDirty")
	boolean mcopt$adDirty();

	@Accessor("isDirty")
	void mcopt$adSetDirty(boolean dirty);
}
