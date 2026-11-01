package mcopt.metal;

import com.mojang.text2speech.Narrator;

public final class LazyNarrator implements Narrator {
	private Narrator real;

	private synchronized Narrator real() {
		if (real == null) real = Narrator.getNarrator();
		return real;
	}

	@Override public void say(String msg, boolean interrupt, float volume) { real().say(msg, interrupt, volume); }
	@Override public void clear() { real().clear(); }
	@Override public boolean active() { return real().active(); }
	@Override public synchronized void destroy() { if (real != null) real.destroy(); }
}
