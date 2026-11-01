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

/**
 * The World page's "Wait out farm changes". Minecraft redraws a whole section for any block
 * change in it (MC-123584), and a farm's cactus grows and breaks a tick later: two redraws that
 * end with the section as it was. So a block change from the server in a section that had one in
 * the last few seconds waits before the section is marked for redrawing; if its blocks go back to
 * what they were meanwhile, the mark is dropped. The player's own changes, and whatever else marks
 * a section (light, chunks arriving), go through at once and end that section's wait, so the
 * redraw reads the blocks as they are by then.
 * <p>
 * Everything here runs on the render thread: ClientLevel's block changes come from packets
 * handled there, and LevelExtractor marks sections and extracts frames there.
 */
public final class FarmChanges {

    /** A change this soon after the last one in its section is a farm's. */
    private static final long BUSY_MS = 5_000;
    /** A waiting section is marked once no change has come for this long, */
    private static final long SETTLE_MS = 100;
    /** or at the latest this long after its first. */
    private static final long MOST_MS = 400;
    private static final long LOG_MS = 30_000;

    /** When each section last had a change from the server. */
    private static final Long2LongOpenHashMap LAST = new Long2LongOpenHashMap();
    /** The sections waiting, by section. */
    private static final Long2ObjectOpenHashMap<Wait> WAITING = new Long2ObjectOpenHashMap<>();
    private static final BlockPos.MutableBlockPos AT = new BlockPos.MutableBlockPos();

    /** The level the waits are in; they go when it does. */
    private static ClientLevel level;
    /** The block change Minecraft is marking sections for, between ClientLevel's hooks; null otherwise. */
    private static BlockPos changing;
    private static BlockState changingFrom;
    private static boolean changingByPlayer;
    /** Marking a section that has waited: not to be held again. */
    private static boolean marking;
    private static long evicted;
    private static long logged;
    private static long held;
    private static long asTheyWere;
    private static long redrawn;

    private FarmChanges() {
    }

    /** A section's wait: the blocks changed in it since, as they were before. */
    private static final class Wait {
        final long since;
        long last;
        final Long2ObjectOpenHashMap<BlockState> before = new Long2ObjectOpenHashMap<>();

        Wait(long now) {
            this.since = now;
            this.last = now;
        }
    }

    /** ClientLevel is about to mark the sections around {@code pos} for a change from {@code from}. */
    public static void begin(BlockPos pos, BlockState from, boolean byPlayer) {
        changing = pos;
        changingFrom = from;
        changingByPlayer = byPlayer;
    }

    public static void end() {
        changing = null;
        changingFrom = null;
    }

    /** Whether the mark of this section is held back: true, and Minecraft doesn't mark it now. */
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
            // (a section already marked is redrawn from the blocks as they are when it is, so it has nothing to wait for)
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

    /** Each frame, before its sections are picked: marks the sections whose wait is over, through {@code mark}. */
    public static void tick(Consumer<SectionPos> mark) {
        // (no block change is being marked between frames: if one ever threw before end(), nothing else is taken for it)
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

    /** What's done with a wait taken out of the map, and the section it was for. */
    interface Finished<T> {
        void accept(long section, T wait);
    }

    /** Takes the waits that are over out of {@code waits}, one at a time. */
    static <T> void takeFinished(Long2ObjectOpenHashMap<T> waits, Predicate<T> over, Finished<T> finished) {
        var entries = waits.long2ObjectEntrySet().fastIterator();
        while (entries.hasNext()) {
            Long2ObjectMap.Entry<T> e = entries.next();
            T wait = e.getValue();
            if (over.test(wait)) {
                // (read before the remove: it moves other keys into this entry's slot, and can shrink the table under it)
                long section = e.getLongKey();
                entries.remove();
                finished.accept(section, wait);
            }
        }
    }

    /** A new level (or none): the old one's waits go with it, as all its sections do. */
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
