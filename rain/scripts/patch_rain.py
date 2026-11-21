"""Russet's changes to Particle Rain, applied after import_rain.py. Each is marked "Russet:" in the code.

Checkstyle then names the imports the changes left unused, which are taken out by hand.

Usage: python3 -I patch_rain.py <orchard>
"""
import sys
from pathlib import Path

R = Path(sys.argv[1]) / "rain/src/main/java/com/lackothy/russet/rain"

def sub(rel, pairs, every=None):
    """Each old text must be there exactly once; every={old: new} then replaces all that are left."""
    p = R / rel
    s = p.read_text()
    for old, new in pairs:
        if s.count(old) != 1:
            raise SystemExit(f"{rel}: expected one {old!r}, found {s.count(old)}")
        s = s.replace(old, new)
    for old, new in (every or {}).items():
        s = s.replace(old, new)
    p.write_text(s)

sub("ParticleRain.java", [
    ('''    public static final String MOD_ID = "particlerain";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
''', '''    public static final String MOD_ID = "particlerain";
    // Russet: assets are in Russet's namespace. MOD_ID stays Particle Rain's, for its translation keys,
    // its command, and WindLink, which asks for it by name.
    public static final String NAMESPACE = "russet";
    public static final Logger LOGGER = LoggerFactory.getLogger("Russet: Particle Rain");
    /** Russet: the real Particle Rain is installed, so it draws the weather and this copy does nothing. */
    public static final boolean STOOD_DOWN = VersionUtil.isModLoaded(MOD_ID);

    /** Russet: whether to draw the weather, read by every hook (Quality > Fabulous > Weather particles). */
    public static boolean on() {
        return !STOOD_DOWN && VideoOptions.WEATHER_PARTICLES.on();
    }
'''),
    ("    public static SimpleParticleType STREAK;\n\n", ""),
    ("import net.minecraft.core.particles.SimpleParticleType;\n", ""),
    ("import com.lackothy.russet.rain.config.ConfigManager;\n",
     "import com.lackothy.russet.rain.config.ConfigManager;\nimport com.lackothy.russet.video.VideoOptions;\n"),
    ('''        final Camera camera = client.gameRenderer.mainCamera();
        if (!client.isPaused()''', '''        final Camera camera = client.gameRenderer.mainCamera();
        if (on() && !client.isPaused()'''),
])

sub("VersionUtil.java", [
    ("return Identifier.fromNamespaceAndPath(ParticleRain.MOD_ID, path);",
     "return Identifier.fromNamespaceAndPath(ParticleRain.NAMESPACE, path);"),
])

sub("config/ConfigManager.java", [
    ('    private static final String CONFIG_PATH = "config/particlerain/config.json";',
     '    // Russet: config/russet-rain, beside Russet\'s own settings. Looked up when used, so tests can\n'
     '    // load the particles without Fabric Loader.\n'
     '    public static Path folder() {\n'
     '        return net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("russet-rain");\n'
     '    }\n\n'
     '    private static String configPath() {\n'
     '        return folder().resolve("config.json").toString();\n'
     '    }'),
    ('Files.createDirectories(Path.of("config", "particlerain"));', "Files.createDirectories(folder());"),
    ("Couldn't create directory 'config/particlerain/'\", e", "Couldn't create directory '{}'\", folder(), e"),
], every={"CONFIG_PATH": "configPath()"})

sub("ParticleLoader.java", [
    ('    private static final String CUSTOM_PARTICLES_PATH = "config/particlerain/particles.json";',
     '    // Russet: in config/russet-rain\n'
     '    private static String customParticlesPath() {\n'
     '        return ConfigManager.folder().resolve("particles.json").toString();\n'
     '    }'),
    ("import com.lackothy.russet.rain.config.ParticleData;\n",
     "import com.lackothy.russet.rain.config.ConfigManager;\nimport com.lackothy.russet.rain.config.ParticleData;\n"),
], every={"CUSTOM_PARTICLES_PATH": "customParticlesPath()"})

sub("config/ParticleData.java", [
    ('''    public void setPresetParticle() {
''', '''    public void setPresetParticle() {
        // Russet: the streak isn't a registered particle; ParticleSpawner makes it directly.
        if ("russet:streak".equals(registeredParticleId)) return;
'''),
])

