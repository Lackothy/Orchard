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

import java.util.Locale;
import java.util.function.IntFunction;
import java.util.function.IntSupplier;
import java.util.function.ToIntFunction;

public final class ShadeOption {
	public enum Kind {
		SLIDER, CHOICE, TOGGLE
	}

	static final String BLOCKS = "\u0000blocks";

	public final String key;
	public final Kind kind;
	final String es, en, tipEs, tipEn;
	final int min, max;
	private final IntSupplier def;
	private final IntFunction<String> toProp, display;
	private final ToIntFunction<String> fromProp;
	final String[] namesEs, namesEn;
	boolean restart;

	private ShadeOption(String key, Kind kind, String es, String en, String tipEs, String tipEn, int min, int max, IntSupplier def,
		IntFunction<String> toProp, ToIntFunction<String> fromProp, IntFunction<String> display, String[] namesEs, String[] namesEn) {
		this.key = key;
		this.kind = kind;
		this.es = es;
		this.en = en;
		this.tipEs = tipEs;
		this.tipEn = tipEn;
		this.min = min;
		this.max = max;
		this.def = def;
		this.toProp = toProp;
		this.fromProp = fromProp;
		this.display = display;
		this.namesEs = namesEs;
		this.namesEn = namesEn;
	}

	static ShadeOption pct(String key, String es, String en, String tipEs, String tipEn, int minPct, int maxPct, int defPct) {
		return pctOf(key, es, en, tipEs, tipEn, minPct, maxPct, () -> defPct, 1f);
	}

	static ShadeOption pctOf(String key, String es, String en, String tipEs, String tipEn, int minPct, int maxPct, IntSupplier defPct, float base) {
		return new ShadeOption(key, Kind.SLIDER, es, en, tipEs, tipEn, minPct, maxPct, defPct, p -> fmt(p * base / 100f),
			v -> roundOr(parse(v, Float.NaN) * 100f / base), p -> p + "%", null, null);
	}

	static ShadeOption pctInt(String key, String es, String en, String tipEs, String tipEn, int minPct, int maxPct, int defPct) {
		return new ShadeOption(key, Kind.SLIDER, es, en, tipEs, tipEn, minPct, maxPct, () -> defPct, Integer::toString,
			v -> roundOr(parse(v, Float.NaN)), p -> p + "%", null, null);
	}

	static ShadeOption num(String key, String es, String en, String tipEs, String tipEn, int min, int max, IntSupplier def, int div, String unit) {
		return new ShadeOption(key, Kind.SLIDER, es, en, tipEs, tipEn, min, max, def, p -> fmt((float) p / div),
			v -> roundOr(parse(v, Float.NaN) * div),
			p -> (div == 1 ? Integer.toString(p) : String.format(Locale.ROOT, div == 10 ? "%.1f" : "%.2f", (float) p / div))
				+ (BLOCKS.equals(unit) ? ShadeLang.t(" bloques", " blocks") : unit),
			null, null);
	}

	static ShadeOption custom(String key, String es, String en, String tipEs, String tipEn, int min, int max, IntSupplier def, IntFunction<String> toProp,
		ToIntFunction<String> fromProp, IntFunction<String> display) {
		return new ShadeOption(key, Kind.SLIDER, es, en, tipEs, tipEn, min, max, def, toProp, fromProp, display, null, null);
	}

	static ShadeOption choice(String key, String es, String en, String tipEs, String tipEn, String[] values, String[] namesEs, String[] namesEn, IntSupplier def) {
		return new ShadeOption(key, Kind.CHOICE, es, en, tipEs, tipEn, 0, values.length - 1, def, p -> values[p], v -> {
			String s = v.trim().toLowerCase(Locale.ROOT);
			for (int i = 0; i < values.length; i++) if (values[i].equals(s)) return i;
			return Integer.MIN_VALUE;
		}, p -> null, namesEs, namesEn);
	}

	static ShadeOption toggle(String key, String es, String en, String tipEs, String tipEn, boolean def, boolean inverted) {
		return new ShadeOption(key, Kind.TOGGLE, es, en, tipEs, tipEn, 0, 1, () -> def ? 1 : 0, p -> Boolean.toString((p == 1) != inverted), v -> {
			String s = v.trim();
			if (!s.equalsIgnoreCase("true") && !s.equalsIgnoreCase("false")) return Integer.MIN_VALUE;
			return s.equalsIgnoreCase("true") != inverted ? 1 : 0;
		}, p -> null, null, null);
	}

	static ShadeOption toggle(String key, String es, String en, String tipEs, String tipEn, boolean def) {
		return toggle(key, es, en, tipEs, tipEn, def, false);
	}

	ShadeOption restart() {
		this.restart = true;
		return this;
	}

	static IntSupplier tier(int p, int b, int q, int u) {
		int[] v = {p, b, q, u};
		return () -> v[Shade.TIER];
	}

	public int defaultPos() {
		return clamp(this.def.getAsInt());
	}

	public int pos() {
		String v = System.getProperty(this.key);
		if (v == null) return defaultPos();
		int p;
		try {
			p = this.fromProp.applyAsInt(v);
		} catch (RuntimeException e) {
			p = Integer.MIN_VALUE;
		}
		return p == Integer.MIN_VALUE ? defaultPos() : clamp(p);
	}

	public void set(int pos) {
		int p = clamp(pos);
		String text = this.toProp.apply(p);
		System.setProperty(this.key, text);
		ShadeSettings.changed(this.key, p == defaultPos() ? null : text);
	}

	public void reset() {
		set(defaultPos());
	}

	public String valueText(int p) {
		return switch (this.kind) {
			case SLIDER -> this.display.apply(p);
			case CHOICE -> ShadeLang.t(this.namesEs[p], this.namesEn[p]);
			case TOGGLE -> p == 1 ? ShadeLang.t("Sí", "On") : ShadeLang.t("No", "Off");
		};
	}

	public String caption() {
		return ShadeLang.t(this.es, this.en) + (this.restart ? " *" : "");
	}

	public String tooltip() {
		String t = this.tipEs == null ? null : ShadeLang.t(this.tipEs, this.tipEn);
		if (!this.restart) return t;
		String r = ShadeLang.t("* Se aplica al reiniciar el juego.", "* Applies after restarting the game.");
		return t == null ? r : t + "\n" + r;
	}

	private int clamp(int p) {
		return Math.max(this.min, Math.min(this.max, p));
	}

	static int roundOr(float v) {
		return Float.isNaN(v) ? Integer.MIN_VALUE : Math.round(v);
	}

	static float parse(String v, float fallback) {
		try {
			return Float.parseFloat(v.trim());
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	static String fmt(float v) {
		if (v == Math.rint(v) && Math.abs(v) < 1e7f) return Integer.toString((int) v);
		String s = String.format(Locale.ROOT, "%.4f", v);
		s = s.replaceAll("0+$", "");
		return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
	}
}
