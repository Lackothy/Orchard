package mcopt.metal;

import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import org.jspecify.annotations.Nullable;

public final class MetalHooks {
	public record Redirect(long @Nullable [] colors, float @Nullable [][] clears, int width, int height, long depth, float depthClear,
		@Nullable PassDelegate delegate, int @Nullable [] colorFormats) {
		public static final float NO_CLEAR = Float.NaN;

		public Redirect(long @Nullable [] colors, float @Nullable [][] clears, int width, int height, long depth, float depthClear, PassDelegate delegate) {
			this(colors, clears, width, height, depth, depthClear, delegate, null);
		}

		public static Redirect keep(PassDelegate delegate) {
			return new Redirect(null, null, 0, 0, 0, NO_CLEAR, delegate, null);
		}

		public static Redirect formats(long[] colors, float @Nullable [][] clears, int width, int height, int[] formats) {
			return new Redirect(colors, clears, width, height, 0, NO_CLEAR, null, formats);
		}

		public boolean keepsAttachments() {
			return this.colors == null;
		}
	}

	public static final int TAG_SHIFT = 16;

	static int[] pixelFormats(int[] formats) {
		boolean tagged = false;
		for (int f : formats) tagged |= f >>> TAG_SHIFT != 0;
		if (!tagged) return formats;
		int[] out = new int[formats.length];
		for (int i = 0; i < formats.length; i++) out[i] = formats[i] & ((1 << TAG_SHIFT) - 1);
		return out;
	}

	public interface PassRedirector {
		@Nullable Redirect redirect(RenderPassDescriptor descriptor);
	}

	private static volatile Entry[] chain = new Entry[0];

	private record Entry(int order, PassRedirector redirector) {
	}

	private MetalHooks() {
	}

	static @Nullable Redirect redirect(RenderPassDescriptor descriptor) {
		Entry[] c = chain;
		for (Entry e : c) {
			Redirect r = e.redirector.redirect(descriptor);
			if (r != null) return r;
		}
		return null;
	}

	static boolean hasRedirectors() {
		return chain.length != 0;
	}

	public interface Labeler {
		void label(long enc, String name);
	}

	static volatile @Nullable Labeler labeler;

	public static void setLabeler(@Nullable Labeler l) {
		labeler = l;
	}

	public static synchronized void setRedirector(@Nullable PassRedirector r) {
		chain = r == null ? new Entry[0] : new Entry[] {new Entry(0, r)};
	}

	public static synchronized void addRedirector(int order, PassRedirector r) {
		java.util.List<Entry> list = new java.util.ArrayList<>(java.util.List.of(chain));
		list.add(new Entry(order, r));
		list.sort(java.util.Comparator.comparingInt(Entry::order));
		chain = list.toArray(Entry[]::new);
	}

	public static synchronized void removeRedirector(PassRedirector r) {
		chain = java.util.Arrays.stream(chain).filter(e -> e.redirector != r).toArray(Entry[]::new);
	}
}