sub("loaders/fabric/FabricEntrypoint.java", [
    ('''        //TODO
        ParticleRain.STREAK = registerParticle("streak");
        ParticleProviderRegistry.getInstance().register(ParticleRain.STREAK, StreakParticle.Provider::new);

        ParticleRain.onInitializeClient();
''', '''        // Russet: called from RussetClient, not an entrypoint of its own. Nothing is registered (the
        // streak is made directly), so a LAN world hosted with Russet asks nothing of who joins.
        if (ParticleRain.STOOD_DOWN) {
            ParticleRain.LOGGER.info("Particle Rain is installed, so Russet's copy of it stays off");
            return;
        }
        ParticleRain.onInitializeClient();
'''),
    ('''    private SimpleParticleType registerParticle(String name) {
        return Registry.register(BuiltInRegistries.PARTICLE_TYPE, VersionUtil.getId(name), FabricParticleTypes.simple(true));
    }
''', ""),
    ("import net.fabricmc.fabric.api.client.particle.v1.ParticleProviderRegistry;\n", ""),
    ("import net.fabricmc.fabric.api.particle.v1.FabricParticleTypes;\n", ""),
    ("import net.minecraft.core.Registry;\n", ""),
    ("import net.minecraft.core.particles.SimpleParticleType;\n", ""),
    ("import net.minecraft.core.registries.BuiltInRegistries;\n", ""),
    ("import com.lackothy.russet.rain.particle.*;\n", ""),
])

sub("mixin/render/WeatherEffectRendererMixin.java", [
    ("        if (!getConfig().compat.renderDefaultRain && !getConfig().compat.renderDefaultSnow) {",
     "        if (ParticleRain.on() && !getConfig().compat.renderDefaultRain && !getConfig().compat.renderDefaultSnow) { // Russet: on()"),
    ("        if (!getConfig().compat.renderDefaultRain) return false;",
     "        if (ParticleRain.on() && !getConfig().compat.renderDefaultRain) return false; // Russet: on()"),
    ("        if (!getConfig().compat.renderDefaultSnow) return false;",
     "        if (ParticleRain.on() && !getConfig().compat.renderDefaultSnow) return false; // Russet: on()"),
])

sub("mixin/render/ClientLevelMixin.java", [
    ("        ParticleSpawner.tickBlockFX(blockPos, state, random);",
     "        if (ParticleRain.on()) ParticleSpawner.tickBlockFX(blockPos, state, random); // Russet: on()"),
    ("        return Biome.Precipitation.RAIN;",
     "        if (!ParticleRain.on()) return original.call(instance, blockPos); // Russet\n        return Biome.Precipitation.RAIN;"),
    ("        ParticleRain.doAdditionalWeatherSounds(",
     "        if (!ParticleRain.on()) return; // Russet\n        ParticleRain.doAdditionalWeatherSounds("),
    ("        int newY = ParticleSpawner.getHeight(instance, blockPos.getX(), blockPos.getZ());",
     "        if (!ParticleRain.on()) return original.call(instance, types, blockPos); // Russet\n"
     "        int newY = ParticleSpawner.getHeight(instance, blockPos.getX(), blockPos.getZ());"),
    ("        original.call(instance, blockPos, soundEvent, soundSource, getConfig().sound.rainVolume, pitch, distanceDelay);",
     "        // Russet: Minecraft's volume while the weather is Minecraft's\n"
     "        original.call(instance, blockPos, soundEvent, soundSource, ParticleRain.on() ? getConfig().sound.rainVolume : volume, pitch, distanceDelay);"),
    ("        if (!getConfig().compat.doDefaultSplashing) {",
     "        if (ParticleRain.on() && !getConfig().compat.doDefaultSplashing) { // Russet: on()"),
])

sub("mixin/tint/DripParticleMixin.java", [
    ("        if (ConfigManager.getConfig().compat.waterTint) TextureUtil",
     "        if (ParticleRain.on() && ConfigManager.getConfig().compat.waterTint) TextureUtil"),
    ("import com.lackothy.russet.rain.config.ConfigManager;\n",
     "import com.lackothy.russet.rain.ParticleRain;\nimport com.lackothy.russet.rain.config.ConfigManager;\n"),
])
sub("mixin/tint/WaterDropParticleMixin.java", [
    ("        if (ConfigManager.getConfig().compat.waterTint) {",
     "        if (ParticleRain.on() && ConfigManager.getConfig().compat.waterTint) { // Russet: on()"),
])

(R / "compat/IrisCompat.java").write_text('''package com.lackothy.russet.rain.compat;

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
''')
print("patched")
