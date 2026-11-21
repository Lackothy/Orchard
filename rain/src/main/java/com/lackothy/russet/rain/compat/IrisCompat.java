package com.lackothy.russet.rain.compat;

import com.lackothy.russet.rain.ParticleRain;
import com.lackothy.russet.rain.VersionUtil;

// Russet: Iris's API through reflection, as Russet doesn't build against Iris.
public class IrisCompat {
    public static boolean shadersEnabled() {
        if (!irisLoaded()) return false;
        try {
            Object api = Class.forName("net.irisshaders.iris.api.v0.IrisApi").getMethod("getInstance").invoke(null);
            return (boolean) api.getClass().getMethod("isShaderPackInUse").invoke(api);
        } catch (ReflectiveOperationException | LinkageError e) {
            return false;
        }
    }

    public static boolean irisLoaded() {
        return VersionUtil.isModLoaded("iris");
    }

    public static void registerRenderPipelines() {
        if (!irisLoaded()) return;
        try {
            Class<?> apiClass = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            Class<?> program = Class.forName("net.irisshaders.iris.api.v0.IrisProgram");
            Object api = apiClass.getMethod("getInstance").invoke(null);
            Object translucent = program.getField("PARTICLES_TRANSLUCENT").get(null);
            for (var method : api.getClass().getMethods()) {
                if (method.getName().equals("assignPipeline") && method.getParameterCount() == 2) {
                    method.invoke(api, com.lackothy.russet.rain.particle.render.BlendedParticleRenderType.BLENDED_PARTICLE, translucent);
                    return;
                }
            }
        } catch (ReflectiveOperationException | LinkageError e) {
            ParticleRain.LOGGER.warn("Couldn't give Iris the fog's pipeline", e);
        }
    }
}
