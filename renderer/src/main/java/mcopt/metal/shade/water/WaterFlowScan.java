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
package mcopt.metal.shade.water;

import java.util.function.Predicate;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.material.Fluids;

public final class WaterFlowScan {
	static final int RANGE = 6;
	private static final int SIDE = 2 * RANGE + 1;
	private static final int COLUMNS_PER_FRAME = 24;
	private static final long HOLD_NANOS = 2_000_000_000L;
	private static final Predicate<BlockState> MOVING = s -> s.getFluidState().getType() == Fluids.FLOWING_WATER;

	private static int cursor;
	private static boolean seenThisPass;
	private static long lastSeen = Long.MIN_VALUE / 2;
	private static ClientLevel lastLevel;

	private WaterFlowScan() {
	}

	static void update(ClientLevel level, double x, double y, double z) {
		if (level == null) {
			lastLevel = null;
			return;
		}
		if (level != lastLevel) {
			lastLevel = level;
			cursor = 0;
			seenThisPass = false;
			lastSeen = Long.MIN_VALUE / 2;
		}
		int csx = (int) Math.floor(x) >> 4, csy = (int) Math.floor(y) >> 4, csz = (int) Math.floor(z) >> 4;
		int minSy = level.getMinSectionY(), count = level.getSectionsCount();
		for (int n = 0; n < COLUMNS_PER_FRAME; n++) {
			int sx = csx - RANGE + cursor % SIDE, sz = csz - RANGE + cursor / SIDE;
			LevelChunk chunk = level.getChunkSource().getChunk(sx, sz, ChunkStatus.FULL, false);
			if (chunk != null) {
				for (int sy = csy - RANGE; sy <= csy + RANGE && !seenThisPass; sy++) {
					int index = sy - minSy;
					if (index < 0 || index >= count) continue;
					LevelChunkSection section = chunk.getSection(index);
					if (!section.hasOnlyAir() && section.getStates().maybeHas(MOVING)) seenThisPass = true;
				}
			}
			if (++cursor == SIDE * SIDE) {
				if (seenThisPass) lastSeen = System.nanoTime();
				cursor = 0;
				seenThisPass = false;
			}
		}
		if (seenThisPass) lastSeen = System.nanoTime();
		boolean now = near();
		if (now != logged) {
			logged = now;
			System.out.println("[mcopt] shade: moving water near the camera: " + now);
		}
	}

	private static boolean logged;

	public static boolean near() {
		return lastLevel != null && System.nanoTime() - lastSeen < HOLD_NANOS;
	}
}
