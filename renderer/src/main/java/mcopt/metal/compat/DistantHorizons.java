package mcopt.metal.compat;

import com.mojang.blaze3d.systems.RenderSystem;

public final class DistantHorizons {
	private static final String API = "com.seibel.distanthorizons.api.enums.config.EDhApiRenderingApi";
	private static Object notOpenGl;
	private static boolean logged;

	private DistantHorizons() {
	}

	public static Object renderingApi() {
		if (!"Metal".equals(RenderSystem.getDevice().getDeviceInfo().backendName())) return null;
		if (notOpenGl == null) {
			try {
				notOpenGl = enumConstant(Class.forName(API, true, DistantHorizons.class.getClassLoader()), "VULKAN");
			} catch (ClassNotFoundException e) {
				throw new IllegalStateException("mcopt: Distant Horizons has no " + API, e);
			}
		}
		if (!logged) {
			logged = true;
			System.out.println("[mcopt] Distant Horizons draws through the Metal backend (its Blaze3D renderer, told the backend isn't OpenGL)");
		}
		return notOpenGl;
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static Object enumConstant(Class<?> type, String name) {
		return Enum.valueOf((Class) type, name);
	}
}
