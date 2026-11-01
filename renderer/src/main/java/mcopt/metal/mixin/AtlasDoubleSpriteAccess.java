package mcopt.metal.mixin;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(TextureAtlasSprite.class)
public interface AtlasDoubleSpriteAccess {
	@Accessor("padding")
	int mcopt$adPadding();
}
