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
package mcopt.metal.shade;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class ShadeOutput {
	private static final int[] CHOICES = {-1, 2160, 1440, 1200, 1080, 900};

	private static volatile int index = parse(System.getProperty("mcopt.shade.output", "native"));
	private static volatile int realHeight;

	private ShadeOutput() {
	}

	private static int parse(String v) {
		String s = v.trim().toLowerCase(Locale.ROOT).replace("p", "");
		try {
			int h = Integer.parseInt(s);
			for (int i = 1; i < CHOICES.length; i++) if (CHOICES[i] == h) return i;
		} catch (NumberFormatException e) {
		}
		return 0;
	}

	public static boolean taauMode() {
		return "taau".equalsIgnoreCase(System.getProperty("mcopt.shade.outputMode", "window").trim());
	}

	public static boolean available() {
		return !taauMode() || ShadeConfig.HDR && !mcopt.metal.DhCompat.loaded();
	}

	private static int choice() {
		String live = System.getProperty("mcopt.shade.output");
		return CHOICES[live != null ? parse(live) : index];
	}

	private static int[] scaled(int w, int h, int c) {
		if (c < 0 || h <= 0 || c >= h) return new int[] {w, h};
		int sw = Math.max(2, (int) (Math.round((double) w * c / h / 2.0) * 2));
		return new int[] {Math.min(sw, w), c};
	}

	public static int[] framebuffer(int width, int height) {
		realHeight = height;
		if (taauMode()) return new int[] {width, height};
		return scaled(width, height, choice());
	}

	public static void apply() {
		if (taauMode()) return;
		net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
		((mcopt.metal.mixin.shade.ShadeWindowAccessor) (Object) mc.getWindow()).mcopt$refreshFramebufferSize();
		mc.framebufferSizeChanged();
	}

	public static int[] size(int mainWidth, int mainHeight) {
		if (!taauMode() || !available()) return new int[] {mainWidth, mainHeight};
		realHeight = mainHeight;
		return scaled(mainWidth, mainHeight, choice());
	}

	public static int[] current(int windowWidth, int windowHeight) {
		mcopt.metal.shade.taa.ShadeTaa taa = mcopt.metal.shade.taa.ShadeTaa.current();
		if (taa != null && taa.outWidth() > 0 && taa.outHeight() > 0) return new int[] {taa.outWidth(), taa.outHeight()};
		return new int[] {Math.max(1, windowWidth), Math.max(1, windowHeight)};
	}

	public static String label() {
		int c = CHOICES[index];
		if (!available()) return ShadeLang.t("Salida: Nativa (DH)", "Output: Native (DH)");
		return c < 0 ? ShadeLang.t("Salida: Nativa", "Output: Native") : ShadeLang.t("Salida: ", "Output: ") + c + "p";
	}

	public static String tooltip() {
		return taauMode() ? ShadeLang.t("Resolución de la imagen del mundo; el menú y el texto siguen nítidos. Más baja = más FPS.",
				"Resolution of the world image; the menus and text stay sharp. Lower = more FPS.")
			: ShadeLang.t("Resolución de todo el juego, como la de un monitor (el menú y el texto también). Más baja = más FPS.",
				"Resolution of the whole game, like a monitor's (menus and text too). Lower = more FPS.");
	}

	public static String debugPart(int outWidth, int outHeight, int mainWidth, int mainHeight) {
		if (outWidth <= 0 || (outWidth == mainWidth && outHeight == mainHeight)) return "";
		return String.format(Locale.ROOT, " → salida %dx%d", outWidth, outHeight);
	}

	public static void cycle(Path gameDir) {
		index = (index + 1) % CHOICES.length;
		int real = realHeight;
		for (int k = 0; k < CHOICES.length && real > 0 && CHOICES[index] >= real; k++) index = (index + 1) % CHOICES.length;
		int c = CHOICES[index];
		String value = c < 0 ? "native" : Integer.toString(c);
		System.setProperty("mcopt.shade.output", value);
		save(gameDir.resolve("config").resolve("mcopt.properties"), value);
		apply();
	}

	private static void save(Path file, String value) {
		try {
			List<String> lines = Files.exists(file) ? new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8)) : new ArrayList<>();
			boolean found = false;
			for (int i = 0; i < lines.size(); i++) {
				if (lines.get(i).trim().startsWith("mcopt.shade.output=")) {
					lines.set(i, "mcopt.shade.output=" + value);
					found = true;
				}
			}
			if (!found) {
				lines.add("# Resolución de salida (también desde el menú de pausa): native, 2160, 1440, 1200, 1080, 900");
				lines.add("mcopt.shade.output=" + value);
			}
			Files.createDirectories(file.getParent());
			Files.write(file, lines, StandardCharsets.UTF_8);
		} catch (IOException e) {
			System.out.println("[mcopt] shade: could not save the output resolution choice: " + e);
		}
	}
}
