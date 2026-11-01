package mcopt.metal;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

public final class Preload implements PreLaunchEntrypoint {
	static {
		mcopt.metal.Profile.apply();
	}

	private static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("mcopt.preload", "true"));
	private static final int THREADS = Integer.getInteger("mcopt.preload.threads", 2);

	@Override
	public void onPreLaunch() {
		if (!Switch.ON) {
			System.out.println("[Orchard] Metal is off. " + Switch.WHY_OFF);
			return;
		}
		if (!ENABLED) return;
		List<String> names = new ArrayList<>();
		try (InputStream in = Preload.class.getResourceAsStream("/mcopt/metal/preload.txt.gz")) {
			if (in == null) return;
			BufferedReader r = new BufferedReader(new InputStreamReader(new GZIPInputStream(in), StandardCharsets.UTF_8));
			for (String line; (line = r.readLine()) != null; ) if (!line.isEmpty()) names.add(line);
		} catch (IOException e) {
			return;
		}
		ClassLoader loader = Preload.class.getClassLoader();
		AtomicInteger next = new AtomicInteger();
		for (int t = 0; t < THREADS; t++) {
			Thread thread = new Thread(() -> {
				for (int i; (i = next.getAndIncrement()) < names.size(); ) {
					try {
						Class.forName(names.get(i), false, loader);
					} catch (Throwable absent) {
					}
				}
			}, "mcopt class preload #" + t);
			thread.setDaemon(true);
			thread.start();
		}
	}
}
