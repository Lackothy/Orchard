package mcopt.metal.mixin.cpu;

import mcopt.metal.cpu.Cpu;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(EntityRenderer.class)
abstract class LeashMixin {
	@Redirect(method = "shouldRender", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/world/entity/Leashable;getLeashHolder()Lnet/minecraft/world/entity/Entity;"))
	private Entity mcopt$leashHolder(Leashable leashable) {
		if (Cpu.leashActive && leashable instanceof Mob mob && mob.getLeashData() == null) return null;
		return leashable.getLeashHolder();
	}
}
