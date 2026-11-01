package com.lackothy.orchard.video;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FarmChangesTest {

    /** Random sections in a map whose table has grown, as a farm's waits do; each wait holds its own section. */
    private static Long2ObjectOpenHashMap<Long> waits(Random random) {
        Long2ObjectOpenHashMap<Long> waits = new Long2ObjectOpenHashMap<>();
        int count = 13 + random.nextInt(36);
        for (int i = 0; i < count; i++) {
            long section = random.nextLong() | 1;
            waits.put(section, Long.valueOf(section));
        }
        return waits;
    }

    @Test
    @DisplayName("every finished wait comes out once, with its own section, however the table moves underneath")
    void finishedWaitsKeepTheirSections() {
        Random random = new Random(1);
        for (int run = 0; run < 20_000; run++) {
            Long2ObjectOpenHashMap<Long> waits = waits(random);
            Set<Long> expected = new HashSet<>(waits.values());
            Set<Long> taken = new HashSet<>();
            FarmChanges.takeFinished(waits, wait -> true, (section, wait) -> {
                assertEquals(wait.longValue(), section, "a wait handed over with another section");
                assertTrue(taken.add(section), "a wait handed over twice");
            });
            assertEquals(expected, taken);
            assertTrue(waits.isEmpty());
        }
    }

    @Test
    @DisplayName("waits that aren't over stay, and only those that are come out")
    void unfinishedWaitsStay() {
        Random random = new Random(2);
        for (int run = 0; run < 20_000; run++) {
            Long2ObjectOpenHashMap<Long> waits = waits(random);
            Set<Long> staying = new HashSet<>();
            Set<Long> leaving = new HashSet<>();
            for (Long wait : waits.values()) {
                (Long.bitCount(wait) % 4 == 0 ? staying : leaving).add(wait);
            }
            Set<Long> taken = new HashSet<>();
            FarmChanges.takeFinished(waits, leaving::contains, (section, wait) -> {
                assertEquals(wait.longValue(), section, "a wait handed over with another section");
                taken.add(section);
            });
            assertEquals(leaving, taken);
            assertEquals(staying, new HashSet<>(waits.values()));
            for (Long wait : staying) {
                assertEquals(wait, waits.get(wait.longValue()), "a wait left under another section");
            }
        }
    }
}
