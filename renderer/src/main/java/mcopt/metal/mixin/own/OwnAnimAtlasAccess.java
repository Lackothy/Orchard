package mcopt.metal.mixin.own;

import java.util.List;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(TextureAtlas.class)
public interface OwnAnimAtlasAccess {
	@Accessor("sprites")
	List<TextureAtlasSprite> mcopt$sprites();

	@Accessor("animatedTexturesStates")
	List<SpriteContents.AnimationState> mcopt$animationStates();

	@Accessor("maxMipLevel")
	int mcopt$maxMipLevel();

	@Accessor("width")
	int mcopt$width();

	@Accessor("height")
	int mcopt$height();

	@org.spongepowered.asm.mixin.gen.Invoker("uploadAnimationFrames")
	void mcopt$uploadAnimationFrames();
}
