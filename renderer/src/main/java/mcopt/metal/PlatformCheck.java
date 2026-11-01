// Modified for Orchard: says what is wrong (Switch then leaves the renderer off) rather than stopping the game, as Orchard's jar has more in it than the renderer.
package mcopt.metal;

/**
 * Whether this is a machine the native library can run on: it is arm64-only and the mod supports macOS 26 and later.
 * -Dmcopt.platformCheck.fake=linux (or x86_64, or a macOS version like 15.5) pretends to be that platform, to test the message.
 */
final class PlatformCheck {
	private PlatformCheck() {
	}

	/** What stops the renderer running here, or null when nothing does. */
	static String problem() {
		String os = System.getProperty("os.name", ""), arch = System.getProperty("os.arch", ""), version = System.getProperty("os.version", "");
		String fake = System.getProperty("mcopt.platformCheck.fake");
		if (fake != null) {
			if (fake.matches("[0-9.]+")) version = fake;
			else if (fake.equals("x86_64") || fake.equals("amd64")) arch = fake;
			else os = fake;
		}
		return problem(os, arch, version);
	}

	static String problem(String os, String arch, String version) {
		if (!os.startsWith("Mac")) return "It needs an Apple Silicon Mac (M1 or newer) with macOS 26 or later, and this computer runs " + os + ".";
		if (!arch.equals("aarch64") && !arch.equals("arm64")) {
			return "It needs an Apple Silicon Mac (M1 or newer) and an Apple Silicon (arm64) Java, and this Java is " + arch
				+ " (an Intel Mac, or an Intel Java running through Rosetta).";
		}
		int major;
		try {
			major = Integer.parseInt(version.split("\\.")[0]);
		} catch (NumberFormatException e) {
			return null; // unknown version string: let it try rather than block a working Mac
		}
		// A JDK built against an older SDK may see macOS 26 as "16.x" (Apple's compatibility numbering); there was no macOS 16.
		if (major < 26 && major != 16) return "It needs macOS 26 or later, and this Mac runs macOS " + version + ". Update macOS in System Settings > General > Software Update.";
		return null;
	}
}
