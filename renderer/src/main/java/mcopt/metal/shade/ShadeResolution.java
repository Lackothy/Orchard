/*
 * Copyright 2026 mcopt-shade contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the
 * License. You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0. Unless required by applicable
 * law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES
 * OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package mcopt.metal.shade;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class ShadeResolution {
	private static final int[] CHOICES = {0, -1, 85, 75, 67, 59, 50};
	public static final String[] VALUES = {"auto", "native", "85%", "75%", "67%", "59%", "50%"};
	public static final float MIN_SCALE = 0.5f;
	private static volatile String parsedText;
	private static volatile int parsed;

	private ShadeResolution() {
	}

	private static int parse(String v) {
		String s = v.trim().toLowerCase(Locale.ROOT);
		if (s.equals("native") || s.equals("nativa")) return -1;
		boolean percent = s.endsWith("%");
		s = s.replace("%", "").replace("p", "").trim();
		try {
			int n = Integer.parseInt(s);
			if (n <= 0) return 0;
			if (percent || n <= 100) return Math.max(Math.round(MIN_SCALE * 100f), Math.min(n, 100));
			return n;
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	private static int choice() {
		String v = System.getProperty("mcopt.shade.resolution", "auto");
		if (v != parsedText) {
			parsed = parse(v);
			parsedText = v;
		}
		return parsed;
	}

	public static float scale(int outHeight) {
		int c = choice();
		if (c == 0 || outHeight <= 0) return 0f;
		if (c < 0) return 1f;
		if (c <= 100) return Math.clamp(c / 100f, MIN_SCALE, 1f);
		return Math.clamp((float) c / outHeight, MIN_SCALE, 1f);
	}

	public static boolean fixed() {
		return choice() != 0;
	}

	public static int[] worldSize() {
		mcopt.metal.shade.taa.ShadeTaa taa = mcopt.metal.shade.taa.ShadeTaa.current();
		if (taa == null || taa.outWidth() <= 0) return null;
		float s = taa.scale();
		return new int[] {Math.round(taa.outWidth() * s), Math.round(taa.outHeight() * s)};
	}

	private static String name(int c) {
		return c == 0 ? "Auto" : c < 0 ? ShadeLang.t("Nativa", "Native") : c <= 100 ? c + "%" : c + "p";
	}

	public static String debugLine() {
		String preset = mcopt.metal.shade.taa.TaaConfig.PRESET.name().toLowerCase(Locale.ROOT);
		int[] size = worldSize();
		mcopt.metal.shade.taa.ShadeTaa taa = mcopt.metal.shade.taa.ShadeTaa.current();
		if (size == null || taa == null) {
			return "Shaders: mundo a la resolución de la ventana (sin TAAU) · " + preset;
		}
		var o = taa.outputTarget();
		String out = o == null ? "" : ShadeOutput.debugPart(taa.outWidth(), taa.outHeight(), o.width, o.height);
		return String.format(Locale.ROOT, "Shaders: mundo %dx%d (%d %%)%s · %s · %s", size[0], size[1], Math.round(taa.scale() * 100f), out, name(choice()), preset);
	}

	public static String label(int outWidth, int outHeight) {
		int c = choice();
		int[] size = worldSize();
		mcopt.metal.shade.taa.ShadeTaa taa = mcopt.metal.shade.taa.ShadeTaa.current();
		if (c == 0) {
			String auto = ShadeLang.t("Resolución: Auto", "Resolution: Auto");
			return size == null || taa == null || !taa.lastAuto() ? auto + ShadeLang.t(" (dinámica)", " (dynamic)") : auto + " (" + size[1] + "p)";
		}
		if (c < 0) return ShadeLang.t("Resolución: Nativa", "Resolution: Native");
		if (c > 100) return ShadeLang.t("Resolución: ", "Resolution: ") + c + "p";
		int oh = taa != null && taa.outHeight() > 0 ? taa.outHeight() : outHeight;
		return ShadeLang.t("Resolución: ", "Resolution: ") + c + "%" + (oh > 0 ? " (" + Math.round(oh * scale(oh)) + "p)" : "");
	}

	public static String tooltip() {
		return ShadeLang.t("Resolución interna del mundo, en % de la Salida (67% = la calidad de MetalFX). Auto: baja y sube sola para mantener los FPS. Más baja = más FPS.",
			"The world's internal resolution, as a % of the Output (67% = MetalFX's Quality). Auto: goes down and up by itself to hold the FPS. Lower = more FPS.");
	}

	public static void cycle(Path gameDir) {
		int c = choice(), i = -1;
		for (int k = 0; k < CHOICES.length; k++) if (CHOICES[k] == c) i = k;
		if (i < 0 && c > 0) {
			mcopt.metal.shade.taa.ShadeTaa taa = mcopt.metal.shade.taa.ShadeTaa.current();
			int oh = taa != null && taa.outHeight() > 0 ? taa.outHeight() : 0;
			float pct = c <= 100 ? c : oh > 0 ? 100f * c / oh : 100f;
			i = 1;
			for (int k = 2; k < CHOICES.length; k++) if (CHOICES[k] >= pct) i = k;
		}
		String value = VALUES[(i + 1) % CHOICES.length];
		System.setProperty("mcopt.shade.resolution", value);
		save(gameDir.resolve("config").resolve("mcopt.properties"), value);
	}

	private static void save(Path file, String value) {
		try {
			List<String> lines = Files.exists(file) ? new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8)) : new ArrayList<>();
			boolean found = false;
			for (int i = 0; i < lines.size(); i++) {
				if (lines.get(i).trim().startsWith("mcopt.shade.resolution=")) {
					lines.set(i, "mcopt.shade.resolution=" + value);
					found = true;
				}
			}
			if (!found) {
				lines.add("# Resolución del mundo (también desde el menú de pausa): auto, native o un % de la salida (85%, 75%, 67%, 59%, 50%)");
				lines.add("mcopt.shade.resolution=" + value);
			}
			Files.createDirectories(file.getParent());
			Files.write(file, lines, StandardCharsets.UTF_8);
		} catch (IOException e) {
			System.out.println("[mcopt] shade: could not save the resolution choice: " + e);
		}
	}
}
