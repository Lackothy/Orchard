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

public final class MacPower {

    public static final boolean MAC = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");

    private static final long EVERY_MS = 2000;
    private static final int UTF8 = 0x08000100;

    public record State(boolean battery, boolean lowPower, int thermal) {
        public static final State PLUGGED_IN = new State(false, false, 0);

        public boolean hot() {
            return thermal >= 2;
        }
    }

    private static volatile State state = State.PLUGGED_IN;
    private static volatile boolean running;

    private MacPower() {
    }

    public static State state() {
        return state;
    }

    public static boolean running() {
        return running;
    }

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

    public static State readNow() throws Throwable {
        return new Reader().read();
    }

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
