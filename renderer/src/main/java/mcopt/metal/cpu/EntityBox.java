package mcopt.metal.cpu;

import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;

public final class EntityBox {
	public static final boolean AB = "ab".equals(System.getProperty("mcopt.cpu.entityBox"));
	public static final boolean ON = Boolean.getBoolean("mcopt.cpu.entityBox") || AB;
	public static boolean active = true;
	private static EntityRenderer<?, ?> renderer;
	private static Entity entity;
	private static float partialTicks;
	private static AABB box;
	private static Thread owner;

	private EntityBox() {
	}

	public static void clear() {
		box = null;
		entity = null;
		renderer = null;
	}

	public static void put(EntityRenderer<?, ?> r, Entity e, float pt, AABB b) {
		if (!active) return;
		renderer = r;
		entity = e;
		partialTicks = pt;
		box = b;
		owner = Thread.currentThread();
	}

	public static AABB take(EntityRenderer<?, ?> r, Entity e, float pt) {
		AABB b = box;
		boolean hit = b != null && renderer == r && entity == e && Float.floatToRawIntBits(partialTicks) == Float.floatToRawIntBits(pt) && owner == Thread.currentThread();
		clear();
		return hit ? b : null;
	}
}
