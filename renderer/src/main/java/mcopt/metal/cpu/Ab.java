package mcopt.metal.cpu;

public final class Ab {
	private final String name;
	private int calls;
	private boolean arm;
	private long t0, n0, n1, ns0, ns1, lastPrint = System.nanoTime();

	public Ab(String name) {
		this.name = name;
	}

	public static long frame;

	public boolean begin() {
		calls++;
		arm = (frame & 1) == 1;
		t0 = System.nanoTime();
		return arm;
	}

	public void end() {
		long now = System.nanoTime(), d = now - t0;
		if (arm) {
			n1++;
			ns1 += d;
		} else {
			n0++;
			ns0 += d;
		}
		if (now - lastPrint > 5_000_000_000L && n0 > 0 && n1 > 0) {
			lastPrint = now;
			System.out.printf("mcopt-cpu: ab %s off %.2f us (n %d) on %.2f us (n %d) ratio %.3f%n", name, ns0 / 1e3 / n0, n0, ns1 / 1e3 / n1, n1,
				(double) (ns1 / n1) / (ns0 / n0));
		}
	}
}
