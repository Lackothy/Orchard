package com.lackothy.orchard.mac;

import com.lackothy.Orchard;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BOOLEAN;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * What the Mac says about its power: whether it runs on battery (IOKit's power sources), whether
 * Low Power Mode is on and how hot it is ({@code NSProcessInfo}), read through FFM so no native
 * library is needed. Asked every two seconds on a thread of its own, as the power sources go
 * through powerd and may take a moment; the game only ever reads the last answer.
 */
public final class MacPower {

    public static final boolean MAC = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");

    private static final long EVERY_MS = 2000;
    private static final int UTF8 = 0x08000100;

    /** How the Mac is now. {@code thermal} is {@code NSProcessInfoThermalState}: 0 nominal, 1 fair, 2 serious, 3 critical. */
    public record State(boolean battery, boolean lowPower, int thermal) {
        public static final State PLUGGED_IN = new State(false, false, 0);

        /** Hot enough that the Mac is slowing itself down. */
        public boolean hot() {
            return thermal >= 2;
        }
    }

    private static volatile State state = State.PLUGGED_IN;
    private static volatile boolean running;

    private MacPower() {
    }

    /** The last answer; plugged in and cool until the first, and always off a Mac. */
    public static State state() {
        return state;
    }

    /** Whether the Mac is being asked at all, so the menu can say when it isn't. */
    public static boolean running() {
        return running;
    }

    /** Starts asking, on a Mac, unless the first answer fails. */
    public static synchronized void start() {
        if (!MAC || running) {
            return;
        }
        Reader reader;
        try {
            reader = new Reader();
            state = reader.read();
        } catch (Throwable e) {
            Orchard.LOG.warn("Could not read the Mac's power state; the battery limits are off", e);
            return;
        }
        running = true;
        Thread.ofPlatform().daemon().name("Orchard power").start(() -> {
            while (true) {
                try {
                    Thread.sleep(EVERY_MS);
                    state = reader.read();
                } catch (InterruptedException e) {
                    return;
                } catch (Throwable e) {
                    Orchard.LOG.warn("Stopped reading the Mac's power state", e);
                    running = false;
                    state = State.PLUGGED_IN;
                    return;
                }
            }
        });
    }

    /** Reads the state once. Fails on anything but a Mac. */
    public static State readNow() throws Throwable {
        return new Reader().read();
    }

    /** The calls, looked up once. */
    private static final class Reader {
        private final MethodHandle copyPowerSources;
        private final MethodHandle providingType;
        private final MethodHandle getCString;
        private final MethodHandle release;
        private final MethodHandle isLowPower;
        private final MethodHandle thermalState;
        private final MemorySegment processInfo;
        private final MemorySegment lowPowerSelector;
        private final MemorySegment thermalSelector;

        Reader() throws Throwable {
            Linker linker = Linker.nativeLinker();
            Arena global = Arena.global();
            SymbolLookup objc = SymbolLookup.libraryLookup("/usr/lib/libobjc.A.dylib", global);
            // Loaded for NSProcessInfo; the game has it already, through SDL, but a test does not.
            SymbolLookup.libraryLookup("/System/Library/Frameworks/Foundation.framework/Foundation", global);
            SymbolLookup iokit = SymbolLookup.libraryLookup("/System/Library/Frameworks/IOKit.framework/IOKit", global);
            SymbolLookup cf = SymbolLookup.libraryLookup("/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation", global);

            copyPowerSources = linker.downcallHandle(iokit.findOrThrow("IOPSCopyPowerSourcesInfo"), FunctionDescriptor.of(ADDRESS));
            providingType = linker.downcallHandle(iokit.findOrThrow("IOPSGetProvidingPowerSourceType"), FunctionDescriptor.of(ADDRESS, ADDRESS));
            getCString = linker.downcallHandle(cf.findOrThrow("CFStringGetCString"),
                FunctionDescriptor.of(JAVA_BYTE, ADDRESS, ADDRESS, JAVA_LONG, JAVA_INT));
            release = linker.downcallHandle(cf.findOrThrow("CFRelease"), FunctionDescriptor.ofVoid(ADDRESS));

            MethodHandle getClass = linker.downcallHandle(objc.findOrThrow("objc_getClass"), FunctionDescriptor.of(ADDRESS, ADDRESS));
            MethodHandle selector = linker.downcallHandle(objc.findOrThrow("sel_registerName"), FunctionDescriptor.of(ADDRESS, ADDRESS));
            MemorySegment send = objc.findOrThrow("objc_msgSend");
            MethodHandle sendForObject = linker.downcallHandle(send, FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
            isLowPower = linker.downcallHandle(send, FunctionDescriptor.of(JAVA_BOOLEAN, ADDRESS, ADDRESS));
            thermalState = linker.downcallHandle(send, FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS));

            try (Arena arena = Arena.ofConfined()) {
                MemorySegment type = (MemorySegment) getClass.invokeExact(arena.allocateFrom("NSProcessInfo"));
                if (type.equals(MemorySegment.NULL)) {
                    throw new IllegalStateException("No NSProcessInfo");
                }
                MemorySegment shared = (MemorySegment) selector.invokeExact(arena.allocateFrom("processInfo"));
                processInfo = (MemorySegment) sendForObject.invokeExact(type, shared);
                lowPowerSelector = (MemorySegment) selector.invokeExact(arena.allocateFrom("isLowPowerModeEnabled"));
                thermalSelector = (MemorySegment) selector.invokeExact(arena.allocateFrom("thermalState"));
            }
        }

        State read() throws Throwable {
            boolean lowPower = (boolean) isLowPower.invokeExact(processInfo, lowPowerSelector);
            int thermal = (int) (long) thermalState.invokeExact(processInfo, thermalSelector);
            return new State(onBattery(), lowPower, thermal);
        }

        /** IOKit's providing power source: "AC Power", "Battery Power" or "UPS Power". */
        private boolean onBattery() throws Throwable {
            MemorySegment snapshot = (MemorySegment) copyPowerSources.invokeExact();
            if (snapshot.equals(MemorySegment.NULL)) {
                return false;
            }
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment type = (MemorySegment) providingType.invokeExact(snapshot);
                if (type.equals(MemorySegment.NULL)) {
                    return false;
                }
                MemorySegment text = arena.allocate(64);
                byte ok = (byte) getCString.invokeExact(type, text, 64L, UTF8);
                return ok != 0 && "Battery Power".equals(text.getString(0, StandardCharsets.UTF_8));
            } finally {
                release.invokeExact(snapshot);
            }
        }
    }

    /** For the menu: "On battery, Low Power Mode, hot", or "Plugged in". */
    public static String describe(@Nullable State now) {
        if (!running || now == null) {
            return MAC ? "Not available" : "Only on a Mac";
        }
        StringBuilder text = new StringBuilder(now.battery() ? "On battery" : "Plugged in");
        if (now.lowPower()) {
            text.append(", Low Power Mode");
        }
        text.append(switch (now.thermal()) {
            case 0 -> "";
            case 1 -> ", warm";
            case 2 -> ", hot";
            default -> ", very hot";
        });
        return text.toString();
    }
}
