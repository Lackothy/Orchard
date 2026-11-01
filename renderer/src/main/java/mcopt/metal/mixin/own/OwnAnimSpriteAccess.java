package mcopt.metal.mixin.own;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(TextureAtlasSprite.class)
public interface OwnAnimSpriteAccess {
	@Accessor("padding")
	int mcopt$padding();
}
