package mcopt.metal.rec;

import com.mojang.renderpearl.api.textures.GpuTextureView;

public final class Rec {
	public static final boolean ON = System.getProperty("mcopt.rec") != null;
	private static Recorder recorder;
	private static boolean failed;

	private Rec() {
	}

	public static void frame(Object encoder, GpuTextureView view) {
		if (failed) return;
		try {
			if (recorder == null) recorder = new Recorder(encoder, view);
			recorder.frame(encoder, view);
		} catch (Throwable t) {
			failed = true;
			System.out.println("mcopt-rec: recorder off after an error: " + t);
			t.printStackTrace(System.out);
			if (recorder != null) recorder.abort();
		}
	}
}
