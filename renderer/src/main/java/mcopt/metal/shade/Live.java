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
import java.util.concurrent.ConcurrentHashMap;

public final class Live {
	private record Entry(String raw, float f, int i) {
	}

	private static final ConcurrentHashMap<String, Entry> CACHE = new ConcurrentHashMap<>();
	private static final Entry MISSING = new Entry(null, Float.NaN, Integer.MIN_VALUE);

	private Live() {
	}

	private static Entry entry(String key) {
		String raw = System.getProperty(key);
		if (raw == null) return MISSING;
		Entry e = CACHE.get(key);
		if (e != null && e.raw == raw) return e;
		float f;
		try {
			f = Float.parseFloat(raw.trim());
		} catch (NumberFormatException ex) {
			f = Float.NaN;
		}
		e = new Entry(raw, f, Float.isNaN(f) ? Integer.MIN_VALUE : Math.round(f));
		CACHE.put(key, e);
		return e;
	}

	public static float f(String key, float def) {
		float v = entry(key).f;
		return Float.isNaN(v) ? def : v;
	}

	public static float f(String key, float def, float min, float max) {
		return Math.max(min, Math.min(max, f(key, def)));
	}

	public static int i(String key, int def) {
		int v = entry(key).i;
		return v == Integer.MIN_VALUE ? def : v;
	}

	public static boolean b(String key, boolean def) {
		String v = System.getProperty(key);
		if (v == null) return def;
		String s = v.trim();
		return s.equalsIgnoreCase("true") || (!s.equalsIgnoreCase("false") && def);
	}

	public static String s(String key, String def) {
		String v = System.getProperty(key);
		return v == null ? def : v.trim().toLowerCase(Locale.ROOT);
	}
}
