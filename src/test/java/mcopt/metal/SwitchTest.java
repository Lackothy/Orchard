package mcopt.metal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SwitchTest {

    @Test
    @DisplayName("on by itself, on an Apple Silicon Mac with nothing in its way")
    void onByItself() {
        assertNull(Switch.whyOff(null, mod -> false, false, null));
        assertNull(Switch.whyOff("metal", mod -> false, false, null));
        assertNull(Switch.backendOnly(mod -> false));
    }

    @Test
    @DisplayName("off when asked for Mojang's renderer")
    void askedForMojang() {
        assertEquals("The game was started with -Drusset.renderer=mojang, which asks for Minecraft's own renderer.",
            Switch.whyOff("mojang", mod -> false, false, null));
        assertEquals("The game was started with -Drusset.renderer=mojang, which asks for Minecraft's own renderer.",
            Switch.whyOff("mojang", Set.of("sodium")::contains, false, null));
    }

    @Test
    @DisplayName("only the backend beside Sodium, which draws the terrain on Metal")
    void besideSodium() {
        assertNull(Switch.whyOff(null, Set.of("sodium")::contains, false, null));
        assertNotNull(Switch.backendOnly(Set.of("sodium")::contains));
    }

    @Test
    @DisplayName("off beside Iris unless options.txt prefers Vulkan, which puts Iris to sleep")
    void besideIris() {
        Set<String> both = Set.of("sodium", "iris");
        assertEquals("Iris is on, and Metal can't run beside it. Set Graphics API to Vulkan and restart: Iris then steps aside, and Metal draws with Sodium.",
            Switch.whyOff(null, both::contains, false, null));
        assertNull(Switch.whyOff(null, both::contains, true, null));
        assertNotNull(Switch.backendOnly(both::contains));
    }

    @Test
    @DisplayName("reads Vulkan from options.txt the way Iris does")
    void vulkanInOptions(@TempDir Path dir) throws IOException {
        Path options = dir.resolve("options.txt");
        assertFalse(Switch.vulkanPreferred(options));
        assertFalse(Switch.vulkanPreferred(null));
        Files.writeString(options, "version:4790\nrenderDistance:12\n");
        assertFalse(Switch.vulkanPreferred(options));
        Files.writeString(options, "version:4790\npreferredGraphicsBackend:\"default\"\n");
        assertFalse(Switch.vulkanPreferred(options));
        Files.writeString(options, "version:4790\npreferredGraphicsBackend:\"vulkan\"\nrenderDistance:12\n");
        assertTrue(Switch.vulkanPreferred(options));
    }

    @Test
    @DisplayName("off, not stopping the game, where the native library can't run")
    void wrongComputer() {
        assertNotNull(PlatformCheck.problem("Windows 11", "amd64", "10.0"));
        assertNotNull(PlatformCheck.problem("Mac OS X", "x86_64", "26.0"));
        assertNotNull(PlatformCheck.problem("Mac OS X", "aarch64", "15.5"));
        assertNull(PlatformCheck.problem("Mac OS X", "aarch64", "26.1"));
        String problem = PlatformCheck.problem("Mac OS X", "aarch64", "15.5");
        assertEquals(problem, Switch.whyOff(null, mod -> false, false, problem));
        assertEquals(problem, Switch.whyOff(null, Set.of("sodium")::contains, false, problem));
    }
}
