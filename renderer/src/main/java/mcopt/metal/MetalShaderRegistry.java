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
package mcopt.metal;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.Nullable;

public final class MetalShaderRegistry {
	public record Variant(@Nullable UnaryOperator<String> vertex, @Nullable UnaryOperator<String> fragment, List<String> required,
		String vertexEntry, String fragmentEntry) {
		public Variant(@Nullable UnaryOperator<String> vertex, @Nullable UnaryOperator<String> fragment, List<String> required) {
			this(vertex, fragment, required, "main0", "main0");
		}
	}

	private static final Map<String, Variant> VARIANTS = new ConcurrentHashMap<>();

	public interface FormatAdapter {
		String vertex(String pipelineName, String msl, boolean registry, int[] colorFormats);

		String fragment(String pipelineName, String msl, boolean registry, int[] colorFormats);

		default int[] canonical(String pipelineName, int[] colorFormats) {
			return colorFormats;
		}
	}

	private static volatile @Nullable FormatAdapter adapter;
	private static volatile java.util.function.@Nullable Function<String, int @Nullable [] @Nullable []> prewarm;

	public static void setFormatAdapter(@Nullable FormatAdapter a) {
		adapter = a;
	}

	static @Nullable FormatAdapter formatAdapter() {
		return adapter;
	}

	public static void setPrewarm(java.util.function.@Nullable Function<String, int @Nullable [] @Nullable []> p) {
		prewarm = p;
	}

	static int @Nullable [] @Nullable [] prewarmFormats(String pipelineName) {
		var p = prewarm;
		return p == null ? null : p.apply(pipelineName);
	}

	public interface PrewarmRunner {
		java.util.List<Integer> runAll(java.util.List<Runnable> builds);
	}

	private static volatile @Nullable PrewarmRunner prewarmRunner;

	public static void setPrewarmRunner(@Nullable PrewarmRunner r) {
		prewarmRunner = r;
	}

	static @Nullable PrewarmRunner prewarmRunner() {
		return prewarmRunner;
	}

	private MetalShaderRegistry() {
	}

	public static void register(String pipelineName, Variant variant) {
		VARIANTS.put(pipelineName, variant);
	}

	public static void unregister(String pipelineName) {
		VARIANTS.remove(pipelineName);
	}

	static @Nullable Variant get(String pipelineName) {
		return VARIANTS.isEmpty() ? null : VARIANTS.get(pipelineName);
	}

	public static String slotDefines(List<String> uniformNames) {
		StringBuilder b = new StringBuilder();
		for (int i = 0; i < uniformNames.size(); i++) {
			b.append("#define MCOPT_SLOT_").append(uniformNames.get(i).replaceAll("[^A-Za-z0-9_]", "_")).append(' ').append(i).append('\n');
		}
		return b.toString();
	}
}
