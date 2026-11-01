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
package mcopt.metal.shade.weather;

import mcopt.metal.shade.Live;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.level.biome.Biome;

public final class WeatherFrame {
	private static long lastPrecipCheck;
	private static Biome.Precipitation precipitation = Biome.Precipitation.NONE;
	private static float line = Float.NaN;
	private static final float LINE_REACH = 256f;
	private static float afterglow;
	private static final float[] glowDir = {0f, 1f, 0f};

	private WeatherFrame() {
	}

	public static void update(ClientLevel level, double camX, double camY, double camZ, float rainLevel, float thunder, boolean overworld, float dt,
	                          float dayIndex, float seconds, boolean snap) {
		if (!ShadeWeather.ON) return;
		long now = System.nanoTime();
		if (level == null || !overworld) {
			precipitation = Biome.Precipitation.NONE;
			line = Float.NaN;
		} else if (snap || now - lastPrecipCheck > 500_000_000L) {
			lastPrecipCheck = now;
			try {
				BlockPos pos = BlockPos.containing(camX, camY, camZ);
				precipitation = level.getPrecipitationAt(pos);
				line = snowLine(level.getBiome(pos).value(), level.getSeaLevel());
			} catch (RuntimeException e) {
				precipitation = Biome.Precipitation.NONE;
				line = Float.NaN;
			}
		}
		float r = overworld ? rainLevel : 0f;
		float rainAmount = Math.min(1f, r * (1f + 0.25f * thunder));
		float rainTarget, snowTarget;
		if (ShadeWeather.snowLine() && !Float.isNaN(line)) {
			boolean falling = precipitation != Biome.Precipitation.NONE;
			rainTarget = falling && line > camY - LINE_REACH ? rainAmount : 0f;
			snowTarget = falling && line < camY + LINE_REACH && (line <= -1e4f || ShadeWeather.SNOW_LINE_COVER) ? r : 0f;
		} else {
			rainTarget = precipitation == Biome.Precipitation.RAIN ? rainAmount : 0f;
			snowTarget = precipitation == Biome.Precipitation.SNOW ? r : 0f;
		}
		ShadeWeather.step(dt, rainTarget, snowTarget, precipitation == Biome.Precipitation.SNOW, line, (float) camY, overworld ? thunder : 0f,
			dayIndex, seconds, snap);
		lightning(level, camX, camY, camZ, overworld, dt, seconds);
	}

	static float snowLine(Biome b, int seaLevel) {
		if (!b.hasPrecipitation()) return 1e4f;
		float t = b.getBaseTemperature();
		return t < 0.15f ? -1e4f : seaLevel + 17 + (t - 0.15f) * 800f;
	}

	private static void lightning(ClientLevel level, double camX, double camY, double camZ, boolean overworld, float dt, float seconds) {
		float best = 0f;
		double bx = 0, by = 1, bz = 0;
		Minecraft mc = Minecraft.getInstance();
		boolean hidden = mc.options != null && mc.options.hideLightningFlash().get();
		if (ShadeWeather.lightning() && level != null && overworld && !hidden) {
			for (Entity e : level.entitiesForRendering()) {
				if (!(e instanceof LightningBolt bolt)) continue;
				double dx = bolt.getX() - camX, dy = bolt.getY() + 40.0 - camY, dz = bolt.getZ() - camZ;
				double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
				if (d > 256.0) continue;
				float age = bolt.tickCount + mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
				double n = Math.sin((bolt.seed & 1023) * 0.37 + age * 7.3) * Math.sin(age * 3.1 + (bolt.seed >> 10 & 255));
				float stroke = (age < 2.5f ? 1.0f : 0.45f + 0.55f * (float) Math.abs(n));
				float w = stroke / (1f + (float) (d * d) / (110f * 110f));
				if (w > best) {
					best = w;
					bx = dx / d;
					by = dy / d;
					bz = dz / d;
				}
			}
		}
		if (best > 0f) {
			afterglow = Math.max(afterglow * 0.5f, best);
			glowDir[0] = (float) bx;
			glowDir[1] = (float) by;
			glowDir[2] = (float) bz;
		} else {
			afterglow *= (float) Math.exp(-dt / 0.12f);
			if (afterglow < 0.01f) afterglow = 0f;
		}
		ShadeWeather.flashDir[0] = glowDir[0];
		ShadeWeather.flashDir[1] = glowDir[1];
		ShadeWeather.flashDir[2] = glowDir[2];
		ShadeWeather.flash = afterglow * Live.f("mcopt.shade.flashLight", FLASH_LIGHT, 0f, 10f);
	}

	private static final float FLASH_LIGHT = ShadeWeather.floatFlag("mcopt.shade.flashLight", 5f);
}
