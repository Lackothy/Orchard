package mcopt.metal;

public final class Latency {
	public static final boolean ON = Boolean.getBoolean("mcopt.metal.latency");
	public static volatile long lastPollNs;

	private Latency() {
	}
}
