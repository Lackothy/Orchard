/*
 * Copyright 2026 mcopt-shade contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package mcopt.metal.shade.shadow;

import java.util.Map;
import mcopt.metal.MetalDrawTap;

public final class EntityCasters {
	public static final boolean ON = mcopt.metal.shade.Shade.SHADOWS && mcopt.metal.shade.ShadeConfig.HDR
		&& Boolean.parseBoolean(System.getProperty("mcopt.shade.entityShadows", "true"));

	static final Map<String, Float> CASTERS = Map.ofEntries(
		Map.entry("minecraft:pipeline/entity_solid", 0.1f), Map.entry("minecraft:pipeline/entity_solid_offset_forward", 0.1f),
		Map.entry("minecraft:pipeline/entity_solid_glint", 0.1f),
		Map.entry("minecraft:pipeline/entity_cutout", 0.1f), Map.entry("minecraft:pipeline/entity_cutout_cull", 0.1f),
		Map.entry("minecraft:pipeline/entity_cutout_z_offset", 0.1f), Map.entry("minecraft:pipeline/entity_cutout_dissolve", 0.1f),
		Map.entry("minecraft:pipeline/entity_translucent", 0.5f), Map.entry("minecraft:pipeline/entity_translucent_cull", 0.5f),
		Map.entry("minecraft:pipeline/armor_cutout_no_cull", 0.1f), Map.entry("minecraft:pipeline/armor_cutout_no_cull_glint", 0.1f),
		Map.entry("minecraft:pipeline/item_cutout", 0.1f), Map.entry("minecraft:pipeline/item_translucent", 0.5f),
		Map.entry("minecraft:pipeline/item_translucent_glint", 0.5f),
		Map.entry("minecraft:pipeline/solid_block", 0.1f), Map.entry("minecraft:pipeline/cutout_block", 0.5f));

	private static long shadow;
	private static int recorded, dropped;
	private static boolean active;
	private static boolean paused;
	private static boolean installed;

	private EntityCasters() {
	}

	private static final MetalDrawTap.Listener TAP = (pipeline, vertexBuffer, firstVertex, quads, stride, uvOffset, texture) -> {
		Float cutoff = CASTERS.get(pipeline);
		long s = shadow;
		if (!active || paused || cutoff == null || s == 0 || quads <= 0 || texture == 0) return;
		if (ShadowNative.entityRecord(s, vertexBuffer, firstVertex, quads, stride, uvOffset, texture, cutoff) == 0) recorded++;
		else dropped++;
	};

	static void attach(long nativeShadow) {
		if (!ON || nativeShadow == 0) return;
		try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
			long err = stack.ncalloc(1, 1024, 1);
			if (ShadowNative.entityInit(nativeShadow, err, 1024) != 0) {
				System.out.println("[mcopt] shade: entity shadows off (" + org.lwjgl.system.MemoryUtil.memUTF8(err) + "); terrain shadows kept");
				return;
			}
		}
		shadow = nativeShadow;
		if (!installed) {
			installed = true;
			MetalDrawTap.listener = TAP;
		}
	}

	static void detach() {
		shadow = 0;
		if (installed) {
			installed = false;
			MetalDrawTap.listener = null;
		}
	}

	static void beginFrame(ShadowPass p) {
		paused = Boolean.getBoolean("mcopt.shade.entityShadows.pause");
		if (shadow != 0 && recorded + dropped > 0) ShadowNative.entityReset(shadow);
		recorded = dropped = 0;
		boolean was = active;
		active = shadow != 0 && p.nearThisFrame();
		if (was && !active && shadow != 0) {
			fresh = false;
			ShadowNative.entityShift(shadow, 0f, 0f, 0f, 1);
		}
	}

	public static boolean dropBlob(net.minecraft.world.level.Level level, net.minecraft.core.BlockPos pos) {
		boolean end = mcopt.metal.shade.dims.ShadeDims.endDropsBlob();
		if (!LOG) {
			return shadow != 0 && !paused && ShadowPass.enabled() && !ShadowPass.lightIsMoon()
				&& (end || level.getBrightness(net.minecraft.world.level.LightLayer.SKY, pos) >= BLOB_SKY);
		}
		if (shadow == 0 || paused || !ShadowPass.enabled()) {
			blobKeptNoMap++;
			return false;
		}
		if (ShadowPass.lightIsMoon()) {
			blobKeptMoon++;
			return false;
		}
		int sky = level.getBrightness(net.minecraft.world.level.LightLayer.SKY, pos);
		if (!end && sky < BLOB_SKY) {
			blobKeptDark++;
			blobDarkSkyMax = Math.max(blobDarkSkyMax, sky);
			return false;
		}
		blobDropped++;
		return true;
	}

	private static int blobDropped, blobKeptNoMap, blobKeptMoon, blobKeptDark, blobDarkSkyMax;

	private static final int BLOB_SKY = 5;

	private static double camX, camY, camZ;
	private static boolean fresh;
	private static final boolean LOG = Boolean.getBoolean("mcopt.shade.entityShadows.log");
	private static long lastLog;
	private static int lastRecorded, lastDropped;

	static void prepareCast(long frame, double x, double y, double z) {
		if (shadow == 0) return;
		boolean stale = !fresh || paused;
		fresh = false;
		double dx = camX - x, dy = camY - y, dz = camZ - z;
		if (dx * dx + dy * dy + dz * dz > 64 * 64) stale = true;
		ShadowNative.entityShift(shadow, (float) dx, (float) dy, (float) dz, stale ? 1 : 0);
		if (LOG && System.nanoTime() - lastLog > 4_000_000_000L) {
			lastLog = System.nanoTime();
			System.out.println("[mcopt] shade: entity casters: " + ShadowNative.entityCount(shadow) + " draws cast this frame" + (stale ? " (stale: dropped)" : "")
				+ ", last frame recorded " + lastRecorded + ", dropped " + lastDropped + ", shift " + String.format("%.3f %.3f %.3f", dx, dy, dz)
				+ "; blob pieces since last line: dropped " + blobDropped + ", kept (no map " + blobKeptNoMap + ", moon " + blobKeptMoon + ", sky light < "
				+ BLOB_SKY + " " + blobKeptDark + (blobKeptDark > 0 ? ", max " + blobDarkSkyMax : "") + ")");
			blobDropped = blobKeptNoMap = blobKeptMoon = blobKeptDark = blobDarkSkyMax = 0;
		}
	}

	public static void commit(long enc) {
		ShadowPass p = ShadowPass.instance();
		if (shadow == 0 || p == null || !active) return;
		ShadowNative.entityCommit(enc, shadow);
		camX = p.camX();
		camY = p.camY();
		camZ = p.camZ();
		fresh = true;
		lastRecorded = recorded;
		lastDropped = dropped;
		recorded = dropped = 0;
	}

	public static int count() {
		return shadow == 0 ? 0 : ShadowNative.entityCount(shadow);
	}
}
