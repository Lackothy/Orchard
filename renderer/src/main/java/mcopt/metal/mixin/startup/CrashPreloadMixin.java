package mcopt.metal.mixin.startup;

import net.minecraft.CrashReport;
import net.minecraft.ReportType;
import net.minecraft.client.main.Main;
import net.minecraft.util.MemoryReserve;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(Main.class)
abstract class CrashPreloadMixin {
	@Redirect(method = "main", at = @At(value = "INVOKE", target = "Lnet/minecraft/CrashReport;preload()V"))
	private static void mcopt$async() {
		MemoryReserve.allocate();
		Thread t = new Thread(() -> new CrashReport("Don't panic!", new Throwable()).getFriendlyReport(ReportType.CRASH), "mcopt crash-report preload");
		t.setDaemon(true);
		t.start();
		String test = System.getProperty("mcopt.startup.crashPreloadTest");
		if (test != null) {
			try {
				java.nio.file.Files.writeString(java.nio.file.Path.of(test),
					new CrashReport("mcopt crash-preload test", new Throwable()).getFriendlyReport(ReportType.CRASH));
				t.join();
				java.nio.file.Files.writeString(java.nio.file.Path.of(test + ".after"),
					new CrashReport("mcopt crash-preload test", new Throwable()).getFriendlyReport(ReportType.CRASH));
			} catch (java.io.IOException | InterruptedException e) {
				throw new RuntimeException(e);
			}
		}
	}
}
