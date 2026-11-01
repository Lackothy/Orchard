package mcopt.metal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class SwitchTest {

    @Test
    @DisplayName("on by itself, on an Apple Silicon Mac with nothing in its way")
    void onByItself() {
        assertNull(Switch.whyOff(null, mod -> false, null));
        assertNull(Switch.whyOff("metal", mod -> false, null));
    }

    @Test
    @DisplayName("off when the launcher says Mojang's renderer")
    void launcherSaysMojang() {
        assertEquals("This instance is set to Mojang's renderer, in the launcher's More menu.",
            Switch.whyOff("mojang", mod -> false, null));
    }

    @Test
    @DisplayName("off beside Sodium or Iris, as mcopt's breaks: sodium was")
    void besideSodiumOrIris() {
        assertEquals("Sodium is on, and Metal can't run beside it.", Switch.whyOff(null, Set.of("sodium")::contains, null));
        assertEquals("Iris is on, and Metal can't run beside it.", Switch.whyOff(null, Set.of("sodium", "iris")::contains, null));
    }

    @Test
    @DisplayName("off, not stopping the game, where the native library can't run")
    void wrongComputer() {
        assertNotNull(PlatformCheck.problem("Windows 11", "amd64", "10.0"));
        assertNotNull(PlatformCheck.problem("Mac OS X", "x86_64", "26.0"));
        assertNotNull(PlatformCheck.problem("Mac OS X", "aarch64", "15.5"));
        assertNull(PlatformCheck.problem("Mac OS X", "aarch64", "26.1"));
        String problem = PlatformCheck.problem("Mac OS X", "aarch64", "15.5");
        assertEquals(problem, Switch.whyOff(null, mod -> false, problem));
    }
}
