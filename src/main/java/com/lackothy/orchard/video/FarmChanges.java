package com.lackothy.orchard.video;

import com.lackothy.Orchard;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.SectionUpdateTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.state.BlockState;

public final class FarmChanges {

    private static final long BUSY_MS = 5_000;
    private static final long SETTLE_MS = 100;
    private static final long MOST_MS = 400;
    private static final long LOG_MS = 30_000;

    private static final Long2LongOpenHashMap LAST = new Long2LongOpenHashMap();
    private static final Long2ObjectOpenHashMap<Wait> WAITING = new Long2ObjectOpenHashMap<>();
    private static final BlockPos.MutableBlockPos AT = new BlockPos.MutableBlockPos();

    private static ClientLevel level;
    private static BlockPos changing;
    private static BlockState changingFrom;
    private static boolean changingByPlayer;
    private static boolean marking;
    private static long evicted;
    private static long logged;
    private static long held;
    private static long asTheyWere;
    private static long redrawn;

    private FarmChanges() {
    }

    private static final class Wait {
        final long since;
        long last;
        final Long2ObjectOpenHashMap<BlockState> before = new Long2ObjectOpenHashMap<>();

        Wait(long now) {
            this.since = now;
            this.last = now;
        }
    }

    public static void begin(BlockPos pos, BlockState from, boolean byPlayer) {
        changing = pos;
        changingFrom = from;
        changingByPlayer = byPlayer;
    }

    public static void end() {
        changing = null;
        changingFrom = null;
    }

    public static boolean hold(int x, int y, int z, boolean byPlayer, SectionUpdateTracker tracker) {
        if (marking) {
            return false;
        }
        follow(Minecraft.getInstance().level);
        long key = SectionPos.asLong(x, y, z);
        if (changing == null || byPlayer || changingByPlayer || !VideoOptions.FARM_CHANGES.on()) {
            WAITING.remove(key);
            return false;
        }
        long now = now();
        boolean busy = LAST.containsKey(key) && now - LAST.get(key) <= BUSY_MS;
        LAST.put(key, now);
        Wait wait = WAITING.get(key);
        if (wait == null) {
            SectionUpdateTracker.SectionDirtyState state = tracker.getDirtyState(key);
            if (!busy || state == null || state.isDirty()) {
                return false;
            }
            wait = new Wait(now);
            WAITING.put(key, wait);
        }
        wait.last = now;
        wait.before.putIfAbsent(changing.asLong(), changingFrom);
        held++;
        if (asTheyWere(wait)) {
            WAITING.remove(key);
            asTheyWere++;
        }
        return true;
    }

    public static void tick(Consumer<SectionPos> mark) {
        end();
        follow(Minecraft.getInstance().level);
        long now = now();
        if (!WAITING.isEmpty()) {
            boolean on = VideoOptions.FARM_CHANGES.on();
            takeFinished(WAITING, wait -> !on || now - wait.last >= SETTLE_MS || now - wait.since >= MOST_MS, (section, wait) -> {
                if (asTheyWere(wait)) {
                    asTheyWere++;
                } else {
                    marking = true;
                    try {
                        mark.accept(SectionPos.of(section));
                    } finally {
                        marking = false;
                    }
                    redrawn++;
                }
            });
        }
        if (now - evicted >= 1_000) {
            evicted = now;
            LAST.long2LongEntrySet().removeIf(e -> now - e.getLongValue() > BUSY_MS);
        }
        if (now - logged >= LOG_MS) {
            logged = now;
            if (held > 0) {
                Orchard.LOG.info("Farm changes in the last {} s: {} section marks held back, {} sections left as they were, {} redrawn after waiting",
                    LOG_MS / 1000, held, asTheyWere, redrawn);
                held = asTheyWere = redrawn = 0;
            }
        }
    }

    interface Finished<T> {
        void accept(long section, T wait);
    }

    static <T> void takeFinished(Long2ObjectOpenHashMap<T> waits, Predicate<T> over, Finished<T> finished) {
        var entries = waits.long2ObjectEntrySet().fastIterator();
        while (entries.hasNext()) {
            Long2ObjectMap.Entry<T> e = entries.next();
            T wait = e.getValue();
            if (over.test(wait)) {
                long section = e.getLongKey();
                entries.remove();
                finished.accept(section, wait);
            }
        }
    }

    private static void follow(ClientLevel current) {
        if (current != level) {
            level = current;
            WAITING.clear();
            LAST.clear();
        }
    }

    private static boolean asTheyWere(Wait wait) {
        ClientLevel now = Minecraft.getInstance().level;
        if (now == null) {
            return false;
        }
        for (Long2ObjectMap.Entry<BlockState> e : wait.before.long2ObjectEntrySet()) {
            if (now.getBlockState(AT.set(e.getLongKey())) != e.getValue()) {
                return false;
            }
        }
        return true;
    }

    private static long now() {
        return System.nanoTime() / 1_000_000L;
    }
}
